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
import android.view.Display;
import android.view.WindowManager;

import com.android.internal.annotations.GuardedBy;

import java.util.concurrent.atomic.AtomicInteger;

import com.android.server.wm.AxAppRefreshRateProvider.AppRefreshRateConfig;
import com.android.server.wm.AxAppRefreshRateProvider.AppVoteInfo;

public class AxRefreshRateController {

    // ──────────────────────────────────────────────────────────────────────
    // Interfaces
    // ──────────────────────────────────────────────────────────────────────

    public interface RefreshRateUpdateCallback {
        void onRefreshRateChanged(float min, float peak, int displayId);
    }

    // ──────────────────────────────────────────────────────────────────────
    // Constants
    // ──────────────────────────────────────────────────────────────────────

    private static final String TAG = "AxRefreshRateController";

    private static final String SETTINGS_REFRESH_RATE_MODE = "display_refresh_rate_mode";
    private static final String LOCKSCREEN_LIMIT_REFRESH_RATE = "lockscreen_limit_refresh_rate";
    private static final String PER_APP_REFRESH_RATE = "per_app_refresh_rate";
    private static final String GAMING_MODE_ACTIVE = "ax_gaming_mode_active";

    private static final int BOOST_TOUCH = 1;
    private static final int BOOST_ANIM = 1 << 1;
    private static final int BOOST_FOCUS = 1 << 2;
    private static final int BOOST_OVERLAY = 1 << 3;

    private static final long DISPLAY_CHANGE_REQUERY_DELAY_MS = 500;

    // ──────────────────────────────────────────────────────────────────────
    // Static fields
    // ──────────────────────────────────────────────────────────────────────

    private static final AxRefreshRateController sInstance = new AxRefreshRateController();

    private static final boolean sSupportsVrr = SystemProperties.getBoolean(
            "ro.surface_flinger.use_content_detection_for_refresh_rate", false);

    // ──────────────────────────────────────────────────────────────────────
    // Core dependencies
    // ──────────────────────────────────────────────────────────────────────

    private Context mContext;
    private Handler mBgHandler;
    private WindowManagerService mWmService;
    private RefreshRateUpdateCallback mRateCallback;
    private volatile boolean mInitialized = false;

    // ──────────────────────────────────────────────────────────────────────
    // Display state
    // ──────────────────────────────────────────────────────────────────────

    private float mMaxSupportedHz = 60f;
    private float mDefaultMinHz = 60f;

    // ──────────────────────────────────────────────────────────────────────
    // Mode state — written on bgHandler or WM thread, read from both
    // ──────────────────────────────────────────────────────────────────────

    private volatile boolean mVrrEnabled = false;
    private volatile int mFixedRefreshRate = 60;
    private volatile boolean mLockscreenLimitEnabled = false;
    private volatile boolean mKeyguardDone = true;
    private volatile boolean mAppOverrideActive = false;
    private volatile float mPerAppOverrideRate = 0f;
    private volatile String mFocusedPackage = "";
    private volatile boolean mGamingActive = false;

    // ──────────────────────────────────────────────────────────────────────
    // Boost state
    // ──────────────────────────────────────────────────────────────────────

    private final AtomicInteger mActiveBoosts = new AtomicInteger(0);
    private long mIdleTimeoutMs;
    private long mFocusBoostTimeoutMs;
    private volatile long mLastActivityTime = 0;

    // ──────────────────────────────────────────────────────────────────────
    // Vote state — accessed on WM thread during traversal
    // ──────────────────────────────────────────────────────────────────────

    private final AppVoteInfo mBestVote = new AppVoteInfo();
    private final AppVoteInfo mCurrentVote = new AppVoteInfo();
    private boolean mCtsTest = false;
    private boolean mOverrideWinPrefer = false;
    private boolean mDisableIdle = false;
    private int mMaxWindowSize = 0;
    private float mCachedResolvedMax = 60f;
    private volatile boolean mHasUserAppRates = false;

