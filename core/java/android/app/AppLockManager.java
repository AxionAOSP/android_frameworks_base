/*
 * Copyright (C) 2025 AxionOS Project
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
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.RemoteException;

import com.android.internal.app.IAppLockListener;
import com.android.internal.app.IAppLockManager;

/**
 * @hide
 */
@SystemService(Context.APP_LOCK_SERVICE)
public class AppLockManager {

    private final Context mContext;
    private final IAppLockManager mService;

    /** @hide */
    public AppLockManager(@NonNull Context context, @NonNull IAppLockManager service) {
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
    public void registerListener(IAppLockListener listener) {
        try {
            mService.registerListener(listener);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     */
    public void unregisterListener(IAppLockListener listener) {
        try {
            mService.unregisterListener(listener);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
}
