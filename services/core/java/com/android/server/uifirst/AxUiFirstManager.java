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
package com.android.server.uifirst;

import android.os.Process;
import android.util.Log;

import com.android.server.am.AxPerfConfig;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;

public final class AxUiFirstManager implements IAxUiFirstManager {
    private static final String TAG = "AxUiFirstManager";
    private static final boolean DBG = false;

    private static final int RESET_UCLAMP_MIN = 0;
    private static final int RESET_UCLAMP_MAX = 1024;

    private static final int DEFAULT_RT_MIN = 512;
    private static final int DEFAULT_RT_MAX = 1024;
    private static final int DEFAULT_UI_MIN = 384;
    private static final int DEFAULT_UI_MAX = 1024;
    private static final int DEFAULT_RENDER_MIN = 512;
    private static final int DEFAULT_RENDER_MAX = 1024;
    private static final int DEFAULT_GL_MIN = 384;
    private static final int DEFAULT_GL_MAX = 900;
    private static final int DEFAULT_HWUI_TASK_MIN = 256;
    private static final int DEFAULT_HWUI_TASK_MAX = 800;
    private static final int DEFAULT_BINDER_POOL_MIN = 200;
    private static final int DEFAULT_BINDER_POOL_MAX = 700;

    private static final int EARLY_WAKEUP_RENDER_MIN = 768;
    private static final int EARLY_WAKEUP_UI_MIN = 640;
    private static final int EARLY_WAKEUP_HWUI_MIN = 384;

    private static final int MEDIA_DAMPEN_MAX = 256;

    @Override
    public void setUxThreads(int uid, int pid, int[] tids, int role) {
        if (tids == null || tids.length == 0) return;
        int[] cfg = uclampForRole(role);
        for (int tid : tids) {
            applyUclamp(tid, cfg[0], cfg[1]);
        }
        if (DBG) Log.d(TAG, "setUxThreads pid=" + pid + " role=" + role + " count=" + tids.length);
    }

    @Override
    public void clearUxThreads(int uid, int pid, int role) {}

    @Override
    public void applyTopAppRoles(int uid, int pid, int renderTid) {
        int[] uiCfg = uclampForRole(ROLE_UI);
        applyUclamp(pid, uiCfg[0], uiCfg[1]);
        if (renderTid > 0) {
            int[] rtCfg = uclampForRole(ROLE_RENDER);
            applyUclamp(renderTid, rtCfg[0], rtCfg[1]);
        }
        int[] hwuiTids = discoverThreadsByPrefix(pid, "hwuiTask");
        if (hwuiTids.length > 0) {
            int[] hwCfg = uclampForRole(ROLE_HWUI_TASK);
            for (int tid : hwuiTids) {
                applyUclamp(tid, hwCfg[0], hwCfg[1]);
            }
        }
        int[] glTids = discoverThreadsByPrefix(pid, "GLThread");
        if (glTids.length > 0) {
            int[] glCfg = uclampForRole(ROLE_GL);
            for (int tid : glTids) {
                applyUclamp(tid, glCfg[0], glCfg[1]);
            }
        }
        int[] binderTids = discoverThreadsByPrefix(pid, "binder:");
        if (binderTids.length > 0) {
            int[] bpCfg = uclampForRole(ROLE_BINDER_POOL);
            for (int tid : binderTids) {
                applyUclamp(tid, bpCfg[0], bpCfg[1]);
            }
        }
        if (DBG)
            Log.d(
                    TAG,
                    "applyTopAppRoles pid="
                            + pid
                            + " hwui="
                            + hwuiTids.length
                            + " gl="
                            + glTids.length
                            + " binder="
                            + binderTids.length);
    }

    @Override
    public void clearTopAppRoles(int uid, int pid) {
        applyUclamp(pid, RESET_UCLAMP_MIN, RESET_UCLAMP_MAX);
        for (int tid : discoverThreadsByPrefix(pid, "hwuiTask")) {
            applyUclamp(tid, RESET_UCLAMP_MIN, RESET_UCLAMP_MAX);
        }
        for (int tid : discoverThreadsByPrefix(pid, "GLThread")) {
            applyUclamp(tid, RESET_UCLAMP_MIN, RESET_UCLAMP_MAX);
        }
        for (int tid : discoverThreadsByPrefix(pid, "binder:")) {
            applyUclamp(tid, RESET_UCLAMP_MIN, RESET_UCLAMP_MAX);
        }
    }

    @Override
    public void dampenMediaForInputBurst(int pid, boolean active) {
        if (pid <= 0) return;
        int[] dav1dTids = discoverThreadsByPrefix(pid, "dav1d");
        int[] mcTids = discoverThreadsByPrefix(pid, "MediaCodec");
        int[] imgTids = discoverThreadsByPrefix(pid, "Image Decod");
        if (active) {
            for (int tid : dav1dTids) applyUclamp(tid, RESET_UCLAMP_MIN, MEDIA_DAMPEN_MAX);
            for (int tid : mcTids) applyUclamp(tid, RESET_UCLAMP_MIN, MEDIA_DAMPEN_MAX);
            for (int tid : imgTids) applyUclamp(tid, RESET_UCLAMP_MIN, MEDIA_DAMPEN_MAX);
        } else {
            for (int tid : dav1dTids) applyUclamp(tid, RESET_UCLAMP_MIN, RESET_UCLAMP_MAX);
            for (int tid : mcTids) applyUclamp(tid, RESET_UCLAMP_MIN, RESET_UCLAMP_MAX);
            for (int tid : imgTids) applyUclamp(tid, RESET_UCLAMP_MIN, RESET_UCLAMP_MAX);
        }
    }

