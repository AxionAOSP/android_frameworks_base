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

import static android.app.ActivityManager.RESTRICTION_LEVEL_ADAPTIVE_BUCKET;
import static android.app.ActivityManager.RESTRICTION_LEVEL_BACKGROUND_RESTRICTED;
import static android.app.ActivityManager.RESTRICTION_LEVEL_EXEMPTED;
import static android.app.ActivityManager.RESTRICTION_LEVEL_RESTRICTED_BUCKET;

import android.annotation.Nullable;
import android.os.Process;
import android.util.SparseArray;

import java.io.PrintWriter;

final class PulseEngineAppStaminaPolicy {
    static final int WORK_TYPE_JOB = 1;
    static final int WORK_TYPE_BROADCAST = 2;
    static final int WORK_TYPE_ALARM = 3;
    static final int WORK_TYPE_PROCESS = 4;

    private static final int MAX_UID_STATES = 512;

    private final Object mLock = new Object();
    private final SparseArray<UidState> mUidStates = new SparseArray<>();
    private final PulseEngineEnergyLedger mEnergyLedger;
    private final PulseEngineAppStaminaStats mStats = new PulseEngineAppStaminaStats();

    @Nullable private volatile AppRestrictionController mAppRestrictionController;
    private int mTopUid = -1;
    @Nullable private String mTopProcessName;
    @Nullable private UidState mLastDecision;

    PulseEngineAppStaminaPolicy(PulseEngineEnergyLedger ledger) {
        mEnergyLedger = ledger;
    }

    void setAppRestrictionController(AppRestrictionController controller) {
        mAppRestrictionController = controller;
    }

    void onTopAppChanged(@Nullable String processName, int uid, long now) {
        synchronized (mLock) {
            mTopUid = uid;
            mTopProcessName = processName;
            if (Process.isApplicationUid(uid)) {
                final UidState state = getOrCreateStateLocked(uid, processName, now);
                state.noteUserVisible(processName, now);
                mLastDecision = state;
                mStats.onUserVisible();
            }
        }
    }

    void onActivityLaunched(@Nullable String processName, int uid, long now) {
        if (!Process.isApplicationUid(uid)) {
            return;
        }
        synchronized (mLock) {
            final UidState state = getOrCreateStateLocked(uid, processName, now);
            state.noteUserVisible(processName, now);
            mLastDecision = state;
            mStats.onUserVisible();
        }
    }

    boolean shouldDeferWork(PulseEngineConfig config, PulseEnginePolicy policy, int uid,
            @Nullable String packageName, int workType, long now) {
        if (!config.mAppStaminaEnabled || !config.isAdmissionEnabled()
                || policy == null || !policy.isAdmissionActive()) {
            return false;
        }
        if (!Process.isApplicationUid(uid) || uid == mTopUid) {
            return false;
        }
        final boolean exempt = isRecentlyVisible(uid, now, config.mAppStaminaRecentVisibleMs);
        mStats.onCheck(workType);
        if (exempt) {
            mStats.onExempt();
            return false;
        }
        final boolean defer;
        synchronized (mLock) {
            final UidState state = getOrCreateStateLocked(uid, packageName, now);
            state.noteWork(packageName, workType, now, config.mAppStaminaWindowMs);
            final int restrictionLevel = RESTRICTION_LEVEL_ADAPTIVE_BUCKET;
            final int energyScore = mEnergyLedger.getUidEnergyScore(config, uid);
            final int score = state.computeScore(policy, restrictionLevel, energyScore, now,
                    config.mAppStaminaRecentVisibleMs);
            state.mLastRestrictionLevel = restrictionLevel;
            state.mLastEnergyScore = energyScore;
            state.mLastScore = score;
            state.mLastDecisionUptimeMillis = now;
            defer = score >= config.mAppStaminaMinScore;
            state.mLastDecisionDeferred = defer;
            if (defer) {
                state.mDeferredCount++;
            }
            mLastDecision = state;
            pruneLocked(now);
        }
        if (defer) {
            mStats.onDeferred(workType);
        }
        return defer;
    }

    boolean shouldDemoteBackgroundProcess(PulseEngineConfig config, PulseEnginePolicy policy,
            int uid, @Nullable String processName, long now) {
        if (!config.mThreadPriorityBackgroundDemotionEnabled) {
            return false;
        }
        return shouldDeferWork(config, policy, uid, processName, WORK_TYPE_PROCESS, now);
    }

