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

package com.android.server;

import static android.os.UserHandle.USER_CURRENT;
import static android.view.WindowManagerPolicyConstants.NAV_BAR_MODE_2BUTTON;
import static android.view.WindowManagerPolicyConstants.NAV_BAR_MODE_2BUTTON_OVERLAY;
import static android.view.WindowManagerPolicyConstants.NAV_BAR_MODE_3BUTTON;
import static android.view.WindowManagerPolicyConstants.NAV_BAR_MODE_3BUTTON_OVERLAY;
import static android.view.WindowManagerPolicyConstants.NAV_BAR_MODE_GESTURAL;
import static android.view.WindowManagerPolicyConstants.NAV_BAR_MODE_GESTURAL_OVERLAY;

import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.om.IOverlayManager;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.hardware.display.DisplayManagerInternal;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Slog;
import android.view.Display;
import android.view.DisplayInfo;
import android.view.IWindowManager;
import android.view.SurfaceControl;

import com.android.internal.os.BackgroundThread;
import com.android.server.display.DisplayControl;
import com.android.server.display.MiFreeformDisplayAdapter;
import com.android.server.wm.WindowManagerInternal;

public class AxPcModeService implements IAxPcModeService {
    private static final String TAG = "AxPcModeService";

    private static final int DESKTOP_MODE_DPI = 284;

    private static final String SETTING_AX_PC_MODE = "ax_pc_mode";
    private static final String SETTING_SAVED_NAV_KEY = "ax_pc_mode_saved_nav_key";
    private static final String SETTING_SAVED_DENSITY_KEY = "ax_pc_mode_saved_density";
    private static final String SETTING_RESOLUTION_OVERRIDE_KEY = "ax_pc_mode_resolution_override";
    private static final String SETTING_DISPLAY_OFF_KEY = "ax_pc_mode_display_off";
    private static final String SETTING_DENSITY_OVERRIDE_KEY = "ax_pc_mode_density";
    private static final String SETTING_SECONDARY_DENSITY_KEY = "ax_pc_mode_secondary_density";
    private static final String SETTING_TARGET_DISPLAY_ID = "ax_pc_mode_target_display_id";
    private static final int DEFAULT_SECONDARY_DPI = 160;

    private static final String KEY_SYSTEM_NAV_GESTURAL = "system_nav_gestural";
    private static final String KEY_SYSTEM_NAV_2BUTTONS = "system_nav_2buttons";
    private static final String KEY_SYSTEM_NAV_3BUTTONS = "system_nav_3buttons";

    private static final String AX_PC_MODE_PKG = "com.android.axion.axpcmode";
    private static final String AX_PC_MODE_ACTIVITY =
            "com.android.axion.axpcmode.activities.PcModeLauncherActivity";

    private Context mContext;
    private Handler mHandler;
    private ContentResolver mContentResolver;
    private IOverlayManager mOverlayManager;
    private IWindowManager mIWindowManager;

    private boolean mPcModeEnabled = false;
    private boolean mIntentionalDisable = false;
    private boolean mDisplayOffEnabled = false;
    private boolean mSystemReady = false;
    private WindowManagerInternal mWindowManager;
    private IBinder mInternalDisplayToken;

    private final ContentObserver mSettingsObserver = new ContentObserver(null) {
        @Override
        public void onChange(boolean selfChange, Uri uri) {
            if (uri.equals(Settings.Secure.getUriFor(SETTING_AX_PC_MODE))) {
                updatePcModeState();
            } else if (uri.equals(Settings.Secure.getUriFor(SETTING_RESOLUTION_OVERRIDE_KEY))) {
                if (mPcModeEnabled && getTargetDisplayId() == Display.DEFAULT_DISPLAY) {
                    updateResolutionOverride(Display.DEFAULT_DISPLAY);
                }
            } else if (uri.equals(Settings.Secure.getUriFor(SETTING_DISPLAY_OFF_KEY))) {
                updateDisplayOffState();
            } else if (uri.equals(Settings.Secure.getUriFor(SETTING_DENSITY_OVERRIDE_KEY))) {
                if (mPcModeEnabled) {
                    forceDesktopDensity(Display.DEFAULT_DISPLAY, false);
                }
            } else if (uri.equals(Settings.Secure.getUriFor(SETTING_SECONDARY_DENSITY_KEY))) {
                if (mPcModeEnabled) {
                    int targetId = getTargetDisplayId();
                    if (targetId != Display.DEFAULT_DISPLAY
                            && targetId != Display.INVALID_DISPLAY) {
                        forceDesktopDensity(targetId, false);
                    }
                }
            } else if (uri.equals(Settings.Secure.getUriFor(SETTING_TARGET_DISPLAY_ID))) {
                onTargetDisplayChanged();
            }
        }
    };

