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

public final class AxNodePaths {

    private static final String KEY_DRAM_MIN_FREQ = "dram_min_freq";
    private static final String KEY_DRAM_BOOST_HZ = "dram_boost_hz";
    private static final String KEY_DRAM_DEFAULT_HZ = "dram_default_hz";

    private static final String KEY_GPU_DVFS_MARGIN = "gpu_dvfs_margin";
    private static final String KEY_GPU_DVFS_MARGIN_BOOST = "gpu_dvfs_margin_boost";
    private static final String KEY_GPU_DVFS_MARGIN_DEFAULT = "gpu_dvfs_margin_default";

    private static final String KEY_GPU_DVFS_STEP = "gpu_dvfs_step";
    private static final String KEY_GPU_DVFS_STEP_BOOST = "gpu_dvfs_step_boost";
    private static final String KEY_GPU_DVFS_STEP_DEFAULT = "gpu_dvfs_step_default";

    private static final String KEY_GPU_BOOST_LEVEL = "gpu_boost_level";
    private static final String KEY_GPU_CUSTOM_BOOST_FREQ = "gpu_custom_boost_freq";
    private static final String KEY_GPU_CUSTOM_UPBOUND_FREQ = "gpu_custom_upbound_freq";
    private static final String KEY_GPU_DVFS_LOADING_MODE = "gpu_dvfs_loading_mode";
    private static final String KEY_GPU_DCS_MODE = "gpu_dcs_mode";

    private static final String KEY_MALI_MIN_FREQ = "mali_min_freq";
    private static final String KEY_MALI_MAX_FREQ = "mali_max_freq";

    private static final String KEY_CCI_FREQ = "cci_freq";

    private static final String KEY_PMQOS_CPU_DMA_LATENCY = "pmqos_cpu_dma_latency";
    private static final String KEY_PMQOS_DEFAULT_US = "pmqos_default_us";

    private static final String KEY_CPU_DVFS_HEADROOM = "cpu_dvfs_headroom";
    private static final String KEY_CPU_DVFS_HEADROOM_BOOST = "cpu_dvfs_headroom_boost";
    private static final String KEY_CPU_DVFS_HEADROOM_DEFAULT = "cpu_dvfs_headroom_default";

    private static final String KEY_CPU_UTIL_THRESHOLD = "cpu_util_threshold";
    private static final String KEY_CPU_TAPERED_DVFS_HEADROOM = "cpu_tapered_dvfs_headroom";

    private static final String KEY_VENDOR_SCHED_TA_UCLAMP = "vendor_sched_ta_uclamp";
    private static final String KEY_VENDOR_SCHED_FG_UCLAMP = "vendor_sched_fg_uclamp";
    private static final String KEY_VENDOR_SCHED_REDUCE_PREFER_IDLE =
            "vendor_sched_reduce_prefer_idle";

    private static final String KEY_CPU_DOWN_RATE_LIMIT_C0 = "cpu_down_rate_limit_c0";
    private static final String KEY_CPU_DOWN_RATE_LIMIT_C1 = "cpu_down_rate_limit_c1";
    private static final String KEY_CPU_DOWN_RATE_LIMIT_C2 = "cpu_down_rate_limit_c2";

    private static final String KEY_INT_MIN_FREQ = "int_min_freq";
    private static final String KEY_DISPLAY_HW_EARLY_WAKEUP = "display_hw_early_wakeup";

    private static volatile boolean sInitDone = false;

    private static volatile String sDramMinPath = null;
    private static volatile long sDramBoostHz = 0L;
    private static volatile long sDramDefaultHz = 0L;

    private static volatile String sGpuDvfsMarginPath = null;
    private static volatile long sGpuDvfsMarginBoost = 0L;
    private static volatile long sGpuDvfsMarginDefault = 0L;

    private static volatile String sGpuDvfsStepPath = null;
    private static volatile long sGpuDvfsStepBoost = 0L;
    private static volatile long sGpuDvfsStepDefault = 0L;

    private static volatile String sGpuBoostLevelPath = null;
    private static volatile String sGpuCustomBoostFreqPath = null;
    private static volatile String sGpuCustomUpboundFreqPath = null;
    private static volatile String sGpuDvfsLoadingModePath = null;
    private static volatile String sGpuDcsModePath = null;

    private static volatile String sMaliMinFreqPath = null;
    private static volatile String sMaliMaxFreqPath = null;

    private static volatile String sCciFreqPath = null;

    private static volatile String sPmqosPath = null;
    private static volatile long sPmqosDefaultUs = 0L;

