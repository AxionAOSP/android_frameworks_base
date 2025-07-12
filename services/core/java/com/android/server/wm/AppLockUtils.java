/*
 * Copyright (C) 2025 AxionOS Project
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

import android.content.ComponentName;
import android.content.Intent;
import android.util.Slog;

public class AppLockUtils {
    private static final String TAG = "AppLockUtils";

    private static IAppLockService getService() {
        IAppLockService service = AppLockManagerService.get();
        if (service == null) {
            Slog.w(TAG, "AppLockService not available");
        }
        return service;
    }

    public static boolean isAppLocked(ActivityRecord r) {
        IAppLockService service = getService();
        return service != null && r != null && service.isAppLocked(r);
    }

    public static boolean checkUnlockApp(ActivityRecord r, int i, Intent intent) {
        IAppLockService service = getService();
        return service != null && r != null && service.checkUnlockApp(r, i, intent);
    }

    public static boolean isAppLockerActivity(ComponentName cmp) {
        IAppLockService service = getService();
        return service != null && cmp != null && service.isAppLockerActivity(cmp);
    }
    
    public static boolean checkLockApp(ActivityRecord r, ActivityRecord r2) {
        IAppLockService service = getService();
        return service != null && r != null && service.checkLockApp(r, r2);
    }

    public static void clearUnlockedApp(ActivityRecord record) {
        IAppLockService service = getService();
        if (service != null && record != null) {
            service.clearUnlockedApp(record);
        }
    }
}