    @GuardedBy("mUserAppRefreshRates")
    private final ArrayMap<String, Float> mUserAppRefreshRates = new ArrayMap<>();

    // ──────────────────────────────────────────────────────────────────────
    // Sync dedup state — accessed on bgHandler
    // ──────────────────────────────────────────────────────────────────────

    private float mLastSyncedPeak = -1f;
    private float mLastSyncedMin = -1f;

    // ──────────────────────────────────────────────────────────────────────
    // Runnables
    // ──────────────────────────────────────────────────────────────────────

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

    private final Runnable mDisplayChangeRequeryRunnable = () -> {
        refreshDisplayModes();
        mLastSyncedPeak = -1f;
        mLastSyncedMin = -1f;
        syncDisplaySettings();
    };

    // ──────────────────────────────────────────────────────────────────────
    // Constructor + singleton
    // ──────────────────────────────────────────────────────────────────────

    private AxRefreshRateController() {}

    public static AxRefreshRateController getInstance() { return sInstance; }

    public void init(Context context, WindowManagerService wms) {
        if (mInitialized) return;
        mContext = context;
        mWmService = wms;

        HandlerThread thread = new HandlerThread("AxRefreshRateConfig",
                Process.THREAD_PRIORITY_BACKGROUND);
        thread.start();
        mBgHandler = new Handler(thread.getLooper());

        mIdleTimeoutMs = SystemProperties.getLong(
                "persist.sys.ax.idle_timeout_ms", 1500);
        mFocusBoostTimeoutMs = SystemProperties.getLong(
                "persist.sys.ax.focus_boost_timeout_ms", 5000);

        refreshDisplayModes();

        loadRefreshRateSetting();
        loadPerAppRefreshRates();

        mInitialized = true;
        new SettingsObserver();
        Slog.i(TAG, "init: maxHz=" + mMaxSupportedHz + " minHz=" + mDefaultMinHz
                + " vrr=" + mVrrEnabled + " supportsVRR=" + sSupportsVrr
                + " fixedRate=" + mFixedRefreshRate
                + " idleTimeout=" + mIdleTimeoutMs);
    }

    // ──────────────────────────────────────────────────────────────────────
    // Public API — callbacks
    // ──────────────────────────────────────────────────────────────────────

    public void setRefreshRateUpdateCallback(RefreshRateUpdateCallback callback) {
        mRateCallback = callback;
        mLastSyncedPeak = -1f;
        mLastSyncedMin = -1f;
        mBgHandler.post(this::syncDisplaySettings);
    }

    // ──────────────────────────────────────────────────────────────────────
    // Public API — input & animation events
    // ──────────────────────────────────────────────────────────────────────

