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

import android.os.Handler;

import com.android.server.AxExtServiceFactory;
import com.android.server.wm.AxRefreshRateController;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

public final class AxBoostManager {

    private static final String FG_UCLAMP_MIN = "/dev/cpuctl/foreground/cpu.uclamp.min";
    private static final String TA_UCLAMP_MIN = "/dev/cpuctl/top-app/cpu.uclamp.min";
    private static final String FG_UCLAMP_MAX = "/dev/cpuctl/foreground/cpu.uclamp.max";
    private static final String TA_UCLAMP_MAX = "/dev/cpuctl/top-app/cpu.uclamp.max";
    private static final String FG_UCLAMP_LAT =
            "/dev/cpuctl/foreground/cpu.uclamp.latency_sensitive";
    private static final String TA_UCLAMP_LAT = "/dev/cpuctl/top-app/cpu.uclamp.latency_sensitive";

    private static final int UCLAMP_MIN_DEFAULT = 30;
    private static final int UCLAMP_MIN_TOP_APP_DEFAULT = 0;
    private static final int UCLAMP_MAX_DEFAULT = 100;

    private static final long INPUT_BURST_UCLAMP_TOP = 80L;
    private static final long INPUT_BURST_UCLAMP_FG = 70L;

    private static final String CPU_MAX_FLOOR = "9999999";

    private final AxBurstEngine mEngine;
    private final Handler mHandler;
    private final AxHintEngine mHintEngine;

    private boolean mPrimitivesRegistered = false;

    private long mLaunchHandle = AxHintEngine.INVALID_HANDLE;
    private long mShadeHandle = AxHintEngine.INVALID_HANDLE;
    private long mFlingHandle = AxHintEngine.INVALID_HANDLE;
    private long mGpuHandle = AxHintEngine.INVALID_HANDLE;
    private long mGameHandle = AxHintEngine.INVALID_HANDLE;
    private long mConsistencyHandle = AxHintEngine.INVALID_HANDLE;
    private long mEarlyWakeupHandle = AxHintEngine.INVALID_HANDLE;
    private long mActivityTransitionHandle = AxHintEngine.INVALID_HANDLE;
    private long mInputBurstHandle = AxHintEngine.INVALID_HANDLE;
    private int mEarlyWakeupRefs = 0;
    private int mTopAppPid = 0;
    private int mTopAppRenderTid = 0;
    private int mMediaDampenedPid = 0;
    private int mMediaDampenedRtTid = 0;
    private volatile int mThermalLevel = 0;
    private volatile int mThermalCpuCap = -1;
    private volatile int mThermalGpuCap = -1;
    private volatile int mThermalUclampMaxCeiling = 100;

    AxBoostManager(AxBurstEngine engine, Handler handler) {
        mEngine = engine;
        mHandler = handler;
        mHintEngine = new AxHintEngine(handler);
    }

    void onConfigsUpdated() {
        if (mPrimitivesRegistered) return;
        mPrimitivesRegistered = true;
        registerPrimitives();
    }