    public AxPcModeService() {
    }

    public void systemReady() {
        mContext = NtServiceInjector.getCtx();
        mHandler = new Handler(Looper.getMainLooper());
        mContentResolver = mContext.getContentResolver();
        mContentResolver.registerContentObserver(
                Settings.Secure.getUriFor(SETTING_AX_PC_MODE),
                false,
                mSettingsObserver,
                UserHandle.USER_ALL
        );
        mContentResolver.registerContentObserver(
                Settings.Secure.getUriFor(SETTING_RESOLUTION_OVERRIDE_KEY),
                false,
                mSettingsObserver,
                UserHandle.USER_ALL
        );
        mContentResolver.registerContentObserver(
                Settings.Secure.getUriFor(SETTING_DISPLAY_OFF_KEY),
                false,
                mSettingsObserver,
                UserHandle.USER_ALL
        );
        mContentResolver.registerContentObserver(
                Settings.Secure.getUriFor(SETTING_DENSITY_OVERRIDE_KEY),
                false,
                mSettingsObserver,
                UserHandle.USER_ALL
        );
        mContentResolver.registerContentObserver(
                Settings.Secure.getUriFor(SETTING_SECONDARY_DENSITY_KEY),
                false,
                mSettingsObserver,
                UserHandle.USER_ALL
        );
        mContentResolver.registerContentObserver(
                Settings.Secure.getUriFor(SETTING_TARGET_DISPLAY_ID),
                false,
                mSettingsObserver,
                UserHandle.USER_ALL
        );
        mIWindowManager = IWindowManager.Stub.asInterface(ServiceManager.getService(Context.WINDOW_SERVICE));
        mWindowManager = LocalServices.getService(WindowManagerInternal.class);
        mSystemReady = true;
        mHandler.post(() -> {
            updatePcModeState();
            if (mPcModeEnabled) {
                updateResolutionOverride(Display.DEFAULT_DISPLAY);
            }
        });
    }

    private void updatePcModeState() {
        boolean enabled = Settings.Secure.getIntForUser(
                mContentResolver,
                SETTING_AX_PC_MODE,
                0,
                UserHandle.USER_CURRENT
        ) == 1;

        if (enabled != mPcModeEnabled) {
            mPcModeEnabled = enabled;
            Slog.i(TAG, "PC Mode state changed: " + (enabled ? "enabled" : "disabled"));

            if (mSystemReady) {
                updatePcModeState(enabled);
                refreshDisplayPolicy();
                updateDisplayOffState();
            }
        }
    }

    public void onDefaultDisplayMirroringChanged(boolean mirrored) {
        Slog.i(TAG, "Default display mirroring changed: " + mirrored);
        mHandler.post(() -> updateDisplayOffState(mirrored));
    }

    private void updateDisplayOffState() {
        DisplayManagerInternal dmi = LocalServices.getService(DisplayManagerInternal.class);
        boolean mirrored = dmi != null && dmi.isDefaultDisplayMirrored();
        updateDisplayOffState(mirrored);
    }

    private void updateDisplayOffState(boolean mirrored) {
        boolean enabled = Settings.Secure.getIntForUser(
                mContentResolver,
                SETTING_DISPLAY_OFF_KEY,
                0,
                UserHandle.USER_CURRENT
        ) == 1;

        if (enabled != mDisplayOffEnabled) {
            mDisplayOffEnabled = enabled;
            Slog.i(TAG, "Display-off state changed: " + (enabled ? "enabled" : "disabled"));
        }

        if (mDisplayOffEnabled && mPcModeEnabled && mirrored) {
            setInternalDisplayPowerMode(false);
        } else {
            setInternalDisplayPowerMode(true);
        }
    }

