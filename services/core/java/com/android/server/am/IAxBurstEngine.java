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

    default void compositionBoost(long durationMs, int topAppPid) {
    }

    default boolean isCompositionBoosting() {
        return false;
    }

    default void gpuBoost(boolean active) {
    }

    default void shadeBoost(boolean active) {
    }

    final class Scroll {
        public static final int INPUT_EVENT = 0;
        public static final int PREFILING = 1;
        public static final int VERTICAL = 2;
        public static final int SCROLLER = 3;
        private Scroll() {}
    }

    final class Launch {
        public static final int LAUNCH_COLD = 1;
        public static final int LAUNCH_HOT = 2;
        public static final int ACTIVITY_SWITCH = 3;
        private Launch() {}
    }

    final class Frame {
        public static final int FRAME_DRAW = 0;
        public static final int FRAME_DRAW_STEP = 1;
        public static final int REQUEST_VSYNC = 2;
        public static final int RENDER_INFO = 3;
        public static final int PREFETCHER = 4;
        public static final int PRE_ANIM = 5;
        public static final int REAL_DRAW = 6;
        public static final int OBTAIN_VIEW = 7;
        private Frame() {}
    }

    final class RefreshRate {
        public static final int FLING_START = 0;
        public static final int FLING_UPDATE = 1;
        public static final int FLING_FINISH = 2;
        public static final int SCROLLER_INIT = 3;
        public static final int TOUCH_SCROLL_ENABLE = 4;
        public static final int FLING_FRICTION_UPDATE = 5;
        private RefreshRate() {}
    }

    final class Ime {
        public static final int IME_SHOW = 1;
        public static final int IME_HIDE = 2;
        public static final int IME_INIT = 3;
        private Ime() {}
    }

    final class Consistency {
        public static final int NORMAL_MODE = 0;
        public static final int APP_LAUNCH_RESPONSE = 1;
        private Consistency() {}
    }

    final class Animation {
        public static final int END = 0;
        public static final int START = 1;
        private Animation() {}
    }

    default void onScrollEvent(int action) {
    }

    default void onLaunch(int type) {
    }

    default void onFrameStage(int stage, long frameId) {
    }

    default void onRefreshRateEvent(int event) {
    }

    default void onImeTransition(int action) {
    }

    default void onConsistency(int mode) {
    }

    default void onAnimation(int action) {
    }
}
