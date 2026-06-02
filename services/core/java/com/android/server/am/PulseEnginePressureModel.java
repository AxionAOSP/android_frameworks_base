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

import static com.android.internal.app.procstats.ProcessStats.ADJ_MEM_FACTOR_CRITICAL;
import static com.android.internal.app.procstats.ProcessStats.ADJ_MEM_FACTOR_LOW;
import static com.android.internal.app.procstats.ProcessStats.ADJ_MEM_FACTOR_MODERATE;

import android.os.PowerManager;

final class PulseEnginePressureModel {
    int compute(int memoryPressure, int thermalStatus, boolean interactive) {
        int score = memoryPressureScore(memoryPressure) + thermalScore(thermalStatus);
        if (!interactive) {
            score += 10;
        }
        return Math.min(score, 100);
    }

    private static int memoryPressureScore(int memoryPressure) {
        if (memoryPressure >= ADJ_MEM_FACTOR_CRITICAL) {
            return 55;
        }
        if (memoryPressure >= ADJ_MEM_FACTOR_LOW) {
            return 30;
        }
        if (memoryPressure >= ADJ_MEM_FACTOR_MODERATE) {
            return 10;
        }
        return 0;
    }

    private static int thermalScore(int thermalStatus) {
        if (thermalStatus >= PowerManager.THERMAL_STATUS_CRITICAL) {
            return 55;
        }
        if (thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE) {
            return 35;
        }
        if (thermalStatus >= PowerManager.THERMAL_STATUS_MODERATE) {
            return 15;
        }
        if (thermalStatus >= PowerManager.THERMAL_STATUS_LIGHT) {
            return 5;
        }
        return 0;
    }
}