    private void setInternalDisplayPowerMode(boolean on) {
        if (!mDisplayOffEnabled && !on) {
            return;
        }

        BackgroundThread.getHandler().post(() -> {
            if (mInternalDisplayToken == null) {
                long[] physicalDisplayIds = DisplayControl.getPhysicalDisplayIds();
                if (physicalDisplayIds != null && physicalDisplayIds.length > 0) {
                    mInternalDisplayToken = DisplayControl.getPhysicalDisplayToken(physicalDisplayIds[0]);
                }
            }

            if (mInternalDisplayToken != null) {
                int mode = on ? SurfaceControl.POWER_MODE_NORMAL : SurfaceControl.POWER_MODE_OFF;
                SurfaceControl.setDisplayPowerMode(mInternalDisplayToken, mode);
                Slog.d(TAG, "Set internal display power mode to: " + (on ? "ON" : "OFF"));
            } else {
                Slog.e(TAG, "Failed to get internal display token");
            }
        });
    }

    private void refreshDisplayPolicy() {
        if (mWindowManager != null) {
            try {
                mWindowManager.requestTraversalFromDisplayManager();
                Slog.d(TAG, "Triggered display policy refresh");
            } catch (Exception e) {
                Slog.e(TAG, "Failed to refresh display policy", e);
            }
        }
    }

    private void updatePcModeState(boolean start) {
        try {
            if (start) {
                setTargetDisplayId(Display.DEFAULT_DISPLAY);
                forceDesktopDensity(Display.DEFAULT_DISPLAY);
                forceThreeButtonNavigation();
                setAppEnabled(true);

                Intent launcherIntent = new Intent();
                launcherIntent.setComponent(
                        new ComponentName(AX_PC_MODE_PKG, AX_PC_MODE_ACTIVITY));
                launcherIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                mContext.startActivityAsUser(launcherIntent, UserHandle.CURRENT);
            } else {
                Intent stopIntent = new Intent();
                stopIntent.setClassName(AX_PC_MODE_PKG,
                        "com.android.axion.axpcmode.services.TaskbarService");
                stopIntent.setAction("com.android.axion.axpcmode.STOP_TASKBAR");
                mContext.startServiceAsUser(stopIntent, UserHandle.CURRENT);

                Intent homeIntent = new Intent(Intent.ACTION_MAIN);
                homeIntent.addCategory(Intent.CATEGORY_HOME);
                homeIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                mContext.startActivityAsUser(homeIntent, UserHandle.CURRENT);

                int targetDisplay = getTargetDisplayId();
                restoreNavigationMode();
                restoreDisplayDensity(Display.DEFAULT_DISPLAY);
                restoreDisplaySize(Display.DEFAULT_DISPLAY);
                if (targetDisplay != Display.DEFAULT_DISPLAY
                        && targetDisplay != Display.INVALID_DISPLAY) {
                    restoreDisplayDensity(targetDisplay);
                }

                setInternalDisplayPowerMode(true);
                setTargetDisplayId(Display.INVALID_DISPLAY);
                setAppEnabled(false);
            }
        } catch (Exception e) {
            Slog.e(TAG, "Failed to update PC mode state", e);
        }
    }

    private void onTargetDisplayChanged() {
        int displayId = getStoredTargetDisplayId();
        if (displayId != Display.INVALID_DISPLAY && displayId != Display.DEFAULT_DISPLAY) {
            Slog.i(TAG, "Secondary display available (" + displayId + "), enabling app");
            forceDesktopDensity(displayId);
            setAppEnabled(true);
        } else if (displayId == Display.INVALID_DISPLAY) {
            Slog.i(TAG, "Secondary display gone, stopping secondary service");
            Intent stopIntent = new Intent();
            stopIntent.setClassName(AX_PC_MODE_PKG,
                    "com.android.axion.axpcmode.services.SecondaryTaskbarService");
            stopIntent.setAction("com.android.axion.axpcmode.STOP_SECONDARY_TASKBAR");
            mContext.startServiceAsUser(stopIntent, UserHandle.CURRENT);
            if (!mPcModeEnabled) {
                setAppEnabled(false);
            }
        }
    }