    private void registerPrimitives() {
        mHintEngine.register(
                AxPerfConfig.Res.CPU_MIN_C0,
                0L,
                v -> {
                    DeviceData.BoostData d = mEngine.getData();
                    if (d == null || d.sMin == null) return;
                    String target;
                    if (v < 0) target = String.valueOf(d.sBoostHz);
                    else if (v > 0) target = String.valueOf(v);
                    else target = d.uSMin != null ? d.uSMin : "0";
                    AxUtils.write(d.sMin, target);
                });
        mHintEngine.register(
                AxPerfConfig.Res.CPU_MIN_C1,
                0L,
                v -> {
                    DeviceData.BoostData d = mEngine.getData();
                    if (d == null || d.bMin == null) return;
                    String target;
                    if (v < 0) target = String.valueOf(d.bBoostHz);
                    else if (v > 0) target = String.valueOf(v);
                    else target = d.uBMin != null ? d.uBMin : "0";
                    AxUtils.write(d.bMin, target);
                });
        mHintEngine.register(
                AxPerfConfig.Res.CPU_MIN_C2,
                0L,
                v -> {
                    DeviceData.BoostData d = mEngine.getData();
                    if (d == null || d.pMin == null) return;
                    String target;
                    if (v < 0) target = String.valueOf(d.pBoostHz);
                    else if (v > 0) target = String.valueOf(v);
                    else target = d.uPMin != null ? d.uPMin : "0";
                    AxUtils.write(d.pMin, target);
                });
        mHintEngine.register(
                AxPerfConfig.Res.CPU_MAX_C0,
                0L,
                v -> {
                    DeviceData.BoostData d = mEngine.getData();
                    if (d == null || d.sMax == null) return;
                    String target =
                            v > 0 ? CPU_MAX_FLOOR : (d.uSMax != null ? d.uSMax : CPU_MAX_FLOOR);
                    AxUtils.write(d.sMax, target);
                });
        mHintEngine.register(
                AxPerfConfig.Res.CPU_MAX_C1,
                0L,
                v -> {
                    DeviceData.BoostData d = mEngine.getData();
                    if (d == null || d.bMax == null) return;
                    String target =
                            v > 0 ? CPU_MAX_FLOOR : (d.uBMax != null ? d.uBMax : CPU_MAX_FLOOR);
                    AxUtils.write(d.bMax, target);
                });
        mHintEngine.register(
                AxPerfConfig.Res.CPU_MAX_C2,
                0L,
                v -> {
                    DeviceData.BoostData d = mEngine.getData();
                    if (d == null || d.pMax == null) return;
                    String target =
                            v > 0 ? CPU_MAX_FLOOR : (d.uPMax != null ? d.uPMax : CPU_MAX_FLOOR);
                    AxUtils.write(d.pMax, target);
                });
        mHintEngine.register(
                AxPerfConfig.Res.DRAM_MIN,
                0L,
                v -> {
                    String path = AxNodePaths.getDramMinPath();
                    if (path == null) return;
                    long target;
                    if (v < 0) target = AxNodePaths.getDramBoostHz();
                    else if (v > 0) target = v;
                    else target = AxNodePaths.getDramDefaultHz();
                    AxUtils.write(path, String.valueOf(target));
                });
        mHintEngine.register(
                AxPerfConfig.Res.GPU_BOOST,
                0L,
                v -> {
                    if (AxGpuData.isGpuOppMode()) {
                        String oppPath = AxGpuData.getGpuOppPath();
                        if (oppPath == null) return;
                        int idx =
                                v > 0
                                        ? AxGpuData.getGpuBoostOppIndex()
                                        : AxGpuData.getGpuDefaultOppIndex();
                        AxUtils.write(oppPath, String.valueOf(idx));
                        return;
                    }
                    String path = AxGpuData.getGpuMinPath();
                    if (path == null) return;
                    int target = v > 0 ? AxGpuData.getGpuBoostHz() : AxGpuData.getGpuDefaultMinHz();
                    if (target <= 0) return;
                    AxUtils.write(path, String.valueOf(target));
                });
        mHintEngine.register(
                AxPerfConfig.Res.GPU_MAX,
                0L,
                v -> {
                    if (AxGpuData.isGpuOppMode()) {
                        String oppPath = AxGpuData.getGpuOppPath();
                        if (oppPath == null) return;
                        int idx = v > 0 ? 0 : AxGpuData.getGpuDefaultOppIndex();
                        AxUtils.write(oppPath, String.valueOf(idx));
                        return;
                    }
                    String path = AxGpuData.getGpuMinPath();
                    if (path == null) return;
                    int target = v > 0 ? AxGpuData.getGpuBoostHz() : AxGpuData.getGpuDefaultMinHz();
                    if (target <= 0) return;
                    AxUtils.write(path, String.valueOf(target));
                });
        mHintEngine.register(
                AxPerfConfig.Res.CPUSET_SVP,
                0L,
                v -> {
                    DeviceData.BoostData d = mEngine.getData();
                    if (d == null) return;
                    if (v > 0) {
                        String ui = (d.pCores != null && !d.pCores.isEmpty()) ? d.pCores : d.bCores;
                        if (ui != null && !ui.isEmpty()) {
                            mEngine.adjustCpusetCpus(DeviceData.CPU_SVP, ui, 0L);
                        }
                    } else {
                        mEngine.adjustCpusetCpus(DeviceData.CPU_SVP, d.svpCpus, -1L);
                    }
                });
        mHintEngine.register(
                AxPerfConfig.Res.CPUSET_SYSUI,
                0L,
                v -> {
                    DeviceData.BoostData d = mEngine.getData();
                    if (d == null) return;
                    if (v > 0) {
                        String ui = (d.pCores != null && !d.pCores.isEmpty()) ? d.pCores : d.bCores;
                        if (ui != null && !ui.isEmpty()) {
                            mEngine.adjustCpusetCpus(AxBurstEngine.CPU_SYSUI, ui, 0L);
                        }
                    } else {
                        mEngine.adjustCpusetCpus(AxBurstEngine.CPU_SYSUI, d.allCores, -1L);
                    }
                });
        mHintEngine.register(
                AxPerfConfig.Res.CPUSET_TOP,
                0L,
                v -> {
                    DeviceData.BoostData d = mEngine.getData();
                    if (d == null) return;
                    if (v > 0) {
                        mEngine.adjustCpusetCpus(DeviceData.CPU_TOP_APP, d.sCores, 0L);
                    } else {
                        mEngine.adjustCpusetCpus(DeviceData.CPU_TOP_APP, d.allCores, -1L);
                    }
                });
        mHintEngine.register(
                AxPerfConfig.Res.UCLAMP_FG,
                UCLAMP_MIN_DEFAULT,
                v -> {
                    long target = Math.max(UCLAMP_MIN_DEFAULT, v);
                    AxUtils.write(FG_UCLAMP_MIN, String.valueOf(target));
                });
        mHintEngine.register(
                AxPerfConfig.Res.UCLAMP_TOP,
                UCLAMP_MIN_TOP_APP_DEFAULT,
                v -> {
                    long target = Math.max(UCLAMP_MIN_TOP_APP_DEFAULT, v);
                    AxUtils.write(TA_UCLAMP_MIN, String.valueOf(target));
                });
        mHintEngine.register(
                AxPerfConfig.Res.RR_FLING,
                0L,
                v -> {
                    AxRefreshRateController.getInstance().setFlingBoost(v > 0);
                });

        registerCgroupUclampMax(AxPerfConfig.Res.UCLAMP_FG_MAX, FG_UCLAMP_MAX);
        registerCgroupUclampMax(AxPerfConfig.Res.UCLAMP_TOP_MAX, TA_UCLAMP_MAX);
        registerToggleDirect(AxPerfConfig.Res.UCLAMP_FG_LAT, FG_UCLAMP_LAT);
        registerToggleDirect(AxPerfConfig.Res.UCLAMP_TOP_LAT, TA_UCLAMP_LAT);

        mHintEngine.register(
                AxPerfConfig.Res.PMQOS_CPU_DMA_LATENCY,
                0L,
                v -> {
                    if (v > 0) AxUtils.pmqosHoldFd((int) v);
                    else AxUtils.pmqosReleaseFd();
                });

        registerBoostOrDefault(
                AxPerfConfig.Res.GPU_DVFS_MARGIN,
                AxNodePaths::getGpuDvfsMarginPath,
                AxNodePaths::getGpuDvfsMarginBoost,
                AxNodePaths::getGpuDvfsMarginDefault);
        registerBoostOrDefault(
                AxPerfConfig.Res.GPU_DVFS_STEP,
                AxNodePaths::getGpuDvfsStepPath,
                AxNodePaths::getGpuDvfsStepBoost,
                AxNodePaths::getGpuDvfsStepDefault);

        registerWriteNonNegative(
                AxPerfConfig.Res.GPU_BOOST_LEVEL, AxNodePaths::getGpuBoostLevelPath);
        registerWriteNonNegative(
                AxPerfConfig.Res.GPU_CUSTOM_BOOST_FREQ, AxNodePaths::getGpuCustomBoostFreqPath);
        registerWriteNonNegative(
                AxPerfConfig.Res.GPU_CUSTOM_UPBOUND_FREQ, AxNodePaths::getGpuCustomUpboundFreqPath);
        registerWriteNonNegative(
                AxPerfConfig.Res.GPU_DVFS_LOADING_MODE, AxNodePaths::getGpuDvfsLoadingModePath);
        registerWriteNonNegative(AxPerfConfig.Res.GPU_DCS_MODE, AxNodePaths::getGpuDcsModePath);

        registerWritePositive(AxPerfConfig.Res.MALI_MIN_FREQ, AxNodePaths::getMaliMinFreqPath);
        registerWritePositive(AxPerfConfig.Res.MALI_MAX_FREQ, AxNodePaths::getMaliMaxFreqPath);

        registerToggle(AxPerfConfig.Res.CCI_FREQ, AxNodePaths::getCciFreqPath);

        registerBoostOrDefault(
                AxPerfConfig.Res.CPU_DVFS_HEADROOM,
                AxNodePaths::getCpuDvfsHeadroomPath,
                AxNodePaths::getCpuDvfsHeadroomBoost,
                AxNodePaths::getCpuDvfsHeadroomDefault);
        registerWritePositive(
                AxPerfConfig.Res.CPU_UTIL_THRESHOLD, AxNodePaths::getCpuUtilThresholdPath);
        registerToggle(
                AxPerfConfig.Res.CPU_TAPERED_DVFS_HEADROOM,
                AxNodePaths::getCpuTaperedDvfsHeadroomPath);

        registerWriteNonNegative(
                AxPerfConfig.Res.VENDOR_SCHED_TA_UCLAMP, AxNodePaths::getVendorSchedTaUclampPath);
        registerWriteNonNegative(
                AxPerfConfig.Res.VENDOR_SCHED_FG_UCLAMP, AxNodePaths::getVendorSchedFgUclampPath);
        registerToggle(
                AxPerfConfig.Res.VENDOR_SCHED_REDUCE_PREFER_IDLE,
                AxNodePaths::getVendorSchedReducePreferIdlePath);

        registerWritePositive(
                AxPerfConfig.Res.CPU_DOWN_RATE_LIMIT_C0, AxNodePaths::getCpuDownRateLimitC0Path);
        registerWritePositive(
                AxPerfConfig.Res.CPU_DOWN_RATE_LIMIT_C1, AxNodePaths::getCpuDownRateLimitC1Path);
        registerWritePositive(
                AxPerfConfig.Res.CPU_DOWN_RATE_LIMIT_C2, AxNodePaths::getCpuDownRateLimitC2Path);

        registerWritePositive(AxPerfConfig.Res.INT_MIN_FREQ, AxNodePaths::getIntMinFreqPath);
        registerToggle(
                AxPerfConfig.Res.DISPLAY_HW_EARLY_WAKEUP, AxNodePaths::getDisplayHwEarlyWakeupPath);
    }

