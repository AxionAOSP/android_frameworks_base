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

import static com.android.server.am.AxUtils.*;

import android.content.Context;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;

import com.android.server.NtServiceInjector;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;

public final class DeviceData {
    private static final String TAG = "DeviceData";

    private static final String CPU_SYS_PATH = "/sys/devices/system/cpu/cpu";
    private static final String SCALING_MIN_FREQ_FILE = "/cpufreq/scaling_min_freq";
    private static final String SCALING_MAX_FREQ_FILE = "/cpufreq/scaling_max_freq";
    private static final String CPUINFO_MAX_FREQ_FILE = "/cpufreq/cpuinfo_max_freq";
    private static final String SCALING_AVAILABLE_FREQ_FILE = "/cpufreq/scaling_available_frequencies";

    public static final String CPU_BG = AxUtils.cpuPath("background");
    public static final String CPU_SYS_BG = AxUtils.cpuPath("system-background");
    public static final String CPU_FG = AxUtils.cpuPath("foreground");
    public static final String CPU_AX_FG = AxUtils.cpuPath("ax_foreground");
    public static final String CPU_SVP = AxUtils.cpuPath("svp");
    public static final String CPU_DEX2OAT = AxUtils.cpuPath("dex2oat");
    public static final String CPU_TOP_APP = AxUtils.cpuPath("top-app");
    public static final String CPU_L_BG = AxUtils.cpuPath("l-background");
    public static final String CPU_H_BG = AxUtils.cpuPath("h-background");

    public static final String RESTRICTED_UC_MAX = AxUtils.cpuCtlPath("restricted", "/cpu.uclamp.max");
    public static final String RESTRICTED_UC_MIN = AxUtils.cpuCtlPath("restricted", "/cpu.uclamp.min");

    private final CpuData cData;
    private BoostData data;
    private Context mContext;

    public DeviceData() {
        mContext = NtServiceInjector.get().getContext();
        cData = initCpuProps();
        initDeviceMemoryData();
    }

    public BoostData getData() {
        return data;
    }

    public void updateSettings(
            int uSMin,
            int uBMin,
            int uPMin,
            int uSMax,
            int uBMax,
            int uPMax
    ) {
        data = new BoostData(
                cData.hasPrime,
                cData.sCores, cData.bCores, cData.pCores, cData.boostCpus,
                cData.sMin, cData.bMin, cData.pMin,
                cData.sMax, cData.bMax, cData.pMax, cData.allCores,
                s(uSMin), s(uBMin), s(uPMin),
                s(uSMax), s(uBMax), s(uPMax),
                cData.bgCpus, cData.fgCpus,
                cData.bgLimit, cData.uiLimit, cData.fgLimited
        );
        logger("updateSettings: " + data);
    }

    public static class BoostData {
        public final boolean hasPrime;

        public final String sCores;
        public final String bCores;
        public final String pCores;
        public final String boostCpus;
        public final String sMin;
        public final String bMin;
        public final String pMin;
        public final String sMax;
        public final String bMax;
        public final String pMax;
        public final String allCores;

        public final String uSMin;
        public final String uBMin;
        public final String uPMin;
        public final String uSMax;
        public final String uBMax;
        public final String uPMax;

        public final String bgCpus;
        public final String fgCpus;

        public final String bgLimit;
        public final String uiLimit;
        public final String fgLimited;

        public BoostData(
                boolean hasPrime,
                String sCores,
                String bCores,
                String pCores,
                String boostCpus,
                String sMin,
                String bMin,
                String pMin,
                String sMax,
                String bMax,
                String pMax,
                String allCores,
                String uSMin,
                String uBMin,
                String uPMin,
                String uSMax,
                String uBMax,
                String uPMax,
                String bgCpus,
                String fgCpus,
                String bgLimit,
                String uiLimit,
                String fgLimited
        ) {
            this.hasPrime = hasPrime;

            this.sCores = sCores;
            this.bCores = bCores;
            this.pCores = pCores;
            this.boostCpus = boostCpus;
            this.sMin = sMin;
            this.bMin = bMin;
            this.pMin = pMin;
            this.sMax = sMax;
            this.bMax = bMax;
            this.pMax = pMax;
            this.allCores = allCores;

            this.uSMin = uSMin;
            this.uBMin = uBMin;
            this.uPMin = uPMin;
            this.uSMax = uSMax;
            this.uBMax = uBMax;
            this.uPMax = uPMax;

            this.bgCpus = bgCpus;
            this.fgCpus = fgCpus;

            this.bgLimit = bgLimit;
            this.uiLimit = uiLimit;
            this.fgLimited = fgLimited;
        }
    }

