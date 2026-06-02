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

final class PulseEngineSignalSnapshot {
    final long mUptimeMillis;
    final String mTopProcessName;
    final int mTopUid;
    final int mTopPid;
    final long mTopAppChangedUptimeMillis;
    final long mLastIntentStartedUptimeMillis;
    final long mLastIntentFailedUptimeMillis;
    final long mLastLaunchId;
    final String mLastLaunchComponentName;
    final String mLastLaunchProcessName;
    final int mLastLaunchUid;
    final int mLastLaunchTemperature;
    final int mLastLaunchMode;
    final boolean mLaunchActive;
    final long mLastLaunchStartedUptimeMillis;
    final long mLastLaunchFinishedUptimeMillis;
    final long mLastLaunchCancelledUptimeMillis;
    final long mLastFullyDrawnUptimeMillis;
    final long mLastIntentStartedTimestampNanos;
    final long mLastLaunchFinishedTimestampNanos;
    final long mLastFullyDrawnTimestampNanos;

    private PulseEngineSignalSnapshot(long uptimeMillis, String topProcessName, int topUid,
            int topPid, long topAppChangedUptimeMillis, long lastIntentStartedUptimeMillis,
            long lastIntentFailedUptimeMillis, long lastLaunchId, String lastLaunchComponentName,
            String lastLaunchProcessName, int lastLaunchUid, int lastLaunchTemperature,
            int lastLaunchMode, boolean launchActive, long lastLaunchStartedUptimeMillis,
            long lastLaunchFinishedUptimeMillis, long lastLaunchCancelledUptimeMillis,
            long lastFullyDrawnUptimeMillis, long lastIntentStartedTimestampNanos,
            long lastLaunchFinishedTimestampNanos, long lastFullyDrawnTimestampNanos) {
        mUptimeMillis = uptimeMillis;
        mTopProcessName = topProcessName;
        mTopUid = topUid;
        mTopPid = topPid;
        mTopAppChangedUptimeMillis = topAppChangedUptimeMillis;
        mLastIntentStartedUptimeMillis = lastIntentStartedUptimeMillis;
        mLastIntentFailedUptimeMillis = lastIntentFailedUptimeMillis;
        mLastLaunchId = lastLaunchId;
        mLastLaunchComponentName = lastLaunchComponentName;
        mLastLaunchProcessName = lastLaunchProcessName;
        mLastLaunchUid = lastLaunchUid;
        mLastLaunchTemperature = lastLaunchTemperature;
        mLastLaunchMode = lastLaunchMode;
        mLaunchActive = launchActive;
        mLastLaunchStartedUptimeMillis = lastLaunchStartedUptimeMillis;
        mLastLaunchFinishedUptimeMillis = lastLaunchFinishedUptimeMillis;
        mLastLaunchCancelledUptimeMillis = lastLaunchCancelledUptimeMillis;
        mLastFullyDrawnUptimeMillis = lastFullyDrawnUptimeMillis;
        mLastIntentStartedTimestampNanos = lastIntentStartedTimestampNanos;
        mLastLaunchFinishedTimestampNanos = lastLaunchFinishedTimestampNanos;
        mLastFullyDrawnTimestampNanos = lastFullyDrawnTimestampNanos;
    }

    static PulseEngineSignalSnapshot empty(long uptimeMillis) {
        return new PulseEngineSignalSnapshot(uptimeMillis, null, -1, -1, 0L, 0L, 0L, -1L,
                null, null, -1, 0, 0, false, 0L, 0L, 0L, 0L, 0L, 0L, 0L);
    }

