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

final class PulseEngineConfig {
    static final int MODE_OFF = 0;
    static final int MODE_TELEMETRY = 1;
    static final int MODE_ADMISSION = 2;

    private static final boolean DEFAULT_ENABLED = true;
    private static final int DEFAULT_MODE = MODE_ADMISSION;
    private static final long DEFAULT_LAUNCH_PROTECTION_MS = 2500L;
    private static final long DEFAULT_TOP_APP_PROTECTION_MS = 750L;
    private static final int DEFAULT_GUARDED_THRESHOLD = 45;
    private static final int DEFAULT_PROTECTED_THRESHOLD = 75;
    private static final int DEFAULT_MIN_ELASTIC_BUDGET = 0;
    private static final int DEFAULT_MAX_ELASTIC_BUDGET = 100;
    private static final boolean DEFAULT_PROCESS_PSS_ADMISSION_ENABLED = true;
    private static final boolean DEFAULT_APP_GC_ADMISSION_ENABLED = true;
    private static final boolean DEFAULT_COMPACTION_ADMISSION_ENABLED = true;
    private static final boolean DEFAULT_FREEZER_ADMISSION_ENABLED = true;
    private static final boolean DEFAULT_TRIM_MEMORY_ADMISSION_ENABLED = true;
    private static final boolean DEFAULT_JOB_ADMISSION_ENABLED = true;
    private static final boolean DEFAULT_BROADCAST_ADMISSION_ENABLED = true;
    private static final boolean DEFAULT_MAINTENANCE_ADMISSION_ENABLED = true;
    private static final long DEFAULT_ELASTIC_WORK_DELAY_MS = 500L;
    private static final long DEFAULT_MAX_ELASTIC_WORK_DEFERRAL_MS = 3000L;
    private static final boolean DEFAULT_APP_STAMINA_ENABLED = true;
    private static final int DEFAULT_APP_STAMINA_MIN_SCORE = 65;
    private static final long DEFAULT_APP_STAMINA_RECENT_VISIBLE_MS = 10 * 60 * 1000L;
    private static final long DEFAULT_APP_STAMINA_WINDOW_MS = 5 * 60 * 1000L;
    private static final long DEFAULT_APP_STAMINA_ALARM_DELAY_MS = 1000L;
    private static final boolean DEFAULT_THREAD_PRIORITY_ENABLED = true;
    private static final boolean DEFAULT_THREAD_PRIORITY_BACKGROUND_DEMOTION_ENABLED = true;
    private static final boolean DEFAULT_USERSPACE_SCHEDULER_ENABLED = true;
    private static final long DEFAULT_SCHEDULER_SAMPLE_INTERVAL_MS = 500L;
    private static final long DEFAULT_SCHEDULER_DEBT_MAX_AGE_MS = 1000L;
    private static final long DEFAULT_SCHEDULER_RUNNABLE_DELAY_GUARDED_NS = 3_000_000L;
    private static final long DEFAULT_SCHEDULER_RUNNABLE_DELAY_PROTECTED_NS = 8_000_000L;
    private static final long DEFAULT_ENERGY_SAMPLE_MAX_AGE_MS = 30 * 60 * 1000L;
    private static final int DEFAULT_ENERGY_HIGH_BACKGROUND_PERCENT = 2;
    private static final int DEFAULT_ENERGY_HIGH_BACKGROUND_MAH_MILLI = 50;
    private static final boolean DEFAULT_APP_STAMINA_RESTRICTED_BUCKET_ENABLED = true;
    private static final boolean DEFAULT_APP_STAMINA_NETWORK_BLOCK_ENABLED = true;
    private static final int DEFAULT_APP_STAMINA_HARD_MIN_SCORE = 85;
    private static final int DEFAULT_APP_STAMINA_HARD_MIN_ENERGY_SCORE = 35;
    private static final PulseEngineConfig DEFAULT_CONFIG = new PulseEngineConfig(
            DEFAULT_ENABLED, DEFAULT_MODE, DEFAULT_LAUNCH_PROTECTION_MS,
            DEFAULT_TOP_APP_PROTECTION_MS, DEFAULT_GUARDED_THRESHOLD,
            DEFAULT_PROTECTED_THRESHOLD, DEFAULT_MIN_ELASTIC_BUDGET,
            DEFAULT_MAX_ELASTIC_BUDGET, DEFAULT_PROCESS_PSS_ADMISSION_ENABLED,
            DEFAULT_APP_GC_ADMISSION_ENABLED, DEFAULT_COMPACTION_ADMISSION_ENABLED,
            DEFAULT_FREEZER_ADMISSION_ENABLED, DEFAULT_TRIM_MEMORY_ADMISSION_ENABLED,
            DEFAULT_JOB_ADMISSION_ENABLED, DEFAULT_BROADCAST_ADMISSION_ENABLED,
            DEFAULT_MAINTENANCE_ADMISSION_ENABLED, DEFAULT_ELASTIC_WORK_DELAY_MS,
            DEFAULT_MAX_ELASTIC_WORK_DEFERRAL_MS, DEFAULT_APP_STAMINA_ENABLED,
            DEFAULT_APP_STAMINA_MIN_SCORE, DEFAULT_APP_STAMINA_RECENT_VISIBLE_MS,
            DEFAULT_APP_STAMINA_WINDOW_MS, DEFAULT_APP_STAMINA_ALARM_DELAY_MS,
            DEFAULT_THREAD_PRIORITY_ENABLED, DEFAULT_THREAD_PRIORITY_BACKGROUND_DEMOTION_ENABLED,
            DEFAULT_USERSPACE_SCHEDULER_ENABLED, DEFAULT_SCHEDULER_SAMPLE_INTERVAL_MS,
            DEFAULT_SCHEDULER_DEBT_MAX_AGE_MS, DEFAULT_SCHEDULER_RUNNABLE_DELAY_GUARDED_NS,
            DEFAULT_SCHEDULER_RUNNABLE_DELAY_PROTECTED_NS, DEFAULT_ENERGY_SAMPLE_MAX_AGE_MS,
            DEFAULT_ENERGY_HIGH_BACKGROUND_PERCENT, DEFAULT_ENERGY_HIGH_BACKGROUND_MAH_MILLI,
            DEFAULT_APP_STAMINA_RESTRICTED_BUCKET_ENABLED,
            DEFAULT_APP_STAMINA_NETWORK_BLOCK_ENABLED, DEFAULT_APP_STAMINA_HARD_MIN_SCORE,
            DEFAULT_APP_STAMINA_HARD_MIN_ENERGY_SCORE);

