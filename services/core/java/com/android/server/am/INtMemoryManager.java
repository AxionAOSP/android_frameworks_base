/*
 * Copyright (C) 2025 AxionOS
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

import android.content.Context;
import com.android.server.wm.WindowManagerService;

public interface INtMemoryManager {
    default void systemReady(ActivityManagerService ams, WindowManagerService wms, Context context) {
    }

    default void setForkProcAdj(ProcessRecord app) {
    }

    default boolean isEnableOptHighUsed(ProcessRecord app) {
        return false;
    }

    default boolean isEnableOptHighUsed() {
        return false;
    }

    default void setOptAdj(ProcessRecord app) {
    }

    default void boostCamera(boolean isColdStart) {
    }

    default void releaseMemoryAtScreenOn() {
    }

    default void loadProcessMemory(String packageName) {
    }

    default int getTargetAdj(ProcessRecord p) {
        return -1;
    }

    default int[] getOptiAdjs() {
        return null;
    }

    default boolean isEnablePreFork(int memoryLevel) {
        return true;
    }

    default void setHighPressureScene(String pkgName) {
    }
    
    default void scheduleForkHighUsedApps() {
    }
    
    default void releaseMemory(int i, int i2, boolean b, boolean b2) {
    }
}
