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

public class WindowEventHelper {

    public static void setKeyguardDoneLocked(boolean showing) {
        WindowEventDispatcher.get().notifyKeyguardDoneLocked(showing);
    }

    public static void onAppFocusChanged(ActivityRecord record, Task task) {
        WindowEventDispatcher.get().notifyAppFocusChanged(record, task);
    }

    public static void onWindowingModeChanged(Task task, int mode) {
        WindowEventDispatcher.get().notifyWindowingModeChanged(task, mode);
    }

    public static void removeTask(Task task, String reason) {
        WindowEventDispatcher.get().notifyTaskRemoved(task, reason);
    }
}
