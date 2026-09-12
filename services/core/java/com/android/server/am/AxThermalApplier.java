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

import android.os.FileUtils;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.util.Slog;

import com.android.server.LocalServices;
import com.android.server.kernel.AxKernelManagerService;
import com.android.server.thermal.AxAdvancedThermalMitigationConfig;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;

public final class AxThermalApplier {
    private static final String TAG = "AxThermalApplier";
    private static final String ATCM_STATE_PATH = "/proc/ax_atcm/state";
    private static final long RETRY_DELAY_MS = 10_000L;

    /*
     * Direct userspace sinks, used when the ax_atcm kernel module is not
     * available (e.g. the sm8735 Bazel/kleaf kernel does not build it).
     */
    private static final String CPUFREQ_BASE = "/sys/devices/system/cpu/cpufreq";
    private static final String GPU_DEVFREQ_BASE = "/sys/class/kgsl/kgsl-3d0/devfreq";
    private static final int[] CLUSTER_ORDER_4 = {
            AxThermalBoostPolicy.PERF_CLUSTER_LITTLE,
            AxThermalBoostPolicy.PERF_CLUSTER_MID,
            AxThermalBoostPolicy.PERF_CLUSTER_BIG,
            AxThermalBoostPolicy.PERF_CLUSTER_PRIME
    };
    private static final int[] CLUSTER_ORDER_3 = {
            AxThermalBoostPolicy.PERF_CLUSTER_LITTLE,
            AxThermalBoostPolicy.PERF_CLUSTER_BIG,
            AxThermalBoostPolicy.PERF_CLUSTER_PRIME
    };
    private static final int[] CLUSTER_ORDER_2 = {
            AxThermalBoostPolicy.PERF_CLUSTER_LITTLE,
            AxThermalBoostPolicy.PERF_CLUSTER_BIG
    };
    private static final int[] CLUSTER_ORDER_1 = {
            AxThermalBoostPolicy.PERF_CLUSTER_LITTLE
    };

    private final Object mLock = new Object();
    private final AxThermalBoostPolicy mPolicy = new AxThermalBoostPolicy();
    private final StringBuilder mBuffer = new StringBuilder(128);
    private final HashMap<String, String> mSavedValues = new HashMap<>();
    private final HashMap<String, String> mAppliedValues = new HashMap<>();
    private List<ClusterSink> mClusterSinks;
    private long mRetryUptimeMs;
    private boolean mUnavailableLogged;

    public void updateThermalState(int level, int cpuCap, int gpuCap, int boostCap) {
        mPolicy.updateThermalState(cpuCap);
        final AxBurstEngineImpl engine = LocalServices.getService(AxBurstEngineImpl.class);
        if (engine != null) {
            engine.setThermalLevel(level);
        }
        synchronized (mLock) {
            if (new File(ATCM_STATE_PATH).exists()) {
                writeStateLocked(level, cpuCap, gpuCap, boostCap);
            } else {
                applyCpuCapsLocked(cpuCap);
                applyGpuCapsLocked(gpuCap);
            }
        }
    }

    /*
     * ax_atcm sink, kept for devices shipping the kernel module.
     */
    private void writeStateLocked(int level, int cpuCap, int gpuCap, int boostCap) {
        final long now = SystemClock.uptimeMillis();
        if (now < mRetryUptimeMs) {
            return;
        }

        mBuffer.setLength(0);
        mBuffer.append(Math.max(level, 0));
        mBuffer.append(' ').append(cpuCap);
        mBuffer.append(' ').append(gpuCap);
        mBuffer.append(' ').append(boostCap);
        appendClusterFloor(mBuffer, level, cpuCap, AxThermalBoostPolicy.PERF_CLUSTER_LITTLE);
        appendClusterFloor(mBuffer, level, cpuCap, AxThermalBoostPolicy.PERF_CLUSTER_BIG);
        appendClusterFloor(mBuffer, level, cpuCap, AxThermalBoostPolicy.PERF_CLUSTER_PRIME);
        mBuffer.append('\n');

        try {
            FileUtils.stringToFile(ATCM_STATE_PATH, mBuffer.toString());
            mRetryUptimeMs = 0L;
            mUnavailableLogged = false;
        } catch (IOException | RuntimeException e) {
            mRetryUptimeMs = now + RETRY_DELAY_MS;
            if (!mUnavailableLogged) {
                Slog.w(TAG, "atcm state unavailable: " + e.getMessage());
                mUnavailableLogged = true;
            }
        }
    }