    private int getStoredTargetDisplayId() {
        return Settings.Secure.getIntForUser(
                mContentResolver,
                SETTING_TARGET_DISPLAY_ID,
                Display.INVALID_DISPLAY,
                UserHandle.USER_CURRENT
        );
    }

    private void setTargetDisplayId(int displayId) {
        Settings.Secure.putIntForUser(
                mContentResolver,
                SETTING_TARGET_DISPLAY_ID,
                displayId,
                UserHandle.USER_CURRENT
        );
    }

    private int getTargetDisplayId() {
        int stored = getStoredTargetDisplayId();
        if (stored != Display.INVALID_DISPLAY) return stored;
        return Display.DEFAULT_DISPLAY;
    }

    private void setAppEnabled(boolean enabled) {
        if (enabled) {
            mIntentionalDisable = false;
        } else {
            mIntentionalDisable = true;
        }
        BackgroundThread.getHandler().post(() -> {
            try {
                int newState = enabled
                        ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                        : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
                mContext.getPackageManager().setApplicationEnabledSetting(
                        AX_PC_MODE_PKG,
                        newState,
                        0
                );
                Slog.d(TAG, "Set AxPcMode app enabled: " + enabled);
            } catch (Exception e) {
                Slog.e(TAG, "Failed to set app enabled state", e);
            }
        });
    }

    private void forceThreeButtonNavigation() {
        String currentKey = getCurrentSystemNavigationMode();

        String savedKey = Settings.Secure.getStringForUser(
                mContentResolver,
                SETTING_SAVED_NAV_KEY,
                UserHandle.USER_CURRENT
        );

        if (savedKey == null && currentKey != null) {
            Settings.Secure.putStringForUser(
                    mContentResolver,
                    SETTING_SAVED_NAV_KEY,
                    currentKey,
                    UserHandle.USER_CURRENT
            );
            Slog.d(TAG, "Saved current navigation mode: " + currentKey);
        }

        if (!KEY_SYSTEM_NAV_3BUTTONS.equals(currentKey)) {
            setCurrentSystemNavigationMode(KEY_SYSTEM_NAV_3BUTTONS);
            Slog.d(TAG, "Forced 3-button navigation mode");
        }
    }

    private void restoreNavigationMode() {
        String savedKey = Settings.Secure.getStringForUser(
                mContentResolver,
                SETTING_SAVED_NAV_KEY,
                UserHandle.USER_CURRENT
        );

        if (savedKey != null) {
            setCurrentSystemNavigationMode(savedKey);
            Slog.d(TAG, "Restored navigation mode to: " + savedKey);

            Settings.Secure.putStringForUser(
                    mContentResolver,
                    SETTING_SAVED_NAV_KEY,
                    null,
                    UserHandle.USER_CURRENT
            );
        } else {
            Slog.w(TAG, "No saved navigation mode found to restore");
        }
    }

    private String getCurrentSystemNavigationMode() {
        int navMode = Settings.Secure.getIntForUser(
                mContentResolver,
                "navigation_mode",
                NAV_BAR_MODE_3BUTTON,
                UserHandle.USER_CURRENT
        );

        switch (navMode) {
            case NAV_BAR_MODE_GESTURAL:
                return KEY_SYSTEM_NAV_GESTURAL;
            case NAV_BAR_MODE_2BUTTON:
                return KEY_SYSTEM_NAV_2BUTTONS;
            case NAV_BAR_MODE_3BUTTON:
            default:
                return KEY_SYSTEM_NAV_3BUTTONS;
        }
    }

    private void setCurrentSystemNavigationMode(String key) {
        if (mOverlayManager == null) {
            mOverlayManager = IOverlayManager.Stub.asInterface(
                    ServiceManager.getService(Context.OVERLAY_SERVICE));
        }

        if (mOverlayManager == null) {
            Slog.e(TAG, "OverlayManager not available");
            return;
        }

        String overlayPackage = NAV_BAR_MODE_GESTURAL_OVERLAY;
        switch (key) {
            case KEY_SYSTEM_NAV_GESTURAL:
                overlayPackage = NAV_BAR_MODE_GESTURAL_OVERLAY;
                break;
            case KEY_SYSTEM_NAV_2BUTTONS:
                overlayPackage = NAV_BAR_MODE_2BUTTON_OVERLAY;
                break;
            case KEY_SYSTEM_NAV_3BUTTONS:
                overlayPackage = NAV_BAR_MODE_3BUTTON_OVERLAY;
                break;
        }

        try {
            mOverlayManager.setEnabledExclusiveInCategory(overlayPackage, USER_CURRENT);
        } catch (RemoteException e) {
            Slog.e(TAG, "Failed to set navigation mode", e);
        }
    }

