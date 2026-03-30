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
package com.android.server.wm;

import android.util.ArrayMap;
import android.util.SparseIntArray;

import java.util.Collections;
import java.util.Map;

public class AxAppRefreshRateProvider {

    private static final ArrayMap<String, AppRefreshRateConfig> sConfigs = new ArrayMap<>();

    static {
        addConfig(sConfigs,
                new String[]{"com.android.systemui"},
                "MAX", false, false);
    }

    static final Map<String, AppRefreshRateConfig> DEFAULT_APP_CONFIGS =
            Collections.unmodifiableMap(sConfigs);

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
        public float minRefreshRate;
        public float maxRefreshRate;
        public String appName;
        public boolean hasVote;

        public AppVoteInfo() {}

        public void updateVote(String appName, float minRate, float maxRate) {
            this.appName = appName;
            this.minRefreshRate = minRate;
            this.maxRefreshRate = maxRate;
            this.hasVote = true;
        }

        public void copyFrom(AppVoteInfo other) {
            this.appName = other.appName;
            this.minRefreshRate = other.minRefreshRate;
            this.maxRefreshRate = other.maxRefreshRate;
            this.hasVote = other.hasVote;
        }

        public void reset() {
            appName = null;
            minRefreshRate = 0.0f;
            maxRefreshRate = 0.0f;
            hasVote = false;
        }
    }
}
