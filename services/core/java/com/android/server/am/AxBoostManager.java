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

import com.android.server.wm.AxRefreshRateController;

public final class AxBoostManager {

    private static final String FG_UCLAMP_MIN = "/dev/cpuctl/foreground/cpu.uclamp.min";
    private static final String TA_UCLAMP_MIN = "/dev/cpuctl/top-app/cpu.uclamp.min";

    private static final int UCLAMP_MIN_DEFAULT = 30;
    private static final int UCLAMP_MIN_TOP_APP_DEFAULT = 0;

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
        mHintEngine.register(AxPerfConfig.Res.CPU_MIN_C0, 0L, v -> {
            DeviceData.BoostData d = mEngine.getData();
            if (d == null || d.sMin == null) return;
            String target;
            if (v < 0) target = String.valueOf(d.sBoostHz);
            else if (v > 0) target = String.valueOf(v);
            else target = d.uSMin != null ? d.uSMin : "0";
            AxUtils.write(d.sMin, target);
        });
        mHintEngine.register(AxPerfConfig.Res.CPU_MIN_C1, 0L, v -> {
            DeviceData.BoostData d = mEngine.getData();
            if (d == null || d.bMin == null) return;
            String target;
            if (v < 0) target = String.valueOf(d.bBoostHz);
            else if (v > 0) target = String.valueOf(v);
            else target = d.uBMin != null ? d.uBMin : "0";
            AxUtils.write(d.bMin, target);
        });
        mHintEngine.register(AxPerfConfig.Res.CPU_MIN_C2, 0L, v -> {
            DeviceData.BoostData d = mEngine.getData();
            if (d == null || d.pMin == null) return;
            String target;
            if (v < 0) target = String.valueOf(d.pBoostHz);
            else if (v > 0) target = String.valueOf(v);
            else target = d.uPMin != null ? d.uPMin : "0";
            AxUtils.write(d.pMin, target);
        });
        mHintEngine.register(AxPerfConfig.Res.CPU_MAX_C0, 0L, v -> {
            DeviceData.BoostData d = mEngine.getData();
            if (d == null || d.sMax == null) return;
            String target = v > 0 ? CPU_MAX_FLOOR
                    : (d.uSMax != null ? d.uSMax : CPU_MAX_FLOOR);
            AxUtils.write(d.sMax, target);
        });
        mHintEngine.register(AxPerfConfig.Res.CPU_MAX_C1, 0L, v -> {
            DeviceData.BoostData d = mEngine.getData();
            if (d == null || d.bMax == null) return;
            String target = v > 0 ? CPU_MAX_FLOOR
                    : (d.uBMax != null ? d.uBMax : CPU_MAX_FLOOR);
            AxUtils.write(d.bMax, target);
        });
        mHintEngine.register(AxPerfConfig.Res.CPU_MAX_C2, 0L, v -> {
            DeviceData.BoostData d = mEngine.getData();
            if (d == null || d.pMax == null) return;
            String target = v > 0 ? CPU_MAX_FLOOR
                    : (d.uPMax != null ? d.uPMax : CPU_MAX_FLOOR);
            AxUtils.write(d.pMax, target);
        });
        mHintEngine.register(AxPerfConfig.Res.DRAM_MIN, 0L, v -> {
            String path = AxNodePaths.getDramMinPath();
            if (path == null) return;
            long target;
            if (v < 0) target = AxNodePaths.getDramBoostHz();
            else if (v > 0) target = v;
            else target = AxNodePaths.getDramDefaultHz();
            AxUtils.write(path, String.valueOf(target));
        });
        mHintEngine.register(AxPerfConfig.Res.GPU_BOOST, 0L, v -> {
            if (AxGpuData.isGpuOppMode()) {
                String oppPath = AxGpuData.getGpuOppPath();
                if (oppPath == null) return;
                int idx = v > 0 ? AxGpuData.getGpuBoostOppIndex()
                        : AxGpuData.getGpuDefaultOppIndex();
                AxUtils.write(oppPath, String.valueOf(idx));
                return;
            }
            String path = AxGpuData.getGpuMinPath();
            if (path == null) return;
            int target = v > 0 ? AxGpuData.getGpuBoostHz()
                    : AxGpuData.getGpuDefaultMinHz();
            if (target <= 0) return;
            AxUtils.write(path, String.valueOf(target));
        });
        mHintEngine.register(AxPerfConfig.Res.GPU_MAX, 0L, v -> {
            if (AxGpuData.isGpuOppMode()) {
                String oppPath = AxGpuData.getGpuOppPath();
                if (oppPath == null) return;
                int idx = v > 0 ? 0 : AxGpuData.getGpuDefaultOppIndex();
                AxUtils.write(oppPath, String.valueOf(idx));
                return;
            }
            String path = AxGpuData.getGpuMinPath();
            if (path == null) return;
            int target = v > 0 ? AxGpuData.getGpuBoostHz()
                    : AxGpuData.getGpuDefaultMinHz();
            if (target <= 0) return;
            AxUtils.write(path, String.valueOf(target));
        });
        mHintEngine.register(AxPerfConfig.Res.CPUSET_SVP, 0L, v -> {
            DeviceData.BoostData d = mEngine.getData();
            if (d == null) return;
            if (v > 0) {
                String ui = (d.pCores != null && !d.pCores.isEmpty())
                        ? d.pCores : d.bCores;
                if (ui != null && !ui.isEmpty()) {
                    mEngine.adjustCpusetCpus(DeviceData.CPU_SVP, ui, 0L);
                }
            } else {
                mEngine.adjustCpusetCpus(DeviceData.CPU_SVP, d.svpCpus, -1L);
            }
        });
        mHintEngine.register(AxPerfConfig.Res.CPUSET_SYSUI, 0L, v -> {
            DeviceData.BoostData d = mEngine.getData();
            if (d == null) return;
            if (v > 0) {
                String ui = (d.pCores != null && !d.pCores.isEmpty())
                        ? d.pCores : d.bCores;
                if (ui != null && !ui.isEmpty()) {
                    mEngine.adjustCpusetCpus(AxBurstEngine.CPU_SYSUI, ui, 0L);
                }
            } else {
                mEngine.adjustCpusetCpus(AxBurstEngine.CPU_SYSUI, d.allCores, -1L);
            }
        });
        mHintEngine.register(AxPerfConfig.Res.CPUSET_TOP, 0L, v -> {
            DeviceData.BoostData d = mEngine.getData();
            if (d == null) return;
            if (v > 0) {
                mEngine.adjustCpusetCpus(DeviceData.CPU_TOP_APP, d.sCores, 0L);
            } else {
                mEngine.adjustCpusetCpus(DeviceData.CPU_TOP_APP, d.allCores, -1L);
            }
        });
        mHintEngine.register(AxPerfConfig.Res.UCLAMP_FG, UCLAMP_MIN_DEFAULT, v -> {
            long target = Math.max(UCLAMP_MIN_DEFAULT, v);
            AxUtils.write(FG_UCLAMP_MIN, String.valueOf(target));
        });
        mHintEngine.register(AxPerfConfig.Res.UCLAMP_TOP, UCLAMP_MIN_TOP_APP_DEFAULT, v -> {
            long target = Math.max(UCLAMP_MIN_TOP_APP_DEFAULT, v);
            AxUtils.write(TA_UCLAMP_MIN, String.valueOf(target));
        });
        mHintEngine.register(AxPerfConfig.Res.RR_FLING, 0L, v -> {
            AxRefreshRateController.getInstance().setFlingBoost(v > 0);
        });
    }

    public boolean isCompositionBoosting() {
        return mLaunchHandle != AxHintEngine.INVALID_HANDLE;
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
        mLaunchHandle = mHintEngine.acquire(h, dur,
                () -> mLaunchHandle = AxHintEngine.INVALID_HANDLE);
    }

    public void shadeBoost(boolean active) {
        if (mEngine.getData() == null || mEngine.gameActive()) return;
        if (active) {
            if (mShadeHandle != AxHintEngine.INVALID_HANDLE) return;
            mShadeHandle = mHintEngine.acquire(AxPerfConfig.getHint(AxPerfConfig.Hint.SHADE),
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
            mFlingHandle = mHintEngine.acquire(AxPerfConfig.getHint(AxPerfConfig.Hint.FLING),
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
            mGpuHandle = mHintEngine.acquire(AxPerfConfig.getHint(AxPerfConfig.Hint.GPU),
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
            mGameHandle = mHintEngine.acquire(AxPerfConfig.getHint(AxPerfConfig.Hint.GAME),
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
            case IAxBurstEngine.Frame.PREFETCHER:
                fireHint(AxPerfConfig.Hint.FRAME_PREFETCHER);
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
            case IAxBurstEngine.Consistency.NORMAL_MODE: {
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
}
