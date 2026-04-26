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

import android.util.Xml;

import com.android.server.thermal.AxAdvancedThermalMitigationConfig;

import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class AxPerfConfig {

    private static final String VENDOR_CONFIG = "/vendor/etc/ax_perf_config.xml";
    private static final String SYSTEM_CONFIG = "/system/etc/ax_perf_config.xml";
    private static final String ROOT_TAG = "perf-config";
    private static final String PATH_TAG = "path";
    private static final String INT_TAG = "int";
    private static final String HINT_TAG = "hint";
    private static final String RES_TAG = "res";
    private static final String ATTR_NAME = "name";
    private static final String KEY_DURATION_MS = "duration_ms";

    private static final String ATMC_TAG = "atmc";
    private static final String ATMC_CPU_LEVELS_TAG = "cpu_levels";
    private static final String ATMC_GPU_LEVELS_TAG = "gpu_levels";
    private static final String ATMC_BOOST_SCENARIOS_TAG = "boost_scenarios";
    private static final String ATMC_SCENES_TAG = "scenes";
    private static final String ATMC_APPS_TAG = "apps";
    private static final String ATMC_COMPLEXES_TAG = "complexes";
    private static final String ATMC_BUFFER_RATES_TAG = "buffer_rates";
    private static final String ATMC_LEVEL_TAG = "level";
    private static final String ATMC_ACTION_TAG = "action";
    private static final String ATMC_SCENE_TAG = "scene";
    private static final String ATMC_APP_TAG = "app";
    private static final String ATMC_COMPLEX_TAG = "complex";
    private static final String ATMC_SCENARIO_TAG = "scenario";
    private static final String ATMC_FPS_TAG = "fps";
    private static final String ATTR_ID = "id";
    private static final String ATTR_PKGS = "pkgs";
    private static final String ATTR_KEY = "key";
    private static final String ATTR_UNIT = "unit";
    private static final String ATTR_STATUS = "status";
    private static final String ATTR_VALUE = "value";
    private static final String ATTR_LITTLE = "little";
    private static final String ATTR_BIG = "big";
    private static final String ATTR_TITANIUM = "titanium";
    private static final String ATTR_PRIME = "prime";
    private static final String ATTR_CURRENT_MA = "current_ma";
    private static final String ATTR_MIN = "min";
    private static final String ATTR_MAX = "max";
    private static final String ATTR_PARAMS = "params";

    private static final String UX_THREADS_TAG = "ux_threads";
    private static final String UX_ROLE_TAG = "role";
    private static final String ATTR_UCLAMP_MIN = "uclamp_min";
    private static final String ATTR_UCLAMP_MAX = "uclamp_max";

    private static volatile boolean sLoaded = false;
    private static final Map<String, String> sMap = new HashMap<>();
    private static final Map<String, HintProfile> sHints = new HashMap<>();
    private static volatile AxAdvancedThermalMitigationConfig sAtmc =
            AxAdvancedThermalMitigationConfig.EMPTY;
    private static final Map<String, int[]> sUxThreadRoles = new HashMap<>();

    private AxPerfConfig() {}

    public static String getString(String name, String def) {
        ensureLoaded();
        String v = sMap.get(name);
        return v != null ? v : def;
    }

    public static int getInt(String name, int def) {
        ensureLoaded();
        String v = sMap.get(name);
        if (v == null) return def;
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public static long getLong(String name, long def) {
        ensureLoaded();
        String v = sMap.get(name);
        if (v == null) return def;
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public static HintProfile getHint(String name) {
        ensureLoaded();
        HintProfile h = sHints.get(name);
        return h != null ? h : HintProfile.EMPTY;
    }

    public static Map<String, HintProfile> getAllHints() {
        ensureLoaded();
        return Collections.unmodifiableMap(sHints);
    }

    public static AxAdvancedThermalMitigationConfig getAtmc() {
        ensureLoaded();
        return sAtmc;
    }

    public static int[] getUxThreadUclamp(String role) {
        ensureLoaded();
        return sUxThreadRoles.get(role);
    }

    private static synchronized void ensureLoaded() {
        if (sLoaded) return;
        sLoaded = true;
        if (!loadFrom(VENDOR_CONFIG)) {
            loadFrom(SYSTEM_CONFIG);
        }
    }

    private static boolean loadFrom(String path) {
        try {
            File f = new File(path);
            if (!f.exists() || !f.canRead()) return false;
            try (InputStream in = new FileInputStream(f)) {
                XmlPullParser parser = Xml.newPullParser();
                parser.setInput(in, null);
                parseRoot(parser);
                AxUtils.logger(
                        "AxPerfConfig loaded "
                                + path
                                + " entries="
                                + sMap.size()
                                + " hints="
                                + sHints.size());
                return !sMap.isEmpty() || !sHints.isEmpty();
            }
        } catch (Exception e) {
            AxUtils.logger("AxPerfConfig parse fail " + path + ": " + e);
            return false;
        }
    }

    private static void parseRoot(XmlPullParser parser) throws Exception {
        int event = parser.getEventType();
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                String tag = parser.getName();
                if (HINT_TAG.equals(tag)) {
                    String name = parser.getAttributeValue(null, ATTR_NAME);
                    HintProfile h = parseHint(parser);
                    if (name != null) sHints.put(name, h);
                } else if (PATH_TAG.equals(tag) || INT_TAG.equals(tag)) {
                    String name = parser.getAttributeValue(null, ATTR_NAME);
                    if (name != null) {
                        String text = parser.nextText();
                        if (text != null) sMap.put(name, text.trim());
                    }
                } else if (ATMC_TAG.equals(tag)) {
                    sAtmc = parseAtmc(parser);
                } else if (UX_THREADS_TAG.equals(tag)) {
                    parseUxThreads(parser);
                }
            }
            event = parser.next();
        }
    }

    private static AxAdvancedThermalMitigationConfig parseAtmc(XmlPullParser parser)
            throws Exception {
        AxAdvancedThermalMitigationConfig.Builder b =
                new AxAdvancedThermalMitigationConfig.Builder();
        int depth = parser.getDepth();
        int event = parser.next();
        while (!(event == XmlPullParser.END_TAG && parser.getDepth() == depth)
                && event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                String tag = parser.getName();
                switch (tag) {
                    case ATMC_CPU_LEVELS_TAG:
                        parseCpuLevels(parser, b);
                        break;
                    case ATMC_GPU_LEVELS_TAG:
                        parseGpuLevels(parser, b);
                        break;
                    case ATMC_BOOST_SCENARIOS_TAG:
                        parseBoostScenarios(parser, b);
                        break;
                    case ATMC_SCENES_TAG:
                        parseScenes(parser, b);
                        break;
                    case ATMC_APPS_TAG:
                        parseApps(parser, b);
                        break;
                    case ATMC_COMPLEXES_TAG:
                        parseComplexes(parser, b);
                        break;
                    case ATMC_BUFFER_RATES_TAG:
                        parseBufferRates(parser, b);
                        break;
                    default:
                        break;
                }
            }
            event = parser.next();
        }
        return b.build();
    }

    private static int parseAttrInt(XmlPullParser parser, String name, int def) {
        String v = parser.getAttributeValue(null, name);
        if (v == null) return def;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static int[] parsePair(String s, int def) {
        if (s == null) return new int[] {def, def};
        String[] parts = s.split(",");
        if (parts.length != 2) return new int[] {def, def};
        try {
            return new int[] {Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim())};
        } catch (NumberFormatException e) {
            return new int[] {def, def};
        }
    }

    private static long[] parseLongList(String s) {
        if (s == null) return new long[0];
        String[] parts = s.split(",");
        long[] out = new long[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                out[i] = Long.parseLong(parts[i].trim());
            } catch (NumberFormatException e) {
                out[i] = 0;
            }
        }
        return out;
    }

    private static List<String> splitPkgs(String s) {
        if (s == null || s.isEmpty()) return Collections.emptyList();
        String[] parts = s.split(",");
        List<String> out = new ArrayList<>(parts.length);
        for (String p : parts) {
            String t = p.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    private static void parseCpuLevels(
            XmlPullParser parser, AxAdvancedThermalMitigationConfig.Builder b) throws Exception {
        int depth = parser.getDepth();
        int event = parser.next();
        while (!(event == XmlPullParser.END_TAG && parser.getDepth() == depth)
                && event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && ATMC_LEVEL_TAG.equals(parser.getName())) {
                int id = parseAttrInt(parser, ATTR_ID, -1);
                int[] little = parsePair(parser.getAttributeValue(null, ATTR_LITTLE), -1);
                int[] big = parsePair(parser.getAttributeValue(null, ATTR_BIG), -1);
                int[] titanium = parsePair(parser.getAttributeValue(null, ATTR_TITANIUM), -1);
                int[] prime = parsePair(parser.getAttributeValue(null, ATTR_PRIME), -1);
                int currentMa = parseAttrInt(parser, ATTR_CURRENT_MA, -1);
                if (id >= 0) {
                    b.addCpuLevel(
                            new AxAdvancedThermalMitigationConfig.CpuLevel(
                                    id,
                                    little[0],
                                    little[1],
                                    big[0],
                                    big[1],
                                    titanium[0],
                                    titanium[1],
                                    prime[0],
                                    prime[1],
                                    currentMa));
                }
            }
            event = parser.next();
        }
    }

    private static void parseGpuLevels(
            XmlPullParser parser, AxAdvancedThermalMitigationConfig.Builder b) throws Exception {
        int depth = parser.getDepth();
        int event = parser.next();
        while (!(event == XmlPullParser.END_TAG && parser.getDepth() == depth)
                && event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && ATMC_LEVEL_TAG.equals(parser.getName())) {
                int id = parseAttrInt(parser, ATTR_ID, -1);
                int min = parseAttrInt(parser, ATTR_MIN, -1);
                int max = parseAttrInt(parser, ATTR_MAX, -1);
                if (id >= 0)
                    b.addGpuLevel(new AxAdvancedThermalMitigationConfig.GpuLevel(id, min, max));
            }
            event = parser.next();
        }
    }

    private static void parseBoostScenarios(
            XmlPullParser parser, AxAdvancedThermalMitigationConfig.Builder b) throws Exception {
        int depth = parser.getDepth();
        int event = parser.next();
        while (!(event == XmlPullParser.END_TAG && parser.getDepth() == depth)
                && event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && ATMC_SCENARIO_TAG.equals(parser.getName())) {
                int id = parseAttrInt(parser, ATTR_ID, -1);
                String name = parser.getAttributeValue(null, ATTR_NAME);
                long[] params = parseLongList(parser.getAttributeValue(null, ATTR_PARAMS));
                if (id >= 0 && name != null) {
                    b.addBoostScenario(
                            new AxAdvancedThermalMitigationConfig.BoostScenario(id, name, params));
                }
            }
            event = parser.next();
        }
    }

    private static TreeMap<Integer, AxAdvancedThermalMitigationConfig.LevelRule> parseLevelRules(
            XmlPullParser parser) throws Exception {
        TreeMap<Integer, AxAdvancedThermalMitigationConfig.LevelRule> out = new TreeMap<>();
        int depth = parser.getDepth();
        int event = parser.next();
        while (!(event == XmlPullParser.END_TAG && parser.getDepth() == depth)
                && event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && ATMC_LEVEL_TAG.equals(parser.getName())) {
                int level = parseAttrInt(parser, ATTR_ID, -1);
                Map<String, Integer> actions = parseActions(parser);
                if (level >= 0) {
                    out.put(level, new AxAdvancedThermalMitigationConfig.LevelRule(level, actions));
                }
            }
            event = parser.next();
        }
        return out;
    }

    private static Map<String, Integer> parseActions(XmlPullParser parser) throws Exception {
        Map<String, Integer> out = new HashMap<>();
        int depth = parser.getDepth();
        int event = parser.next();
        while (!(event == XmlPullParser.END_TAG && parser.getDepth() == depth)
                && event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && ATMC_ACTION_TAG.equals(parser.getName())) {
                String unit = parser.getAttributeValue(null, ATTR_UNIT);
                int status = parseAttrInt(parser, ATTR_STATUS, -1);
                if (unit != null) out.put(unit, status);
            }
            event = parser.next();
        }
        return out;
    }

    private static void parseScenes(
            XmlPullParser parser, AxAdvancedThermalMitigationConfig.Builder b) throws Exception {
        int depth = parser.getDepth();
        int event = parser.next();
        while (!(event == XmlPullParser.END_TAG && parser.getDepth() == depth)
                && event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && ATMC_SCENE_TAG.equals(parser.getName())) {
                String name = parser.getAttributeValue(null, ATTR_NAME);
                TreeMap<Integer, AxAdvancedThermalMitigationConfig.LevelRule> levels =
                        parseLevelRules(parser);
                if (name != null)
                    b.addScene(new AxAdvancedThermalMitigationConfig.Scene(name, levels));
            }
            event = parser.next();
        }
    }

    private static void parseApps(XmlPullParser parser, AxAdvancedThermalMitigationConfig.Builder b)
            throws Exception {
        int depth = parser.getDepth();
        int event = parser.next();
        while (!(event == XmlPullParser.END_TAG && parser.getDepth() == depth)
                && event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && ATMC_APP_TAG.equals(parser.getName())) {
                List<String> pkgs = splitPkgs(parser.getAttributeValue(null, ATTR_PKGS));
                TreeMap<Integer, AxAdvancedThermalMitigationConfig.LevelRule> levels =
                        parseLevelRules(parser);
                if (!pkgs.isEmpty())
                    b.addApp(new AxAdvancedThermalMitigationConfig.AppRule(pkgs, levels));
            }
            event = parser.next();
        }
    }

    private static void parseComplexes(
            XmlPullParser parser, AxAdvancedThermalMitigationConfig.Builder b) throws Exception {
        int depth = parser.getDepth();
        int event = parser.next();
        while (!(event == XmlPullParser.END_TAG && parser.getDepth() == depth)
                && event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && ATMC_COMPLEX_TAG.equals(parser.getName())) {
                String key = parser.getAttributeValue(null, ATTR_KEY);
                List<String> tokens =
                        key != null ? Arrays.asList(key.split("\\|")) : Collections.emptyList();
                TreeMap<Integer, AxAdvancedThermalMitigationConfig.LevelRule> levels =
                        parseLevelRules(parser);
                if (key != null)
                    b.addComplex(
                            new AxAdvancedThermalMitigationConfig.ComplexRule(key, tokens, levels));
            }
            event = parser.next();
        }
    }

    private static void parseBufferRates(
            XmlPullParser parser, AxAdvancedThermalMitigationConfig.Builder b) throws Exception {
        int depth = parser.getDepth();
        int event = parser.next();
        while (!(event == XmlPullParser.END_TAG && parser.getDepth() == depth)
                && event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && ATMC_APP_TAG.equals(parser.getName())) {
                List<String> pkgs = splitPkgs(parser.getAttributeValue(null, ATTR_PKGS));
                Map<Integer, Integer> levelToFps = parseFpsRules(parser);
                if (!pkgs.isEmpty())
                    b.addBufferRate(
                            new AxAdvancedThermalMitigationConfig.BufferRateRule(pkgs, levelToFps));
            }
            event = parser.next();
        }
    }

    private static Map<Integer, Integer> parseFpsRules(XmlPullParser parser) throws Exception {
        Map<Integer, Integer> out = new HashMap<>();
        int depth = parser.getDepth();
        int event = parser.next();
        while (!(event == XmlPullParser.END_TAG && parser.getDepth() == depth)
                && event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && ATMC_FPS_TAG.equals(parser.getName())) {
                int level = parseAttrInt(parser, ATMC_LEVEL_TAG, -1);
                int value = parseAttrInt(parser, ATTR_VALUE, -1);
                if (level >= 0 && value >= 0) out.put(level, value);
            }
            event = parser.next();
        }
        return out;
    }

    private static void parseUxThreads(XmlPullParser parser) throws Exception {
        int depth = parser.getDepth();
        int event = parser.next();
        while (!(event == XmlPullParser.END_TAG && parser.getDepth() == depth)
                && event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && UX_ROLE_TAG.equals(parser.getName())) {
                String name = parser.getAttributeValue(null, ATTR_NAME);
                int min = parseAttrInt(parser, ATTR_UCLAMP_MIN, -1);
                int max = parseAttrInt(parser, ATTR_UCLAMP_MAX, -1);
                if (name != null && min >= 0 && max >= 0) {
                    sUxThreadRoles.put(name, new int[] {min, max});
                }
            }
            event = parser.next();
        }
    }

    private static HintProfile parseHint(XmlPullParser parser) throws Exception {
        HintProfile.Builder b = new HintProfile.Builder();
        int depth = parser.getDepth();
        int event = parser.next();
        while (!(event == XmlPullParser.END_TAG && parser.getDepth() == depth)
                && event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                String tag = parser.getName();
                String name = parser.getAttributeValue(null, ATTR_NAME);
                if (name == null) {
                    event = parser.next();
                    continue;
                }
                String text = parser.nextText();
                if (text == null) {
                    event = parser.next();
                    continue;
                }
                text = text.trim();
                try {
                    long value = Long.parseLong(text);
                    if (INT_TAG.equals(tag) && KEY_DURATION_MS.equals(name)) {
                        b.setDuration(value);
                    } else if (RES_TAG.equals(tag)) {
                        b.putRes(name, value);
                    }
                } catch (NumberFormatException ignored) {
                }
            }
            event = parser.next();
        }
        return b.build();
    }

    public static final class Res {
        public static final String CPU_MIN_C0 = "cpu_min_c0";
        public static final String CPU_MIN_C1 = "cpu_min_c1";
        public static final String CPU_MIN_C2 = "cpu_min_c2";
        public static final String CPU_MIN_C3 = "cpu_min_c3";
        public static final String CPU_MAX_C0 = "cpu_max_c0";
        public static final String CPU_MAX_C1 = "cpu_max_c1";
        public static final String CPU_MAX_C2 = "cpu_max_c2";
        public static final String CPU_MAX_C3 = "cpu_max_c3";
        public static final String DRAM_MIN = "dram_min";
        public static final String DRAM_LAT_MIN = "dram_lat_min";
        public static final String GPU_BOOST = "gpu_boost";
        public static final String GPU_MAX = "gpu_max";
        public static final String GPU_MIN_POWER_LEVEL = "gpu_min_power_level";
        public static final String GPU_MAX_POWER_LEVEL = "gpu_max_power_level";
        public static final String GPU_IDLE_TIMER = "gpu_idle_timer";
        public static final String GPU_TOUCH_WAKEUP = "gpu_touch_wakeup";
        public static final String GPU_DVFS_MARGIN = "gpu_dvfs_margin";
        public static final String GPU_DVFS_STEP = "gpu_dvfs_step";
        public static final String GPU_BOOST_LEVEL = "gpu_boost_level";
        public static final String GPU_CUSTOM_BOOST_FREQ = "gpu_custom_boost_freq";
        public static final String GPU_CUSTOM_UPBOUND_FREQ = "gpu_custom_upbound_freq";
        public static final String GPU_DVFS_LOADING_MODE = "gpu_dvfs_loading_mode";
        public static final String GPU_DCS_MODE = "gpu_dcs_mode";
        public static final String GPU_PROC_FG = "gpu_proc_fg";
        public static final String GPU_PROC_BG = "gpu_proc_bg";
        public static final String MALI_MIN_FREQ = "mali_min_freq";
        public static final String MALI_MAX_FREQ = "mali_max_freq";
        public static final String LLCC_BW_MIN = "llcc_bw_min";
        public static final String LLCC_BW_MAX = "llcc_bw_max";
        public static final String LLCC_IO_PERCENT = "llcc_io_percent";
        public static final String LLCC_HYST_LENGTH = "llcc_hyst_length";
        public static final String LLCC_SAMPLE_MS = "llcc_sample_ms";
        public static final String LLCC_DDR_MIN_FREQ = "llcc_ddr_min_freq";
        public static final String LLCC_DDR_IO_PERCENT = "llcc_ddr_io_percent";
        public static final String LLCC_DDR_HYST = "llcc_ddr_hyst";
        public static final String LLCC_DDR_SAMPLE_MS = "llcc_ddr_sample_ms";
        public static final String CPUBW_HWMON_SAMPLE_MS = "cpubw_hwmon_sample_ms";
        public static final String CPUBW_HWMON_IO_PERCENT = "cpubw_hwmon_io_percent";
        public static final String CPUBW_HWMON_HYST_OPT = "cpubw_hwmon_hyst_opt";
        public static final String GOLD_DYNPREFETCHER = "gold_dynprefetcher";
        public static final String LLCC_DYNPREFETCHER = "llcc_dynprefetcher";
        public static final String SWAP_RATIO = "swap_ratio";
        public static final String MEM_RECLAIM_ENABLE = "mem_reclaim_enable";
        public static final String CCI_FREQ = "cci_freq";
        public static final String CPU_DVFS_HEADROOM = "cpu_dvfs_headroom";
        public static final String CPU_UTIL_THRESHOLD = "cpu_util_threshold";
        public static final String CPU_TAPERED_DVFS_HEADROOM = "cpu_tapered_dvfs_headroom";
        public static final String VENDOR_SCHED_TA_UCLAMP = "vendor_sched_ta_uclamp";
        public static final String VENDOR_SCHED_FG_UCLAMP = "vendor_sched_fg_uclamp";
        public static final String VENDOR_SCHED_REDUCE_PREFER_IDLE =
                "vendor_sched_reduce_prefer_idle";
        public static final String CPU_DOWN_RATE_LIMIT_C0 = "cpu_down_rate_limit_c0";
        public static final String CPU_DOWN_RATE_LIMIT_C1 = "cpu_down_rate_limit_c1";
        public static final String CPU_DOWN_RATE_LIMIT_C2 = "cpu_down_rate_limit_c2";
        public static final String INT_MIN_FREQ = "int_min_freq";
        public static final String DISPLAY_HW_EARLY_WAKEUP = "display_hw_early_wakeup";
        public static final String SCHED_BOOST = "sched_boost";
        public static final String SCHED_UPMIGRATE = "sched_upmigrate";
        public static final String SCHED_DOWNMIGRATE = "sched_downmigrate";
        public static final String SCHED_BUSY_HYSTERESIS_MASK = "sched_busy_hysteresis_mask";
        public static final String LPM_BIAS = "lpm_bias";
        public static final String SCHED_IDLE_ENOUGH = "sched_idle_enough";
        public static final String HISPEED_FREQ_C0 = "hispeed_freq_c0";
        public static final String HISPEED_FREQ_C1 = "hispeed_freq_c1";
        public static final String HISPEED_FREQ_C2 = "hispeed_freq_c2";
        public static final String HISPEED_FREQ_C3 = "hispeed_freq_c3";
        public static final String HISPEED_LOAD = "hispeed_load";
        public static final String SCHEDUTIL_PL_DISABLE_C0 = "schedutil_pl_disable_c0";
        public static final String SCHEDUTIL_PL_DISABLE_C1 = "schedutil_pl_disable_c1";
        public static final String SCHEDUTIL_PL_DISABLE_C2 = "schedutil_pl_disable_c2";
        public static final String SCHEDUTIL_PL_DISABLE_C3 = "schedutil_pl_disable_c3";
        public static final String SCHEDUTIL_FREQ_C0 = "schedutil_freq_c0";
        public static final String SCHEDUTIL_FREQ_C1 = "schedutil_freq_c1";
        public static final String SCHEDUTIL_FREQ_C2 = "schedutil_freq_c2";
        public static final String SCHEDUTIL_FREQ_C3 = "schedutil_freq_c3";
        public static final String MIN_PARTIAL_HALT_C3 = "min_partial_halt_c3";
        public static final String TITANIUM_STATE2_OFFLINE_TIMEOUT =
                "titanium_state2_offline_timeout";
        public static final String CORE_NOT_PREFERRED = "core_not_preferred";
        public static final String PWR_CLPS_DISABLE = "pwr_clps_disable";
        public static final String MIN_ONLINE_CPU_BIG = "min_online_cpu_big";
        public static final String CPUSET_SVP = "cpuset_svp";
        public static final String CPUSET_SYSUI = "cpuset_sysui";
        public static final String CPUSET_TOP = "cpuset_top";
        public static final String CPUSET_CAMERA = "cpuset_camera";
        public static final String CPUSET_NNAPI = "cpuset_nnapi";
        public static final String CPUSET_RT = "cpuset_rt";
        public static final String CPUSET_SYSTEM = "cpuset_system";
        public static final String CPUSET_FG = "cpuset_fg";
        public static final String CPUSET_BG = "cpuset_bg";
        public static final String CPUSET_AX_FG = "cpuset_ax_fg";
        public static final String CPUSET_L_BG = "cpuset_l_bg";
        public static final String CPUSET_H_BG = "cpuset_h_bg";
        public static final String CPUSET_DEX2OAT = "cpuset_dex2oat";
        public static final String UCLAMP_FG = "uclamp_fg";
        public static final String UCLAMP_TOP = "uclamp_top";
        public static final String UCLAMP_FG_MAX = "uclamp_fg_max";
        public static final String UCLAMP_TOP_MAX = "uclamp_top_max";
        public static final String UCLAMP_FG_LAT = "uclamp_fg_lat";
        public static final String UCLAMP_TOP_LAT = "uclamp_top_lat";
        public static final String TID_UCLAMP_MIN = "tid_uclamp_min";
        public static final String PMQOS_CPU_DMA_LATENCY = "pmqos_cpu_dma_latency";
        public static final String STORAGE_CLK_SCALING = "storage_clk_scaling";
        public static final String STORAGE_BOOST_MIN_THRESH = "storage_boost_min_thresh";
        public static final String STORAGE_BOOST_MAX_THRESH = "storage_boost_max_thresh";
        public static final String DISPLAY_PERF_MODE = "display_perf_mode";
        public static final String PIN_FILE_PATH = "pin_file_path";
        public static final String RR_FLING = "rr_fling";

        private Res() {}
    }

    public static final class Hint {
        public static final String LAUNCH = "launch";
        public static final String FIRST_LAUNCH = "first_launch";
        public static final String SUBSEQ_LAUNCH = "subseq_launch";
        public static final String WARM_LAUNCH = "warm_launch";
        public static final String ACTIVITY_BOOST = "activity_boost";
        public static final String ACTIVITY_TRANSITION = "activity_transition";
        public static final String ACTIVITY_EXIT = "activity_exit";
        public static final String FLING = "fling";
        public static final String SHADE = "shade";
        public static final String GPU = "gpu";
        public static final String GAME = "game";
        public static final String SCROLL_INPUT = "scroll_input";
        public static final String SCROLL_PREFILING = "scroll_prefiling";
        public static final String SCROLL_VERTICAL = "scroll_vertical";
        public static final String SCROLL_SCROLLER = "scroll_scroller";
        public static final String FRAME_PREFETCHER = "frame_prefetcher";
        public static final String FRAME_REAL_DRAW = "frame_real_draw";
        public static final String FRAME_OBTAIN_VIEW = "frame_obtain_view";
        public static final String FRAME_RENDER_INFO = "frame_render_info";
        public static final String FRAME_PRE_ANIM = "frame_pre_anim";
        public static final String FRAME_REQUEST_VSYNC = "frame_request_vsync";
        public static final String FRAME_DRAW = "frame_draw";
        public static final String FRAME_DRAW_STEP = "frame_draw_step";
        public static final String IME_SHOW_HIDE = "ime_show_hide";
        public static final String IME_INIT = "ime_init";
        public static final String IME_LAUNCH = "ime_launch";
        public static final String TOUCH_BOOST = "touch_boost";
        public static final String TAP_EVENT = "tap_event";
        public static final String DRAG = "drag";
        public static final String DRAG_START = "drag_start";
        public static final String DRAG_END = "drag_end";
        public static final String MTP_BOOST = "mtp_boost";
        public static final String ROTATION_LATENCY = "rotation_latency";
        public static final String ROTATION_ANIM = "rotation_anim";
        public static final String PERFORMANCE_MODE = "performance_mode";
        public static final String SCENARIO_GPU = "scenario_gpu";
        public static final String SCENARIO_CPU = "scenario_cpu";
        public static final String SCENARIO_CPU_GPU = "scenario_cpu_gpu";
        public static final String SCENARIO_CPU_AGGRESSIVE = "scenario_cpu_aggressive";
        public static final String PACKAGE_INSTALL_BOOST = "package_install_boost";
        public static final String PKG_INSTALL = "pkg_install";
        public static final String PKG_UNINSTALL = "pkg_uninstall";
        public static final String APP_UPDATE = "app_update";
        public static final String BINDAPP = "bindapp";
        public static final String KILL = "kill";
        public static final String FIRST_DRAW = "first_draw";
        public static final String GPU_APP_FG = "gpu_app_fg";
        public static final String GPU_APP_BG = "gpu_app_bg";
        public static final String BOOST_RENDERTHREAD = "boost_renderthread";
        public static final String PIN_FILE = "pin_file";
        public static final String UNPIN_FILE = "unpin_file";
        public static final String EARLY_WAKEUP = "early_wakeup";
        public static final String EXPENSIVE_RENDERING = "expensive_rendering";
        public static final String TRANSITION = "transition";
        public static final String TRANSITION_LITE = "transition_lite";

        private Hint() {}
    }

    public static final class HintProfile {
        public static final HintProfile EMPTY = new Builder().build();

        public final Map<String, Long> res;
        public final long durationMs;

        private HintProfile(Builder b) {
            this.res = Collections.unmodifiableMap(b.res);
            this.durationMs = b.durationMs;
        }

        static final class Builder {
            final LinkedHashMap<String, Long> res = new LinkedHashMap<>();
            long durationMs;

            void putRes(String name, long value) {
                res.put(name, value);
            }

            void setDuration(long v) {
                durationMs = v;
            }

            HintProfile build() {
                return new HintProfile(this);
            }
        }
    }
}
