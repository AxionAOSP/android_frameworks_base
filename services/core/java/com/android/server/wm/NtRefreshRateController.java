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
import static com.android.server.wm.NtAppRefreshRateProvider.DEFAULT_APP_CONFIGS;
import static com.android.server.wm.NtAppRefreshRateProvider.GAMING_APPS;
import static com.android.server.wm.NtAppRefreshRateProvider.SYS_WINDOW_TYPES;

import com.android.server.wm.NtAppRefreshRateProvider.AppRefreshRateConfig;
import com.android.server.wm.NtAppRefreshRateProvider.AppVoteInfo;

import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.util.*;
import android.view.*;

import java.util.*;

public class NtRefreshRateController {

    public interface GameFpsCallback {
        void setGameFps(int uid, float fps);
    }

    private static final String TAG = "NtRefreshRateController";
    private static final String SETTINGS_REFRESH_RATE_MODE = "display_refresh_rate_mode";
    private static final String PEAK_REFRESH_RATE = "peak_refresh_rate";

    private static final NtRefreshRateController INSTANCE = new NtRefreshRateController();

    private final Map<Integer, Display.Mode> hzToDisplayMode = new HashMap<>();
    private final Map<Integer, Display.Mode> modeIdToDisplayMode = new HashMap<>();

    private final SparseIntArray gameUidToFpsMap = new SparseIntArray(10);
    private AppVoteInfo currentAppVote;
    private AppVoteInfo bestAppVote;

    private Display.Mode[] supportedDisplayModes;

    private Context context;
    private Handler bgHandler;
    private SettingsObserver settingsObserver;
    private WindowManagerService wm;
    private DisplayInfo displayInfo;

    private int maxSupportedHz;
    private int defaultModeId;
    private float defaultMinRefreshRate;

    private boolean isVrrEnabled = false;
    private int currentRefreshRate = 60;
    private int modeId;
    private int maxWindowSize = 0;

    private final boolean supportsVRR = SystemProperties.getBoolean(
            "ro.surface_flinger.use_content_detection_for_refresh_rate", false);
    private final boolean supportsIdle = SystemProperties.getBoolean(
            "ro.surface_flinger.support_kernel_idle_timer", false);

    private boolean overrideWinPref = false;
    private boolean lastIdle = false;
    private boolean disableIdle = false;

    private GameFpsCallback mCallbacks;

    private NtRefreshRateController() {}

    public static NtRefreshRateController get() { return INSTANCE; }

    public void setGameFpsCallback(GameFpsCallback callback) {
        this.mCallbacks = callback;
    }

    public void init(Context context, DisplayInfo displayInfo, WindowManagerService wm) {
        this.context = context;
        this.displayInfo = displayInfo;
        this.wm = wm;

        HandlerThread thread = new HandlerThread("NtRefreshRateConfig");
        thread.start();
        bgHandler = new Handler(thread.getLooper());
        settingsObserver = new SettingsObserver();

        supportedDisplayModes = Arrays.copyOf(displayInfo.supportedModes, displayInfo.supportedModes.length);
        setupDisplayModes();

        maxSupportedHz = hzToDisplayMode.keySet().stream().max(Integer::compareTo).orElse(60);
        defaultModeId = computeDefaultModeId();
        defaultMinRefreshRate = getClosestSupportedMode(60).getRefreshRate();

        loadRefreshRateSetting();
        updateRefreshRate();
        modeId = getModeId();

        bestAppVote = new AppVoteInfo(null, 0, 0.0f, 0.0f, false);
        currentAppVote = new AppVoteInfo(null, 0, 0.0f, 0.0f, false);
    }

    private void setupDisplayModes() {
        for (Display.Mode mode : supportedDisplayModes) {
            int hz = Math.round(mode.getRefreshRate());
            hzToDisplayMode.put(hz, mode);
            modeIdToDisplayMode.put(mode.getModeId(), mode);
        }
    }

