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
import java.util.Arrays;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class AxAppRefreshRateProvider {

    public static final ArrayMap<String, AppRefreshRateConfig> DEFAULT_APP_CONFIGS = new ArrayMap<>();

    public static final Set<String> SYS_WINDOW_TYPES = Set.of(
            "StatusBar", "NavigationBar", "wallpapers"
    );

    static {
        addConfig(DEFAULT_APP_CONFIGS, new String[]{
            "com.android.launcher3", "com.android.settings", 
            "com.android.systemui",
            "com.android.wallpaper",
            "com.google.android.googlequicksearchbox",
            "com.android.setupwizard.overlay"
        }, "MAX,MAX,MAX", true, true);
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
            String rate = rates[i].trim();
            if ("MAX".equals(rate)) {
                refreshRates.put(i, -1);
            } else if ("MIN".equals(rate)) {
                refreshRates.put(i, -2);
            } else {
                refreshRates.put(i, Integer.parseInt(rate));
            }
        }
        return refreshRates;
    }


    public static class AppRefreshRateConfig {
        public final SparseIntArray refreshRates;
        public final boolean disableSV;
        public final boolean disableIdle;
        public final String packageName;

        public AppRefreshRateConfig(String packageName, SparseIntArray refreshRates,
                                    boolean disableSV, boolean disableIdle) {
            this.packageName = packageName;
            this.refreshRates = refreshRates;
            this.disableSV = disableSV;
            this.disableIdle = disableIdle;
        }
    }

    public static class AppVoteInfo {
        public int preferredModeId;
        public float minRefreshRate;
        public float maxRefreshRate;
        public String appName;
        public boolean hasVote;

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
}
