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
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManagerInternal;
import android.net.Uri;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.view.Display;
import android.view.Gravity;
import android.view.IWindowManager;
import android.view.SurfaceControl;
import android.view.View;
import android.view.WindowManager;
import android.util.DisplayMetrics;
import android.util.Slog;

import com.android.internal.os.BackgroundThread;

import com.android.server.LocalServices;
import com.android.server.display.DisplayControl;
import com.android.server.wm.WindowManagerInternal;

public class AxPcModeService implements IAxPcModeService {
    private static final String TAG = "AxPcModeService";

    private static final int DESKTOP_MODE_DPI = 284;
    private static final int PC_MODE_ANIM_INTRO_DURATION = 2000;
    private static final float TABLET_MIN_DPS = 600;
    
    private static final String SETTING_AX_PC_MODE = "ax_pc_mode";
    private static final String SETTING_SAVED_NAV_KEY = "ax_pc_mode_saved_nav_key";
    private static final String SETTING_SAVED_DENSITY_KEY = "ax_pc_mode_saved_density";
    private static final String SETTING_RESOLUTION_OVERRIDE_KEY = "ax_pc_mode_resolution_override";
    private static final String SETTING_DISPLAY_OFF_KEY = "ax_pc_mode_display_off";

    private static final String KEY_SYSTEM_NAV_GESTURAL = "system_nav_gestural";
    private static final String KEY_SYSTEM_NAV_2BUTTONS = "system_nav_2buttons";
    private static final String KEY_SYSTEM_NAV_3BUTTONS = "system_nav_3buttons";

    private static final String AX_PC_MODE_PKG = "com.android.axion.axpcmode";
    private static final String AX_PC_MODE_ACTIVITY =
            "com.android.axion.axpcmode.activities.PcModeLauncherActivity";
    private static final String AX_TASKBAR_SERVICE_CLASS =
            "com.android.axion.axpcmode.services.TaskbarService";
    private static final String ACTION_START_TASKBAR =
            "com.android.axion.axpcmode.START_TASKBAR";
    private static final String ACTION_STOP_TASKBAR =
            "com.android.axion.axpcmode.STOP_TASKBAR";

    private Context mContext;
    private Handler mHandler;
    private ContentResolver mContentResolver;
    private IOverlayManager mOverlayManager;
    private IWindowManager mIWindowManager;