    public void onPointerEvent() {
        if (!mInitialized || !mVrrEnabled) return;
        if (mAppOverrideActive || mGamingActive) return;
        if (mLockscreenLimitEnabled && !mKeyguardDone) return;

        boolean wasIdle = !hasBoost(BOOST_TOUCH);
        setBoost(BOOST_TOUCH);
        mLastActivityTime = SystemClock.uptimeMillis();

        if (wasIdle) {
            mLastSyncedPeak = -1f;
            mLastSyncedMin = -1f;
            mBgHandler.post(this::syncDisplaySettings);
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

        if (!mVrrEnabled || mAppOverrideActive
                || (mLockscreenLimitEnabled && !mKeyguardDone)) return;

        if (animating) {
            mBgHandler.post(this::syncDisplaySettings);
        } else {
            scheduleIdleCheck();
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Public API — system state changes
    // ──────────────────────────────────────────────────────────────────────

    public void setKeyguardDone(boolean done) {
        mKeyguardDone = done;
        if (mInitialized) {
            if (done && mVrrEnabled) {
                setBoost(BOOST_FOCUS);
                mLastActivityTime = SystemClock.uptimeMillis();
                mBgHandler.removeCallbacks(mFocusBoostExpireRunnable);
                mBgHandler.postDelayed(mFocusBoostExpireRunnable, mFocusBoostTimeoutMs);
            }
            mBgHandler.post(this::syncDisplaySettings);
            mWmService.requestTraversal();
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
            mAppOverrideActive = true;
            mPerAppOverrideRate = userRate;
        } else {
            mAppOverrideActive = false;
            mPerAppOverrideRate = 0f;
        }
        mFocusedPackage = pkg;
        setBoost(BOOST_FOCUS);
        mLastActivityTime = SystemClock.uptimeMillis();
        mLastSyncedPeak = -1f;
        mLastSyncedMin = -1f;
        mBgHandler.post(() -> {
            syncDisplaySettings();
            mWmService.requestTraversal();
        });
        mBgHandler.removeCallbacks(mFocusBoostExpireRunnable);
        mBgHandler.postDelayed(mFocusBoostExpireRunnable, mFocusBoostTimeoutMs);
    }

    public void onDisplayChanged() {
        if (!mInitialized) return;
        refreshDisplayModes();
        mLastSyncedPeak = -1f;
        mLastSyncedMin = -1f;

        mBgHandler.removeCallbacks(mDisplayChangeRequeryRunnable);
        mBgHandler.post(this::syncDisplaySettings);
        mBgHandler.postDelayed(mDisplayChangeRequeryRunnable, DISPLAY_CHANGE_REQUERY_DELAY_MS);
    }

    public void forceResync() {
        if (!mInitialized) return;
        mLastSyncedPeak = -1f;
        mLastSyncedMin = -1f;
        mBgHandler.post(this::syncDisplaySettings);
    }

    // ──────────────────────────────────────────────────────────────────────
    // Public API — vote cycle (called on WM thread during traversal)
    // ──────────────────────────────────────────────────────────────────────

    public void resetVoteResult() {
        if (!mInitialized) return;
        mBestVote.reset();
        mCtsTest = false;
        mOverrideWinPrefer = false;
        boolean hadOverlay = hasBoost(BOOST_OVERLAY);
        clearBoost(BOOST_OVERLAY);
        if (hadOverlay && hasBoost(BOOST_TOUCH) && !hasBoost(BOOST_ANIM)) {
            mBgHandler.post(this::scheduleIdleCheck);
        }
        mMaxWindowSize = 0;
        mDisableIdle = false;
        mCachedResolvedMax = resolveMaxRate();
    }

    public void votePreferredRate(WindowState ws, boolean displayOn) {
        if (!mInitialized) return;
        if (mCtsTest) return;

        int type = ws.mAttrs.type;
        if (isSystemWindowType(type)) {
            return;
        }

        if (type == TYPE_NOTIFICATION_SHADE) {
            setBoost(BOOST_OVERLAY);
        }

        if (shouldOverrideForWindow(ws, type, displayOn)) {
            mOverrideWinPrefer = true;
            float resolvedMax = mCachedResolvedMax;
            int windowSize = ws.mRequestedWidth * ws.mRequestedHeight;
            if (resolvedMax > mBestVote.maxRefreshRate ||
                (Math.abs(resolvedMax - mBestVote.maxRefreshRate) < 1.0f && windowSize > mMaxWindowSize)) {
                mBestVote.updateVote(ws.mAttrs.packageName, 0f, resolvedMax);
                mMaxWindowSize = windowSize;
            }
            return;
        }

        String pkg = ws.mAttrs.packageName;

        if (pkg.contains("android.graphics.cts") || pkg.contains("com.android.cts")) {
            mCtsTest = true;
            return;
        }

        float targetRate = 0f;
        float minRate = 0f;

        if (mHasUserAppRates) {
            synchronized (mUserAppRefreshRates) {
                Float userRate = mUserAppRefreshRates.get(pkg);
                if (userRate != null && userRate > 0) {
                    targetRate = userRate;
                    minRate = userRate;
                }
            }
        }

        if (targetRate == 0f) {
            AppRefreshRateConfig config = DEFAULT_APP_CONFIGS.get(pkg);
            if (config != null) {
                int configRate = config.refreshRates.valueAt(0);
                if (configRate == -1) {
                    targetRate = mCachedResolvedMax;
                } else if (configRate == -2) {
                    targetRate = mDefaultMinHz;
                } else {
                    targetRate = configRate;
                }

                if (config.disableIdle && pkg.equals(mFocusedPackage)) {
                    mDisableIdle = true;
                }

                if (config.disableSV) {
                    WindowManager.LayoutParams lp = ws.mAttrs;
                    if (lp.preferredMinDisplayRefreshRate == (mDefaultMinHz - 1.0f) &&
                        lp.preferredMaxDisplayRefreshRate == mDefaultMinHz) {
                        lp.preferredMinDisplayRefreshRate = lp.preferredMaxDisplayRefreshRate = 0.0f;
                    }
                }
            }
        }

        if (targetRate <= 0) {
            return;
        }

        int windowSize = ws.mRequestedWidth * ws.mRequestedHeight;

        if (targetRate > mBestVote.maxRefreshRate ||
            (Math.abs(targetRate - mBestVote.maxRefreshRate) < 1.0f && windowSize > mMaxWindowSize)) {
            mBestVote.updateVote(pkg, minRate, targetRate);
            mMaxWindowSize = windowSize;
        }
    }

    public void updateVoteResult() {
        if (!mInitialized) return;
        mCurrentVote.reset();
        float resolvedMax = resolveMaxRate();

        if (mCtsTest) {
            return;
        }

        if (mAppOverrideActive && mPerAppOverrideRate > 0) {
            mCurrentVote.updateVote("PerAppOverride", mPerAppOverrideRate, mPerAppOverrideRate);
        } else if (!mVrrEnabled && mOverrideWinPrefer) {
            mCurrentVote.updateVote("OverrideWinPrefer", (float) mFixedRefreshRate, resolvedMax);
        } else if (!mVrrEnabled && mBestVote.hasVote) {
            mCurrentVote.copyFrom(mBestVote);
        }

        if (mLockscreenLimitEnabled && !mKeyguardDone) {
            if (mCurrentVote.hasVote) {
                if (mCurrentVote.maxRefreshRate > mDefaultMinHz) {
                    mCurrentVote.maxRefreshRate = mDefaultMinHz;
                }
            } else {
                mCurrentVote.updateVote("LockscreenLimit", 0.0f, mDefaultMinHz);
            }
        }

        if (mDisableIdle && mCurrentVote.hasVote) {
            mCurrentVote.minRefreshRate = mCurrentVote.maxRefreshRate;
        }

        if (!mCurrentVote.hasVote && !mVrrEnabled && mFixedRefreshRate > 0) {
            mCurrentVote.updateVote("GlobalMode", (float) mFixedRefreshRate, mFixedRefreshRate);
        }

        if (mVrrEnabled && hasBoost(BOOST_OVERLAY)) {
            mBgHandler.post(this::syncDisplaySettings);
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Public API — vote accessors
    // ──────────────────────────────────────────────────────────────────────

    public boolean hasActiveVote() {
        return mCurrentVote.hasVote;
    }

    public float getMaxPreferredRate() {
        return mCurrentVote.maxRefreshRate;
    }

    public float getMinPreferredRate() {
        return mCurrentVote.minRefreshRate;
    }

    public int getPreferredModeId() {
        return 0;
    }

    // ──────────────────────────────────────────────────────────────────────
    // Private — boost helpers
    // ──────────────────────────────────────────────────────────────────────

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

    private void scheduleIdleCheck() {
        mBgHandler.removeCallbacks(mIdleRunnable);
        mBgHandler.postDelayed(mIdleRunnable, mIdleTimeoutMs);
    }

    // ──────────────────────────────────────────────────────────────────────
    // Private — display mode queries
    // ──────────────────────────────────────────────────────────────────────

    private float resolveMaxRate() {
        return mVrrEnabled ? mMaxSupportedHz : mFixedRefreshRate;
    }

    private void refreshDisplayModes() {
        final DisplayManager dm = mContext.getSystemService(DisplayManager.class);
        Display display = dm.getDisplay(Display.DEFAULT_DISPLAY);
        if (display == null) return;

        float newMax = 60f;
        float newMin = 0f;

        String customList = SystemProperties.get("persist.sys.display_refresh_rates_list", "");
        boolean customListParsed = false;
        if (!customList.isEmpty()) {
            try {
                String[] rates = customList.split(",");
                float parsedMin = Float.MAX_VALUE;
                float parsedMax = 0;
                for (String rateStr : rates) {
                    float rate = Float.parseFloat(rateStr.trim());
                    if (rate < parsedMin) parsedMin = rate;
                    if (rate > parsedMax) parsedMax = rate;
                }
                if (parsedMax > 0) {
                    newMax = parsedMax;
                    newMin = parsedMin;
                    customListParsed = true;
                }
            } catch (Exception e) {
                Slog.e(TAG, "Failed to parse persist.sys.display_refresh_rates_list: "
                        + customList, e);
            }
        }

        if (!customListParsed) {
            Display.Mode[] supportedModes = display.getSupportedModes();
            newMin = Float.MAX_VALUE;
            for (Display.Mode mode : supportedModes) {
                float hz = mode.getRefreshRate();
                if (hz > newMax) newMax = hz;
                if (hz < newMin) newMin = hz;
            }
            if (newMin == Float.MAX_VALUE) newMin = 60f;
        }

        if (newMax <= 0 || newMax < newMin) return;

        float oldMax = mMaxSupportedHz;
        mMaxSupportedHz = newMax;
        mDefaultMinHz = newMin;

        if (oldMax != mMaxSupportedHz) {
            Slog.i(TAG, "refreshDisplayModes: maxHz=" + mMaxSupportedHz
                    + " minHz=" + mDefaultMinHz);
            mLastSyncedPeak = -1f;
            mLastSyncedMin = -1f;
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Private — display sync
    // ──────────────────────────────────────────────────────────────────────

    private void syncDisplaySettings() {
        float peak;
        float min;
        if (mAppOverrideActive && mPerAppOverrideRate > 0) {
            peak = mPerAppOverrideRate + 1.0f;
            min = mPerAppOverrideRate;
        } else if (mGamingActive) {
            float cap = mVrrEnabled ? mMaxSupportedHz : mFixedRefreshRate;
            peak = cap + 1.0f;
            min = 0f;
        } else if (mLockscreenLimitEnabled && !mKeyguardDone) {
            peak = mDefaultMinHz + 1.0f;
            min = 0f;
        } else if (mVrrEnabled) {
            if (isBoosted()) {
                peak = mMaxSupportedHz + 1.0f;
            } else {
                peak = mDefaultMinHz + 1.0f;
            }
            min = 0f;
        } else {
            float rate = mFixedRefreshRate > 0 ? mFixedRefreshRate : mMaxSupportedHz;
            peak = rate + 1.0f;
            min = rate;
        }
        if (peak == mLastSyncedPeak && min == mLastSyncedMin) return;
        mLastSyncedPeak = peak;
        mLastSyncedMin = min;
        Slog.d(TAG, "syncDisplaySettings: peak=" + peak + " min=" + min
                + " boosts=" + boostString()
                + " vrr=" + mVrrEnabled + " fixedRate=" + mFixedRefreshRate
                + " perApp=" + mAppOverrideActive + "(" + mPerAppOverrideRate + ")");
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

    // ──────────────────────────────────────────────────────────────────────
    // Private — settings loading
    // ──────────────────────────────────────────────────────────────────────

    private void loadRefreshRateSetting() {
        int value = Settings.Global.getInt(mContext.getContentResolver(),
                SETTINGS_REFRESH_RATE_MODE, sSupportsVrr ? 0 : Math.round(mMaxSupportedHz));
        mVrrEnabled = (value == 0 && sSupportsVrr);
        if (!mVrrEnabled) mFixedRefreshRate = value > 0 ? value : Math.round(mMaxSupportedHz);
        mLockscreenLimitEnabled = Settings.System.getInt(mContext.getContentResolver(),
                LOCKSCREEN_LIMIT_REFRESH_RATE, 0) != 0;
        syncDisplaySettings();
    }

    private void loadPerAppRefreshRates() {
        String config = Settings.System.getString(mContext.getContentResolver(), PER_APP_REFRESH_RATE);
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
            mHasUserAppRates = !mUserAppRefreshRates.isEmpty();
        }
    }

    private void refreshFocusedAppOverride() {
        String pkg = mFocusedPackage;
        if (pkg == null || pkg.isEmpty()) return;
        Float userRate;
        synchronized (mUserAppRefreshRates) {
            userRate = mUserAppRefreshRates.get(pkg);
        }
        if (userRate != null && userRate > 0) {
            mAppOverrideActive = true;
            mPerAppOverrideRate = userRate;
        } else {
            mAppOverrideActive = false;
            mPerAppOverrideRate = 0f;
        }
        mLastSyncedPeak = -1f;
        mLastSyncedMin = -1f;
        syncDisplaySettings();
        mWmService.requestTraversal();
    }

    // ──────────────────────────────────────────────────────────────────────
    // Private — window helpers
    // ──────────────────────────────────────────────────────────────────────

    private boolean isSystemWindowType(int type) {
        return type == TYPE_STATUS_BAR
                || type == TYPE_NAVIGATION_BAR
                || type == TYPE_WALLPAPER;
    }

    private boolean shouldOverrideForWindow(WindowState ws, int type, boolean displayOn) {
        if (!displayOn) {
            return true;
        }

        if (type == TYPE_NOTIFICATION_SHADE ||
            (type == TYPE_APPLICATION_OVERLAY && ws.mOwnerUid != SYSTEM_UID)) {
            return true;
        }

        if (ws.inMultiWindowMode()) {
            return true;
        }

        if (ws.isAnimationRunningSelfOrParent()) {
            return true;
        }

        return false;
    }

    // ──────────────────────────────────────────────────────────────────────
    // Inner class — settings observer
    // ──────────────────────────────────────────────────────────────────────

    private final class SettingsObserver extends ContentObserver {
        private final Uri mRefreshRateModeUri =
                Settings.Global.getUriFor(SETTINGS_REFRESH_RATE_MODE);
        private final Uri mLockscreenLimitUri =
                Settings.System.getUriFor(LOCKSCREEN_LIMIT_REFRESH_RATE);
        private final Uri mPerAppRefreshRateUri =
                Settings.System.getUriFor(PER_APP_REFRESH_RATE);
        private final Uri mGamingModeUri =
                Settings.Secure.getUriFor(GAMING_MODE_ACTIVE);

        SettingsObserver() {
            super(mBgHandler);
            mContext.getContentResolver().registerContentObserver(
                    mRefreshRateModeUri, false, this, -1);
            mContext.getContentResolver().registerContentObserver(
                    mLockscreenLimitUri, false, this, -1);
            mContext.getContentResolver().registerContentObserver(
                    mPerAppRefreshRateUri, false, this, -1);
            mContext.getContentResolver().registerContentObserver(
                    mGamingModeUri, false, this, -1);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            if (mGamingModeUri.equals(uri)) {
                mGamingActive = Settings.Secure.getIntForUser(
                        mContext.getContentResolver(), GAMING_MODE_ACTIVE,
                        0, UserHandle.USER_CURRENT) == 1;
                mLastSyncedPeak = -1f;
                mLastSyncedMin = -1f;
                syncDisplaySettings();
                mWmService.requestTraversal();
                return;
            }
            loadRefreshRateSetting();
            if (mPerAppRefreshRateUri.equals(uri)) {
                loadPerAppRefreshRates();
                refreshFocusedAppOverride();
                return;
            }
            mWmService.requestTraversal();
        }
    }
}
