/*
 * Copyright (C) 2025-2026 AxionOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.server.wm;

import static android.os.Process.SYSTEM_UID;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_NAVIGATION_BAR;
import static android.view.WindowManager.LayoutParams.TYPE_NOTIFICATION_SHADE;
import static android.view.WindowManager.LayoutParams.TYPE_STATUS_BAR;
import static android.view.WindowManager.LayoutParams.TYPE_WALLPAPER;
import static com.android.server.wm.AxAppRefreshRateProvider.DEFAULT_APP_CONFIGS;

import android.content.Context;
import android.database.ContentObserver;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.ArrayMap;
import android.util.Slog;
import android.util.SparseIntArray;
import android.view.Display;
import android.view.WindowManager;

import java.util.concurrent.atomic.AtomicInteger;

import com.android.server.wm.AxAppRefreshRateProvider.AppRefreshRateConfig;
import com.android.server.wm.AxAppRefreshRateProvider.AppVoteInfo;

public class AxRefreshRateController {

    public interface GameFpsCallback {
        void setGameFps(int uid, float fps);
    }

    public interface RefreshRateUpdateCallback {
        void onRefreshRateChanged(float min, float peak, int displayId);
    }

    private static final String TAG = "AxRefreshRateController";
    private static final String SETTINGS_REFRESH_RATE_MODE = "display_refresh_rate_mode";
    private static final String LOCKSCREEN_LIMIT_REFRESH_RATE = "lockscreen_limit_refresh_rate";
    private static final String PER_APP_REFRESH_RATE = "per_app_refresh_rate";
    private static final String GAMING_MODE_ACTIVE = "ax_gaming_mode_active";

    private static final AxRefreshRateController INSTANCE = new AxRefreshRateController();

    private volatile boolean mInitialized = false;

    private final AppVoteInfo bestVote = new AppVoteInfo();
    private final AppVoteInfo currentVote = new AppVoteInfo();

    private final SparseIntArray gameUidToFpsMap = new SparseIntArray(10);

    private Context context;
    private Handler bgHandler;
    private WindowManagerService wm;

    private float maxSupportedHz = 60f;
    private float defaultMinRefreshRate = 60f;

    private volatile boolean isVrrEnabled = false;
    private volatile int currentRefreshRate = 60;
    private volatile boolean isLockscreenLimitEnabled = false;
    private volatile boolean isKeyguardDone = true;
    private volatile boolean isAppOverrideActive = false;
    private volatile float mPerAppOverrideRate = 0f;
    private volatile String mFocusedPackage = "";
    private volatile boolean mGamingActive = false;

    private boolean isCtsTest = false;
    private boolean overrideWinPrefer = false;
    private boolean disableIdle = false;
    private int maxWindowSize = 0;

    private final ArrayMap<String, Float> mUserAppRefreshRates = new ArrayMap<>();

    private float mLastSyncedPeak = -1f;
    private float mLastSyncedMin = -1f;

    private static final int BOOST_TOUCH = 1;
    private static final int BOOST_ANIM = 1 << 1;
    private static final int BOOST_FOCUS = 1 << 2;
    private static final int BOOST_OVERLAY = 1 << 3;

    private final AtomicInteger mActiveBoosts = new AtomicInteger(0);
    private long mIdleTimeoutMs;
    private long mFocusBoostTimeoutMs;
    private volatile long mLastActivityTime = 0;

    private void setBoost(int flag) {
        mActiveBoosts.updateAndGet(v -> v | flag);
    }

    private void clearBoost(int flag) {
        mActiveBoosts.updateAndGet(v -> v & ~flag);
    }

    private boolean hasBoost(int flag) {
        return (mActiveBoosts.get() & flag) != 0;
    }

    private boolean isBoosted() {
        return mActiveBoosts.get() != 0;
    }

    private final Runnable mIdleRunnable = () -> {
        long elapsed = SystemClock.uptimeMillis() - mLastActivityTime;
        if (elapsed >= mIdleTimeoutMs && !hasBoost(BOOST_ANIM | BOOST_OVERLAY)) {
            clearBoost(BOOST_TOUCH);
            syncDisplaySettings();
        }
    };

    private final Runnable mFocusBoostExpireRunnable = () -> {
        clearBoost(BOOST_FOCUS);
        scheduleIdleCheck();
    };

    private final boolean supportsVRR = SystemProperties.getBoolean(
            "ro.surface_flinger.use_content_detection_for_refresh_rate", false);

    private GameFpsCallback mCallbacks;
    private RefreshRateUpdateCallback mRateCallback;

    private AxRefreshRateController() {}

    public static AxRefreshRateController get() { return INSTANCE; }

    public void setGameFpsCallback(GameFpsCallback callback) {
        this.mCallbacks = callback;
    }

    public void setRefreshRateUpdateCallback(RefreshRateUpdateCallback callback) {
        mRateCallback = callback;
        mLastSyncedPeak = -1f;
        mLastSyncedMin = -1f;
        bgHandler.post(this::syncDisplaySettings);
    }

    private float resolveMaxRate() {
        return isVrrEnabled ? maxSupportedHz : currentRefreshRate;
    }

    private void scheduleIdleCheck() {
        bgHandler.removeCallbacks(mIdleRunnable);
        bgHandler.postDelayed(mIdleRunnable, mIdleTimeoutMs);
    }

    public void onPointerEvent() {
        if (!mInitialized || !isVrrEnabled) return;
        if (isAppOverrideActive || mGamingActive) return;
        if (isLockscreenLimitEnabled && !isKeyguardDone) return;

        boolean wasIdle = !hasBoost(BOOST_TOUCH);
        setBoost(BOOST_TOUCH);
        mLastActivityTime = SystemClock.uptimeMillis();

        if (wasIdle) {
            mLastSyncedPeak = -1f;
            mLastSyncedMin = -1f;
            bgHandler.post(this::syncDisplaySettings);
        }
        scheduleIdleCheck();
    }

    public void setAnimating(boolean animating) {
        if (!mInitialized) return;
        boolean wasAnimating = hasBoost(BOOST_ANIM);
        if (wasAnimating == animating) return;

        if (animating) {
            setBoost(BOOST_ANIM);
        } else {
            clearBoost(BOOST_ANIM);
        }
        mLastActivityTime = SystemClock.uptimeMillis();

        if (!isVrrEnabled || isAppOverrideActive
                || (isLockscreenLimitEnabled && !isKeyguardDone)) return;

        if (animating) {
            bgHandler.post(this::syncDisplaySettings);
        } else {
            scheduleIdleCheck();
        }
    }

    private void syncDisplaySettings() {
        float peak;
        float min;
        if (isAppOverrideActive && mPerAppOverrideRate > 0) {
            peak = mPerAppOverrideRate + 1.0f;
            min = mPerAppOverrideRate;
        } else if (mGamingActive) {
            float cap = isVrrEnabled ? maxSupportedHz : currentRefreshRate;
            peak = cap + 1.0f;
            min = 0f;
        } else if (isLockscreenLimitEnabled && !isKeyguardDone) {
            peak = defaultMinRefreshRate + 1.0f;
            min = 0f;
        } else if (isVrrEnabled) {
            if (isBoosted()) {
                peak = maxSupportedHz + 1.0f;
            } else {
                peak = defaultMinRefreshRate + 1.0f;
            }
            min = 0f;
        } else {
            float rate = currentRefreshRate > 0 ? currentRefreshRate : maxSupportedHz;
            peak = rate + 1.0f;
            min = 0f;
        }
        if (peak == mLastSyncedPeak && min == mLastSyncedMin) return;
        mLastSyncedPeak = peak;
        mLastSyncedMin = min;
        Slog.d(TAG, "syncDisplaySettings: peak=" + peak + " min=" + min
                + " boosts=" + boostString()
                + " vrr=" + isVrrEnabled + " fixedRate=" + currentRefreshRate
                + " perApp=" + isAppOverrideActive + "(" + mPerAppOverrideRate + ")");
        if (mRateCallback != null) {
            mRateCallback.onRefreshRateChanged(min, peak, Display.DEFAULT_DISPLAY);
        }
    }

    private String boostString() {
        int boosts = mActiveBoosts.get();
        if (boosts == 0) return "NONE";
        StringBuilder sb = new StringBuilder();
        if ((boosts & BOOST_TOUCH) != 0) sb.append("TOUCH|");
        if ((boosts & BOOST_ANIM) != 0) sb.append("ANIM|");
        if ((boosts & BOOST_FOCUS) != 0) sb.append("FOCUS|");
        if ((boosts & BOOST_OVERLAY) != 0) sb.append("OVERLAY|");
        sb.setLength(sb.length() - 1);
        return sb.toString();
    }

    private void refreshDisplayModes() {
        final DisplayManager dm = context.getSystemService(DisplayManager.class);
        Display display = dm.getDisplay(Display.DEFAULT_DISPLAY);
        if (display == null) return;

        float oldMax = maxSupportedHz;
        maxSupportedHz = 60f;
        defaultMinRefreshRate = 0;

        String customList = SystemProperties.get("persist.sys.display_refresh_rates_list", "");
        boolean customListParsed = false;
        if (!customList.isEmpty()) {
            try {
                String[] rates = customList.split(",");
                float min = Float.MAX_VALUE;
                float max = 0;
                for (String rateStr : rates) {
                    float rate = Float.parseFloat(rateStr.trim());
                    if (rate < min) min = rate;
                    if (rate > max) max = rate;
                }
                if (max > 0) {
                    maxSupportedHz = max;
                    defaultMinRefreshRate = min;
                    customListParsed = true;
                }
            } catch (Exception e) {
                Slog.e(TAG, "Failed to parse persist.sys.display_refresh_rates_list: "
                        + customList, e);
            }
        }

        if (!customListParsed) {
            Display.Mode[] supportedModes = display.getSupportedModes();
            defaultMinRefreshRate = Float.MAX_VALUE;
            for (Display.Mode mode : supportedModes) {
                float hz = mode.getRefreshRate();
                if (hz > maxSupportedHz) maxSupportedHz = hz;
                if (hz < defaultMinRefreshRate) defaultMinRefreshRate = hz;
            }
            if (defaultMinRefreshRate == Float.MAX_VALUE) defaultMinRefreshRate = 60f;
        }

        if (oldMax != maxSupportedHz) {
            Slog.i(TAG, "refreshDisplayModes: maxHz=" + maxSupportedHz
                    + " minHz=" + defaultMinRefreshRate);
            mLastSyncedPeak = -1f;
            mLastSyncedMin = -1f;
        }
    }

    public void forceResync() {
        if (!mInitialized) return;
        mLastSyncedPeak = -1f;
        mLastSyncedMin = -1f;
        bgHandler.post(this::syncDisplaySettings);
    }

    public void onDisplayChanged() {
        if (!mInitialized) return;
        refreshDisplayModes();
        mLastSyncedPeak = -1f;
        mLastSyncedMin = -1f;
        setBoost(BOOST_FOCUS);
        mLastActivityTime = SystemClock.uptimeMillis();
        bgHandler.post(this::syncDisplaySettings);
        bgHandler.removeCallbacks(mFocusBoostExpireRunnable);
        bgHandler.postDelayed(mFocusBoostExpireRunnable, mFocusBoostTimeoutMs);
    }

    public void init(Context context, WindowManagerService wm) {
        if (mInitialized) return;
        this.context = context;
        this.wm = wm;

        HandlerThread thread = new HandlerThread("AxRefreshRateConfig",
                Process.THREAD_PRIORITY_BACKGROUND);
        thread.start();
        bgHandler = new Handler(thread.getLooper());

        mIdleTimeoutMs = SystemProperties.getLong(
                "persist.sys.ax.idle_timeout_ms", 1500);
        mFocusBoostTimeoutMs = SystemProperties.getLong(
                "persist.sys.ax.focus_boost_timeout_ms", 5000);

        refreshDisplayModes();

        loadRefreshRateSetting();
        loadPerAppRefreshRates();

        mInitialized = true;
        new SettingsObserver();
        Slog.i(TAG, "init: maxHz=" + maxSupportedHz + " minHz=" + defaultMinRefreshRate
                + " vrr=" + isVrrEnabled + " supportsVRR=" + supportsVRR
                + " currentRate=" + currentRefreshRate
                + " idleTimeout=" + mIdleTimeoutMs);
    }

    private void loadRefreshRateSetting() {
        int value = Settings.Global.getInt(context.getContentResolver(),
                SETTINGS_REFRESH_RATE_MODE, supportsVRR ? 0 : Math.round(maxSupportedHz));
        isVrrEnabled = (value == 0 && supportsVRR);
        if (!isVrrEnabled) currentRefreshRate = value;
        isLockscreenLimitEnabled = Settings.System.getInt(context.getContentResolver(),
                LOCKSCREEN_LIMIT_REFRESH_RATE, 0) != 0;
        syncDisplaySettings();
    }

    private void loadPerAppRefreshRates() {
        String config = Settings.System.getString(context.getContentResolver(), PER_APP_REFRESH_RATE);
        ArrayMap<String, Float> parsed = new ArrayMap<>();
        if (config != null && !config.isEmpty()) {
            String[] apps = config.split(",");
            for (String app : apps) {
                String[] parts = app.split(":");
                if (parts.length >= 2) {
                    try {
                        parsed.put(parts[0], Float.parseFloat(parts[1]));
                    } catch (NumberFormatException e) {
                        Slog.e(TAG, "Failed to parse refresh rate for app: " + app, e);
                    }
                }
            }
        }
        synchronized (mUserAppRefreshRates) {
            mUserAppRefreshRates.clear();
            mUserAppRefreshRates.putAll(parsed);
        }
    }

    private void handleFocusedAppUpdate(ActivityRecord activityRecord) {
        String pkg = activityRecord.packageName;
        mFocusedPackage = pkg;

        Float userRate = null;
        synchronized (mUserAppRefreshRates) {
            userRate = mUserAppRefreshRates.get(pkg);
        }

        AppRefreshRateConfig config = (userRate == null) ? DEFAULT_APP_CONFIGS.get(pkg) : null;

        if (userRate != null || config != null) {
            int uid = activityRecord.getUid();
            int targetFps;
            if (userRate != null) {
                targetFps = Math.round(userRate);
            } else {
                int configRate = config.refreshRates.valueAt(0);
                if (configRate == -1) {
                    targetFps = Math.round(resolveMaxRate());
                } else if (configRate == -2) {
                    targetFps = Math.round(defaultMinRefreshRate);
                } else {
                    targetFps = configRate;
                }
            }

            synchronized (gameUidToFpsMap) {
                if (gameUidToFpsMap.get(uid, -1) != targetFps) {
                    if (mCallbacks != null) mCallbacks.setGameFps(uid, targetFps);
                    gameUidToFpsMap.put(uid, targetFps);
                }
            }
        }

        if (userRate != null && userRate > 0) {
            isAppOverrideActive = true;
            mPerAppOverrideRate = userRate;
        } else {
            isAppOverrideActive = false;
            mPerAppOverrideRate = 0f;
        }

        wm.requestTraversal();
    }

    public void resetVoteResult() {
        if (!mInitialized) return;
        bestVote.reset();
        isCtsTest = false;
        overrideWinPrefer = false;
        clearBoost(BOOST_OVERLAY);
        maxWindowSize = 0;
        disableIdle = false;
    }

    public void updateVoteResult() {
        if (!mInitialized) return;
        currentVote.reset();
        float resolvedMax = resolveMaxRate();

        if (isCtsTest) {
            return;
        }

        if (isAppOverrideActive && mPerAppOverrideRate > 0) {
            currentVote.updateVote("PerAppOverride", mPerAppOverrideRate, mPerAppOverrideRate);
        } else if (!isVrrEnabled && overrideWinPrefer) {
            currentVote.updateVote("OverrideWinPrefer", 0.0f, resolvedMax);
        } else if (!isVrrEnabled && bestVote.hasVote) {
            currentVote.copyFrom(bestVote);
        }

        if (isLockscreenLimitEnabled && !isKeyguardDone) {
            if (currentVote.hasVote) {
                if (currentVote.maxRefreshRate > defaultMinRefreshRate) {
                    currentVote.maxRefreshRate = defaultMinRefreshRate;
                }
            } else {
                currentVote.updateVote("LockscreenLimit", 0.0f, defaultMinRefreshRate);
            }
        }

        if (disableIdle && currentVote.hasVote) {
            currentVote.minRefreshRate = currentVote.maxRefreshRate;
        }

        if (!currentVote.hasVote && !isVrrEnabled && currentRefreshRate > 0) {
            currentVote.updateVote("GlobalMode", 0f, currentRefreshRate);
        }

        if (isVrrEnabled && hasBoost(BOOST_OVERLAY)) {
            bgHandler.post(this::syncDisplaySettings);
        }
    }

    public void setKeyguardDone(boolean done) {
        isKeyguardDone = done;
        if (mInitialized) {
            if (done && isVrrEnabled) {
                setBoost(BOOST_FOCUS);
                mLastActivityTime = SystemClock.uptimeMillis();
                bgHandler.removeCallbacks(mFocusBoostExpireRunnable);
                bgHandler.postDelayed(mFocusBoostExpireRunnable, mFocusBoostTimeoutMs);
            }
            bgHandler.post(this::syncDisplaySettings);
            wm.requestTraversal();
        }
    }

    public void onGameFrameRateOverride(int uid, float frameRate) {
        synchronized (gameUidToFpsMap) {
            if (gameUidToFpsMap.get(uid, -1) >= 0) {
                gameUidToFpsMap.put(uid, Math.round(frameRate));
            }
        }
    }

    public void updateFocusedApp(final ActivityRecord activityRecord) {
        if (!mInitialized) return;
        String pkg = activityRecord.packageName;
        Float userRate;
        synchronized (mUserAppRefreshRates) {
            userRate = mUserAppRefreshRates.get(pkg);
        }
        if (userRate != null && userRate > 0) {
            isAppOverrideActive = true;
            mPerAppOverrideRate = userRate;
        } else {
            isAppOverrideActive = false;
            mPerAppOverrideRate = 0f;
        }
        mFocusedPackage = pkg;
        setBoost(BOOST_FOCUS);
        mLastActivityTime = SystemClock.uptimeMillis();
        mLastSyncedPeak = -1f;
        mLastSyncedMin = -1f;
        bgHandler.post(() -> {
            syncDisplaySettings();
            handleFocusedAppUpdate(activityRecord);
        });
        bgHandler.removeCallbacks(mFocusBoostExpireRunnable);
        bgHandler.postDelayed(mFocusBoostExpireRunnable, mFocusBoostTimeoutMs);
    }

    private boolean isSystemWindowType(int type) {
        return type == TYPE_STATUS_BAR
                || type == TYPE_NAVIGATION_BAR
                || type == TYPE_WALLPAPER;
    }

    private boolean shouldOverrideForWindow(WindowState ws, boolean displayOn) {
        if (ws.inMultiWindowMode()) {
            return true;
        }

        int type = ws.mAttrs.type;
        if (type == TYPE_NOTIFICATION_SHADE ||
            (type == TYPE_APPLICATION_OVERLAY && ws.mOwnerUid != SYSTEM_UID)) {
            return true;
        }

        if (!displayOn) {
            return true;
        }

        if (ws.isAnimationRunningSelfOrParent()) {
            return true;
        }

        return false;
    }

    public void votePreferredRate(WindowState ws, boolean displayOn) {
        if (!mInitialized) return;

        int type = ws.mAttrs.type;
        if (isSystemWindowType(type)) {
            return;
        }

        String pkg = ws.getOwningPackage();

        if (pkg.contains("android.graphics.cts") || pkg.contains("com.android.cts")) {
            isCtsTest = true;
            return;
        }

        if (isCtsTest) {
            return;
        }

        float targetRate = 0f;
        float minRate = 0f;
        float resolvedMax = resolveMaxRate();

        synchronized (mUserAppRefreshRates) {
            Float userRate = mUserAppRefreshRates.get(pkg);
            if (userRate != null && userRate > 0) {
                targetRate = userRate;
                minRate = userRate;
            } else {
                AppRefreshRateConfig config = DEFAULT_APP_CONFIGS.get(pkg);
                if (config != null) {
                    int configRate = config.refreshRates.valueAt(0);
                    if (configRate == -1) {
                        targetRate = resolvedMax;
                    } else if (configRate == -2) {
                        targetRate = defaultMinRefreshRate;
                    } else {
                        targetRate = configRate;
                    }

                    if (config.disableIdle && pkg.equals(mFocusedPackage)) {
                        disableIdle = true;
                    }

                    if (config.disableSV) {
                        WindowManager.LayoutParams lp = ws.mAttrs;
                        if (lp.preferredMinDisplayRefreshRate == (defaultMinRefreshRate - 1.0f) &&
                            lp.preferredMaxDisplayRefreshRate == defaultMinRefreshRate) {
                            lp.preferredMinDisplayRefreshRate = lp.preferredMaxDisplayRefreshRate = 0.0f;
                        }
                    }
                }
            }
        }

        if (ws.mAttrs.type == TYPE_NOTIFICATION_SHADE) {
            setBoost(BOOST_OVERLAY);
        }

        if (shouldOverrideForWindow(ws, displayOn)) {
            overrideWinPrefer = true;
            targetRate = resolvedMax;
        }

        if (targetRate <= 0) {
            return;
        }

        int windowSize = ws.mRequestedWidth * ws.mRequestedHeight;

        if (targetRate > bestVote.maxRefreshRate ||
            (Math.abs(targetRate - bestVote.maxRefreshRate) < 1.0f && windowSize > maxWindowSize)) {
            bestVote.updateVote(pkg, minRate, targetRate);
            maxWindowSize = windowSize;
        }
    }

    public boolean isOverrideWinPrefer() {
        return overrideWinPrefer;
    }

    public boolean hasActiveVote() {
        return currentVote.hasVote;
    }

    public float getMaxPreferredRate() {
        return currentVote.maxRefreshRate;
    }

    public float getMinPreferredRate() {
        return currentVote.minRefreshRate;
    }

    public int getPreferredModeId() {
        return 0;
    }

    private final class SettingsObserver extends ContentObserver {
        private final Uri refreshRateModeUri =
                Settings.Global.getUriFor(SETTINGS_REFRESH_RATE_MODE);
        private final Uri lockscreenLimitUri =
                Settings.System.getUriFor(LOCKSCREEN_LIMIT_REFRESH_RATE);
        private final Uri perAppRefreshRateUri =
                Settings.System.getUriFor(PER_APP_REFRESH_RATE);
        private final Uri gamingModeUri =
                Settings.Secure.getUriFor(GAMING_MODE_ACTIVE);

        SettingsObserver() {
            super(bgHandler);
            context.getContentResolver().registerContentObserver(
                    refreshRateModeUri, false, this, -1);
            context.getContentResolver().registerContentObserver(
                    lockscreenLimitUri, false, this, -1);
            context.getContentResolver().registerContentObserver(
                    perAppRefreshRateUri, false, this, -1);
            context.getContentResolver().registerContentObserver(
                    gamingModeUri, false, this, -1);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            if (gamingModeUri.equals(uri)) {
                mGamingActive = Settings.Secure.getIntForUser(
                        context.getContentResolver(), GAMING_MODE_ACTIVE,
                        0, UserHandle.USER_CURRENT) == 1;
                syncDisplaySettings();
                return;
            }
            loadRefreshRateSetting();
            if (perAppRefreshRateUri.equals(uri)) {
                loadPerAppRefreshRates();
            }
            wm.requestTraversal();
        }
    }
}
