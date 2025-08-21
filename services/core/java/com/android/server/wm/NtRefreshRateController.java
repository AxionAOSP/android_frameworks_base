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

import static com.android.server.wm.NtAppRefreshRateProvider.DEFAULT_APP_CONFIGS;
import static com.android.server.wm.NtAppRefreshRateProvider.GAMING_APPS;

import com.android.server.wm.NtAppRefreshRateProvider.AppRefreshRateConfig;
import com.android.server.wm.NtAppRefreshRateProvider.AppVoteInfo;
import com.android.server.wm.NtAppRefreshRateProvider.RefreshRateMode;
import com.android.server.wm.NtAppRefreshRateProvider.RefreshRate;

import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.util.*;
import android.view.*;

import java.util.*;

public class NtRefreshRateController {

    private static final String TAG = "NtRefreshRateController";

    private static final String SURFACE_COMPOSER_INTERFACE = "android.ui.ISurfaceComposer";
    private static final IBinder SURFACE_FLINGER_BINDER = ServiceManager.getService("SurfaceFlinger");

    private static final int TRANSACTION_SET_IDLE_FPS = 2005;
    private static final int TRANSACTION_SET_GAME_FPS = 2010;

    private static final String SETTINGS_REFRESH_RATE_MODE = "display_refresh_rate_mode";
    private static final String PEAK_REFRESH_RATE = "peak_refresh_rate";

    private static final int SYSTEM_UID = 1000;
    private static final int DEFAULT_UID_FPS = 0;

    private static final float PEAK_LOW = 61.0f;
    private static final float PEAK_HIGH = 121.0f;

    private static final NtRefreshRateController INSTANCE = new NtRefreshRateController();

    private final EnumMap<RefreshRate, Integer> modeIdMap = new EnumMap<>(RefreshRate.class);
    private final Map<Integer, Display.Mode> modeIdToDisplayMode = new HashMap<>();
    private final Map<Integer, Display.Mode> refreshRateToDisplayMode = new HashMap<>();

    private SparseIntArray gameUidToFpsMap;
    private AppVoteInfo currentAppVote;
    private AppVoteInfo bestAppVote;

    private Display.Mode[] supportedDisplayModes;
    private ArrayMap<String, AppRefreshRateConfig> defaultAppConfigs;

    private Context context;
    private Handler backgroundHandler;
    private SettingsObserver settingsObserver;
    private WindowManagerService windowManagerService;
    private DisplayInfo displayInfo;

    private RefreshRateMode displayRefreshMode;
    private int currentSelectedModeId;
    private int maxWindowSize = 0;

    private boolean hasAppPreference = false;
    private boolean supportsVariableRefreshRate = false;
    private boolean shouldOverrideWindowPreference = false;
    private boolean lastIdleState = false;
    private boolean shouldDisableIdleFps = false;
    private boolean supportsIdleFps = false;

    private NtRefreshRateController() {}

    public static NtRefreshRateController get() { return INSTANCE; }

    public void init(Context context, DisplayInfo displayInfo, WindowManagerService windowManagerService) {
        this.context = context;
        this.displayInfo = displayInfo;
        this.windowManagerService = windowManagerService;

        gameUidToFpsMap = new SparseIntArray(10);
        defaultAppConfigs = DEFAULT_APP_CONFIGS;

        HandlerThread thread = new HandlerThread("NtRefreshRateConfig");
        thread.start();
        backgroundHandler = new Handler(thread.getLooper());
        settingsObserver = new SettingsObserver();

        supportedDisplayModes = Arrays.copyOf(displayInfo.supportedModes, displayInfo.supportedModes.length);
        initializeDisplayModes();

        supportsVariableRefreshRate = SystemProperties.getBoolean(
                "ro.surface_flinger.use_content_detection_for_refresh_rate", false);
        supportsIdleFps = SystemProperties.getBoolean("ro.surface_flinger.support_kernel_idle_timer", false);

        int modeValue = Settings.Global.getInt(
                context.getContentResolver(),
                SETTINGS_REFRESH_RATE_MODE,
                supportsVariableRefreshRate ? 0 : 1);
        displayRefreshMode = RefreshRateMode.fromInt(modeValue, !supportsVariableRefreshRate);
        updatePeakRefreshRateSetting(displayRefreshMode);

        currentSelectedModeId = getModeIdForRefreshRateMode(displayRefreshMode);

        bestAppVote = new AppVoteInfo(null, 0, 0.0f, 0.0f, false);
        currentAppVote = new AppVoteInfo(null, 0, 0.0f, 0.0f, false);
    }