    final boolean mEnabled;
    final int mMode;
    final long mLaunchProtectionMs;
    final long mTopAppProtectionMs;
    final int mGuardedThreshold;
    final int mProtectedThreshold;
    final int mMinElasticBudget;
    final int mMaxElasticBudget;
    final boolean mProcessPssAdmissionEnabled;
    final boolean mAppGcAdmissionEnabled;
    final boolean mCompactionAdmissionEnabled;
    final boolean mFreezerAdmissionEnabled;
    final boolean mTrimMemoryAdmissionEnabled;
    final boolean mJobAdmissionEnabled;
    final boolean mBroadcastAdmissionEnabled;
    final boolean mMaintenanceAdmissionEnabled;
    final long mElasticWorkDelayMs;
    final long mMaxElasticWorkDeferralMs;
    final boolean mAppStaminaEnabled;
    final int mAppStaminaMinScore;
    final long mAppStaminaRecentVisibleMs;
    final long mAppStaminaWindowMs;
    final long mAppStaminaAlarmDelayMs;
    final boolean mThreadPriorityEnabled;
    final boolean mThreadPriorityBackgroundDemotionEnabled;
    final boolean mUserspaceSchedulerEnabled;
    final long mSchedulerSampleIntervalMs;
    final long mSchedulerDebtMaxAgeMs;
    final long mSchedulerRunnableDelayGuardedNs;
    final long mSchedulerRunnableDelayProtectedNs;
    final long mEnergySampleMaxAgeMs;
    final int mEnergyHighBackgroundPercent;
    final int mEnergyHighBackgroundMahMilli;
    final boolean mAppStaminaRestrictedBucketEnabled;
    final boolean mAppStaminaNetworkBlockEnabled;
    final int mAppStaminaHardMinScore;
    final int mAppStaminaHardMinEnergyScore;

