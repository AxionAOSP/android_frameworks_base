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

import static com.android.server.am.DeviceData.*;
import static com.android.server.am.BoostFlagsManager.*;
import static com.android.server.am.AxUtils.*;
import static android.os.Process.*;

import android.content.Context;
import android.os.*;
import android.os.Process;
import android.os.Handler;
import android.provider.Settings;
import android.util.Slog;
import com.android.server.NtServiceInjector;
import com.android.server.UiThread;
import com.android.internal.util.ScrollOptimizer;
import java.util.ArrayList;
import java.util.HashMap;

public class AxBurstEngine implements IAxBurstEngine {

    private static final String TAG = "AxBurstEngine";

    private static final int MSG_GAME_BOOST = 108;
    private static final long INPUT_BOOST_DURATION = SystemProperties.getLong(
            "persist.sys.ax.idle_timeout_ms", 1500);

    private static final HashMap<String, String> sConfig = new HashMap<>();
    private static final HashMap<String, String> sDefaultsCpu = new HashMap<>();

    public static final String CPU_CAMERA = AxCpusetManager.CPU_CAMERA;
    public static final String CPU_SYSUI = AxCpusetManager.CPU_SYSUI;
    public static final String CPU_NNAPI_HAL = AxCpusetManager.CPU_NNAPI_HAL;
    public static final String CPU_RT = AxCpusetManager.CPU_RT;
    public static final String CPU_SYSTEM = AxCpusetManager.CPU_SYSTEM;

    private static final String FG_UCLAMP_MIN_PATH = "/dev/cpuctl/foreground/cpu.uclamp.min";
    private static final String UCLAMP_MIN_BOOT = "30";

    private ProcessList procList;
    private Context mContext;
    private final Handler mHandler;
    private final HandlerThread mBoostHandlerThread;
    private final AxFreezeManager mFreezeManager = new AxFreezeManager();
    private final AxBoostManager mBoostMgr;

    private DeviceData.BoostData mData;
    private DeviceData mDeviceData;
    private BoostFlagsManager mFlags;

    private boolean mBackgroundLimited = false;
    private boolean mSfBoosted = false;
    private boolean mSfBindPersistent = false;
    private final Runnable inputReset = new InputBoostResetRunnable();
    private final Runnable sfBindReset = new SfBindControlRunnable();

    private boolean mSystemReady = false;
    private boolean mInstallBoostActive = false;

    private final AxThreadBoost mThreadBoost;
    

    public AxBurstEngine() {
        mBoostHandlerThread = new HandlerThread("AxBurstEngineThread", -2);
        mBoostHandlerThread.start();
        mHandler = new AxBurstEngineHandler(mBoostHandlerThread.getLooper());

        mFlags = new BoostFlagsManager();
        mThreadBoost = new AxThreadBoost(mHandler, this);
        mBoostMgr = new AxBoostManager(this, mHandler);
    }

    public void systemReady() {
        mContext = NtServiceInjector.getCtx();
        procList = NtServiceInjector.getAm().mProcessList;
        mDeviceData = new DeviceData();
        BoostSettingsRepository repo = new BoostSettingsRepository(mDeviceData, mHandler);

        repo.setOnSettingsChangeListener(this::updateConfigs);

        mSfBindPersistent = true;
        mHandler.post(() -> sfBindCoreControll(true));
        mHandler.post(() -> AxUtils.write(FG_UCLAMP_MIN_PATH, UCLAMP_MIN_BOOT));

        mFreezeManager.setSystemReady(mContext, procList);

        mSystemReady = true;
    }

    public void onWakefulnessChanged(boolean awake) {
        if (!mSystemReady) return;
        mSfBindPersistent = awake;
        mHandler.post(() -> sfBindCoreControll(awake));
    }

    public DeviceData getDeviceData() {
        return mDeviceData;
    }

    DeviceData.BoostData getData() {
        return mData;
    }

    private void updateConfigs(DeviceData.BoostData data) {
        mData = data;
        mBoostMgr.onConfigsUpdated();

        sConfig.clear();
        sConfig.put(data.sMin, data.uSMin);
        sConfig.put(data.bMin, data.uBMin);
        sConfig.put(data.pMin, data.uPMin);
        sConfig.put(data.sMax, data.uSMax);
        sConfig.put(data.bMax, data.uBMax);
        sConfig.put(data.pMax, data.uPMax);

        sDefaultsCpu.clear();
        sDefaultsCpu.put(CPU_SYS_BG, data.sCores);
        sDefaultsCpu.put(CPU_BG, data.bgCpus);
        sDefaultsCpu.put(CPU_TOP_APP, data.allCores);
        sDefaultsCpu.put(CPU_CAMERA, data.allCores);
        sDefaultsCpu.put(CPU_FG, data.fgCpus);
        sDefaultsCpu.put(CPU_SVP, data.svpCpus);
        sDefaultsCpu.put(CPU_DEX2OAT, data.bgCpus);
        sDefaultsCpu.put(CPU_AX_FG, data.fgCpus);
        sDefaultsCpu.put(CPU_L_BG, data.bgLimit);
        sDefaultsCpu.put(CPU_H_BG, data.bgCpus);
        sDefaultsCpu.put(CPU_NNAPI_HAL, data.sCores);
        sDefaultsCpu.put(CPU_RT, data.allCores);
        sDefaultsCpu.put(CPU_SYSTEM, data.sCores);
        sDefaultsCpu.put(CPU_SYSUI, data.allCores);

        write(sConfig);
        writeDefaultCpusets();
    }