    private void registerCgroupUclampMax(String res, String path) {
        mHintEngine.register(
                res,
                UCLAMP_MAX_DEFAULT,
                v -> {
                    long requested = v > 0 ? Math.min(UCLAMP_MAX_DEFAULT, v) : UCLAMP_MAX_DEFAULT;
                    long target = Math.min(requested, mThermalUclampMaxCeiling);
                    AxUtils.write(
                            path, target >= UCLAMP_MAX_DEFAULT ? "max" : String.valueOf(target));
                });
    }

    private void registerToggleDirect(String res, String path) {
        mHintEngine.register(res, 0L, v -> AxUtils.write(path, v > 0 ? "1" : "0"));
    }

    private void registerToggle(String res, Supplier<String> pathSupplier) {
        mHintEngine.register(
                res,
                0L,
                v -> {
                    String p = pathSupplier.get();
                    if (p == null) return;
                    AxUtils.write(p, v > 0 ? "1" : "0");
                });
    }

    private void registerWriteNonNegative(String res, Supplier<String> pathSupplier) {
        mHintEngine.register(
                res,
                0L,
                v -> {
                    String p = pathSupplier.get();
                    if (p == null) return;
                    AxUtils.write(p, String.valueOf(Math.max(0, v)));
                });
    }

    private void registerWritePositive(String res, Supplier<String> pathSupplier) {
        mHintEngine.register(
                res,
                0L,
                v -> {
                    String p = pathSupplier.get();
                    if (p == null || v <= 0) return;
                    AxUtils.write(p, String.valueOf(v));
                });
    }

