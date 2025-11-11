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

import static com.android.server.am.AxUtils.*;

import android.content.Context;
import android.os.SystemProperties;
import android.provider.Settings;

import com.android.server.NtServiceInjector;

import java.util.Arrays;

public final class DeviceData {
    private static final String TAG = "DeviceData";

    private static final String CPU_SYS_PATH = "/sys/devices/system/cpu/cpu";
    private static final String SCALING_MIN_FREQ_FILE = "/cpufreq/scaling_min_freq";
    private static final String SCALING_MAX_FREQ_FILE = "/cpufreq/scaling_max_freq";

    private static final String GPU_FREQS_PATH = AxUtils.prop("gpu_freqs_path", "");
    private static final String GPU_MIN_FILE = AxUtils.prop("gpu_minfreq_file", "");
    public static final boolean GPU_BOOST_SUPPORT = !GPU_FREQS_PATH.isEmpty() && !GPU_MIN_FILE.isEmpty();

    public static final String CPU_BG = AxUtils.cpuPath("background");
    public static final String CPU_SYS_BG = AxUtils.cpuPath("system-background");
    public static final String CPU_FG = AxUtils.cpuPath("foreground");
    public static final String CPU_NT_FG = AxUtils.cpuPath("nt_foreground");
    public static final String CPU_RESTRICTED = AxUtils.cpuPath("restricted");
    public static final String CPU_DEX2OAT = AxUtils.cpuPath("dex2oat");
    public static final String CPU_DISPLAY = AxUtils.cpuPath("display");
    public static final String CPU_TOP_APP = AxUtils.cpuPath("top-app");

    public static final String RESTRICTED_UC_MAX = AxUtils.cpuCtlPath("restricted", "/cpu.uclamp.max");
    public static final String RESTRICTED_UC_MIN = AxUtils.cpuCtlPath("restricted", "/cpu.uclamp.min");

    private final CpuData cData;
    private String[] gpuAvailableFreqs;
    private BoostData data;
    private Context mContext;

    public DeviceData() {
        mContext = NtServiceInjector.get().getContext();
        cData = initCpuProps();
        initGpuData();
        initDeviceMemoryData();
    }

    public BoostData getData() {
        return data;
    }

    public void updateSettings(
            boolean cpuBoost,
            boolean bigBoost,
            boolean primeBoost,
            boolean sfBoost,
            boolean inputBoost,
            int fBoost,
            int fBoostB,
            int fBoostP,
            int uSMin,
            int uBMin,
            int uPMin,
            int uSMax,
            int uBMax,
            int uPMax,
            int gGpuBoost,
            int sGpuBoost
    ) {
        data = new BoostData(
                cpuBoost, bigBoost, primeBoost, sfBoost, inputBoost, cData.hasPrime,
                cData.sCores, cData.bCores, cData.pCores, cData.boostCpus,
                cData.sMin, cData.bMin, cData.pMin,
                cData.sMax, cData.bMax, cData.pMax, cData.allCores,
                s(fBoost), s(fBoostB), s(fBoostP),
                s(uSMin), s(uBMin), s(uPMin),
                s(uSMax), s(uBMax), s(uPMax),
                cData.bgCpus, cData.fgCpus,
                cData.bgLimit, cData.uiLimit, cData.fgLimited,
                gGpuBoost, sGpuBoost
        );
        logger("updateSettings: " + data);
    }

    public static class BoostData {
        public final boolean cpuBoost;
        public final boolean bigBoost;
        public final boolean primeBoost;
        public final boolean sfBoost;
        public final boolean inputBoost;
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

        public final String fBoost;
        public final String fBoostB;
        public final String fBoostP;
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
        
        public final int gGpuBoost;
        public final int sGpuBoost;

