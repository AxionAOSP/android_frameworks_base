/*
 * Copyright (C) 2025-2026 AxionOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */
package com.android.server.kernel;

import android.os.AxKernelMetrics;
import android.os.FileUtils;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.SparseArray;

import com.android.modules.utils.TypedXmlPullParser;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.TreeSet;

final class AxKernelMetricsReader {
    private static final String PROC_STAT_PATH = "/proc/stat";
    private static final String ATTR_ID = "id";
    private static final String ATTR_GROUP = "group";
    private static final String ATTR_PATH = "path";
    private static final String ATTR_NODE = "node";
    private static final String ATTR_MIN_PATH = "minPath";
    private static final String ATTR_MIN_NODE = "minNode";
    private static final String ATTR_MAX_PATH = "maxPath";
    private static final String ATTR_MAX_NODE = "maxNode";
    private static final String ATTR_CURRENT_PATH = "currentPath";
    private static final String ATTR_CURRENT_NODE = "currentNode";
    private static final String ATTR_RELATED_CPUS_PATH = "relatedCpusPath";
    private static final String ATTR_RELATED_CPUS_NODE = "relatedCpusNode";
    private static final String ATTR_USAGE_PATH = "usagePath";
    private static final String ATTR_USAGE_NODE = "usageNode";
    private static final String ATTR_FREQUENCY_MULTIPLIER = "frequencyMultiplier";
    private static final int MAX_CPU_COUNT = 4096;

    private volatile Config mConfig = new Config();

    void setConfig(Config config) {
        mConfig = config;
    }

    AxKernelMetrics query(long previousCpuActiveTimeTicks, long previousCpuTimeTicks) {
        long now = SystemClock.elapsedRealtime();
        Config config = mConfig;
        CpuSample currentCpuSample = readCpuSample();
        CpuTimes totalCpuTimes = currentCpuSample != null ? currentCpuSample.total : null;
        long totalCpuActiveTimeTicks = activeTimeTicks(totalCpuTimes);
        long totalCpuTimeTicks = totalTimeTicks(totalCpuTimes);
        ArrayList<AxKernelMetrics.CpuCluster> cpuClusters =
                new ArrayList<>(config.cpuConfigs.size());
        for (int i = 0; i < config.cpuConfigs.size(); i++) {
            CpuConfig cpuConfig = config.cpuConfigs.valueAt(i);
            CpuTimes currentTimes = sumTimes(currentCpuSample, cpuConfig.cpuIds);
            cpuClusters.add(
                    new AxKernelMetrics.CpuCluster(
                            cpuConfig.id,
                            cpuConfig.group,
                            cpuConfig.cpuIds,
                            activeTimeTicks(currentTimes),
                            totalTimeTicks(currentTimes),
                            readFrequency(cpuConfig.currentPath, cpuConfig.frequencyMultiplier),
                            readFrequency(cpuConfig.minPath, cpuConfig.frequencyMultiplier),
                            readFrequency(cpuConfig.maxPath, cpuConfig.frequencyMultiplier)));
        }

        AxKernelMetrics.Gpu gpu = null;
        for (int i = config.gpuConfigs.size() - 1; i >= 0; i--) {
            gpu = readGpu(config.gpuConfigs.get(i));
            if (gpu != null) {
                break;
            }
        }

        return new AxKernelMetrics(
                now,
                cpuUsagePercent(
                        previousCpuActiveTimeTicks,
                        previousCpuTimeTicks,
                        totalCpuActiveTimeTicks,
                        totalCpuTimeTicks),
                totalCpuActiveTimeTicks,
                totalCpuTimeTicks,
                cpuClusters,
                gpu);
    }

