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

import static android.os.Process.*;
import static com.android.server.am.AxUtils.THREAD_GROUP_SVP;
import static com.android.server.am.BurstEngineConstants.*;

import android.os.Binder;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.util.Slog;

import com.android.server.AxExtServiceFactory;
import com.android.server.NtServiceInjector;
import com.android.server.ServiceThread;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

public class AxBurstEngine {

    private static final String TAG = "AxBurstEngine";
    private static AxBurstEngine sInstance;

    private final HandlerThread mWorkerThread;
    private final Handler mHandler;

    private final ConcurrentHashMap<Integer, ProcessState> mProcessStates = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, ThreadBoost> mBoostedThreads = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, Integer> mPendingOomAdj = new ConcurrentHashMap<>();
    
    private volatile String mFocusedAppPackage = null;
    private volatile String mPreviousFocusedAppPackage = null;
    private volatile boolean mIsLauncherVisible = false;
    private final Set<String> mPerceptibleApps = ConcurrentHashMap.newKeySet();
    private final Set<String> mProtectedMediaPackages = ConcurrentHashMap.newKeySet();
    
    private static final long FAST_SWITCH_DELAY_MS = 1500;
    private static final int MSG_DEMOTE_PREVIOUS_APP = 1;
    
    private AxBurstEngine() {
        mWorkerThread = new ServiceThread(
            TAG, THREAD_PRIORITY_TOP_APP_BOOST, false);
        mWorkerThread.start();
        mHandler = new Handler(mWorkerThread.getLooper());
    }

    public static synchronized AxBurstEngine get() {
        if (sInstance == null) {
            sInstance = new AxBurstEngine();
        }
        return sInstance;
    }

    public static boolean isSupported() {
        return AxUtils.boolProp("burst_engine_enabled", true)
                && AxUtils.isModernKernel();
    }

    public static void scheduleProcess(int pid, int group, String name) {
        if (!isSupported()) return;
        get().handleProcessScheduling(pid, group, name);
    }

    public static void onProcessDied(int pid) {
        if (!isSupported() || pid <= 0) return;
        get().removeProcess(pid);
    }

    public static void animationBoost(int pid, int renderTid, long duration) {
        if (!isSupported()) return;
        get().handleAnimationBoost(pid, renderTid, duration);
    }

    public static int interceptOomAdj(int pid, int adj) {
        if (!isSupported()) return adj;
        return get().getOptAdj(pid, adj);
    }

    public static void setFocusedApp(String packageName, boolean isLauncherVisible) {
        if (!isSupported()) return;
        get().handleFocusedAppChanged(packageName, isLauncherVisible);
    }

    public static void onTaskRemoved(int taskId, String packageName) {
        if (!isSupported() || packageName == null) return;
        get().handleTaskRemoved(packageName);
    }

    public static void setMediaPlayerActive(String packageName, boolean active) {
        if (!isSupported() || packageName == null) return;
        get().handleMediaPlayerState(packageName, active);
    }

    private int getOptAdj(int pid, int adj) {
        Integer optAdj = mPendingOomAdj.get(pid);
        return optAdj != null ? optAdj : adj;
    }

    private void handleFocusedAppChanged(String packageName, boolean isLauncherVisible) {
        final String prevFocused = mFocusedAppPackage;
        mFocusedAppPackage = packageName;
        mIsLauncherVisible = isLauncherVisible;

        mHandler.removeMessages(MSG_DEMOTE_PREVIOUS_APP);

        if (prevFocused != null && !prevFocused.equals(packageName)) {
            mPreviousFocusedAppPackage = prevFocused;
            
            mHandler.postDelayed(() -> {
                if (mPreviousFocusedAppPackage != null && mPreviousFocusedAppPackage.equals(prevFocused)) {
                    mPerceptibleApps.add(prevFocused);
                    mPreviousFocusedAppPackage = null;
                    AxUtils.logger(TAG + ": marked perceptible (delayed) → " + prevFocused);
                    rescheduleAllProcesses();
                }
            }, FAST_SWITCH_DELAY_MS);
        }

        AxUtils.logger(TAG + ": focused_app → " + packageName 
                + " launcher_visible=" + isLauncherVisible
                + " prev=" + prevFocused);

        mHandler.post(this::rescheduleAllProcesses);
    }

