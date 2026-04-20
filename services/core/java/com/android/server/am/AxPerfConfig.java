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

import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

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

    private static volatile boolean sLoaded = false;
    private static final Map<String, String> sMap = new HashMap<>();
    private static final Map<String, HintProfile> sHints = new HashMap<>();

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
                AxUtils.logger("AxPerfConfig loaded " + path
                        + " entries=" + sMap.size() + " hints=" + sHints.size());
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
                } catch (NumberFormatException ignored) {}
            }
            event = parser.next();
        }
        return b.build();
    }

    public static final class Res {
        public static final String CPU_MIN_C0 = "cpu_min_c0";
        public static final String CPU_MIN_C1 = "cpu_min_c1";
        public static final String CPU_MIN_C2 = "cpu_min_c2";
        public static final String CPU_MAX_C0 = "cpu_max_c0";
        public static final String CPU_MAX_C1 = "cpu_max_c1";
        public static final String CPU_MAX_C2 = "cpu_max_c2";
        public static final String DRAM_MIN = "dram_min";
        public static final String GPU_BOOST = "gpu_boost";
        public static final String GPU_MAX = "gpu_max";
        public static final String CPUSET_SVP = "cpuset_svp";
        public static final String CPUSET_SYSUI = "cpuset_sysui";
        public static final String CPUSET_TOP = "cpuset_top";
        public static final String UCLAMP_FG = "uclamp_fg";
        public static final String UCLAMP_TOP = "uclamp_top";
        public static final String RR_FLING = "rr_fling";

        private Res() {}
    }

    public static final class Hint {
        public static final String LAUNCH = "launch";
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
        public static final String IME_SHOW_HIDE = "ime_show_hide";
        public static final String IME_INIT = "ime_init";

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