    private static AxKernelMetrics.Gpu readGpu(GpuConfig config) {
        float usage = readUsagePercent(config.usagePath);
        long currentFrequency = readFrequency(config.currentPath, config.frequencyMultiplier);
        long minFrequency = readFrequency(config.minPath, config.frequencyMultiplier);
        long maxFrequency = readFrequency(config.maxPath, config.frequencyMultiplier);
        if (usage == AxKernelMetrics.USAGE_UNAVAILABLE
                && currentFrequency == AxKernelMetrics.FREQUENCY_UNAVAILABLE_HZ
                && minFrequency == AxKernelMetrics.FREQUENCY_UNAVAILABLE_HZ
                && maxFrequency == AxKernelMetrics.FREQUENCY_UNAVAILABLE_HZ) {
            return null;
        }
        return new AxKernelMetrics.Gpu(usage, currentFrequency, minFrequency, maxFrequency);
    }

    private static CpuSample readCpuSample() {
        String text;
        try {
            text = FileUtils.readTextFile(new File(PROC_STAT_PATH), 0, null);
        } catch (IOException e) {
            return null;
        }
        CpuTimes total = null;
        SparseArray<CpuTimes> cores = new SparseArray<>();
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("cpu")) {
                continue;
            }
            String[] parts = trimmed.split("\\s+");
            if (parts.length < 5) {
                continue;
            }
            CpuTimes times = parseCpuTimes(parts);
            if (times == null) {
                continue;
            }
            if ("cpu".equals(parts[0])) {
                total = times;
            } else {
                int cpu = parseCpuId(parts[0]);
                if (cpu >= 0) {
                    cores.put(cpu, times);
                }
            }
        }
        return total != null ? new CpuSample(total, cores) : null;
    }

    private static CpuTimes parseCpuTimes(String[] parts) {
        long total = 0L;
        try {
            int end = Math.min(parts.length, 9);
            for (int i = 1; i < end; i++) {
                total += Long.parseLong(parts[i]);
            }
            long idle = Long.parseLong(parts[4]);
            long iowait = parts.length > 5 ? Long.parseLong(parts[5]) : 0L;
            return new CpuTimes(total, idle + iowait);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int parseCpuId(String name) {
        if (name.length() <= 3) {
            return -1;
        }
        try {
            return Integer.parseInt(name.substring(3));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static CpuTimes sumTimes(CpuSample sample, int[] cpuIds) {
        if (sample == null || cpuIds.length == 0) {
            return null;
        }
        long total = 0L;
        long idle = 0L;
        boolean found = false;
        for (int cpuId : cpuIds) {
            CpuTimes times = sample.cores.get(cpuId);
            if (times == null) {
                continue;
            }
            found = true;
            total += times.total;
            idle += times.idle;
        }
        return found ? new CpuTimes(total, idle) : null;
    }

    private static long activeTimeTicks(CpuTimes times) {
        return times != null
                ? Math.max(0L, times.total - times.idle)
                : AxKernelMetrics.CPU_TIME_UNAVAILABLE_TICKS;
    }

    private static long totalTimeTicks(CpuTimes times) {
        return times != null ? times.total : AxKernelMetrics.CPU_TIME_UNAVAILABLE_TICKS;
    }

    private static float cpuUsagePercent(
            long previousActiveTimeTicks,
            long previousTotalTimeTicks,
            long activeTimeTicks,
            long totalTimeTicks) {
        if (previousActiveTimeTicks < 0L
                || previousTotalTimeTicks < previousActiveTimeTicks
                || activeTimeTicks < previousActiveTimeTicks
                || totalTimeTicks <= previousTotalTimeTicks
                || totalTimeTicks < activeTimeTicks) {
            return AxKernelMetrics.USAGE_UNAVAILABLE;
        }
        long activeDelta = activeTimeTicks - previousActiveTimeTicks;
        long totalDelta = totalTimeTicks - previousTotalTimeTicks;
        if (activeDelta > totalDelta) {
            return AxKernelMetrics.USAGE_UNAVAILABLE;
        }
        return (float) activeDelta * 100.0f / totalDelta;
    }

    private static float readUsagePercent(String path) {
        String value = readString(path);
        if (value == null) {
            return AxKernelMetrics.USAGE_UNAVAILABLE;
        }
        int separator = firstSeparator(value);
        String token = value.substring(0, separator).replace("%", "");
        try {
            float usage = Float.parseFloat(token);
            if (!Float.isFinite(usage)) {
                return AxKernelMetrics.USAGE_UNAVAILABLE;
            }
            return Math.max(0.0f, Math.min(100.0f, usage));
        } catch (NumberFormatException e) {
            return AxKernelMetrics.USAGE_UNAVAILABLE;
        }
    }

    private static long readFrequency(String path, long multiplier) {
        String value = readString(path);
        if (value == null) {
            return AxKernelMetrics.FREQUENCY_UNAVAILABLE_HZ;
        }
        int separator = firstSeparator(value);
        try {
            long frequency = Long.parseLong(value.substring(0, separator));
            if (frequency < 0L) {
                return AxKernelMetrics.FREQUENCY_UNAVAILABLE_HZ;
            }
            return Math.multiplyExact(frequency, multiplier);
        } catch (ArithmeticException | NumberFormatException e) {
            return AxKernelMetrics.FREQUENCY_UNAVAILABLE_HZ;
        }
    }

    private static int firstSeparator(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isWhitespace(c) || c == ',') {
                return i;
            }
        }
        return value.length();
    }

    private static String readString(String path) {
        if (TextUtils.isEmpty(path)) {
            return null;
        }
        try {
            String value = FileUtils.readTextFile(new File(path), 256, null).trim();
            return value.isEmpty() ? null : value;
        } catch (IOException e) {
            return null;
        }
    }

    private static int[] readCpuIds(String path) {
        String value = readString(path);
        if (value == null) {
            return new int[0];
        }
        TreeSet<Integer> cpuIds = new TreeSet<>();
        for (String token : value.split("[\\s,]+")) {
            if (token.isEmpty()) {
                continue;
            }
            int separator = token.indexOf('-');
            try {
                int first =
                        Integer.parseInt(separator >= 0 ? token.substring(0, separator) : token);
                int last =
                        separator >= 0 ? Integer.parseInt(token.substring(separator + 1)) : first;
                if (first < 0 || last < first || last >= MAX_CPU_COUNT) {
                    return new int[0];
                }
                for (int cpu = first; cpu <= last; cpu++) {
                    cpuIds.add(cpu);
                }
            } catch (NumberFormatException e) {
                return new int[0];
            }
        }
        int[] result = new int[cpuIds.size()];
        int index = 0;
        for (int cpu : cpuIds) {
            result[index++] = cpu;
        }
        return result;
    }

    private static String pathAttr(TypedXmlPullParser parser, String pathAttr, String nodeAttr) {
        String value = parser.getAttributeValue(null, pathAttr);
        return !TextUtils.isEmpty(value) ? value : parser.getAttributeValue(null, nodeAttr);
    }

    private static String childPath(String base, String child) {
        return TextUtils.isEmpty(base) ? null : new File(base, child).getPath();
    }

    static final class Config {
        final ArrayMap<String, CpuConfig> cpuConfigs = new ArrayMap<>();
        final ArrayList<GpuConfig> gpuConfigs = new ArrayList<>();

        void addCpu(TypedXmlPullParser parser) {
            String id = parser.getAttributeValue(null, ATTR_ID);
            String group = parser.getAttributeValue(null, ATTR_GROUP);
            if (TextUtils.isEmpty(id)) {
                return;
            }
            if (TextUtils.isEmpty(group)) {
                group = id;
            }
            String base = pathAttr(parser, ATTR_PATH, ATTR_NODE);
            String minPath = pathAttr(parser, ATTR_MIN_PATH, ATTR_MIN_NODE);
            String maxPath = pathAttr(parser, ATTR_MAX_PATH, ATTR_MAX_NODE);
            String policyPath =
                    !TextUtils.isEmpty(base)
                            ? base
                            : !TextUtils.isEmpty(minPath) ? new File(minPath).getParent() : null;
            if (TextUtils.isEmpty(minPath)) {
                minPath = childPath(policyPath, "scaling_min_freq");
            }
            if (TextUtils.isEmpty(maxPath)) {
                maxPath = childPath(policyPath, "scaling_max_freq");
            }
            String currentPath = pathAttr(parser, ATTR_CURRENT_PATH, ATTR_CURRENT_NODE);
            if (TextUtils.isEmpty(currentPath)) {
                currentPath = childPath(policyPath, "scaling_cur_freq");
            }
            String relatedCpusPath =
                    pathAttr(parser, ATTR_RELATED_CPUS_PATH, ATTR_RELATED_CPUS_NODE);
            if (TextUtils.isEmpty(relatedCpusPath)) {
                relatedCpusPath = childPath(policyPath, "related_cpus");
            }
            long multiplier = parser.getAttributeLong(null, ATTR_FREQUENCY_MULTIPLIER, 1000L);
            if (multiplier <= 0L) {
                multiplier = 1000L;
            }
            cpuConfigs.put(
                    id,
                    new CpuConfig(
                            id,
                            group,
                            readCpuIds(relatedCpusPath),
                            currentPath,
                            minPath,
                            maxPath,
                            multiplier));
        }

        void addGpu(TypedXmlPullParser parser) {
            String base = pathAttr(parser, ATTR_PATH, ATTR_NODE);
            String currentPath = pathAttr(parser, ATTR_CURRENT_PATH, ATTR_CURRENT_NODE);
            String minPath = pathAttr(parser, ATTR_MIN_PATH, ATTR_MIN_NODE);
            String maxPath = pathAttr(parser, ATTR_MAX_PATH, ATTR_MAX_NODE);
            String usagePath = pathAttr(parser, ATTR_USAGE_PATH, ATTR_USAGE_NODE);
            if (TextUtils.isEmpty(currentPath)) {
                currentPath = childPath(base, "cur_freq");
            }
            if (TextUtils.isEmpty(minPath)) {
                minPath = childPath(base, "min_freq");
            }
            if (TextUtils.isEmpty(maxPath)) {
                maxPath = childPath(base, "max_freq");
            }
            long multiplier = parser.getAttributeLong(null, ATTR_FREQUENCY_MULTIPLIER, 1L);
            if (multiplier <= 0L) {
                multiplier = 1L;
            }
            if (!TextUtils.isEmpty(currentPath) || !TextUtils.isEmpty(usagePath)) {
                gpuConfigs.add(new GpuConfig(currentPath, minPath, maxPath, usagePath, multiplier));
            }
        }
    }

    private static final class CpuConfig {
        final String id;
        final String group;
        final int[] cpuIds;
        final String currentPath;
        final String minPath;
        final String maxPath;
        final long frequencyMultiplier;

        CpuConfig(
                String id,
                String group,
                int[] cpuIds,
                String currentPath,
                String minPath,
                String maxPath,
                long frequencyMultiplier) {
            this.id = id;
            this.group = group;
            this.cpuIds = cpuIds;
            this.currentPath = currentPath;
            this.minPath = minPath;
            this.maxPath = maxPath;
            this.frequencyMultiplier = frequencyMultiplier;
        }
    }

    private static final class GpuConfig {
        final String currentPath;
        final String minPath;
        final String maxPath;
        final String usagePath;
        final long frequencyMultiplier;

        GpuConfig(
                String currentPath,
                String minPath,
                String maxPath,
                String usagePath,
                long frequencyMultiplier) {
            this.currentPath = currentPath;
            this.minPath = minPath;
            this.maxPath = maxPath;
            this.usagePath = usagePath;
            this.frequencyMultiplier = frequencyMultiplier;
        }
    }

    private static final class CpuSample {
        final CpuTimes total;
        final SparseArray<CpuTimes> cores;

        CpuSample(CpuTimes total, SparseArray<CpuTimes> cores) {
            this.total = total;
            this.cores = cores;
        }
    }

    private static final class CpuTimes {
        final long total;
        final long idle;

        CpuTimes(long total, long idle) {
            this.total = total;
            this.idle = idle;
        }
    }
}
