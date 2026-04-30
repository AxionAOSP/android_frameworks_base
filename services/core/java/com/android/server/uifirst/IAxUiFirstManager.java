/*
 * Copyright (C) 2025-2026 AxionOS
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
package com.android.server.uifirst;

import android.content.pm.ApplicationInfo;

public interface IAxUiFirstManager {
    int ROLE_RT = 1;
    int ROLE_UI = 2;
    int ROLE_RENDER = 3;
    int ROLE_GL = 4;
    int ROLE_HWUI_TASK = 5;
    int ROLE_BINDER_POOL = 6;

    int STATUS_FOREGROUND = 1;
    int STATUS_BACKGROUND = 2;
    int STATUS_PROC_DIE = 3;
    int STATUS_RESUME_ACTIVITY = 4;
    int STATUS_TOP = 5;
    int STATUS_PAUSE_ACTIVITY = 6;

    default void setUxThreads(int uid, int pid, int[] tids, int role) {}

    default void clearUxThreads(int uid, int pid, int role) {}

    default void applyTopAppRoles(int uid, int pid, int renderTid) {}

    default void applyTopAppRoles(int uid, int pid, int renderTid, int[] hwuiTids) {}

    default void adjustTopApp(String packageName, int uid, int pid, int renderTid, int[] hwuiTids) {}

    default void adjustUxProcess(ApplicationInfo ai, String packageName, int status, int uid, int pid,
            int renderTid, int[] hwuiTids, boolean isRemoteAnimation) {}

    default void setTaskAsRemoteAnimationUx(int pid, int renderTid, int[] hwuiTids,
            String packageName, boolean isRemoteAnimation) {}

    default void onAppStatusChanged(int status, String packageName, String activityName, int uid) {}

    default void onRenderThreadTid(String packageName, int uid, int pid, int renderTid) {}

    default void setRenderThreadTid(String packageName, int uid, int pid, int renderTid) {}

    default void onHwuiTaskThreads(int uid, int pid, int[] hwuiTids) {}

    default void addApplicationHwuiTaskThread(String packageName, int uid, int pid, int tid) {}

    default void addApplicationGlThread(String packageName, int uid, int pid, int tid) {}

    default void removeApplicationGlThread(String packageName, int pid, int tid) {}

    default int getApplicationGlThreadValue(String packageName) {
        return 0;
    }

    default void handleProcessStart(
            String packageName, int uid, int pid, boolean isolated, String processName) {}

    default void handleProcessStop(String packageName, int uid, int pid) {}

    default void setApplicationKeyThreads(String packageName, int uid, int op, int pid, int[] tids) {}

    default void clearTopAppRoles(int uid, int pid) {}

    default void notifyUiSwitched(String uiInfo, int status) {}

    default void onPanelRevealed(int items) {}

    default void onPanelHidden() {}

    default void onProcessDied(int pid) {}

    default void setBinderThreadUxFlag(int pid, int flag) {}

    default void setBinderUx(int uid, int pid, boolean enable) {}

    default void setImeRelevant(int uid, int pid, boolean enable) {}

    default void boostTopAppForEarlyWakeup(int pid, int renderTid, boolean active) {}

    default void dampenMediaForInputBurst(int pid, boolean active) {}
}
