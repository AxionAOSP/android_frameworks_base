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
import android.os.SystemProperties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class WindowEventDispatcher {

    private static WindowEventDispatcher sInstance;

    private final CopyOnWriteArrayList<IWindowEventListener> mListeners = new CopyOnWriteArrayList<>();

    private final ExecutorService mNotifierExecutor = Executors.newSingleThreadExecutor();

    private String mFocusedPackageName;

    private static final ComponentName GMS_ADD_ACCOUNT_ACTIVITY = ComponentName.unflattenFromString(
            "com.google.android.gms/.auth.uiflows.minutemaid.MinuteMaidActivity");

    private WindowEventDispatcher() {}

    public static synchronized WindowEventDispatcher get() {
        if (sInstance == null) {
            sInstance = new WindowEventDispatcher();
        }
        return sInstance;
    }

    public void registerListener(IWindowEventListener listener) {
        if (listener != null && !mListeners.contains(listener)) {
            mListeners.add(listener);
        }
    }

    public void unregisterListener(IWindowEventListener listener) {
        if (listener != null) {
            mListeners.remove(listener);
        }
    }

    public void notifyAppFocusChanged(final ActivityRecord r, final Task task) {
        mFocusedPackageName = (r != null && r.packageName != null) ? r.packageName : null;
        final boolean gmsOnTop = task != null && task.getTopMostActivity() != null 
            && GMS_ADD_ACCOUNT_ACTIVITY.equals(task.getTopMostActivity().packageName);
        String pixelPropsGms = SystemProperties.get("persist.sys.pixelprops.gms", "true");
        if (!"false".equals(pixelPropsGms)) {
            String newValue = gmsOnTop ? "true" : "false";
            String currentValue = SystemProperties.get("persist.sys.disable_gms_hook", "false");
            if (!newValue.equals(currentValue)) {
                SystemProperties.set("persist.sys.disable_gms_hook", newValue);
            }
        }
        for (final IWindowEventListener listener : mListeners) {
            mNotifierExecutor.execute(() -> listener.onAppFocusChanged(r, task));
        }
    }

    public void notifyWindowingModeChanged(final Task task, final int mode) {
        for (final IWindowEventListener listener : mListeners) {
            mNotifierExecutor.execute(() -> listener.onWindowingModeChanged(task, mode));
        }
    }

    public void notifyKeyguardDoneLocked(final boolean showing) {
        for (final IWindowEventListener listener : mListeners) {
            mNotifierExecutor.execute(() -> listener.setKeyguardDoneLocked(showing));
        }
    }

    public void notifyTaskRemoved(final Task task, final String reason) {
        for (final IWindowEventListener listener : mListeners) {
            mNotifierExecutor.execute(() -> listener.removeTask(task, reason));
        }
    }

    public String getFocusedPackageName() {
        return mFocusedPackageName;
    }
}
