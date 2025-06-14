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

import android.app.ActivityManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.util.Log;
import com.android.internal.os.IBoostFramework;

import java.util.HashMap;

public class BoostFrameworkService extends IBoostFramework.Stub {

    private static final String TAG = "BoostFrameworkService";

    private static final int CPU_AFFINITY_BIG_CORES = 0;
    private static final int CPU_AFFINITY_SMALL_CORES = 1;

    private static final long ANIMATION_BOOST_ON = 0L;
    private static final long ANIMATION_BOOST_OFF = -1L;
    
    private final HashMap<Integer, Integer> mOriginalPriorities = new HashMap<>();

    @Override
    public void animationBoost(int tid, long boost) throws RemoteException {
        try {
            if (boost >= ANIMATION_BOOST_ON) {
                if (!mOriginalPriorities.containsKey(tid)) {
                    int originalPriority = Process.getThreadPriority(tid);
                    mOriginalPriorities.put(tid, originalPriority);
                }
                ActivityManager.getService().animationBoost(tid);
            } else if (boost == ANIMATION_BOOST_OFF) {
                Integer originalPriority = mOriginalPriorities.remove(tid);
                if (originalPriority != null) {
                    restoreThreadPriority(tid, originalPriority);
                } else {
                    Log.w(TAG, "No cached priority found for tid " + tid);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in animationBoost: " + e.getMessage(), e);
        }
    }

    private void restoreThreadPriority(int tid, int originalPriority) {
        try {
            ActivityManager.getService().restoreThreadPriority(tid, originalPriority);
        } catch (Exception e) {
        }
    }

    @Override
    public void setProcThreadAffinity(int tid, int affinity) throws RemoteException {
        try {
            int threadGroup = (affinity == CPU_AFFINITY_BIG_CORES)
                    ? Process.THREAD_GROUP_TOP_APP
                    : Process.THREAD_GROUP_BACKGROUND;
            Process.setThreadGroupAndCpuset(tid, threadGroup);
            Process.setThreadAffinity(tid, affinity);
        } catch (Exception e) {
            Log.e(TAG, "Error in setProcThreadAffinity for tid: " + tid, e);
        }
    }
}
