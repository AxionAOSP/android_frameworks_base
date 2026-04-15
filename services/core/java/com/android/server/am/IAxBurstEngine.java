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

public interface IAxBurstEngine {

    default void systemReady() {
    }

    default void adjustCpusetCpus(String group, String cpus, long duration) {
    }

    default void getProcessesAndFrozen(String resumePackageName) {
    }

    default void inputBoost() {
    }
    
    default void onWakefulnessChanged(boolean awake) {
    }
    
    default void boostGame(boolean enable) {
    }
    
    default void boostInstall(boolean boost) {
    }
    
    default void boostThread(int tid) {
    }
    
    default void systemThreadBoost(int tid, long duration) {
    }

    default void launcherItemsLoadingBoost(long duration) {
    }

    default void flingBoost(boolean active) {
    }

    default void compositionBoost(long durationMs) {
    }

    default void gpuBoost(boolean active) {
    }
}
