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
package com.android.server.am;

import android.content.pm.ApplicationInfo;

public interface IAxBurstEngine {

    default void systemReady() {}

    default void getProcessesAndFrozen(String resumePackageName) {}

    default void setCancelCompactionCallback(Runnable r) {}

    default void setThermalState(int level, int cpuCap, int gpuCap) {}

    default long acquireHint(int opcode, long durMs) {
        return 0L;
    }

    default long updateTopApp(String processName, int pid, int tid) {
        return -1;
    }

    default void onFrameDraw() {}

    default void onFrameRealDraw(long durMs) {}

    default int perfLockAcquire(int duration, int[] list) {
        return -1;
    }

    default void perfHintRelease(long handle) {}
    
    default int perfGetFeedback(ApplicationInfo ai, String packageName) {
        return 0;
    }
    default void systemThreadBoost(int tid, long duration) {}

    default void boostGame(boolean enable) {}

    default void boostInstall(boolean boost) {}

    default void boostThread(int tid) {}

    default void launcherItemsLoadingBoost(long duration) {}

    default boolean isCompositionBoosting() {
        return false;
    }

    default boolean shouldDeferProcessPss() {
        return false;
    }

    default void pinApp(ApplicationInfo info) {}

    default void unpinApp(String packageName) {}
}
