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

import android.app.role.RoleManager;
import android.content.Context;
import android.hardware.power.Mode;
import android.os.*;
import android.os.Process;
import android.os.Handler;
import android.provider.Settings;
import android.util.Slog;
import com.android.server.NtServiceInjector;
import com.android.server.UiThread;
import com.android.internal.util.ScrollOptimizer;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;

public class AxBurstEngine implements IAxBurstEngine {

    private static final String TAG = "AxBurstEngine";

    private static final int MSG_CPU_UPDATE_BACKGROUND = 100;
    private static final int MSG_CPU_UPDATE_SYS_BG = 101;
    private static final int MSG_CPU_UPDATE_TOP_APP = 102;
    private static final int MSG_CPU_UPDATE_CAMERA = 103;
    private static final int MSG_CPU_UPDATE_FG = 104;
    private static final int MSG_CPU_UPDATE_RES = 105;
    private static final int MSG_CPU_UPDATE_DEX = 106;
    private static final int MSG_CPU_UPDATE_AX_FG = 107;
    private static final int MSG_GAME_BOOST = 108;
    
    private static final long INPUT_BOOST_DURATION = 800L;

    private static final HashMap<String, File> sFileCache = new HashMap<>();
    private static final HashMap<String, Integer> sCpuUpdateMessages = new HashMap<>();
    private static final HashMap<String, HashMap> sCpusetGroups = new HashMap<>();

    private static final HashMap<Integer, Object> bgCpusetOverrides = new HashMap<>();
    private static final HashMap<Integer, Object> cameraCpusetOverrides = new HashMap<>();
    private static final HashMap<Integer, Object> sysBgCpusetOverrides = new HashMap<>();
    private static final HashMap<Integer, Object> topAppCpusetOverrides = new HashMap<>();
    private static final HashMap<Integer, Object> fgCpusetOverrides = new HashMap<>();
    private static final HashMap<Integer, Object> svpCpusetOverrides = new HashMap<>();
    private static final HashMap<Integer, Object> dex2oatCpusetOverrides = new HashMap<>();
    private static final HashMap<Integer, Object> axFgCpusetOverrides = new HashMap<>();
    
    private static final HashMap<String, String> sConfig = new HashMap<>();
    private static final HashMap<String, String> sRestrictBackgroundOn = new HashMap<>();
    private static final HashMap<String, String> sRestrictBackgroundOff = new HashMap<>();
    private static final HashMap<String, String> sDefaultsCpu = new HashMap<>();
    
    public static final String CPU_CAMERA = AxUtils.cpuPath("camera-daemon");
    
    private ProcessList procList;
    private Context mContext;
    private final Handler mHandler;
    private final HandlerThread mBoostHandlerThread;
    private final HandlerThread mFreezeHandlerThread;
    
    private DeviceData.BoostData mData;
    private DeviceData mDeviceData;
    private BoostFlagsManager mFlags;

    private boolean mBackgroundLimited = false;
    private boolean mSfBoosted = false;
    private final Runnable inputReset = new InputBoostResetRunnable();
    private final Runnable sfBindReset = new SfBindControlRunnable();

    private Handler mFreezeHandler;
    private boolean mFreezing = false;
    private int mFreezeDuration = 600;

    private boolean mSystemReady = false;
    
    private boolean mInstallBoostActive = false;

    private final HashMap<Integer, Integer> mBoostCount = new HashMap<>();
    private final HashMap<Integer, Integer> mOriginalPriorities = new HashMap<>();

    private final Runnable mLauncherBoostReset = this::restoreLauncherBoost;
    
