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

import android.content.Context;
import android.os.UserHandle;
import android.provider.Settings;

import com.android.server.NtServiceInjector;

import java.util.Arrays;

public final class AxGpuData {

    private static final int BOOST_PERCENT_GPU = 67;
    private static final int GPU_OPP_DEFAULT_INDEX = -1;

    private static volatile String sMinPath = null;
    private static volatile int sBoostHz = 0;
    private static volatile int sDefaultMinHz = 0;
    private static volatile String[] sFreqs = new String[0];
    private static volatile boolean sInitDone = false;

    private static volatile boolean sUseOpp = false;
    private static volatile String sOppPath = null;
    private static volatile int sBoostOppIndex = GPU_OPP_DEFAULT_INDEX;
    private static volatile int sOppCount = 0;

    private AxGpuData() {}

    public static String getGpuMinPath() { ensureInit(); return sMinPath; }
    public static int getGpuBoostHz() { ensureInit(); return sBoostHz; }
    public static int getGpuDefaultMinHz() { ensureInit(); return sDefaultMinHz; }
    public static String[] getGpuFreqs() { ensureInit(); return sFreqs; }

    public static boolean isGpuOppMode() { ensureInit(); return sUseOpp; }
    public static String getGpuOppPath() { ensureInit(); return sOppPath; }
    public static int getGpuBoostOppIndex() { ensureInit(); return sBoostOppIndex; }
    public static int getGpuDefaultOppIndex() { return GPU_OPP_DEFAULT_INDEX; }

    private static synchronized void ensureInit() {
        if (sInitDone) return;
        sInitDone = true;

        if (initOpp()) return;

        String minPath = AxPerfConfig.getString("gpu_min_freq", "");
        String freqsPath = AxPerfConfig.getString("gpu_freqs", "");
        if (minPath.isEmpty() || freqsPath.isEmpty()) return;

        String[] freqs = readFreqs(freqsPath);
        if (freqs.length == 0) return;

        int boostHz = DeviceData.pickBoostFreq(freqs, BOOST_PERCENT_GPU);
        int idleHz = DeviceData.pickMinFreq(freqs);
        if (boostHz <= 0) return;

        sMinPath = minPath;
        sBoostHz = boostHz;
        sDefaultMinHz = idleHz;
        sFreqs = freqs;

        Context ctx = NtServiceInjector.get().getContext();
        if (ctx != null) {
            Settings.Secure.putStringForUser(ctx.getContentResolver(),
                "ax_gpu_freqs", String.join(",", freqs), UserHandle.USER_CURRENT);
        }

        AxUtils.logger("Gpu init: minPath=" + minPath + " freqs=" + Arrays.toString(freqs)
                + " boostHz=" + boostHz + " idleHz=" + idleHz);
    }

    private static boolean initOpp() {
        String oppIndexPath = AxPerfConfig.getString("gpu_opp_index", "");
        String oppTablePath = AxPerfConfig.getString("gpu_opp_table", "");
        if (oppIndexPath.isEmpty() || oppTablePath.isEmpty()) return false;

        String buf = AxUtils.readBufFile(oppIndexPath);
        if (buf == null) {
            AxUtils.logger("GPU opp path not found!!");
            AxUtils.propSet("gpu_boost_use_opp", "false");
            return false;
        }

        int maxOpp = parseOppCount(oppTablePath);
        if (maxOpp <= 0) {
            AxUtils.logger("GPU opp not available!!");
            AxUtils.propSet("gpu_boost_use_opp", "false");
            return false;
        }

        sOppCount = maxOpp;
        sBoostOppIndex = (int) ((long) maxOpp * (100 - BOOST_PERCENT_GPU) / 100);
        if (sBoostOppIndex < 0) sBoostOppIndex = 0;
        sOppPath = oppIndexPath;
        sUseOpp = true;

        AxUtils.propSet("gpu_boost_use_opp", "true");

        AxUtils.write(oppIndexPath, String.valueOf(GPU_OPP_DEFAULT_INDEX));

        AxUtils.logger("Gpu OPP init: indexPath=" + oppIndexPath
                + " tablePath=" + oppTablePath
                + " oppCount=" + maxOpp
                + " boostIndex=" + sBoostOppIndex);
        return true;
    }

    private static int parseOppCount(String tablePath) {
        String buf = AxUtils.readBufFile(tablePath);
        if (buf == null || buf.trim().isEmpty()) return 0;
        int count = 0;
        for (int i = 0; i < buf.length(); i++) {
            if (buf.charAt(i) == '[') count++;
        }
        return count > 0 ? count - 1 : 0;
    }

    private static String[] readFreqs(String path) {
        String buf = AxUtils.readBufFile(path);
        if (buf == null || buf.trim().isEmpty()) return new String[0];
        return buf.trim().split("\\s+");
    }
}