    private void registerBoostOrDefault(
            String res,
            Supplier<String> pathSupplier,
            LongSupplier boostSupplier,
            LongSupplier defaultSupplier) {
        mHintEngine.register(
                res,
                0L,
                v -> {
                    String p = pathSupplier.get();
                    if (p == null) return;
                    long target = v > 0 ? boostSupplier.getAsLong() : defaultSupplier.getAsLong();
                    if (target <= 0) return;
                    AxUtils.write(p, String.valueOf(target));
                });
    }

    public boolean isCompositionBoosting() {
        return mLaunchHandle != AxHintEngine.INVALID_HANDLE
                || mEarlyWakeupHandle != AxHintEngine.INVALID_HANDLE
                || mActivityTransitionHandle != AxHintEngine.INVALID_HANDLE;
    }

    public void compositionBoost(long durationMs) {
        compositionBoost(durationMs, 0);
    }

    public void compositionBoost(long durationMs, int topAppPid) {
        AxPerfConfig.HintProfile h = AxPerfConfig.getHint(AxPerfConfig.Hint.LAUNCH);
        long dur = durationMs > 0 ? durationMs : h.durationMs;
        if (dur <= 0) return;
        if (mLaunchHandle != AxHintEngine.INVALID_HANDLE) {
            mHintEngine.release(mLaunchHandle);
            mLaunchHandle = AxHintEngine.INVALID_HANDLE;
        }
        mLaunchHandle =
                mHintEngine.acquire(h, dur, () -> mLaunchHandle = AxHintEngine.INVALID_HANDLE);
    }

