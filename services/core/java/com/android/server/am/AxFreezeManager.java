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

import static android.os.Process.THREAD_PRIORITY_TOP_APP_BOOST;

import android.app.role.RoleManager;
import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.SystemClock;
import android.os.Trace;
import android.util.ArrayMap;
import android.util.Slog;
import android.util.SparseArray;

import com.android.server.NtServiceInjector;
import com.android.server.am.psc.ProcessRecordInternal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class AxFreezeManager {

    private static final String TAG = "AxFreezeMgr";

    public enum AppPolicy {
        NORMAL(0), AGGRESSIVE(1);
        public final int value;
        AppPolicy(int v) { value = v; }
        public static AppPolicy fromValue(int v) {
            for (AppPolicy p : values()) if (p.value == v) return p;
            return NORMAL;
        }
    }

    private static final int MSG_ANIMATION_FREEZE = 100;

    private static final long REFREEZE_GAP_MS = 1500;
    private static final long DEFAULT_LAUNCH_TIMEOUT = 2000;
    private static final long DEFAULT_DELAY_UNFREEZER_TIMEOUT = 1000;
    private static final int DEFAULT_FREEZE_ADJ_THRESHOLD = ProcessList.FOREGROUND_APP_ADJ + 1;
    private static final int FREEZE_BINDER_TIMEOUT_MS = 10;

    private static final int REPORT_UNFREEZE_SERVICE_MSG = 0;
    private static final int FROZEN_AND_UPDATE_PROCESS_MSG = 1;
    private static final int REPORT_UNFREEZE_PROCESS_MSG = 2;

    public static final int FIRST_LAUNCH_FREEZE = 0;
    public static final int WARM_LAUNCH_FREEZE = 1;
    public static final int COLD_LAUNCH_FREEZE = 2;

    public static final int COMPLETE_LAUNCH_UNFREEZE = 0;
    public static final int INTERRUPT_LAUNCH_UNFREEZE = 1;
    public static final int TIMEOUT_LAUNCH_UNFREEZE = 2;
    public static final int REMOVE_PROCESS_UNFREEZE = 3;
    public static final int CROSS_LAUNCH_UNFREEZE = 4;
    public static final int DEPEND_LAUNCH_UNFREEZE = 5;

    private static final int FREEZE_SUCCESS = 0;
    private static final int PID_NOT_FOUND = -1;
    private static final int BINDER_FREEZE_FAILED = -2;
    private static final int SKIP_FREEZE = -3;
    private static final int FOREGROUND_SERVICE_ACTIVE = -4;

    private final Object mFreezeFlagLock = new Object();
    private final Freezer mFreezer = new Freezer();
    private final Handler mHandler;
    private volatile int mFreezeAdjThreshold = DEFAULT_FREEZE_ADJ_THRESHOLD;
    private volatile long mLaunchTimeout = DEFAULT_LAUNCH_TIMEOUT;
    private volatile long mDelayUnfreezeTimeout = DEFAULT_DELAY_UNFREEZER_TIMEOUT;

    private volatile int mFreezerLevel = 2;
    private volatile Set<String> mFreezePackages = new HashSet<>();
    private volatile AppPolicy mAppPolicy = AppPolicy.NORMAL;

    private final Map<String, Integer> mProcessFreezeRecordLocked = new HashMap<>();

    private final HandlerThread mHandlerThread;
    private volatile boolean mFreezing = false;
    private volatile boolean mIsCpuLoadHigh = false;
    private volatile long mLastUnfreezeAt = 0;
    private int mDurationMs = 600;
    private Context mContext;
    private ProcessList mProcList;

    private final SparseArray<Boolean> mTrackedPids = new SparseArray<>();

    public AxFreezeManager() {
        mHandlerThread = new HandlerThread("AxFreezeThread", -2);
        mHandlerThread.start();
        mHandler = new FreezeHandler(mHandlerThread.getLooper());
        int tid = mHandlerThread.getThreadId();
        if (tid > 0) {
            try {
                Process.setThreadGroupAndCpuset(tid, Process.THREAD_GROUP_BACKGROUND);
            } catch (Exception e) {
                Slog.e(TAG, "AxFreezeThread cpuset pin failed: " + e);
            }
        }
    }

    public void systemReady() {
        mContext = NtServiceInjector.getCtx();
        mProcList = NtServiceInjector.getAm().mProcessList;
    }

    public void setAppPolicy(AppPolicy policy) {
        mAppPolicy = policy;
    }

    public void setFreezerLevel(int level) {
        mFreezerLevel = level;
    }

    public void setFreezePackages(Set<String> packages) {
        mFreezePackages = packages;
    }

    public void setFreezeAdjThreshold(int threshold) {
        mFreezeAdjThreshold = threshold;
    }

    public void setLaunchTimeout(long timeout) {
        mLaunchTimeout = timeout;
    }

    public void setDelayUnfreezeTimeout(long timeout) {
        mDelayUnfreezeTimeout = timeout;
    }

    public void setDurationMs(int durationMs) {
        if (durationMs > 0) mDurationMs = durationMs;
    }

    public void addPidLocked(ProcessRecordInternal app) {
        int pid = app.getPid();
        synchronized (mTrackedPids) {
            mTrackedPids.put(pid, true);
        }
    }

    public void removePidLocked(int pid, ProcessRecordInternal app) {
        synchronized (mTrackedPids) {
            mTrackedPids.remove(pid);
        }
    }

    public void freeze(String packageName) {
        if (packageName == null || mFreezing) {
            return;
        }
        long sinceUnfreeze = SystemClock.uptimeMillis() - mLastUnfreezeAt;
        if (mLastUnfreezeAt > 0 && sinceUnfreeze < REFREEZE_GAP_MS) {
            return;
        }
        mFreezing = true;
        mHandler.sendMessage(mHandler.obtainMessage(MSG_ANIMATION_FREEZE, packageName));
    }

    public boolean useFreezerManager() {
        return true;
    }

    public boolean isPackageExemptFromFreeze(String packageName) {
        return mFreezePackages == null || !mFreezePackages.contains(packageName);
    }

    public void startFreeze(String packageName, int freezeReason) {
        if (mFreezerLevel < 1) return;
        startFreezeInternal(packageName, freezeReason);
    }

    public void startUnfreeze(String packageName, int unfreezeReason) {
        if (mFreezerLevel < 1) return;
        startUnfreezeInternal(packageName, unfreezeReason);
    }

    public void startUnfreezeService(ProcessRecordInternal app, int unfreezeReason) {
        if (mFreezerLevel < 1) return;
        mHandler.sendMessage(mHandler.obtainMessage(
                REPORT_UNFREEZE_SERVICE_MSG, unfreezeReason, 0, (ProcessRecord) app));
    }

    public boolean checkNeedFreezeProcessLocked(ProcessRecordInternal app) {
        int pid = app.getPid();
        synchronized (mPackagesSelfLocked) {
            for (String packageName : mPackagesSelfLocked.mPackageMap.keySet()) {
                SparseArray<ProcessRecord> freezeList = mPackagesSelfLocked.get(packageName);
                if (freezeList.get(pid) == null) continue;
                if (isBoundClient((ProcessRecord) app, packageName, true)) return true;
            }
            return false;
        }
    }

    public boolean checkInFreezeProcessLocked(ProcessRecordInternal app) {
        int pid = app.getPid();
        synchronized (mPackagesSelfLocked) {
            for (String packageName : mPackagesSelfLocked.mPackageMap.keySet()) {
                SparseArray<ProcessRecord> freezeList = mPackagesSelfLocked.get(packageName);
                if (freezeList.get(pid) != null) return true;
            }
            return false;
        }
    }

    final PackageMap mPackagesSelfLocked = new PackageMap();

    static final class PackageMap {
        private final Map<String, SparseArray<ProcessRecord>> mPackageMap = new HashMap<>();

        SparseArray<ProcessRecord> get(String processName) { return mPackageMap.get(processName); }
        boolean contains(String processName) { return mPackageMap.containsKey(processName); }
        int size() { return mPackageMap.size(); }

        ArrayList<String> getAllKeys() {
            return new ArrayList<>(mPackageMap.keySet());
        }

        void put(String processName, SparseArray<ProcessRecord> pidList) {
            mPackageMap.put(processName, pidList);
        }

        boolean remove(String processName) {
            if (mPackageMap.containsKey(processName)) {
                mPackageMap.remove(processName);
                return true;
            }
            return false;
        }

        void clear() { mPackageMap.clear(); }
    }

    int getFreezeRecordLocked(String processName) {
        synchronized (mProcessFreezeRecordLocked) {
            if (mProcessFreezeRecordLocked.containsKey(processName)) {
                return mProcessFreezeRecordLocked.get(processName);
            }
            return -1;
        }
    }

    void addFreezeRecordLocked(String processName, int freezeReason) {
        synchronized (mProcessFreezeRecordLocked) {
            mProcessFreezeRecordLocked.put(processName, freezeReason);
        }
    }

    void removeFreezeRecordLocked(String processName) {
        synchronized (mProcessFreezeRecordLocked) {
            mProcessFreezeRecordLocked.remove(processName);
        }
    }

    boolean packageContainKey(String processName) {
        synchronized (mPackagesSelfLocked) {
            return mPackagesSelfLocked.contains(processName);
        }
    }

    SparseArray<ProcessRecord> getFreezeProcessesLocked(String processName) {
        synchronized (mPackagesSelfLocked) {
            if (mPackagesSelfLocked.contains(processName)) {
                return mPackagesSelfLocked.get(processName);
            }
            return null;
        }
    }

    SparseArray<ProcessRecord> getUnfreezeProcessesLocked(String processName) {
        synchronized (mPackagesSelfLocked) {
            if (mPackagesSelfLocked.contains(processName)) {
                return mPackagesSelfLocked.get(processName);
            }
            return null;
        }
    }

    int getPackageSizeLocked() {
        synchronized (mPackagesSelfLocked) {
            return mPackagesSelfLocked.size();
        }
    }

    void addPackageLocked(String processName, SparseArray<ProcessRecord> pidList) {
        synchronized (mPackagesSelfLocked) {
            mPackagesSelfLocked.put(processName, pidList);
        }
    }

    boolean removePackageLocked(String processName) {
        synchronized (mPackagesSelfLocked) {
            SparseArray<ProcessRecord> freezeList = mPackagesSelfLocked.get(processName);
            if (freezeList != null) freezeList.clear();
            return mPackagesSelfLocked.remove(processName);
        }
    }

    ArrayList<String> getPackageNameListLocked() {
        synchronized (mPackagesSelfLocked) {
            return mPackagesSelfLocked.getAllKeys();
        }
    }

    void clearPackageLocked() {
        synchronized (mPackagesSelfLocked) {
            mPackagesSelfLocked.clear();
        }
    }

    void removeProcessFromListLocked(ProcessRecord app) {
        int pid = app.getPid();
        synchronized (mPackagesSelfLocked) {
            for (String packageName : mPackagesSelfLocked.mPackageMap.keySet()) {
                SparseArray<ProcessRecord> freezeList = mPackagesSelfLocked.get(packageName);
                if (freezeList.get(pid) != null) {
                    freezeList.remove(pid);
                }
            }
        }
    }

    void removeProcessFromListLocked(String processName, List<ProcessRecord> pidsToRemove) {
        synchronized (mPackagesSelfLocked) {
            SparseArray<ProcessRecord> freezeList = mPackagesSelfLocked.get(processName);
            if (freezeList == null) return;
            for (ProcessRecord process : pidsToRemove) {
                freezeList.remove(process.getPid());
            }
        }
    }

    ProcessRecord findProcessByNameLocked(String processName) {
        synchronized (NtServiceInjector.getAm().mPidsSelfLocked) {
            for (int i = 0; i < NtServiceInjector.getAm().mPidsSelfLocked.size(); i++) {
                ProcessRecord foundProcess = NtServiceInjector.getAm().mPidsSelfLocked.valueAt(i);
                if (foundProcess.processName.equals(processName)) return foundProcess;
            }
        }
        return null;
    }

    public SparseArray<ProcessRecord> findPidsByPackageName(String packageName) {
        SparseArray<ProcessRecord> pids = new SparseArray<>();
        synchronized (NtServiceInjector.getAm().mPidsSelfLocked) {
            for (int i = 0; i < NtServiceInjector.getAm().mPidsSelfLocked.size(); i++) {
                final ProcessRecord app = NtServiceInjector.getAm().mPidsSelfLocked.valueAt(i);
                if (app.info.packageName.equals(packageName)) {
                    pids.put(app.getPid(), app);
                }
            }
        }
        return pids;
    }

    SparseArray<ProcessRecord> findNeedFreezeProcessesLocked(String processName) {
        SparseArray<ProcessRecord> needFreezeProcesses = new SparseArray<>();
        synchronized (NtServiceInjector.getAm().mPidsSelfLocked) {
            for (int i = 0; i < NtServiceInjector.getAm().mPidsSelfLocked.size(); i++) {
                final ProcessRecord app = NtServiceInjector.getAm().mPidsSelfLocked.valueAt(i);
                if (app.getCurAdj() >= ProcessList.FOREGROUND_APP_ADJ) {
                    String appPackageName = app.info.packageName;
                    if (processName.equals(appPackageName) || app.info.isSystemApp()) continue;
                    needFreezeProcesses.put(app.getPid(), app);
                }
            }
            return needFreezeProcesses;
        }
    }

    boolean isUsingForegroundService(ProcessRecord app) {
        return app.getCurrentSchedulingGroup() != ProcessList.SCHED_GROUP_BACKGROUND;
    }

    boolean isBoundClient(ProcessRecord app, String processName, boolean equal) {
        final ProcessServiceRecord psr = app.mServices;
        int servicesNum = psr.numberOfRunningServices();
        for (int i = servicesNum - 1; i >= 0; i--) {
            final ServiceRecord sr = psr.getRunningServiceAt(i);
            if (sr == null) continue;
            ArrayMap<IBinder, ArrayList<ConnectionRecord>> conns = sr.getConnections();
            for (int conni = conns.size() - 1; conni >= 0; conni--) {
                ArrayList<ConnectionRecord> c = conns.valueAt(conni);
                for (int con = 0; con < c.size(); con++) {
                    ConnectionRecord cr = c.get(con);
                    if (equal) {
                        if (cr.clientPackageName.equals(processName)) return true;
                    } else {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    boolean isMainProcess(String packageName) {
        return !packageName.contains(":");
    }

    private boolean isSystemApp(String processName) {
        ProcessRecord pr = findProcessByNameLocked(processName);
        if (pr == null) return false;
        return pr.info.isSystemApp();
    }

    void unFreezeProcess(ProcessRecord app) {
        final ProcessCachedOptimizerRecord opt = app.mOptRecord;
        int pid = app.getPid();
        int uid = app.uid;
        String processName = app.processName;
        String logInfo = String.format("app info: uid=%d, pid=%d, adj=%d, frozen=%b, proc name=%s",
                uid, pid, app.getCurAdj(), opt.isFrozen(), processName);
        if (opt.isFrozen() || pid == 0) {
            return;
        }
        try {
            int rc = mFreezer.freezeBinder(pid, false, 2);
            if (rc != 0) {
                Slog.w(TAG, " *unable to unfreeze binder: " + logInfo + " " + rc);
            }
        } catch (RuntimeException e) {
            Slog.w(TAG, " *unable to unfreeze binder for " + pid + ": " + e);
            Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER, "unable to unfreeze binder: " + logInfo);
            Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
        }
        try {
            mFreezer.setProcessFrozen(pid, uid, false);
        } catch (Exception e) {
            Slog.w(TAG, " *unable to unfreeze process: " + logInfo + " " + e);
            Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER, "unable to unfreeze process: " + logInfo);
            Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
        }
    }

    int freezeProcess(ProcessRecord app) {
        final ProcessCachedOptimizerRecord opt = app.mOptRecord;
        final ProcessServiceRecord psr = app.mServices;
        int pid = app.getPid();
        int uid = app.uid;
        int servicesNum = psr.numberOfRunningServices();
        String processName = app.processName;
        String logInfo = String.format(
                "app info: uid=%d, pid=%d, adj=%d, frozen=%b, services=%d, proc name=%s",
                uid, pid, app.getCurAdj(), opt.isFrozen(), servicesNum, processName);
        boolean freezeBinderSuccess = false;
        if (opt.isFrozen() || pid == 0) {
            return pid == 0 ? PID_NOT_FOUND : SKIP_FREEZE;
        }
        if (app.getCurAdj() < mFreezeAdjThreshold) {
            return SKIP_FREEZE;
        }
        final boolean isHighPriorityApp = app.getCurAdj() >= ProcessList.FOREGROUND_APP_ADJ
                && app.getCurAdj() <= ProcessList.PERCEPTIBLE_APP_ADJ;
        if (isHighPriorityApp) {
            if (mAppPolicy != AppPolicy.AGGRESSIVE) {
                boolean isUsingFgService = isUsingForegroundService(app);
                if (isUsingFgService) {
                    return FOREGROUND_SERVICE_ACTIVE;
                }
            }
        }
        try {
            int rc = mFreezer.freezeBinder(pid, true, FREEZE_BINDER_TIMEOUT_MS);
            if (rc != 0) {
                Slog.w(TAG, " *unable to freeze binder for " + pid + ": " + rc);
            } else {
                freezeBinderSuccess = true;
            }
        } catch (RuntimeException e) {
            Slog.w(TAG, "  unable to freeze binder: " + logInfo);
        }
        try {
            if (freezeBinderSuccess) {
                mFreezer.setProcessFrozen(pid, uid, true);
            } else {
                Slog.d(TAG, " *skip freeze process: skip reason: unable to freeze process's binder. " + logInfo);
            }
        } catch (RuntimeException e) {
            Slog.w(TAG, "  unable to freeze process: " + logInfo);
        }
        if (!freezeBinderSuccess) return BINDER_FREEZE_FAILED;
        return FREEZE_SUCCESS;
    }
    
    public void setIsCpuLoadHigh(boolean isCpuLoadHigh) {
        mIsCpuLoadHigh = isCpuLoadHigh;
    }

    private void startFreezeInternal(String packageName, int freezeReason) {
        if (!isMainProcess(packageName) || isSystemApp(packageName)) return;
        if (packageContainKey(packageName)) {
            Slog.d(TAG, "Already triggered freeze for " + packageName);
            return;
        }
        if (!mIsCpuLoadHigh) {
            return;
        }
        startUnfreezeAll();
        SparseArray<ProcessRecord> needFreezeProcesses = findNeedFreezeProcessesLocked(packageName);
        if (needFreezeProcesses.size() == 0) {
            return;
        }
        addFreezeRecordLocked(packageName, freezeReason);
        addPackageLocked(packageName, needFreezeProcesses);
        mHandler.sendMessage(mHandler.obtainMessage(
                FROZEN_AND_UPDATE_PROCESS_MSG, freezeReason, 0, packageName));
        startTimeoutUnfreeze(packageName);
    }

    private void startTimeoutUnfreeze(String packageName) {
        mHandler.sendMessageDelayed(mHandler.obtainMessage(
                REPORT_UNFREEZE_PROCESS_MSG, TIMEOUT_LAUNCH_UNFREEZE, 0, packageName),
                mLaunchTimeout);
    }

    private void removeTimeoutUnfreeze(String packageName) {
        mHandler.removeMessages(REPORT_UNFREEZE_PROCESS_MSG, packageName);
    }

    private void startUnfreezeAll() {
        ArrayList<String> packageNameList = getPackageNameListLocked();
        for (String pkg : packageNameList) {
            startUnfreezeInternal(pkg, CROSS_LAUNCH_UNFREEZE);
        }
    }

    private void startUnfreezeInternal(String packageName, int unfreezeReason) {
        if (!packageContainKey(packageName)) return;
        removeTimeoutUnfreeze(packageName);
        if (unfreezeReason == COMPLETE_LAUNCH_UNFREEZE) {
            int freezeReason = getFreezeRecordLocked(packageName);
            if (freezeReason == WARM_LAUNCH_FREEZE) {
                mHandler.sendMessage(mHandler.obtainMessage(
                        REPORT_UNFREEZE_PROCESS_MSG, unfreezeReason, 0, packageName));
            } else {
                mHandler.sendMessageDelayed(mHandler.obtainMessage(
                        REPORT_UNFREEZE_PROCESS_MSG, unfreezeReason, 0, packageName),
                        mDelayUnfreezeTimeout);
            }
        } else {
            mHandler.sendMessage(mHandler.obtainMessage(
                    REPORT_UNFREEZE_PROCESS_MSG, unfreezeReason, 0, packageName));
        }
    }

    private void setFrozen(int pid, int uid, boolean frozen) {
        try {
            Process.setProcessFrozen(pid, uid, frozen);
        } catch (Exception e) {
            Slog.e(TAG, "AnimationFreeze error: " + e);
        }
    }

    private void animationUnfreeze(ArrayList<ProcessRecord> list) {
        for (int i = 0; i < list.size(); i++) {
            ProcessRecord r = list.get(i);
            setFrozen(r.mPid, r.getUid(), false);
        }
    }

    private void animationFreeze(ArrayList<ProcessRecord> list) {
        for (int i = 0; i < list.size(); i++) {
            ProcessRecord r = list.get(i);
            setFrozen(r.mPid, r.getUid(), true);
        }
    }

    private void backgroundFreeze(String packageName) {
        ArrayList<ProcessRecord> freezeList = new ArrayList<>();
        if (mProcList == null) {
            Slog.e(TAG, "AnimationFreeze: system not ready");
            mFreezing = false;
            return;
        }
        ArrayList<ProcessRecord> lru =
                (ArrayList<ProcessRecord>) mProcList.ntGetLruProcesses().clone();
        try {
            boolean homeContains = packageName.isEmpty() ? false :
                    ((RoleManager) mContext.getSystemService(RoleManager.class))
                    .getRoleHolders("android.app.role.HOME").contains(packageName);
            for (int i = 0; i < lru.size(); i++) {
                ProcessRecord pr = lru.get(i);
                if (pr != null && !pr.getProcessName().equals(packageName)
                        && !pr.getProcessName().contains("webview")
                        && (!homeContains || !pr.getProcessName().equals(
                                "com.google.android.googlequicksearchbox:search"))) {
                    int curAdj = pr.getCurAdj();
                    if (pr.getUid() > 10000 && curAdj >= 250
                            && curAdj != 600 && curAdj != 700 && curAdj < 900) {
                        freezeList.add(pr);
                    }
                }
            }
            if (freezeList.isEmpty()) {
                mFreezing = false;
                return;
            }
            mHandler.post(new FreezeRunnable(freezeList));
            mHandler.postDelayed(new UnfreezeRunnable(freezeList), mDurationMs);
        } catch (Exception e) {
            Slog.e(TAG, "AnimationFreeze: get process failed", e);
            mFreezing = false;
        }
    }

    private final class FreezeRunnable implements Runnable {
        final ArrayList<ProcessRecord> list;
        FreezeRunnable(ArrayList<ProcessRecord> l) { list = l; }
        @Override public void run() { animationFreeze(list); }
    }

    private final class UnfreezeRunnable implements Runnable {
        final ArrayList<ProcessRecord> list;
        UnfreezeRunnable(ArrayList<ProcessRecord> l) { list = l; }
        @Override public void run() {
            animationUnfreeze(list);
            mLastUnfreezeAt = SystemClock.uptimeMillis();
            mFreezing = false;
        }
    }

    private final class FreezeHandler extends Handler {
        FreezeHandler(Looper looper) { super(looper); }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_ANIMATION_FREEZE: {
                    backgroundFreeze(String.valueOf(msg.obj));
                } break;

                case REPORT_UNFREEZE_SERVICE_MSG: {
                    final int unfreezeReason = msg.arg1;
                    final ProcessRecord app = (ProcessRecord) msg.obj;
                    if (!checkInFreezeProcessLocked(app)) {
                        Slog.d(TAG, "skip unfreeze service: skip reason: " + app.processName
                                + " has been removed from freeze list");
                        break;
                    }
                    unFreezeProcess(app);
                    removeProcessFromListLocked(app);
                } break;

                case FROZEN_AND_UPDATE_PROCESS_MSG: {
                    final int freezeReason = msg.arg1;
                    final String packageName = (String) msg.obj;
                    synchronized (mFreezeFlagLock) {
                        final SparseArray<ProcessRecord> needFreezeProcesses =
                                getFreezeProcessesLocked(packageName);
                        if (needFreezeProcesses != null) {
                            List<ProcessRecord> pidsToRemove = new ArrayList<>();
                            for (int i = 0; i < needFreezeProcesses.size(); i++) {
                                ProcessRecord app = needFreezeProcesses.valueAt(i);
                                if (freezeProcess(app) == FREEZE_SUCCESS) {
                                    pidsToRemove.add(app);
                                }
                            }
                            removeProcessFromListLocked(packageName, pidsToRemove);
                        } else {
                            Slog.d(TAG, "freeze object is null for " + packageName);
                        }
                    }
                } break;

                case REPORT_UNFREEZE_PROCESS_MSG: {
                    final int unfreezeReason = msg.arg1;
                    final String packageName = (String) msg.obj;
                    if (!packageContainKey(packageName)) {
                        Slog.e(TAG, "Already triggered unfreeze for " + packageName);
                        break;
                    }
                    synchronized (mFreezeFlagLock) {
                        final SparseArray<ProcessRecord> needUnfreezeProcesses =
                                getUnfreezeProcessesLocked(packageName);
                        if (needUnfreezeProcesses != null) {
                            for (int i = 0; i < needUnfreezeProcesses.size(); i++) {
                                ProcessRecord app = needUnfreezeProcesses.valueAt(i);
                                unFreezeProcess(app);
                            }
                            removePackageLocked(packageName);
                            removeFreezeRecordLocked(packageName);
                        } else {
                            Slog.d(TAG, "unfreeze object is null for " + packageName);
                        }
                    }
                } break;
            }
        }
    }

    private static String getFreezeReason(int freezeReason) {
        switch (freezeReason) {
            case FIRST_LAUNCH_FREEZE: return "First launch";
            case WARM_LAUNCH_FREEZE: return "Warm launch";
            case COLD_LAUNCH_FREEZE: return "Cold launch";
            default: return "Unknown";
        }
    }

    private static String getUnfreezeReason(int unfreezeReason) {
        switch (unfreezeReason) {
            case COMPLETE_LAUNCH_UNFREEZE: return "Complete launch";
            case INTERRUPT_LAUNCH_UNFREEZE: return "Interrupt launch";
            case TIMEOUT_LAUNCH_UNFREEZE: return "Launch timeout";
            case REMOVE_PROCESS_UNFREEZE: return "Remove main process";
            case CROSS_LAUNCH_UNFREEZE: return "Cross launch process";
            case DEPEND_LAUNCH_UNFREEZE: return "Dependent launch";
            default: return "Unknown";
        }
    }
}
