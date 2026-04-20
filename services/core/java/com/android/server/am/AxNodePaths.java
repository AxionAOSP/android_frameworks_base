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

    private static volatile boolean sInitDone = false;
    private static volatile String sDramMinPath = null;
    private static volatile long sDramBoostHz = 0L;
    private static volatile long sDramDefaultHz = 0L;
    private AxNodePaths() {}

    public static String getDramMinPath() { ensureInit(); return sDramMinPath; }
    public static long getDramBoostHz() { ensureInit(); return sDramBoostHz; }
    public static long getDramDefaultHz() { ensureInit(); return sDramDefaultHz; }

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

    }
}
