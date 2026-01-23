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

    private final ConcurrentHashMap<Integer, ThreadBoost> mBoostedThreads = new ConcurrentHashMap<>();
    
    private AxBurstEngine() {
        mWorkerThread = new HandlerThread(TAG, -2);
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

    public static boolean scheduleProcess(int pid, int group, String name) {
        if (!isSupported()) return false;
        if (name == null || !name.contains("systemui")) return false;
        return get().handleProcessScheduling(pid, group, name);
    }

    public static void onProcessDied(int pid) {
        if (!isSupported() || pid <= 0) return;
        get().removeProcess(pid);
    }

    public static void animationBoost(int pid, int renderTid, long duration) {
        if (!isSupported()) return;
        get().handleAnimationBoost(pid, renderTid, duration);
    }

    private boolean handleProcessScheduling(int pid, int group, String name) {
        try {
            ProcessRecord pr = NtServiceInjector.getAm().getProcessRecordByPid(pid);
            if (pr == null) return false;
            
            final boolean isTop = group == THREAD_GROUP_TOP_APP 
                    || pr.mState.hasTopUi() 
                    || pr.mState.isRunningRemoteAnimation();

            return setSchedulingPolicy(pid, name, isTop);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean setSchedulingPolicy(int pid, String name, boolean isTop) {
        if (!AxUtils.checkTid(pid)) {
            return false;
        }

        final int perfAffinity = isTop ? AFFINITY_BIG : AFFINITY_ALL;
        final int perfGroup = isTop 
                ? THREAD_GROUP_SVP
                : THREAD_GROUP_TOP_APP;
                
        final int policy = isTop ? SCHED_RR | SCHED_RESET_ON_FORK : SCHED_OTHER;
        final int prio = isTop ? 1 : THREAD_PRIORITY_URGENT_DISPLAY;

        Process.setProcessGroup(pid, perfGroup);
        Process.setThreadGroupAndCpuset(pid, perfGroup);
        Process.setThreadAffinity(pid, perfAffinity);
        Process.setThreadScheduler(pid, policy, prio);

        AxUtils.logger(TAG + ": " + "perfList → cgroup=" + perfGroup
                + " proc=" + name);
        return true;
    }

    private void handleAnimationBoost(int pid, int renderTid, long duration) {
        mHandler.post(() -> {
            ProcessRecord pr = NtServiceInjector.getAm().getProcessRecordByPid(pid);
            if (pr == null) return;
            
            final boolean isSystemUI = pr.processName.contains("systemui");
            final int rtid = renderTid > 0 ? renderTid : pr.getRenderThreadTid();

            if (duration >= 0) {
                boostRenderThread(pid, rtid);
                if (isSystemUI) {
                    AxExtServiceFactory.getBoostAdjuster().limitForegroundCpu(true);
                }
                AxUtils.logger(TAG + ": animation_start → pid=" + pid +
                        " rtid=" + rtid + " duration=" + duration + "ms");
            } else {
                if (isSystemUI) {
                    AxExtServiceFactory.getBoostAdjuster().limitForegroundCpu(false);
                }
                AxUtils.logger(TAG + ": animation_end");
                resetBoostedThreads(pid);
            }
        });
    }

    private void boostRenderThread(int pid, int rtid) {
        if (rtid > 0) {
            mBoostedThreads.computeIfAbsent(rtid, 
                tid -> new ThreadBoost(tid, pid));
            setScheduler(rtid, SCHED_RR | SCHED_RESET_ON_FORK, 1);
            Process.setThreadGroupAndCpuset(rtid, THREAD_GROUP_SVP);
            Process.setThreadAffinity(rtid, AFFINITY_BIG);
        } else {
            resetBoostedThreads(pid);
        }
    }

    private void resetBoostedThreads(int pid) {
        mBoostedThreads.entrySet().removeIf(entry -> {
            ThreadBoost boost = entry.getValue();
            if (boost.ownerPid == pid) {
                setScheduler(boost.tid, SCHED_OTHER, THREAD_PRIORITY_URGENT_DISPLAY);
                Process.setThreadGroupAndCpuset(boost.tid, THREAD_GROUP_TOP_APP);
                Process.setThreadAffinity(boost.tid, AFFINITY_ALL);
                return true;
            }
            return false;
        });
    }

    private void removeProcess(int pid) {
        resetBoostedThreads(pid);
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