    private void loadRefreshRateSetting() {
        int value = Settings.Global.getInt(context.getContentResolver(),
                SETTINGS_REFRESH_RATE_MODE, supportsVRR ? 0 : maxSupportedHz);
        isVrrEnabled = (value == 0 && supportsVRR);
        if (!isVrrEnabled) currentRefreshRate = value;
    }

    private int getModeId() {
        return isVrrEnabled ? defaultModeId : getModeIdForHz(currentRefreshRate);
    }

    private int getModeIdForHz(int hz) {
        Display.Mode mode = hzToDisplayMode.get(hz);
        return (mode != null) ? mode.getModeId() : getClosestSupportedMode(hz).getModeId();
    }

    private int computeDefaultModeId() {
        if (supportedDisplayModes.length == 0) return 0;
        int width = displayInfo.logicalWidth, height = displayInfo.logicalHeight;
        Display.Mode bestMode = null;
        int bestHz = 0;
        for (Display.Mode mode : supportedDisplayModes) {
            if (mode.getPhysicalWidth() == width && mode.getPhysicalHeight() == height) {
                int hz = Math.round(mode.getRefreshRate());
                if (hz > bestHz) {
                    bestHz = hz;
                    bestMode = mode;
                }
            }
        }
        return (bestMode != null ? bestMode : supportedDisplayModes[0]).getModeId();
    }

    private void updateRefreshRate() {
        if (isVrrEnabled) {
            setSystemRefreshRates(defaultMinRefreshRate, maxSupportedHz);
        } else {
            setSystemRefreshRates(currentRefreshRate, currentRefreshRate);
        }
    }

    private void setIdleFpsMode(boolean disableIdle) {
        float idleHz = isVrrEnabled ? defaultMinRefreshRate : currentRefreshRate;
        float minHz = disableIdle ? maxSupportedHz : idleHz;
        Settings.System.putFloat(context.getContentResolver(),
                Settings.System.MIN_REFRESH_RATE, minHz);
        wm.requestTraversal();
    }

    private void setSystemRefreshRates(float minRate, float peakRate) {
        Settings.System.putFloat(context.getContentResolver(), Settings.System.MIN_REFRESH_RATE, minRate);
        Settings.System.putFloat(context.getContentResolver(), PEAK_REFRESH_RATE, peakRate);
    }

    private Display.Mode getClosestSupportedMode(int hz) {
        int bestHz = maxSupportedHz;
        for (int supportedHz : hzToDisplayMode.keySet()) {
            if (Math.abs(hz - supportedHz) < Math.abs(hz - bestHz)) {
                bestHz = supportedHz;
            }
        }
        return hzToDisplayMode.get(bestHz);
    }

    private void handleFocusedAppUpdate(ActivityRecord activityRecord) {
        AppRefreshRateConfig config = getAppConfigIfGame(activityRecord);
        if (config != null) {
            int uid = activityRecord.getUid();
            int targetFps = config.refreshRates.valueAt(0);
            synchronized (gameUidToFpsMap) {
                if (gameUidToFpsMap.get(uid, -1) != targetFps) {
                    if (mCallbacks != null) mCallbacks.setGameFps(uid, targetFps);
                    gameUidToFpsMap.put(uid, targetFps);
                }
            }
            disableIdle = config.disableIdle;
        } else {
            disableIdle = false;
        }
        if (supportsIdle && lastIdle != disableIdle) {
            bgHandler.post(() -> setIdleFpsMode(disableIdle));
            lastIdle = disableIdle;
        }
    }

    private AppRefreshRateConfig getAppConfigIfGame(ActivityRecord activityRecord) {
        String process = activityRecord.getProcessName();
        int category = activityRecord.info.applicationInfo.category;
        if (DEFAULT_APP_CONFIGS.containsKey(process) &&
            (category == 0 || GAMING_APPS.contains(process))) {
            return DEFAULT_APP_CONFIGS.get(process);
        }
        return null;
    }

