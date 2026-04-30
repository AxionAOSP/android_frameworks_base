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

import android.app.AxBoostFwk;

import com.android.server.thermal.AxAdvancedThermalMitigationConfig;

final class AxThermalBoostPolicy {

    private static final int UCLAMP_MAX_DEFAULT = 100;
    private static final String CPU_MAX_FLOOR = "9999999";

    private volatile int mThermalLevel = 0;
    private volatile int mThermalCpuCap = -1;
    private volatile int mThermalGpuCap = -1;
    private volatile int mThermalUclampMaxCeiling = UCLAMP_MAX_DEFAULT;

    AxThermalBoostPolicy() {}

    int getThermalLevel() {
        return mThermalLevel;
    }

    int getThermalUclampMaxCeiling() {
        return mThermalUclampMaxCeiling;
    }

    boolean updateThermalState(int level, int cpuCap, int gpuCap) {
        mThermalLevel = level;
        mThermalCpuCap = cpuCap;
        mThermalGpuCap = gpuCap;
        int ceiling = cpuCapToUclampMax(cpuCap);
        if (ceiling != mThermalUclampMaxCeiling) {
            mThermalUclampMaxCeiling = ceiling;
            return true;
        }
        return false;
    }

    String resolveCpuMaxTarget(String defaultMax, int cluster) {
        long target = parsePositiveLong(defaultMax, Long.MAX_VALUE);
        long thermalCap = getThermalCpuMaxKhz(cluster);
        if (thermalCap > 0L) {
            target = Math.min(target, thermalCap);
        }
        if (target <= 0L || target == Long.MAX_VALUE) {
            return CPU_MAX_FLOOR;
        }
        return String.valueOf(target);
    }

    int getThermalGpuMaxOppIndex() {
        AxAdvancedThermalMitigationConfig.GpuLevel level =
                AxPerfConfig.getAtmc().getGpuLevel(mThermalGpuCap);
        return level != null ? level.max : -1;
    }

    long getThermalCpuMinKhz(int cluster) {
        AxAdvancedThermalMitigationConfig.CpuLevel level =
                AxPerfConfig.getAtmc().getCpuLevel(mThermalCpuCap);
        if (level == null) return 0L;
        int min;
        switch (cluster) {
            case AxBoostFwk.PERF_CLUSTER_LITTLE:
                min = level.littleMin;
                break;
            case AxBoostFwk.PERF_CLUSTER_BIG:
                min = level.bigMin;
                break;
            case AxBoostFwk.PERF_CLUSTER_PRIME:
                min = level.primeMin > 0 ? level.primeMin : level.titaniumMin;
                if (min <= 0) min = level.bigMin;
                break;
            default:
                min = -1;
                break;
        }
        return toCpuKhz(min);
    }

    long getThermalCpuMaxKhz(int cluster) {
        AxAdvancedThermalMitigationConfig.CpuLevel level =
                AxPerfConfig.getAtmc().getCpuLevel(mThermalCpuCap);
        if (level == null) return 0L;
        int max;
        switch (cluster) {
            case AxBoostFwk.PERF_CLUSTER_LITTLE:
                max = level.littleMax;
                break;
            case AxBoostFwk.PERF_CLUSTER_BIG:
                max = level.bigMax;
                break;
            case AxBoostFwk.PERF_CLUSTER_PRIME:
                max = level.primeMax > 0 ? level.primeMax : level.titaniumMax;
                if (max <= 0) max = level.bigMax;
                break;
            default:
                max = -1;
                break;
        }
        return toCpuKhz(max);
    }

    private static int cpuCapToUclampMax(int cpuCap) {
        if (cpuCap <= 0) return UCLAMP_MAX_DEFAULT;
        int v = UCLAMP_MAX_DEFAULT - (cpuCap * 3);
        if (v < 20) v = 20;
        if (v > UCLAMP_MAX_DEFAULT) v = UCLAMP_MAX_DEFAULT;
        return v;
    }

    private static long parsePositiveLong(String value, long def) {
        if (value == null) return def;
        try {
            long parsed = Long.parseLong(value.trim());
            return parsed > 0L ? parsed : def;
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static long toCpuKhz(int value) {
        if (value <= 0) return 0L;
        return value < 10000 ? (long) value * 1000L : value;
    }
}
