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
package com.android.server.am;

import android.app.AxBoostFwk;
import android.os.SystemClock;

public final class AxBoostManager {

    static native void native_set_boost_data(String[] paths, String[] values);
    static native boolean native_is_composition_boosting();
    static native boolean native_should_defer_pss();
    static native long native_perf_hint(int opcode, long durMs);
    static native void native_perf_hint_rel(long handle);
    static native void native_set_thermal_ceiling(String path, int ceiling);
    static native void native_remove_thermal_ceiling(String path);
    static native void native_set_cpu_freq_bound(String path, int boundValue, boolean isFloor);
    static native void native_remove_cpu_freq_bounds();
    static native void native_update_top_app(int pid, int tid);
    static native void native_set_ui_boost_active(boolean active);

    private volatile Runnable mCancelCompactionCallback = null;

    private final AxBurstEngine mEngine;
    private final AxThermalBoostPolicy mThermalPolicy = new AxThermalBoostPolicy();

    private volatile int mTopAppPid = 0;
    private volatile int mTopAppRenderTid = 0;
    
    private static long sFrameDrawNs;

    AxBoostManager(AxBurstEngine engine) {
        mEngine = engine;
    }

    public long acquireHint(int opcode, long durMs) {
        if (durMs == 0L) return native_perf_hint(opcode, 0L);
        if (opcode == AxBoostFwk.OP_SCROLL_BOOST
                || opcode == AxBoostFwk.OP_SCROLL_SCROLLER) {
            Runnable cb = mCancelCompactionCallback;
            if (cb != null) cb.run();
        }
        return native_perf_hint(opcode, durMs);
    }

    void onFrameDraw() {
        sFrameDrawNs = SystemClock.uptimeNanos();
    }

    void onFrameRealDraw(long durMs) {
        if (sFrameDrawNs == 0) return;
        long elapsed = SystemClock.uptimeNanos() - sFrameDrawNs;
        if (elapsed > 16000000L) sFrameDrawNs = 0;
        if (elapsed > 20000000L)
            acquireHint(AxBoostFwk.OP_FRAME_RESCUE_CROSS, durMs);
        else if (elapsed > 11000000L)
            acquireHint(AxBoostFwk.OP_FRAME_RESCUE_HEAVY, durMs);
        else if (elapsed > 5000000L)
            acquireHint(AxBoostFwk.OP_FRAME_RESCUE_LIGHT, durMs);
    }

    public int perfLockAcquire(int duration, int... list) {
        if (list == null || list.length == 0) return -1;
        return (int)acquireHint(list[0], duration);
    }

    public void setThermalState(int level, int cpuCap, int gpuCap) {
        DeviceData.BoostData d = mEngine.getData();
        if (d == null) return;
        if (mThermalPolicy.getThermalLevel() <= 0) {
            native_remove_cpu_freq_bounds();
            return;
        }
        applyClusterBound(AxBoostFwk.PERF_CLUSTER_LITTLE,
                d.sMin, d.uSMin, d.sMax, d.uSMax);
        applyClusterBound(AxBoostFwk.PERF_CLUSTER_BIG,
                d.bMin, d.uBMin, d.bMax, d.uBMax);
        if (d.hasPrime)
            applyClusterBound(AxBoostFwk.PERF_CLUSTER_PRIME,
                    d.pMin, d.uPMin, d.pMax, d.uPMax);
    }

    private void applyClusterBound(int cluster, String minPath, String uMin,
                                    String maxPath, String uMax) {
        if (minPath == null || maxPath == null) return;
        long userMin = parseLong(uMin, 0);
        long userMax = parseLong(uMax, Long.MAX_VALUE);
        long tMin = mThermalPolicy.getThermalCpuMinKhz(cluster);
        long tMax = mThermalPolicy.getThermalCpuMaxKhz(cluster);

        long floor = tMin > 0 ? Math.max(userMin, tMin) : userMin;
        long ceiling = tMax > 0 ? Math.min(userMax, tMax) : userMax;
        if (floor > ceiling) floor = ceiling;

        native_set_cpu_freq_bound(minPath, (int) floor, true);
        native_set_cpu_freq_bound(maxPath, (int) ceiling, false);
    }

    private static long parseLong(String value, long def) {
        if (value == null) return def;
        try {
            long parsed = Long.parseLong(value.trim());
            return parsed > 0 ? parsed : def;
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public static void setUiBoostActive(boolean active) {
        native_set_ui_boost_active(active);
    }

    public void setTopAppRenderThread(int pid, int tid) {
        if (tid <= 0 || pid <= 0) return;
        if (pid == mTopAppPid && tid == mTopAppRenderTid) return;
        mTopAppPid = pid;
        mTopAppRenderTid = tid;
        native_update_top_app(pid, tid);
    }

    int getTopAppRenderTid() { return mTopAppRenderTid; }
    int getTopAppPid() { return mTopAppPid; }

    void setCancelCompactionCallback(Runnable r) {
        mCancelCompactionCallback = r;
    }

    public void perfHintRelease(long handle) {
        native_perf_hint_rel(handle);
    }
}
