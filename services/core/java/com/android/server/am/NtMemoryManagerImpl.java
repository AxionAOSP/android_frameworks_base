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

import com.android.internal.app.procstats.ProcessStats;

import com.android.server.am.CachedAppOptimizer;
import com.android.server.wm.WindowManagerService;
import com.android.server.AxExtServiceFactory;
import com.android.server.NtServiceInjector;
import com.android.server.utils.SimpleAppRecord;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class NtMemoryManagerImpl implements INtMemoryManager {

    private static final int MSG_TUNE_EXTRA_FREE = 0;
    private static final int MSG_KILL_PREFORK = 1;
    private static final int MSG_FORK_HIGH_USED = 2;
    private static final int MSG_START_EMPTY_APP = 3;
    private static final int MSG_BOOST_CAMERA_START_WARM = 4;
    private static final int MSG_BOOST_CAMERA_RESET_WARM = 5;
    private static final int MSG_RELEASE_MEMORY_SCREEN_ON = 6;
    private static final int MSG_LOAD_PROCESS_MEMORY = 7;
    private static final int MSG_CAMERA_MEMORY_RELEASE = 8;
    private static final int MSG_BOOST_CAMERA_COLD_RESET = 9;
    private static final int MSG_CACHE_PERCENT = 10;
    private static final int MSG_OPT_HIGH_USED = 11;

    private static final List<String> mReleaseProcessWhiteList;

    public static final String TAG = "NtMemoryManager";

    public static final boolean DEBUG = SystemProperties.getBoolean("persist.sys.nmm.debug", false);

    private static final boolean DEBUG_CAMERA = SystemProperties.getBoolean("persist.sys.nmm.debug_bcamera", false);

    private static final long FORK_HIGH_USED_DELAY = DEBUG ? 180000L : 10800000L;

    private static final long FORK_HIGH_USED_APPS_DELAY = 30000;

    private static final long START_EMPTY_APP_DELAY = 3000;

    private Context mContext;

    private ActivityManagerService mService;

    private WindowManagerService mWindowService;

    private HandlerThread mHandlerThread;

    private Handler mHandler;

    private boolean mTuneExtraFree = true;

    private boolean mEnableOptHighUsed = true;

    private boolean mOpt3rdAppAdjEnabled = true;

    private boolean mEnableForkHighUsed = true;

    private int mAdjForkHighUsed = 801;

    private int mAdjHighUsed = 801;

    private int mOpt3rdHighAdj = 802;

    private int mForkHighUsedNum = 5;

    private int mTopRankHighUsed = 5;

    private long mTotalPssLimit = 1048576;

    private long mDefaultPss = 204800;

    private ArrayList<String> mOpt3rdFgList = new ArrayList<>();

    private ArrayList<String> mOpt3rdList = new ArrayList<>();

    private ArrayList<ProcessRecord> mForkedProcessList = new ArrayList<>();

    private long mMemorySize = AxUtils.getPhysicalMemory();

    private boolean mEnableBoostCamera = true;

    private boolean mIsBoostingCameraCold = false;

    private boolean mIsBoostingCameraWarm = false;

    private long mBoostCameraDuration = 5000;

    private int mKillProcessCount = 20;

    private int mKillProcessCountWarmStart = 5;

    private boolean mEnableReleaseMemory = true;

    private long mLastScreenOnTime = 0;

    private long mReleaseMemoryDuration = 3600000;

    private long mWeight = 10;

    private int mReleaseMemoryKillCount = 5;

    private boolean mEnableLoadProcessMemory = true;

    private int mCachePercent = 0;

    private int mCameraCachePercent = 0;

    private long[] mPssSections = new long[]{ 102400L, 204800L, 512000L };

    private int[] mTargetAdjs = new int[]{ 201, 401, 801 };

    private long mComputeAdjDuration = 86400000;

    private long mComputeTargetAdjDuration = 600000;

    private boolean mEnablePreFork = true;

    private boolean mIsHighPressureScene = false;

    private int mPreforkMemoryLevel = ProcessStats.ADJ_MEM_FACTOR_CRITICAL;

    private boolean mEnableTuneLmkd = true;

    private ArrayList<String> mWhiteListForCameraStart = new ArrayList<>();
    
    private boolean mSystemReady = false;

    public static final class ProcessComparator implements Comparator<ProcessInfo> {
        @Override
        public int compare(ProcessInfo processInfo, ProcessInfo processInfo2) {
            return Long.valueOf(processInfo.rss).compareTo(Long.valueOf(processInfo2.rss));
        }
    }

    public static final class ProcessInfo {
        public int adj;
        public long rss;
        public int pid;
        public String name;
        public float score = 0.0f;

        public ProcessInfo(int i, int i2, long j, String str) {
            pid = i;
            adj = i2;
            rss = j;
            name = str;
        }
    }

    class MemoryManagerHandler extends Handler {
        MemoryManagerHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message message) {
            int i = 0;
            switch (message.what) {
                case MSG_TUNE_EXTRA_FREE:
                    if (mWindowService != null) {
                        Point displaySize = new Point();
                        mWindowService.getBaseDisplaySize(0, displaySize);
                        int extraFreeFactor = 6; // calculated from n2a with 61279 efk = factor ≈ 61279 / (((1080*2412)*4)/1024) ≈ 6.02
                        int extraFreeKb = (((displaySize.x * displaySize.y) * 4) * extraFreeFactor) / 1024;
                        SystemProperties.set("sys.sysctl.extra_free_kbytes", Integer.toString(extraFreeKb));
                    }
                    if (DEBUG) {
                        Slog.d(TAG, "new extra_free_kbytes: " + SystemProperties.getInt("sys.sysctl.extra_free_kbytes", 0));
                    }
                    return;
                case MSG_KILL_PREFORK:
                    synchronized (mForkedProcessList) {
                        int i2 = message.getData().getInt("pid", -1);
                        ArrayList forkedProcessList = mForkedProcessList;
                        int size = forkedProcessList.size();
                        while (i < size) {
                            Object obj = forkedProcessList.get(i);
                            i++;
                            ProcessRecord processRecord = (ProcessRecord) obj;
                            if (processRecord.mPid == i2) {
                                Slog.d(TAG, "Kill pre fork high used: " + processRecord.processName);
                                mForkedProcessList.remove(processRecord);
                                Process.killProcess(i2);
                            }
                        }
                    }
                    return;
                case MSG_FORK_HIGH_USED:
                    forkHighUsedApps();
                    return;
                case MSG_START_EMPTY_APP:
                    String proc = message.getData().getString("proc", "");
                    if (proc.length() > 0) {
                        ArrayList<String> arrayList = new ArrayList<>();
                        arrayList.add(proc);
                        Bundle bundle = new Bundle();
                        bundle.putStringArrayList("start_empty_apps", arrayList);
                        bundle.putBoolean("fork_high", true);
                        mService.startActivityAsUserEmpty(bundle);
                    }
                    return;
                case MSG_BOOST_CAMERA_START_WARM:
                    SystemProperties.set("persist.sys.nmm.boost.camera", "1");
                    releaseMemory(900, mKillProcessCountWarmStart, true, mWhiteListForCameraStart);
                    return;
                case MSG_BOOST_CAMERA_RESET_WARM:
                    mIsBoostingCameraWarm = false;
                    if (DEBUG) {
                        Slog.d(TAG, "mIsBoostingCameraStart : " + mIsBoostingCameraWarm);
                    }
                    return;
                case MSG_RELEASE_MEMORY_SCREEN_ON:
                    if (DEBUG) {
                        Slog.d(TAG, "Start to kill process to release memory on screen on");
                    }
                    SystemProperties.set("persist.sys.nmm.boost.camera", "1");
                    releaseMemory(900, mReleaseMemoryKillCount, false, mReleaseProcessWhiteList);
                    return;
                case MSG_LOAD_PROCESS_MEMORY:
                    String loadPkg = message.getData().getString("packageName", "");
                    if (loadPkg.length() > 0) {
                        startLoadProcessMemory(loadPkg);
                    }
                    return;
                case MSG_CAMERA_MEMORY_RELEASE:
                    SystemProperties.set("persist.sys.nmm.boost.camera", "1");
                    releaseMemory(900, mKillProcessCount, true, mWhiteListForCameraStart);
                    return;
                case MSG_BOOST_CAMERA_COLD_RESET:
                    mIsBoostingCameraCold = false;
                    if (DEBUG) {
                        Slog.d(TAG, "mIsBoostingCameraColdStart : " + mIsBoostingCameraCold);
                    }
                    return;
                case MSG_CACHE_PERCENT:
                    String tunePkg = message.getData().getString("packageName", "");
                    int cachePercent = SystemProperties.getInt("persist.sys.nmm.cache_percent", 0);
                    if (tunePkg.contains("camera")) {
                        if (cachePercent != 0) {
                            SystemProperties.set("persist.sys.nmm.cache_percent", Integer.toString(mCameraCachePercent));
                            ProcessList.updateLmkProps();
                        }
                        return;
                    }
                    if (cachePercent == mCameraCachePercent) {
                        SystemProperties.set("persist.sys.nmm.cache_percent", Integer.toString(mCachePercent));
                        ProcessList.updateLmkProps();
                    }
                    return;
                case MSG_OPT_HIGH_USED:
                    if (mEnableOptHighUsed) {
                        computeHighUsedAppsAdjs();
                        mHandler.sendMessageDelayed(mHandler.obtainMessage(MSG_OPT_HIGH_USED), mComputeTargetAdjDuration);
                    }
                    return;
                default:
                    return;
            }
        }
    }

    public static final class AdjComparator implements Comparator<ProcessInfo> {
        @Override
        public int compare(ProcessInfo processInfo, ProcessInfo processInfo2) {
            return Integer.valueOf(processInfo.adj).compareTo(Integer.valueOf(processInfo2.adj));
        }
    }

    public static final class ScoreComparator implements Comparator<ProcessInfo> {
        @Override
        public int compare(ProcessInfo processInfo, ProcessInfo processInfo2) {
            return Float.valueOf(processInfo2.score).compareTo(Float.valueOf(processInfo.score));
        }
    }

    static {
        mReleaseProcessWhiteList = List.of("com.google.android.googlequicksearchbox:search", "com.google.android.gms", "com.android.chrome");
    }

    public NtMemoryManagerImpl() {
        Slog.d(TAG, "init NtMemoryManagerImpl");
    }

    private void releaseMemory(int i, int i2, boolean z, List<String> list) {
        if (i2 == 0) {
            return;
        }
        try {
            ArrayList arrayList = (ArrayList) mService.mProcessList.getLruProcessesLOSP().clone();
            ArrayList<ProcessInfo> arrayList2 = new ArrayList<>();
            int size = arrayList.size();
            int i3 = 0;
            int i4 = 0;
            while (i4 < size) {
                Object obj = arrayList.get(i4);
                i4++;
                ProcessRecord processRecord = (ProcessRecord) obj;
                if (processRecord != null && processRecord.getSetAdj() >= i) {
                    if (!processRecord.hasActivities() || z) {
                        if (!list.contains(processRecord.processName)) {
                            arrayList2.add(new ProcessInfo(processRecord.getPid(), processRecord.getSetAdj(), processRecord.mProfile.getLastRss(), processRecord.processName));
                        } else if (DEBUG) {
                            Slog.d(TAG, "skip killing whiteListProcess:" + processRecord.processName);
                        }
                    } else if (DEBUG) {
                        Slog.d(TAG, "Don't kill process has ui: " + processRecord.processName);
                    }
                }
            }
            float f = z ? 1.0f : mWeight / 10.0f;
            float f2 = 1.0f - f;
            if (DEBUG) {
                Slog.d(TAG, "adjWight=" + f + ", rssWight=" + f2);
            }
            if (f2 != 0.0f) {
                Collections.sort(arrayList2, new ProcessComparator());
                applyWeight(arrayList2, f2, 1);
            }
            if (f != 0.0f) {
                Collections.sort(arrayList2, new AdjComparator());
                applyWeight(arrayList2, f, 0);
            }
            Collections.sort(arrayList2, new ScoreComparator());
            int size2 = arrayList2.size();
            int i5 = 0;
            while (i5 < size2) {
                ProcessInfo processInfo = arrayList2.get(i5);
                i5++;
                ProcessInfo processInfo2 = processInfo;
                Process.killProcess(processInfo2.pid);
                i3++;
                Slog.d(TAG, "kill proc " + processInfo2.name + "[" + processInfo2.pid + "]: adj:" + processInfo2.adj + " rss:" + processInfo2.rss + " to release memory, now killed : " + i3 + " proceeses");
                if (i3 >= i2) {
                    Slog.d(TAG, "Stop kill process , KillProcessCount " + i2);
                    return;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void startLoadProcessMemory(String pkg) {
        if (pkg.length() <= 0) {
            Slog.d(TAG, "Invalid packageName to load memory");
            return;
        }
        if (DEBUG) {
            Slog.d(TAG, "Start find package uid of " + pkg);
        }
        ProcessRecord prLocked = getProcessRecordLocked(pkg);
        if (prLocked == null) {
            if (DEBUG) {
                Slog.d(TAG, "Couldn't find package uid of " + pkg);
            }
            return;
        }
        if (DEBUG) {
            Slog.d(TAG, "Start to load process memory of " + pkg);
        }
        synchronized (mService.mProcLock) {
            mService.mOomAdjuster.mCachedAppOptimizer.compactApp(prLocked, CachedAppOptimizer.CompactProfile.POPULATE, CachedAppOptimizer.CompactSource.SHELL, true);
        }
        if (DEBUG) {
            Slog.d(TAG, "Load process memory of " + pkg + " successfully");
        }
    }

    private void loadProcessMemoryInternal(String str) {
        Bundle bundle = new Bundle();
        bundle.putString("packageName", str);
        Message messageObtainMessage = mHandler.obtainMessage(MSG_LOAD_PROCESS_MEMORY);
        messageObtainMessage.setData(bundle);
        mHandler.sendMessage(messageObtainMessage);
        if (!DEBUG || str.length() <= 0) {
            return;
        }
        Slog.d(TAG, "Send msg to load memory: " + str);
    }

    public void scheduleForkHighUsedApps() {
        if (!mSystemReady) return;
        mHandler.sendMessageDelayed(mHandler.obtainMessage(MSG_FORK_HIGH_USED), FORK_HIGH_USED_APPS_DELAY);
    }

    private void computeHighUsedAppsAdjs() {
        int i = 0;
        ArrayList highUsedRecords = AxExtServiceFactory.getAppUsageManager().getHighUsedRecords(false);
        long jCurrentTimeMillis = System.currentTimeMillis();
        int size = highUsedRecords.size();
        while (i < size) {
            Object obj = highUsedRecords.get(i);
            i++;
            computeHighUsedAppAdj((SimpleAppRecord) obj, jCurrentTimeMillis);
        }
    }

    private void loadEnableOptHighUsed() {
        Slog.d(TAG, "EnableOptHighUsed : " + mEnableOptHighUsed);
        if (mEnableOptHighUsed) {
            SystemProperties.set("persist.sys.nmm.low_adj", Integer.toString(mTargetAdjs[0]));
            SystemProperties.set("persist.sys.nmm.mid_adj", Integer.toString(mTargetAdjs[1]));
            SystemProperties.set("persist.sys.nmm.high_adj", Integer.toString(mTargetAdjs[2]));
            ProcessList.updateLmkProps();
        }
    }

    private void loadBoostCamera() {
        Slog.d(TAG, "EnableBoostCamera : " + mEnableBoostCamera);
        if (mEnableBoostCamera || DEBUG_CAMERA) {
            if (mMemorySize == AxUtils.MEM_12GB) {
                mKillProcessCount = 5;
                mKillProcessCountWarmStart = 5;
            } else if (mMemorySize == AxUtils.MEM_8GB) {
                mKillProcessCount = 15;
                mKillProcessCountWarmStart = 5;
            } else {
                mKillProcessCount = 15;
                mKillProcessCountWarmStart = 5;
            }
            Slog.d(TAG, "KillProcessCount : " + mKillProcessCount);
            Slog.d(TAG, "KillProcessCountWarmStart : " + mKillProcessCountWarmStart);
            Slog.d(TAG, "CameraCachePercent : " + mCameraCachePercent);
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

    private void initThread() {
        HandlerThread handlerThread = new HandlerThread("NothingMemoryManager");
        mHandlerThread = handlerThread;
        handlerThread.start();
        mHandler = new MemoryManagerHandler(mHandlerThread.getLooper());
    }

    private void forkHighUsedApps() {
        long j;
        int i = 0;
        if (mEnableForkHighUsed) {
            ArrayList highUsedRecords = AxExtServiceFactory.getAppUsageManager().getHighUsedRecords(true);
            if (highUsedRecords.size() == 0) {
                if (DEBUG) {
                    Slog.d(TAG, "sizeOfHighUsed == 0");
                    return;
                }
                return;
            }
            long j2 = mMemorySize;
            if (j2 != -1) {
                mTotalPssLimit = (long) (mTotalPssLimit * (j2 / 8388608.0d));
                if (DEBUG) {
                    Slog.d(TAG, "TotalPssLimit: " + mTotalPssLimit);
                }
            }
            int size = highUsedRecords.size();
            int i2 = 0;
            while (true) {
                j = 0;
                if (i2 >= size) {
                    break;
                }
                Object obj = highUsedRecords.get(i2);
                i2++;
                SimpleAppRecord simpleAppRecord = (SimpleAppRecord) obj;
                if (simpleAppRecord.mLastCachedPss == 0) {
                    simpleAppRecord.mLastCachedPss = mDefaultPss;
                }
            }
            ArrayList arrayList = new ArrayList();
            if (DEBUG) {
                Slog.d(TAG, "start to fork high used apps after booting");
                int size2 = highUsedRecords.size();
                int i3 = 0;
                while (i3 < size2) {
                    Object obj2 = highUsedRecords.get(i3);
                    i3++;
                    Slog.d(TAG, "high used candidate: " + ((SimpleAppRecord) obj2).mPackageName);
                }
            }
            int size3 = highUsedRecords.size();
            int i4 = 0;
            long j3 = 0;
            while (true) {
                if (i4 >= size3) {
                    break;
                }
                Object obj3 = highUsedRecords.get(i4);
                i4++;
                SimpleAppRecord simpleAppRecord2 = (SimpleAppRecord) obj3;
                j3 += simpleAppRecord2.mLastCachedPss;
                if (DEBUG) {
                    Slog.d(TAG, simpleAppRecord2.mPackageName + " : LastCachedPss: " + simpleAppRecord2.mLastCachedPss);
                }
                if (j3 < mTotalPssLimit) {
                    arrayList.add(simpleAppRecord2.mPackageName);
                } else if (DEBUG) {
                    Slog.d(TAG, "TotalPssLimit is reached now: " + j3);
                }
            }
            Slog.d(TAG, "Fork list " + arrayList);
            int size4 = arrayList.size();
            while (i < size4) {
                Object obj4 = arrayList.get(i);
                i++;
                Bundle bundle = new Bundle();
                bundle.putString("proc", (String) obj4);
                Message messageObtainMessage = mHandler.obtainMessage(MSG_START_EMPTY_APP);
                messageObtainMessage.setData(bundle);
                Handler handler = mHandler;
                j += START_EMPTY_APP_DELAY;
                handler.sendMessageDelayed(messageObtainMessage, j);
            }
        }
    }

    private void computeHighUsedAppAdj(SimpleAppRecord sar, long j) {
        int i;
        int i2 = mTargetAdjs[2];
        long j2 = sar.mLastRemoveTaskTime;
        if (j2 != 0 && j - j2 < mComputeAdjDuration) {
            Slog.d(TAG, "don't raise adj due to remove task : " + sar.mPackageName + " : -1");
            AxExtServiceFactory.getAppUsageManager().setTargetAdj(sar.mPackageName, -1);
            return;
        }
        if (j - sar.mLastLmkdTimeTime < mComputeTargetAdjDuration) {
            Slog.d(TAG, "sar.mCurTargetAdj " + sar.mCurTargetAdj);
            int i3 = sar.mCurTargetAdj;
            int[] iArr = mTargetAdjs;
            if (i3 == iArr[2]) {
                i2 = iArr[1];
                Slog.d(TAG, "raise adj due to mid lmkd kill : " + sar.mPackageName + " : " + i2);
            } else if (i3 == iArr[1]) {
                i2 = iArr[0];
                Slog.d(TAG, "raise adj due to mid lmkd kill : " + sar.mPackageName + " : " + i2);
            }
        }
        Slog.d(TAG, sar.mPackageName + " last pss: " + sar.mLastCachedPss);
        long j3 = sar.mLastCachedPss;
        long[] jArr = mPssSections;
        if (j3 > jArr[2]) {
            i2 = mTargetAdjs[2];
            if (DEBUG) {
                Slog.d(TAG, "lower adj due to pss is over : " + mPssSections[2] + " , " + sar.mPackageName + " : " + i2);
            }
        } else if (j3 > jArr[1] && i2 < (i = mTargetAdjs[1])) {
            if (DEBUG) {
                Slog.d(TAG, "lower adj due to pss is over : " + mPssSections[1] + " , " + sar.mPackageName + " : " + i);
            }
            i2 = i;
        }
        AxExtServiceFactory.getAppUsageManager().setTargetAdj(sar.mPackageName, i2);
    }

    private ProcessRecord getProcessRecordLocked(String str) {
        ProcessRecord processRecordLocked;
        synchronized (mService.mProcLock) {
            int currentUserId = mService.getCurrentUserId();
            if (DEBUG) {
                Slog.d(TAG, "Current userId = " + Integer.toString(currentUserId));
            }
            int packageUid = mService.getPackageManagerInternal().getPackageUid(str, 0L, currentUserId);
            if (DEBUG) {
                Slog.d(TAG, "Current packageUid = " + Integer.toString(packageUid));
            }
            processRecordLocked = mService.getProcessRecordLocked(str, packageUid);
        }
        return processRecordLocked;
    }

    private void loadReleaseMemoryConfig() {
        Slog.d(TAG, "EnableReleaseMemory : " + mEnableReleaseMemory);
        if (mEnableReleaseMemory) {
            if (mMemorySize == AxUtils.MEM_12GB) {
                mReleaseMemoryKillCount = 10;
            } else if (mMemorySize == AxUtils.MEM_8GB) {
                mReleaseMemoryKillCount = 20;
            } else {
                mReleaseMemoryKillCount = 20;
            }
            Slog.d(TAG, "KillProcessScreenOnCount : " + mReleaseMemoryKillCount);
        }
    }

    public boolean isOpt3rdAppAdjEnabled() {
        return mOpt3rdAppAdjEnabled;
    }

    public void scheduleKillForkedProcess(int i) {
        Message messageObtainMessage = mHandler.obtainMessage(MSG_KILL_PREFORK);
        Bundle bundle = new Bundle();
        bundle.putInt("pid", i);
        messageObtainMessage.setData(bundle);
        mHandler.sendMessageDelayed(messageObtainMessage, FORK_HIGH_USED_DELAY);
    }

    public void boostCamera(boolean isColdStart) {
        if (mEnableBoostCamera || DEBUG_CAMERA) {
            if (isColdStart) {
                if (mIsBoostingCameraCold) {
                    if (DEBUG) {
                        Slog.d(TAG, "now is boosting camera cold start, skipped this boost");
                    }
                    return;
                } else {
                    mIsBoostingCameraCold = true;
                    mHandler.sendMessage(mHandler.obtainMessage(MSG_CAMERA_MEMORY_RELEASE));
                    mHandler.sendMessageDelayed(mHandler.obtainMessage(MSG_BOOST_CAMERA_COLD_RESET), mBoostCameraDuration);
                    return;
                }
            }
            if (mIsBoostingCameraWarm) {
                if (DEBUG) {
                    Slog.d(TAG, "now is boosting camera warm/hot start, skipped this boost");
                }
            } else {
                mIsBoostingCameraWarm = true;
                mHandler.sendMessage(mHandler.obtainMessage(MSG_BOOST_CAMERA_START_WARM));
                mHandler.sendMessageDelayed(mHandler.obtainMessage(MSG_BOOST_CAMERA_RESET_WARM), mBoostCameraDuration);
            }
        }
    }

    public void optimize3rdApp(ProcessRecord pr, ProcessRecord proc) {
        if (!mOpt3rdAppAdjEnabled) {
            if (DEBUG) {
                Slog.d(TAG, "mEnableOpt3rd is false");
                return;
            }
            return;
        }
        if (proc == null || pr.info.packageName.equals(proc.info.packageName)) {
            return;
        }
        if (pr.mServices.hasForegroundServices() && mOpt3rdFgList.contains(pr.processName)) {
            pr.mState.setAdjType("started-services");
            pr.mState.setCurAdj(mOpt3rdHighAdj);
            pr.mState.setCurrentSchedulingGroup(0);
            pr.mState.setServiceHighRam(true);
            pr.mState.setServiceB(true);
            pr.mState.setCurProcState(10);
            pr.mState.setCurRawProcState(10);
            if (DEBUG) {
                Slog.d(TAG, pr.processName + " is set to adj " + pr.mState.getCurAdj() + " due to in fgs opt list");
                return;
            }
            return;
        }
        if (!mOpt3rdList.contains(pr.processName) || pr.mState.getCurAdj() >= mOpt3rdHighAdj) {
            return;
        }
        if (!pr.mState.hasShownUi()) {
            pr.mState.setCurAdj(mOpt3rdHighAdj);
        } else if (!pr.getWindowProcessController().isPreviousProcess() || pr.mState.getCurAdj() >= 700) {
            pr.mState.setCurAdj(900);
        } else {
            pr.mState.setCurAdj(700);
        }
        if (DEBUG) {
            Slog.d(TAG, pr.processName + " is set to adj " + pr.mState.getCurAdj() + " due to in persist opt list");
        }
    }

    public int[] getOptiAdjs() {
        return mTargetAdjs;
    }

    public int getTargetAdj(ProcessRecord pr) {
        SimpleAppRecord sar = AxExtServiceFactory.getAppUsageManager().geedHighUsedRecord(false, pr.processName);
        if (sar != null) {
            return sar.mCurTargetAdj;
        }
        return -1;
    }

    public boolean isEnableOptHighUsed() {
        return mEnableOptHighUsed;
    }

    public boolean isEnablePreFork(int memLevel) {
        if (!mEnablePreFork || mIsHighPressureScene) {
            Slog.d(TAG, "Disable prefork, EnablePreFork: " + mEnablePreFork + ", IsHighPressureScene: " + mIsHighPressureScene);
            return false;
        }
        if (memLevel <= mPreforkMemoryLevel) {
            return true;
        }
        Slog.d(TAG, "Forbid prefork, current mem level: " + memLevel + ", criterial: " + mPreforkMemoryLevel);
        return false;
    }

    public void loadProcessMemory(String pkg) {
        if (mEnableLoadProcessMemory && mSystemReady) {
            loadProcessMemoryInternal(pkg);
        }
    }

    public void addForkedFromHighUsed(ProcessRecord pr) {
        synchronized (mForkedProcessList) {
            mForkedProcessList.add(pr);
            if (DEBUG) {
                Slog.d(TAG, "add new fork high used: " + pr.processName);
            }
        }
        scheduleKillForkedProcess(pr.mPid);
    }

    public boolean isForkedFromHighUsed(ProcessRecord pr) {
        if (!mEnableForkHighUsed || !AxExtServiceFactory.getAppUsageManager().isHighUsedPackages(pr.processName) || pr.isForkedFromHighUsed) {
            if (DEBUG) {
                if (!mEnableForkHighUsed) {
                    Slog.d(TAG, "EnableForkHighUsed is false");
                }
                if (pr.isForkedFromHighUsed) {
                    Slog.d(TAG, pr.processName + " isForkedFromHighUsed");
                } else {
                    Slog.d(TAG, pr.processName + " is not high used");
                }
            }
            return false;
        }
        synchronized (mForkedProcessList) {
            if (mForkedProcessList.size() > mForkHighUsedNum) {
                if (DEBUG) {
                    Slog.d(TAG, "mMaxForkNumber is reached: " + mForkHighUsedNum);
                }
                return false;
            }
            ArrayList<ProcessRecord> arrayList = mForkedProcessList;
            int size = arrayList.size();
            int i = 0;
            while (i < size) {
                ProcessRecord pr2 = arrayList.get(i);
                i++;
                if (pr2.processName.equals(pr.processName)) {
                    if (DEBUG) {
                        Slog.d(TAG, "already is fork process list: " + pr.processName);
                    }
                    return false;
                }
            }
            return true;
        }
    }

    public void releaseMemoryAtScreenOn() {
        if (mEnableReleaseMemory && mSystemReady) {
            long jCurrentTimeMillis = System.currentTimeMillis();
            long j = mLastScreenOnTime;
            if (j == 0 || jCurrentTimeMillis - j > mReleaseMemoryDuration) {
                mHandler.sendMessage(mHandler.obtainMessage(MSG_RELEASE_MEMORY_SCREEN_ON));
                mLastScreenOnTime = jCurrentTimeMillis;
            }
        }
    }

    public void setForkProcAdj(ProcessRecord pr) {
        if (pr.mState.getCurAdj() < 900) {
            if (pr.mState.getCurAdj() != mAdjForkHighUsed) {
                pr.isForkedFromHighUsed = false;
                if (DEBUG) {
                    Slog.d(TAG, "high used fork is used now: " + pr.processName);
                }
                return;
            }
            return;
        }
        pr.mState.setAdjType("pre_fork");
        pr.mState.setCurAdj(mAdjForkHighUsed);
        if (DEBUG) {
            Slog.d(TAG, "Set fork high used " + pr.processName + " to adj " + pr.mState.getCurAdj());
        }
    }

    public void setHighPressureScene(String pkg) {
        if (pkg != null) {
            if (pkg.contains("camera")) {
                mIsHighPressureScene = true;
            } else if (pkg.contains("launcher")) {
                mIsHighPressureScene = false;
            }
        }
        Slog.d(TAG, "mIsHighPressureScene: " + mIsHighPressureScene + " : " + pkg);
    }

    public void setOptAdj(ProcessRecord pr) {
        pr.mState.setCurAdj(mAdjHighUsed);
        if (DEBUG) {
            Slog.d(TAG, "Set high used " + pr.processName + " to adj " + pr.mState.getCurAdj());
        }
    }

    public void systemReady() {
        mService = NtServiceInjector.getAm();
        mWindowService = NtServiceInjector.getWm();
        mContext = NtServiceInjector.getCtx();
        initThread();
        scheduleForkHighUsedApps();
        loadEnableOptHighUsed();
        loadReleaseMemoryConfig();
        loadBoostCamera();
        mSystemReady = true;
    }

    public void tuneLmkdParam(String str) {
        if (mEnableTuneLmkd && mSystemReady) {
            Bundle bundle = new Bundle();
            bundle.putString("packageName", str);
            Message messageObtainMessage = mHandler.obtainMessage(MSG_CACHE_PERCENT);
            messageObtainMessage.setData(bundle);
            mHandler.sendMessage(messageObtainMessage);
        }
    }

    public boolean isEnableOptHighUsed(ProcessRecord pr) {
        int iIndexOf = AxExtServiceFactory.getAppUsageManager()
            .getHighUsedPackageList(false).indexOf(pr.processName);
        return mEnableOptHighUsed && !pr.processName.equals("com.android.settings") 
                && pr.mState.hasShownUi() 
                && iIndexOf != -1 && iIndexOf < mTopRankHighUsed;
    }
}