    static {
        sFileCache.put(CPU_BG, new File(CPU_BG));
        sFileCache.put(CPU_SYS_BG, new File(CPU_SYS_BG));
        sFileCache.put(CPU_TOP_APP, new File(CPU_TOP_APP));
        sFileCache.put(CPU_FG, new File(CPU_FG));
        sFileCache.put(CPU_SVP, new File(CPU_SVP));
        sFileCache.put(CPU_DEX2OAT, new File(CPU_DEX2OAT));
        sFileCache.put(CPU_AX_FG, new File(CPU_AX_FG));
        sFileCache.put(CPU_CAMERA, new File(CPU_CAMERA));

        sCpuUpdateMessages.put(CPU_SYS_BG, Integer.valueOf(MSG_CPU_UPDATE_SYS_BG));
        sCpuUpdateMessages.put(CPU_BG, Integer.valueOf(MSG_CPU_UPDATE_BACKGROUND));
        sCpuUpdateMessages.put(CPU_TOP_APP, Integer.valueOf(MSG_CPU_UPDATE_TOP_APP));
        sCpuUpdateMessages.put(CPU_CAMERA, Integer.valueOf(MSG_CPU_UPDATE_CAMERA));
        sCpuUpdateMessages.put(CPU_FG, Integer.valueOf(MSG_CPU_UPDATE_FG));
        sCpuUpdateMessages.put(CPU_SVP, Integer.valueOf(MSG_CPU_UPDATE_RES));
        sCpuUpdateMessages.put(CPU_DEX2OAT, Integer.valueOf(MSG_CPU_UPDATE_DEX));
        sCpuUpdateMessages.put(CPU_AX_FG, Integer.valueOf(MSG_CPU_UPDATE_AX_FG));

        sCpusetGroups.put(CPU_BG, bgCpusetOverrides);
        sCpusetGroups.put(CPU_SYS_BG, sysBgCpusetOverrides);
        sCpusetGroups.put(CPU_TOP_APP, topAppCpusetOverrides);
        sCpusetGroups.put(CPU_CAMERA, cameraCpusetOverrides);
        sCpusetGroups.put(CPU_FG, fgCpusetOverrides);
        sCpusetGroups.put(CPU_SVP, svpCpusetOverrides);
        sCpusetGroups.put(CPU_DEX2OAT, dex2oatCpusetOverrides);
        sCpusetGroups.put(CPU_AX_FG, axFgCpusetOverrides);
    }

    public AxBurstEngine() {
        mBoostHandlerThread = new HandlerThread("AxBurstEngineThread", -2);
        mBoostHandlerThread.start();
        mHandler = new AxBurstEngineHandler(mBoostHandlerThread.getLooper());

        mFreezeHandlerThread = new HandlerThread("FreezeHandlerThread", -2);
        mFreezeHandlerThread.start();
        mFreezeHandler = new FreezerHandler(mFreezeHandlerThread.getLooper());

        mFlags = new BoostFlagsManager();
    }

    public void systemReady() {
        mContext = NtServiceInjector.getCtx();
        procList = NtServiceInjector.getAm().mProcessList;
        mDeviceData = new DeviceData();
        BoostSettingsRepository repo = new BoostSettingsRepository(mDeviceData, mHandler);

        repo.setOnSettingsChangeListener(this::updateConfigs);
        
        mSystemReady = true;
    }

    public DeviceData getDeviceData() {
        return mDeviceData;
    }

