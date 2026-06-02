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

final class PulseEngineStats {
    private final AtomicLong mSystemReadyCount = new AtomicLong();
    private final AtomicLong mPulseCount = new AtomicLong();
    private final AtomicLong mPolicyChangedCount = new AtomicLong();
    private final AtomicLong mTopAppChangedCount = new AtomicLong();
    private final AtomicLong mIntentStartedCount = new AtomicLong();
    private final AtomicLong mIntentFailedCount = new AtomicLong();
    private final AtomicLong mActivityLaunchedCount = new AtomicLong();
    private final AtomicLong mActivityLaunchCancelledCount = new AtomicLong();
    private final AtomicLong mActivityLaunchFinishedCount = new AtomicLong();
    private final AtomicLong mReportFullyDrawnCount = new AtomicLong();
    private final AtomicLong mMemoryPressureChangedCount = new AtomicLong();
    private final AtomicLong mThermalStatusChangedCount = new AtomicLong();
    private final AtomicLong mWakefulnessChangedCount = new AtomicLong();
    private final AtomicLong mProcessPssDeferredCount = new AtomicLong();
    private final AtomicLong mAppGcDeferredCount = new AtomicLong();
    private final AtomicLong mCompactionDeferredCount = new AtomicLong();
    private final AtomicLong mFreezerDeferredCount = new AtomicLong();
    private final AtomicLong mTrimMemoryDeferredCount = new AtomicLong();
    private final AtomicLong mJobDeferredCount = new AtomicLong();
    private final AtomicLong mBroadcastDeferredCount = new AtomicLong();
    private final AtomicLong mMaintenanceDeferredCount = new AtomicLong();

    void onSystemReady() {
        mSystemReadyCount.incrementAndGet();
    }

    void onPulse() {
        mPulseCount.incrementAndGet();
    }

    void onPolicyChanged() {
        mPolicyChangedCount.incrementAndGet();
    }

    void onTopAppChanged() {
        mTopAppChangedCount.incrementAndGet();
    }

    void onIntentStarted() {
        mIntentStartedCount.incrementAndGet();
    }

    void onIntentFailed() {
        mIntentFailedCount.incrementAndGet();
    }

    void onActivityLaunched() {
        mActivityLaunchedCount.incrementAndGet();
    }

    void onActivityLaunchCancelled() {
        mActivityLaunchCancelledCount.incrementAndGet();
    }

    void onActivityLaunchFinished() {
        mActivityLaunchFinishedCount.incrementAndGet();
    }

    void onReportFullyDrawn() {
        mReportFullyDrawnCount.incrementAndGet();
    }

    void onMemoryPressureChanged() {
        mMemoryPressureChangedCount.incrementAndGet();
    }

    void onThermalStatusChanged() {
        mThermalStatusChangedCount.incrementAndGet();
    }

    void onWakefulnessChanged() {
        mWakefulnessChangedCount.incrementAndGet();
    }

    void onProcessPssDeferred() {
        mProcessPssDeferredCount.incrementAndGet();
    }

    void onAppGcDeferred() {
        mAppGcDeferredCount.incrementAndGet();
    }

    void onCompactionDeferred() {
        mCompactionDeferredCount.incrementAndGet();
    }

    void onFreezerDeferred() {
        mFreezerDeferredCount.incrementAndGet();
    }

    void onTrimMemoryDeferred() {
        mTrimMemoryDeferredCount.incrementAndGet();
    }

    void onJobDeferred() {
        mJobDeferredCount.incrementAndGet();
    }

    void onBroadcastDeferred() {
        mBroadcastDeferredCount.incrementAndGet();
    }

    void onMaintenanceDeferred() {
        mMaintenanceDeferredCount.incrementAndGet();
    }

    void dump(PrintWriter pw) {
        pw.println("  stats:");
        pw.println("    systemReadyCount=" + mSystemReadyCount.get());
        pw.println("    pulseCount=" + mPulseCount.get());
        pw.println("    policyChangedCount=" + mPolicyChangedCount.get());
        pw.println("    topAppChangedCount=" + mTopAppChangedCount.get());
        pw.println("    intentStartedCount=" + mIntentStartedCount.get());
        pw.println("    intentFailedCount=" + mIntentFailedCount.get());
        pw.println("    activityLaunchedCount=" + mActivityLaunchedCount.get());
        pw.println("    activityLaunchCancelledCount=" + mActivityLaunchCancelledCount.get());
        pw.println("    activityLaunchFinishedCount=" + mActivityLaunchFinishedCount.get());
        pw.println("    reportFullyDrawnCount=" + mReportFullyDrawnCount.get());
        pw.println("    memoryPressureChangedCount=" + mMemoryPressureChangedCount.get());
        pw.println("    thermalStatusChangedCount=" + mThermalStatusChangedCount.get());
        pw.println("    wakefulnessChangedCount=" + mWakefulnessChangedCount.get());
        pw.println("    processPssDeferredCount=" + mProcessPssDeferredCount.get());
        pw.println("    appGcDeferredCount=" + mAppGcDeferredCount.get());
        pw.println("    compactionDeferredCount=" + mCompactionDeferredCount.get());
        pw.println("    freezerDeferredCount=" + mFreezerDeferredCount.get());
        pw.println("    trimMemoryDeferredCount=" + mTrimMemoryDeferredCount.get());
        pw.println("    jobDeferredCount=" + mJobDeferredCount.get());
        pw.println("    broadcastDeferredCount=" + mBroadcastDeferredCount.get());
        pw.println("    maintenanceDeferredCount=" + mMaintenanceDeferredCount.get());
    }
}