    boolean shouldApplyRestrictedBucket(PulseEngineConfig config, int uid,
            @Nullable String packageName, long now) {
        if (!config.mAppStaminaRestrictedBucketEnabled || packageName == null
                || !Process.isApplicationUid(uid) || uid == mTopUid
                || isRecentlyVisible(uid, now, config.mAppStaminaRecentVisibleMs)) {
            return false;
        }
        synchronized (mLock) {
            final UidState state = mUidStates.get(uid);
            return state != null
                    && state.mLastScore >= config.mAppStaminaHardMinScore
                    && state.mLastEnergyScore >= config.mAppStaminaHardMinEnergyScore;
        }
    }

    boolean applyRestrictedBucket(int uid, @Nullable String packageName) {
        final AppRestrictionController controller = mAppRestrictionController;
        if (controller == null || packageName == null) {
            return false;
        }
        if (controller.restrictAppForPulseEngine(packageName, uid)) {
            mStats.onRestrictedBucketApplied();
            return true;
        }
        return false;
    }

    boolean isNetworkingBlocked(PulseEngineConfig config, int uid, long now) {
        if (!config.mAppStaminaNetworkBlockEnabled || !Process.isApplicationUid(uid)
                || uid == mTopUid || isRecentlyVisible(uid, now,
                        config.mAppStaminaRecentVisibleMs)) {
            return false;
        }
        synchronized (mLock) {
            final UidState state = mUidStates.get(uid);
            return state != null
                    && state.mLastScore >= config.mAppStaminaHardMinScore
                    && state.mLastEnergyScore >= config.mAppStaminaHardMinEnergyScore;
        }
    }

    long getAlarmDelayMillis(PulseEngineConfig config) {
        return config.mAppStaminaAlarmDelayMs;
    }

    void dump(PrintWriter pw) {
        pw.println("  appStamina:");
        pw.println("    topUid=" + mTopUid);
        pw.println("    topProcess=" + mTopProcessName);
        synchronized (mLock) {
            pw.println("    trackedUidCount=" + mUidStates.size());
            if (mLastDecision != null) {
                mLastDecision.dump(pw);
            }
        }
        mStats.dump(pw);
    }

    private boolean isRecentlyVisible(int uid, long now, long recentVisibleMs) {
        synchronized (mLock) {
            final UidState state = mUidStates.get(uid);
            return state != null && state.mLastVisibleUptimeMillis > 0L
                    && now >= state.mLastVisibleUptimeMillis
                    && now - state.mLastVisibleUptimeMillis <= recentVisibleMs;
        }
    }

    private UidState getOrCreateStateLocked(int uid, @Nullable String packageName, long now) {
        UidState state = mUidStates.get(uid);
        if (state == null) {
            state = new UidState(uid, packageName, now);
            mUidStates.put(uid, state);
            return state;
        }
        if (packageName != null) {
            state.mPackageName = packageName;
        }
        state.mLastSeenUptimeMillis = now;
        return state;
    }

    private void pruneLocked(long now) {
        if (mUidStates.size() <= MAX_UID_STATES) {
            return;
        }
        int oldestIndex = -1;
        long oldest = Long.MAX_VALUE;
        for (int i = 0; i < mUidStates.size(); i++) {
            final UidState state = mUidStates.valueAt(i);
            if (state.mUid == mTopUid) {
                continue;
            }
            if (state.mLastSeenUptimeMillis < oldest) {
                oldest = state.mLastSeenUptimeMillis;
                oldestIndex = i;
            }
        }
        if (oldestIndex >= 0 && now >= oldest) {
            mUidStates.removeAt(oldestIndex);
        }
    }

    static String workTypeToString(int workType) {
        if (workType == WORK_TYPE_JOB) {
            return "job";
        }
        if (workType == WORK_TYPE_BROADCAST) {
            return "broadcast";
        }
        if (workType == WORK_TYPE_ALARM) {
            return "alarm";
        }
        if (workType == WORK_TYPE_PROCESS) {
            return "process";
        }
        return "unknown";
    }

    private static final class UidState {
        final int mUid;
        @Nullable String mPackageName;
        long mLastSeenUptimeMillis;
        long mWindowStartUptimeMillis;
        long mLastVisibleUptimeMillis;
        long mLastWorkUptimeMillis;
        long mLastDecisionUptimeMillis;
        int mElasticWorkScore;
        int mLastWorkType;
        int mLastScore;
        int mLastEnergyScore;
        int mLastRestrictionLevel;
        int mDeferredCount;
        boolean mLastDecisionDeferred;

