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

import java.io.FileInputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

final class PulseEngineUserspaceSchedulerPolicy {
    private static final int INVALID_PID = -1;
    private static final int SCHEDSTAT_BUFFER_SIZE = 128;
    private static final String PROC_PATH_PREFIX = "/proc/";
    private static final String TASK_PATH_SEGMENT = "/task/";
    private static final String SCHEDSTAT_PATH_SUFFIX = "/schedstat";
    private static final char TOKEN_SEPARATOR_LIMIT = ' ';

    private final Object mLock = new Object();
    private final AtomicLong mSampleCount = new AtomicLong();
    private final AtomicLong mSampleErrorCount = new AtomicLong();
    private final AtomicLong mCpuRunnableDebtCount = new AtomicLong();
    private final byte[] mSchedstatBuffer = new byte[SCHEDSTAT_BUFFER_SIZE];
    private final SchedstatSample mSchedstatSample = new SchedstatSample();

    private int mTopPid = INVALID_PID;
    private int mTopUid = INVALID_PID;
    @Nullable private String mTopProcessName;
    private boolean mHasBaseline;
    private long mLastSampleUptimeMillis;
    private long mLastRuntimeNanos;
    private long mLastRunnableDelayNanos;
    private long mLastTimesliceCount;
    private long mLastRuntimeDeltaNanos;
    private long mLastRunnableDelayDeltaNanos;
    private long mLastTimesliceDeltaCount;
    private int mLastDebtType = PulseEngineSchedulerDebt.TYPE_NONE;
    private int mLastDebtScore;
    private long mLastDebtUptimeMillis;

    void onTopAppChanged(@Nullable String processName, int uid, int pid, long now) {
        synchronized (mLock) {
            if (mTopPid != pid) {
                mHasBaseline = false;
                mLastRuntimeNanos = 0L;
                mLastRunnableDelayNanos = 0L;
                mLastTimesliceCount = 0L;
                mLastRuntimeDeltaNanos = 0L;
                mLastRunnableDelayDeltaNanos = 0L;
                mLastTimesliceDeltaCount = 0L;
                mLastSampleUptimeMillis = 0L;
                mLastDebtType = PulseEngineSchedulerDebt.TYPE_NONE;
                mLastDebtScore = 0;
                mLastDebtUptimeMillis = now;
            }
            mTopProcessName = processName;
            mTopUid = uid;
            mTopPid = pid;
        }
    }

    void sample(PulseEngineConfig config, PulseEnginePolicy policy, long now) {
        if (!config.mUserspaceSchedulerEnabled) {
            return;
        }
        final int pid;
        synchronized (mLock) {
            if (mTopPid <= 0 || now < mLastSampleUptimeMillis
                    || now - mLastSampleUptimeMillis < config.mSchedulerSampleIntervalMs) {
                return;
            }
            mLastSampleUptimeMillis = now;
            pid = mTopPid;
        }
        if (!readSchedstat(pid, pid, mSchedstatSample)) {
            recordUnavailable(pid, now);
            return;
        }
        recordSample(config, policy, pid, now, mSchedstatSample);
    }

    boolean shouldContinueSampling(PulseEngineConfig config, boolean interactive) {
        if (!config.mUserspaceSchedulerEnabled || !interactive) {
            return false;
        }
        synchronized (mLock) {
            return mTopPid > 0;
        }
    }

    int getRecentDebtScore(PulseEngineConfig config, long now) {
        synchronized (mLock) {
            if (mLastDebtType != PulseEngineSchedulerDebt.TYPE_CPU_RUNNABLE
                    || now < mLastDebtUptimeMillis
                    || now - mLastDebtUptimeMillis > config.mSchedulerDebtMaxAgeMs) {
                return 0;
            }
            return mLastDebtScore;
        }
    }

    void dump(PrintWriter pw) {
        pw.println("  userspaceScheduler:");
        pw.println("    sampleCount=" + mSampleCount.get());
        pw.println("    sampleErrorCount=" + mSampleErrorCount.get());
        pw.println("    cpuRunnableDebtCount=" + mCpuRunnableDebtCount.get());
        synchronized (mLock) {
            pw.println("    topUid=" + mTopUid);
            pw.println("    topPid=" + mTopPid);
            pw.println("    topProcess=" + mTopProcessName);
            pw.println("    hasBaseline=" + mHasBaseline);
            pw.println("    lastSampleUptimeMillis=" + mLastSampleUptimeMillis);
            pw.println("    lastDebtType=" + PulseEngineSchedulerDebt.typeToString(mLastDebtType));
            pw.println("    lastDebtScore=" + mLastDebtScore);
            pw.println("    lastDebtUptimeMillis=" + mLastDebtUptimeMillis);
            pw.println("    lastRuntimeDeltaNanos=" + mLastRuntimeDeltaNanos);
            pw.println("    lastRunnableDelayDeltaNanos=" + mLastRunnableDelayDeltaNanos);
            pw.println("    lastTimesliceDeltaCount=" + mLastTimesliceDeltaCount);
        }
    }

