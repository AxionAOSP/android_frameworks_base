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

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class AxPerfConfig {

    private static final String VENDOR_THERMAL_CONFIG = "/vendor/etc/ax_perf_thermal.xml";
    private static final String SYSTEM_THERMAL_CONFIG = "/system/etc/ax_perf_thermal.xml";
    private static final String VENDOR_THREADS_CONFIG = "/vendor/etc/ax_perf_threads.xml";
    private static final String SYSTEM_THREADS_CONFIG = "/system/etc/ax_perf_threads.xml";
    private static final String ROOT_TAG = "perf-config";
    private static final String PATH_TAG = "path";
    private static final String INT_TAG = "int";
    private static final String ATTR_NAME = "name";
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
        boolean loaded = loadFrom(VENDOR_THERMAL_CONFIG, ATMC_TAG)
                || loadFrom(SYSTEM_THERMAL_CONFIG, ATMC_TAG);
        loaded |= loadFrom(VENDOR_THREADS_CONFIG, UX_THREADS_TAG)
                || loadFrom(SYSTEM_THREADS_CONFIG, UX_THREADS_TAG);
        if (!loaded) {
            AxUtils.logger("AxPerfConfig missing all configs, hints may be unavailable");
        }
    }

    private static boolean loadFrom(String path, String tagFilter) {
        try {
            File f = new File(path);
            if (!f.exists() || !f.canRead()) return false;
            try (InputStream in = new FileInputStream(f)) {
                XmlPullParser parser = Xml.newPullParser();
                parser.setInput(in, null);
                parseRoot(parser, tagFilter);
                AxUtils.logger(
                        "AxPerfConfig loaded "
                                + path
                                + " entries="
                                + sMap.size());
                return !sMap.isEmpty()
                        || sAtmc != AxAdvancedThermalMitigationConfig.EMPTY
                        || !sUxThreadRoles.isEmpty();
            }
        } catch (Exception e) {
            AxUtils.logger("AxPerfConfig parse fail " + path + ": " + e);
            return false;
        }
    }

    private static void parseRoot(XmlPullParser parser, String tagFilter) throws Exception {
        int event = parser.getEventType();
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                String tag = parser.getName();
                if (tagFilter != null && !tagFilter.equals(tag)
                        && !ROOT_TAG.equals(tag)
                        && !PATH_TAG.equals(tag)
                        && !INT_TAG.equals(tag)) {
                    event = parser.next();
                    continue;
                }
                if (PATH_TAG.equals(tag) || INT_TAG.equals(tag)) {
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


}
