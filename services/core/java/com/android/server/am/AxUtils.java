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

import android.os.*;
import android.os.Process;
import android.util.Slog;

import java.io.File;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors; 

public class AxUtils {

    public static final long MEM_16GB = 16777216;
    public static final long MEM_12GB = 12582912;
    public static final long MEM_10GB = 10485760;
    public static final long MEM_8GB = 8388608;
    public static final long MEM_6GB = 6291456;

    public static final int THREAD_GROUP_SVP = 9;
    public static final int THREAD_GROUP_NT_FOREGROUND = 10;
    public static final int THREAD_GROUP_RESTRICTED = Process.THREAD_GROUP_RESTRICTED;

    public final static String SCALING_GOV = "persist.sys.scaling_governor";
    public final static String PERF_GOV = "performance";
    public final static String DEFAULT_GOV = AxUtils.prop("default_scaling_gov", "schedutil");

    public static final ArrayList<String> sAppWhiteList = new ArrayList<>();
    public static final ArrayList<String> sAppPerfList = new ArrayList<>();
    public static final ArrayList<String> sCameraList = new ArrayList<>();
    public static final ArrayList<String> sPerfBlackList = new ArrayList<>();

    static {
        sAppWhiteList.add("com.google.android.providers.media.module");
        sAppWhiteList.add("android.process.media");
        sAppWhiteList.add("android.os.cts");
        sAppPerfList.add("com.android.systemui");
        sAppPerfList.add("com.android.launcher3");
        sCameraList.add("com.google.android.GoogleCamera");
        sCameraList.add("org.lineageos.aperture");
        sCameraList.add("com.oplus.camera");
        // processes that should never be scheduled on high energy cores but still needs resources
        sPerfBlackList.add("com.google.pixel.camera.services");
        sPerfBlackList.add("com.android.qns");
        sPerfBlackList.add("com.google.android.iwlan");
        sPerfBlackList.add("com.android.axion.widgets");
        sPerfBlackList.add("org.lineage.settings");
        sPerfBlackList.add("co.aospa.sense");
        sPerfBlackList.add("io.chaldeaprjkt.gamespace");
        sPerfBlackList.add("com.android.edge.bar");
    }

    AxUtils() {
    }

    public static String scalingGov() {
        return prop(SCALING_GOV, "schedutil");
    }

    private static boolean needsControl(ProcessRecord app, boolean verifyGroup, int oldScheduleGroup) {
        if (verifyGroup && oldScheduleGroup == ProcessList.SCHED_GROUP_TOP_APP && app.hasActivities()) {
            logger("previous schedule group is top, not need limit!");
            return false;
        }
        if (app.uid % 100000 < 10000 || isInPerfList(app.processName) || isInWhiteList(app.processName)) {
            logger("system app not need limit!");
            return false;
        }
        if (app.getHostingRecord() == null || app.getHostingRecord().isTopApp()) {
            return false;
        }
        logger("process : " + app.processName + " is not top!");
        return true;
    }

    public static boolean isForegroundNeedSelfControll(int oldScheduleGroup, ProcessRecord app) {
        return needsControl(app, true, oldScheduleGroup);
    }

    public static boolean isRestrictedNeedSelfControll(ProcessRecord app) {
        return needsControl(app, false, -1);
    }

    public static boolean isInWhiteList(String processName) {
        return processName != null && sAppWhiteList.contains(processName);
    }

    public static boolean isInPerfList(String processName) {
        return processName != null && (sAppPerfList.contains(processName) || isCamera(processName));
    }

    public static boolean isCamera(String processName) {
        return processName != null && sCameraList.contains(processName);
    }

    public static boolean isInPerfBlackList(String processName) {
        return processName != null && sPerfBlackList.contains(processName) || isInLowPrioList(processName);
    }

    public static boolean isInLowPrioList(String processName) {
        return processName != null && processName.contains("google");
    }

    public static void boostCamera(boolean boost) {
        SystemProperties.set(SCALING_GOV, boost ? PERF_GOV : DEFAULT_GOV);
    }

    public static boolean isBoosted() {
        return PERF_GOV.equals(scalingGov());
    }

    public static boolean isModernKernel() {
        try {
            String kernelVersion = SystemProperties.get("ro.kernel.version", "");
            String[] parts = kernelVersion.split("\\.");
            int major = Integer.parseInt(parts[0]);
            int minor = (parts.length > 1) ? Integer.parseInt(parts[1]) : 0;
            return (major > 4) || (major == 4 && minor >= 19);
        } catch (Exception e) {
            return false;
        }
    }