    public void shadeBoost(boolean active) {
        if (mEngine.getData() == null || mEngine.gameActive()) return;
        if (active) {
            if (mShadeHandle != AxHintEngine.INVALID_HANDLE) return;
            mShadeHandle =
                    mHintEngine.acquire(
                            AxPerfConfig.getHint(AxPerfConfig.Hint.SHADE),
                            () -> mShadeHandle = AxHintEngine.INVALID_HANDLE);
            AxUtils.logger("shadeBoost: ON handle=" + mShadeHandle);
        } else {
            long h = mShadeHandle;
            mShadeHandle = AxHintEngine.INVALID_HANDLE;
            mHintEngine.release(h);
            AxUtils.logger("shadeBoost: OFF");
        }
    }

    public void flingBoost(boolean active) {
        if (mEngine.getData() == null || mEngine.gameActive()) return;
        if (active) {
            if (mFlingHandle != AxHintEngine.INVALID_HANDLE) return;
            mFlingHandle =
                    mHintEngine.acquire(
                            AxPerfConfig.getHint(AxPerfConfig.Hint.FLING),
                            () -> mFlingHandle = AxHintEngine.INVALID_HANDLE);
        } else {
            long h = mFlingHandle;
            mFlingHandle = AxHintEngine.INVALID_HANDLE;
            mHintEngine.release(h);
        }
    }

    public void gpuBoost(boolean active) {
        if (active) {
            if (mGpuHandle != AxHintEngine.INVALID_HANDLE) return;
            mGpuHandle =
                    mHintEngine.acquire(
                            AxPerfConfig.getHint(AxPerfConfig.Hint.GPU),
                            () -> mGpuHandle = AxHintEngine.INVALID_HANDLE);
        } else {
            long h = mGpuHandle;
            mGpuHandle = AxHintEngine.INVALID_HANDLE;
            mHintEngine.release(h);
        }
    }

    public void gpuMaxBoost(boolean active) {
        gpuBoost(active);
    }

    public void gameBoost(boolean active) {
        if (active) {
            if (mGameHandle != AxHintEngine.INVALID_HANDLE) return;
            mGameHandle =
                    mHintEngine.acquire(
                            AxPerfConfig.getHint(AxPerfConfig.Hint.GAME),
                            () -> mGameHandle = AxHintEngine.INVALID_HANDLE);
        } else {
            long h = mGameHandle;
            mGameHandle = AxHintEngine.INVALID_HANDLE;
            mHintEngine.release(h);
        }
    }

    private void fireHint(String name) {
        if (mEngine.getData() == null || mEngine.gameActive()) return;
        mHintEngine.acquire(AxPerfConfig.getHint(name));
    }

