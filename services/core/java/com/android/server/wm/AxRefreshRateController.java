/*
 * Copyright (C) 2025 AxionOS
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
import static android.view.WindowManager.LayoutParams.TYPE_NOTIFICATION_SHADE;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
import static com.android.server.wm.AxAppRefreshRateProvider.DEFAULT_APP_CONFIGS;
import static com.android.server.wm.AxAppRefreshRateProvider.SYS_WINDOW_TYPES;

import com.android.server.wm.AxAppRefreshRateProvider.AppRefreshRateConfig;
import com.android.server.wm.AxAppRefreshRateProvider.AppVoteInfo;

import android.content.Context;
import android.database.ContentObserver;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.util.ArrayMap;
import android.util.Slog;
import android.util.SparseIntArray;
import android.view.Display;
import android.view.WindowManager;

import java.util.Arrays;

public class AxRefreshRateController {

    public interface GameFpsCallback {
        void setGameFps(int uid, float fps);
    }

    private static final String TAG = "AxRefreshRateController";
    private static final String SETTINGS_REFRESH_RATE_MODE = "display_refresh_rate_mode";
    private static final String PEAK_REFRESH_RATE = "peak_refresh_rate";
    private static final String LOCKSCREEN_LIMIT_REFRESH_RATE = "lockscreen_limit_refresh_rate";
    private static final String PER_APP_REFRESH_RATE = "per_app_refresh_rate";

    private static final AxRefreshRateController INSTANCE = new AxRefreshRateController();

    private final SparseIntArray gameUidToFpsMap = new SparseIntArray(10);
    private AppVoteInfo currentVote;
    private AppVoteInfo bestVote;

    private Context context;
    private Handler bgHandler;
    private SettingsObserver settingsObserver;
    private WindowManagerService wm;

    private Display display;
    private float maxSupportedHz = 60f;
    private float defaultMinRefreshRate = 60f;

    private boolean isVrrEnabled = false;
    private boolean isLockscreenLimitEnabled = false;
    private boolean isKeyguardDone = true;

    private static class UserAppConfig {
        float rate;
        UserAppConfig(float rate) {
            this.rate = rate;
        }
    }

    private final ArrayMap<String, UserAppConfig> mUserAppRefreshRates = new ArrayMap<>();
    private int currentRefreshRate = 60;
    private boolean isAppOverrideActive = false;
    private String mFocusedPackage = "";
    
    private boolean isCtsTest = false;
    private boolean overrideWinPrefer = false;
    private boolean lastIdle = false;
    private boolean disableIdle = false;
    private int maxWindowSize = 0;

    private final boolean supportsVRR = SystemProperties.getBoolean(
            "ro.surface_flinger.use_content_detection_for_refresh_rate", false);
    private final boolean supportsIdle = SystemProperties.getBoolean(
            "ro.surface_flinger.support_kernel_idle_timer", false);

    private GameFpsCallback mCallbacks;

    private AxRefreshRateController() {}

    public static AxRefreshRateController get() { return INSTANCE; }

    public void setGameFpsCallback(GameFpsCallback callback) {
        this.mCallbacks = callback;
    }

    public void init(Context context, WindowManagerService wm) {
        this.context = context;
        this.wm = wm;

        HandlerThread thread = new HandlerThread("NtRefreshRateConfig");
        thread.start();
        bgHandler = new Handler(thread.getLooper());
        settingsObserver = new SettingsObserver();

        final DisplayManager dm = context.getSystemService(DisplayManager.class);
        display = dm.getDisplay(Display.DEFAULT_DISPLAY);

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
                Slog.e(TAG, "Failed to parse persist.sys.display_refresh_rates_list: " + customList, e);
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
        updateRefreshRate();

        bestVote = new AppVoteInfo(null, 0, 0.0f, 0.0f, false);
        currentVote = new AppVoteInfo(null, 0, 0.0f, 0.0f, false);
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
        synchronized (mUserAppRefreshRates) {
            mUserAppRefreshRates.clear();
            String config = Settings.System.getString(context.getContentResolver(), PER_APP_REFRESH_RATE);
            if (config != null && !config.isEmpty()) {
                String[] apps = config.split(",");
                for (String app : apps) {
                    String[] parts = app.split(":");
                    if (parts.length >= 2) {
                        try {
                            float rate = Float.parseFloat(parts[1]);
                            mUserAppRefreshRates.put(parts[0], new UserAppConfig(rate));
                        } catch (NumberFormatException e) {
                            Slog.e(TAG, "Failed to parse refresh rate for app: " + app, e);
                        }
                    }
                }
            }
        }
    }

    private void updateRefreshRate() {
        if (isVrrEnabled) {
            Slog.d(TAG, "updateRefreshRate: VRR mode, setting min=" + defaultMinRefreshRate + " peak=" + maxSupportedHz);
            setSystemRefreshRates(defaultMinRefreshRate, maxSupportedHz);
        } else {
            Slog.d(TAG, "updateRefreshRate: Fixed mode, setting min=peak=" + currentRefreshRate);
            setSystemRefreshRates(currentRefreshRate, currentRefreshRate);
        }
    }

    private void restoreSystemDefaults() {
        updateRefreshRate();
    }

    private void setIdleFpsMode(boolean disableIdle) {
        float idleHz = isVrrEnabled ? defaultMinRefreshRate : currentRefreshRate;
        float minHz = disableIdle ? maxSupportedHz : idleHz;
        Slog.d(TAG, "setIdleFpsMode: disableIdle=" + disableIdle + " setting min=" + minHz);
        Settings.System.putFloat(context.getContentResolver(),
                Settings.System.MIN_REFRESH_RATE, minHz);
        wm.requestTraversal();
    }

    private void setSystemRefreshRates(float minRate, float peakRate) {
        Slog.d(TAG, "setSystemRefreshRates: min=" + minRate + " peak=" + peakRate);
        Settings.System.putFloat(context.getContentResolver(), Settings.System.MIN_REFRESH_RATE, minRate);
        Settings.System.putFloat(context.getContentResolver(), PEAK_REFRESH_RATE, peakRate);
    }

    private void handleFocusedAppUpdate(ActivityRecord activityRecord) {
        String pkg = activityRecord.packageName;
        mFocusedPackage = pkg;
        Slog.d(TAG, "handleFocusedAppUpdate: pkg=" + pkg);
        
        UserAppConfig userConfig = null;
        synchronized (mUserAppRefreshRates) {
            userConfig = mUserAppRefreshRates.get(pkg);
        }

        AppRefreshRateConfig config = null;
        if (userConfig == null) {
            config = DEFAULT_APP_CONFIGS.get(pkg);
        }
        
        if (userConfig != null) {
            Slog.d(TAG, "  Found user config: rate=" + userConfig.rate);
        } else if (config != null) {
            Slog.d(TAG, "  Found default config: disableSV=" + config.disableSV + " disableIdle=" + config.disableIdle);
        } else {
            Slog.d(TAG, "  No config found");
        }

        if (userConfig != null || config != null) {
            int uid = activityRecord.getUid();
            int targetFps;
            if (userConfig != null) {
                targetFps = Math.round(userConfig.rate);
            } else if (config != null) {
                int configRate = config.refreshRates.valueAt(0);
                if (configRate == -1) {
                    targetFps = Math.round(maxSupportedHz);
                } else if (configRate == -2) {
                    targetFps = Math.round(defaultMinRefreshRate);
                } else {
                    targetFps = configRate;
                }
            } else {
                targetFps = Math.round(maxSupportedHz);
            }

            synchronized (gameUidToFpsMap) {
                if (gameUidToFpsMap.get(uid, -1) != targetFps) {
                    if (mCallbacks != null) mCallbacks.setGameFps(uid, targetFps);
                    gameUidToFpsMap.put(uid, targetFps);
                }
            }
            disableIdle = (config != null) ? config.disableIdle : false;
        } else {
            disableIdle = false;
        }

        if (userConfig != null && userConfig.rate > 0) {
            float min = userConfig.rate;
            float peak = userConfig.rate;
            Slog.d(TAG, "  Applying per-app override: min=peak=" + userConfig.rate);
            setSystemRefreshRates(min, peak);
            wm.requestTraversal();
            isAppOverrideActive = true;
            disableIdle = false;
            lastIdle = false;
        } else {
            if (isAppOverrideActive) {
                Slog.d(TAG, "  Restoring from per-app override");
                updateRefreshRate();
                wm.requestTraversal();
                isAppOverrideActive = false;
            }
            
            if (supportsIdle && lastIdle != disableIdle) {
                Slog.d(TAG, "  Idle mode change: disableIdle=" + disableIdle);
                setIdleFpsMode(disableIdle);
                lastIdle = disableIdle;
            }
        }
    }

    public void resetNtVoteResult() {
        bestVote.reset();
        isCtsTest = false;
        overrideWinPrefer = false;
        maxWindowSize = 0;
        disableIdle = false;
    }

    public void updateVoteResult() {
        currentVote.reset();
        if (isCtsTest) {
            currentVote.updateVote("CtsTest", 0, 0.0f, 0.0f);
            currentVote.hasVote = false;
        } else if (overrideWinPrefer) {
            int modeId = getModeId(Math.round(maxSupportedHz));
            currentVote.updateVote("OverrideWinPrefer", modeId, 0.0f, maxSupportedHz);
        } else if (bestVote.hasVote) {
            currentVote.copyFrom(bestVote);
        }

        if (isLockscreenLimitEnabled && !isKeyguardDone) {
            if (currentVote.hasVote) {
                if (currentVote.maxRefreshRate > defaultMinRefreshRate) {
                    currentVote.maxRefreshRate = defaultMinRefreshRate;
                    currentVote.preferredModeId = getModeId(Math.round(defaultMinRefreshRate));
                }
            } else {
                currentVote.updateVote("LockscreenLimit", getModeId(Math.round(defaultMinRefreshRate)), 0.0f, defaultMinRefreshRate);
            }
        }

        if (!isAppOverrideActive && supportsIdle && lastIdle != disableIdle) {
            bgHandler.post(() -> setIdleFpsMode(disableIdle));
            lastIdle = disableIdle;
        }
    }

    public void setKeyguardDone(boolean done) {
        isKeyguardDone = done;
        
        if (isLockscreenLimitEnabled) {
            if (!done) {
                setSystemRefreshRates(defaultMinRefreshRate, defaultMinRefreshRate);
            } else {
                restoreSystemDefaults();
            }
        }
        
        wm.requestTraversal();
    }

    public void setGameModeFrameRateOverrideToNtRefreshRate(int uid, float frameRate) {
        synchronized (gameUidToFpsMap) {
            if (gameUidToFpsMap.get(uid, -1) >= 0) {
                gameUidToFpsMap.put(uid, Math.round(frameRate));
            }
        }
    }

    public void updateFocusedApp(final ActivityRecord activityRecord) {
        bgHandler.post(() -> handleFocusedAppUpdate(activityRecord));
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

    public void voteNtPreferredModeId(WindowState ws, boolean displayOn) {
        if (SYS_WINDOW_TYPES.contains(ws.getName())) {
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
        int targetModeId = 0;
        float minRate = 0f;
        
        synchronized (mUserAppRefreshRates) {
            UserAppConfig userConfig = mUserAppRefreshRates.get(pkg);
            if (userConfig != null && userConfig.rate > 0) {
                targetRate = userConfig.rate;
                targetModeId = getModeId(Math.round(targetRate));
                minRate = targetRate;
            } else {
                AppRefreshRateConfig config = DEFAULT_APP_CONFIGS.get(pkg);
                if (config != null) {
                    int configRate = config.refreshRates.valueAt(0);
                    if (configRate == -1) {
                        targetRate = maxSupportedHz;
                    } else if (configRate == -2) {
                        targetRate = defaultMinRefreshRate;
                    } else {
                        targetRate = configRate;
                    }
                    targetModeId = getModeId(Math.round(targetRate));
                    minRate = 0.0f;
                    
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
            targetRate = maxSupportedHz;
            targetModeId = getModeId(Math.round(targetRate));
            disableIdle = true;
        }

        if (targetRate <= 0) {
            return;
        }

        int windowSize = ws.mRequestedWidth * ws.mRequestedHeight;

        if (targetRate > bestVote.maxRefreshRate || 
            (Math.abs(targetRate - bestVote.maxRefreshRate) < 1.0f && windowSize > maxWindowSize)) {
            bestVote.updateVote(pkg, targetModeId, minRate, targetRate);
            maxWindowSize = windowSize;
        }
    }

    public boolean OverrideWinPrefer() {
        return overrideWinPrefer;
    }

    public float getMaxPreferRate() { 
        return currentVote.maxRefreshRate; 
    }

    public float getMinPreferRate() { 
        return currentVote.minRefreshRate; 
    }

    public int getModeId(int rate) {
        Display.Mode mode = new Display.Mode.Builder()
                .setRefreshRate(rate)
                .build();
        return Math.round(mode.getRefreshRate());
    }

    public int getPreferMode() {
        return currentVote.preferredModeId;
    }

    private boolean isDozeState(int state) {
        return state == Display.STATE_DOZE || state == Display.STATE_DOZE_SUSPEND;
    }

    private final class SettingsObserver extends ContentObserver {
        private final Uri refreshRateModeUri =
                Settings.Global.getUriFor(SETTINGS_REFRESH_RATE_MODE);
        private final Uri lockscreenLimitUri =
                Settings.System.getUriFor(LOCKSCREEN_LIMIT_REFRESH_RATE);
        private final Uri perAppRefreshRateUri =
                Settings.System.getUriFor(PER_APP_REFRESH_RATE);

        public SettingsObserver() {
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
            updateRefreshRate();
            wm.requestTraversal();
        }
    }
}