    private static volatile String sCpuDvfsHeadroomPath = null;
    private static volatile long sCpuDvfsHeadroomBoost = 0L;
    private static volatile long sCpuDvfsHeadroomDefault = 0L;
    private static volatile String sCpuUtilThresholdPath = null;
    private static volatile String sCpuTaperedDvfsHeadroomPath = null;
    private static volatile String sVendorSchedTaUclampPath = null;
    private static volatile String sVendorSchedFgUclampPath = null;
    private static volatile String sVendorSchedReducePreferIdlePath = null;
    private static volatile String sCpuDownRateLimitC0Path = null;
    private static volatile String sCpuDownRateLimitC1Path = null;
    private static volatile String sCpuDownRateLimitC2Path = null;
    private static volatile String sIntMinFreqPath = null;
    private static volatile String sDisplayHwEarlyWakeupPath = null;

    private AxNodePaths() {}

    public static String getDramMinPath() {
        ensureInit();
        return sDramMinPath;
    }

    public static long getDramBoostHz() {
        ensureInit();
        return sDramBoostHz;
    }

    public static long getDramDefaultHz() {
        ensureInit();
        return sDramDefaultHz;
    }

    public static String getGpuDvfsMarginPath() {
        ensureInit();
        return sGpuDvfsMarginPath;
    }

    public static long getGpuDvfsMarginBoost() {
        ensureInit();
        return sGpuDvfsMarginBoost;
    }

    public static long getGpuDvfsMarginDefault() {
        ensureInit();
        return sGpuDvfsMarginDefault;
    }

    public static String getGpuDvfsStepPath() {
        ensureInit();
        return sGpuDvfsStepPath;
    }

    public static long getGpuDvfsStepBoost() {
        ensureInit();
        return sGpuDvfsStepBoost;
    }

    public static long getGpuDvfsStepDefault() {
        ensureInit();
        return sGpuDvfsStepDefault;
    }

    public static String getGpuBoostLevelPath() {
        ensureInit();
        return sGpuBoostLevelPath;
    }

    public static String getGpuCustomBoostFreqPath() {
        ensureInit();
        return sGpuCustomBoostFreqPath;
    }

    public static String getGpuCustomUpboundFreqPath() {
        ensureInit();
        return sGpuCustomUpboundFreqPath;
    }

    public static String getGpuDvfsLoadingModePath() {
        ensureInit();
        return sGpuDvfsLoadingModePath;
    }

    public static String getGpuDcsModePath() {
        ensureInit();
        return sGpuDcsModePath;
    }

    public static String getMaliMinFreqPath() {
        ensureInit();
        return sMaliMinFreqPath;
    }

    public static String getMaliMaxFreqPath() {
        ensureInit();
        return sMaliMaxFreqPath;
    }

    public static String getCciFreqPath() {
        ensureInit();
        return sCciFreqPath;
    }

    public static String getPmqosPath() {
        ensureInit();
        return sPmqosPath;
    }

    public static long getPmqosDefaultUs() {
        ensureInit();
        return sPmqosDefaultUs;
    }

    public static String getCpuDvfsHeadroomPath() {
        ensureInit();
        return sCpuDvfsHeadroomPath;
    }

    public static long getCpuDvfsHeadroomBoost() {
        ensureInit();
        return sCpuDvfsHeadroomBoost;
    }

    public static long getCpuDvfsHeadroomDefault() {
        ensureInit();
        return sCpuDvfsHeadroomDefault;
    }

    public static String getCpuUtilThresholdPath() {
        ensureInit();
        return sCpuUtilThresholdPath;
    }

    public static String getCpuTaperedDvfsHeadroomPath() {
        ensureInit();
        return sCpuTaperedDvfsHeadroomPath;
    }

    public static String getVendorSchedTaUclampPath() {
        ensureInit();
        return sVendorSchedTaUclampPath;
    }

    public static String getVendorSchedFgUclampPath() {
        ensureInit();
        return sVendorSchedFgUclampPath;
    }

    public static String getVendorSchedReducePreferIdlePath() {
        ensureInit();
        return sVendorSchedReducePreferIdlePath;
    }

    public static String getCpuDownRateLimitC0Path() {
        ensureInit();
        return sCpuDownRateLimitC0Path;
    }

    public static String getCpuDownRateLimitC1Path() {
        ensureInit();
        return sCpuDownRateLimitC1Path;
    }

    public static String getCpuDownRateLimitC2Path() {
        ensureInit();
        return sCpuDownRateLimitC2Path;
    }

    public static String getIntMinFreqPath() {
        ensureInit();
        return sIntMinFreqPath;
    }