    PulseEngineSignalSnapshot withUptime(long uptimeMillis) {
        return new PulseEngineSignalSnapshot(uptimeMillis, mTopProcessName, mTopUid, mTopPid,
                mTopAppChangedUptimeMillis, mLastIntentStartedUptimeMillis,
                mLastIntentFailedUptimeMillis, mLastLaunchId, mLastLaunchComponentName,
                mLastLaunchProcessName, mLastLaunchUid, mLastLaunchTemperature, mLastLaunchMode,
                mLaunchActive, mLastLaunchStartedUptimeMillis, mLastLaunchFinishedUptimeMillis,
                mLastLaunchCancelledUptimeMillis, mLastFullyDrawnUptimeMillis,
                mLastIntentStartedTimestampNanos, mLastLaunchFinishedTimestampNanos,
                mLastFullyDrawnTimestampNanos);
    }

    PulseEngineSignalSnapshot withTopApp(String processName, int uid, int pid,
            long uptimeMillis) {
        return new PulseEngineSignalSnapshot(uptimeMillis, processName, uid, pid, uptimeMillis,
                mLastIntentStartedUptimeMillis, mLastIntentFailedUptimeMillis, mLastLaunchId,
                mLastLaunchComponentName, mLastLaunchProcessName, mLastLaunchUid,
                mLastLaunchTemperature, mLastLaunchMode, mLaunchActive,
                mLastLaunchStartedUptimeMillis, mLastLaunchFinishedUptimeMillis,
                mLastLaunchCancelledUptimeMillis, mLastFullyDrawnUptimeMillis,
                mLastIntentStartedTimestampNanos, mLastLaunchFinishedTimestampNanos,
                mLastFullyDrawnTimestampNanos);
    }

    PulseEngineSignalSnapshot withIntentStarted(long timestampNanos, long uptimeMillis) {
        return new PulseEngineSignalSnapshot(uptimeMillis, mTopProcessName, mTopUid, mTopPid,
                mTopAppChangedUptimeMillis, uptimeMillis, mLastIntentFailedUptimeMillis,
                mLastLaunchId, mLastLaunchComponentName, mLastLaunchProcessName, mLastLaunchUid,
                mLastLaunchTemperature, mLastLaunchMode, true, mLastLaunchStartedUptimeMillis,
                mLastLaunchFinishedUptimeMillis, mLastLaunchCancelledUptimeMillis,
                mLastFullyDrawnUptimeMillis, timestampNanos, mLastLaunchFinishedTimestampNanos,
                mLastFullyDrawnTimestampNanos);
    }

    PulseEngineSignalSnapshot withIntentFailed(long uptimeMillis) {
        return new PulseEngineSignalSnapshot(uptimeMillis, mTopProcessName, mTopUid, mTopPid,
                mTopAppChangedUptimeMillis, mLastIntentStartedUptimeMillis, uptimeMillis,
                mLastLaunchId, mLastLaunchComponentName, mLastLaunchProcessName, mLastLaunchUid,
                mLastLaunchTemperature, mLastLaunchMode, false, mLastLaunchStartedUptimeMillis,
                mLastLaunchFinishedUptimeMillis, mLastLaunchCancelledUptimeMillis,
                mLastFullyDrawnUptimeMillis, mLastIntentStartedTimestampNanos,
                mLastLaunchFinishedTimestampNanos, mLastFullyDrawnTimestampNanos);
    }

    PulseEngineSignalSnapshot withActivityLaunched(long id, String componentName,
            String processName, int uid, int temperature, long uptimeMillis) {
        return new PulseEngineSignalSnapshot(uptimeMillis, mTopProcessName, mTopUid, mTopPid,
                mTopAppChangedUptimeMillis, mLastIntentStartedUptimeMillis,
                mLastIntentFailedUptimeMillis, id, componentName, processName, uid, temperature,
                mLastLaunchMode, true, uptimeMillis, mLastLaunchFinishedUptimeMillis,
                mLastLaunchCancelledUptimeMillis, mLastFullyDrawnUptimeMillis,
                mLastIntentStartedTimestampNanos, mLastLaunchFinishedTimestampNanos,
                mLastFullyDrawnTimestampNanos);
    }

