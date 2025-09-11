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

import android.os.Process;
import android.util.Slog;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.Arrays;

public class TaskProfiler {
    private static final String TAG = "TaskProfiler";

    public TaskProfiler() {
    }

    public void initTaskProfiles() {
        String[] bgProfiles = { "ProcessCapacityLow" };
        String[] bgProcs = { "kswapd", "kcompactd" };
        setTaskProfilesForProcs(bgProcs, bgProfiles);
    }

    public static void setTaskProfilesForProcs(String[] procGroups, String[] profiles) {
        File procDir = new File("/proc");
        File[] entries = procDir.listFiles(file -> file.isDirectory() && file.getName().matches("\\d+"));
        if (entries == null) {
            Slog.w(TAG, "/proc not accessible or empty.");
            return;
        }

        for (File pidDir : entries) {
            File commFile = new File(pidDir, "comm");
            String processName = null;

            try (BufferedReader reader = new BufferedReader(new FileReader(commFile))) {
                processName = reader.readLine();
            } catch (IOException e) {
                Slog.w(TAG, "Could not read " + commFile.getPath() + ": " + e);
                continue;
            }

            if (processName == null) continue;

            for (String proc : procGroups) {
                if (processName.contains(proc)) {
                    try {
                        int pid = Integer.parseInt(pidDir.getName());
                        Process.setTaskProfiles(pid, profiles);
                        Slog.i(TAG, "Applied profiles " + Arrays.toString(profiles) +
                                " to process " + processName + " (PID " + pid + ")");
                    } catch (NumberFormatException e) {
                        Slog.w(TAG, "Invalid PID: " + pidDir.getName());
                    } catch (Exception e) {
                        Slog.w(TAG, "Failed to set profiles for PID " + pidDir.getName() + ": " + e);
                    }
                    break;
                }
            }
        }
    }
}