    private static class CpuData {
        public final String sCores;
        public final String bCores;
        public final String pCores;
        public final String boostCpus;
        public final String sMin;
        public final String bMin;
        public final String pMin;
        public final String sMax;
        public final String bMax;
        public final String pMax;
        public final String allCores;

        public final String bgCpus;
        public final String fgCpus;
        public final String bgLimit;
        public final String uiLimit;
        public final String fgLimited;
        
        public boolean hasPrime;

        public CpuData(
                String sCores, String bCores, String pCores, String boostCpus,
                String sMin, String bMin, String pMin,
                String sMax, String bMax, String pMax,
                String allCores,
                String bgCpus, String fgCpus,
                String bgLimit, String uiLimit, String fgLimited,
                boolean hasPrime
        ) {
            this.sCores = sCores;
            this.bCores = bCores;
            this.pCores = pCores;
            this.boostCpus = boostCpus;
            this.sMin = sMin;
            this.bMin = bMin;
            this.pMin = pMin;
            this.sMax = sMax;
            this.bMax = bMax;
            this.pMax = pMax;
            this.allCores = allCores;
            this.bgCpus = bgCpus;
            this.fgCpus = fgCpus;
            this.bgLimit = bgLimit;
            this.uiLimit = uiLimit;
            this.hasPrime = hasPrime;
            this.fgLimited = fgLimited;
        }
    }

