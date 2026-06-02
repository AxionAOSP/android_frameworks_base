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

final class PulseEngineSchedulerDebt {
    static final int TYPE_NONE = 0;
    static final int TYPE_CPU_RUNNABLE = 1;
    static final int TYPE_UNAVAILABLE = 2;

    private static final String TYPE_NONE_NAME = "none";
    private static final String TYPE_CPU_RUNNABLE_NAME = "cpu_runnable";
    private static final String TYPE_UNAVAILABLE_NAME = "unavailable";
    private static final int MAX_DEBT_SCORE = 100;

    private PulseEngineSchedulerDebt() {
    }

    static int computeRunnableScore(PulseEngineConfig config, long runnableDelayDeltaNanos) {
        if (runnableDelayDeltaNanos <= 0L) {
            return 0;
        }
        if (runnableDelayDeltaNanos >= config.mSchedulerRunnableDelayProtectedNs) {
            return MAX_DEBT_SCORE;
        }
        return (int) Math.min(MAX_DEBT_SCORE,
                runnableDelayDeltaNanos * MAX_DEBT_SCORE
                        / config.mSchedulerRunnableDelayProtectedNs);
    }

    static int classify(PulseEngineConfig config, PulseEnginePolicy policy,
            long runnableDelayDeltaNanos) {
        if (policy == null || !policy.isInteractiveAdmissionActive()) {
            return TYPE_NONE;
        }
        if (runnableDelayDeltaNanos >= config.mSchedulerRunnableDelayGuardedNs) {
            return TYPE_CPU_RUNNABLE;
        }
        return TYPE_NONE;
    }

    static String typeToString(int type) {
        if (type == TYPE_CPU_RUNNABLE) {
            return TYPE_CPU_RUNNABLE_NAME;
        }
        if (type == TYPE_UNAVAILABLE) {
            return TYPE_UNAVAILABLE_NAME;
        }
        return TYPE_NONE_NAME;
    }
}