    private void writeDefaultCpusets() {
        mHandler.post(() -> sDefaultsCpu.forEach(AxCpusetManager::restoreCpuset));
    }

    public void adjustCpusetCpus(String path, String value, long duration) {
        AxCpusetManager.adjust(mHandler, path, value, duration, Binder.getCallingUid());
    }

    public void inputBoost() {
        if (mData == null) {
            return;
        }
        if (gameActive()) {
            if (!mBackgroundLimited) {
                adjustBackground(true);
            }
            return;
        }
        if (mBackgroundLimited) {
            UiThread.getHandler().removeCallbacks(inputReset);
            UiThread.getHandler().postDelayed(inputReset, INPUT_BOOST_DURATION);
        } else {
            adjustBackground(true);
            UiThread.getHandler().postDelayed(inputReset, INPUT_BOOST_DURATION);
        }
    }
    
    public void adjustBackground(boolean limit) {
        if (mData == null) return;
        final long duration = limit ? 0L : -1L;
        final String bgLimit = limit ? mData.bgLimit : mData.bgCpus;
        final String axFgLimit = limit ? mData.uiLimit : mData.fgCpus;
        adjustCpusetCpus(CPU_H_BG, bgLimit, duration);
        adjustCpusetCpus(CPU_AX_FG, axFgLimit, duration);
        adjustCpusetCpus(CPU_DEX2OAT, bgLimit, duration);
        if (!mInstallBoostActive) {
            SystemProperties.set("dalvik.vm.dex2oat-threads", limit
                ? "1"
                : String.valueOf(Runtime.getRuntime().availableProcessors()));
        }
        mBackgroundLimited = limit;
    }

    private class InputBoostResetRunnable implements Runnable {
        @Override
        public void run() {
            adjustBackground(false);
        }
    }
    
    private class SfBindControlRunnable implements Runnable {
        @Override
        public void run() {
            sfBindCoreControll(false);
        }
    }

    public void gpuBoost(boolean active) {
        mBoostMgr.gpuBoost(active);
    }

    public void gpuMaxBoost(boolean active) {
        mBoostMgr.gpuMaxBoost(active);
    }

    public void compositionBoost(long durationMs) {
        mBoostMgr.compositionBoost(durationMs);
    }

    @Override
    public boolean isCompositionBoosting() {
        return mBoostMgr.isCompositionBoosting();
    }

    public void compositionBoost(long durationMs, int topAppPid) {
        mBoostMgr.compositionBoost(durationMs, topAppPid);
    }

    public void shadeBoost(boolean active) {
        mBoostMgr.shadeBoost(active);
    }

    public void flingBoost(boolean active) {
        mBoostMgr.flingBoost(active);
    }

    public void onScrollEvent(int action) {
        mBoostMgr.onScrollEvent(action);
    }

    public void onLaunch(int type) {
        mBoostMgr.onLaunch(type);
    }

    public void onFrameStage(int stage, long frameId) {
        mBoostMgr.onFrameStage(stage, frameId);
    }

    public void onRefreshRateEvent(int event) {
        mBoostMgr.onRefreshRateEvent(event);
    }

    public void onImeTransition(int action) {
        mBoostMgr.onImeTransition(action);
    }

    public void onConsistency(int mode) {
        mBoostMgr.onConsistency(mode);
    }

    public void onAnimation(int action) {
        mBoostMgr.onAnimation(action);
    }

