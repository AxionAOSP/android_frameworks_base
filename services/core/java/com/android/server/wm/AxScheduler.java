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
package com.android.server.wm;

import android.os.Process;
import android.os.SystemProperties;

import com.android.server.am.AxUtils;

public class AxScheduler {

    private static final String TAG = "AxScheduler";
    private static AxScheduler sInstance;

    private AxScheduler() {
    }

    public static synchronized AxScheduler get() {
        if (sInstance == null) {
            sInstance = new AxScheduler();
        }
        return sInstance;
    }
    
    public static boolean isSupported() {
        return AxUtils.boolProp("scheduler_supported", true);
    }
    
    public static void scheduleOpt(int pid, int group, String processName) {
        get().scheduleOptInternal(pid, group, processName);
    }
    
    public void scheduleOptInternal(int pid, int group, String processName) {
        try {
            final boolean isPerfProcess = AxUtils.isInPerfList(processName);
            final boolean isTop = group == Process.THREAD_GROUP_TOP_APP;
            final boolean isPerfBlack = AxUtils.isInPerfBlackList(processName);
            final boolean isLowPrio = AxUtils.isInLowPrioList(processName);

            final int affinity = isTop ? 2 : 1;

            if (isPerfBlack || isLowPrio) {

                final boolean isBg = group == Process.THREAD_GROUP_BACKGROUND;

                final int lowPrioGroup = isBg 
                        ? Process.THREAD_GROUP_BACKGROUND 
                        : AxUtils.THREAD_GROUP_NT_FOREGROUND;
                final int newGroup = isTop
                        ? Process.THREAD_GROUP_TOP_APP
                        : lowPrioGroup;

                Process.setProcessGroup(pid, newGroup);
                Process.setThreadAffinity(pid, affinity);

                AxUtils.logger(TAG + ": " + (isTop ? "boost " : "limit ")
                        + "blacklist → cgroup=" + newGroup
                        + " proc=" + processName);

                return;
            }

            if (isPerfProcess) {
                final int perfAffinity = isTop ? 0 : 2;
                final int perfGroup = isTop
                        ? Process.THREAD_GROUP_RESTRICTED
                        : AxUtils.THREAD_GROUP_NT_FOREGROUND;

                Process.setProcessGroup(pid, perfGroup);
                Process.setThreadAffinity(pid, perfAffinity);
                Process.setThreadScheduler(pid, Process.SCHED_FIFO, 1);

                AxUtils.logger(TAG + ": " + "perfList → cgroup=" + perfGroup
                        + " proc=" + processName);

                return;   
            }

            Process.setProcessGroup(pid, group);
            Process.setThreadAffinity(pid, affinity);

        } catch (Exception e) {
            AxUtils.logger(TAG + ": " + "AxScheduler failed scheduling pid=" + pid + " group=" + group + " " + e);
        }
    }
}