    private void updateConfigs(DeviceData.BoostData data) {
        mData = data;

        sConfig.clear();
        sConfig.put(data.sMin, data.uSMin);
        sConfig.put(data.bMin, data.uBMin);
        sConfig.put(data.pMin, data.uPMin);
        sConfig.put(data.sMax, data.uSMax);
        sConfig.put(data.bMax, data.uBMax);
        sConfig.put(data.pMax, data.uPMax);

        sRestrictBackgroundOn.clear();
        sRestrictBackgroundOn.put(CPU_BG, data.bgLimit);
        sRestrictBackgroundOn.put(CPU_AX_FG, data.bgLimit);

        sRestrictBackgroundOff.clear();
        sRestrictBackgroundOff.put(CPU_BG, data.bgCpus);
        sRestrictBackgroundOff.put(CPU_AX_FG, data.bgCpus);

        sDefaultsCpu.clear();
        sDefaultsCpu.put(CPU_SYS_BG, data.sCores);
        sDefaultsCpu.put(CPU_BG, data.bgCpus);
        sDefaultsCpu.put(CPU_TOP_APP, data.allCores);
        sDefaultsCpu.put(CPU_CAMERA, data.allCores);
        sDefaultsCpu.put(CPU_FG, data.allCores);
        sDefaultsCpu.put(CPU_SVP, data.boostCpus);
        sDefaultsCpu.put(CPU_DEX2OAT, data.bgCpus);
        sDefaultsCpu.put(CPU_AX_FG, data.allCores);

        write(sConfig);
    }

    private void restoreCpuset(String path, String cpus) {
        if (cpus == null) return;
        File file = sFileCache.get(path);
        if (file == null) return;
        try {
            FileUtils.stringToFile(file, cpus);
            logger("restore: cpuFile = " + file + ", cpus = " + cpus);
        } catch (IOException e) {
            logger("restore cpuset failed");
        }
    }

