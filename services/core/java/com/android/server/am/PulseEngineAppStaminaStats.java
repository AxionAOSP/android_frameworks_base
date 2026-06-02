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

import java.io.PrintWriter;
import java.util.concurrent.atomic.AtomicLong;

final class PulseEngineAppStaminaStats {
    private final AtomicLong mUserVisibleCount = new AtomicLong();
    private final AtomicLong mJobCheckCount = new AtomicLong();
    private final AtomicLong mBroadcastCheckCount = new AtomicLong();
    private final AtomicLong mAlarmCheckCount = new AtomicLong();
    private final AtomicLong mProcessCheckCount = new AtomicLong();
    private final AtomicLong mJobDeferredCount = new AtomicLong();
    private final AtomicLong mBroadcastDeferredCount = new AtomicLong();
    private final AtomicLong mAlarmDeferredCount = new AtomicLong();
    private final AtomicLong mProcessDemotedCount = new AtomicLong();
    private final AtomicLong mRestrictedBucketAppliedCount = new AtomicLong();
    private final AtomicLong mExemptCount = new AtomicLong();

    void onUserVisible() {
        mUserVisibleCount.incrementAndGet();
    }

    void onCheck(int workType) {
        if (workType == PulseEngineAppStaminaPolicy.WORK_TYPE_JOB) {
            mJobCheckCount.incrementAndGet();
        } else if (workType == PulseEngineAppStaminaPolicy.WORK_TYPE_BROADCAST) {
            mBroadcastCheckCount.incrementAndGet();
        } else if (workType == PulseEngineAppStaminaPolicy.WORK_TYPE_ALARM) {
            mAlarmCheckCount.incrementAndGet();
        } else if (workType == PulseEngineAppStaminaPolicy.WORK_TYPE_PROCESS) {
            mProcessCheckCount.incrementAndGet();
        }
    }

    void onDeferred(int workType) {
        if (workType == PulseEngineAppStaminaPolicy.WORK_TYPE_JOB) {
            mJobDeferredCount.incrementAndGet();
        } else if (workType == PulseEngineAppStaminaPolicy.WORK_TYPE_BROADCAST) {
            mBroadcastDeferredCount.incrementAndGet();
        } else if (workType == PulseEngineAppStaminaPolicy.WORK_TYPE_ALARM) {
            mAlarmDeferredCount.incrementAndGet();
        } else if (workType == PulseEngineAppStaminaPolicy.WORK_TYPE_PROCESS) {
            mProcessDemotedCount.incrementAndGet();
        }
    }

    void onRestrictedBucketApplied() {
        mRestrictedBucketAppliedCount.incrementAndGet();
    }

    void onExempt() {
        mExemptCount.incrementAndGet();
    }

    void dump(PrintWriter pw) {
        pw.println("  appStaminaStats:");
        pw.println("    userVisibleCount=" + mUserVisibleCount.get());
        pw.println("    jobCheckCount=" + mJobCheckCount.get());
        pw.println("    broadcastCheckCount=" + mBroadcastCheckCount.get());
        pw.println("    alarmCheckCount=" + mAlarmCheckCount.get());
        pw.println("    processCheckCount=" + mProcessCheckCount.get());
        pw.println("    jobDeferredCount=" + mJobDeferredCount.get());
        pw.println("    broadcastDeferredCount=" + mBroadcastDeferredCount.get());
        pw.println("    alarmDeferredCount=" + mAlarmDeferredCount.get());
        pw.println("    processDemotedCount=" + mProcessDemotedCount.get());
        pw.println("    restrictedBucketAppliedCount=" + mRestrictedBucketAppliedCount.get());
        pw.println("    exemptCount=" + mExemptCount.get());
    }
}