    private void forceDesktopDensity(int displayId) {
        forceDesktopDensity(displayId, true);
    }

    private String savedDensityKey(int displayId) {
        return displayId == Display.DEFAULT_DISPLAY
                ? SETTING_SAVED_DENSITY_KEY
                : SETTING_SAVED_DENSITY_KEY + "_" + displayId;
    }

    private void forceDesktopDensity(int displayId, boolean saveCurrent) {
        if (mIWindowManager == null) return;

        BackgroundThread.getHandler().post(() -> {
            try {
                if (saveCurrent) {
                    String key = savedDensityKey(displayId);
                    int existingSaved = Settings.Secure.getIntForUser(
                            mContentResolver, key, -1, UserHandle.USER_CURRENT);
                    if (existingSaved == -1) {
                        int currentDensity = mIWindowManager.getBaseDisplayDensity(displayId);
                        Settings.Secure.putIntForUser(
                                mContentResolver,
                                key,
                                currentDensity,
                                UserHandle.USER_CURRENT
                        );
                        Slog.d(TAG, "Saved density: " + currentDensity
                                + " (display " + displayId + ")");
                    } else {
                        Slog.d(TAG, "Density backup already exists: " + existingSaved
                                + " (display " + displayId + "), skipping save");
                    }
                }

                boolean isSecondary = displayId != Display.DEFAULT_DISPLAY;
                String densityKey = isSecondary
                        ? SETTING_SECONDARY_DENSITY_KEY
                        : SETTING_DENSITY_OVERRIDE_KEY;
                int defaultDpi = isSecondary ? DEFAULT_SECONDARY_DPI : DESKTOP_MODE_DPI;
                int targetDpi = Settings.Secure.getIntForUser(
                        mContentResolver,
                        densityKey,
                        defaultDpi,
                        UserHandle.USER_CURRENT
                );
                mIWindowManager.setForcedDisplayDensityForUser(
                        displayId,
                        targetDpi,
                        UserHandle.USER_CURRENT
                );
                Slog.d(TAG, "Forced desktop density: " + targetDpi
                        + " on display " + displayId);
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to force desktop density", e);
            }
        });
    }

    private void restoreDisplayDensity(int displayId) {
        if (mIWindowManager == null) return;

        String key = savedDensityKey(displayId);
        BackgroundThread.getHandler().post(() -> {
            try {
                int savedDensity = Settings.Secure.getIntForUser(
                        mContentResolver, key, -1, UserHandle.USER_CURRENT);

                if (savedDensity != -1) {
                    int initialDensity = mIWindowManager.getInitialDisplayDensity(displayId);

                    if (savedDensity == initialDensity) {
                        mIWindowManager.clearForcedDisplayDensityForUser(
                                displayId, UserHandle.USER_CURRENT);
                        Slog.d(TAG, "Cleared forced density on display " + displayId);
                    } else {
                        mIWindowManager.setForcedDisplayDensityForUser(
                                displayId, savedDensity, UserHandle.USER_CURRENT);
                        Slog.d(TAG, "Restored density: " + savedDensity
                                + " on display " + displayId);
                    }

                    Settings.Secure.putStringForUser(
                            mContentResolver, key, null, UserHandle.USER_CURRENT);
                } else {
                    mIWindowManager.clearForcedDisplayDensityForUser(
                            displayId, UserHandle.USER_CURRENT);
                    Slog.d(TAG, "No saved density, clearing forced on display " + displayId);
                }
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to restore display density", e);
            }
        });
    }

    public boolean isPcModeEnabled() {
        return mPcModeEnabled;
    }

