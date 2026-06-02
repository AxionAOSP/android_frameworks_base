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

final class PulseEnginePolicy {
    static final int STATE_OPEN = 0;
    static final int STATE_GUARDED = 1;
    static final int STATE_PROTECTED = 2;

    final boolean mEnabled;
    final int mMode;
    final int mPhase;
    final int mState;
    final int mPerceptionScore;
    final int mPressureScore;
    final int mElasticBudgetPercent;
    final long mUptimeMillis;

    PulseEnginePolicy(boolean enabled, int mode, int phase, int state, int perceptionScore,
            int pressureScore, int elasticBudgetPercent, long uptimeMillis) {
        mEnabled = enabled;
        mMode = mode;
        mPhase = phase;
        mState = state;
        mPerceptionScore = perceptionScore;
        mPressureScore = pressureScore;
        mElasticBudgetPercent = elasticBudgetPercent;
        mUptimeMillis = uptimeMillis;
    }

    static PulseEnginePolicy disabled(long uptimeMillis) {
        return new PulseEnginePolicy(false, PulseEngineConfig.MODE_OFF,
                PulseEnginePhaseModel.PHASE_IDLE, STATE_OPEN, 0, 0, 100, uptimeMillis);
    }

    boolean hasSameDecision(PulseEnginePolicy other) {
        return other != null
                && mEnabled == other.mEnabled
                && mMode == other.mMode
                && mPhase == other.mPhase
                && mState == other.mState
                && mElasticBudgetPercent == other.mElasticBudgetPercent;
    }

    boolean isAdmissionActive() {
        return mEnabled && mMode == PulseEngineConfig.MODE_ADMISSION && mState != STATE_OPEN;
    }

    boolean isInteractiveAdmissionActive() {
        return isAdmissionActive() && mPhase != PulseEnginePhaseModel.PHASE_IDLE;
    }

    void dump(PrintWriter pw) {
        pw.println("  policy:");
        pw.println("    enabled=" + mEnabled);
        pw.println("    mode=" + PulseEngineConfig.modeToString(mMode));
        pw.println("    phase=" + PulseEnginePhaseModel.phaseToString(mPhase));
        pw.println("    state=" + stateToString(mState));
        pw.println("    perceptionScore=" + mPerceptionScore);
        pw.println("    pressureScore=" + mPressureScore);
        pw.println("    elasticBudgetPercent=" + mElasticBudgetPercent);
        pw.println("    uptimeMillis=" + mUptimeMillis);
    }

    private static String stateToString(int state) {
        if (state == STATE_PROTECTED) {
            return "protected";
        }
        if (state == STATE_GUARDED) {
            return "guarded";
        }
        return "open";
    }
}
