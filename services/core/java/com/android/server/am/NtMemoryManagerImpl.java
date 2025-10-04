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
import com.android.server.NtServiceInjector;
import com.android.server.am.CachedAppOptimizer;
import com.android.server.wm.WindowManagerService;
import com.android.server.utils.SimpleAppRecord;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.*;

public class NtMemoryManagerImpl implements INtMemoryManager {
    private static final String TAG = "NtMemoryManagerImpl";
    
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
    private static final int MSG_COMPUTE_ADJ = 10;

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

    private boolean mEnableOptHighUsed = false;
    private boolean mEnableForkHighUsed = false;
    private boolean mEnableBoostCamera = false;
    private boolean mEnablePreFork = false;
    private boolean mEnableLoadProcessMemory = false;
    private boolean mPreferredAppsSupported = false;
    private boolean mEnableReleaseMemory = false;

    private int mHighUsedAdj = 801;
    private int mOptHighUsedAdj = 801;
    private int mHighUsedRank = 5;
    private long mTotalPssLimit = 1048576;
    private long mDefaultPss = 204800;
    private ArrayList<ProcessRecord> mForkedProcessList = new ArrayList<>();
    private long mPhysicalMemory;

    private boolean mIsBoostingCameraWarm = true;
    private boolean mIsBoostingCameraCold = true;
    private long mBoostCameraDuration = 5000;
    private int mKillProcessCount = 20;
    private int mKillProcessCountWarmStart = 5;
    private long mLastScreenOnTime = 0;
    private long mReleaseMemoryDuration = 3600000;
    private int mKillProcessScreenOnCount = 5;

    private long[] mPssSections = new long[3];
    private int[] mTargetAdjs = new int[3];
    private long mComputeAdjDuration = 86400000;
    private long mComputeTargetAdjDuration = 600000;
    private boolean mIsHighPressureScene = false;
    private int mPreforkMemoryLevel = 0;
    
    private boolean mSystemReady = false;


    public static final class ProcessInfo {
        public int pid;
        public int adj;
        public long rss;
        public String name;
        public float score;

        public ProcessInfo(int pid, int adj, long rss, String name) {
            this.pid = pid;
            this.adj = adj;
            this.rss = rss;
            this.name = name;
        }
    }

    public static final class RssComparator implements Comparator<ProcessInfo> {
        @Override
        public int compare(ProcessInfo p1, ProcessInfo p2) {
            return Long.compare(p2.rss, p1.rss);
        }
    }

    public static final class AdjComparator implements Comparator<ProcessInfo> {
        @Override
        public int compare(ProcessInfo p1, ProcessInfo p2) {
            return Integer.compare(p2.adj, p1.adj);
        }
    }

    public static final class ScoreComparator implements Comparator<ProcessInfo> {
        @Override
        public int compare(ProcessInfo p1, ProcessInfo p2) {
            return Float.compare(p2.score, p1.score);
        }
    }

    class MemoryHandler extends Handler {
        MemoryHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            if (!mSystemReady) return;
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

