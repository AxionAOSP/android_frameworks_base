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
package com.android.internal.util;

import android.app.AppLockManager;
import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.app.IAppLockListener;

public final class NTAppLockerHelper {

    private static final String TAG = "NTAppLockerHelper";

    private static final NTAppLockerHelper sInstance = new NTAppLockerHelper();

    private Context mContext;
    private AppLockManager mAppLockManager;

    private NTAppLockerHelper() {}

    public static synchronized void init(Context context) {
        if (context == null) {
            Log.w(TAG, "init() called with null context");
            return;
        }

        if (sInstance.mContext == null) {
            sInstance.mContext = context.getApplicationContext();
            sInstance.mAppLockManager = sInstance.mContext.getSystemService(AppLockManager.class);
            Log.d(TAG, "NTAppLockerHelper initialized");
        } else {
            Log.w(TAG, "NTAppLockerHelper already initialized. Ignoring subsequent init.");
        }
    }

    public static NTAppLockerHelper get() {
        return sInstance;
    }

    private AppLockManager getService() {
        if (mAppLockManager == null && mContext != null) {
            mAppLockManager = mContext.getSystemService(AppLockManager.class);
        }
        return mAppLockManager;
    }

    public boolean isAppLocked(String packageName) {
        if (TextUtils.isEmpty(packageName)) return false;
        AppLockManager service = getService();
        if (service == null) return false;
        try {
            return service.isAppLocked(packageName);
        } catch (Exception e) {
            Log.w(TAG, "Failed to check app lock for: " + packageName, e);
            return false;
        }
    }

    public boolean isPackageHidden(String packageName) {
        if (TextUtils.isEmpty(packageName)) return false;
        AppLockManager service = getService();
        if (service == null) return false;
        try {
            return service.isPackageHidden(packageName);
        } catch (Exception e) {
            Log.w(TAG, "Failed to check if package is hidden: " + packageName, e);
            return false;
        }
    }

    public void addLockedApp(String packageName) {
        if (TextUtils.isEmpty(packageName)) return;
        AppLockManager service = getService();
        if (service == null) return;
        try {
            service.addLockedApp(packageName);
        } catch (Exception e) {
            Log.w(TAG, "Failed to add locked app: " + packageName, e);
        }
    }

    public void removeLockedApp(String packageName) {
        if (TextUtils.isEmpty(packageName)) return;
        AppLockManager service = getService();
        if (service == null) return;
        try {
            service.removeLockedApp(packageName);
        } catch (Exception e) {
            Log.w(TAG, "Failed to remove locked app: " + packageName, e);
        }
    }

    public void setPackageHidden(String packageName, boolean hidden) {
        if (TextUtils.isEmpty(packageName)) return;
        AppLockManager service = getService();
        if (service == null) return;
        try {
            service.setPackageHidden(packageName, hidden);
        } catch (Exception e) {
            Log.w(TAG, "Failed to set package hidden: " + packageName + ", hidden: " + hidden, e);
        }
    }

    public void registerListener(IAppLockListener listener) {
        if (listener == null) return;
        AppLockManager service = getService();
        if (service == null) return;
        try {
            service.registerListener(listener);
        } catch (Exception e) {
            Log.w(TAG, "Failed to register AppLock listener", e);
        }
    }

    public void unregisterListener(IAppLockListener listener) {
        if (listener == null) return;
        AppLockManager service = getService();
        if (service == null) return;
        try {
            service.unregisterListener(listener);
        } catch (Exception e) {
            Log.w(TAG, "Failed to unregister AppLock listener", e);
        }
    }

    public static boolean isInitialized() {
        return sInstance.mContext != null;
    }
}
