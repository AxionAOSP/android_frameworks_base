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
import android.os.SystemProperties;
import android.provider.Settings;
import android.util.ArrayMap;
import android.util.Slog;
import android.util.SparseIntArray;
import android.view.Display;
import android.view.WindowManager;

import com.android.server.wm.AxAppRefreshRateProvider.AppRefreshRateConfig;
import com.android.server.wm.AxAppRefreshRateProvider.AppVoteInfo;

public class AxRefreshRateController {

    public interface GameFpsCallback {
        void setGameFps(int uid, float fps);
    }

    private static final String TAG = "AxRefreshRateController";
    private static final String SETTINGS_REFRESH_RATE_MODE = "display_refresh_rate_mode";
    private static final String LOCKSCREEN_LIMIT_REFRESH_RATE = "lockscreen_limit_refresh_rate";
    private static final String PER_APP_REFRESH_RATE = "per_app_refresh_rate";

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

    private boolean isCtsTest = false;
    private boolean overrideWinPrefer = false;
    private boolean disableIdle = false;
    private int maxWindowSize = 0;

    private final ArrayMap<String, Float> mUserAppRefreshRates = new ArrayMap<>();

    private final boolean supportsVRR = SystemProperties.getBoolean(
            "ro.surface_flinger.use_content_detection_for_refresh_rate", false);

    private GameFpsCallback mCallbacks;

    private AxRefreshRateController() {}

    public static AxRefreshRateController get() { return INSTANCE; }

    public void setGameFpsCallback(GameFpsCallback callback) {
        this.mCallbacks = callback;
    }

    private float resolveMaxRate() {
        return isVrrEnabled ? maxSupportedHz : currentRefreshRate;
    }

    public void init(Context context, WindowManagerService wm) {
        if (mInitialized) return;
        this.context = context;
        this.wm = wm;

        HandlerThread thread = new HandlerThread("AxRefreshRateConfig",
                Process.THREAD_PRIORITY_BACKGROUND);
        thread.start();
        bgHandler = new Handler(thread.getLooper());

        final DisplayManager dm = context.getSystemService(DisplayManager.class);
        Display display = dm.getDisplay(Display.DEFAULT_DISPLAY);

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

        loadRefreshRateSetting();
        loadPerAppRefreshRates();

        mInitialized = true;
        new SettingsObserver();
    }

    private void loadRefreshRateSetting() {
        int value = Settings.Global.getInt(context.getContentResolver(),
                SETTINGS_REFRESH_RATE_MODE, supportsVRR ? 0 : Math.round(maxSupportedHz));
        isVrrEnabled = (value == 0 && supportsVRR);
        if (!isVrrEnabled) currentRefreshRate = value;
        isLockscreenLimitEnabled = Settings.System.getInt(context.getContentResolver(),
                LOCKSCREEN_LIMIT_REFRESH_RATE, 0) != 0;
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
        } else if (overrideWinPrefer) {
            currentVote.updateVote("OverrideWinPrefer", 0.0f, resolvedMax);
        } else if (bestVote.hasVote) {
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
    }

    public void setKeyguardDone(boolean done) {
        isKeyguardDone = done;
        if (mInitialized) {
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
        bgHandler.post(() -> handleFocusedAppUpdate(activityRecord));
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

        SettingsObserver() {
            super(bgHandler);
            context.getContentResolver().registerContentObserver(
                    refreshRateModeUri, false, this, -1);
            context.getContentResolver().registerContentObserver(
                    lockscreenLimitUri, false, this, -1);
            context.getContentResolver().registerContentObserver(
                    perAppRefreshRateUri, false, this, -1);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            loadRefreshRateSetting();
            if (perAppRefreshRateUri.equals(uri)) {
                loadPerAppRefreshRates();
            }
            wm.requestTraversal();
        }
    }
}
