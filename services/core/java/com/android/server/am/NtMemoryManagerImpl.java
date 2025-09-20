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

import android.content.Context;
import android.graphics.Point;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.SystemProperties;
import android.util.Slog;
import com.android.server.AxExtServiceFactory;
import com.android.server.am.CachedAppOptimizer;
import com.android.server.wm.WindowManagerService;
import com.android.server.utils.SimpleAppRecord;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import org.json.*;

public class NtMemoryManagerImpl implements INtMemoryManager {
    private static final String TAG = "NtMemoryManagerImpl";
    
    private static final String KEY_TUNE_EXTRA_FREE = "tune_extra_free";
    private static final String KEY_EXTRA_FREE_FACTOR = "extra_free_factor";
    private static final String KEY_EXTRA_FREE = "extra_free";
    private static final String KEY_OPT_HIGH_USED_ADJ = "opt_high_used_adj";
    private static final String KEY_OPT_FORK_HIGH_USED = "opt_fork_high_used";
    private static final String KEY_OPT_HIGH_USED_RANK = "opt_high_used_rank";
    private static final String KEY_BOOST_CAMERA = "boost_camera";
    private static final String KEY_BOOST_CAMERA_DURATION = "boost_camera_duration";
    private static final String KEY_RELEASE_MEMORY_SCREEN_ON = "release_memory_screen_on";
    private static final String KEY_RELEASE_MEMORY_SCREEN_ON_DURATION = "release_memory_screen_on_duration";
    private static final String KEY_LOAD_PROCESS_MEMORY = "load_process_memory";
    private static final String KEY_LOW_ADJ = "low_adj";
    private static final String KEY_MID_ADJ = "mid_adj";
    private static final String KEY_HIGH_ADJ = "high_adj";
    private static final String KEY_LOW_PSS = "low_pss";
    private static final String KEY_MID_PSS = "mid_pss";
    private static final String KEY_HIGH_PSS = "high_pss";
    private static final String KEY_COMPUTE_ADJ_DURATION = "compute_adj_duration";
    private static final String KEY_ENABLE_PREFORK = "enable_prefork";
    private static final String KEY_PREFORK_MEM_LEVEL = "prefork_mem_level";

    private static final int MSG_TUNE_EXTRA_FREE = 0;
    private static final int MSG_KILL_FORK_HIGH_USED = 1;
    private static final int MSG_FORK_HIGH_USED_APPS = 2;
    private static final int MSG_START_EMPTY_APP = 3;
    private static final int MSG_BOOST_CAMERA_WARM = 4;
    private static final int MSG_STOP_BOOST_CAMERA_WARM = 5;
    private static final int MSG_RELEASE_MEMORY_SCREEN_ON = 6;
    private static final int MSG_LOAD_PROCESS_MEMORY = 7;
    private static final int MSG_BOOST_CAMERA_COLD = 8;
    private static final int MSG_STOP_BOOST_CAMERA_COLD = 9;
    private static final int MSG_COMPUTE_ADJ = 11;

    private static final long MEM_16GB = 16777216;
    private static final long MEM_12GB = 12582912;
    private static final long MEM_10GB = 10485760;
    private static final long MEM_8GB = 8388608;
    private static final long MEM_6GB = 6291456;

    public static final boolean DEBUG = SystemProperties.getBoolean("persist.sys.nmm.debug", false);
    private static final boolean DEBUG_CAMERA = SystemProperties.getBoolean("persist.sys.nmm.debug_bcamera", false);
    private static final long FORK_HIGH_USED_DELAY = DEBUG ? 180000L : 10800000L;
    private static final long FORK_HIGH_USED_APPS_DELAY = 30000;
    private static final long START_EMPTY_APP_DELAY = 3000;

    private Context mContext;
    private ActivityManagerService mActivityManagerService;
    private WindowManagerService mWindowManagerService;
    private HandlerThread mHandlerThread;
    private Handler mHandler;

    private boolean mEnableTuneExtraFree = true;
    private boolean mEnableOptHighUsed = true;
    private boolean mEnableForkHighUsed = true;
    private int mExtraFreeFactor = -1;
    private int mExtraFree = -1;
    private int mHighUsedAdj = 801;
    private int mOptHighUsedAdj = 801;
    private int mHighUsedRank = 5;
    private long mTotalPssLimit = 1048576;
    private long mDefaultPss = 204800;
    private ArrayList<String> mForegroundServiceList = new ArrayList<>();
    private ArrayList<ProcessRecord> mForkedProcessList = new ArrayList<>();
    private long mPhysicalMemory = getPhysicalMemory();

