/*
 * Copyright (C) 2025 AxionOS 
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */
package android.app;

import android.annotation.NonNull;
import android.annotation.SystemService;
import android.content.Context;
import android.os.RemoteException;


import com.android.internal.app.IAppLockStateListener;
import com.android.internal.app.IAxSandboxManager;
import com.android.internal.app.IHiddenNotificationListener;
import com.android.internal.app.HiddenNotificationInfo;

import java.util.Collections;
import java.util.List;

/**
 * @hide
 */
@SystemService(Context.AX_SANDBOX_SERVICE)
public class AxSandboxManager {

    private final Context mContext;
    private final IAxSandboxManager mService;

    /** @hide */
    public AxSandboxManager(@NonNull Context context, @NonNull IAxSandboxManager service) {
        mContext = context;
        mService = service;
    }

    /**
     * @hide
     */
    public boolean isAppLocked(@NonNull String packageName) {
        try {
            return mService.isAppLocked(packageName);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     */
    public void addLockedApp(@NonNull String packageName) {
        try {
            mService.addLockedApp(packageName);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     */
    public void removeLockedApp(@NonNull String packageName) {
        try {
            mService.removeLockedApp(packageName);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     */
    public boolean isPackageHidden(@NonNull String packageName) {
        try {
            return mService.isPackageHidden(packageName);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     */
    public void setPackageHidden(@NonNull String packageName, boolean hidden) {
        try {
            mService.setPackageHidden(packageName, hidden);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     */
    @NonNull
    public List<String> getLockedPackages() {
        try {
            List<String> result = mService.getLockedPackages();
            return result != null ? result : Collections.emptyList();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     */
    @NonNull
    public List<String> getHiddenPackages() {
        try {
            List<String> result = mService.getHiddenPackages();
            return result != null ? result : Collections.emptyList();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     */
    @NonNull
    public List<String> getLockablePackages() {
        try {
            List<String> result = mService.getLockablePackages();
            return result != null ? result : Collections.emptyList();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     */
    public boolean isPackageLockable(@NonNull String packageName) {
        try {
            return mService.isPackageLockable(packageName);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     */
    public void unlockApp(@NonNull String packageName, int userId) {
        try {
            mService.unlockApp(packageName, userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     */
    public void promptUnlock(@NonNull String packageName, int userId) {
        try {
            mService.promptUnlock(packageName, userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     */
    public void registerAppLockStateListener(IAppLockStateListener listener) {
        try {
            mService.registerAppLockStateListener(listener);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     */
    public void unregisterAppLockStateListener(IAppLockStateListener listener) {
        try {
            mService.unregisterAppLockStateListener(listener);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    // ==================== Hidden Notification Listeners ====================


    /**
     * @hide
     */
    public void registerHiddenNotificationListener(IHiddenNotificationListener listener) {
        try {
            mService.registerHiddenNotificationListener(listener);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     */
    public void unregisterHiddenNotificationListener(IHiddenNotificationListener listener) {
        try {
            mService.unregisterHiddenNotificationListener(listener);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     */
    public List<HiddenNotificationInfo> getHiddenNotifications() {
        try {
            return mService.getHiddenNotifications();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     */
    public void onHiddenNotificationPosted(HiddenNotificationInfo info) {
        try {
            mService.onHiddenNotificationPosted(info);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     */
    public void onHiddenNotificationRemoved(String key) {
        try {
            mService.onHiddenNotificationRemoved(key);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     */
    public boolean isPackageSandboxed(@NonNull String packageName) {
        try {
            return mService.isPackageSandboxed(packageName);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     */
    public void addSandboxedPackage(@NonNull String packageName) {
        try {
            mService.addSandboxedPackage(packageName);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     */
    public void removeSandboxedPackage(@NonNull String packageName) {
        try {
            mService.removeSandboxedPackage(packageName);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     */
    @NonNull
    public List<String> getSandboxedPackages() {
        try {
            List<String> result = mService.getSandboxedPackages();
            return result != null ? result : Collections.emptyList();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     */
    public boolean isDevOptionsHidden(@NonNull String packageName) {
        try {
            return mService.isDevOptionsHidden(packageName);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     */
    public void addDevOptionsHiddenPackage(@NonNull String packageName) {
        try {
            mService.addDevOptionsHiddenPackage(packageName);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     */
    public void removeDevOptionsHiddenPackage(@NonNull String packageName) {
        try {
            mService.removeDevOptionsHiddenPackage(packageName);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     */
    @NonNull
    public List<String> getDevOptionsHiddenPackages() {
        try {
            List<String> result = mService.getDevOptionsHiddenPackages();
            return result != null ? result : Collections.emptyList();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
}
