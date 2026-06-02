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

final class PulseEnginePhaseModel {
    static final int PHASE_IDLE = 0;
    static final int PHASE_TOP_APP_TRANSITION = 1;
    static final int PHASE_LAUNCH = 2;

    int resolve(PulseEngineSignalSnapshot snapshot, PulseEngineConfig config, long now) {
        if (snapshot.mLaunchActive) {
            return PHASE_LAUNCH;
        }
        if (isRecent(snapshot.mLastLaunchStartedUptimeMillis, config.mLaunchProtectionMs, now)
                || isRecent(snapshot.mLastLaunchFinishedUptimeMillis,
                        config.mLaunchProtectionMs, now)) {
            return PHASE_LAUNCH;
        }
        if (isRecent(snapshot.mTopAppChangedUptimeMillis, config.mTopAppProtectionMs, now)) {
            return PHASE_TOP_APP_TRANSITION;
        }
        return PHASE_IDLE;
    }

    static String phaseToString(int phase) {
        if (phase == PHASE_LAUNCH) {
            return "launch";
        }
        if (phase == PHASE_TOP_APP_TRANSITION) {
            return "top_app_transition";
        }
        return "idle";
    }

    static boolean isRecent(long timestampMillis, long windowMillis, long now) {
        return timestampMillis > 0L && now >= timestampMillis
                && now - timestampMillis <= windowMillis;
    }
}