        public BoostData(
                boolean cpuBoost,
                boolean bigBoost,
                boolean primeBoost,
                boolean sfBoost,
                boolean inputBoost,
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
                String fBoost,
                String fBoostB,
                String fBoostP,
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
                String fgLimited,
                int gGpuBoost,
                int sGpuBoost
        ) {
            this.cpuBoost = cpuBoost;
            this.bigBoost = bigBoost;
            this.primeBoost = primeBoost;
            this.sfBoost = sfBoost;
            this.inputBoost = inputBoost;
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

            this.fBoost = fBoost;
            this.fBoostB = fBoostB;
            this.fBoostP = fBoostP;
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
            
            this.gGpuBoost = gGpuBoost;
            this.sGpuBoost = sGpuBoost;
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
        for (int i = 0; i < cpuCount; i++) {
            String path = CPU_SYS_PATH + i + SCALING_MAX_FREQ_FILE;
            AxUtils.write(path, s(Integer.MAX_VALUE));
            maxFreqs[i] = AxUtils.readFreq(path);
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
            if (f == minFreq) appendCpu(small, i);
            else if (midFreq != -1 && f == midFreq) appendCpu(big, i);
            else if (f == maxFreq) appendCpu(prime, i);
        }

        if (prime.length() == 0) {
            if (big.length() > 0) prime.append(big);
            else prime.append(small);
        }

        String sCores = small.toString();
        String bCores = big.toString();
        String pCores = prime.toString();
        String allCores = "0-" + (cpuCount - 1);

        String sIndex = firstIndex(sCores);
        String bIndex = firstIndex(bCores);
        String pIndex = firstIndex(pCores);

        String sMin = CPU_SYS_PATH + sIndex + SCALING_MIN_FREQ_FILE;
        String bMin = CPU_SYS_PATH + bIndex + SCALING_MIN_FREQ_FILE;
        String pMin = CPU_SYS_PATH + pIndex + SCALING_MIN_FREQ_FILE;

        String sMax = CPU_SYS_PATH + sIndex + SCALING_MAX_FREQ_FILE;
        String bMax = CPU_SYS_PATH + bIndex + SCALING_MAX_FREQ_FILE;
        String pMax = CPU_SYS_PATH + pIndex + SCALING_MAX_FREQ_FILE;
        
        propSet("cpu_small_index", sIndex);
        propSet("cpu_big_index", bIndex);
        propSet("cpu_prime_index", pIndex);

        String sMaxFreq = readFile(sMax);
        String bMaxFreq = readFile(bMax);
        String pMaxFreq = readFile(pMax);

        propSetF("persist.sys.ax_max_cpu_freqs", joinString(sMaxFreq, bMaxFreq, pMaxFreq));

        String smallR = toRange(sCores);
        String bigR = toRange(bCores);
        String primeR = toRange(pCores);

        String bgCpus = rangeTo(sCores, 3);
        String fgCpus = allCores;
        String fgLimited = joinRanges(smallR, rangeTo(bCores, 2));
        String boostCpus = joinRanges(bigR, primeR);

        String bgLimit = rangeTo(sCores, 2);
        String uiLimit = rangeTo(sCores, 3);
        
        propSet("cpu_small", sCores);
        propSet("cpu_big", bCores);
        propSet("cpu_prime", pCores);
        propSet("cpu_all", allCores);
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
                " freqs=" + minFreq + "," + (midFreq > 0 ? midFreq : maxFreq) + "," + maxFreq +
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

    private void initGpuData() {
        if (!GPU_BOOST_SUPPORT) return;
        String freqs = AxUtils.readBufFile(GPU_FREQS_PATH);
        if (freqs == null || freqs.isEmpty()) return;

        String[] freqsArr = freqs.trim().split("\\s+");
        int[] freqInts = new int[freqsArr.length];
        for (int i = 0; i < freqsArr.length; i++) freqInts[i] = Integer.parseInt(freqsArr[i]);
        Arrays.sort(freqInts);

        gpuAvailableFreqs = new String[freqInts.length];
        for (int i = 0; i < freqInts.length; i++) {
            gpuAvailableFreqs[i] = String.valueOf(freqInts[i]);
        }
        propSet("gpu_levels", String.valueOf(gpuAvailableFreqs.length));
        logger("initGpuData: GPU available freqs=" + String.join(",", gpuAvailableFreqs) +
                " gpu_levels=" + gpuAvailableFreqs.length);
    }
    
    private void initDeviceMemoryData() {
        int memGb = AxUtils.getMemTotal();
        if (memGb <= 0) return;
        AxUtils.propSetF("persist.sys.device_ram_size", String.valueOf(memGb));
        AxUtils.logger("initDeviceMemoryData: RAM size data: " + memGb + "GB");
    }

    public void boostGpu(int level) {
        if (!GPU_BOOST_SUPPORT || gpuAvailableFreqs == null) return;
        int idx = Math.max(0, Math.min(level, gpuAvailableFreqs.length - 1));
        String boostFreq = gpuAvailableFreqs[idx];
        AxUtils.write(GPU_MIN_FILE, boostFreq);
        logger("GPU boosted to level=" + idx + " freq=" + boostFreq);
    }

    private static String s(int val) {
        return String.valueOf(val);
    }
}
