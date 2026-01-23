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

import static android.os.Process.*;
import static com.android.server.am.AxUtils.THREAD_GROUP_SVP;
import static com.android.server.am.BurstEngineConstants.*;

import android.os.Process;

import com.android.server.AxExtServiceFactory;
import com.android.server.NtServiceInjector;

import java.util.concurrent.ConcurrentHashMap;

public class AxBurstEngine {

    private static final String TAG = "AxBurstEngine";
    private static AxBurstEngine sInstance;

    private AxBurstEngine() {}

    public static synchronized AxBurstEngine get() {
        if (sInstance == null) {
            sInstance = new AxBurstEngine();
        }
        return sInstance;
    }

    public static boolean isSupported() {
        return AxUtils.boolProp("burst_engine_enabled", true);
    }

    public static boolean scheduleProcess(int pid, int group, String name) {
        if (!isSupported()) return false;
        return get().handleProcessScheduling(pid, group, name);
    }

    private boolean handleProcessScheduling(int pid, int group, String name) {
        try {
            ProcessRecord pr = NtServiceInjector.getAm().getProcessRecordByPid(pid);
            if (pr == null) return false;

            final int rTid = pr.getRenderThreadTid();
            
            final boolean isTop = group == THREAD_GROUP_TOP_APP 
                    || pr.mState.hasTopUi() 
                    || pr.mState.isRunningRemoteAnimation();

            if (!isTop) return false;

            final boolean isPerfUi = AxUtils.isInPerfList(name) && !AxUtils.isCamera(name);

            if (name == null || !isPerfUi) return false;

            return setSchedulingPolicy(pid, rTid, name);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean setSchedulingPolicy(int pid, int rTid, String name) {
        if (!AxUtils.checkTid(pid)) {
            return false;
        }

        boolean result = setSchedule(pid, rTid, THREAD_GROUP_SVP, AFFINITY_BIG);
        if (result) {
            AxUtils.logger(TAG + ": " + "perfList → cgroup=" + THREAD_GROUP_SVP
                    + " proc=" + name);
        }
        return result;
    }

    private boolean setSchedule(int pid , int rTid, int group, int affinity) {
        try {
            int[] targetTids = new int[]{pid, rTid};
            for (int tid : targetTids) {
                if (!AxUtils.checkTid(tid) || tid <= 0) continue;
                Process.setProcessGroup(tid, group);
                Process.setThreadGroupAndCpuset(tid, group);
                Process.setThreadAffinity(tid, affinity);
            }
            return true;
        } catch (Exception e) {
        }
        return false;
    }
}
