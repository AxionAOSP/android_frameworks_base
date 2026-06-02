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

import static com.android.internal.app.procstats.ProcessStats.ADJ_MEM_FACTOR_LOW;
import static com.android.internal.app.procstats.ProcessStats.ADJ_MEM_FACTOR_NORMAL;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.os.Handler;
import android.os.PowerManager;
import android.os.SystemClock;

import java.io.PrintWriter;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class PulseEngine {
    private static final long POLICY_CACHE_MAX_AGE_MS = 100L;
    private static final long SCHEDULER_SAMPLE_IMMEDIATE_DELAY_MS = 0L;
    private static final int MAX_POLICY_SCORE = 100;

    private final Handler mHandler;
    private final PulseEnginePhaseModel mPhaseModel = new PulseEnginePhaseModel();
    private final PulseEnginePerceptionModel mPerceptionModel = new PulseEnginePerceptionModel();
    private final PulseEnginePressureModel mPressureModel = new PulseEnginePressureModel();
    private final PulseEngineBudgetSolver mBudgetSolver = new PulseEngineBudgetSolver();
    private final PulseEngineEnergyLedger mEnergyLedger = new PulseEngineEnergyLedger();
    private final PulseEngineAppStaminaPolicy mAppStaminaPolicy =
            new PulseEngineAppStaminaPolicy(mEnergyLedger);
    private final PulseEngineThreadPriorityPolicy mThreadPriorityPolicy =
            new PulseEngineThreadPriorityPolicy();
    private final PulseEngineUserspaceSchedulerPolicy mUserspaceSchedulerPolicy =
            new PulseEngineUserspaceSchedulerPolicy();
    private final PulseEngineStats mStats = new PulseEngineStats();
    private final AtomicReference<PulseEngineSignalSnapshot> mSnapshot;
    private final AtomicBoolean mPulseScheduled = new AtomicBoolean();
    private final AtomicBoolean mSchedulerSampleScheduled = new AtomicBoolean();
    private final Runnable mPulseRunnable = this::pulse;
    private final Runnable mSchedulerSampleRunnable = this::sampleUserspaceScheduler;

    private final PulseEngineConfig mConfig = PulseEngineConfig.defaultConfig();
    private volatile PulseEnginePolicy mPolicy;
    private volatile boolean mSystemReady;
    private volatile int mMemoryPressure = ADJ_MEM_FACTOR_NORMAL;
    private volatile int mThermalStatus = PowerManager.THERMAL_STATUS_NONE;
    private volatile int mWakefulness;
    private volatile boolean mInteractive = true;
    private volatile long mLastMemoryPressureUptimeMillis;
    private volatile long mLastThermalStatusUptimeMillis;
    private volatile long mLastWakefulnessUptimeMillis;

    public PulseEngine(@NonNull Handler handler) {
        mHandler = handler;
        final long now = SystemClock.uptimeMillis();
        mSnapshot = new AtomicReference<>(PulseEngineSignalSnapshot.empty(now));
        mPolicy = PulseEnginePolicy.disabled(now);
    }

    void setAppRestrictionController(AppRestrictionController controller) {
        mAppStaminaPolicy.setAppRestrictionController(controller);
    }

    void systemReady() {
        mSystemReady = true;
        mStats.onSystemReady();
        schedulePulse();
    }

    void onTopAppChanged(@Nullable String processName, int uid, int pid) {
        final long now = SystemClock.uptimeMillis();
        while (true) {
            final PulseEngineSignalSnapshot snapshot = mSnapshot.get();
            if (mSnapshot.compareAndSet(snapshot, snapshot.withTopApp(processName, uid, pid,
                    now))) {
                break;
            }
        }
        mAppStaminaPolicy.onTopAppChanged(processName, uid, now);
        mUserspaceSchedulerPolicy.onTopAppChanged(processName, uid, pid, now);
        mStats.onTopAppChanged();
        schedulePulse();
        scheduleUserspaceSchedulerSample(SCHEDULER_SAMPLE_IMMEDIATE_DELAY_MS);
    }

    void onIntentStarted(long timestampNanos) {
        final long now = SystemClock.uptimeMillis();
        while (true) {
            final PulseEngineSignalSnapshot snapshot = mSnapshot.get();
            if (mSnapshot.compareAndSet(snapshot, snapshot.withIntentStarted(timestampNanos,
                    now))) {
                break;
            }
        }
        mStats.onIntentStarted();
        schedulePulse();
    }

    void onIntentFailed(long id) {
        final long now = SystemClock.uptimeMillis();
        while (true) {
            final PulseEngineSignalSnapshot snapshot = mSnapshot.get();
            if (mSnapshot.compareAndSet(snapshot, snapshot.withIntentFailed(now))) {
                break;
            }
        }
        mStats.onIntentFailed();
        schedulePulse();
    }

    void onActivityLaunched(long id, @Nullable ComponentName name, int temperature,
            @Nullable String processName, int uid) {
        final String componentName = name != null ? name.flattenToShortString() : null;
        final long now = SystemClock.uptimeMillis();
        while (true) {
            final PulseEngineSignalSnapshot snapshot = mSnapshot.get();
            if (mSnapshot.compareAndSet(snapshot, snapshot.withActivityLaunched(id,
                    componentName, processName, uid, temperature, now))) {
                break;
            }
        }
        mAppStaminaPolicy.onActivityLaunched(processName, uid, now);
        mStats.onActivityLaunched();
        schedulePulse();
    }

    void onActivityLaunchCancelled(long id) {
        final long now = SystemClock.uptimeMillis();
        while (true) {
            final PulseEngineSignalSnapshot snapshot = mSnapshot.get();
            if (mSnapshot.compareAndSet(snapshot, snapshot.withActivityLaunchCancelled(id,
                    now))) {
                break;
            }
        }
        mStats.onActivityLaunchCancelled();
        schedulePulse();
    }

    void onActivityLaunchFinished(long id, @Nullable ComponentName name, long timestampNanos,
            int launchMode) {
        final String componentName = name != null ? name.flattenToShortString() : null;
        final long now = SystemClock.uptimeMillis();
        while (true) {
            final PulseEngineSignalSnapshot snapshot = mSnapshot.get();
            if (mSnapshot.compareAndSet(snapshot, snapshot.withActivityLaunchFinished(id,
                    componentName, timestampNanos, launchMode, now))) {
                break;
            }
        }
        mStats.onActivityLaunchFinished();
        schedulePulse();
    }

    void onReportFullyDrawn(long id, long timestampNanos) {
        final long now = SystemClock.uptimeMillis();
        while (true) {
            final PulseEngineSignalSnapshot snapshot = mSnapshot.get();
            if (mSnapshot.compareAndSet(snapshot, snapshot.withReportFullyDrawn(id,
                    timestampNanos, now))) {
                break;
            }
        }
        mStats.onReportFullyDrawn();
        schedulePulse();
    }

    void onMemoryPressureChanged(int newMemFactor) {
        mMemoryPressure = newMemFactor;
        mLastMemoryPressureUptimeMillis = SystemClock.uptimeMillis();
        mStats.onMemoryPressureChanged();
        schedulePulse();
    }

    void onThermalStatusChanged(int status) {
        mThermalStatus = status;
        mLastThermalStatusUptimeMillis = SystemClock.uptimeMillis();
        mStats.onThermalStatusChanged();
        schedulePulse();
    }

    void onWakefulnessChanged(int wakefulness, boolean interactive) {
        mWakefulness = wakefulness;
        mInteractive = interactive;
        mLastWakefulnessUptimeMillis = SystemClock.uptimeMillis();
        mStats.onWakefulnessChanged();
        schedulePulse();
        scheduleUserspaceSchedulerSample(SCHEDULER_SAMPLE_IMMEDIATE_DELAY_MS);
    }

    void onUidBatteryUsageSample(int uid, double backgroundMah, double cachedMah,
            double backgroundPercent, double cachedPercent, long elapsedMillis) {
        mEnergyLedger.onUidBatteryUsageSample(uid, backgroundMah, cachedMah, backgroundPercent,
                cachedPercent, elapsedMillis);
    }

    boolean shouldDeferProcessPss() {
        final PulseEngineConfig config = mConfig;
        if (!config.mProcessPssAdmissionEnabled || !shouldDeferInteractiveElasticWork(config)) {
            return false;
        }
        mStats.onProcessPssDeferred();
        return true;
    }

    boolean shouldDeferAppGc() {
        final PulseEngineConfig config = mConfig;
        if (!config.mAppGcAdmissionEnabled || !shouldDeferMemoryReliefWork(config)) {
            return false;
        }
        mStats.onAppGcDeferred();
        return true;
    }

    boolean shouldDeferCompaction() {
        final PulseEngineConfig config = mConfig;
        if (!config.mCompactionAdmissionEnabled || !shouldDeferMemoryReliefWork(config)) {
            return false;
        }
        mStats.onCompactionDeferred();
        return true;
    }

    boolean shouldDeferFreezer() {
        final PulseEngineConfig config = mConfig;
        if (!config.mFreezerAdmissionEnabled || !shouldDeferInteractiveElasticWork(config)) {
            return false;
        }
        mStats.onFreezerDeferred();
        return true;
    }

    boolean shouldDeferTrimMemory() {
        final PulseEngineConfig config = mConfig;
        if (!config.mTrimMemoryAdmissionEnabled || !shouldDeferMemoryReliefWork(config)) {
            return false;
        }
        mStats.onTrimMemoryDeferred();
        return true;
    }

    boolean shouldDeferJobs() {
        final PulseEngineConfig config = mConfig;
        if (!config.mJobAdmissionEnabled || !shouldDeferElasticWork(config)) {
            return false;
        }
        mStats.onJobDeferred();
        return true;
    }

    boolean shouldDeferBroadcasts() {
        final PulseEngineConfig config = mConfig;
        if (!config.mBroadcastAdmissionEnabled || !shouldDeferElasticWork(config)) {
            return false;
        }
        mStats.onBroadcastDeferred();
        return true;
    }

    boolean shouldDeferMaintenance() {
        final PulseEngineConfig config = mConfig;
        if (!config.mMaintenanceAdmissionEnabled || !shouldDeferElasticWork(config)) {
            return false;
        }
        mStats.onMaintenanceDeferred();
        return true;
    }

    boolean shouldDeferJob(int uid, @Nullable String packageName) {
        final PulseEngineConfig config = mConfig;
        if (!config.mJobAdmissionEnabled) {
            return false;
        }
        if (config.mAppStaminaEnabled) {
            final long now = SystemClock.uptimeMillis();
            final boolean defer = mAppStaminaPolicy.shouldDeferWork(config, currentPolicy(now),
                    uid, packageName, PulseEngineAppStaminaPolicy.WORK_TYPE_JOB, now);
            if (defer) {
                maybeScheduleRestrictedBucket(config, uid, packageName, now);
            }
            return defer;
        }
        return shouldDeferJobs();
    }

    boolean shouldDeferBroadcast(int uid, @Nullable String packageName) {
        final PulseEngineConfig config = mConfig;
        if (!config.mBroadcastAdmissionEnabled) {
            return false;
        }
        if (config.mAppStaminaEnabled) {
            final long now = SystemClock.uptimeMillis();
            final boolean defer = mAppStaminaPolicy.shouldDeferWork(config, currentPolicy(now),
                    uid, packageName, PulseEngineAppStaminaPolicy.WORK_TYPE_BROADCAST, now);
            if (defer) {
                maybeScheduleRestrictedBucket(config, uid, packageName, now);
            }
            return defer;
        }
        return shouldDeferBroadcasts();
    }

    boolean shouldDeferAlarm(int uid, @Nullable String packageName) {
        final PulseEngineConfig config = mConfig;
        if (!config.mAppStaminaEnabled) {
            return false;
        }
        final long now = SystemClock.uptimeMillis();
        final boolean defer = mAppStaminaPolicy.shouldDeferWork(config, currentPolicy(now), uid,
                packageName, PulseEngineAppStaminaPolicy.WORK_TYPE_ALARM, now);
        if (defer) {
            maybeScheduleRestrictedBucket(config, uid, packageName, now);
        }
        return defer;
    }

    boolean shouldDemoteBackgroundProcess(int uid, @Nullable String processName) {
        final long now = SystemClock.uptimeMillis();
        return mThreadPriorityPolicy.shouldDemoteBackgroundProcess(mConfig, currentPolicy(now),
                mAppStaminaPolicy, uid, processName, now);
    }

    boolean isNetworkingBlocked(int uid) {
        final long now = SystemClock.uptimeMillis();
        return mAppStaminaPolicy.isNetworkingBlocked(mConfig, uid, now);
    }

    long getAppStaminaAlarmDelayMillis() {
        return mAppStaminaPolicy.getAlarmDelayMillis(mConfig);
    }

    long getElasticWorkDelayMillis() {
        return mConfig.mElasticWorkDelayMs;
    }

    long getMaxElasticWorkDeferralMillis() {
        return mConfig.mMaxElasticWorkDeferralMs;
    }

    void dump(PrintWriter pw) {
        pw.println("PulseEngine:");
        pw.println("  systemReady=" + mSystemReady);
        pw.println("  context:");
        pw.println("    memoryPressure=" + mMemoryPressure);
        pw.println("    thermalStatus=" + mThermalStatus);
        pw.println("    wakefulness=" + mWakefulness);
        pw.println("    interactive=" + mInteractive);
        pw.println("    lastMemoryPressureUptimeMillis=" + mLastMemoryPressureUptimeMillis);
        pw.println("    lastThermalStatusUptimeMillis=" + mLastThermalStatusUptimeMillis);
        pw.println("    lastWakefulnessUptimeMillis=" + mLastWakefulnessUptimeMillis);
        mConfig.dump(pw);
        mPolicy.dump(pw);
        mSnapshot.get().dump(pw);
        mStats.dump(pw);
        mEnergyLedger.dump(pw);
        mAppStaminaPolicy.dump(pw);
        mThreadPriorityPolicy.dump(pw);
        mUserspaceSchedulerPolicy.dump(pw);
    }

    private void maybeScheduleRestrictedBucket(PulseEngineConfig config, int uid,
            @Nullable String packageName, long now) {
        if (!mAppStaminaPolicy.shouldApplyRestrictedBucket(config, uid, packageName, now)) {
            return;
        }
        mHandler.post(() -> {
            final PulseEngineConfig currentConfig = mConfig;
            final long currentNow = SystemClock.uptimeMillis();
            if (mAppStaminaPolicy.shouldApplyRestrictedBucket(currentConfig, uid, packageName,
                    currentNow)) {
                mAppStaminaPolicy.applyRestrictedBucket(uid, packageName);
            }
        });
    }

    private void pulse() {
        mPulseScheduled.set(false);
        updatePolicy(SystemClock.uptimeMillis());
    }

    private void sampleUserspaceScheduler() {
        mSchedulerSampleScheduled.set(false);
        final long now = SystemClock.uptimeMillis();
        final PulseEnginePolicy policy = updatePolicy(now);
        final PulseEngineConfig config = mConfig;
        mUserspaceSchedulerPolicy.sample(config, policy, now);
        if (mUserspaceSchedulerPolicy.shouldContinueSampling(config, mInteractive)) {
            scheduleUserspaceSchedulerSample(config.mSchedulerSampleIntervalMs);
        }
    }

    private boolean shouldDeferElasticWork(PulseEngineConfig config) {
        if (!mSystemReady || !config.isAdmissionEnabled()) {
            return false;
        }
        return currentPolicy(SystemClock.uptimeMillis()).isAdmissionActive();
    }

    private boolean shouldDeferInteractiveElasticWork(PulseEngineConfig config) {
        if (!mSystemReady || !config.isAdmissionEnabled()) {
            return false;
        }
        return currentPolicy(SystemClock.uptimeMillis()).isInteractiveAdmissionActive();
    }

    private boolean shouldDeferMemoryReliefWork(PulseEngineConfig config) {
        return mMemoryPressure < ADJ_MEM_FACTOR_LOW && shouldDeferInteractiveElasticWork(config);
    }

    private PulseEnginePolicy currentPolicy(long now) {
        final PulseEnginePolicy policy = mPolicy;
        if (now >= policy.mUptimeMillis
                && now - policy.mUptimeMillis <= POLICY_CACHE_MAX_AGE_MS) {
            return policy;
        }
        return updatePolicy(now);
    }

    private PulseEnginePolicy updatePolicy(long now) {
        final PulseEngineConfig config = mConfig;
        final PulseEngineSignalSnapshot snapshot = mSnapshot.get().withUptime(now);
        mSnapshot.set(snapshot);
        final PulseEnginePolicy newPolicy;
        if (!config.isEnabled()) {
            newPolicy = PulseEnginePolicy.disabled(now);
        } else {
            final int phase = mPhaseModel.resolve(snapshot, config, now);
            final int perception = mPerceptionModel.compute(snapshot, config, phase, now);
            final int pressure = Math.min(MAX_POLICY_SCORE,
                    mPressureModel.compute(mMemoryPressure, mThermalStatus, mInteractive)
                            + mUserspaceSchedulerPolicy.getRecentDebtScore(config, now));
            newPolicy = mBudgetSolver.solve(config, phase, perception, pressure, now);
        }
        final PulseEnginePolicy oldPolicy = mPolicy;
        if (!newPolicy.hasSameDecision(oldPolicy)) {
            mStats.onPolicyChanged();
        }
        mPolicy = newPolicy;
        mStats.onPulse();
        return newPolicy;
    }

    private void schedulePulse() {
        final PulseEngineConfig config = mConfig;
        if (!mSystemReady || !config.isEnabled()) {
            return;
        }
        if (mPulseScheduled.compareAndSet(false, true)) {
            mHandler.post(mPulseRunnable);
        }
    }

    private void scheduleUserspaceSchedulerSample(long delayMillis) {
        final PulseEngineConfig config = mConfig;
        if (!mSystemReady || !config.isEnabled()
                || !mUserspaceSchedulerPolicy.shouldContinueSampling(config, mInteractive)) {
            return;
        }
        if (delayMillis == SCHEDULER_SAMPLE_IMMEDIATE_DELAY_MS) {
            mHandler.removeCallbacks(mSchedulerSampleRunnable);
            mSchedulerSampleScheduled.set(false);
        }
        if (mSchedulerSampleScheduled.compareAndSet(false, true)) {
            if (delayMillis == SCHEDULER_SAMPLE_IMMEDIATE_DELAY_MS) {
                mHandler.post(mSchedulerSampleRunnable);
            } else {
                mHandler.postDelayed(mSchedulerSampleRunnable, delayMillis);
            }
        }
    }

}