    private PulseEngineConfig(boolean enabled, int mode, long launchProtectionMs,
            long topAppProtectionMs, int guardedThreshold, int protectedThreshold,
            int minElasticBudget, int maxElasticBudget, boolean processPssAdmissionEnabled,
            boolean appGcAdmissionEnabled, boolean compactionAdmissionEnabled,
            boolean freezerAdmissionEnabled, boolean trimMemoryAdmissionEnabled,
            boolean jobAdmissionEnabled, boolean broadcastAdmissionEnabled,
            boolean maintenanceAdmissionEnabled,
            long elasticWorkDelayMs, long maxElasticWorkDeferralMs, boolean appStaminaEnabled,
            int appStaminaMinScore, long appStaminaRecentVisibleMs, long appStaminaWindowMs,
            long appStaminaAlarmDelayMs, boolean threadPriorityEnabled,
            boolean threadPriorityBackgroundDemotionEnabled, boolean userspaceSchedulerEnabled,
            long schedulerSampleIntervalMs, long schedulerDebtMaxAgeMs,
            long schedulerRunnableDelayGuardedNs, long schedulerRunnableDelayProtectedNs,
            long energySampleMaxAgeMs,
            int energyHighBackgroundPercent, int energyHighBackgroundMahMilli,
            boolean appStaminaRestrictedBucketEnabled, boolean appStaminaNetworkBlockEnabled,
            int appStaminaHardMinScore, int appStaminaHardMinEnergyScore) {
        mEnabled = enabled;
        mMode = mode;
        mLaunchProtectionMs = launchProtectionMs;
        mTopAppProtectionMs = topAppProtectionMs;
        mGuardedThreshold = guardedThreshold;
        mProtectedThreshold = protectedThreshold;
        mMinElasticBudget = minElasticBudget;
        mMaxElasticBudget = maxElasticBudget;
        mProcessPssAdmissionEnabled = processPssAdmissionEnabled;
        mAppGcAdmissionEnabled = appGcAdmissionEnabled;
        mCompactionAdmissionEnabled = compactionAdmissionEnabled;
        mFreezerAdmissionEnabled = freezerAdmissionEnabled;
        mTrimMemoryAdmissionEnabled = trimMemoryAdmissionEnabled;
        mJobAdmissionEnabled = jobAdmissionEnabled;
        mBroadcastAdmissionEnabled = broadcastAdmissionEnabled;
        mMaintenanceAdmissionEnabled = maintenanceAdmissionEnabled;
        mElasticWorkDelayMs = elasticWorkDelayMs;
        mMaxElasticWorkDeferralMs = maxElasticWorkDeferralMs;
        mAppStaminaEnabled = appStaminaEnabled;
        mAppStaminaMinScore = appStaminaMinScore;
        mAppStaminaRecentVisibleMs = appStaminaRecentVisibleMs;
        mAppStaminaWindowMs = appStaminaWindowMs;
        mAppStaminaAlarmDelayMs = appStaminaAlarmDelayMs;
        mThreadPriorityEnabled = threadPriorityEnabled;
        mThreadPriorityBackgroundDemotionEnabled = threadPriorityBackgroundDemotionEnabled;
        mUserspaceSchedulerEnabled = userspaceSchedulerEnabled;
        mSchedulerSampleIntervalMs = schedulerSampleIntervalMs;
        mSchedulerDebtMaxAgeMs = schedulerDebtMaxAgeMs;
        mSchedulerRunnableDelayGuardedNs = schedulerRunnableDelayGuardedNs;
        mSchedulerRunnableDelayProtectedNs = schedulerRunnableDelayProtectedNs;
        mEnergySampleMaxAgeMs = energySampleMaxAgeMs;
        mEnergyHighBackgroundPercent = energyHighBackgroundPercent;
        mEnergyHighBackgroundMahMilli = energyHighBackgroundMahMilli;
        mAppStaminaRestrictedBucketEnabled = appStaminaRestrictedBucketEnabled;
        mAppStaminaNetworkBlockEnabled = appStaminaNetworkBlockEnabled;
        mAppStaminaHardMinScore = appStaminaHardMinScore;
        mAppStaminaHardMinEnergyScore = appStaminaHardMinEnergyScore;
    }

