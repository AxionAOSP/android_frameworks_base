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

final class PulseEngineBudgetSolver {
    PulseEnginePolicy solve(PulseEngineConfig config, int phase, int perceptionScore,
            int pressureScore, long uptimeMillis) {
        final int demand = clamp(perceptionScore + pressureScore, 0, 100);
        final int state;
        if (demand >= config.mProtectedThreshold) {
            state = PulseEnginePolicy.STATE_PROTECTED;
        } else if (demand >= config.mGuardedThreshold) {
            state = PulseEnginePolicy.STATE_GUARDED;
        } else {
            state = PulseEnginePolicy.STATE_OPEN;
        }
        final int span = config.mMaxElasticBudget - config.mMinElasticBudget;
        final int budget = config.mMaxElasticBudget - (span * demand / 100);
        return new PulseEnginePolicy(config.mEnabled, config.mMode, phase, state,
                perceptionScore, pressureScore, clamp(budget, config.mMinElasticBudget,
                config.mMaxElasticBudget), uptimeMillis);
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
