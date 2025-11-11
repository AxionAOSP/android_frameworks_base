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

import static com.android.server.am.ActivityManagerService.MY_PID;

import android.os.Process;
import android.os.StrictMode;
import android.util.Slog;

import com.android.server.AxExtServiceFactory;

import java.io.*;
import java.util.*;

public class TaskProfiler {
    private static final String TAG = "TaskProfiler";
    private static final boolean DEBUG = false;

    private static final int UX_KERNEL   = Process.THREAD_GROUP_BACKGROUND;

    public TaskProfiler() {
    }

    public void initTaskProfiles() {
        try {
            int inputReaderTid = findThreadIdByName(MY_PID, "InputReader");
            int inputDispatcherTid = findThreadIdByName(MY_PID, "InputDispatcher");

            if (inputReaderTid > 0) {
                AxExtServiceFactory.getBoostAdjuster().boostThreadLimited(inputReaderTid);
                Slog.i(TAG, "Boosted InputReader (tid=" + inputReaderTid + ")");
            } else {
                Slog.w(TAG, "InputReader thread not found!");
            }

            if (inputDispatcherTid > 0) {
                AxExtServiceFactory.getBoostAdjuster().boostThreadLimited(inputDispatcherTid);
                Slog.i(TAG, "Boosted InputDispatcher (tid=" + inputDispatcherTid + ")");
            } else {
                Slog.w(TAG, "InputDispatcher thread not found!");
            }

            Map<String, Integer> processMap = buildProcessMap();
            Set<Integer> uxThreads = new HashSet<>();

            uxThreads.addAll(setUxThreadByNames(processMap,
                    Arrays.asList("kswapd0", "kcompactd0"), UX_KERNEL));

            uxThreads.addAll(findByPrefix(processMap, "khugepaged", UX_KERNEL));
            uxThreads.addAll(findByPrefix(processMap, "f2fs_gc", UX_KERNEL));
            uxThreads.addAll(findByPrefix(processMap, "eh_comp_thread", UX_KERNEL));

            if (DEBUG) Slog.i(TAG, "Applied task profiles to " + uxThreads.size() + " tids. processMap:\n" + dumpProcessMap(processMap));
        } catch (Exception e) {
            Slog.w(TAG, "initTaskProfiles failed: " + e);
        }
    }

    private Map<String, Integer> buildProcessMap() {
        Map<String, Integer> map = new HashMap<>();
        File[] entries = new File("/proc").listFiles(f -> f.isDirectory() && f.getName().matches("\\d+"));
        if (entries == null) return map;

        for (File entry : entries) {
            try {
                int id = Integer.parseInt(entry.getName());
                String comm = readSingleLine(entry.getPath() + "/comm");
                String cmdline = readCmdline(entry.getPath() + "/cmdline");

                String name = (comm != null ? comm.trim() : null);
                if (cmdline != null && !cmdline.isEmpty()) {
                    if (name == null ||
                        name.startsWith("binder:") ||
                        name.length() >= 15 ||
                        name.startsWith("android.hardwar")) {
                        name = cmdline.trim();
                    }
                }

                if (name != null && !map.containsKey(name)) {
                    map.put(name, id);
                    Slog.i(TAG, "Adding thread: " + name + " (pid=" + id + ")");
                }
            } catch (Exception ignored) { }
        }
        return map;
    }

    private Set<Integer> setUxThreadByNames(Map<String, Integer> map, List<String> names, int group) {
        Set<Integer> out = new HashSet<>();
        for (String n : names) {
            Integer pid = map.get(n);
            if (pid != null) {
                setUxThread(pid, group);
                out.add(pid);
            }
        }
        return out;
    }

    private Set<Integer> findByPrefix(Map<String, Integer> map, String prefix, int group) {
        Set<Integer> out = new HashSet<>();
        for (Map.Entry<String, Integer> e : map.entrySet()) {
            if (e.getKey().startsWith(prefix)) {
                setUxThread(e.getValue(), group);
                out.add(e.getValue());
            }
        }
        return out;
    }