    public boolean isSecondaryDisplayOnly() {
        int id = getStoredTargetDisplayId();
        return id != Display.INVALID_DISPLAY && id != Display.DEFAULT_DISPLAY;
    }

    public int getAxPcModeDisplay() {
        int id = getStoredTargetDisplayId();
        if (id != Display.INVALID_DISPLAY && id != Display.DEFAULT_DISPLAY) return id;
        return 0;
    }

    private void updateResolutionOverride(int displayId) {
        if (mIWindowManager == null || !mPcModeEnabled) return;

        BackgroundThread.getHandler().post(() -> {
            String resolutionString = Settings.Secure.getStringForUser(
                    mContentResolver,
                    SETTING_RESOLUTION_OVERRIDE_KEY,
                    UserHandle.USER_CURRENT
            );

            try {
                if (resolutionString == null || resolutionString.isEmpty()) {
                    mIWindowManager.clearForcedDisplaySize(displayId);
                    Slog.d(TAG, "Cleared forced resolution on display " + displayId);
                    return;
                }

                String[] parts = resolutionString.split("x|X");
                if (parts.length != 2) {
                    Slog.e(TAG, "Invalid resolution format: " + resolutionString);
                    return;
                }

                int width = Integer.parseInt(parts[0].trim());
                int height = Integer.parseInt(parts[1].trim());

                if (width <= 0 || height <= 0) {
                    Slog.e(TAG, "Invalid resolution dimensions: " + width + "x" + height);
                    return;
                }

                mIWindowManager.setForcedDisplaySize(displayId, width, height);
                Slog.d(TAG, "Forced resolution to: " + width + "x" + height
                        + " on display " + displayId);

            } catch (NumberFormatException e) {
                Slog.e(TAG, "Failed to parse resolution: " + resolutionString, e);
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to set forced display size", e);
            }
        });
    }

    public void onDisplayAdded(int displayId) {
        Slog.i(TAG, "Display added (" + displayId + ")");
        DisplayManagerInternal dmi = LocalServices.getService(DisplayManagerInternal.class);
        if (dmi != null) {
            DisplayInfo info = dmi.getDisplayInfo(displayId);
            if (info != null && info.uniqueId != null
                    && info.uniqueId.startsWith(MiFreeformDisplayAdapter.UNIQUE_ID_PREFIX)) {
                Slog.d(TAG, "Ignoring freeform virtual display " + displayId
                        + " uniqueId=" + info.uniqueId);
                return;
            }
        }
        mHandler.post(() -> {
            setTargetDisplayId(displayId);
        });
    }

    public void onDisplayRemoved(int displayId) {
        int storedId = getStoredTargetDisplayId();
        if (storedId == displayId) {
            Slog.i(TAG, "Target display removed (" + displayId + "), restoring density and disabling app");
            mHandler.post(() -> {
                restoreDisplayDensity(displayId);
                setTargetDisplayId(Display.INVALID_DISPLAY);
            });
        }
    }

    public void onPcModeProcessDied() {
        if (mIntentionalDisable) {
            Slog.d(TAG, "AxPcMode process died after intentional disable, skipping recovery");
            mIntentionalDisable = false;
            return;
        }
        Slog.w(TAG, "AxPcMode process died unexpectedly, triggering recovery");
        mHandler.post(() -> {
            if (mPcModeEnabled) {
                updatePcModeState(true);
            }
        });
    }

    private void restoreDisplaySize(int displayId) {
        if (mIWindowManager == null) return;
        BackgroundThread.getHandler().post(() -> {
            try {
                mIWindowManager.clearForcedDisplaySize(displayId);
                Slog.d(TAG, "Restored display size on display " + displayId);
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to restore display size", e);
            }
        });
    }

    public void onScreenStateChanged(boolean isOff) {
        if (!isOff && mDisplayOffEnabled && mPcModeEnabled) {
            DisplayManagerInternal dmi = LocalServices.getService(DisplayManagerInternal.class);
            boolean mirrored = dmi != null && dmi.isDefaultDisplayMirrored();
            if (mirrored) {
                Slog.d(TAG, "Screen turned ON, but PC Mode Screen Off is active. Re-applying OFF state.");
                setInternalDisplayPowerMode(false);
            }
        }
    }
}
