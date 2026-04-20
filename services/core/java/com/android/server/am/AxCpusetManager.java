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

import static com.android.server.am.DeviceData.CPU_AX_FG;
import static com.android.server.am.DeviceData.CPU_BG;
import static com.android.server.am.DeviceData.CPU_DEX2OAT;
import static com.android.server.am.DeviceData.CPU_FG;
import static com.android.server.am.DeviceData.CPU_H_BG;
import static com.android.server.am.DeviceData.CPU_L_BG;
import static com.android.server.am.DeviceData.CPU_SVP;
import static com.android.server.am.DeviceData.CPU_SYS_BG;
import static com.android.server.am.DeviceData.CPU_TOP_APP;

import android.os.FileUtils;
import android.os.Handler;
import android.os.Message;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;

public final class AxCpusetManager {

    static final int MSG_BG = 100;
    static final int MSG_SYS_BG = 101;
    static final int MSG_TOP_APP = 102;
    static final int MSG_CAMERA = 103;
    static final int MSG_FG = 104;
    static final int MSG_SVP = 105;
    static final int MSG_DEX = 106;
    static final int MSG_AX_FG = 107;
    static final int MSG_L_BG = 109;
    static final int MSG_H_BG = 110;

    public static final String CPU_CAMERA = AxUtils.cpuPath("camera-daemon");
    public static final String CPU_SYSUI = AxUtils.cpuPath("systemui");
    public static final String CPU_NNAPI_HAL = AxUtils.cpuPath("nnapi-hal");
    public static final String CPU_RT = AxUtils.cpuPath("rt");
    public static final String CPU_SYSTEM = AxUtils.cpuPath("system");

    private static final HashMap<String, File> sFileCache = new HashMap<>();
    private static final HashMap<String, Integer> sMsgIds = new HashMap<>();
    private static final HashMap<String, HashMap<Integer, CpusetData>> sGroups = new HashMap<>();

    static {
        for (String p : new String[] { CPU_BG, CPU_SYS_BG, CPU_TOP_APP, CPU_FG, CPU_SVP,
                CPU_DEX2OAT, CPU_AX_FG, CPU_CAMERA, CPU_L_BG, CPU_H_BG, CPU_SYSUI,
                CPU_NNAPI_HAL, CPU_RT, CPU_SYSTEM }) {
            sFileCache.put(p, new File(p));
            sGroups.put(p, new HashMap<>());
        }
        sMsgIds.put(CPU_SYS_BG, MSG_SYS_BG);
        sMsgIds.put(CPU_BG, MSG_BG);
        sMsgIds.put(CPU_TOP_APP, MSG_TOP_APP);
        sMsgIds.put(CPU_CAMERA, MSG_CAMERA);
        sMsgIds.put(CPU_FG, MSG_FG);
        sMsgIds.put(CPU_SVP, MSG_SVP);
        sMsgIds.put(CPU_DEX2OAT, MSG_DEX);
        sMsgIds.put(CPU_AX_FG, MSG_AX_FG);
        sMsgIds.put(CPU_L_BG, MSG_L_BG);
        sMsgIds.put(CPU_H_BG, MSG_H_BG);
    }

    private AxCpusetManager() {}

    public static void adjust(Handler handler, String path, String value, long duration,
            int callingUid) {
        AxUtils.logger("adjustCpuset: uid=" + callingUid + " path=" + path
                + " value=" + value + " duration=" + duration);
        HashMap<Integer, CpusetData> map = sGroups.get(path);
        if (map == null) {
            AxUtils.logger("unknown group: " + path);
            return;
        }
        long now = System.currentTimeMillis();
        long expiry = (duration == -1L) ? -1L : now + duration;
        CpusetData newData = new CpusetData(callingUid, value, now, expiry);

        if (duration >= 0) {
            CpusetData existing = map.get(callingUid);
            if (existing == null) {
                map.put(callingUid, newData);
            } else {
                if (existing.duration == -1L && (newData.duration == -1L || newData.duration > 0)
                        || (existing.duration > 0 && newData.duration > 0
                                && newData.duration < existing.duration)) {
                    AxUtils.logger(callingUid + " skip — already tracked");
                    return;
                }
                existing.duration = newData.duration;
            }
        } else if (duration == -1) {
            map.remove(callingUid);
        }

        File file = sFileCache.get(path);
        if (file != null && file.exists()) {
            try {
                FileUtils.stringToFile(file, value);
            } catch (IOException e) {
                AxUtils.logger("adjust cpuset failed");
            }
        }

        if (duration > 0) {
            Integer what = sMsgIds.get(path);
            if (what != null) {
                Message m = handler.obtainMessage(what);
                m.arg1 = callingUid;
                handler.sendMessageDelayed(m, duration);
            }
        }
    }

    public static void expireOverride(String path, int uid, String defaultCpus) {
        HashMap<Integer, CpusetData> map = sGroups.get(path);
        if (map == null) return;
        map.remove(uid);
        if (map.isEmpty()) {
            restoreCpuset(path, defaultCpus);
        }
    }

    public static void restoreCpuset(String path, String cpus) {
        if (cpus == null) return;
        File file = sFileCache.get(path);
        if (file == null) return;
        try {
            FileUtils.stringToFile(file, cpus);
            AxUtils.logger("restore: " + path + " cpus=" + cpus);
        } catch (IOException e) {
            AxUtils.logger("restore cpuset failed: " + path);
        }
    }

    static final class CpusetData {
        final int uid;
        final String value;
        final long currentTime;
        long duration;

        CpusetData(int uid, String value, long currentTime, long duration) {
            this.uid = uid;
            this.value = value;
            this.currentTime = currentTime;
            this.duration = duration;
        }
    }
}