    static PulseEngineConfig defaultConfig() {
        return DEFAULT_CONFIG;
    }

    boolean isEnabled() {
        return mEnabled && mMode != MODE_OFF;
    }

    boolean isAdmissionEnabled() {
        return isEnabled() && mMode == MODE_ADMISSION;
    }

    void dump(PrintWriter pw) {
        pw.println("  config:");
        pw.println("    enabled=" + mEnabled);
        pw.println("    mode=" + modeToString(mMode));
        pw.println("    launchProtectionMs=" + mLaunchProtectionMs);
        pw.println("    topAppProtectionMs=" + mTopAppProtectionMs);
        pw.println("    guardedThreshold=" + mGuardedThreshold);
        pw.println("    protectedThreshold=" + mProtectedThreshold);
        pw.println("    minElasticBudget=" + mMinElasticBudget);
        pw.println("    maxElasticBudget=" + mMaxElasticBudget);
        pw.println("    processPssAdmissionEnabled=" + mProcessPssAdmissionEnabled);
        pw.println("    appGcAdmissionEnabled=" + mAppGcAdmissionEnabled);
        pw.println("    compactionAdmissionEnabled=" + mCompactionAdmissionEnabled);
        pw.println("    freezerAdmissionEnabled=" + mFreezerAdmissionEnabled);
        pw.println("    trimMemoryAdmissionEnabled=" + mTrimMemoryAdmissionEnabled);
        pw.println("    jobAdmissionEnabled=" + mJobAdmissionEnabled);
        pw.println("    broadcastAdmissionEnabled=" + mBroadcastAdmissionEnabled);
        pw.println("    maintenanceAdmissionEnabled=" + mMaintenanceAdmissionEnabled);
        pw.println("    elasticWorkDelayMs=" + mElasticWorkDelayMs);
        pw.println("    maxElasticWorkDeferralMs=" + mMaxElasticWorkDeferralMs);
        pw.println("    appStaminaEnabled=" + mAppStaminaEnabled);
        pw.println("    appStaminaMinScore=" + mAppStaminaMinScore);
        pw.println("    appStaminaRecentVisibleMs=" + mAppStaminaRecentVisibleMs);
        pw.println("    appStaminaWindowMs=" + mAppStaminaWindowMs);
        pw.println("    appStaminaAlarmDelayMs=" + mAppStaminaAlarmDelayMs);
        pw.println("    threadPriorityEnabled=" + mThreadPriorityEnabled);
        pw.println("    threadPriorityBackgroundDemotionEnabled="
                + mThreadPriorityBackgroundDemotionEnabled);
        pw.println("    userspaceSchedulerEnabled=" + mUserspaceSchedulerEnabled);
        pw.println("    schedulerSampleIntervalMs=" + mSchedulerSampleIntervalMs);
        pw.println("    schedulerDebtMaxAgeMs=" + mSchedulerDebtMaxAgeMs);
        pw.println("    schedulerRunnableDelayGuardedNs=" + mSchedulerRunnableDelayGuardedNs);
        pw.println("    schedulerRunnableDelayProtectedNs=" + mSchedulerRunnableDelayProtectedNs);
        pw.println("    energySampleMaxAgeMs=" + mEnergySampleMaxAgeMs);
        pw.println("    energyHighBackgroundPercent=" + mEnergyHighBackgroundPercent);
        pw.println("    energyHighBackgroundMahMilli=" + mEnergyHighBackgroundMahMilli);
        pw.println("    appStaminaRestrictedBucketEnabled="
                + mAppStaminaRestrictedBucketEnabled);
        pw.println("    appStaminaNetworkBlockEnabled=" + mAppStaminaNetworkBlockEnabled);
        pw.println("    appStaminaHardMinScore=" + mAppStaminaHardMinScore);
        pw.println("    appStaminaHardMinEnergyScore=" + mAppStaminaHardMinEnergyScore);
    }

    static String modeToString(int mode) {
        if (mode == MODE_OFF) {
            return "off";
        }
        if (mode == MODE_ADMISSION) {
            return "admission";
        }
        return "telemetry";
    }
}
