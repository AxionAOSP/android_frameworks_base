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

    private static final IBinder sFlinger = ServiceManager.getService("SurfaceFlinger");

    private static final int TRANSACTION_SET_IDLE_FPS = 2005;
    private static final int TRANSACTION_SET_GAME_FPS = 2010;

    private static final String SETTINGS_REFRESH_RATE_MODE = "display_refresh_rate_mode";
    private static final String PEAK_REFRESH_RATE = "peak_refresh_rate";

    private static final float FPS_LOW = 61.0f;
    private static final float FPS_HIGH = 121.0f;

    private static final NtRefreshRateController INSTANCE = new NtRefreshRateController();

    private final EnumMap<RefreshRate, Integer> modeIdMap = new EnumMap<>(RefreshRate.class);
    private final Map<Integer, Display.Mode> modeIdToDisplayMode = new HashMap<>();
    private final Map<Integer, Display.Mode> rrToDisplayMode = new HashMap<>();

    private SparseIntArray gameUidToFpsMap = new SparseIntArray(10);
    private AppVoteInfo currentAppVote;
    private AppVoteInfo bestAppVote;

    private Display.Mode[] supportedDisplayModes;

    private Context context;
    private Handler bgHandler;
    private SettingsObserver settingsObserver;
    private WindowManagerService wm;
    private DisplayInfo displayInfo;

    private RefreshRateMode refreshRateMode;
    private int modeId;
    private int maxWindowSize = 0;

    private boolean supportsVRR = SystemProperties.getBoolean("ro.surface_flinger.use_content_detection_for_refresh_rate", false);
    private boolean supportsIdle = SystemProperties.getBoolean("ro.surface_flinger.support_kernel_idle_timer", false);

    private boolean overrideWinPref = false;
    private boolean lastIdle = false;
    private boolean disableIdle = false;

    private NtRefreshRateController() {}

    public static NtRefreshRateController get() { return INSTANCE; }

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

        refreshRateMode = getRRMode();
        updatePeakRefreshRate(refreshRateMode);

        modeId = getModeId(refreshRateMode);

        bestAppVote = new AppVoteInfo(null, 0, 0.0f, 0.0f, false);
        currentAppVote = new AppVoteInfo(null, 0, 0.0f, 0.0f, false);
    }

    private void setupDisplayModes() {
        int defaultModeId = supportedDisplayModes[0].getModeId();
        for (RefreshRate rate : RefreshRate.values()) {
            modeIdMap.put(rate, defaultModeId);
        }
        for (Display.Mode mode : supportedDisplayModes) {
            RefreshRate rate = RefreshRate.fromHz(mode.getRefreshRate());
            if (rate != null) {
                rrToDisplayMode.put((int) mode.getRefreshRate(), mode);
                modeIdToDisplayMode.put(mode.getModeId(), mode);
                modeIdMap.put(rate, mode.getModeId());
            }
        }
    }

    private int getModeId(RefreshRateMode mode) {
        return switch (mode) {
            case LOW -> modeIdMap.get(RefreshRate.LOW);
            case VARIABLE -> modeIdMap.get(RefreshRate.MID);
            case HIGH -> modeIdMap.get(RefreshRate.HIGH);
        };
    }

    private void updatePeakRefreshRate(RefreshRateMode mode) {
        float peakRate = mode == RefreshRateMode.LOW ? FPS_LOW : FPS_HIGH;
        Settings.System.putFloat(context.getContentResolver(), PEAK_REFRESH_RATE, peakRate);
    }

    private void handleFocusedAppUpdate(ActivityRecord activityRecord) {
        String processName = activityRecord.getProcessName();
        int uid = activityRecord.getUid();
        int appCategory = activityRecord.info.applicationInfo.category;

        boolean hasAppConfig = DEFAULT_APP_CONFIGS.containsKey(processName);
        boolean isGame = (appCategory == 0) || GAMING_APPS.contains(processName);

        if (hasAppConfig && isGame) {
            AppRefreshRateConfig appConfig = DEFAULT_APP_CONFIGS.get(processName);
            int targetFps = appConfig.refreshRates.valueAt(refreshRateMode.value);
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
        data.writeInterfaceToken("android.ui.ISurfaceComposer");
        if (transaction == TRANSACTION_SET_IDLE_FPS) {
            data.writeInt(fps > 0 ? 1 : 0);
        } else {
            data.writeInt(uid);
            data.writeFloat(fps);
        }
        try {
            sFlinger.transact(transaction, data, null, 0);
        } catch (RemoteException ignored) {
        } finally {
            data.recycle();
        }
    }

    public void resetNtVoteResult() {
        bestAppVote.reset();
        maxWindowSize = 0;
    }

    public void updateVoteResult() {
        if (overrideWinPref) {
            bestAppVote.updateVote("OverrideWinPrefer",
                    modeIdMap.get(RefreshRate.HIGH), 0.0f, RefreshRate.HIGH.hz);
        } else if (!bestAppVote.hasVote) {
            Display.Mode mode = modeIdToDisplayMode.get(modeId);
            float refreshRate = mode != null ? mode.getRefreshRate() : RefreshRate.HIGH.hz;
            bestAppVote.updateVote("SettingMode", modeId, 0.0f, refreshRate);
        }

        currentAppVote.copyFrom(bestAppVote);

        if (supportsIdle && lastIdle != disableIdle) {
            bgHandler.post(() -> setIdleFpsMode(disableIdle));
            lastIdle = disableIdle;
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
        bgHandler.post(() -> handleFocusedAppUpdate(activityRecord));
    }

    public void voteNtPreferredModeId(WindowState ws, boolean displayOn) {
        if (SYS_WINDOW_TYPES.contains(ws.getName())) return;

        String packageName = ws.getOwningPackage();
        int preferredModeId = 0;

        AppRefreshRateConfig appConfig = DEFAULT_APP_CONFIGS.get(packageName);
        if (appConfig != null) {
            SparseIntArray refreshRates = appConfig.refreshRates;
            if (refreshRateMode.value < refreshRates.size()) {
                int hz = refreshRates.valueAt(refreshRateMode.value);
                Display.Mode mode = rrToDisplayMode.get(hz);
                preferredModeId = mode != null ? mode.getModeId() : currentAppVote.preferredModeId;
            }

            if (appConfig.disableIdle) disableIdle = true;

            if (appConfig.disableSV) {
                WindowManager.LayoutParams lp = ws.mAttrs;
                if (lp.preferredMinDisplayRefreshRate == 59.0f && lp.preferredMaxDisplayRefreshRate == FPS_LOW) {
                    lp.preferredMinDisplayRefreshRate = lp.preferredMaxDisplayRefreshRate = 0.0f;
                }
            }
        }

        preferredModeId = getOverlayWindowModeId(ws, displayOn, preferredModeId);
        if (preferredModeId == 0) return;

        Display.Mode preferredMode = modeIdToDisplayMode.get(preferredModeId);
        float refreshRate = preferredMode != null ? preferredMode.getRefreshRate() : RefreshRate.HIGH.hz;
        if (preferredMode == null) preferredModeId = modeIdMap.get(RefreshRate.HIGH);

        int windowSize = ws.mRequestedWidth * ws.mRequestedHeight;
        if (refreshRate > bestAppVote.maxRefreshRate || windowSize > maxWindowSize) {
            bestAppVote.updateVote(packageName, preferredModeId, 0.0f, refreshRate);
            maxWindowSize = windowSize;
        }
    }

    private int getOverlayWindowModeId(WindowState ws, boolean displayOn, int preferredModeId) {
        if (ws.inMultiWindowMode() || isOverlay(ws) || !displayOn
                || ws.isAnimationRunningSelfOrParent()) {
            if (modeId != modeIdMap.get(RefreshRate.LOW)) {
                overrideWinPref = true;
                return modeIdMap.get(RefreshRate.HIGH);
            }
        }
        return preferredModeId;
    }

    private boolean isOverlay(WindowState ws) {
        int type = ws.mAttrs.type;
        return type == TYPE_NOTIFICATION_SHADE
                || (type == TYPE_APPLICATION_OVERLAY
                && ws.mOwnerUid != SYSTEM_UID);
    }

    public boolean OverrideWinPrefer() { 
        return overrideWinPref; 
    }

    public float getMaxPreferRate() {
        return bestAppVote.maxRefreshRate;
    }

    public float getMinPreferRate() {
         return bestAppVote.minRefreshRate;
    }

    public int getPreferMode() { 
        return bestAppVote.preferredModeId; 
    }

    private RefreshRateMode getRRMode() {
        int mode = Settings.Global.getInt(context.getContentResolver(),
                        SETTINGS_REFRESH_RATE_MODE,
                        supportsVRR ? 0 : 1);
        return RefreshRateMode.fromInt(mode, supportsVRR);
    }

    private final class SettingsObserver extends ContentObserver {
        private final Uri refreshRateModeUri = Settings.Global.getUriFor(SETTINGS_REFRESH_RATE_MODE);

        public SettingsObserver() {
            super(bgHandler);
            context.getContentResolver().registerContentObserver(refreshRateModeUri, false, this, -1);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            if (!refreshRateModeUri.equals(uri)) return;
            refreshRateMode = getRRMode();
            updatePeakRefreshRate(refreshRateMode);
            modeId = getModeId(refreshRateMode);
            wm.requestTraversal();
        }
    }
}