    public static String getDisplayHwEarlyWakeupPath() {
        ensureInit();
        return sDisplayHwEarlyWakeupPath;
    }

    private static synchronized void ensureInit() {
        if (sInitDone) return;
        sInitDone = true;

        String dramPath = AxPerfConfig.getString(KEY_DRAM_MIN_FREQ, "");
        long dramBoost = AxPerfConfig.getLong(KEY_DRAM_BOOST_HZ, 0L);
        long dramDefault = AxPerfConfig.getLong(KEY_DRAM_DEFAULT_HZ, 0L);
        if (!dramPath.isEmpty() && AxUtils.readBufFile(dramPath) != null && dramBoost > 0L) {
            sDramMinPath = dramPath;
            sDramBoostHz = dramBoost;
            sDramDefaultHz = dramDefault;
            AxUtils.logger("Dram init: path=" + dramPath + " boostHz=" + dramBoost);
        }

        sGpuDvfsMarginPath = resolvePath(KEY_GPU_DVFS_MARGIN);
        sGpuDvfsMarginBoost = AxPerfConfig.getLong(KEY_GPU_DVFS_MARGIN_BOOST, 0L);
        sGpuDvfsMarginDefault = AxPerfConfig.getLong(KEY_GPU_DVFS_MARGIN_DEFAULT, 0L);

        sGpuDvfsStepPath = resolvePath(KEY_GPU_DVFS_STEP);
        sGpuDvfsStepBoost = AxPerfConfig.getLong(KEY_GPU_DVFS_STEP_BOOST, 0L);
        sGpuDvfsStepDefault = AxPerfConfig.getLong(KEY_GPU_DVFS_STEP_DEFAULT, 0L);

        sGpuBoostLevelPath = resolvePath(KEY_GPU_BOOST_LEVEL);
        sGpuCustomBoostFreqPath = resolvePath(KEY_GPU_CUSTOM_BOOST_FREQ);
        sGpuCustomUpboundFreqPath = resolvePath(KEY_GPU_CUSTOM_UPBOUND_FREQ);
        sGpuDvfsLoadingModePath = resolvePath(KEY_GPU_DVFS_LOADING_MODE);
        sGpuDcsModePath = resolvePath(KEY_GPU_DCS_MODE);

        sMaliMinFreqPath = resolvePath(KEY_MALI_MIN_FREQ);
        sMaliMaxFreqPath = resolvePath(KEY_MALI_MAX_FREQ);

        sCciFreqPath = resolvePath(KEY_CCI_FREQ);

        sPmqosPath = resolvePath(KEY_PMQOS_CPU_DMA_LATENCY);
        sPmqosDefaultUs = AxPerfConfig.getLong(KEY_PMQOS_DEFAULT_US, 1_000_000L);

        sCpuDvfsHeadroomPath = resolvePath(KEY_CPU_DVFS_HEADROOM);
        sCpuDvfsHeadroomBoost = AxPerfConfig.getLong(KEY_CPU_DVFS_HEADROOM_BOOST, 0L);
        sCpuDvfsHeadroomDefault = AxPerfConfig.getLong(KEY_CPU_DVFS_HEADROOM_DEFAULT, 0L);

        sCpuUtilThresholdPath = resolvePath(KEY_CPU_UTIL_THRESHOLD);
        sCpuTaperedDvfsHeadroomPath = resolvePath(KEY_CPU_TAPERED_DVFS_HEADROOM);
        sVendorSchedTaUclampPath = resolvePath(KEY_VENDOR_SCHED_TA_UCLAMP);
        sVendorSchedFgUclampPath = resolvePath(KEY_VENDOR_SCHED_FG_UCLAMP);
        sVendorSchedReducePreferIdlePath = resolvePath(KEY_VENDOR_SCHED_REDUCE_PREFER_IDLE);

        sCpuDownRateLimitC0Path = resolvePath(KEY_CPU_DOWN_RATE_LIMIT_C0);
        sCpuDownRateLimitC1Path = resolvePath(KEY_CPU_DOWN_RATE_LIMIT_C1);
        sCpuDownRateLimitC2Path = resolvePath(KEY_CPU_DOWN_RATE_LIMIT_C2);

        sIntMinFreqPath = resolvePath(KEY_INT_MIN_FREQ);
        sDisplayHwEarlyWakeupPath = resolvePath(KEY_DISPLAY_HW_EARLY_WAKEUP);
    }

    private static String resolvePath(String key) {
        String p = AxPerfConfig.getString(key, "");
        if (p.isEmpty()) return null;
        if (AxUtils.readBufFile(p) == null) return null;
        return p;
    }
}
