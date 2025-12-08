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
package android.app;

import android.os.IBinder;
import android.os.ServiceManager;
import android.util.Log;
import android.view.Surface;

/** @hide */
public class FreeformLauncher {
    private static final String TAG = "FreeformLauncher";
    
    private static IFreeformOverlayManager sService;
    
    private static IFreeformOverlayManager getService() {
        if (sService == null) {
            sService = IFreeformOverlayManager.Stub.asInterface(
                ServiceManager.getService("freeform_overlay"));
        }
        return sService;
    }

    /** @hide */
    public static void launch(String packageName) {
        try {
            IFreeformOverlayManager service = getService();
            if (service != null) {
                service.launchInFreeform(packageName);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to launch in freeform", e);
        }
    }

    /** @hide */
    @SuppressWarnings("ReferencesHidden")
    public static void createFreeform(String name, IFreeformDisplayCallback callback,
            int width, int height, int densityDpi, boolean secure,
            boolean ownContentOnly, boolean shouldShowSystemDecorations, Surface surface,
            float refreshRate, long presentationDeadlineNanos) {
        try {
            IFreeformOverlayManager service = getService();
            if (service != null) {
                service.createFreeform(name, callback, width, height, densityDpi, secure,
                        ownContentOnly, shouldShowSystemDecorations, surface, refreshRate,
                        presentationDeadlineNanos);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to create freeform display", e);
        }
    }

    /** @hide */
    public static void launchAppOnDisplay(String packageName, String activityName, 
            int displayId, int userId) {
        try {
            IFreeformOverlayManager service = getService();
            if (service != null) {
                service.launchAppOnDisplay(packageName, activityName, displayId, userId);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to launch app on display", e);
        }
    }

    /** @hide */
    public static void pauseDisplay(int displayId) {
        try {
            IFreeformOverlayManager service = getService();
            if (service != null) {
                service.pauseDisplay(displayId);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to pause display", e);
        }
    }

    /** @hide */
    public static void resumeDisplay(int displayId) {
        try {
            IFreeformOverlayManager service = getService();
            if (service != null) {
                service.resumeDisplay(displayId);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to resume display", e);
        }
    }

    /** @hide */
    public static void resizeFreeform(IBinder appToken, int width, int height, 
            int densityDpi) {
        try {
            IFreeformOverlayManager service = getService();
            if (service != null) {
                service.resizeFreeform(appToken, width, height, densityDpi);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to resize freeform display", e);
        }
    }

    /** @hide */
    public static void releaseFreeform(IBinder appToken) {
        try {
            IFreeformOverlayManager service = getService();
            if (service != null) {
                service.releaseFreeform(appToken);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to release freeform display", e);
        }
    }
}