        UidState(int uid, @Nullable String packageName, long now) {
            mUid = uid;
            mPackageName = packageName;
            mLastSeenUptimeMillis = now;
            mWindowStartUptimeMillis = now;
        }

        void noteUserVisible(@Nullable String packageName, long now) {
            if (packageName != null) {
                mPackageName = packageName;
            }
            mLastVisibleUptimeMillis = now;
            mLastSeenUptimeMillis = now;
            mElasticWorkScore = Math.max(0, mElasticWorkScore - 30);
        }

        void noteWork(@Nullable String packageName, int workType, long now, long windowMs) {
            if (packageName != null) {
                mPackageName = packageName;
            }
            if (now - mWindowStartUptimeMillis > windowMs) {
                mElasticWorkScore /= 2;
                mWindowStartUptimeMillis = now;
            }
            mLastSeenUptimeMillis = now;
            mLastWorkUptimeMillis = now;
            mLastWorkType = workType;
            mElasticWorkScore = Math.min(100, mElasticWorkScore + workWeight(workType));
        }

        int computeScore(PulseEnginePolicy policy, int restrictionLevel, int energyScore, long now,
                long recentVisibleMs) {
            final int usefulnessCost = usefulnessCost(now, recentVisibleMs);
            final int restrictionCost = restrictionCost(restrictionLevel);
            int score = mElasticWorkScore + usefulnessCost + restrictionCost + energyScore;
            if (policy.mState == PulseEnginePolicy.STATE_PROTECTED) {
                score += 15;
            } else if (policy.mState == PulseEnginePolicy.STATE_GUARDED) {
                score += 8;
            }
            score += policy.mPressureScore / 4;
            return clamp(score, 0, 100);
        }

        void dump(PrintWriter pw) {
            pw.println("    lastUid=" + mUid);
            pw.println("    lastPackage=" + mPackageName);
            pw.println("    lastWorkType=" + workTypeToString(mLastWorkType));
            pw.println("    lastScore=" + mLastScore);
            pw.println("    lastEnergyScore=" + mLastEnergyScore);
            pw.println("    lastRestrictionLevel=" + mLastRestrictionLevel);
            pw.println("    lastDecisionDeferred=" + mLastDecisionDeferred);
            pw.println("    deferredCount=" + mDeferredCount);
            pw.println("    elasticWorkScore=" + mElasticWorkScore);
            pw.println("    lastVisibleUptimeMillis=" + mLastVisibleUptimeMillis);
            pw.println("    lastWorkUptimeMillis=" + mLastWorkUptimeMillis);
            pw.println("    lastDecisionUptimeMillis=" + mLastDecisionUptimeMillis);
        }

        private int usefulnessCost(long now, long recentVisibleMs) {
            if (mLastVisibleUptimeMillis <= 0L || now < mLastVisibleUptimeMillis) {
                return 35;
            }
            final long age = now - mLastVisibleUptimeMillis;
            if (age <= recentVisibleMs) {
                return 0;
            }
            if (age <= recentVisibleMs * 6L) {
                return 15;
            }
            return 35;
        }

        private static int workWeight(int workType) {
            if (workType == WORK_TYPE_JOB) {
                return 18;
            }
            if (workType == WORK_TYPE_BROADCAST) {
                return 12;
            }
            if (workType == WORK_TYPE_ALARM) {
                return 22;
            }
            if (workType == WORK_TYPE_PROCESS) {
                return 10;
            }
            return 8;
        }

        private static int restrictionCost(int level) {
            if (level >= RESTRICTION_LEVEL_BACKGROUND_RESTRICTED) {
                return 35;
            }
            if (level >= RESTRICTION_LEVEL_RESTRICTED_BUCKET) {
                return 25;
            }
            if (level >= RESTRICTION_LEVEL_ADAPTIVE_BUCKET) {
                return 10;
            }
            if (level <= RESTRICTION_LEVEL_EXEMPTED) {
                return -20;
            }
            return 0;
        }

        private static int clamp(int value, int min, int max) {
            if (value < min) {
                return min;
            }
            if (value > max) {
                return max;
            }
            return value;
        }
    }
}
