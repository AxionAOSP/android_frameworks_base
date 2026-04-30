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

import static android.os.Process.SCHED_OTHER;
import static android.os.Process.SCHED_RESET_ON_FORK;
import static android.os.Process.SCHED_RR;
import static android.os.Process.SCHED_FIFO;
import static android.os.Process.THREAD_GROUP_SVP;

import android.os.Binder;
import android.os.FileUtils;
import android.os.Process;

import com.android.server.NtServiceInjector;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public final class AxThreadBoost {

    private final ScheduledExecutorService mScheduler;
    private final AxBurstEngine mEngine;
    private final HashMap<Integer, Integer> mBoostCount = new HashMap<>();
    private final HashMap<Integer, Integer> mOrigPrio = new HashMap<>();
    private final HashMap<Integer, ScheduledFuture<?>> mBoostFutures = new HashMap<>();
    private final Runnable mLauncherReset = this::restoreLauncher;

    public AxThreadBoost(AxBurstEngine engine) {
        mScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "AxThreadBoost");
            t.setPriority(5);
            return t;
        });
        mEngine = engine;
    }

    public void boost(int tid) {
        Process.setThreadScheduler(tid, SCHED_FIFO | SCHED_RESET_ON_FORK, 1);
        Process.setThreadGroupAndCpuset(tid, THREAD_GROUP_SVP);
    }

    public void systemBoost(int tid, long duration) {
        if (tid <= 0) return;
        applyBoost(tid, duration);
        int callerPid = Binder.getCallingPid();
        if (callerPid <= 0 || callerPid == Process.myPid()) return;
        int renderTid = -1;
        ProcessRecord pr = NtServiceInjector.getAm().getProcessRecordByPid(callerPid);
        if (pr != null) {
            renderTid = pr.getRenderThreadTid();
        }
        if (renderTid <= 0) {
            renderTid = scanRenderThreadTid(callerPid);
        }
        if (renderTid > 0) applyBoost(renderTid, duration);
    }

    private int scanRenderThreadTid(int pid) {
        int[] tids = Process.getPids("/proc/" + pid + "/task", new int[1024]);
        if (tids == null) return -1;
        for (int tid : tids) {
            if (tid <= 0) break;
            File commFile = new File("/proc/" + pid + "/task/" + tid + "/comm");
            if (!commFile.exists()) continue;
            try {
                String name = FileUtils.readTextFile(commFile, 1024, null);
                if (name != null && "RenderThread".equals(name.trim())) {
                    return tid;
                }
            } catch (IOException ignored) {
            }
        }
        return -1;
    }

    private void applyBoost(int tid, long duration) {
        if (duration <= 0) {
            if (duration == 0) tryBoost(tid);
            else if (duration == -1) tryRestore(tid);
            return;
        }
        if (tryBoost(tid)) {
            ScheduledFuture<?> future = mScheduler.schedule(
                    () -> tryRestore(tid), duration, TimeUnit.MILLISECONDS);
            synchronized (mBoostFutures) {
                ScheduledFuture<?> prev = mBoostFutures.put(tid, future);
                if (prev != null) prev.cancel(false);
            }
        }
    }

    private synchronized boolean tryBoost(int tid) {
        Integer count = mBoostCount.get(tid);
        if (count != null) {
            mBoostCount.put(tid, count + 1);
        } else {
            if (mBoostCount.size() > 512) {
                mBoostCount.clear();
                mOrigPrio.clear();
            }
            try {
                mOrigPrio.put(tid, Process.getThreadPriority(tid));
                ActivityManagerService.scheduleAsRoundRobinPriority(tid, true);
                mBoostCount.put(tid, 1);
            } catch (Exception e) {
                return false;
            }
        }
        return true;
    }

    private synchronized void tryRestore(int tid) {
        Integer count = mBoostCount.get(tid);
        if (count == null) return;
        if (count > 1) {
            mBoostCount.put(tid, count - 1);
        } else {
            try {
                Integer orig = mOrigPrio.get(tid);
                if (orig != null) {
                    Process.setThreadScheduler(tid, SCHED_OTHER, 0);
                    Process.setThreadPriority(tid, orig);
                }
            } catch (Exception ignored) {
            }
            mBoostCount.remove(tid);
            mOrigPrio.remove(tid);
            synchronized (mBoostFutures) {
                ScheduledFuture<?> f = mBoostFutures.remove(tid);
                if (f != null) f.cancel(false);
            }
        }
    }

    public void launcherLoadBoost(long duration) {
        try {
            int pid = Process.myPid();
            if (pid <= 0) return;
            if (duration >= 0) {
                ActivityManagerService.scheduleAsRoundRobinPriority(pid, true);
                if (duration > 0) {
                    mScheduler.schedule(mLauncherReset, duration, TimeUnit.MILLISECONDS);
                }
            } else {
                restoreLauncher();
            }
        } catch (Exception ignored) {
        }
    }

    private void restoreLauncher() {
        try {
            int pid = Process.myPid();
            Process.setThreadScheduler(pid, SCHED_OTHER, 0);
            Process.setThreadPriority(pid, Process.THREAD_PRIORITY_FOREGROUND);
        } catch (Exception ignored) {
        }
    }
}
