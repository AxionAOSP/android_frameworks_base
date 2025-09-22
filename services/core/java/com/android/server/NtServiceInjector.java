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
package com.android.server;

import android.content.Context;

import com.android.server.am.ActivityManagerService;
import com.android.server.wm.WindowManagerService;

public class NtServiceInjector {
    private static NtServiceInjector instance;
    private Context mContext;
    private WindowManagerService mWindowManagerService;
    private ActivityManagerService mActivityManagerService;

    private NtServiceInjector() {
    }

    public static synchronized NtServiceInjector get() {
        if (instance == null) {
            instance = new NtServiceInjector();
        }
        return instance;
    }

    public void setContext(Context context) {
        mContext = context;
    }

    public void setWindowManagerService(WindowManagerService service) {
        mWindowManagerService = service;
    }

    public void setActivityManagerService(ActivityManagerService ams) {
        mActivityManagerService = ams;
    }

    public Context getContext() {
        return mContext;
    }

    public WindowManagerService getWindowManagerService() {
        if (mWindowManagerService == null) {
            throw new IllegalStateException("WindowManagerService not initialized yet.");
        }
        return mWindowManagerService;
    }

    public ActivityManagerService getActivityManagerService() {
        if (mActivityManagerService == null) {
            throw new IllegalStateException("ActivityManagerService not initialized yet.");
        }
        return mActivityManagerService;
    }

    public static Context getCtx() {
        return get().getContext();
    }

    public static WindowManagerService getWm() {
        return get().getWindowManagerService();
    }

    public static ActivityManagerService getAm() {
        return get().getActivityManagerService();
    }
}
