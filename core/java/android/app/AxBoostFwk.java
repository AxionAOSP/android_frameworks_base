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
package android.app;

import android.util.Log;

/** @hide */
public final class AxBoostFwk {

    private static final String TAG = "AxBoostFwk";

    public static final int OP_FIRST_LAUNCH_BOOST = 1;
    public static final int OP_SUBSEQ_LAUNCH_BOOST = 2;
    public static final int OP_ACTIVITY_BOOST = 4;
    public static final int OP_ANIM_BOOST = 5;
    public static final int OP_EXIT_ANIM_BOOST = 6;
    public static final int OP_LAUNCH_ACT_SWITCH = 10;

    public static final int OP_SCROLL_BOOST = 11;
    public static final int OP_SCROLL_INPUT = 12;
    public static final int OP_SCROLL_VERTICAL = 14;
    public static final int OP_SCROLL_SCROLLER = 15;

    public static final int OP_TOUCH_BOOST = 19;
    public static final int OP_DRAG_BOOST = 21;
    public static final int OP_DRAG_START = 22;
    public static final int OP_DRAG_END = 23;

    public static final int OP_FRAME_INPUT_END = 25;
    public static final int OP_FRAME_DRAW_STEP = 26;
    public static final int OP_FRAME_PREFETCHER = 27;
    public static final int OP_FRAME_OBTAIN_VIEW = 29;
    public static final int OP_FRAME_PRE_ANIM = 31;
    public static final int OP_FRAME_VSYNC = 32;
    public static final int OP_FRAME_RESCUE_LIGHT = 33;
    public static final int OP_FRAME_RESCUE_HEAVY = 34;
    public static final int OP_FRAME_RESCUE_CROSS = 35;

    public static final int OP_RENDER_EARLY_WAKEUP = 36;
    public static final int OP_RENDER_TRANSITION = 37;
    public static final int OP_RENDER_ANIMATION = 38;

    public static final int OP_ROTATION_LATENCY_BOOST = 39;
    public static final int OP_ROTATION_ANIM_BOOST = 40;

    public static final int OP_SCENARIO_GPU = 41;
    public static final int OP_GPU_APP_FG = 42;
    public static final int OP_GPU_APP_BG = 43;

    public static final int OP_IME_SHOW_HIDE = 44;
    public static final int OP_IME_INIT = 45;

    public static final int OP_SHADE = 46;
    public static final int OP_SYSTEM_GAME = 47;
    public static final int OP_FIRST_DRAW = 48;

    public static final int OP_PACKAGE_INSTALL_BOOST = 52;
    public static final int OP_PKG_INSTALL = 53;
    public static final int OP_PKG_UNINSTALL = 54;
    public static final int OP_APP_UPDATE = 55;

    public static final int OP_BOOST_RENDERTHREAD = 63;
    public static final int OP_MISC_LAUNCHER_LOAD = 66;
    public static final int OP_KILL = 67;
    public static final int OP_UI_BOOST = 68;
    public static final int OP_GAME_LAUNCH_BOOST = 69;

    public static final int PERF_CLUSTER_LITTLE = 0;
    public static final int PERF_CLUSTER_BIG = 1;
    public static final int PERF_CLUSTER_PRIME = 2;

    public static final int UXE_TRIGGER = 1;
    public static final int UXE_EVENT_BINDAPP = 2;
    public static final int UXE_EVENT_DISPLAYED_ACT = 3;
    public static final int UXE_EVENT_KILL = 4;
    public static final int UXE_EVENT_GAME = 5;
    public static final int UXE_EVENT_SUB_LAUNCH = 6;
    public static final int UXE_EVENT_PKG_UNINSTALL = 7;
    public static final int UXE_EVENT_PKG_INSTALL = 8;

    public static final int WORKLOAD_NOT_KNOWN = 0;
    public static final int WORKLOAD_APP = 1;
    public static final int WORKLOAD_GAME = 2;

    public static final int FEEDBACK_WORKLOAD_TYPE = 0x15FF;

    /** @hide */
    public static void acquireHint(int opcode, long durMs) {
        try {
            ActivityManager.getService().acquireHint(opcode, durMs);
        } catch (Exception e) {
            Log.w(TAG, "acquireHint failed", e);
        }
    }

    /** @hide */
    public static void onFrameDraw() {
        try {
            ActivityManager.getService().onFrameDraw();
        } catch (Exception e) {
            Log.w(TAG, "onFrameDraw failed", e);
        }
    }

    /** @hide */
    public static void onFrameRealDraw(long durMs) {
        try {
            ActivityManager.getService().onFrameRealDraw(durMs);
        } catch (Exception e) {
            Log.w(TAG, "onFrameRealDraw failed", e);
        }
    }

    /** @hide */
    public static void boostThread(int tid) {
        try {
            ActivityManager.getService().boostThread(tid);
        } catch (Exception e) {
            Log.w(TAG, "boostThread failed", e);
        }
    }

    /** @hide */
    public static void uxEngineEvent(int opcode, int pid, String pkgName, int lat) {
        try {
            ActivityManager.getService().uxEngineEvent(opcode, pid, pkgName, lat);
        } catch (Exception e) {
            Log.w(TAG, "uxEngineEvent failed", e);
        }
    }

    private AxBoostFwk() {}
}