    PulseEngineSignalSnapshot withActivityLaunchCancelled(long id, long uptimeMillis) {
        return new PulseEngineSignalSnapshot(uptimeMillis, mTopProcessName, mTopUid, mTopPid,
                mTopAppChangedUptimeMillis, mLastIntentStartedUptimeMillis,
                mLastIntentFailedUptimeMillis, id, mLastLaunchComponentName,
                mLastLaunchProcessName, mLastLaunchUid, mLastLaunchTemperature, mLastLaunchMode,
                false, mLastLaunchStartedUptimeMillis, mLastLaunchFinishedUptimeMillis,
                uptimeMillis, mLastFullyDrawnUptimeMillis, mLastIntentStartedTimestampNanos,
                mLastLaunchFinishedTimestampNanos, mLastFullyDrawnTimestampNanos);
    }

    PulseEngineSignalSnapshot withActivityLaunchFinished(long id, String componentName,
            long timestampNanos, int launchMode, long uptimeMillis) {
        return new PulseEngineSignalSnapshot(uptimeMillis, mTopProcessName, mTopUid, mTopPid,
                mTopAppChangedUptimeMillis, mLastIntentStartedUptimeMillis,
                mLastIntentFailedUptimeMillis, id, componentName, mLastLaunchProcessName,
                mLastLaunchUid, mLastLaunchTemperature, launchMode, false,
                mLastLaunchStartedUptimeMillis, uptimeMillis, mLastLaunchCancelledUptimeMillis,
                mLastFullyDrawnUptimeMillis, mLastIntentStartedTimestampNanos, timestampNanos,
                mLastFullyDrawnTimestampNanos);
    }

    PulseEngineSignalSnapshot withReportFullyDrawn(long id, long timestampNanos,
            long uptimeMillis) {
        return new PulseEngineSignalSnapshot(uptimeMillis, mTopProcessName, mTopUid, mTopPid,
                mTopAppChangedUptimeMillis, mLastIntentStartedUptimeMillis,
                mLastIntentFailedUptimeMillis, id, mLastLaunchComponentName,
                mLastLaunchProcessName, mLastLaunchUid, mLastLaunchTemperature, mLastLaunchMode,
                false, mLastLaunchStartedUptimeMillis, mLastLaunchFinishedUptimeMillis,
                mLastLaunchCancelledUptimeMillis, uptimeMillis, mLastIntentStartedTimestampNanos,
                mLastLaunchFinishedTimestampNanos, timestampNanos);
    }

    void dump(PrintWriter pw) {
        pw.println("  signals:");
        pw.println("    uptimeMillis=" + mUptimeMillis);
        pw.println("    topProcess=" + mTopProcessName);
        pw.println("    topUid=" + mTopUid);
        pw.println("    topPid=" + mTopPid);
        pw.println("    topAppChangedUptimeMillis=" + mTopAppChangedUptimeMillis);
        pw.println("    launchActive=" + mLaunchActive);
        pw.println("    lastLaunchId=" + mLastLaunchId);
        pw.println("    lastLaunchComponent=" + mLastLaunchComponentName);
        pw.println("    lastLaunchProcess=" + mLastLaunchProcessName);
        pw.println("    lastLaunchUid=" + mLastLaunchUid);
        pw.println("    lastLaunchTemperature=" + mLastLaunchTemperature);
        pw.println("    lastLaunchMode=" + mLastLaunchMode);
        pw.println("    lastIntentStartedUptimeMillis=" + mLastIntentStartedUptimeMillis);
        pw.println("    lastIntentFailedUptimeMillis=" + mLastIntentFailedUptimeMillis);
        pw.println("    lastLaunchStartedUptimeMillis=" + mLastLaunchStartedUptimeMillis);
        pw.println("    lastLaunchFinishedUptimeMillis=" + mLastLaunchFinishedUptimeMillis);
        pw.println("    lastLaunchCancelledUptimeMillis=" + mLastLaunchCancelledUptimeMillis);
        pw.println("    lastFullyDrawnUptimeMillis=" + mLastFullyDrawnUptimeMillis);
    }
}