    private boolean mPcModeEnabled = false;
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
                updateResolutionOverride();
            } else if (uri.equals(Settings.Secure.getUriFor(SETTING_DISPLAY_OFF_KEY))) {
                updateDisplayOffState();
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
        mIWindowManager = IWindowManager.Stub.asInterface(ServiceManager.getService(Context.WINDOW_SERVICE));
        mWindowManager = LocalServices.getService(WindowManagerInternal.class);
        mSystemReady = true;
        mHandler.post(() -> {
            updatePcModeState();
            updateResolutionOverride();
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
                setAppEnabled(true);
                
                forceDesktopDensity();
                forceThreeButtonNavigation();

                Intent intent = new Intent();
                intent.setComponent(new ComponentName(AX_PC_MODE_PKG, AX_PC_MODE_ACTIVITY));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                mContext.startActivityAsUser(intent, UserHandle.CURRENT);

                Intent serviceIntent = new Intent();
                serviceIntent.setComponent(new ComponentName(
                        AX_PC_MODE_PKG,
                        AX_TASKBAR_SERVICE_CLASS
                ));
                serviceIntent.setAction(ACTION_START_TASKBAR);
                Slog.d(TAG, "Starting TaskbarService");
                mContext.startForegroundServiceAsUser(serviceIntent, UserHandle.CURRENT);
            } else {
                restoreNavigationMode();
                restoreDisplayDensity();
                restoreDisplaySize();

                setInternalDisplayPowerMode(true);

                Intent animIntent = new Intent();
                animIntent.setComponent(new ComponentName(
                        AX_PC_MODE_PKG,
                        "com.android.axion.axpcmode.activities.PcModeAnimationActivity"
                ));
                animIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                Slog.d(TAG, "Launching PcModeAnimationActivity");
                mContext.startActivityAsUser(animIntent, UserHandle.CURRENT);

                Intent serviceIntent = new Intent();
                serviceIntent.setComponent(new ComponentName(
                        AX_PC_MODE_PKG,
                        AX_TASKBAR_SERVICE_CLASS
                ));
                serviceIntent.setAction(ACTION_STOP_TASKBAR);
                Slog.d(TAG, "Stopping TaskbarService");
                mContext.startServiceAsUser(serviceIntent, UserHandle.CURRENT);

                mHandler.postDelayed(() -> {
                    Intent homeIntent = new Intent(Intent.ACTION_MAIN);
                    homeIntent.addCategory(Intent.CATEGORY_HOME);
                    homeIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    try {
                        mContext.startActivityAsUser(homeIntent, UserHandle.CURRENT);
                        Slog.d(TAG, "Launched home intent after exit animation");
                    } catch (Exception e) {
                        Slog.e(TAG, "Failed to launch home intent", e);
                    }
                    setAppEnabled(false);
                }, PC_MODE_ANIM_INTRO_DURATION);
            }
        } catch (Exception e) {
            Slog.e(TAG, "Failed to control TaskbarService", e);
        }
    }

    private void setAppEnabled(boolean enabled) {
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

    private void forceDesktopDensity() {
        if (mIWindowManager == null) return;
        
        BackgroundThread.getHandler().post(() -> {
            WindowManager wm = mContext.getSystemService(WindowManager.class);
            if (isLargeScreen(wm, mContext.getResources())) {
                Slog.d(TAG, "Skipping density force for large screen device");
                return;
            }

            try {
                int currentDensity = mIWindowManager.getBaseDisplayDensity(Display.DEFAULT_DISPLAY);

                Settings.Secure.putIntForUser(
                        mContentResolver,
                        SETTING_SAVED_DENSITY_KEY,
                        currentDensity,
                        UserHandle.USER_CURRENT
                );
                Slog.d(TAG, "Saved density: " + currentDensity);

                mIWindowManager.setForcedDisplayDensityForUser(
                        Display.DEFAULT_DISPLAY,
                        DESKTOP_MODE_DPI,
                        UserHandle.USER_CURRENT
                );
                Slog.d(TAG, "Forced desktop density: " + DESKTOP_MODE_DPI);
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to force desktop density", e);
            }
        });
    }

    private void restoreDisplayDensity() {
        if (mIWindowManager == null) return;
        
        BackgroundThread.getHandler().post(() -> {
            try {
                int savedDensity = Settings.Secure.getIntForUser(
                        mContentResolver,
                        SETTING_SAVED_DENSITY_KEY,
                        -1,
                        UserHandle.USER_CURRENT
                );
            
                if (savedDensity != -1) {
                    int initialDensity = mIWindowManager.getInitialDisplayDensity(Display.DEFAULT_DISPLAY);
                    
                    if (savedDensity == initialDensity) {
                        mIWindowManager.clearForcedDisplayDensityForUser(
                                Display.DEFAULT_DISPLAY,
                                UserHandle.USER_CURRENT
                        );
                        Slog.d(TAG, "Cleared forced density (restored to initial)");
                    } else {
                        mIWindowManager.setForcedDisplayDensityForUser(
                                Display.DEFAULT_DISPLAY,
                                savedDensity,
                                UserHandle.USER_CURRENT
                        );
                        Slog.d(TAG, "Restored saved density: " + savedDensity);
                    }

                    Settings.Secure.putStringForUser(
                            mContentResolver,
                            SETTING_SAVED_DENSITY_KEY,
                            null,
                            UserHandle.USER_CURRENT
                        );
                }
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to restore display density", e);
            }
        });
    }
    
    public boolean isPcModeEnabled() {
        return mPcModeEnabled;
    }
    
    public static boolean isLargeScreen(WindowManager windowManager, Resources resources) {
        final Rect bounds = windowManager.getMaximumWindowMetrics().getBounds();

        float smallestWidth = dpiFromPx(Math.min(bounds.width(), bounds.height()),
                resources.getConfiguration().densityDpi);
        return smallestWidth >= TABLET_MIN_DPS;
    }
    
    private void updateResolutionOverride() {
        if (mIWindowManager == null || !mPcModeEnabled) return;
    
        BackgroundThread.getHandler().post(() -> {
            String resolutionString = Settings.Secure.getStringForUser(
                    mContentResolver,
                    SETTING_RESOLUTION_OVERRIDE_KEY,
                    UserHandle.USER_CURRENT
            );

            try {
                if (resolutionString == null || resolutionString.isEmpty()) {
                    mIWindowManager.clearForcedDisplaySize(Display.DEFAULT_DISPLAY);
                    Slog.d(TAG, "Cleared forced resolution");
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
                
                mIWindowManager.setForcedDisplaySize(Display.DEFAULT_DISPLAY, width, height);
                Slog.d(TAG, "Forced resolution to: " + width + "x" + height);

            } catch (NumberFormatException e) {
                Slog.e(TAG, "Failed to parse resolution: " + resolutionString, e);
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to set forced display size", e);
            }
        });
    }

    public static float dpiFromPx(float size, int densityDpi) {
        float densityRatio = (float) densityDpi / DisplayMetrics.DENSITY_DEFAULT;
        return (size / densityRatio);
    }

    public void onPcModeProcessDied() {
        Slog.w(TAG, "AxPcMode process died while enabled, triggering recovery");
        mHandler.post(() -> {
            if (mPcModeEnabled) {
                updatePcModeState(true);
            }
        });
    }

    private void restoreDisplaySize() {
        if (mIWindowManager == null) return;
        BackgroundThread.getHandler().post(() -> {
            try {
                mIWindowManager.clearForcedDisplaySize(Display.DEFAULT_DISPLAY);
                Slog.d(TAG, "Restored display size (cleared forced resolution)");
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