    public NtMemoryManagerImpl() {
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
        ArrayList highUsedRecords = AxExtServiceFactory.getAppUsageManager().getHighUsedRecords(true);
        if (highUsedRecords.size() == 0) {
            if (DEBUG) {
                Slog.d(TAG, "forkHighUsedApps: sizeOfHighUsed == 0");
            }
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
        if (!mEnableOptHighUsed) return;
        computeTargetAdjustment();
        mHandler.sendMessageDelayed(
            mHandler.obtainMessage(MSG_COMPUTE_ADJ), mComputeTargetAdjDuration);
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

    public void releaseMemory(int minAdj, int killCount, boolean killWithUi, boolean skipCamera) {
        if (killCount == 0) return;
        
        List<String> whiteList = List.of("com.google.android.googlequicksearchbox:search", "com.google.android.gms", "com.android.chrome");

        try {
            ArrayList<ProcessRecord> lruProcesses =
                    (ArrayList<ProcessRecord>) mActivityManagerService.mProcessList.getLruProcessesLOSP().clone();
            ArrayList<ProcessInfo> candidates = new ArrayList<>();

            for (ProcessRecord proc : lruProcesses) {
                if (proc != null && proc.getSetAdj() >= minAdj) {
                    if (!proc.hasActivities() || killWithUi) {
                        if (whiteList == null || !whiteList.contains(proc.processName)) {
                            candidates.add(new ProcessInfo(
                                    proc.getPid(),
                                    proc.getSetAdj(),
                                    proc.mProfile.getLastRss(),
                                    proc.processName));
                        } else if (DEBUG) {
                            Slog.d(TAG, "Skip killing whitelisted process: " + proc.processName);
                        }
                    } else if (DEBUG) {
                        Slog.d(TAG, "Don't kill process with UI: " + proc.processName);
                    }
                }
            }

            float adjWeight = killWithUi ? 1.0f : (10 % 11 / 10.0f);
            float rssWeight = 1.0f - adjWeight;

            if (DEBUG) {
                Slog.d(TAG, "adjWeight=" + adjWeight + ", rssWeight=" + rssWeight);
            }

            if (rssWeight != 0f) {
                Collections.sort(candidates, new RssComparator());
                applyWeight(candidates, rssWeight, 1);
                if (DEBUG) dumpList("After RSS sort", candidates);
            }

            if (adjWeight != 0f) {
                Collections.sort(candidates, new AdjComparator());
                applyWeight(candidates, adjWeight, 0);
                if (DEBUG) dumpList("After Adj sort", candidates);
            }

            Collections.sort(candidates, new ScoreComparator());
            if (DEBUG) dumpList("After Score sort", candidates);

            int killed = 0;
            for (ProcessInfo candidate : candidates) {
                Process.killProcess(candidate.pid);
                killed++;
                if (DEBUG) {
                    Slog.d(TAG, "Killed proc " + candidate.name +
                            " [pid=" + candidate.pid + "] adj=" + candidate.adj +
                            " rss=" + candidate.rss + " score=" + candidate.score +
                            " killed: " + killed);
                }
                if (killed >= killCount) {
                    if (DEBUG) {
                        Slog.d(TAG, "Stop killing processes, killCount=" + killCount);
                    }
                    return;
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void applyWeight(List<ProcessInfo> list, float weight, int mode) {
        float offset = 0f;
        for (int i = 0; i < list.size(); i++) {
            if (i != 0) {
                long prevVal = (mode == 0) ? list.get(i - 1).adj : list.get(i - 1).rss;
                long currVal = (mode == 0) ? list.get(i).adj : list.get(i).rss;

                if (currVal != prevVal) {
                    offset = i * weight;
                }
                list.get(i).score += offset;
            }
        }
    }

    private void dumpList(String tag, List<ProcessInfo> list) {
        for (ProcessInfo p : list) {
            Slog.d(TAG, tag + " -> " + p.name + " adj=" + p.adj +
                    " rss=" + p.rss + " score=" + p.score);
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
                proc, CachedAppOptimizer.CompactProfile.POPULATE, 
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
        try {
            mHandler.obtainMessage(MSG_TUNE_EXTRA_FREE).sendToTarget();
            updateCameraDeviceDatauration();
            updateReleaseMemoryConfiguration();
            updateHighUsedOptimizationConfiguration();
            updateUsapPoolCOnfiguration();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void updateCameraDeviceDatauration() {
        if (DEBUG) Slog.d(TAG, "EnableBoostCamera: " + mEnableBoostCamera);
        
        if (mEnableBoostCamera || DEBUG_CAMERA) {
            if (mPhysicalMemory == MEM_12GB) {
                mKillProcessCount = 5;
                mKillProcessCountWarmStart = 5;
            } else if (mPhysicalMemory == MEM_8GB) {
                mKillProcessCount = 15;
                mKillProcessCountWarmStart = 5;
            } else {
                mKillProcessCount = 15;
                mKillProcessCountWarmStart = 5;
            }
            
            if (DEBUG) Slog.d(TAG, "KillProcessCount: " + mKillProcessCount);
            if (DEBUG) Slog.d(TAG, "KillProcessCountWarmStart: " + mKillProcessCountWarmStart);
            if (DEBUG) Slog.d(TAG, "BoostCameraDuration: " + mBoostCameraDuration);
        }
    }

    private void updateReleaseMemoryConfiguration() {
        if (DEBUG) Slog.d(TAG, "EnableReleaseMemory: " + mEnableReleaseMemory);
        if (mEnableReleaseMemory) {
            if (mPhysicalMemory == MEM_12GB) {
                mKillProcessScreenOnCount = 10;
            } else if (mPhysicalMemory == MEM_8GB) {
                mKillProcessScreenOnCount = 20;
            } else {
                mKillProcessScreenOnCount = 20;
            }
            if (DEBUG) Slog.d(TAG, "KillProcessScreenOnCount: " + mKillProcessScreenOnCount);
        }
    }

    private void updateHighUsedOptimizationConfiguration() {
        if (DEBUG) Slog.d(TAG, "EnableOptHighUsed: " + mEnableOptHighUsed);
        if (!mEnableOptHighUsed) return;
        mTargetAdjs[0] = 201;
        mTargetAdjs[1] = 401;
        mTargetAdjs[2] = 801;
        mPssSections[0] = 102400L;
        mPssSections[1] = 204800L;
        mPssSections[2] = 512000L;

        SystemProperties.set("persist.sys.nmm.low_adj", Integer.toString(mTargetAdjs[0]));
        SystemProperties.set("persist.sys.nmm.mid_adj", Integer.toString(mTargetAdjs[1]));
        SystemProperties.set("persist.sys.nmm.high_adj", Integer.toString(mTargetAdjs[2]));

        ProcessList.updateLmkProps();
        mHandler.obtainMessage(MSG_COMPUTE_ADJ).sendToTarget();

        if (DEBUG) {
            Slog.d(TAG, "TargetAdjs: " + mTargetAdjs[0] + ", " + mTargetAdjs[1] + ", " + mTargetAdjs[2]);
            Slog.d(TAG, "PssSections: " + mPssSections[0] + ", " + mPssSections[1] + ", " + mPssSections[2]);
            Slog.d(TAG, "ComputeTargetAdjDuration: " + mComputeTargetAdjDuration);
        }
    }

    public void scheduleForkHighUsedApps() {
        if (!mEnableForkHighUsed) return;
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
        if (!mEnableLoadProcessMemory) return;
        Bundle bundle = new Bundle();
        bundle.putString("packageName", packageName);
        Message msg = mHandler.obtainMessage(MSG_LOAD_PROCESS_MEMORY);
        msg.setData(bundle);
        mHandler.sendMessage(msg);
        
        if (DEBUG && packageName.length() > 0) {
            Slog.d(TAG, "Sending message to load memory: " + packageName);
        }
    }

    public void systemReady() {
        mHandlerThread = new HandlerThread("NtMemoryManagerImpl");
        mHandlerThread.start();
        mHandler = new MemoryHandler(mHandlerThread.getLooper());
        mActivityManagerService = NtServiceInjector.getAm();
        mWindowManagerService = NtServiceInjector.getWm();
        mContext = NtServiceInjector.getCtx();

        mPhysicalMemory = AxUtils.getPhysicalMemory();
        mPreferredAppsSupported = isPreferredAppsSupported();
        mEnableForkHighUsed = mPreferredAppsSupported;
        mEnableOptHighUsed = mPreferredAppsSupported;
        mEnableBoostCamera = mPreferredAppsSupported;
        mEnablePreFork = mPreferredAppsSupported;
        mEnableLoadProcessMemory = mPreferredAppsSupported;
        mEnableReleaseMemory = true;
        mSystemReady = true;

        scheduleForkHighUsedApps();
        updateConfiguration();

        if (DEBUG) {
            Slog.d(TAG, "systemReady:");
            Slog.d(TAG, "  mPreferredAppsSupporte = " + mPreferredAppsSupported);
            Slog.d(TAG, "  mPhysicalMemory = " + mPhysicalMemory);
            Slog.d(TAG, "  mEnableForkHighUsed = " + mEnableForkHighUsed);
            Slog.d(TAG, "  mEnableOptHighUsed = " + mEnableOptHighUsed);
            Slog.d(TAG, "  mEnableBoostCamera = " + mEnableBoostCamera);
            Slog.d(TAG, "  mEnablePreFork = " + mEnablePreFork);
            Slog.d(TAG, "  mEnableLoadProcessMemory = " + mEnableLoadProcessMemory);
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
    
    private void updateUsapPoolCOnfiguration() {
        SystemProperties.set("persist.device_config.runtime_native.usap_pool_enabled", "true");
        SystemProperties.set("persist.device_config.runtime_native.usap_pool_refill_delay_ms", "3000");
        SystemProperties.set("persist.device_config.runtime_native.usap_refill_threshold", "1");
        SystemProperties.set("persist.device_config.runtime_native.usap_pool_size_max", "3");
        SystemProperties.set("persist.device_config.runtime_native.usap_pool_size_min", "1");
    }
}
