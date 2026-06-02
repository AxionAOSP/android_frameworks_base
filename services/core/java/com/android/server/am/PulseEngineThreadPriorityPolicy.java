/*
 * Copyright (C) 2026 AxionOS
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

import android.annotation.Nullable;

import java.io.PrintWriter;
import java.util.concurrent.atomic.AtomicLong;

final class PulseEngineThreadPriorityPolicy {
    private final Object mLock = new Object();
    private final AtomicLong mBackgroundDemotionCheckCount = new AtomicLong();
    private final AtomicLong mBackgroundDemotionCount = new AtomicLong();

    private long mLastBackgroundDemotionUptimeMillis;
    private int mLastBackgroundDemotionUid = -1;
    @Nullable private String mLastBackgroundDemotionProcess;

    boolean shouldDemoteBackgroundProcess(PulseEngineConfig config, PulseEnginePolicy policy,
            PulseEngineAppStaminaPolicy appStaminaPolicy, int uid, @Nullable String processName,
            long now) {
        if (!config.mThreadPriorityEnabled || appStaminaPolicy == null) {
            return false;
        }
        mBackgroundDemotionCheckCount.incrementAndGet();
        final boolean demote = appStaminaPolicy.shouldDemoteBackgroundProcess(config, policy, uid,
                processName, now);
        if (demote) {
            synchronized (mLock) {
                mLastBackgroundDemotionUptimeMillis = now;
                mLastBackgroundDemotionUid = uid;
                mLastBackgroundDemotionProcess = processName;
            }
            mBackgroundDemotionCount.incrementAndGet();
        }
        return demote;
    }

    void dump(PrintWriter pw) {
        pw.println("  threadPriority:");
        pw.println("    backgroundDemotionCheckCount=" + mBackgroundDemotionCheckCount.get());
        pw.println("    backgroundDemotionCount=" + mBackgroundDemotionCount.get());
        synchronized (mLock) {
            pw.println("    lastBackgroundDemotionUid=" + mLastBackgroundDemotionUid);
            pw.println("    lastBackgroundDemotionProcess=" + mLastBackgroundDemotionProcess);
            pw.println("    lastBackgroundDemotionUptimeMillis="
                    + mLastBackgroundDemotionUptimeMillis);
        }
    }
}
