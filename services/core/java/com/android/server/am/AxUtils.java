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

import android.os.*;
import android.os.Process;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.IntArray;
import android.util.Slog;

import com.android.server.am.psc.ProcessRecordInternal;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileReader;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class AxUtils {

    public static final long MEM_16GB = 16777216;
    public static final long MEM_12GB = 12582912;
    public static final long MEM_10GB = 10485760;
    public static final long MEM_8GB = 8388608;
    public static final long MEM_6GB = 6291456;

    public static final String SCALING_GOV = "persist.sys.scaling_governor";
    public static final String PERF_GOV = "performance";
    public static final String DEFAULT_GOV = AxUtils.prop("default_scaling_gov", "schedutil");

    public static final ArrayList<String> sAppWhiteList = new ArrayList<>();
    public static final ArrayList<String> sAppPerfList = new ArrayList<>();
    public static final ArrayList<String> sCameraList = new ArrayList<>();
    public static final ArrayList<String> sPerfBlackList = new ArrayList<>();
    public static final HashSet<String> sVipHBgSet = new HashSet<>();
    private static final HashMap<String, String> sLastWrites = new HashMap<>();
    private static final boolean DEBUG_LOG_ENABLED =
            SystemProperties.getBoolean("persist.sys.ax_sys_debug", false);

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
        sVipHBgSet.add("com.android.launcher3");
        sVipHBgSet.add("com.android.settings");
        sVipHBgSet.add("com.google.android.apps.nexuslauncher");
        sVipHBgSet.add("com.google.android.inputmethod.latin");
        sVipHBgSet.add("com.android.inputmethod.latin");
    }

    AxUtils() {}

    public static String scalingGov() {
        return prop(SCALING_GOV, "schedutil");
    }

    private static boolean needsControl(
            ProcessRecordInternal app, boolean verifyGroup, int oldScheduleGroup) {
        if (verifyGroup
                && oldScheduleGroup == ProcessList.SCHED_GROUP_TOP_APP
                && app.hasActivities()) {
            logger("previous schedule group is top, not need limit!");
            return false;
        }
        if (app.uid % 100000 < 10000
                || isInPerfList(app.processName)
                || isInWhiteList(app.processName)) {
            logger("system app not need limit!");
            return false;
        }
        logger("process : " + app.processName + " is not top!");
        return true;
    }

    public static boolean isForegroundNeedSelfControll(
            int oldScheduleGroup, ProcessRecordInternal app) {
        return needsControl(app, true, oldScheduleGroup);
    }

    public static boolean isRestrictedNeedSelfControll(ProcessRecordInternal app) {
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
        return processName != null && sPerfBlackList.contains(processName)
                || isInLowPrioList(processName);
    }

    public static boolean isSystemUi(String processName) {
        return "com.android.systemui".equals(processName);
    }

    public static boolean isVipHBackground(String processName) {
        return processName != null
                && (sVipHBgSet.contains(processName) || processName.contains("axion"));
    }

    public static boolean isInLowPrioList(String processName) {
        if (processName == null) return false;
        return processName.contains("google");
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

    public static String rangeTail(String value, int startIdx) {
        String[] cores = value.split(",");
        if (cores.length == 0 || startIdx >= cores.length) return "";
        if (startIdx <= 0) return toRange(value);
        StringBuilder sb = new StringBuilder();
        for (int i = startIdx; i < cores.length; i++) {
            if (sb.length() > 0) sb.append(",");
            sb.append(cores[i]);
        }
        return toRange(sb.toString());
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
        if (cores == null || cores.isEmpty()) return null;
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
        synchronized (sLastWrites) {
            String last = sLastWrites.get(path);
            if (Objects.equals(value, last)) {
                return;
            }
            if (last != null) {
                writeLocked(path, value);
                return;
            }
        }
        String current = readFile(path);
        synchronized (sLastWrites) {
            if (Objects.equals(value, sLastWrites.get(path))) {
                return;
            }
            if (current != null && current.equals(value)) {
                sLastWrites.put(path, value);
                return;
            }
            writeLocked(path, value);
        }
    }

    private static void writeLocked(String path, String value) {
        try {
            FileUtils.stringToFile(path, value);
            sLastWrites.put(path, value);
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

    public static boolean isTaskExist(int pid, int tid) {
        return pid > 0 && tid > 0
                && Files.exists(Paths.get("/proc/" + pid + "/task/" + tid), new LinkOption[0]);
    }

    public static boolean writeProcNode(String path, String value) {
        if (path == null || value == null || !new File(path).exists()) {
            return false;
        }
        try {
            FileUtils.stringToFile(path, value);
            return true;
        } catch (IOException e) {
            logger("writeProcNode failed: " + path + " error=" + e);
            return false;
        }
    }

    public static String readProcNode(String path, int lines) {
        if (path == null || lines <= 0 || !new File(path).exists()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new FileReader(path), 128)) {
            for (int i = 0; i < lines; i++) {
                String line = br.readLine();
                if (line == null) {
                    break;
                }
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(line);
            }
        } catch (IOException e) {
            logger("readProcNode failed: " + path + " error=" + e);
        }
        return sb.toString();
    }

    public static String readProcComm(int pid) {
        return readProcCommPath("/proc/" + pid + "/comm");
    }

    public static String readProcComm(int pid, int tid) {
        return readProcCommPath("/proc/" + pid + "/task/" + tid + "/comm");
    }

    private static String readProcCommPath(String path) {
        String comm = readFile(path);
        return comm != null && !comm.isEmpty() ? comm : null;
    }

    public static String readProcCmdline(int pid) {
        String cmdline = readFile("/proc/" + pid + "/cmdline");
        return cmdline != null && !cmdline.isEmpty() ? cmdline.replace('\0', ' ').trim() : null;
    }

    public static boolean isMatchedTid(int tid, String pattern) {
        String comm = readProcComm(tid);
        return comm != null && Pattern.matches(pattern, comm);
    }

    public static boolean isMatchedTask(int pid, int tid, String pattern) {
        String comm = readProcComm(pid, tid);
        return comm != null && Pattern.matches(pattern, comm);
    }

    public static IntArray findMatchedPids(String pattern, int... uids) {
        IntArray result = new IntArray();
        if (pattern == null) {
            return result;
        }
        try {
            Pattern compiled = Pattern.compile(pattern);
            int[] pids = Process.getPids("/proc", new int[1024]);
            for (int pid : pids) {
                if (pid == -1) {
                    break;
                }
                String comm = readProcComm(pid);
                if (comm == null || !compiled.matcher(comm).find()) {
                    continue;
                }
                if (uids == null || uids.length == 0 || containsUid(uids, Process.getUidForPid(pid))) {
                    result.add(pid);
                }
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    public static IntArray findMatchedTids(int pid, String pattern) {
        IntArray result = new IntArray();
        if (pid <= 0 || pattern == null) {
            return result;
        }
        try {
            Pattern compiled = Pattern.compile(pattern);
            int[] tids = Process.getPids("/proc/" + pid + "/task", new int[256]);
            for (int tid : tids) {
                if (tid == -1) {
                    break;
                }
                String comm = readProcComm(pid, tid);
                if (comm != null && compiled.matcher(comm).find()) {
                    result.add(tid);
                }
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    public static IntArray findTidsByPrefix(int pid, String prefix) {
        IntArray result = new IntArray();
        if (pid <= 0 || prefix == null) {
            return result;
        }
        try {
            int[] tids = Process.getPids("/proc/" + pid + "/task", new int[256]);
            for (int tid : tids) {
                if (tid == -1) {
                    break;
                }
                String comm = readProcComm(pid, tid);
                if (comm != null && comm.startsWith(prefix)) {
                    result.add(tid);
                }
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    private static boolean containsUid(int[] uids, int uid) {
        for (int candidate : uids) {
            if (candidate == uid) {
                return true;
            }
        }
        return false;
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

        logger(
                "getMemTotal: MemTotal="
                        + memTotalKb
                        + "KB, usable="
                        + usableGb
                        + "GB → estimated="
                        + estimatedGb
                        + "GB → reported="
                        + memGb
                        + "GB (even)");

        return memGb;
    }

    public static boolean isPreferredAppsSupported() {
        return getPhysicalMemory() > MEM_8GB && boolProp("pr_apps", true);
    }

    public static boolean isDebugLogEnabled() {
        return DEBUG_LOG_ENABLED;
    }

    public static void logger(String msg) {
        if (!isDebugLogEnabled()) return;
        Slog.d("AxUtils", msg);
    }

    public static boolean checkTid(int tid) {
        return tid > 0 && new File("/proc/" + tid).exists();
    }

    private static final String PMQOS_PATH = "/dev/cpu_dma_latency";
    private static FileDescriptor sPmqosFd = null;
    private static int sPmqosLatencyUs = -1;
    private static boolean sPmqosSupported = true;

    public static synchronized void pmqosHoldFd(int latencyUs) {
        if (!sPmqosSupported) return;
        if (sPmqosFd != null && sPmqosLatencyUs == latencyUs) return;
        if (sPmqosFd != null) pmqosReleaseFdLocked();
        try {
            FileDescriptor fd = Os.open(PMQOS_PATH, OsConstants.O_WRONLY, 0);
            ByteBuffer buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
            buf.putInt(latencyUs).flip();
            Os.write(fd, buf);
            sPmqosFd = fd;
            sPmqosLatencyUs = latencyUs;
            logger("pmqosHoldFd latencyUs=" + latencyUs);
        } catch (ErrnoException | InterruptedIOException e) {
            sPmqosSupported = false;
            logger("pmqosHoldFd failed: " + e);
        }
    }

    public static synchronized void pmqosReleaseFd() {
        pmqosReleaseFdLocked();
    }

    private static void pmqosReleaseFdLocked() {
        if (sPmqosFd == null) return;
        try {
            Os.close(sPmqosFd);
        } catch (ErrnoException ignored) {
        }
        sPmqosFd = null;
        sPmqosLatencyUs = -1;
    }
}