    private void recordSample(PulseEngineConfig config, PulseEnginePolicy policy, int pid,
            long now, SchedstatSample sample) {
        synchronized (mLock) {
            if (pid != mTopPid) {
                return;
            }
            if (!mHasBaseline || sample.mRuntimeNanos < mLastRuntimeNanos
                    || sample.mRunnableDelayNanos < mLastRunnableDelayNanos
                    || sample.mTimesliceCount < mLastTimesliceCount) {
                mHasBaseline = true;
                mLastRuntimeNanos = sample.mRuntimeNanos;
                mLastRunnableDelayNanos = sample.mRunnableDelayNanos;
                mLastTimesliceCount = sample.mTimesliceCount;
                mLastRuntimeDeltaNanos = 0L;
                mLastRunnableDelayDeltaNanos = 0L;
                mLastTimesliceDeltaCount = 0L;
                mLastDebtType = PulseEngineSchedulerDebt.TYPE_NONE;
                mLastDebtScore = 0;
                mLastDebtUptimeMillis = now;
                mSampleCount.incrementAndGet();
                return;
            }
            mLastRuntimeDeltaNanos = sample.mRuntimeNanos - mLastRuntimeNanos;
            mLastRunnableDelayDeltaNanos =
                    sample.mRunnableDelayNanos - mLastRunnableDelayNanos;
            mLastTimesliceDeltaCount = sample.mTimesliceCount - mLastTimesliceCount;
            mLastRuntimeNanos = sample.mRuntimeNanos;
            mLastRunnableDelayNanos = sample.mRunnableDelayNanos;
            mLastTimesliceCount = sample.mTimesliceCount;
            mLastDebtScore = PulseEngineSchedulerDebt.computeRunnableScore(config,
                    mLastRunnableDelayDeltaNanos);
            mLastDebtType = PulseEngineSchedulerDebt.classify(config, policy,
                    mLastRunnableDelayDeltaNanos);
            mLastDebtUptimeMillis = now;
            mSampleCount.incrementAndGet();
            if (mLastDebtType == PulseEngineSchedulerDebt.TYPE_CPU_RUNNABLE) {
                mCpuRunnableDebtCount.incrementAndGet();
            }
        }
    }

    private void recordUnavailable(int pid, long now) {
        synchronized (mLock) {
            if (pid != mTopPid) {
                return;
            }
            mHasBaseline = false;
            mLastRuntimeDeltaNanos = 0L;
            mLastRunnableDelayDeltaNanos = 0L;
            mLastTimesliceDeltaCount = 0L;
            mLastDebtType = PulseEngineSchedulerDebt.TYPE_UNAVAILABLE;
            mLastDebtScore = 0;
            mLastDebtUptimeMillis = now;
        }
        mSampleErrorCount.incrementAndGet();
    }

    private boolean readSchedstat(int pid, int tid, SchedstatSample sample) {
        final String path = PROC_PATH_PREFIX + pid + TASK_PATH_SEGMENT + tid
                + SCHEDSTAT_PATH_SUFFIX;
        try (FileInputStream stream = new FileInputStream(path)) {
            final int length = stream.read(mSchedstatBuffer);
            if (length <= 0) {
                return false;
            }
            return parseSchedstat(new String(mSchedstatBuffer, 0, length,
                    StandardCharsets.US_ASCII), sample);
        } catch (IOException | NumberFormatException e) {
            return false;
        }
    }

    private static boolean parseSchedstat(String value, SchedstatSample sample) {
        final int runtimeStart = nextTokenStart(value, 0);
        final int runtimeEnd = nextTokenEnd(value, runtimeStart);
        final int runnableStart = nextTokenStart(value, runtimeEnd);
        final int runnableEnd = nextTokenEnd(value, runnableStart);
        final int timesliceStart = nextTokenStart(value, runnableEnd);
        final int timesliceEnd = nextTokenEnd(value, timesliceStart);
        if (runtimeStart < 0 || runtimeEnd <= runtimeStart || runnableStart < 0
                || runnableEnd <= runnableStart || timesliceStart < 0
                || timesliceEnd <= timesliceStart) {
            return false;
        }
        sample.mRuntimeNanos = Long.parseLong(value.substring(runtimeStart, runtimeEnd));
        sample.mRunnableDelayNanos = Long.parseLong(value.substring(runnableStart, runnableEnd));
        sample.mTimesliceCount = Long.parseLong(value.substring(timesliceStart, timesliceEnd));
        return true;
    }

    private static int nextTokenStart(String value, int start) {
        if (start < 0) {
            return -1;
        }
        final int length = value.length();
        for (int i = start; i < length; i++) {
            if (value.charAt(i) > TOKEN_SEPARATOR_LIMIT) {
                return i;
            }
        }
        return -1;
    }

    private static int nextTokenEnd(String value, int start) {
        if (start < 0) {
            return -1;
        }
        final int length = value.length();
        for (int i = start; i < length; i++) {
            if (value.charAt(i) <= TOKEN_SEPARATOR_LIMIT) {
                return i;
            }
        }
        return length;
    }

    private static final class SchedstatSample {
        long mRuntimeNanos;
        long mRunnableDelayNanos;
        long mTimesliceCount;
    }
}
