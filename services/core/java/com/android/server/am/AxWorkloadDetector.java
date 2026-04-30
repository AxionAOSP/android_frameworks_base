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

import android.app.AxBoostFwk;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.util.LruCache;

public class AxWorkloadDetector {
    private static final int CACHE_SIZE = 50;
    private static final LruCache<String, Integer> sCache = new LruCache<>(CACHE_SIZE);

    public AxWorkloadDetector() {
    }

    public int getType(ApplicationInfo ai, String packageName) {
        if (packageName == null) return AxBoostFwk.WORKLOAD_NOT_KNOWN;
        Integer cached = sCache.get(packageName);
        if (cached != null) return cached;

        int type = detectType(ai);
        sCache.put(packageName, type);
        return type;
    }

    private int detectType(ApplicationInfo ai) {
        if (ai == null) return AxBoostFwk.WORKLOAD_NOT_KNOWN;
        if (ai.category == ApplicationInfo.CATEGORY_GAME
                || (ai.flags & ApplicationInfo.FLAG_IS_GAME) != 0) {
            return AxBoostFwk.WORKLOAD_GAME;
        }
        return AxBoostFwk.WORKLOAD_APP;
    }
}
