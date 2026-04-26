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
package com.android.server.thermal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class AxAdvancedThermalMitigationConfig {

    public static final String SCENE_DEFAULT = "default_scene";
    public static final String SCENE_GAME = "game";
    public static final String SCENE_POP_UP_WIN = "pop_up_win";
    public static final String SCENE_HEATING_FGS = "heating_fgs";
    public static final String SCENE_NEARBY = "nearby";
    public static final String SCENE_CAMERA_PHOTO = "axcamera_photo_preview";

    public static final String UNIT_CPU = "cpu";
    public static final String UNIT_GPU = "gpu";
    public static final String UNIT_FPS = "fps";
    public static final String UNIT_BRIGHTNESS = "brightness";
    public static final String UNIT_BG_KILLER = "bg_killer";
    public static final String UNIT_DEXOPT = "dexopt";
    public static final String UNIT_NETWORK_LIMIT = "network_speed_limit";
    public static final String UNIT_FILE_LIMIT = "file_speed_limit";
    public static final String UNIT_CAMERA_BRIGHTNESS = "cameraBrightness";
    public static final String UNIT_DISABLE_CAMERA = "disableCamera";
    public static final String UNIT_NR_NSA_DISABLE = "nr_nsa_disable";
    public static final String UNIT_WIFI_AP_TEMP = "wifi_ap_temperature";

    public static final AxAdvancedThermalMitigationConfig EMPTY = new Builder().build();

    private final Map<Integer, CpuLevel> mCpuLevels;
    private final Map<Integer, GpuLevel> mGpuLevels;
    private final Map<Integer, BoostScenario> mBoostScenarios;
    private final Map<String, Scene> mScenes;
    private final List<AppRule> mApps;
    private final List<ComplexRule> mComplexes;
    private final List<BufferRateRule> mBufferRates;

    private AxAdvancedThermalMitigationConfig(
            Map<Integer, CpuLevel> cpuLevels,
            Map<Integer, GpuLevel> gpuLevels,
            Map<Integer, BoostScenario> boostScenarios,
            Map<String, Scene> scenes,
            List<AppRule> apps,
            List<ComplexRule> complexes,
            List<BufferRateRule> bufferRates) {
        this.mCpuLevels = Collections.unmodifiableMap(cpuLevels);
        this.mGpuLevels = Collections.unmodifiableMap(gpuLevels);
        this.mBoostScenarios = Collections.unmodifiableMap(boostScenarios);
        this.mScenes = Collections.unmodifiableMap(scenes);
        this.mApps = Collections.unmodifiableList(apps);
        this.mComplexes = Collections.unmodifiableList(complexes);
        this.mBufferRates = Collections.unmodifiableList(bufferRates);
    }

    public CpuLevel getCpuLevel(int id) {
        return mCpuLevels.get(id);
    }

    public GpuLevel getGpuLevel(int id) {
        return mGpuLevels.get(id);
    }

    public BoostScenario getBoostScenario(int id) {
        return mBoostScenarios.get(id);
    }

    public Scene getScene(String name) {
        return mScenes.get(name);
    }

    public List<AppRule> getApps() {
        return mApps;
    }

    public List<ComplexRule> getComplexes() {
        return mComplexes;
    }

    public List<BufferRateRule> getBufferRates() {
        return mBufferRates;
    }

    public AppRule findAppRule(String pkg) {
        if (pkg == null) return null;
        for (AppRule r : mApps) {
            if (r.packages.contains(pkg)) return r;
        }
        return null;
    }

    public BufferRateRule findBufferRateRule(String pkg) {
        if (pkg == null) return null;
        for (BufferRateRule r : mBufferRates) {
            if (r.packages.contains(pkg)) return r;
        }
        return null;
    }

    public boolean isEmpty() {
        return mScenes.isEmpty() && mApps.isEmpty() && mComplexes.isEmpty();
    }

    public static final class CpuLevel {
        public final int id;
        public final int littleMin, littleMax;
        public final int bigMin, bigMax;
        public final int titaniumMin, titaniumMax;
        public final int primeMin, primeMax;
        public final int currentMa;

        public CpuLevel(
                int id,
                int lMin,
                int lMax,
                int bMin,
                int bMax,
                int tMin,
                int tMax,
                int pMin,
                int pMax,
                int currentMa) {
            this.id = id;
            this.littleMin = lMin;
            this.littleMax = lMax;
            this.bigMin = bMin;
            this.bigMax = bMax;
            this.titaniumMin = tMin;
            this.titaniumMax = tMax;
            this.primeMin = pMin;
            this.primeMax = pMax;
            this.currentMa = currentMa;
        }
    }

    public static final class GpuLevel {
        public final int id;
        public final int min;
        public final int max;

        public GpuLevel(int id, int min, int max) {
            this.id = id;
            this.min = min;
            this.max = max;
        }
    }

    public static final class BoostScenario {
        public final int id;
        public final String name;
        public final long[] params;

        public BoostScenario(int id, String name, long[] params) {
            this.id = id;
            this.name = name;
            this.params = params;
        }
    }

    public static final class LevelRule {
        public final int level;
        public final Map<String, Integer> actions;

        public LevelRule(int level, Map<String, Integer> actions) {
            this.level = level;
            this.actions = Collections.unmodifiableMap(actions);
        }
    }

    public static final class Scene {
        public final String name;
        public final TreeMap<Integer, LevelRule> levels;

        public Scene(String name, TreeMap<Integer, LevelRule> levels) {
            this.name = name;
            this.levels = levels;
        }
    }

    public static final class AppRule {
        public final List<String> packages;
        public final TreeMap<Integer, LevelRule> levels;

        public AppRule(List<String> packages, TreeMap<Integer, LevelRule> levels) {
            this.packages = Collections.unmodifiableList(packages);
            this.levels = levels;
        }
    }

    public static final class ComplexRule {
        public final String key;
        public final List<String> tokens;
        public final TreeMap<Integer, LevelRule> levels;

        public ComplexRule(String key, List<String> tokens, TreeMap<Integer, LevelRule> levels) {
            this.key = key;
            this.tokens = Collections.unmodifiableList(tokens);
            this.levels = levels;
        }
    }

    public static final class BufferRateRule {
        public final List<String> packages;
        public final Map<Integer, Integer> levelToFps;

        public BufferRateRule(List<String> packages, Map<Integer, Integer> levelToFps) {
            this.packages = Collections.unmodifiableList(packages);
            this.levelToFps = Collections.unmodifiableMap(levelToFps);
        }
    }

    public static final class Builder {
        private final Map<Integer, CpuLevel> cpuLevels = new HashMap<>();
        private final Map<Integer, GpuLevel> gpuLevels = new HashMap<>();
        private final Map<Integer, BoostScenario> boostScenarios = new HashMap<>();
        private final Map<String, Scene> scenes = new HashMap<>();
        private final List<AppRule> apps = new ArrayList<>();
        private final List<ComplexRule> complexes = new ArrayList<>();
        private final List<BufferRateRule> bufferRates = new ArrayList<>();

        public Builder addCpuLevel(CpuLevel l) {
            cpuLevels.put(l.id, l);
            return this;
        }

        public Builder addGpuLevel(GpuLevel l) {
            gpuLevels.put(l.id, l);
            return this;
        }

        public Builder addBoostScenario(BoostScenario s) {
            boostScenarios.put(s.id, s);
            return this;
        }

        public Builder addScene(Scene s) {
            scenes.put(s.name, s);
            return this;
        }

        public Builder addApp(AppRule a) {
            apps.add(a);
            return this;
        }

        public Builder addComplex(ComplexRule c) {
            complexes.add(c);
            return this;
        }

        public Builder addBufferRate(BufferRateRule b) {
            bufferRates.add(b);
            return this;
        }

        public AxAdvancedThermalMitigationConfig build() {
            return new AxAdvancedThermalMitigationConfig(
                    cpuLevels, gpuLevels, boostScenarios, scenes, apps, complexes, bufferRates);
        }
    }
}
