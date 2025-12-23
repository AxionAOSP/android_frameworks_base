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

    private int getOptAdj(int pid, int adj) {
        Integer optAdj = mPendingOomAdj.get(pid);
        return optAdj != null ? optAdj : adj;
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
        final boolean isTop = ps.isUiProc;
        final boolean isPerfBlack = ps.isBlacklisted;
        final int pid = ps.pid;
        
        if (!AxUtils.checkTid(pid)) {
            mProcessStates.remove(pid);
            return;
        }

        if (ps.adj != null && ps.isPerceptible) {
            mPendingOomAdj.put(pid, ps.adj);
        }

        try {
            if (isPerfBlack && !isTop) {
                final boolean isBg = ps.group == THREAD_GROUP_BACKGROUND;

                final int lowPrioGroup = isBg 
                        ? THREAD_GROUP_BACKGROUND
                        : AxUtils.THREAD_GROUP_NT_FOREGROUND;

                Process.setProcessGroup(pid, lowPrioGroup);
                Process.setThreadGroupAndCpuset(pid, lowPrioGroup);
                Process.setThreadAffinity(pid, AFFINITY_BALANCED);

                AxUtils.logger(TAG + ": " + "limit "
                        + "blacklist → cgroup=" + lowPrioGroup
                        + " proc=" + ps.name);
                return;
            }

            if (isPerfProcess) {
                final int perfAffinity = isTop ? AFFINITY_BIG : AFFINITY_ALL;
                final int perfGroup = isTop
                        ? AxUtils.THREAD_GROUP_SVP
                        : THREAD_GROUP_DEFAULT;

                final int policy = isTop ? SCHED_RR | SCHED_RESET_ON_FORK : SCHED_OTHER;
                final int prio = isTop ? 1 : 0;
                Process.setProcessGroup(pid, perfGroup);
                Process.setThreadGroupAndCpuset(pid, perfGroup);
                Process.setThreadAffinity(pid, perfAffinity);
                Process.setThreadScheduler(pid, policy, prio);

                AxUtils.logger(TAG + ": " + "perfList → cgroup=" + perfGroup
                        + " proc=" + ps.name);
                return;
            }

            final int affinity = isTop ? AFFINITY_ALL : AFFINITY_BALANCED;

            Process.setProcessGroup(pid, ps.group);
            Process.setThreadGroupAndCpuset(pid, ps.group);
            Process.setThreadAffinity(pid, affinity);
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