    public static String readBufFile(String path) {
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append(" ");
            }
            String res = sb.toString().trim();
            logger("readBufFile path=" + path + " res=" + res);
            return res;
        } catch (IOException e) {
            logger("readBufFile failed: " + path + " error=" + e);
            return null;
        }
    }

    public static String rangeTo(String value, int count) {
        String[] cores = value.split(",");
        if (cores.length == 0) return "";
        int limit = Math.min(count, cores.length);
        if (limit == 1) return cores[0];
        return cores[0] + "-" + cores[limit - 1];
    }

    public static String joinRanges(String a, String b) {
        if (a.isEmpty()) return b;
        if (b.isEmpty()) return a;
        return a + "," + b;
    }

    public static String toRange(String cores) {
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

    public static void appendCpu(StringBuilder sb, int idx) {
        if (sb.length() > 0) sb.append(",");
        sb.append(idx);
    }

    public static String firstIndex(String cores) {
        if (cores == null || cores.isEmpty()) return "0";
        return cores.split(",")[0];
    }

    public static String cpuPath(String path) {
        return "/dev/cpuset/" + path + "/cpus";
    }

    public static String cpuCtlPath(String path, String file) {
        return "/dev/cpuctl/" + path + file;
    }

    public static String prop(String key, String def) {
        return SystemProperties.get("persist.sys.axion_" + key, def);
    }

    public static boolean boolProp(String key, boolean def) {
        return SystemProperties.getBoolean("persist.sys.axion_" + key, def);
    }

    public static void propSet(String key, String val) {
        SystemProperties.set("persist.sys.axion_" + key, val);
    }

    public static void propSetF(String key, String val) {
        SystemProperties.set(key, val);
    }

    public static String joinString(String... parts) {
        return Arrays.stream(parts)
                     .filter(s -> s != null && !s.isEmpty())
                     .collect(Collectors.joining(","));
    }

    public static void write(String path, String value) {
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

    public static int readFreq(String path) {
        String val = readFile(path);
        if (val == null) return -1;
        try {
            return Integer.parseInt(val.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public static String readFile(String path) {
        try {
            String res = new String(Files.readAllBytes(Paths.get(path))).trim();
            return res;
        } catch (IOException e) {
            logger("readFile path: " + path + " error=" + e);
            return null;
        }
    }

    private static long parseMemTotalKb() {
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/meminfo"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("MemTotal:")) {
                    return Long.parseLong(
                        line.substring(line.indexOf(":") + 1, line.indexOf("kB")).trim());
                }
            }
        } catch (Exception e) {
            logger("parseMemTotalKb failed: " + e);
        }
        return -1;
    }

    public static long getPhysicalMemory() {
        long memTotal = parseMemTotalKb();
        if (memTotal <= 0) return -1;

        long physicalMem = MEM_6GB;
        if (memTotal > MEM_12GB) {
            physicalMem = MEM_16GB;
        } else if (memTotal > MEM_10GB) {
            physicalMem = MEM_12GB;
        } else if (memTotal > MEM_8GB) {
            physicalMem = MEM_10GB;
        } else if (memTotal > MEM_6GB) {
            physicalMem = MEM_8GB;
        }

        logger("getPhysicalMemory: MemTotal=" + memTotal + "KB, Physical=" + physicalMem);
        return physicalMem;
    }

    public static String getRamKey() {
        long memSize = AxUtils.getPhysicalMemory();
        if (memSize == MEM_12GB || memSize == MEM_16GB) return "12GB";
        if (memSize == MEM_8GB || memSize == MEM_10GB) return "8GB";
        if (memSize == MEM_6GB) return "6GB";
        return null;
    }

    public static int getMemTotal() {
        long memTotalKb = parseMemTotalKb();
        if (memTotalKb <= 0) return -1;

        float usableGb = memTotalKb / 1024f / 1024f;

        // common factor - here, we assume that memtotal reprorts 
        // at least 93.5 % of the total memory size due to reserved memory 
        // for gpu and other stuffs
        float factor = 0.935f;  

        float estimatedGb = usableGb / factor;

        // i have nver seen a marketed ram size for android with odd number except for 1 gb
        int memGb = (int) Math.ceil(estimatedGb);
        if (memGb % 2 != 0) memGb++;

        logger("getMemTotal: MemTotal=" + memTotalKb + "KB, usable=" + usableGb
               + "GB → estimated=" + estimatedGb + "GB → reported=" + memGb + "GB (even)");

        return memGb;
    }

    public static boolean isPreferredAppsSupported() {
        return getPhysicalMemory() > MEM_8GB && boolProp("pr_apps", true);
    }

    public static void logger(String msg) {
        if (!SystemProperties.getBoolean("persist.sys.ax_sys_debug", false)) return;
        Slog.d("AxUtils", msg);
    }
    
    public static boolean checkTid(int tid) {
        return tid > 0 && new File("/proc/" + tid).exists();
    }
}