    private void appendClusterFloor(StringBuilder out, int level, int cpuCap, int cluster) {
        long floor = 0L;
        long ceiling = 0L;
        if (level > 0 && cpuCap >= 0) {
            final AxAdvancedThermalMitigationConfig.CpuClusterPath path = findClusterPath(cluster);
            floor = path != null && path.min > 0 ? path.min : 0L;
            ceiling = path != null && path.max > 0 ? path.max : 0L;
            if (path != null) {
                final long userMin = userFrequency(path.minPath);
                final long userMax = userFrequency(path.maxPath);
                if (userMin > 0L) {
                    floor = Math.max(floor, userMin);
                }
                if (userMax > 0L) {
                    ceiling = ceiling > 0L ? Math.min(ceiling, userMax) : userMax;
                }
            }
            final long thermalMin = mPolicy.getThermalCpuMinKhz(cluster);
            final long thermalMax = mPolicy.getThermalCpuMaxKhz(cluster);
            if (thermalMin > 0L) {
                floor = Math.max(floor, thermalMin);
            }
            if (thermalMax > 0L) {
                ceiling = ceiling > 0L ? Math.min(ceiling, thermalMax) : thermalMax;
            }
            if (floor > 0L && ceiling > 0L && floor > ceiling) {
                floor = ceiling;
            }
        }
        out.append(' ').append(toInt(floor));
    }

    /*
     * Direct userspace sink. Cluster paths come from <cpu_clusters> when the
     * config provides them, otherwise they are discovered from cpufreq sysfs.
     */
    private void applyCpuCapsLocked(int cpuCap) {
        for (ClusterSink sink : getClusterSinksLocked()) {
            final long thermalMin = mPolicy.getThermalCpuMinKhz(sink.cluster);
            final long thermalMax = mPolicy.getThermalCpuMaxKhz(sink.cluster);
            final long userMin = userFrequency(sink.minPath);
            final long userMax = userFrequency(sink.maxPath);
            if (cpuCap <= 0 || (sink.baseMin <= 0L && sink.baseMax <= 0L
                    && thermalMin <= 0L && thermalMax <= 0L)) {
                restoreNode(sink.minPath, userMin);
                restoreNode(sink.maxPath, userMax);
                continue;
            }
            long floor = Math.max(sink.baseMin, Math.max(thermalMin, userMin));
            final long ceiling = minPositive(sink.baseMax, thermalMax, userMax);
            if (floor > 0L && ceiling > 0L && floor > ceiling) {
                floor = ceiling;
            }
            if (ceiling > 0L) {
                writeNode(sink.maxPath, ceiling);
            }
            if (floor > 0L) {
                writeNode(sink.minPath, floor);
            }
        }
    }

    private void applyGpuCapsLocked(int gpuCap) {
        final String minPath = GPU_DEVFREQ_BASE + "/min_freq";
        final String maxPath = GPU_DEVFREQ_BASE + "/max_freq";
        if (!new File(minPath).exists() || !new File(maxPath).exists()) {
            return;
        }
        final AxAdvancedThermalMitigationConfig.GpuLevel level =
                AxPerfConfig.getAtmc().getGpuLevel(gpuCap);
        final long thermalMin = level != null ? toHz(level.min) : 0L;
        final long thermalMax = level != null ? toHz(level.max) : 0L;
        if (gpuCap < 0 || (thermalMin <= 0L && thermalMax <= 0L)) {
            restoreNode(minPath, userFrequency(minPath));
            restoreNode(maxPath, userFrequency(maxPath));
            return;
        }
        long floor = Math.max(thermalMin, userFrequency(minPath));
        final long ceiling = minPositive(thermalMax, userFrequency(maxPath));
        if (floor > 0L && ceiling > 0L && floor > ceiling) {
            floor = ceiling;
        }
        if (ceiling > 0L) {
            writeNode(maxPath, ceiling);
        }
        if (floor > 0L) {
            writeNode(minPath, floor);
        }
    }