    private Set<Integer> findByContains(Map<String, Integer> map, List<String> substrings, int group) {
        Set<Integer> out = new HashSet<>();
        for (Map.Entry<String, Integer> e : map.entrySet()) {
            for (String sub : substrings) {
                Slog.i(TAG, "findByContains: substrings: " + sub);
                if (e.getKey().contains(sub)) {
                    setUxThread(e.getValue(), group);
                    out.add(e.getValue());
                    break;
                }
            }
        }
        return out;
    }

    private Set<Integer> findMatchingTidsInProcess(int pid, String[] names, int group) {
        Set<Integer> found = new HashSet<>();
        File taskDir = new File("/proc/" + pid + "/task");
        File[] tasks = taskDir.listFiles(f -> f.getName().matches("\\d+"));
        if (tasks == null) return found;

        Set<String> targets = new HashSet<>(Arrays.asList(names));
        for (File t : tasks) {
            try {
                int tid = Integer.parseInt(t.getName());
                String tname = readSingleLine(t.getPath() + "/comm");
                if (tname != null && targets.contains(tname.trim())) {
                    setUxThread(tid, group);
                    found.add(tid);
                }
            } catch (Exception ignored) { }
        }
        return found;
    }

    private static void setUxThread(int tid, int group) {
        try {
            Process.setThreadGroupAndCpuset(tid, group);
            if (group == UX_KERNEL) {
                Process.setThreadScheduler(tid, Process.SCHED_IDLE, 0);
            }
            String name = readSingleLine("/proc/" + tid + "/comm");
            String cpuset = readSingleLine("/proc/" + tid + "/cpuset");
            Slog.i(TAG, "Applied group " + group +
                    (group == UX_KERNEL ? " + SCHED_IDLE" : "") +
                    " to " + tid +
                    (name != null ? " (" + name + ")" : "") +
                    (cpuset != null ? " [cpuset=" + cpuset + "]" : ""));
        } catch (Exception e) {
            Slog.w(TAG, "Failed to set group/scheduler for " + tid + ": " + e);
        }
    }

    private static String readSingleLine(String path) {
        int saved = StrictMode.allowThreadDiskReadsMask();
        try (BufferedReader r = new BufferedReader(new FileReader(path))) {
            return r.readLine();
        } catch (IOException e) {
            return null;
        } finally {
            StrictMode.setThreadPolicyMask(saved);
        }
    }

    private static String readCmdline(String path) {
        int saved = StrictMode.allowThreadDiskReadsMask();
        try (FileInputStream fis = new FileInputStream(path)) {
            byte[] buf = new byte[512];
            int len = fis.read(buf);
            if (len > 0) {
                String raw = new String(buf, 0, len).trim();
                return raw.replace('\0', ' ');
            }
        } catch (IOException ignored) {
        } finally {
            StrictMode.setThreadPolicyMask(saved);
        }
        return null;
    }

    private static String dumpProcessMap(Map<String, Integer> map) {
        StringBuilder sb = new StringBuilder();
        sb.append("Process Map:\n");
        List<String> keys = new ArrayList<>(map.keySet());
        Collections.sort(keys);
        for (String name : keys) {
            sb.append("  ").append(name).append(" (pid=").append(map.get(name)).append(")\n");
        }
        return sb.toString();
    }
    
    private int findThreadIdByName(int pid, String threadName) {
        File taskDir = new File("/proc/" + pid + "/task");
        File[] tasks = taskDir.listFiles(f -> f.getName().matches("\\d+"));
        if (tasks == null) return -1;

        for (File t : tasks) {
            try {
                int tid = Integer.parseInt(t.getName());
                String name = readSingleLine(t.getPath() + "/comm");
                if (name != null && name.trim().equals(threadName)) {
                    return tid;
                }
            } catch (Exception ignored) {}
        }
        return -1;
    }

}