    public void resetNtVoteResult() {
        bestAppVote.reset();
        maxWindowSize = 0;
    }

    public void updateVoteResult() {
        if (overrideWinPref) {
            updateBestVote("OverrideWinPrefer", getModeId(), maxSupportedHz);
        } else if (!bestAppVote.hasVote) {
            Display.Mode mode = modeIdToDisplayMode.get(modeId);
            float hz = (mode != null ? mode.getRefreshRate() : maxSupportedHz);
            updateBestVote("SettingMode", modeId, hz);
        }
        currentAppVote.copyFrom(bestAppVote);

        if (supportsIdle && lastIdle != disableIdle) {
            bgHandler.post(() -> setIdleFpsMode(disableIdle));
            lastIdle = disableIdle;
        }
    }

    private void updateBestVote(String source, int modeId, float refreshRate) {
        bestAppVote.updateVote(source, modeId, 0.0f, refreshRate);
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

    public void voteNtPreferredModeId(WindowState ws, boolean displayOn) {
        if (SYS_WINDOW_TYPES.contains(ws.getName())) return;

        String pkg = ws.getOwningPackage();
        int preferredModeId = 0;

        AppRefreshRateConfig config = DEFAULT_APP_CONFIGS.get(pkg);
        if (config != null) {
            int hz = config.refreshRates.valueAt(0);
            preferredModeId = getModeIdForHz(hz);
            if (config.disableIdle) disableIdle = true;

            if (config.disableSV) {
                WindowManager.LayoutParams lp = ws.mAttrs;
                if (lp.preferredMinDisplayRefreshRate == 59.0f &&
                    lp.preferredMaxDisplayRefreshRate == 60.0f) {
                    lp.preferredMinDisplayRefreshRate = lp.preferredMaxDisplayRefreshRate = 0.0f;
                }
            }
        }

        preferredModeId = shouldOverrideWinPref(ws, displayOn) ? getModeId() : preferredModeId;
        if (preferredModeId == 0) return;

        Display.Mode mode = modeIdToDisplayMode.get(preferredModeId);
        float hz = (mode != null ? mode.getRefreshRate() : maxSupportedHz);
        if (mode == null) preferredModeId = getModeId();

        int windowSize = ws.mRequestedWidth * ws.mRequestedHeight;
        if (hz > bestAppVote.maxRefreshRate || windowSize > maxWindowSize) {
            updateBestVote(pkg, preferredModeId, hz);
            maxWindowSize = windowSize;
        }
    }

    private boolean shouldOverrideWinPref(WindowState ws, boolean displayOn) {
        if (ws.inMultiWindowMode() || isOverlay(ws) || !displayOn || ws.isAnimationRunningSelfOrParent()) {
            overrideWinPref = true;
            return true;
        }
        return false;
    }

    private boolean isOverlay(WindowState ws) {
        int type = ws.mAttrs.type;
        return type == TYPE_NOTIFICATION_SHADE ||
                (type == TYPE_APPLICATION_OVERLAY && ws.mOwnerUid != SYSTEM_UID);
    }

    public boolean OverrideWinPrefer() { return overrideWinPref; }
    public float getMaxPreferRate() { return bestAppVote.maxRefreshRate; }
    public float getMinPreferRate() { return bestAppVote.minRefreshRate; }
    public int getPreferMode() { return bestAppVote.preferredModeId; }

    private final class SettingsObserver extends ContentObserver {
        private final Uri refreshRateModeUri =
                Settings.Global.getUriFor(SETTINGS_REFRESH_RATE_MODE);

        public SettingsObserver() {
            super(bgHandler);
            context.getContentResolver().registerContentObserver(
                    refreshRateModeUri, false, this, -1);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            if (!refreshRateModeUri.equals(uri)) return;
            loadRefreshRateSetting();
            updateRefreshRate();
            modeId = getModeId();
            wm.requestTraversal();
        }
    }
}