    @Override
    public void boostTopAppForEarlyWakeup(int pid, int renderTid, boolean active) {
        if (pid <= 0) return;
        if (active) {
            applyUclamp(pid, EARLY_WAKEUP_UI_MIN, DEFAULT_UI_MAX);
            if (renderTid > 0) {
                applyUclamp(renderTid, EARLY_WAKEUP_RENDER_MIN, DEFAULT_RENDER_MAX);
            }
            for (int tid : discoverThreadsByPrefix(pid, "hwuiTask")) {
                applyUclamp(tid, EARLY_WAKEUP_HWUI_MIN, DEFAULT_HWUI_TASK_MAX);
            }
        } else {
            int[] uiCfg = uclampForRole(ROLE_UI);
            applyUclamp(pid, uiCfg[0], uiCfg[1]);
            if (renderTid > 0) {
                int[] rtCfg = uclampForRole(ROLE_RENDER);
                applyUclamp(renderTid, rtCfg[0], rtCfg[1]);
            }
            int[] hwCfg = uclampForRole(ROLE_HWUI_TASK);
            for (int tid : discoverThreadsByPrefix(pid, "hwuiTask")) {
                applyUclamp(tid, hwCfg[0], hwCfg[1]);
            }
        }
    }

    @Override
    public void setBinderUx(int uid, int pid, boolean enable) {
        int[] tids = discoverThreadsByPrefix(pid, "binder:");
        if (tids.length == 0) return;
        if (enable) {
            int[] cfg = uclampForRole(ROLE_BINDER_POOL);
            for (int tid : tids) applyUclamp(tid, cfg[0], cfg[1]);
        } else {
            for (int tid : tids) applyUclamp(tid, RESET_UCLAMP_MIN, RESET_UCLAMP_MAX);
        }
        if (DBG)
            Log.d(TAG, "setBinderUx pid=" + pid + " enable=" + enable + " count=" + tids.length);
    }

    @Override
    public void setImeRelevant(int uid, int pid, boolean enable) {
        if (enable) {
            int[] uiCfg = uclampForRole(ROLE_UI);
            applyUclamp(pid, uiCfg[0], uiCfg[1]);
            int[] binderTids = discoverThreadsByPrefix(pid, "binder:");
            int[] bpCfg = uclampForRole(ROLE_BINDER_POOL);
            for (int tid : binderTids) applyUclamp(tid, bpCfg[0], bpCfg[1]);
            if (DBG)
                Log.d(TAG, "setImeRelevant pid=" + pid + " enabled binder=" + binderTids.length);
        } else {
            applyUclamp(pid, RESET_UCLAMP_MIN, RESET_UCLAMP_MAX);
            for (int tid : discoverThreadsByPrefix(pid, "binder:")) {
                applyUclamp(tid, RESET_UCLAMP_MIN, RESET_UCLAMP_MAX);
            }
        }
    }

    public static int[] discoverThreadsByPrefix(int pid, String namePrefix) {
        File taskDir = new File("/proc/" + pid + "/task");
        if (!taskDir.isDirectory()) return new int[0];
        File[] tasks = taskDir.listFiles();
        if (tasks == null) return new int[0];
        List<Integer> result = new ArrayList<>();
        for (File t : tasks) {
            if (!t.isDirectory()) continue;
            int tid;
            try {
                tid = Integer.parseInt(t.getName());
            } catch (NumberFormatException e) {
                continue;
            }
            File commFile = new File(t, "comm");
            if (!commFile.exists()) continue;
            try (BufferedReader br = new BufferedReader(new FileReader(commFile))) {
                String name = br.readLine();
                if (name != null && name.startsWith(namePrefix)) {
                    result.add(tid);
                }
            } catch (Exception ignored) {
            }
        }
        int[] out = new int[result.size()];
        for (int i = 0; i < result.size(); i++) out[i] = result.get(i);
        return out;
    }

    private static void applyUclamp(int tid, int min, int max) {
        try {
            Process.setThreadUtilClamp(tid, min, max);
        } catch (Exception e) {
            if (DBG) Log.w(TAG, "setThreadUtilClamp failed tid=" + tid, e);
        }
    }

    private static int[] uclampForRole(int role) {
        String name = roleName(role);
        if (name != null) {
            int[] xml = AxPerfConfig.getUxThreadUclamp(name);
            if (xml != null) return xml;
        }
        switch (role) {
            case ROLE_RT:
                return new int[] {DEFAULT_RT_MIN, DEFAULT_RT_MAX};
            case ROLE_UI:
                return new int[] {DEFAULT_UI_MIN, DEFAULT_UI_MAX};
            case ROLE_RENDER:
                return new int[] {DEFAULT_RENDER_MIN, DEFAULT_RENDER_MAX};
            case ROLE_GL:
                return new int[] {DEFAULT_GL_MIN, DEFAULT_GL_MAX};
            case ROLE_HWUI_TASK:
                return new int[] {DEFAULT_HWUI_TASK_MIN, DEFAULT_HWUI_TASK_MAX};
            case ROLE_BINDER_POOL:
                return new int[] {DEFAULT_BINDER_POOL_MIN, DEFAULT_BINDER_POOL_MAX};
            default:
                return new int[] {RESET_UCLAMP_MIN, RESET_UCLAMP_MAX};
        }
    }

    private static String roleName(int role) {
        switch (role) {
            case ROLE_RT:
                return "rt";
            case ROLE_UI:
                return "ui";
            case ROLE_RENDER:
                return "render";
            case ROLE_GL:
                return "gl";
            case ROLE_HWUI_TASK:
                return "hwui_task";
            case ROLE_BINDER_POOL:
                return "binder_pool";
            default:
                return null;
        }
    }
}