    private List<ClusterSink> getClusterSinksLocked() {
        if (mClusterSinks != null) {
            return mClusterSinks;
        }
        final List<ClusterSink> sinks = new ArrayList<>();
        for (AxAdvancedThermalMitigationConfig.CpuClusterPath path
                : AxPerfConfig.getAtmc().getCpuClusterPaths()) {
            if (path == null || path.minPath == null || path.maxPath == null) {
                continue;
            }
            if (new File(path.minPath).exists() && new File(path.maxPath).exists()) {
                sinks.add(new ClusterSink(
                        path.cluster, path.minPath, path.maxPath, path.min, path.max));
            }
        }
        if (sinks.isEmpty()) {
            collectDiscoveredSinks(sinks);
        }
        mClusterSinks = sinks;
        return mClusterSinks;
    }

    private void collectDiscoveredSinks(List<ClusterSink> sinks) {
        final File base = new File(CPUFREQ_BASE);
        final File[] dirs = base.listFiles(
                file -> file.isDirectory() && file.getName().startsWith("policy"));
        if (dirs == null || dirs.length == 0) {
            return;
        }
        Arrays.sort(dirs, Comparator.comparingInt((File f) -> policyIndex(f.getName())));
        final int[] order;
        if (dirs.length >= 4 && AxPerfConfig.getAtmc().hasMidCluster()) {
            order = CLUSTER_ORDER_4;
        } else if (dirs.length >= 3) {
            order = CLUSTER_ORDER_3;
        } else if (dirs.length == 2) {
            order = CLUSTER_ORDER_2;
        } else {
            order = CLUSTER_ORDER_1;
        }
        final int count = Math.min(dirs.length, order.length);
        for (int i = 0; i < count; i++) {
            final String dir = dirs[i].getAbsolutePath();
            final String minPath = dir + "/scaling_min_freq";
            final String maxPath = dir + "/scaling_max_freq";
            if (new File(minPath).exists() && new File(maxPath).exists()) {
                sinks.add(new ClusterSink(order[i], minPath, maxPath, 0L, 0L));
            }
        }
    }

    private void writeNode(String path, long value) {
        if (value <= 0L) {
            return;
        }
        final String text = Long.toString(value);
        if (text.equals(mAppliedValues.get(path))) {
            return;
        }
        try {
            if (!mSavedValues.containsKey(path)) {
                mSavedValues.put(path, FileUtils.readTextFile(new File(path), 0, null).trim());
            }
            FileUtils.stringToFile(path, text);
            mAppliedValues.put(path, text);
        } catch (IOException | RuntimeException e) {
            Slog.w(TAG, "unable to write " + path + ": " + e.getMessage());
        }
    }

    private void restoreNode(String path, long userValue) {
        if (!mSavedValues.containsKey(path)) {
            return;
        }
        final String saved = mSavedValues.remove(path);
        mAppliedValues.remove(path);
        final long value = userValue > 0L ? userValue : parseLong(saved);
        if (value <= 0L) {
            return;
        }
        try {
            FileUtils.stringToFile(path, Long.toString(value));
        } catch (IOException | RuntimeException e) {
            Slog.w(TAG, "unable to restore " + path + ": " + e.getMessage());
        }
    }

    private static long userFrequency(String path) {
        if (path == null || path.isEmpty()) {
            return 0L;
        }
        return SystemProperties.getLong(AxKernelManagerService.propKeyForPath(path), 0L);
    }

    private static AxAdvancedThermalMitigationConfig.CpuClusterPath findClusterPath(int cluster) {
        for (AxAdvancedThermalMitigationConfig.CpuClusterPath path
                : AxPerfConfig.getAtmc().getCpuClusterPaths()) {
            if (path.cluster == cluster) {
                return path;
            }
        }
        return null;
    }

    private static int policyIndex(String name) {
        try {
            return Integer.parseInt(name.substring("policy".length()));
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    private static long minPositive(long... values) {
        long result = 0L;
        for (long value : values) {
            if (value > 0L && (result == 0L || value < result)) {
                result = value;
            }
        }
        return result;
    }

    private static long parseLong(String value) {
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static long toHz(int value) {
        if (value <= 0) {
            return 0L;
        }
        return value < 10_000 ? (long) value * 1_000_000L : value;
    }

    private static int toInt(long value) {
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }

    private static final class ClusterSink {
        final int cluster;
        final String minPath;
        final String maxPath;
        final long baseMin;
        final long baseMax;

        ClusterSink(int cluster, String minPath, String maxPath, long baseMin, long baseMax) {
            this.cluster = cluster;
            this.minPath = minPath;
            this.maxPath = maxPath;
            this.baseMin = baseMin;
            this.baseMax = baseMax;
        }
    }
}