    private void initializeDisplayModes() {
        int defaultModeId = supportedDisplayModes[0].getModeId();
        for (RefreshRate rate : RefreshRate.values()) {
            modeIdMap.put(rate, defaultModeId);
        }
        for (Display.Mode mode : supportedDisplayModes) {
            RefreshRate rate = RefreshRate.fromHz(mode.getRefreshRate());
            if (rate != null) {
                refreshRateToDisplayMode.put((int) mode.getRefreshRate(), mode);
                modeIdToDisplayMode.put(mode.getModeId(), mode);
                modeIdMap.put(rate, mode.getModeId());
            }
        }
    }

    private int getModeIdForRefreshRateMode(RefreshRateMode mode) {
        return switch (mode) {
            case LOW -> modeIdMap.get(RefreshRate.LOW);
            case VARIABLE -> modeIdMap.get(RefreshRate.MID);
            case HIGH -> modeIdMap.get(RefreshRate.HIGH);
        };
    }

    private void updatePeakRefreshRateSetting(RefreshRateMode mode) {
        float peakRate = mode == RefreshRateMode.LOW ? PEAK_LOW : PEAK_HIGH;
        Settings.System.putFloat(context.getContentResolver(), PEAK_REFRESH_RATE, peakRate);
    }

    private void handleFocusedAppUpdate(ActivityRecord activityRecord) {
        String processName = activityRecord.getProcessName();
        int uid = activityRecord.getUid();
        int appCategory = activityRecord.info.applicationInfo.category;

        boolean hasAppConfig = defaultAppConfigs.containsKey(processName);
        boolean isGameApp = (appCategory == 0) || GAMING_APPS.contains(processName);

        if (hasAppConfig && isGameApp) {
            AppRefreshRateConfig appConfig = defaultAppConfigs.get(processName);
            int targetFps = appConfig.getRefreshRates().valueAt(displayRefreshMode.value);
            synchronized (gameUidToFpsMap) {
                if (gameUidToFpsMap.get(uid, -1) != targetFps) {
                    transactSurfaceFlinger(uid, targetFps, TRANSACTION_SET_GAME_FPS);
                    gameUidToFpsMap.put(uid, targetFps);
                }
            }
        }
    }

    private void transactSurfaceFlinger(int uid, float fps, int transaction) {
        Parcel data = Parcel.obtain();
        data.writeInterfaceToken(SURFACE_COMPOSER_INTERFACE);
        if (transaction == TRANSACTION_SET_IDLE_FPS) {
            data.writeInt(fps > 0 ? 1 : 0);
        } else {
            data.writeInt(uid);
            data.writeFloat(fps);
        }
        try {
            SURFACE_FLINGER_BINDER.transact(transaction, data, null, 0);
        } catch (RemoteException ignored) {
        } finally {
            data.recycle();
        }
    }

    public void resetNtVoteResult() {
        bestAppVote.reset();
        hasAppPreference = shouldOverrideWindowPreference = shouldDisableIdleFps = false;
        maxWindowSize = 0;
    }

    public void updateVoteResult() {
        if (shouldOverrideWindowPreference) {
            bestAppVote.updateVote("OverrideWinPrefer",
                    modeIdMap.get(RefreshRate.HIGH), 0.0f, RefreshRate.HIGH.hz);
        } else if (!bestAppVote.hasVote()) {
            Display.Mode mode = modeIdToDisplayMode.get(currentSelectedModeId);
            float refreshRate = mode != null ? mode.getRefreshRate() : RefreshRate.HIGH.hz;
            bestAppVote.updateVote("SettingMode", currentSelectedModeId, 0.0f, refreshRate);
        }

        currentAppVote.copyFrom(bestAppVote);

        if (supportsIdleFps && lastIdleState != shouldDisableIdleFps) {
            backgroundHandler.post(() -> setIdleFpsMode(shouldDisableIdleFps));
            lastIdleState = shouldDisableIdleFps;
        }
    }

    private void setIdleFpsMode(boolean disableIdle) {
        transactSurfaceFlinger(disableIdle ? 0 : 1, 0, TRANSACTION_SET_IDLE_FPS);
    }

    public void setGameModeFrameRateOverrideToNtRefreshRate(int uid, float frameRate) {
        synchronized (gameUidToFpsMap) {
            if (gameUidToFpsMap.get(uid, -1) >= 0) {
                gameUidToFpsMap.put(uid, Math.round(frameRate));
            }
        }
    }

