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
package com.android.server.wm;

import android.util.ArrayMap;
import android.util.SparseIntArray;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

public class NtAppRefreshRateProvider {

    public static final ArrayMap<String, AppRefreshRateConfig> DEFAULT_APP_CONFIGS;

    public static final List<String> GAMING_APPS;

    static {
        DEFAULT_APP_CONFIGS = new ArrayMap<>();

        addConfig(DEFAULT_APP_CONFIGS, new String[]{
            "com.google.android.projection.gearhead.phonescreen","com.waze","com.google.android.apps.mapslite",
            "ru.yandex.yandexmaps","maps.pro","com.baidu.BaiduMap","com.mapquest.android.ace","com.autonavi.minimap",
            "com.nhn.android.nmap","com.here.app.maps","com.ss.android.ugc.aweme","com.ss.android.ugc.aweme.lite",
            "com.ss.android.ugc.trill","tv.twitch.android.app"
        }, "60,60,60", false, false);

        addConfig(DEFAULT_APP_CONFIGS, new String[]{
            "com.android.chrome","com.brave.browser","com.opera.browser","org.mozilla.firefox","org.torproject.torbrowser",
            "com.sec.android.app.sbrowser","com.kiwibrowser.browser","com.microsoft.emmx","com.hsv.freeadblockerbrowser",
            "com.vivaldi.browser","com.yandex.browser","org.adblockplus.browser","com.naver.whale"
        }, "90,120,60", true, false);

        addConfig(DEFAULT_APP_CONFIGS, new String[]{
            "com.android.launcher3","com.tencent.mm","com.google.android.apps.nbu.paisa.user","com.android.systemui",
            "com.android.wallpaper"
        }, "120,120,60", true, false);

        addConfig(DEFAULT_APP_CONFIGS, new String[]{
            "com.axlebolt.standoff2","com.riotgames.league.wildrift","com.antutu.ABenchMark",
            "com.futuremark.dmandroid.application","com.primatealbs.geekbench6"
        }, "120,120,60", true, true);

        addConfig(DEFAULT_APP_CONFIGS, new String[]{
            "com.tencent.ig","com.tencent.igfit","com.rekoo.pubgm","com.vng.pubgmobile","com.pubg.krmobile","com.tencent.igce"
        }, "90,90,60", true, true);

        addConfig(DEFAULT_APP_CONFIGS, new String[]{ "com.pubg.imobile" }, "120,120,60", true, true);

        addConfig(DEFAULT_APP_CONFIGS, new String[]{
            "com.twitter.android","com.nothing.weather","com.android.vending","com.android.settings",
            "com.google.android.googlequicksearchbox","com.android.setupwizard.overlay","com.nothing.applocker"
        }, "120,120,60", false, false);

        GAMING_APPS = new ArrayList<>(Arrays.asList(
            "com.tencent.ig", "com.rekoo.pubgm", "com.vng.pubgmobile",
            "com.pubg.krmobile", "com.tencent.igce", "com.pubg.imobile"
        ));
    }

    private static void addConfig(ArrayMap<String, AppRefreshRateConfig> map, String[] packages,
                                  String rateString, boolean disableSv, boolean disableIdle) {
        SparseIntArray refreshRates = parseRefreshRates(rateString);
        for (String pkg : packages) {
            map.put(pkg, new AppRefreshRateConfig(pkg, refreshRates, disableSv, disableIdle));
        }
    }

    private static SparseIntArray parseRefreshRates(String rateString) {
        SparseIntArray refreshRates = new SparseIntArray(3);
        String[] rates = rateString.split(",");
        for (int i = 0; i < rates.length; i++) {
            refreshRates.put(i, Integer.parseInt(rates[i]));
        }
        return refreshRates;
    }

    public static class AppRefreshRateConfig {
        private final SparseIntArray refreshRates;
        private final boolean disableSurfaceView;
        private final boolean disableIdleFps;
        private final String packageName;

        public AppRefreshRateConfig(String packageName, SparseIntArray refreshRates,
                                    boolean disableSurfaceView, boolean disableIdleFps) {
            this.packageName = packageName;
            this.refreshRates = refreshRates;
            this.disableSurfaceView = disableSurfaceView;
            this.disableIdleFps = disableIdleFps;
        }

        public boolean shouldDisableIdleFps() { return disableIdleFps; }
        public boolean shouldDisableSurfaceView() { return disableSurfaceView; }
        public SparseIntArray getRefreshRates() { return refreshRates; }
        public String getPackageName() { return packageName; }
    }

    public static class AppVoteInfo {
        private int preferredModeId;
        private float minRefreshRate, maxRefreshRate;
        private String appName;
        private boolean hasVote;

        public AppVoteInfo(String appName, int modeId, float minRate, float maxRate, boolean hasVote) {
            this.appName = appName;
            this.preferredModeId = modeId;
            this.minRefreshRate = minRate;
            this.maxRefreshRate = maxRate;
            this.hasVote = hasVote;
        }

        public void updateVote(String appName, int modeId, float minRate, float maxRate) {
            this.appName = appName;
            this.preferredModeId = modeId;
            this.minRefreshRate = minRate;
            this.maxRefreshRate = maxRate;
            this.hasVote = true;
        }

        public float getMaxRefreshRate() { return maxRefreshRate; }
        public float getMinRefreshRate() { return minRefreshRate; }
        public boolean hasVote() { return hasVote; }
        public int getPreferredModeId() { return preferredModeId; }

        public void copyFrom(AppVoteInfo other) {
            this.appName = other.appName;
            this.preferredModeId = other.preferredModeId;
            this.minRefreshRate = other.minRefreshRate;
            this.maxRefreshRate = other.maxRefreshRate;
            this.hasVote = other.hasVote;
        }

        public void reset() {
            appName = null;
            preferredModeId = 0;
            minRefreshRate = 0.0f;
            maxRefreshRate = 0.0f;
            hasVote = false;
        }

        @Override
        public String toString() {
            return "AppVoteInfo{appName=" + appName +
                   ", preferMode=" + preferredModeId +
                   ", minRate=" + minRefreshRate +
                   ", maxRate=" + maxRefreshRate +
                   ", hasVote=" + hasVote + "}";
        }
    }
    
    public enum RefreshRateMode {
        VARIABLE(0),
        HIGH(1),
        LOW(2);

        final int value;
        private static final Map<Integer, RefreshRateMode> MAP = new HashMap<>();
        static {
            for (RefreshRateMode mode : values()) {
                MAP.put(mode.value, mode);
            }
        }

        RefreshRateMode(int value) { this.value = value; }
        public static RefreshRateMode fromInt(int value, boolean fallbackToHigh) {
            return MAP.getOrDefault(value, fallbackToHigh ? HIGH : VARIABLE);
        }
    }

    public enum RefreshRate {
        LOW(60), MID(90), HIGH(120);

        final int hz;
        private static final float TOLERANCE = 1.0f;
        private static final Map<Integer, RefreshRate> MAP = new HashMap<>();
        static {
            for (RefreshRate rate : values()) {
                MAP.put(rate.hz, rate);
            }
        }

        RefreshRate(int hz) { this.hz = hz; }
        public static RefreshRate fromHz(float refreshRate) {
            for (RefreshRate rate : values()) {
                if (Math.abs(refreshRate - rate.hz) < TOLERANCE) return rate;
            }
            return null;
        }
    }
}
