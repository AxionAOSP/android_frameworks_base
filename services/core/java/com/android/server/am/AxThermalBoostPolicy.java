/*
 * Copyright (C) 2025-2026 AxionOS
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

import com.android.server.thermal.AxAdvancedThermalMitigationConfig;

final class AxThermalBoostPolicy {
    static final int PERF_CLUSTER_LITTLE = 0;
    static final int PERF_CLUSTER_BIG = 1;
    static final int PERF_CLUSTER_PRIME = 2;
    static final int PERF_CLUSTER_MID = 3;

    private volatile int mThermalCpuCap = -1;

    AxThermalBoostPolicy() {}

    void updateThermalState(int cpuCap) {
        mThermalCpuCap = cpuCap;
    }

    long getThermalCpuMinKhz(int cluster) {
        final AxAdvancedThermalMitigationConfig.CpuLevel level =
                AxPerfConfig.getAtmc().getCpuLevel(mThermalCpuCap);
        if (level == null) {
            return 0L;
        }
        final int min;
        switch (cluster) {
            case PERF_CLUSTER_LITTLE:
                min = level.littleMin;
                break;
            case PERF_CLUSTER_MID:
                min = level.midMin;
                break;
            case PERF_CLUSTER_BIG:
                min = level.bigMin;
                break;
            case PERF_CLUSTER_PRIME:
                min = primeMin(level);
                break;
            default:
                min = -1;
                break;
        }
        return toCpuKhz(min);
    }

    long getThermalCpuMaxKhz(int cluster) {
        final AxAdvancedThermalMitigationConfig.CpuLevel level =
                AxPerfConfig.getAtmc().getCpuLevel(mThermalCpuCap);
        if (level == null) {
            return 0L;
        }
        final int max;
        switch (cluster) {
            case PERF_CLUSTER_LITTLE:
                max = level.littleMax;
                break;
            case PERF_CLUSTER_MID:
                max = level.midMax;
                break;
            case PERF_CLUSTER_BIG:
                max = level.bigMax;
                break;
            case PERF_CLUSTER_PRIME:
                max = primeMax(level);
                break;
            default:
                max = -1;
                break;
        }
        return toCpuKhz(max);
    }

    private static int primeMin(AxAdvancedThermalMitigationConfig.CpuLevel level) {
        final int primeMin = level.primeMin > 0 ? level.primeMin : level.titaniumMin;
        return primeMin > 0 ? primeMin : level.bigMin;
    }

    private static int primeMax(AxAdvancedThermalMitigationConfig.CpuLevel level) {
        final int primeMax = level.primeMax > 0 ? level.primeMax : level.titaniumMax;
        return primeMax > 0 ? primeMax : level.bigMax;
    }

    private static long toCpuKhz(int value) {
        if (value <= 0) {
            return 0L;
        }
        return value < 10000 ? (long) value * 1000L : value;
    }
}