    private void handleTaskRemoved(String packageName) {
        if (mPerceptibleApps.remove(packageName)) {
            AxUtils.logger(TAG + ": cleared perceptible → " + packageName);
        }
    }

    private void handleMediaPlayerState(String packageName, boolean active) {
        if (active) {
            mProtectedMediaPackages.add(packageName);
            AxUtils.logger(TAG + ": media_protected → " + packageName);
        } else {
            mProtectedMediaPackages.remove(packageName);
            AxUtils.logger(TAG + ": media_unprotected → " + packageName);
        }
        mHandler.post(this::rescheduleAllProcesses);
    }

    private void rescheduleAllProcesses() {
        for (ProcessState ps : mProcessStates.values()) {
            setSchedulingPolicy(ps);
        }
    }

    private void handleProcessScheduling(int pid, int group, String name) {
        ProcessState ps = getOrCreateProcessState(pid, name, group);
        if (ps.isUiPerfPkg || group == THREAD_GROUP_TOP_APP) {
            setSchedulingPolicy(ps);
        } else {
            mHandler.post(() -> setSchedulingPolicy(ps));
        }
    }

    private ProcessState getOrCreateProcessState(int pid, String name, int targetGroup) {
        return mProcessStates.compute(pid, (k, ps) -> {
            if (ps == null) {
                return new ProcessState(pid, name, targetGroup);
            } else {
                ps.updateFromRecord(targetGroup);
                return ps;
            }
        });
    }

    private void setSchedulingPolicy(ProcessState ps) {
        final boolean isPerfProcess = ps.isUiPerfPkg;
        final boolean isPerfBlack = ps.isBlacklisted;
        final int pid = ps.pid;
        final String packageName = ps.packageName;
        
        if (!AxUtils.checkTid(pid)) {
            mProcessStates.remove(pid);
            return;
        }

        if (ps.adj != null && ps.isPerceptible) {
            mPendingOomAdj.put(pid, ps.adj);
        }

        final boolean isFocusedApp = packageName != null && packageName.equals(mFocusedAppPackage);
        final boolean isProtectedMedia = packageName != null && mProtectedMediaPackages.contains(packageName);
        final boolean isTargetingTop = ps.group == THREAD_GROUP_TOP_APP;
        final boolean isBg = ps.group == THREAD_GROUP_BACKGROUND || ps.group == THREAD_GROUP_RESTRICTED;

        int targetGroup = ps.group;
        int affinity = AFFINITY_LITTLE;

        if (isFocusedApp) {
            targetGroup = THREAD_GROUP_TOP_APP;
            affinity = AFFINITY_ALL;
        } else if (isProtectedMedia && isTargetingTop) {
            targetGroup = THREAD_GROUP_TOP_APP;
            affinity = AFFINITY_ALL;
        } else if (isProtectedMedia) {
            targetGroup = THREAD_GROUP_DEFAULT;
            affinity = AFFINITY_BALANCED;
        } else if (isTargetingTop && !isFocusedApp) {
            if (mIsLauncherVisible) {
                targetGroup = THREAD_GROUP_DEFAULT;
            } else {
                targetGroup = AxUtils.THREAD_GROUP_NT_FOREGROUND;
            }
            affinity = AFFINITY_BALANCED;
        } else if (isPerfBlack) {
            targetGroup = isBg 
                ? THREAD_GROUP_BACKGROUND 
                : AxUtils.THREAD_GROUP_NT_FOREGROUND;
            affinity = AFFINITY_BALANCED;
        } else if (!isBg) {
            affinity = ps.group == THREAD_GROUP_TOP_APP ? AFFINITY_ALL : AFFINITY_BALANCED;
        }

        if (isPerfProcess && !isBg) {
            targetGroup = isTargetingTop 
                ? AxUtils.THREAD_GROUP_SVP 
                : THREAD_GROUP_DEFAULT;
            affinity = isTargetingTop 
                ? AFFINITY_BIG
                : AFFINITY_ALL;
        }

        try {
            Process.setProcessGroup(pid, targetGroup);
            Process.setThreadGroupAndCpuset(pid, targetGroup);
            Process.setThreadAffinity(pid, affinity);
            if (isPerfProcess) {
                final int policy = isTargetingTop ? SCHED_RR | SCHED_RESET_ON_FORK : SCHED_OTHER;
                final int prio = isTargetingTop? 1 : 0;
                Process.setThreadScheduler(pid, policy, prio);
            }
        } catch (Exception e) {
            mProcessStates.remove(pid);
            mPendingOomAdj.remove(pid);
        }
    }