    public void onScrollEvent(int action) {
        if (mEngine.getData() == null || mEngine.gameActive()) return;
        switch (action) {
            case IAxBurstEngine.Scroll.INPUT_EVENT:
                fireHint(AxPerfConfig.Hint.SCROLL_INPUT);
                break;
            case IAxBurstEngine.Scroll.PREFILING:
                fireHint(AxPerfConfig.Hint.SCROLL_PREFILING);
                break;
            case IAxBurstEngine.Scroll.VERTICAL:
                fireHint(AxPerfConfig.Hint.SCROLL_VERTICAL);
                break;
            case IAxBurstEngine.Scroll.SCROLLER:
                fireHint(AxPerfConfig.Hint.SCROLL_SCROLLER);
                break;
            default:
                break;
        }
    }

    public void onLaunch(int type) {
        if (mEngine.getData() == null) return;
        switch (type) {
            case IAxBurstEngine.Launch.LAUNCH_COLD:
                compositionBoost(1500L, 0);
                break;
            case IAxBurstEngine.Launch.LAUNCH_HOT:
                compositionBoost(500L, 0);
                break;
            case IAxBurstEngine.Launch.ACTIVITY_SWITCH:
                compositionBoost(500L, 0);
                break;
            default:
                break;
        }
    }

    public void onFrameStage(int stage, long frameId) {
        if (mEngine.getData() == null || mEngine.gameActive()) return;
        switch (stage) {
            case IAxBurstEngine.Frame.FRAME_DRAW:
                fireHint(AxPerfConfig.Hint.FRAME_DRAW);
                break;
            case IAxBurstEngine.Frame.FRAME_DRAW_STEP:
                fireHint(AxPerfConfig.Hint.FRAME_DRAW_STEP);
                break;
            case IAxBurstEngine.Frame.REQUEST_VSYNC:
                fireHint(AxPerfConfig.Hint.FRAME_REQUEST_VSYNC);
                break;
            case IAxBurstEngine.Frame.PREFETCHER:
                fireHint(AxPerfConfig.Hint.FRAME_PREFETCHER);
                break;
            case IAxBurstEngine.Frame.PRE_ANIM:
                fireHint(AxPerfConfig.Hint.FRAME_PRE_ANIM);
                break;
            case IAxBurstEngine.Frame.REAL_DRAW:
                fireHint(AxPerfConfig.Hint.FRAME_REAL_DRAW);
                break;
            case IAxBurstEngine.Frame.OBTAIN_VIEW:
                fireHint(AxPerfConfig.Hint.FRAME_OBTAIN_VIEW);
                break;
            case IAxBurstEngine.Frame.RENDER_INFO:
                fireHint(AxPerfConfig.Hint.FRAME_RENDER_INFO);
                break;
            default:
                break;
        }
    }

    public void onRefreshRateEvent(int event) {
        if (mEngine.getData() == null || mEngine.gameActive()) return;
        switch (event) {
            case IAxBurstEngine.RefreshRate.FLING_START:
            case IAxBurstEngine.RefreshRate.FLING_UPDATE:
                flingBoost(true);
                break;
            case IAxBurstEngine.RefreshRate.FLING_FINISH:
                flingBoost(false);
                break;
            default:
                break;
        }
    }

    public void onImeTransition(int action) {
        if (mEngine.getData() == null) return;
        switch (action) {
            case IAxBurstEngine.Ime.IME_SHOW:
            case IAxBurstEngine.Ime.IME_HIDE:
                fireHint(AxPerfConfig.Hint.IME_SHOW_HIDE);
                break;
            case IAxBurstEngine.Ime.IME_INIT:
                fireHint(AxPerfConfig.Hint.IME_INIT);
                break;
            default:
                break;
        }
    }

    public void onConsistency(int mode) {
        if (mEngine.getData() == null) return;
        switch (mode) {
            case IAxBurstEngine.Consistency.APP_LAUNCH_RESPONSE:
                compositionBoost(500L, 0);
                break;
            case IAxBurstEngine.Consistency.NORMAL_MODE:
                {
                    long h = mConsistencyHandle;
                    mConsistencyHandle = AxHintEngine.INVALID_HANDLE;
                    mHintEngine.release(h);
                    break;
                }
            default:
                break;
        }
    }

