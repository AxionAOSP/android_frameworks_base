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

import static com.android.server.am.AxUtils.*;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.app.AxBoostFwk;
import android.os.IBinder;
import android.os.Parcel;
import android.os.ServiceManager;
import android.os.SystemProperties;

import com.android.internal.os.BackgroundThread;
import com.android.server.AxExtServiceFactory;
import com.android.server.LocalServices;
import com.android.server.NtServiceInjector;
import com.android.server.pinner.PinnedFile;
import com.android.server.pinner.PinnerService;

import java.util.concurrent.ConcurrentHashMap;

public class AxBurstEngine implements IAxBurstEngine {

    private static final String TAG = "AxBurstEngine";

    private final Context mContext;
    private final AxBoostManager mBoostMgr;

    private DeviceData.BoostData mData;
    private DeviceData mDeviceData;

    private volatile boolean mBackgroundLimited = false;

    private volatile boolean mInstallBoostActive = false;

    private final AxThreadBoost mThreadBoost;
    private volatile boolean mGameActive = false;
    
    private long mGameHandle = -1;
    private final ConcurrentHashMap<String, PinnedFile> mPinnedApps = new ConcurrentHashMap<>();
    private PinnerService mPinner;
    
    private final AxWorkloadDetector mAxWorkloadDetector;

    public AxBurstEngine() {
        mThreadBoost = new AxThreadBoost(this);
        mBoostMgr = new AxBoostManager(this);
        mContext = NtServiceInjector.getCtx();
        mAxWorkloadDetector = new AxWorkloadDetector();
    }

    public void systemReady() {
        mDeviceData = new DeviceData();
        BoostSettingsRepository repo =
                new BoostSettingsRepository(mDeviceData, BackgroundThread.getHandler());

        repo.setOnSettingsChangeListener(this::updateConfigs);

        sfBindCoreControll();

        mPinner = LocalServices.getService(PinnerService.class);
    }

    private void sfBindCoreControll() {
        IBinder b = ServiceManager.getService("SurfaceFlinger");
        if (b == null) return;
        Parcel p = Parcel.obtain();
        try {
            p.writeInterfaceToken("android.ui.ISurfaceComposer");
            p.writeInt(1);
            b.transact(1048, p, null, 0);
        } catch (Exception e) {
            logger("sfBindCoreControll failed: " + e);
        } finally {
            p.recycle();
        }
    }

    public DeviceData getDeviceData() {
        return mDeviceData;
    }

    DeviceData.BoostData getData() {
        return mData;
    }

    private void updateConfigs(DeviceData.BoostData data) {
        mData = data;
        writeDefaultCpusets(data);
    }

    private void writeDefaultCpusets(DeviceData.BoostData data) {
        AxBoostManager.native_set_boost_data(
            new String[]{DeviceData.CPU_SYS_BG, DeviceData.CPU_BG, DeviceData.CPU_FG,
                         DeviceData.CPU_TOP_APP, DeviceData.CPU_SVP, DeviceData.CPU_DEX2OAT,
                         DeviceData.CPU_AX_FG, DeviceData.CPU_L_BG, DeviceData.CPU_H_BG},
            new String[]{data.sCores, data.bgCpus, data.fgCpus,
                         data.allCores, data.svpCpus, data.bgCpus, data.fgCpus,
                         data.bgLimit, data.bgCpus}
        );
    }

    public void adjustBackground(boolean limit) {
        if (mData == null) return;
        if (mBackgroundLimited == limit) return;
        acquireHint(AxBoostFwk.OP_UI_BOOST, limit ? -1L : 0L);
        if (!mInstallBoostActive) {
            SystemProperties.set(
                    "dalvik.vm.dex2oat-threads",
                    limit ? "1" : String.valueOf(Runtime.getRuntime().availableProcessors()));
        }
        mBackgroundLimited = limit;
    }

    public boolean isCompositionBoosting() {
        return AxBoostManager.native_is_composition_boosting();
    }

    public boolean shouldDeferProcessPss() {
        return AxBoostManager.native_should_defer_pss();
    }

    public void setCancelCompactionCallback(Runnable r) {
        mBoostMgr.setCancelCompactionCallback(r);
    }

    public long updateTopApp(String processName, int pid, int tid) {
        mBoostMgr.setTopAppRenderThread(pid, tid);
        return -1L;
    }

    public void setThermalState(int level, int cpuCap, int gpuCap) {
        mBoostMgr.setThermalState(level, cpuCap, gpuCap);
    }

    public long acquireHint(int opcode, long durOverrideMs) {
        return mBoostMgr.acquireHint(opcode, durOverrideMs);
    }

    public void onFrameDraw() {
        mBoostMgr.onFrameDraw();
    }

    public void onFrameRealDraw(long durMs) {
        mBoostMgr.onFrameRealDraw(durMs);
    }

    public int perfLockAcquire(int duration, int[] list) {
        return mBoostMgr.perfLockAcquire(duration, list);
    }

    public void perfHintRelease(long handle) {
        mBoostMgr.perfHintRelease(handle);
    }

    public int perfGetFeedback(ApplicationInfo ai, String pkgName) {
        if (pkgName != null) {
            return mAxWorkloadDetector.getType(ai, pkgName);
        }
        return -1;
    }

    public void backgroundLoadLimit(boolean boost) {
        if (mData == null) return;
        adjustBackground(boost);
    }

    public void boostGame(boolean enabled) {
        if (mGameActive == enabled) return;
        mGameActive = enabled;
        adjustBackground(enabled);
        if (enabled) {
            mGameHandle = acquireHint(AxBoostFwk.OP_SYSTEM_GAME, -1L);
        } else if (mGameHandle >= 0) {
            perfHintRelease(mGameHandle);
            mGameHandle = -1;
        }
    }

    boolean gameActive() {
        return mGameActive;
    }

    public void getProcessesAndFrozen(String packageName) {
        AxExtServiceFactory.getAxFreezeManager().freeze(packageName);
    }

    public void boostInstall(boolean boost) {
        if (mData == null) return;

        mInstallBoostActive = boost;

        boolean limitBackground = !boost && mBackgroundLimited;
        int availableThreads = Runtime.getRuntime().availableProcessors();
        int threadCount = limitBackground ? 1 : availableThreads;
        String cpuSet = limitBackground ? expandRanges(mData.bgCpus) : expandRanges(mData.allCores);

        acquireHint(AxBoostFwk.OP_PACKAGE_INSTALL_BOOST, boost ? -1L : 0L);

        SystemProperties.set("dalvik.vm.dex2oat-threads", String.valueOf(threadCount));
        SystemProperties.set("dalvik.vm.restore-dex2oat-threads", String.valueOf(threadCount));
        SystemProperties.set("dalvik.vm.dex2oat-cpu-set", cpuSet);
        SystemProperties.set("dalvik.vm.restore-dex2oat-cpu-set", cpuSet);

        logger("boostInstall boost=" + boost + " threads=" + threadCount + " cpuset=" + cpuSet);
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

    public void pinApp(ApplicationInfo info) {
        if (info == null || info.sourceDir == null) return;
        if (info.isSystemApp()) return;
        if (mPinner == null) return;
        if (mPinnedApps.containsKey(info.packageName)) return;
        PinnedFile pf = mPinner.pinFile(info.sourceDir, Integer.MAX_VALUE,
                null, "app_pin", false);
        if (pf != null) mPinnedApps.put(info.packageName, pf);
    }

    public void unpinApp(String packageName) {
        if (packageName == null) return;
        PinnedFile pf = mPinnedApps.remove(packageName);
        if (pf != null) pf.close();
    }
}