    class AxBurstEngineHandler extends Handler {
        AxBurstEngineHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            if (!mSystemReady || mData == null) return;
            int what = msg.what;
            switch (what) {
                case AxCpusetManager.MSG_BG:
                    AxCpusetManager.expireOverride(CPU_BG, msg.arg1, mData.bgCpus);
                    break;
                case AxCpusetManager.MSG_SYS_BG:
                    AxCpusetManager.expireOverride(CPU_SYS_BG, msg.arg1, mData.sCores);
                    break;
                case AxCpusetManager.MSG_TOP_APP:
                    AxCpusetManager.expireOverride(CPU_TOP_APP, msg.arg1, mData.allCores);
                    break;
                case AxCpusetManager.MSG_CAMERA:
                    AxCpusetManager.expireOverride(CPU_CAMERA, msg.arg1, mData.allCores);
                    break;
                case AxCpusetManager.MSG_FG:
                    AxCpusetManager.expireOverride(CPU_FG, msg.arg1, mData.fgCpus);
                    break;
                case AxCpusetManager.MSG_SVP:
                    AxCpusetManager.expireOverride(CPU_SVP, msg.arg1, mData.svpCpus);
                    break;
                case AxCpusetManager.MSG_DEX:
                    AxCpusetManager.expireOverride(CPU_DEX2OAT, msg.arg1, mData.bgCpus);
                    break;
                case AxCpusetManager.MSG_AX_FG:
                    AxCpusetManager.expireOverride(CPU_AX_FG, msg.arg1, mData.fgCpus);
                    break;
                case AxCpusetManager.MSG_L_BG:
                    AxCpusetManager.expireOverride(CPU_L_BG, msg.arg1, mData.bgLimit);
                    break;
                case AxCpusetManager.MSG_H_BG:
                    AxCpusetManager.expireOverride(CPU_H_BG, msg.arg1, mData.bgCpus);
                    break;
                case MSG_GAME_BOOST:
                    final boolean boost = msg.arg1 == 1;
                    backgroundLoadLimit(boost);
                    sfBindCoreControll(boost);
                    mBoostMgr.gameBoost(boost);
                    break;
                default:
                    logger("unknown msg, drop it!");
                    break;
            }
        }
    }

    public void backgroundLoadLimit(boolean boost) {
        if (mData == null) return;
        if (boost) {
            UiThread.getHandler().removeCallbacks(inputReset);
        }
        adjustBackground(boost /* limit */);
    }
    
    public void boostGame(boolean enabled) {
        if (!mFlags.isNewState(BOOST_GM, enabled)) return;
        final boolean boost = enabled;
        mFlags.setFlag(BOOST_GM, boost);
        mHandler.sendMessage(mHandler.obtainMessage(MSG_GAME_BOOST, enabled ? 1 : 0));
    }

    void boostSfDelegated(long duration) {
        boostSf(duration);
    }

    private void boostSf(long duration) {
        if (gameActive()) {
            if (!mSfBoosted) {
                sfBindCoreControll(true);
            }
            return;
        }
        if (mSfBoosted) {
            mHandler.removeCallbacks(sfBindReset);
            mHandler.postDelayed(sfBindReset, duration);
        } else {
            sfBindCoreControll(true);
            mHandler.postDelayed(sfBindReset, duration);
        }
    }

    private void sfBindCoreControll(boolean enabled) {
        if (!enabled && mSfBindPersistent) return;
        IBinder b = ServiceManager.getService("SurfaceFlinger");
        if (b == null) return;
        Parcel p = Parcel.obtain();
        try {
            p.writeInterfaceToken("android.ui.ISurfaceComposer");
            p.writeInt(enabled ? 1 : 0);
            b.transact(1048, p, null, 0);
        } catch (Exception e) {
            logger("sfBindCoreControll transact failed: " + e);
        } finally {
            p.recycle();
        }
        mSfBoosted = enabled;
    }

    public void write(HashMap<String, String> values) {
        mHandler.post(() -> values.forEach((k, v) -> {
            if (k != null && v != null) AxUtils.write(k, v);
        }));
    }
    
    boolean gameActive() {
        return mFlags.isActive(BOOST_GM);
    }
    
    public void getProcessesAndFrozen(String packageName) {
        mFreezeManager.freeze(packageName);
    }


    public void boostInstall(boolean boost) {
        if (mData == null) return;
        
        mInstallBoostActive = boost;

        String allCores = joinRanges(mData.sCores, mData.bCores);
        if (!mData.pCores.isEmpty()) {
            allCores = joinRanges(allCores, mData.pCores);
        }

        allCores = allCores.replace("-", ",");

        int threadCount = boost ?
                Runtime.getRuntime().availableProcessors() : 1;

        String cpuSet = boost ? allCores : mData.bgCpus.replace("-", ",");
        
        final long duration = boost ? 0L : -1L;
        final String dexBoost = boost ? mData.allCores : mData.bgCpus;
        
        adjustCpusetCpus(CPU_DEX2OAT, dexBoost, duration);

        SystemProperties.set("dalvik.vm.dex2oat-threads", String.valueOf(threadCount));
        SystemProperties.set("dalvik.vm.restore-dex2oat-threads", String.valueOf(threadCount));
        SystemProperties.set("dalvik.vm.dex2oat-cpu-set", cpuSet);
        SystemProperties.set("dalvik.vm.restore-dex2oat-cpu-set", cpuSet);

        logger("boostInstall boost=" + boost +
                " threads=" + threadCount + " cpuset=" + cpuSet);
    }
    
    public void boostThread(int tid) {
        mThreadBoost.boost(tid);
    }

    public void systemThreadBoost(int tid, long duration) {
        mThreadBoost.systemBoost(tid, duration);
    }

    public void launcherItemsLoadingBoost(long duration) {
        mThreadBoost.launcherLoadBoost(duration);
    }
}