    public void onAnimation(int action) {
        if (mEngine.getData() == null || mEngine.gameActive()) return;
        compositionBoost(300L, 0);
    }

    public void onEarlyWakeup(boolean start, long maxDurMs) {
        if (mEngine.getData() == null || mEngine.gameActive()) return;
        if (start) {
            if (++mEarlyWakeupRefs > 1) return;
            AxPerfConfig.HintProfile h = AxPerfConfig.getHint(AxPerfConfig.Hint.EARLY_WAKEUP);
            long dur = maxDurMs > 0 ? maxDurMs : h.durationMs;
            mEarlyWakeupHandle =
                    mHintEngine.acquire(
                            h, dur, () -> mEarlyWakeupHandle = AxHintEngine.INVALID_HANDLE);
            if (mTopAppPid > 0) {
                AxExtServiceFactory.getUiFirstManager()
                        .boostTopAppForEarlyWakeup(mTopAppPid, mTopAppRenderTid, true);
            }
        } else {
            if (mEarlyWakeupRefs == 0) return;
            if (--mEarlyWakeupRefs > 0) return;
            long h = mEarlyWakeupHandle;
            mEarlyWakeupHandle = AxHintEngine.INVALID_HANDLE;
            mHintEngine.release(h);
            if (mTopAppPid > 0) {
                AxExtServiceFactory.getUiFirstManager()
                        .boostTopAppForEarlyWakeup(mTopAppPid, mTopAppRenderTid, false);
            }
        }
    }

    public void onActivityTransition(String fromPkg, String toPkg, int phase) {
        if (mEngine.getData() == null || mEngine.gameActive()) return;
        if (phase == IAxBurstEngine.Animation.START) {
            if (mActivityTransitionHandle != AxHintEngine.INVALID_HANDLE) return;
            mActivityTransitionHandle =
                    mHintEngine.acquire(
                            AxPerfConfig.getHint(AxPerfConfig.Hint.ACTIVITY_TRANSITION),
                            () -> mActivityTransitionHandle = AxHintEngine.INVALID_HANDLE);
        } else {
            long h = mActivityTransitionHandle;
            mActivityTransitionHandle = AxHintEngine.INVALID_HANDLE;
            mHintEngine.release(h);
        }
    }

    public void onActivityExit(String pkg) {
        if (mEngine.getData() == null || mEngine.gameActive()) return;
        mHintEngine.acquire(AxPerfConfig.getHint(AxPerfConfig.Hint.ACTIVITY_EXIT));
    }

    public void onProcessKill(int pid, String pkg) {
        if (pid > 0 && pid == mTopAppPid) {
            mTopAppPid = 0;
            mTopAppRenderTid = 0;
        }
        mHintEngine.acquire(AxPerfConfig.getHint(AxPerfConfig.Hint.KILL));
    }

    public void setTopAppRenderThread(int pid, int tid) {
        if (tid <= 0 || pid <= 0) return;
        if (pid == mTopAppPid && tid == mTopAppRenderTid) return;
        mTopAppPid = pid;
        mTopAppRenderTid = tid;
    }

    int getTopAppRenderTid() {
        return mTopAppRenderTid;
    }

    int getTopAppPid() {
        return mTopAppPid;
    }

