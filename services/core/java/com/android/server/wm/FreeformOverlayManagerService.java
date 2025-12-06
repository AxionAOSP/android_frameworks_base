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

import android.app.ActivityOptions;
import android.app.ActivityTaskManager;
import android.app.IActivityTaskManager;
import android.app.IFreeformDisplayCallback;
import android.app.IFreeformOverlayManager;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManagerInternal;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.view.Surface;
import com.android.server.LocalServices;
import com.android.server.SystemService;

public class FreeformOverlayManagerService extends SystemService {
    private final FreeformOverlayManagerImpl mService = new FreeformOverlayManagerImpl();

    public FreeformOverlayManagerService(Context context) {
        super(context);
    }

    @Override
    public void onStart() {
        publishBinderService("freeform_overlay", mService);
    }

    private final class FreeformOverlayManagerImpl extends IFreeformOverlayManager.Stub {
        private static final String EDGE_SERVICE_PACKAGE = "com.android.edge.bar";
        private static final String EDGE_SERVICE_CLASS = "com.android.edge.bar.EdgeService";
        private static final String ACTION_LAUNCH_FREEFORM = "com.android.edge.bar.ACTION_LAUNCH_FREEFORM";
        private static final String EXTRA_PACKAGE_NAME = "package_name";
        
        @Override
        public void launchInFreeform(String packageName) {
            final long token = Binder.clearCallingIdentity();
            try {
                Intent intent = new Intent(ACTION_LAUNCH_FREEFORM);
                intent.setClassName(EDGE_SERVICE_PACKAGE, EDGE_SERVICE_CLASS);
                intent.putExtra(EXTRA_PACKAGE_NAME, packageName);
                getContext().startServiceAsUser(intent, UserHandle.CURRENT);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }
        
        @Override
        public void createFreeform(String name, IFreeformDisplayCallback callback,
                int width, int height, int densityDpi, boolean secure,
                boolean ownContentOnly, boolean shouldShowSystemDecorations, Surface surface,
                float refreshRate, long presentationDeadlineNanos) {
            final long token = Binder.clearCallingIdentity();
            try {
                DisplayManagerInternal dmi = LocalServices.getService(DisplayManagerInternal.class);
                if (dmi != null) {
                    dmi.createFreeformDisplay(name, callback, width, height, densityDpi, secure,
                            ownContentOnly, shouldShowSystemDecorations, surface, refreshRate,
                            presentationDeadlineNanos);
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }
        
        @Override
        public void pauseDisplay(int displayId) {
            final long token = Binder.clearCallingIdentity();
            try {
                DisplayManagerInternal dmi = LocalServices.getService(DisplayManagerInternal.class);
                if (dmi != null) {
                    dmi.pauseFreeformDisplay(displayId);
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }
        
        @Override
        public void resumeDisplay(int displayId) {
            final long token = Binder.clearCallingIdentity();
            try {
                DisplayManagerInternal dmi = LocalServices.getService(DisplayManagerInternal.class);
                if (dmi != null) {
                    dmi.resumeFreeformDisplay(displayId);
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }
        
        @Override
        public void launchAppOnDisplay(String packageName, String activityName, int displayId, int userId) {
            final long token = Binder.clearCallingIdentity();
            try {
                Intent intent = new Intent();
                intent.setClassName(packageName, activityName);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                
                ActivityOptions options = ActivityOptions.makeBasic();
                options.setLaunchDisplayId(displayId);
                
                getContext().startActivityAsUser(intent, options.toBundle(), UserHandle.of(userId));
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }
        
        @Override
        public void resizeFreeform(IBinder appToken, int width, int height, int densityDpi) {
            final long token = Binder.clearCallingIdentity();
            try {
                DisplayManagerInternal dmi = LocalServices.getService(DisplayManagerInternal.class);
                if (dmi != null) {
                    dmi.resizeFreeform(appToken, width, height, densityDpi);
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }
        
        @Override
        public void releaseFreeform(IBinder appToken) {
            final long token = Binder.clearCallingIdentity();
            try {
                DisplayManagerInternal dmi = LocalServices.getService(DisplayManagerInternal.class);
                if (dmi != null) {
                    dmi.releaseFreeform(appToken);
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }
    }
}
