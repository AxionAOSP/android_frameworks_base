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

import android.content.Context;

public interface IBoostAdjuster {

    default void systemReady() {
    }

    default void setThreadAffinity(int tid, int setAffinity) {
    }

    default void adjustCpusetCpus(String group, String cpus, long duration) {
    }

    default void animationBoost(int pid, int renderTid, long duration) {
    }

    default void enablePerformanceMode(boolean enable) {
    }

    default void getProcessesAndFrozen(String resumePackageName) {
    }

    default void inputBoost() {
    }
    
    default void boostHint(String reason, long duration) {
    }
    
    default void onWakefulnessChanged(boolean awake) {
    }
    
    default void boostGame(boolean enable) {
    }
    
    default void boostInstall(boolean boost) {
    }
    
    default void boostThread(int tid) {
    }
    
    default void boostThreadLimited(int tid) {
    }
}