    void setMediaDampenedForInput(long durationMs) {
        if (mTopAppPid <= 0 || mEngine.getData() == null || mEngine.gameActive()) return;
        final int pid = mTopAppPid;
        final int rtTid = mTopAppRenderTid;
        if (mMediaDampenedPid != pid) {
            final int oldPid = mMediaDampenedPid;
            final int oldRtTid = mMediaDampenedRtTid;
            mMediaDampenedPid = pid;
            mMediaDampenedRtTid = rtTid;
            mHandler.post(
                    () -> {
                        if (oldPid > 0) {
                            AxExtServiceFactory.getUiFirstManager()
                                    .boostTopAppForEarlyWakeup(oldPid, oldRtTid, false);
                        }
                        AxExtServiceFactory.getUiFirstManager()
                                .boostTopAppForEarlyWakeup(pid, rtTid, true);
                    });
        }
        if (mInputBurstHandle != AxHintEngine.INVALID_HANDLE) {
            mHintEngine.release(mInputBurstHandle);
            mInputBurstHandle = AxHintEngine.INVALID_HANDLE;
        }
        Map<String, Long> res = new HashMap<>(3);
        res.put(AxPerfConfig.Res.UCLAMP_TOP, INPUT_BURST_UCLAMP_TOP);
        res.put(AxPerfConfig.Res.UCLAMP_FG, INPUT_BURST_UCLAMP_FG);
        res.put(AxPerfConfig.Res.UCLAMP_TOP_LAT, 1L);
        mInputBurstHandle =
                mHintEngine.acquire(
                        res,
                        durationMs,
                        () -> {
                            mInputBurstHandle = AxHintEngine.INVALID_HANDLE;
                            if (mMediaDampenedPid > 0) {
                                final int p = mMediaDampenedPid;
                                final int rt = mMediaDampenedRtTid;
                                mMediaDampenedPid = 0;
                                mMediaDampenedRtTid = 0;
                                AxExtServiceFactory.getUiFirstManager()
                                        .boostTopAppForEarlyWakeup(p, rt, false);
                            }
                        });
    }

    public void setTopAppPid(int pid) {
        if (pid <= 0 || pid == mTopAppPid) return;
        mTopAppPid = pid;
    }

    public long acquireResources(long durMs, String[] resNames, long[] values) {
        if (resNames == null
                || values == null
                || resNames.length == 0
                || resNames.length != values.length) {
            return AxHintEngine.INVALID_HANDLE;
        }
        if (isBoostGated()) {
            return AxHintEngine.INVALID_HANDLE;
        }
        LinkedHashMap<String, Long> bundle = new LinkedHashMap<>(resNames.length);
        for (int i = 0; i < resNames.length; i++) {
            if (resNames[i] != null) bundle.put(resNames[i], values[i]);
        }
        return mHintEngine.acquire(bundle, durMs, null);
    }

    public void releaseResources(long handle) {
        if (handle == AxHintEngine.INVALID_HANDLE) return;
        mHintEngine.release(handle);
    }

    public long swapResources(long prevHandle, long durMs, String[] resNames, long[] values) {
        if (prevHandle != AxHintEngine.INVALID_HANDLE) {
            mHintEngine.release(prevHandle);
        }
        return acquireResources(durMs, resNames, values);
    }

    public long acquireHint(String hintName, long durOverrideMs) {
        if (hintName == null) return AxHintEngine.INVALID_HANDLE;
        if (isBoostGated()) return AxHintEngine.INVALID_HANDLE;
        AxPerfConfig.HintProfile h = AxPerfConfig.getHint(hintName);
        if (h == AxPerfConfig.HintProfile.EMPTY) return AxHintEngine.INVALID_HANDLE;
        return mHintEngine.acquire(h, durOverrideMs, null);
    }

    public void setThermalState(int level, int cpuCap, int gpuCap) {
        mThermalLevel = level;
        mThermalCpuCap = cpuCap;
        mThermalGpuCap = gpuCap;
        int ceiling = cpuCapToUclampMax(cpuCap);
        if (ceiling != mThermalUclampMaxCeiling) {
            mThermalUclampMaxCeiling = ceiling;
            String value = ceiling >= UCLAMP_MAX_DEFAULT ? "max" : String.valueOf(ceiling);
            AxUtils.write(FG_UCLAMP_MAX, value);
            AxUtils.write(TA_UCLAMP_MAX, value);
        }
    }

    private static int cpuCapToUclampMax(int cpuCap) {
        if (cpuCap <= 0) return UCLAMP_MAX_DEFAULT;
        int v = UCLAMP_MAX_DEFAULT - (cpuCap * 3);
        if (v < 20) v = 20;
        if (v > UCLAMP_MAX_DEFAULT) v = UCLAMP_MAX_DEFAULT;
        return v;
    }

    public int getThermalLevel() {
        return mThermalLevel;
    }

    private boolean isBoostGated() {
        return mThermalLevel >= 11;
    }

    public long swapHint(long prevHandle, String hintName, long durOverrideMs) {
        if (prevHandle != AxHintEngine.INVALID_HANDLE) {
            mHintEngine.release(prevHandle);
        }
        return acquireHint(hintName, durOverrideMs);
    }
}
