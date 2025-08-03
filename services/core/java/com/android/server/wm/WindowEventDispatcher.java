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

import java.util.concurrent.CopyOnWriteArrayList;

public class WindowEventDispatcher {

    private static WindowEventDispatcher sInstance;

    private final CopyOnWriteArrayList<IWindowEventListener> mListeners = new CopyOnWriteArrayList<>();
    
    private String mFocusedPackageName;

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

    public void notifyAppFocusChanged(ActivityRecord r, Task task) {
        mFocusedPackageName = (r != null && r.packageName != null) ? r.packageName : null;
        for (IWindowEventListener listener : mListeners) {
            listener.onAppFocusChanged(r, task);
        }
    }

    public void notifyWindowingModeChanged(Task task, int mode) {
        for (IWindowEventListener listener : mListeners) {
            listener.onWindowingModeChanged(task, mode);
        }
    }

    public void notifyKeyguardDoneLocked(boolean showing) {
        for (IWindowEventListener listener : mListeners) {
            listener.setKeyguardDoneLocked(showing);
        }
    }
    
    public void notifyTaskRemoved(Task task, String reason) {
        for (IWindowEventListener listener : mListeners) {
            listener.removeTask(task, reason);
        }
    }
    
    public String getFocusedPackageName() {
        return mFocusedPackageName;
    }
}