    public void updateFocusedApp(final ActivityRecord activityRecord) {
        backgroundHandler.post(() -> handleFocusedAppUpdate(activityRecord));
    }

    public void voteNtPreferredModeId(WindowState windowState, boolean displayOn) {
        if (defaultAppConfigs == null || isSystemWindow(windowState)) return;

        String packageName = windowState.getOwningPackage();
        int preferredModeId = 0;

        AppRefreshRateConfig appConfig = defaultAppConfigs.get(packageName);
        if (appConfig != null) {
            SparseIntArray refreshRates = appConfig.getRefreshRates();
            if (displayRefreshMode.value < refreshRates.size()) {
                int hz = refreshRates.valueAt(displayRefreshMode.value);
                Display.Mode mode = refreshRateToDisplayMode.get(hz);
                preferredModeId = mode != null ? mode.getModeId() : currentAppVote.getPreferredModeId();
            }

            if (appConfig.shouldDisableIdleFps()) shouldDisableIdleFps = true;

            if (appConfig.shouldDisableSurfaceView()) {
                WindowManager.LayoutParams lp = windowState.mAttrs;
                if (lp.preferredMinDisplayRefreshRate == 59.0f && lp.preferredMaxDisplayRefreshRate == 61.0f) {
                    hasAppPreference = true;
                    lp.preferredMinDisplayRefreshRate = lp.preferredMaxDisplayRefreshRate = 0.0f;
                }
            }
        }

        preferredModeId = handleSpecialWindowConditions(windowState, displayOn, preferredModeId);
        if (preferredModeId == 0) return;

        Display.Mode preferredMode = modeIdToDisplayMode.get(preferredModeId);
        float refreshRate = preferredMode != null ? preferredMode.getRefreshRate() : RefreshRate.HIGH.hz;
        if (preferredMode == null) preferredModeId = modeIdMap.get(RefreshRate.HIGH);

        int windowSize = windowState.mRequestedWidth * windowState.mRequestedHeight;
        if (refreshRate > bestAppVote.getMaxRefreshRate() || windowSize > maxWindowSize) {
            bestAppVote.updateVote(packageName, preferredModeId, 0.0f, refreshRate);
            maxWindowSize = windowSize;
        }
    }

    private boolean isSystemWindow(WindowState windowState) {
        String name = windowState.getName();
        return name.contains("StatusBar") || name.contains("NavigationBar") || name.contains("wallpapers");
    }

    private int handleSpecialWindowConditions(WindowState windowState, boolean displayOn, int preferredModeId) {
        if (windowState.inMultiWindowMode() || isSpecialOverlay(windowState) || !displayOn
                || windowState.isAnimationRunningSelfOrParent()) {
            if (currentSelectedModeId != modeIdMap.get(RefreshRate.LOW)) {
                shouldOverrideWindowPreference = true;
                return modeIdMap.get(RefreshRate.HIGH);
            }
        }
        return preferredModeId;
    }

    private boolean isSpecialOverlay(WindowState windowState) {
        int windowType = windowState.mAttrs.type;
        return windowType == WindowManager.LayoutParams.TYPE_NOTIFICATION_SHADE
                || (windowType == WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                && windowState.mOwnerUid != SYSTEM_UID);
    }

    public boolean OverrideWinPrefer() { return shouldOverrideWindowPreference; }
    public float getMaxPreferRate() { return bestAppVote.getMaxRefreshRate(); }
    public float getMinPreferRate() { return bestAppVote.getMinRefreshRate(); }
    public int getPreferMode() { return bestAppVote.getPreferredModeId(); }
    boolean hasAppPreference() { return hasAppPreference; }

    private final class SettingsObserver extends ContentObserver {
        private final Uri refreshRateModeUri = Settings.Global.getUriFor(SETTINGS_REFRESH_RATE_MODE);

        public SettingsObserver() {
            super(backgroundHandler);
            context.getContentResolver().registerContentObserver(refreshRateModeUri, false, this, -1);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            if (!refreshRateModeUri.equals(uri)) return;
            int modeValue = Settings.Global.getInt(
                    context.getContentResolver(),
                    SETTINGS_REFRESH_RATE_MODE,
                    supportsVariableRefreshRate ? 0 : 1);

            displayRefreshMode = RefreshRateMode.fromInt(modeValue, !supportsVariableRefreshRate);
            updatePeakRefreshRateSetting(displayRefreshMode);
            currentSelectedModeId = getModeIdForRefreshRateMode(displayRefreshMode);
            windowManagerService.requestTraversal();
        }
    }
}
