/*
 * Copyright (C) 2025 AxionOS
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
import android.os.SystemProperties;
import android.util.Slog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

public final class BoostConfig {
    private static final String TAG = "BoostConfig";
    private static final String AX_PROP = "persist.sys.axion_";

    private final CpuData cData;
    private BoostData data;

    private static final String CPU_SYS_PATH = "/sys/devices/system/cpu/cpu";
    private static final String SCALING_MIN_FREQ_FILE = "/cpufreq/scaling_min_freq";
    private static final String SCALING_MAX_FREQ_FILE = "/cpufreq/scaling_max_freq";

    public static final int SF_UC_MIN_BOOST =
            Math.round(SystemProperties.getInt("ro.surface_flinger.uclamp.min", 100) * 100f / 1024f);

    public final String CPU_BG = cpuPath("background");
    public final String CPU_NT_FG = cpuPath("nt_foreground");
    public final String CPU_RESTRICTED = cpuPath("restricted");
    public final String CPU_DISPLAY = cpuPath("display");

    public final String ROOT_PROCS = cpuCtlPath("", "/cgroup.procs");
    public final String RESTRICTED_PROCS = cpuCtlPath("restricted", "/cgroup.procs");
    public final String RESTRICTED_UC_MAX = cpuCtlPath("restricted", "/cpu.uclamp.max");
    public final String RESTRICTED_UC_MIN = cpuCtlPath("restricted", "/cpu.uclamp.min");
    public final String DISPLAY_UC_MAX = cpuCtlPath("display", "/cpu.uclamp.max");
    public final String DISPLAY_UC_MIN = cpuCtlPath("display", "/cpu.uclamp.min");

    public final static String SCALING_GOV = "persist.sys.scaling_governor";
    public final static String DEFAULT_GOV = prop("default_scaling_gov", "schedutil");
    public final static String PERF_GOV = "performance";
    public static final String BG_CPU = prop("cpu_bg", "0-3");
    public static final String BG_LIMIT = prop("cpu_limit_bg", "0-1");
    public static final String FG_LIMIT = prop("cpu_limit_ui", "0-2");

    public BoostConfig() {
        cData = initCpuProps();
    }

    public BoostData getData() {
        return data;
    }

    public static String scalingGov() {
        return prop(SCALING_GOV, "schedutil");
    }

    public void updateSettings(
            boolean cpuBoost, 
            boolean bigCoreBoost, 
            boolean boostSf, 
            boolean inputBoost,
            int freqBoost,
            int freqBoostBig
    ) {
        data = new BoostData(
                cpuBoost, bigCoreBoost, boostSf, inputBoost, freqBoost,
                cData.smallCores, cData.bigCores, cData.primeCores,
                cData.littleMin, cData.bigMin, cData.primeMin,
                cData.littleMax, cData.bigMax, cData.primeMax, 
                cData.allCores, freqBoostBig
        );
        logger("updateSettings: " + data);
    }

    public record BoostData(
            boolean cpuBoost,
            boolean bigCoreBoost,
            boolean boostSf,
            boolean inputBoost,
            int freqBoost,
            String smallCores,
            String bigCores,
            String primeCores,
            String littleMin,
            String bigMin,
            String primeMin,
            String littleMax,
            String bigMax,
            String primeMax,
            String allCores,
            int freqBoostBig
    ) {}

    private record CpuData(
            String smallCores, String bigCores, String primeCores,
            String littleMin, String bigMin, String primeMin,
            String littleMax, String bigMax, String primeMax,
            String allCores
    ) {}

    private CpuData initCpuProps() {
        int cpuCount = Runtime.getRuntime().availableProcessors();
        if (cpuCount <= 0) return emptyData();

        int[] maxFreqs = new int[cpuCount];
        for (int i = 0; i < cpuCount; i++) {
            String path = CPU_SYS_PATH + i + SCALING_MAX_FREQ_FILE;
            maxFreqs[i] = readFreq(path);
        }

        int minFreq = Integer.MAX_VALUE, maxFreq = Integer.MIN_VALUE;
        for (int f : maxFreqs) {
            if (f > 0) {
                if (f < minFreq) minFreq = f;
                if (f > maxFreq) maxFreq = f;
            }
        }

        int midFreq = -1;
        for (int f : maxFreqs) {
            if (f > minFreq && f < maxFreq) {
                midFreq = f;
                break;
            }
        }

        StringBuilder small = new StringBuilder();
        StringBuilder big = new StringBuilder();
        StringBuilder prime = new StringBuilder();

        for (int i = 0; i < cpuCount; i++) {
            int f = maxFreqs[i];
            if (f == minFreq) {
                appendCpu(small, i);
            } else if (midFreq != -1 && f == midFreq) {
                appendCpu(big, i);
            } else if (f == maxFreq) {
                appendCpu(prime, i);
            }
        }

        if (prime.length() == 0) {
            if (big.length() > 0) {
                prime.append(big);
            } else {
                prime.append(small);
            }
        }

        String smallCores = small.toString();
        String bigCores = big.toString();
        String primeCores = prime.toString();
        String allCores = "0-" + String.valueOf(cpuCount -1);

        SystemProperties.set(AX_PROP + "cpu_small", smallCores);
        SystemProperties.set(AX_PROP + "cpu_big", bigCores);
        SystemProperties.set(AX_PROP + "cpu_prime", primeCores);

        SystemProperties.set(AX_PROP + "cpu_small_index", firstIndex(smallCores));
        SystemProperties.set(AX_PROP + "cpu_big_index", firstIndex(bigCores));
        SystemProperties.set(AX_PROP + "cpu_prime_index", firstIndex(primeCores));
        
        SystemProperties.set(AX_PROP + "cpu_all", allCores);

        String value = minFreq + "," + (midFreq > 0 ? midFreq : maxFreq) + "," + maxFreq;
        SystemProperties.set("persist.sys.ax_max_cpu_freqs", value);

        String littleMin = CPU_SYS_PATH + firstIndex(smallCores) + SCALING_MIN_FREQ_FILE;
        String bigMin = CPU_SYS_PATH + firstIndex(bigCores) + SCALING_MIN_FREQ_FILE;
        String primeMin = CPU_SYS_PATH + firstIndex(primeCores) + SCALING_MIN_FREQ_FILE;

        String littleMax = CPU_SYS_PATH + firstIndex(smallCores) + SCALING_MAX_FREQ_FILE;
        String bigMax = CPU_SYS_PATH + firstIndex(bigCores) + SCALING_MAX_FREQ_FILE;
        String primeMax = CPU_SYS_PATH + firstIndex(primeCores) + SCALING_MAX_FREQ_FILE;

        String[] bg = smallCores.split(",");
        String cpuBg = rangeTo(bg, 3);
        SystemProperties.set(AX_PROP + "cpu_bg", cpuBg);

        String cpuSysBg = toRange(smallCores);
        SystemProperties.set(AX_PROP + "cpu_sys_bg", cpuSysBg);

        String cpuLimitBg = rangeTo(bg, 2);
        SystemProperties.set(AX_PROP + "cpu_limit_bg", cpuLimitBg);

        SystemProperties.set(AX_PROP + "cpu_fg", allCores);

        String cpuLimitUi = toRange(smallCores);
        SystemProperties.set(AX_PROP + "cpu_limit_ui", cpuLimitUi);

        String displayCores = joinRanges(toRange(smallCores), toRange(bigCores));
        SystemProperties.set(AX_PROP + "cpu_display", displayCores);

        logger("initCpuProps: small=" + smallCores +
                " big=" + bigCores +
                " prime=" + primeCores +
                " freqs=" + value + " allCores=" + allCores);

        return new CpuData(
                toRange(smallCores), 
                toRange(bigCores), 
                toRange(primeCores),
                littleMin, bigMin, primeMin,
                littleMax, bigMax, primeMax, 
                allCores);
    }

    private CpuData emptyData() {
        return new CpuData("", "", "",
                "", "", "",
                "", "", "", "");
    }

    private static String rangeTo(String[] cores, int count) {
        if (cores.length == 0) return "";
        int limit = Math.min(count, cores.length);
        if (limit == 1) return cores[0];
        return cores[0] + "-" + cores[limit - 1];
    }

    private static String joinRanges(String a, String b) {
        if (a.isEmpty()) return b;
        if (b.isEmpty()) return a;
        return a + "," + b;
    }

    private static String toRange(String cores) {
        if (cores == null || cores.isEmpty()) return "";
        String[] parts = cores.split(",");
        if (parts.length == 1) return parts[0];

        int start = Integer.parseInt(parts[0]);
        int prev = start;
        StringBuilder sb = new StringBuilder();
        boolean inRange = false;

        for (int i = 1; i < parts.length; i++) {
            int curr = Integer.parseInt(parts[i]);
            if (curr == prev + 1) {
                inRange = true;
            } else {
                if (inRange) {
                    sb.append(start).append("-").append(prev).append(",");
                } else {
                    sb.append(start).append(",");
                }
                start = curr;
                inRange = false;
            }
            prev = curr;
        }

        if (inRange) {
            sb.append(start).append("-").append(prev);
        } else {
            sb.append(start);
        }

        return sb.toString();
    }

    private static void appendCpu(StringBuilder sb, int idx) {
        if (sb.length() > 0) sb.append(",");
        sb.append(idx);
    }

    private static String firstIndex(String cores) {
        if (cores == null || cores.isEmpty()) return "0";
        return cores.split(",")[0];
    }

    public static String cpuPath(String cgroup) {
        return "/dev/cpuset/" + cgroup + "/cpus";
    }

    private static String cpuCtlPath(String cgroup, String file) {
        return "/dev/cpuctl/" + cgroup + file;
    }

    private static String prop(String key, String def) {
        return SystemProperties.get(AX_PROP + key, def);
    }

    public void writeInternal(String path, String value) {
        String current = readFile(path);
        if (current != null && current.equals(value)) {
            return;
        }
        try {
            FileUtils.stringToFile(path, value);
            logger("writeInternal write: " + path + " value: " + value);
        } catch (Exception e) {
            logger("writeInternal failed: " + path + " : " + e.getMessage());
        }
    }

    private static int readFreq(String path) {
        String val = readFile(path);
        if (val == null) return -1;
        try {
            return Integer.parseInt(val.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String readFile(String path) {
        try {
            return new String(Files.readAllBytes(Paths.get(path))).trim();
        } catch (IOException e) {
            return null;
        }
    }

    private static void logger(String msg) {
        if (SystemProperties.getBoolean("persist.sys.ax_boost_debug", false)) {
            Slog.d(TAG, msg);
        }
    }
}
