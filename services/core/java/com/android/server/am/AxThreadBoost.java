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

import android.os.Handler;
import android.os.Process;

import com.android.server.NtServiceInjector;

import java.util.HashMap;

public final class AxThreadBoost {

    private final Handler mHandler;
    private final AxBurstEngine mEngine;
    private final HashMap<Integer, Integer> mBoostCount = new HashMap<>();
    private final HashMap<Integer, Integer> mOrigPrio = new HashMap<>();
    private final Runnable mLauncherReset = this::restoreLauncher;

    public AxThreadBoost(Handler handler, AxBurstEngine engine) {
        mHandler = handler;
        mEngine = engine;
    }

    public void boost(int tid) {
        mHandler.post(() -> Process.setThreadScheduler(tid, SCHED_RR | SCHED_RESET_ON_FORK, 1));
    }

    public void systemBoost(int tid, long duration) {
        if (tid <= 0) return;
        mEngine.boostSfDelegated(duration);
        applyBoost(tid, duration);
        int myPid = Process.myPid();
        if (myPid > 0) {
            ProcessRecord pr = NtServiceInjector.getAm().getProcessRecordByPid(myPid);
            if (pr != null) {
                int renderTid = pr.getRenderThreadTid();
                if (renderTid > 0) applyBoost(renderTid, duration);
            }
        }
    }

    private void applyBoost(int tid, long duration) {
        if (duration <= 0) {
            if (duration == 0) tryBoost(tid);
            else if (duration == -1) tryRestore(tid);
            return;
        }
        if (tryBoost(tid)) {
            mHandler.postDelayed(() -> tryRestore(tid), duration);
        }
    }

    private synchronized boolean tryBoost(int tid) {
        Integer count = mBoostCount.get(tid);
        if (count != null) {
            mBoostCount.put(tid, count + 1);
        } else {
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
            } catch (Exception ignored) {}
            mBoostCount.remove(tid);
            mOrigPrio.remove(tid);
        }
    }

    public void launcherLoadBoost(long duration) {
        try {
            int pid = Process.myPid();
            if (pid <= 0) return;
            if (duration >= 0) {
                ActivityManagerService.scheduleAsRoundRobinPriority(pid, true);
                if (duration > 0) {
                    mHandler.removeCallbacks(mLauncherReset);
                    mHandler.postDelayed(mLauncherReset, duration);
                }
            } else {
                restoreLauncher();
            }
        } catch (Exception ignored) {}
    }

    private void restoreLauncher() {
        try {
            int pid = Process.myPid();
            Process.setThreadScheduler(pid, SCHED_OTHER, 0);
            Process.setThreadPriority(pid, Process.THREAD_PRIORITY_FOREGROUND);
        } catch (Exception ignored) {}
    }
}