    private CpuData initCpuProps() {
        int cpuCount = Runtime.getRuntime().availableProcessors();
        if (cpuCount <= 0) return null;
        
        int[] maxFreqs = new int[cpuCount];
        TreeSet<Integer> uniqueFreqs = new TreeSet<>();
        
        for (int i = 0; i < cpuCount; i++) {
            String[] freqs = readAvailableFrequencies(String.valueOf(i));
            int maxForCore = 0;
            for (String f : freqs) {
                try {
                    int val = Integer.parseInt(f);
                    if (val > maxForCore) maxForCore = val;
                } catch (NumberFormatException ignored) {}
            }
            
            if (maxForCore <= 0) {
                maxForCore = AxUtils.readFreq(CPU_SYS_PATH + i + CPUINFO_MAX_FREQ_FILE);
                if (maxForCore <= 0) {
                    maxForCore = AxUtils.readFreq(CPU_SYS_PATH + i + SCALING_MAX_FREQ_FILE);
                }
            }
            maxFreqs[i] = maxForCore;
            if (maxForCore > 0) uniqueFreqs.add(maxForCore);
        }

        if (uniqueFreqs.isEmpty()) return null;

        List<Integer> sortedFreqs = new ArrayList<>(uniqueFreqs);
        int freqCount = sortedFreqs.size();
        
        int minFreq = sortedFreqs.get(0);
        int bigFreq = freqCount > 1 ? sortedFreqs.get(1) : -1;
        int maxFreq = sortedFreqs.get(freqCount - 1);
        
        StringBuilder small = new StringBuilder();
        StringBuilder big = new StringBuilder();
        StringBuilder prime = new StringBuilder();

        for (int i = 0; i < cpuCount; i++) {
            int f = maxFreqs[i];
            if (f == minFreq) {
                appendCpu(small, i);
            } else if (f == bigFreq) {
                if (freqCount > 2) {
                    appendCpu(big, i);
                } else {
                    appendCpu(big, i);
                }
            } else if (f == maxFreq) {
                if (freqCount > 2) {
                    appendCpu(prime, i);
                } else {
                    appendCpu(big, i);
                }
            } else {
                if (f < maxFreq) {
                    appendCpu(big, i);
                } else {
                    appendCpu(prime, i);
                }
            }
        }

        String sCores = small.toString();
        String bCores = big.toString();
        String pCores = prime.toString();
        String allCores = "0-" + (cpuCount - 1);

        String sIndex = firstIndex(sCores);
        String bIndex = firstIndex(bCores);
        String pIndex = firstIndex(pCores);

        String sMin = sIndex != null ? CPU_SYS_PATH + sIndex + SCALING_MIN_FREQ_FILE : null;
        String bMin = bIndex != null ? CPU_SYS_PATH + bIndex + SCALING_MIN_FREQ_FILE : null;
        String pMin = pIndex != null ? CPU_SYS_PATH + pIndex + SCALING_MIN_FREQ_FILE : null;

        String sMax = sIndex != null ? CPU_SYS_PATH + sIndex + SCALING_MAX_FREQ_FILE : null;
        String bMax = bIndex != null ? CPU_SYS_PATH + bIndex + SCALING_MAX_FREQ_FILE : null;
        String pMax = pIndex != null ? CPU_SYS_PATH + pIndex + SCALING_MAX_FREQ_FILE : null;
        
        if (sIndex != null) propSet("cpu_small_index", sIndex);
        if (bIndex != null) propSet("cpu_big_index", bIndex);
        if (pIndex != null) propSet("cpu_prime_index", pIndex);

        String[] sAvailableFreqs = readAvailableFrequencies(sIndex);
        String[] bAvailableFreqs = readAvailableFrequencies(bIndex);
        String[] pAvailableFreqs = readAvailableFrequencies(pIndex);

        if (sAvailableFreqs.length > 0) {
            Settings.Secure.putStringForUser(mContext.getContentResolver(),
                "ax_cpu_small_freqs", String.join(",", sAvailableFreqs), UserHandle.USER_CURRENT);
        }
        if (bAvailableFreqs.length > 0) {
            Settings.Secure.putStringForUser(mContext.getContentResolver(),
                "ax_cpu_big_freqs", String.join(",", bAvailableFreqs), UserHandle.USER_CURRENT);
        }
        if (pAvailableFreqs.length > 0) {
            Settings.Secure.putStringForUser(mContext.getContentResolver(),
                "ax_cpu_prime_freqs", String.join(",", pAvailableFreqs), UserHandle.USER_CURRENT);
        }

        String smallR = toRange(sCores);
        String bigR = toRange(bCores);
        String primeR = toRange(pCores);

        String bgCpus = rangeTo(sCores, 3);
        String fgLimited = joinRanges(smallR, rangeTo(bCores, 2));
        String fgCpus = allCores;
        String boostCpus = joinRanges(bigR, primeR);
        if (boostCpus.isEmpty()) boostCpus = allCores;

        String bgLimit = rangeTo(sCores, 2);
        String uiLimit = rangeTo(sCores, 3);
        
        propSet("cpu_small", sCores);
        propSet("cpu_big", bCores);
        propSet("cpu_prime", pCores);
        propSet("cpu_all", allCores);
        propSet("cpu_svp", boostCpus);
        propSet("cpu_bg", bgCpus);
        propSet("cpu_fg", fgCpus);
        propSet("cpu_audio", fgLimited);
        propSet("cpu_limit_bg", bgLimit);
        propSet("cpu_limit_ui", uiLimit);
        
        boolean hasPrime = pCores != null && !pCores.isEmpty();

        logger("initCpuProps: small=" + sCores +
                " big=" + bCores +
                " prime=" + pCores +
                " boostCpus=" + boostCpus +
                " uniqueFreqs=" + sortedFreqs +
                " allCores=" + allCores +
                " bgCpus=" + bgCpus +
                " fgCpus=" + fgCpus +
                " fgLimited=" + fgLimited +
                " cpu_limit_bg=" + bgLimit +
                " cpu_limit_ui=" + uiLimit +
                " hasPrime=" + hasPrime);

        return new CpuData(
                smallR, bigR, primeR, boostCpus,
                sMin, bMin, pMin,
                sMax, bMax, pMax,
                allCores,
                bgCpus, fgCpus,
                bgLimit, uiLimit, fgLimited,
                hasPrime
        );
    }
    
    private void initDeviceMemoryData() {
        int memGb = AxUtils.getMemTotal();
        if (memGb <= 0) return;
        AxUtils.propSetF("persist.sys.device_ram_size", String.valueOf(memGb));
        AxUtils.logger("initDeviceMemoryData: RAM size data: " + memGb + "GB");
    }

    private String[] readAvailableFrequencies(String cpuIndex) {
        if (cpuIndex == null) return new String[0];
        String path = CPU_SYS_PATH + cpuIndex + SCALING_AVAILABLE_FREQ_FILE;
        String freqsStr = AxUtils.readBufFile(path);
        if (freqsStr == null || freqsStr.trim().isEmpty()) {
            return new String[0];
        }
        return freqsStr.trim().split("\\s+");
    }

    private static String s(int val) {
        return String.valueOf(val);
    }
}
