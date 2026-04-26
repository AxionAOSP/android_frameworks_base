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

public interface IAxUiFirstManager {
    int ROLE_RT = 1;
    int ROLE_UI = 2;
    int ROLE_RENDER = 3;
    int ROLE_GL = 4;
    int ROLE_HWUI_TASK = 5;
    int ROLE_BINDER_POOL = 6;

    default void setUxThreads(int uid, int pid, int[] tids, int role) {}

    default void clearUxThreads(int uid, int pid, int role) {}

    default void applyTopAppRoles(int uid, int pid, int renderTid) {}

    default void clearTopAppRoles(int uid, int pid) {}

    default void setBinderUx(int uid, int pid, boolean enable) {}

    default void setImeRelevant(int uid, int pid, boolean enable) {}

    default void boostTopAppForEarlyWakeup(int pid, int renderTid, boolean active) {}

    default void dampenMediaForInputBurst(int pid, boolean active) {}
}
