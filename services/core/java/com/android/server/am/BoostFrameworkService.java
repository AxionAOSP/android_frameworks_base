/*
 * Copyright (C) 2025 AxionAOSP Project
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

import android.os.Process;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.util.Log;
import com.android.internal.os.IBoostFramework;

public class BoostFrameworkService extends IBoostFramework.Stub {

    private static final String TAG = "BoostFrameworkService";

    private static final int CPU_AFFINITY_BIG_CORES = 0;
    private static final int CPU_AFFINITY_SMALL_CORES = 1;

    private static final long ANIMATION_BOOST_ON = 0L;
    private static final long ANIMATION_BOOST_OFF = -1L;

    private final boolean useFifoUiScheduling =
            SystemProperties.getBoolean("ro.sys.axion_is_modern_kernel", true);

    @Override
    public void animationBoost(int tid, long boost) throws RemoteException {
        try {
            int originalPriority = Process.getThreadPriority(tid);
            if (boost >= ANIMATION_BOOST_ON) {
                applyBoost(tid);
            } else if (boost == ANIMATION_BOOST_OFF) {
                restoreThreadPriority(tid, originalPriority);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in animationBoost: " + e.getMessage(), e);
        }
    }

    private void applyBoost(int tid) {
        if (useFifoUiScheduling) {
            Process.setThreadScheduler(tid, Process.SCHED_FIFO | Process.SCHED_RESET_ON_FORK, 99);
        } else {
            Process.setThreadPriority(tid, Process.THREAD_PRIORITY_TOP_APP_BOOST);
        }
    }

    private void restoreThreadPriority(int tid, int originalPriority) {
        try {
            if (useFifoUiScheduling) {
                Process.setThreadScheduler(tid, Process.SCHED_OTHER, 0);
            }
            Process.setThreadPriority(tid, originalPriority);
        } catch (Exception e) {
            Log.w(TAG, "Failed to restore thread priority for " + tid + ", setting to default.", e);
            Process.setThreadPriority(tid, Process.THREAD_PRIORITY_DEFAULT);
        }
    }

    @Override
    public void setProcThreadAffinity(int tid, int affinity) throws RemoteException {
        try {
            int threadGroup = (affinity == CPU_AFFINITY_SMALL_CORES)
                    ? Process.THREAD_GROUP_BACKGROUND
                    : Process.THREAD_GROUP_TOP_APP;
            Process.setThreadGroupAndCpuset(tid, threadGroup);
            Process.setThreadAffinity(tid, affinity);
        } catch (Exception e) {
            Log.e(TAG, "Error in setProcThreadAffinity for tid: " + tid, e);
        }
    }
}