    public void adjustCpusetCpus(String path, String value, long duration) {
        int callingUid = Binder.getCallingUid();
        logger("adjustCpusetCpus: uid=" + callingUid + " path=" + path + 
                " value=" + value + " duration=" + duration);

        HashMap map = sCpusetGroups.get(path);
        long now = System.currentTimeMillis();
        long expiry = (duration == -1L) ? -1L : now + duration;
        CpusetData newData = new CpusetData(callingUid, value, now, expiry);

        if (map == null) {
            logger("unknown group: " + path + ", ignore!");
            return;
        }

        if (duration >= 0) {
            CpusetData existing = (CpusetData) map.get(Integer.valueOf(callingUid));
            if (existing == null) {
                map.put(Integer.valueOf(callingUid), newData);
            } else {
                if (existing.duration == -1L && (newData.duration == -1L || newData.duration > 0)
                        || (existing.duration > 0 && newData.duration > 0 && newData.duration < existing.duration)) {
                    logger(callingUid + " not need set again, return!");
                    return;
                }
                existing.duration = newData.duration;
            }
        } else if (duration == -1) {
            map.remove(Integer.valueOf(callingUid));
        }

        File file = sFileCache.get(path);
        if (file.exists()) {
            try {
                logger("uid = " + callingUid + " group = " + path + 
                        ", origin cpus: " + sDefaultsCpu.get(path) + 
                        ", targetCpus = " + value + ", duration = " + duration);
                FileUtils.stringToFile(file, value);
            } catch (IOException e) {
                logger("adjust cpuset failed");
            }
        }

        if (duration > 0) {
            Integer what = sCpuUpdateMessages.get(path);
            if (what != null) {
                Message delayedMsg = mHandler.obtainMessage(what.intValue());
                delayedMsg.arg1 = callingUid;
                mHandler.sendMessageDelayed(delayedMsg, duration);
            }
        }
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
        final String axFgLimit = limit ? mData.uiLimit : mData.allCores;
        adjustCpusetCpus(CPU_AX_FG, axFgLimit, duration);
        adjustCpusetCpus(CPU_DEX2OAT, bgLimit, duration);
        adjustCpusetCpus(CPU_BG, bgLimit, duration);
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

    private static class CpusetData {
        private final String value;
        private final int uid;
        private long currentTime;
        private long duration;

        public CpusetData(int uid, String value, long currentTime, long duration) {
            this.uid = uid;
            this.value = value;
            this.currentTime = currentTime;
            this.duration = duration;
        }
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
                case MSG_CPU_UPDATE_BACKGROUND:
                    bgCpusetOverrides.remove(Integer.valueOf(msg.arg1));
                    if (bgCpusetOverrides.isEmpty()) {
                        restoreCpuset(CPU_BG, mData.bgCpus);
                    }
                    break;
                case MSG_CPU_UPDATE_SYS_BG:
                    sysBgCpusetOverrides.remove(Integer.valueOf(msg.arg1));
                    if (sysBgCpusetOverrides.isEmpty()) {
                        restoreCpuset(CPU_SYS_BG, mData.sCores);
                    }
                    break;
                case MSG_CPU_UPDATE_TOP_APP:
                    topAppCpusetOverrides.remove(Integer.valueOf(msg.arg1));
                    if (topAppCpusetOverrides.isEmpty()) {
                        restoreCpuset(CPU_TOP_APP, mData.allCores);
                    }
                    break;
                case MSG_CPU_UPDATE_CAMERA:
                    cameraCpusetOverrides.remove(Integer.valueOf(msg.arg1));
                    if (cameraCpusetOverrides.isEmpty()) {
                        restoreCpuset(CPU_CAMERA, mData.allCores);
                    }
                    break;
                case MSG_CPU_UPDATE_FG:
                    fgCpusetOverrides.remove(Integer.valueOf(msg.arg1));
                    if (fgCpusetOverrides.isEmpty()) {
                        restoreCpuset(CPU_FG, mData.allCores);
                    }
                    break;
                case MSG_CPU_UPDATE_RES:
                    svpCpusetOverrides.remove(Integer.valueOf(msg.arg1));
                    if (svpCpusetOverrides.isEmpty()) {
                        restoreCpuset(CPU_SVP, mData.boostCpus);
                    }
                    break;
                case MSG_CPU_UPDATE_DEX:
                    dex2oatCpusetOverrides.remove(Integer.valueOf(msg.arg1));
                    if (dex2oatCpusetOverrides.isEmpty()) {
                        restoreCpuset(CPU_DEX2OAT, mData.bgCpus);
                    }
                    break;
                case MSG_CPU_UPDATE_AX_FG:
                    axFgCpusetOverrides.remove(Integer.valueOf(msg.arg1));
                    if (axFgCpusetOverrides.isEmpty()) {
                        restoreCpuset(CPU_AX_FG, mData.allCores);
                    }
                    break;
                case MSG_GAME_BOOST:
                    final boolean boost = msg.arg1 == 1;
                    backgroundLoadLimit(boost);
                    sfBindCoreControll(boost);
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
    
    public void onWakefulnessChanged(boolean awake) {
        mHandler.post(() -> write(awake ? sRestrictBackgroundOff : sRestrictBackgroundOn));
    }
    
    public void boostGame(boolean enabled) {
        if (!mFlags.isNewState(BOOST_GM, enabled)) return;
        final boolean boost = enabled;
        mFlags.setFlag(BOOST_GM, boost);
        mHandler.sendMessage(mHandler.obtainMessage(MSG_GAME_BOOST, enabled ? 1 : 0));
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
    
    private boolean gameActive() {
        return mFlags.isActive(BOOST_GM);
    }
    
    public void getProcessesAndFrozen(String packageName) {
        if (mFreezeHandler == null || packageName == null || mFreezing) {
            logger("AnimationFreeze: freezing not needed. ignoring!");
            return;
        }
        mFreezing = true;
        Message message = new Message();
        message.obj = packageName;
        message.what = 1;
        mFreezeHandler.sendMessage(message);
    }
    
    private void setFrozen(int pid, int uid, boolean frozen) {
        try {
            Process.setProcessFrozen(pid, uid, frozen);
        } catch (Exception e) {
            logger(e.toString());
        }
        logger("AnimationFreeze: frozen: uid = " + uid + ", pid = " + pid + 
                ", frozen: " + frozen);
    }

    private void animationUnfreeze(ArrayList<ProcessRecord> procListToUnfreeze) {
        logger("AnimationFreeze: unfrozen processes start");
        for (int i = 0; i < procListToUnfreeze.size(); i++) {
            ProcessRecord record = procListToUnfreeze.get(i);
            setFrozen(record.mPid, record.getUid(), false);
        }
        logger("AnimationFreeze: unfrozen processes end");
    }
    
    private void animationFreeze(ArrayList<ProcessRecord> procListToFreeze) {
        logger("AnimationFreeze: frozen processes start");
        for (int i = 0; i < procListToFreeze.size(); i++) {
            ProcessRecord record = procListToFreeze.get(i);
            setFrozen(record.mPid, record.getUid(), true);
        }
        logger("AnimationFreeze: frozen processes end");
    }

    private void backgroundFreeze(String packageName) {
        logger("AnimationFreeze: get frozen app list and frozen start");
        ArrayList<ProcessRecord> freezeList = new ArrayList<>();
        if (procList == null) {
            Slog.e(TAG, "AnimationFreeze: system is not ready, return!");
            mFreezing = false;
            return;
        }
        ArrayList<ProcessRecord> lruProcesses = (ArrayList<ProcessRecord>) procList.ntGetLruProcesses().clone();
        try {
            boolean homeContains = packageName.isEmpty() ? false :
                    ((RoleManager) mContext.getSystemService(RoleManager.class))
                    .getRoleHolders("android.app.role.HOME").contains(packageName);

            for (int i = 0; i < lruProcesses.size(); i++) {
                ProcessRecord pr = lruProcesses.get(i);
                if (pr != null && !pr.getProcessName().equals(packageName) 
                        && !pr.getProcessName().contains("webview")
                        && (!homeContains || !pr.getProcessName().equals(
                                "com.google.android.googlequicksearchbox:search"))) {
                    int curAdj = pr.getCurAdj();
                    if (pr.getUid() > 10000 && curAdj >= 250 && 
                            curAdj != 600 && curAdj != 700 && curAdj < 900) {
                        logger("AnimationFreeze: freeze package: " + pr.getProcessName());
                        freezeList.add(pr);
                    }
                }
            }
            
            final int size = freezeList.size();

            if (size == 0) {
                mFreezing = false;
                return;
            }

            mFreezeHandler.post(new AnimationFreezeRunnable(freezeList));
            logger("AnimationFreeze: get frozen app list and frozen end, size=" + size);
            mFreezeHandler.postDelayed(new AnimationUnfreezeRunnable(freezeList), 
                    (long) mFreezeDuration);
        } catch (Exception e) {
            logger("AnimationFreeze: get process failed, return!");
            mFreezing = false;
        }
    }

    class FreezerHandler extends Handler {
        FreezerHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            if (msg.what != 1) {
                return;
            }
            backgroundFreeze(String.valueOf(msg.obj));
        }
    }
    
    class AnimationFreezeRunnable implements Runnable {
        final ArrayList<ProcessRecord> freezeList;

        AnimationFreezeRunnable(ArrayList<ProcessRecord> freezeList) {
            this.freezeList = freezeList;
        }

        @Override
        public void run() {
            animationFreeze(freezeList);
        }
    }

    class AnimationUnfreezeRunnable implements Runnable {
        final ArrayList<ProcessRecord> procListToUnfreeze;

        AnimationUnfreezeRunnable(ArrayList<ProcessRecord> procList) {
            this.procListToUnfreeze = procList;
        }

        @Override
        public void run() {
            animationUnfreeze(procListToUnfreeze);
            mFreezing = false;
        }
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
        mHandler.post(() -> {
            Process.setThreadScheduler(tid, SCHED_RR | SCHED_RESET_ON_FORK, 1);
        });
    }
    
    private final HashMap<Integer, Integer> mGcThreadIds = new HashMap<>();
    public void boostGcThread(int pid, boolean boost) {
        if (pid <= 0) return;
        mHandler.post(() -> {
            int gcTid = findGcThread(pid);
            if (gcTid <= 0) return;
            applyThreadPriorityBoost(gcTid, boost ? 0 : -1);
        });
    }
    private int findGcThread(int pid) {
        Integer cached = mGcThreadIds.get(pid);
        if (cached != null) return cached;
        try {
            File[] tasks = new File("/proc/" + pid + "/task").listFiles();
            if (tasks == null) return -1;
            for (File task : tasks) {
                try (BufferedReader r = new BufferedReader(
                        new FileReader(new File(task, "comm")))) {
                    String name = r.readLine();
                    if (name == null) continue;
                    name = name.trim();
                    if (name.contains("GC") || name.equals("HeapTaskDaemon")) {
                        int tid = Integer.parseInt(task.getName());
                        mGcThreadIds.put(pid, tid);
                        return tid;
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
        return -1;
    }
    public void systemThreadBoost(int tid, long duration) {
        if (tid <= 0) return;
        boostSf(duration);

        applyThreadPriorityBoost(tid, duration);

        int myPid = Process.myPid();
        if (myPid > 0) {
            ProcessRecord pr = NtServiceInjector.getAm().getProcessRecordByPid(myPid);
            if (pr != null) {
                int renderTid = pr.getRenderThreadTid();
                if (renderTid > 0) {
                    applyThreadPriorityBoost(renderTid, duration);
                }
            }
        }
    }

    private void applyThreadPriorityBoost(int tid, long duration) {
        if (duration <= 0) {
            if (duration == 0) tryBoostThreadPriority(tid);
            else if (duration == -1) tryRestoreThreadPriority(tid);
            return;
        }

        if (tryBoostThreadPriority(tid)) {
            mHandler.postDelayed(() -> tryRestoreThreadPriority(tid), duration);
        }
    }

    private synchronized boolean tryBoostThreadPriority(int tid) {
        Integer count = mBoostCount.get(tid);
        if (count != null) {
            mBoostCount.put(tid, count + 1);
        } else {
            try {
                mOriginalPriorities.put(tid, Process.getThreadPriority(tid));
                ActivityManagerService.scheduleAsRoundRobinPriority(tid, true);
                mBoostCount.put(tid, 1);
            } catch (Exception e) {
                return false;
            }
        }
        return true;
    }

    public void launcherItemsLoadingBoost(long duration) {
        try {
            final int pid = Process.myPid();
            if (pid > 0) {
                if (duration >= 0) {
                    ActivityManagerService.scheduleAsRoundRobinPriority(pid, true);
                    adjustCpusetCpus(CPU_FG, mData.fgLimited, duration);
                    if (duration > 0) {
                        mHandler.removeCallbacks(mLauncherBoostReset);
                        mHandler.postDelayed(mLauncherBoostReset, duration);
                    }
                } else {
                    restoreLauncherBoost();
                }
            }
        } catch (Exception e) {
        }
    }

    private void restoreLauncherBoost() {
        try {
            final int pid = Process.myPid();
            adjustCpusetCpus(CPU_FG, mData.allCores, 0);
            Process.setThreadScheduler(pid, Process.SCHED_OTHER, 0);
            Process.setThreadPriority(pid, Process.THREAD_PRIORITY_FOREGROUND);
        } catch (Exception e) {
        }
    }

    private synchronized void tryRestoreThreadPriority(int tid) {
        Integer count = mBoostCount.get(tid);
        if (count == null) return;

        if (count > 1) {
            mBoostCount.put(tid, count - 1);
        } else {
            try {
                Integer origPrio = mOriginalPriorities.get(tid);
                if (origPrio != null) {
                    Process.setThreadScheduler(tid, SCHED_OTHER, 0);
                    Process.setThreadPriority(tid, origPrio);
                }
            } catch (Exception e) {
            }
            mBoostCount.remove(tid);
            mOriginalPriorities.remove(tid);
        }
    }
}
