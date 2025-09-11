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

import android.graphics.Point;
import android.os.Process;
import android.os.SystemProperties;

import com.android.server.wm.WindowManagerService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;

public class MemoryManager {
    private final ActivityManagerService mAm;
    private final WindowManagerService mWindowManager;

    public MemoryManager(ActivityManagerService am) {
        this.mAm = am;
        this.mWindowManager = am.mWindowManager;
    }

    public void releaseMemory(int minAdj, int maxKillCount, boolean includeUIProcesses, boolean skipCamera) {
        if (minAdj == 0) return;

        try {
            ArrayList<ProcessRecord> processList =
                    (ArrayList<ProcessRecord>) mAm.mProcessList.getLruProcessesLOSP().clone();

            ArrayList<ProcessToKill> toKill = new ArrayList<>();

            for (ProcessRecord record : processList) {
                if (record != null && record.getSetAdj() >= minAdj) {
                    boolean hasUI = record.hasActivities();
                    if ((!hasUI || includeUIProcesses) && !shouldSkip(record, skipCamera)) {
                        toKill.add(new ProcessToKill(record));
                    }
                }
            }

            Collections.sort(toKill, new ProcessComparator());

            int killedCount = 0;
            for (ProcessToKill info : toKill) {
                Process.killProcess(info.pid);
                killedCount++;
                if (killedCount >= maxKillCount) return;
            }

        } catch (Exception e) {
        }
    }

    public void updateExtraFree() {
        if (mWindowManager != null) {
            Point displaySize = new Point();
            mWindowManager.getBaseDisplaySize(0, displaySize);
            int extraFreeFactor = 6; // calculated from n2a with 61279 efk = factor ≈ 61279 / (((1080*2412)*4)/1024) ≈ 6.02
            int extraFreeKb = (((displaySize.x * displaySize.y) * 4) * extraFreeFactor) / 1024;
            SystemProperties.set("sys.sysctl.extra_free_kbytes", Integer.toString(extraFreeKb));
        }
    }

    private boolean shouldSkip(ProcessRecord record, boolean skipCamera) {
        if (skipCamera && record.processName != null && record.processName.contains("camera")) {
            return true;
        }
        return false;
    }

    private static class ProcessComparator implements Comparator<ProcessToKill> {
        @Override
        public int compare(ProcessToKill p1, ProcessToKill p2) {
            return Integer.compare(p2.adj, p1.adj);
        }
    }

    private static final class ProcessToKill {
        final int adj;
        final String name;
        final int pid;
        final int uid;
        final ProcessRecord record;

        ProcessToKill(ProcessRecord record) {
            this.pid = record.getPid();
            this.uid = record.uid;
            this.adj = record.getSetAdj();
            this.name = record.processName;
            this.record = record;
        }
    }
}