    private boolean mEnableBoostCamera = true;
    private boolean mIsBoostingCameraWarm = true;
    private boolean mIsBoostingCameraCold = true;
    private long mBoostCameraDuration = 5000;
    private int mKillProcessCount = 20;
    private int mKillProcessCountWarmStart = 5;
    private boolean mEnableReleaseMemory = true;
    private long mLastScreenOnTime = 0;
    private long mReleaseMemoryDuration = 3600000;
    private int mKillProcessScreenOnCount = 5;
    private boolean mEnableLoadProcessMemory = true;

    private long[] mPssSections = new long[3];
    private int[] mTargetAdjs = new int[3];
    private long mComputeAdjDuration = 86400000;
    private long mComputeTargetAdjDuration = 600000;
    private boolean mEnablePreFork = true;
    private boolean mIsHighPressureScene = true;
    private int mPreforkMemoryLevel = 0;

    public static final class ProcessInfo {
        public int pid;
        public int adj;
        public String name;

        public ProcessInfo(int pid, int adj, String name) {
            this.pid = pid;
            this.adj = adj;
            this.name = name;
        }
    }

    class MemoryHandler extends Handler {
        MemoryHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_TUNE_EXTRA_FREE:
                    handleTuneExtraFree();
                    break;
                case MSG_KILL_FORK_HIGH_USED:
                    handleKillForkedHighUsed(msg);
                    break;
                case MSG_FORK_HIGH_USED_APPS:
                    forkHighUsedApps();
                    break;
                case MSG_START_EMPTY_APP:
                    handleStartEmptyApp(msg);
                    break;
                case MSG_BOOST_CAMERA_WARM:
                    handleBoostCameraWarm();
                    break;
                case MSG_STOP_BOOST_CAMERA_WARM:
                    handleStopBoostCameraWarm();
                    break;
                case MSG_RELEASE_MEMORY_SCREEN_ON:
                    handleReleaseMemoryScreenOn();
                    break;
                case MSG_LOAD_PROCESS_MEMORY:
                    String packageName = msg.getData().getString("packageName", "");
                    handleLoadProcessMemory(packageName);
                    break;
                case MSG_BOOST_CAMERA_COLD:
                    handleBoostCameraCold();
                    break;
                case MSG_STOP_BOOST_CAMERA_COLD:
                    handleStopBoostCameraCold();
                    break;
                case MSG_COMPUTE_ADJ:
                    handleComputeAdj();
                    break;
            }
        }
    }

    public static final class ProcessComparator implements Comparator<ProcessInfo> {
        @Override
        public int compare(ProcessInfo p1, ProcessInfo p2) {
            return Integer.valueOf(p2.adj).compareTo(Integer.valueOf(p1.adj));
        }
    }

    public NtMemoryManagerImpl() {
        Slog.d(TAG, "Initializing NtMemoryManagerImpl");
    }

    private void initializeHandler() {
        mHandlerThread = new HandlerThread("NtMemoryManagerImpl");
        mHandlerThread.start();
        mHandler = new MemoryHandler(mHandlerThread.getLooper());
    }

    private long getPhysicalMemory() {
        try {
            BufferedReader reader = new BufferedReader(new FileReader("/proc/meminfo"));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("MemTotal:")) {
                    long memTotal = Long.parseLong(
                        line.substring(line.indexOf(":") + 1, line.indexOf("kB")).trim());
                    
                    long physicalMem = MEM_6GB;
                    if (memTotal > MEM_12GB) {
                        physicalMem = MEM_16GB;
                    } else if (memTotal > MEM_10GB) {
                        physicalMem = MEM_12GB;
                    } else if (memTotal > MEM_8GB) {
                        physicalMem = MEM_10GB;
                    } else if (memTotal > MEM_6GB) {
                        physicalMem = MEM_8GB;
                    }
                    
                    if (DEBUG) {
                        Slog.d(TAG, "Total: " + memTotal + ", Physical: " + physicalMem);
                    }
                    return physicalMem;
                }
            }
            reader.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return -1;
    }

    private void handleTuneExtraFree() {
        if (mWindowManagerService != null) {
            Point displaySize = new Point();
            mWindowManagerService.getBaseDisplaySize(0, displaySize);
            int extraFreeFactor = 6; // calculated from n2a with 61279 efk = factor ≈ 61279 / (((1080*2412)*4)/1024) ≈ 6.02
            int extraFreeKb = (((displaySize.x * displaySize.y) * 4) * extraFreeFactor) / 1024;
            SystemProperties.set("sys.sysctl.extra_free_kbytes", Integer.toString(extraFreeKb));
        }
        if (DEBUG) {
            Slog.d(TAG, "New extra_free_kbytes: " + SystemProperties.getInt("sys.sysctl.extra_free_kbytes", 0));
        }
    }

    private void handleKillForkedHighUsed(Message msg) {
        synchronized (mForkedProcessList) {
            int pid = msg.getData().getInt("pid", -1);
            Iterator<ProcessRecord> it = mForkedProcessList.iterator();
            while (it.hasNext()) {
                ProcessRecord proc = it.next();
                if (proc.mPid == pid) {
                    if (DEBUG) Slog.d(TAG, "Killing pre-forked high used: " + proc.processName);
                    mForkedProcessList.remove(proc);
                    Process.killProcess(pid);
                    break;
                }
            }
        }
    }

    private void forkHighUsedApps() {
        long j;
        if (mEnableForkHighUsed) {
            ArrayList highUsedRecords = AxExtServiceFactory.getAppUsageManager().getHighUsedRecords(true);
            if (highUsedRecords.size() == 0) {
                if (DEBUG) {
                    Slog.d(TAG, "forkHighUsedApps: sizeOfHighUsed == 0");
                }
                scheduleForkHighUsedApps();
                return;
            }
            long j2 = this.mPhysicalMemory;
            if (j2 != -1) {
                this.mTotalPssLimit = (long) (this.mTotalPssLimit * (j2 / 8388608.0d));
                if (DEBUG) {
                    Slog.d(TAG, "TotalPssLimit: " + this.mTotalPssLimit);
                }
            }
            Iterator it = highUsedRecords.iterator();
            while (true) {
                j = 0;
                if (!it.hasNext()) {
                    break;
                }
                SimpleAppRecord simpleAppRecord = (SimpleAppRecord) it.next();
                if (simpleAppRecord.mLastCachedPss == 0) {
                    simpleAppRecord.mLastCachedPss = mDefaultPss;
                }
            }
            ArrayList arrayList = new ArrayList();
            if (DEBUG) {
                Slog.d(TAG, "start to fork high used apps after booting");
                Iterator it2 = highUsedRecords.iterator();
                while (it2.hasNext()) {
                    Slog.d(TAG, "high used candidate: " + ((SimpleAppRecord) it2.next()).mPackageName);
                }
            }
            Iterator it3 = highUsedRecords.iterator();
            long j3 = 0;
            while (true) {
                if (!it3.hasNext()) {
                    break;
                }
                SimpleAppRecord simpleAppRecord2 = (SimpleAppRecord) it3.next();
                j3 += simpleAppRecord2.mLastCachedPss;
                boolean z = DEBUG;
                if (z) {
                    Slog.d(TAG, simpleAppRecord2.mPackageName + " : LastCachedPss: " + simpleAppRecord2.mLastCachedPss);
                }
                if (j3 < this.mTotalPssLimit) {
                    arrayList.add(simpleAppRecord2.mPackageName);
                } else if (z) {
                    Slog.d(TAG, "TotalPssLimit is reached now: " + j3);
                }
            }
            if (DEBUG) Slog.d(TAG, "Fork list " + arrayList);
            Iterator it4 = arrayList.iterator();
            while (it4.hasNext()) {
                String str = (String) it4.next();
                Bundle bundle = new Bundle();
                bundle.putString("proc", str);
                Message msg = mHandler.obtainMessage(MSG_START_EMPTY_APP);
                msg.setData(bundle);
                j += START_EMPTY_APP_DELAY;
                mHandler.sendMessageDelayed(msg, j);
            }
        }
    }

    private void handleStartEmptyApp(Message msg) {
        String packageName = msg.getData().getString("proc", "");
        if (packageName.length() > 0) {
            ArrayList<String> apps = new ArrayList<>();
            apps.add(packageName);
            Bundle bundle = new Bundle();
            bundle.putStringArrayList("start_empty_apps", apps);
            bundle.putBoolean("fork_high", true);
            mActivityManagerService.startActivityAsUserEmpty(bundle);
        }
    }

    private void handleBoostCameraWarm() {
        releaseMemory(900, mKillProcessCountWarmStart, true, true);
    }

    private void handleStopBoostCameraWarm() {
        mIsBoostingCameraWarm = false;
        if (DEBUG) {
            Slog.d(TAG, "Stopped boosting camera warm start: " + mIsBoostingCameraWarm);
        }
    }

    private void handleBoostCameraCold() {
        releaseMemory(900, mKillProcessCount, true, true);
    }

    private void handleStopBoostCameraCold() {
        mIsBoostingCameraCold = false;
        if (DEBUG) {
            Slog.d(TAG, "Stopped boosting camera cold start: " + mIsBoostingCameraCold);
        }
    }

    private void handleReleaseMemoryScreenOn() {
        if (DEBUG) {
            Slog.d(TAG, "Starting to kill processes to release memory on screen on");
        }
        SystemProperties.set("persist.sys.nmm.boost.camera", "1");
        releaseMemory(900, mKillProcessScreenOnCount, false, false);
    }

    private void handleComputeAdj() {
        if (mEnableOptHighUsed) {
            computeTargetAdjustment();
            mHandler.sendMessageDelayed(
                mHandler.obtainMessage(MSG_COMPUTE_ADJ), mComputeTargetAdjDuration);
        }
    }

    private void computeTargetAdjustment() {
        ArrayList highUsedRecords = AxExtServiceFactory.getAppUsageManager().getHighUsedRecords(false);
        long jCurrentTimeMillis = System.currentTimeMillis();
        Iterator it = highUsedRecords.iterator();
        while (it.hasNext()) {
            computeTargetAdjForApp((SimpleAppRecord) it.next(), jCurrentTimeMillis);
        }
    }

    private void computeTargetAdjForApp(SimpleAppRecord appRecord, long currentTime) {
        int targetAdj = mTargetAdjs[2];
        
        long lastRemoveTaskTime = appRecord.mLastRemoveTaskTime;
        if (lastRemoveTaskTime != 0 && currentTime - lastRemoveTaskTime < mComputeAdjDuration) {
            if (DEBUG) Slog.d(TAG, "don't raise adj due to remove task : " + appRecord.mPackageName + " : -1");
            AxExtServiceFactory.getAppUsageManager().setTargetAdj(appRecord.mPackageName, -1);
            return;
        }
        
        if (currentTime - appRecord.mLastLmkdTimeTime < mComputeTargetAdjDuration) {
            if (DEBUG) Slog.d(TAG, "sar.mCurTargetAdj " + appRecord.mCurTargetAdj);
            int currentTargetAdj = appRecord.mCurTargetAdj;
            if (currentTargetAdj == mTargetAdjs[2]) {
                targetAdj = mTargetAdjs[1];
                if (DEBUG) Slog.d(TAG, "raise adj due to mid lmkd kill : " + appRecord.mPackageName + " : " + targetAdj);
            } else if (currentTargetAdj == mTargetAdjs[1]) {
                targetAdj = mTargetAdjs[0];
                if (DEBUG) Slog.d(TAG, "raise adj due to mid lmkd kill : " + appRecord.mPackageName + " : " + targetAdj);
            }
        }
        
        long lastCachedPss = appRecord.mLastCachedPss;
        
        if (lastCachedPss > mPssSections[2]) {
            targetAdj = mTargetAdjs[2];
            if (DEBUG) Slog.d(TAG, "lower adj due to pss is over : " + this.mTargetAdjs[2] + " , " + appRecord.mPackageName + " : " + targetAdj);
        } else if (lastCachedPss > mPssSections[1] && targetAdj < mTargetAdjs[1]) {
            targetAdj = mTargetAdjs[1];
            if (DEBUG) Slog.d(TAG, "lower adj due to pss is over : " + this.mTargetAdjs[1] + " , " + appRecord.mPackageName + " : " + targetAdj);
        }
        
        AxExtServiceFactory.getAppUsageManager().setTargetAdj(appRecord.mPackageName, targetAdj);
    }

    public void releaseMemory(int minAdj, int killCount, boolean killWithUi, 
                                          boolean skipCamera) {
        if (killCount == 0) return;

        try {
            @SuppressWarnings("unchecked")
            ArrayList<ProcessRecord> lruProcesses = 
                (ArrayList<ProcessRecord>) mActivityManagerService.mProcessList.getLruProcessesLOSP().clone();
            ArrayList<ProcessInfo> candidates = new ArrayList<>();

            for (ProcessRecord proc : lruProcesses) {
                if (proc != null && proc.getSetAdj() >= minAdj) {
                    if (!proc.hasActivities() || killWithUi) {
                        candidates.add(new ProcessInfo(proc.getPid(), proc.getSetAdj(), proc.processName));
                    } else if (DEBUG) {
                        Slog.d(TAG, "Don't kill process with UI: " + proc.processName);
                    }
                }
            }

            Collections.sort(candidates, new ProcessComparator());

            int killed = 0;
            for (ProcessInfo candidate : candidates) {
                if (!skipCamera || !BoostAdjuster.CAMERA_APPS.equals(candidate.name)) {
                    Process.killProcess(candidate.pid);
                    killed++;
                    
                    if (DEBUG) {
                        Slog.d(TAG, "Killed proc " + candidate.name + 
                               ": adj: " + candidate.adj + 
                               " to release memory, now killed: " + killed + " processes");
                    }
                    
                    if (killed >= killCount) {
                        if (DEBUG) {
                            Slog.d(TAG, "Stop killing processes, killCount: " + killCount);
                        }
                        return;
                    }
                } else if (DEBUG) {
                    Slog.d(TAG, "Skipped killing camera: " + skipCamera + ", " + candidate.name);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private ProcessRecord findProcessRecord(String packageName) {
        synchronized (mActivityManagerService.mProcLock) {
            int userId = mActivityManagerService.getCurrentUserId();
            if (DEBUG) {
                Slog.d(TAG, "findProcessRecord: Current userId = " + userId);
            }
            int packageUid = mActivityManagerService.getPackageManagerInternal()
                .getPackageUid(packageName, 0L, userId);
            if (DEBUG) {
                Slog.d(TAG, "findProcessRecord: Package uid = " + packageUid);
            }
            return mActivityManagerService.getProcessRecordLocked(packageName, packageUid);
        }
    }

    public void handleLoadProcessMemory(String packageName) {
        if (packageName.length() <= 0) {
            if (DEBUG) Slog.d(TAG, "Invalid packageName to load memory");
            return;
        }

        if (DEBUG) {
            Slog.d(TAG, "Starting to find package uid of " + packageName);
        }

        ProcessRecord proc = findProcessRecord(packageName);
        if (proc == null) {
            if (DEBUG) {
                Slog.d(TAG, "Couldn't find package uid of " + packageName);
            }
            return;
        }

        if (DEBUG) {
            Slog.d(TAG, "Starting to load process memory of " + packageName);
        }

        boolean result = false;

        synchronized (mActivityManagerService.mProcLock) {
            result = mActivityManagerService.mOomAdjuster.mCachedAppOptimizer.compactApp(
                proc, CachedAppOptimizer.CompactProfile.FULL, 
                CachedAppOptimizer.CompactSource.SHELL, true);
        }

        if (DEBUG) {
            Slog.d(TAG, "Loaded process memory of " + packageName + " result=" + result);
        }
    }

    private void parseStringList(ArrayList<String> list, String configValue) {
        list.clear();
        if (configValue != null && configValue.length() > 0) {
            for (String item : configValue.split(",")) {
                list.add(item);
            }
        }
        
        if (DEBUG) {
            for (String item : list) {
                Slog.d(TAG, "Parsed list item: " + item);
            }
        }
    }

    private void updateConfiguration() {
        JSONArray config = new JSONArray();
        try {
            JSONObject configObj = new JSONObject();

            configObj.put(KEY_TUNE_EXTRA_FREE, true);
            configObj.put(KEY_EXTRA_FREE_FACTOR, 2);
            configObj.put(KEY_EXTRA_FREE, 512);

            configObj.put(KEY_BOOST_CAMERA, true);
            configObj.put(KEY_RELEASE_MEMORY_SCREEN_ON, true);
            configObj.put(KEY_LOAD_PROCESS_MEMORY, true);
            configObj.put(KEY_OPT_HIGH_USED_ADJ, true);
            configObj.put(KEY_ENABLE_PREFORK, true);

            config.put(configObj);
        } catch (JSONException e) {
        }

        updateConfiguration(config);
    }


    private void updateConfiguration(JSONArray config) {
        if (config == null) return;

        if (DEBUG) {
            Slog.d(TAG, "Configuration update: " + config.toString());
        }

        try {
            JSONObject configObj = config.optJSONObject(0);
            
            boolean oldEnableTuneExtraFree = mEnableTuneExtraFree;
            mEnableTuneExtraFree = configObj.optBoolean(KEY_TUNE_EXTRA_FREE, mEnableTuneExtraFree);
            mExtraFreeFactor = configObj.optInt(KEY_EXTRA_FREE_FACTOR, mExtraFreeFactor);
            mExtraFree = configObj.optInt(KEY_EXTRA_FREE, mExtraFree);
            
            if (mEnableTuneExtraFree || oldEnableTuneExtraFree != mEnableTuneExtraFree) {
                mHandler.obtainMessage(MSG_TUNE_EXTRA_FREE).sendToTarget();
            }

            if (DEBUG) {
                Slog.d(TAG, "EnableTuneExtraFree: " + mEnableTuneExtraFree + 
                           ", ExtraFreeFactor: " + mExtraFreeFactor + 
                           ", ExtraFree: " + mExtraFree);
            }

            updateCameraBoostConfiguration(configObj);
            updateReleaseMemoryConfiguration(configObj);
            updateLoadProcessMemoryConfiguration(configObj);
            updateHighUsedOptimizationConfiguration(configObj);
            updatePreForkConfiguration(configObj);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void updateCameraBoostConfiguration(JSONObject config) {
        mEnableBoostCamera = config.optBoolean(KEY_BOOST_CAMERA, true);
        if (DEBUG) Slog.d(TAG, "EnableBoostCamera: " + mEnableBoostCamera);
        
        if (mEnableBoostCamera || DEBUG_CAMERA) {
            if (mPhysicalMemory == MEM_12GB) {
                mKillProcessCount = config.optInt("boost_camera_kill_count_12G", 5);
                mKillProcessCountWarmStart = config.optInt("boost_camera_warm_count_12G", 5);
            } else if (mPhysicalMemory == MEM_8GB) {
                mKillProcessCount = config.optInt("boost_camera_kill_count_8G", 15);
                mKillProcessCountWarmStart = config.optInt("boost_camera_warm_count_8G", 5);
            } else {
                mKillProcessCount = config.optInt("boost_camera_kill_count_default", 15);
                mKillProcessCountWarmStart = config.optInt("boost_camera_warm_count_default", 5);
            }
            
            if (DEBUG) Slog.d(TAG, "KillProcessCount: " + mKillProcessCount);
            if (DEBUG) Slog.d(TAG, "KillProcessCountWarmStart: " + mKillProcessCountWarmStart);
            
            mBoostCameraDuration = config.optLong(KEY_BOOST_CAMERA_DURATION, 5000L);
            if (DEBUG) Slog.d(TAG, "BoostCameraDuration: " + mBoostCameraDuration);
        }
    }

    private void updateReleaseMemoryConfiguration(JSONObject config) {
        mEnableReleaseMemory = config.optBoolean(KEY_RELEASE_MEMORY_SCREEN_ON, true);
        if (DEBUG) Slog.d(TAG, "EnableReleaseMemory: " + mEnableReleaseMemory);
        
        if (mEnableReleaseMemory) {
            if (mPhysicalMemory == MEM_12GB) {
                mKillProcessScreenOnCount = config.optInt("release_memory_kill_count_12G", 10);
            } else if (mPhysicalMemory == MEM_8GB) {
                mKillProcessScreenOnCount = config.optInt("release_memory_kill_count_8G", 20);
            } else {
                mKillProcessScreenOnCount = config.optInt("release_memory_kill_count_default", 20);
            }
            
            mReleaseMemoryDuration = config.optInt(KEY_RELEASE_MEMORY_SCREEN_ON_DURATION, 3600000);
            if (DEBUG) Slog.d(TAG, "KillProcessScreenOnCount: " + mKillProcessScreenOnCount);
        }
    }

    private void updateLoadProcessMemoryConfiguration(JSONObject config) {
        mEnableLoadProcessMemory = config.optBoolean(KEY_LOAD_PROCESS_MEMORY, true);
        if (DEBUG) Slog.d(TAG, "EnableLoadProcessMemory: " + mEnableLoadProcessMemory);
    }

    private void updateHighUsedOptimizationConfiguration(JSONObject config) {
        mEnableOptHighUsed = config.optBoolean(KEY_OPT_HIGH_USED_ADJ, mEnableOptHighUsed);
        if (DEBUG) Slog.d(TAG, "EnableOptHighUsed: " + mEnableOptHighUsed);
        
        if (mEnableOptHighUsed) {
            mHighUsedRank = config.optInt(KEY_OPT_HIGH_USED_RANK, mHighUsedRank);
            mTargetAdjs[0] = config.optInt(KEY_LOW_ADJ, 201);
            mTargetAdjs[1] = config.optInt(KEY_MID_ADJ, 401);
            mTargetAdjs[2] = config.optInt(KEY_HIGH_ADJ, 801);
            mPssSections[0] = config.optLong(KEY_LOW_PSS, 102400L);
            mPssSections[1] = config.optLong(KEY_MID_PSS, 204800L);
            mPssSections[2] = config.optLong(KEY_HIGH_PSS, 512000L);
            mComputeTargetAdjDuration = config.optLong(KEY_COMPUTE_ADJ_DURATION, mComputeTargetAdjDuration);
            
            ProcessList.updateLmkProps();
            mHandler.obtainMessage(MSG_COMPUTE_ADJ).sendToTarget();
            
            SystemProperties.set("persist.sys.nmm.low_adj", Integer.toString(mTargetAdjs[0]));
            SystemProperties.set("persist.sys.nmm.mid_adj", Integer.toString(mTargetAdjs[1]));
            SystemProperties.set("persist.sys.nmm.high_adj", Integer.toString(mTargetAdjs[2]));
            
            if (DEBUG) {
                Slog.d(TAG, "TargetAdjs: " + mTargetAdjs[0] + ", " + mTargetAdjs[1] + ", " + mTargetAdjs[2]);
                Slog.d(TAG, "PssSections: " + mPssSections[0] + ", " + mPssSections[1] + ", " + mPssSections[2]);
                Slog.d(TAG, "ComputeTargetAdjDuration: " + mComputeTargetAdjDuration);
            }
        }
    }

    private void updatePreForkConfiguration(JSONObject config) {
        mEnablePreFork = config.optBoolean(KEY_ENABLE_PREFORK, true);
        mPreforkMemoryLevel = config.optInt(KEY_PREFORK_MEM_LEVEL, 0);
        if (DEBUG) Slog.d(TAG, "EnablePreFork: " + mEnablePreFork + ", PreforkMemoryLevel: " + mPreforkMemoryLevel);
    }

    public void scheduleForkHighUsedApps() {
        mHandler.removeMessages(MSG_FORK_HIGH_USED_APPS);
        mHandler.sendMessageDelayed(
            mHandler.obtainMessage(MSG_FORK_HIGH_USED_APPS), FORK_HIGH_USED_APPS_DELAY);
    }

    private void scheduleKillForkedProcess(int pid) {
        Message msg = mHandler.obtainMessage(MSG_KILL_FORK_HIGH_USED);
        Bundle bundle = new Bundle();
        bundle.putInt("pid", pid);
        msg.setData(bundle);
        mHandler.sendMessageDelayed(msg, FORK_HIGH_USED_DELAY);
    }

    public void loadProcessMemory(String packageName) {
        Bundle bundle = new Bundle();
        bundle.putString("packageName", packageName);
        Message msg = mHandler.obtainMessage(MSG_LOAD_PROCESS_MEMORY);
        msg.setData(bundle);
        mHandler.sendMessage(msg);
        
        if (DEBUG && packageName.length() > 0) {
            Slog.d(TAG, "Sending message to load memory: " + packageName);
        }
    }

    public void systemReady(ActivityManagerService ams, WindowManagerService wms, Context context) {
        mActivityManagerService = ams;
        mWindowManagerService = wms;
        mContext = context;
        initializeHandler();
        scheduleForkHighUsedApps();
        updateConfiguration();
        if (DEBUG) {
            Slog.d(TAG, "systemReady");
        }
    }

    public void boostCamera(boolean isColdStart) {
        if (mEnableBoostCamera || DEBUG_CAMERA) {
            if (isColdStart) {
                if (mIsBoostingCameraCold) {
                    if (DEBUG) {
                        Slog.d(TAG, "Already boosting camera cold start, skipping");
                    }
                    return;
                }
                mIsBoostingCameraCold = true;
                mHandler.sendMessage(mHandler.obtainMessage(MSG_BOOST_CAMERA_COLD));
                mHandler.sendMessageDelayed(mHandler.obtainMessage(MSG_STOP_BOOST_CAMERA_COLD), 
                    mBoostCameraDuration);
            } else {
                if (mIsBoostingCameraWarm) {
                    if (DEBUG) {
                        Slog.d(TAG, "Already boosting camera warm start, skipping");
                    }
                    return;
                }
                mIsBoostingCameraWarm = true;
                mHandler.sendMessage(mHandler.obtainMessage(MSG_BOOST_CAMERA_WARM));
                mHandler.sendMessageDelayed(mHandler.obtainMessage(MSG_STOP_BOOST_CAMERA_WARM), 
                    mBoostCameraDuration);
            }
        }
    }

    public int[] getOptiAdjs() {
        return mTargetAdjs;
    }

    public int getTargetAdj(ProcessRecord processRecord) {
        SimpleAppRecord simpleAppRecordGeedHighUsedRecord =
             AxExtServiceFactory.getAppUsageManager().geedHighUsedRecord(false, processRecord.processName);
        if (simpleAppRecordGeedHighUsedRecord != null) {
            return simpleAppRecordGeedHighUsedRecord.mCurTargetAdj;
        }
        return -1;
    }

    public boolean isEnableOptHighUsed() {
        return mEnableOptHighUsed;
    }

    public boolean isEnableOptHighUsed(ProcessRecord processRecord) {
        int iIndexOf = AxExtServiceFactory.getAppUsageManager().getHighUsedPackageList(false).indexOf(processRecord.processName);
        return mEnableOptHighUsed 
                && !processRecord.processName.equals("com.android.settings") 
                && processRecord.mState.hasShownUi() 
                && iIndexOf != -1
                && iIndexOf < mHighUsedRank;
    }

    public boolean isEnablePreFork(int memoryLevel) {
        if (!mEnablePreFork || mIsHighPressureScene) {
            if (DEBUG) Slog.d(TAG, "PreFork disabled - EnablePreFork: " + mEnablePreFork + ", HighPressureScene: " + mIsHighPressureScene);
            return false;
        }

        if (memoryLevel <= mPreforkMemoryLevel) {
            return true;
        }

        if (DEBUG) Slog.d(TAG, "PreFork forbidden - current memory level: " + memoryLevel + ", threshold: " + mPreforkMemoryLevel);
        return false;
    }

    public void addForkedHighUsageProcess(ProcessRecord app) {
        synchronized (mForkedProcessList) {
            mForkedProcessList.add(app);
            if (DEBUG) {
                Slog.d(TAG, "Added new forked high usage: " + app.processName);
            }
        }
        scheduleKillForkedProcess(app.mPid);
    }

    public void setForkProcAdj(ProcessRecord app) {
        if (app.mState.getCurAdj() < 900) {
            if (app.mState.getCurAdj() != mHighUsedAdj) {
                app.isForkedFromHighUsed = false;
                if (DEBUG) {
                    Slog.d(TAG, "Forked high usage process now in use: " + app.processName);
                }
            }
            return;
        }

        app.mState.setAdjType("pre_fork");
        app.mState.setCurAdj(mHighUsedAdj);
        if (DEBUG) {
            Slog.d(TAG, "Set forked high usage " + app.processName + 
                   " to adj " + app.mState.getCurAdj());
        }
    }

    public void setOptAdj(ProcessRecord app) {
        app.mState.setCurAdj(mOptHighUsedAdj);
        if (DEBUG) {
            Slog.d(TAG, "Set high usage " + app.processName + 
                   " to adj " + app.mState.getCurAdj());
        }
    }

    public void setHighPressureScene(String packageName) {
        if (packageName != null) {
            if (packageName.toLowerCase().contains("camera")) {
                mIsHighPressureScene = true;
            } else if (packageName.contains("launcher")) {
                mIsHighPressureScene = false;
            }
        }
        if (DEBUG) Slog.d(TAG, "High pressure scene: " + mIsHighPressureScene + " for " + packageName);
    }

    public void releaseMemoryAtScreenOn() {
        if (mEnableReleaseMemory) {
            long currentTime = System.currentTimeMillis();
            if (mLastScreenOnTime == 0 || currentTime - mLastScreenOnTime > mReleaseMemoryDuration) {
                mHandler.sendMessage(mHandler.obtainMessage(MSG_RELEASE_MEMORY_SCREEN_ON));
                mLastScreenOnTime = currentTime;
            }
        }
    }
}