    private void handleAnimationBoost(int pid, int renderTid, long duration) {
        mHandler.post(() -> {
            ProcessState ps = getOrCreateProcessState(pid, "animator", THREAD_GROUP_SVP);
            ps.updateFromRecord(THREAD_GROUP_SVP);
            final int rtid = renderTid > 0 ? renderTid : ps.rtid;
            if (duration >= 0) {
                boostRenderThread(ps, rtid);
                if (ps.isSystemUI) {
                    AxExtServiceFactory.getBoostAdjuster().limitForegroundCpu(true);
                }
                AxUtils.logger(TAG + ": animation_start → pid=" + pid +
                        " rtid=" + rtid + " duration=" + duration + "ms");
            } else {
                if (ps.isSystemUI) {
                    AxExtServiceFactory.getBoostAdjuster().limitForegroundCpu(false);
                }
                AxUtils.logger(TAG + ": animation_end");
                resetBoostedThreads(ps.pid);
            }
        });
    }

    private void boostRenderThread(ProcessState ps, int rtid) {
        if (rtid > 0) {
            mBoostedThreads.computeIfAbsent(rtid, 
                tid -> new ThreadBoost(tid, ps.pid));
            try {
                setScheduler(rtid, SCHED_RR | SCHED_RESET_ON_FORK, 1);
                Process.setThreadGroupAndCpuset(rtid, THREAD_GROUP_SVP);
                Process.setThreadAffinity(rtid, AFFINITY_BIG);
            } catch (Exception e) {
                mBoostedThreads.remove(rtid);
            }
        } else {
            resetBoostedThreads(ps.pid);
        }
    }

    private void resetBoostedThreads(int pid) {
        mBoostedThreads.entrySet().removeIf(entry -> {
            ThreadBoost boost = entry.getValue();
            if (boost.ownerPid == pid) {
                setScheduler(boost.tid, SCHED_OTHER, THREAD_PRIORITY_DISPLAY);
                Process.setThreadGroupAndCpuset(boost.tid, THREAD_GROUP_DEFAULT);
                Process.setThreadAffinity(boost.tid, AFFINITY_BALANCED);
                return true;
            }
            return false;
        });
    }

    private void removeProcess(int pid) {
        Integer optAdj = mPendingOomAdj.remove(pid);
        if (optAdj != null && optAdj == OOM_ADJ_PROTECTED) {
            AxUtils.logger(TAG + ": CLEANUP → pid=" + pid + " [OOM protection removed]");
        }
        resetBoostedThreads(pid);
        mProcessStates.remove(pid);
        AxUtils.logger(TAG + ": CLEANUP → pid=" + pid);
    }

    private void setScheduler(int tid, int policy, int priority) {
        if (!AxUtils.checkTid(tid)) {
            return;
        }
        try {
            Process.setThreadScheduler(tid, policy, priority);
        } catch (Exception e) {
        }
    }
}
