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
package com.android.server.am;

import java.util.HashMap;

public class BoostFlagsManager {

    static final String BOOST_SF = "sfboost";
    static final String BOOST_PF = "perfBoost";
    static final String BOOST_GM = "gameBoost";
    static final String BOOST_HT = "hintBoost";

    private final HashMap<String, Boolean> flags = new HashMap<>();

    BoostFlagsManager() {
        flags.put(BOOST_SF, false);
        flags.put(BOOST_PF, false);
        flags.put(BOOST_GM, false);
        flags.put(BOOST_HT, false);
    }

    public boolean isActive(String key) {
        return flags.getOrDefault(key, false);
    }

    public void setFlag(String key, boolean value) {
        flags.put(key, value);
    }

    public boolean isNewState(String key, boolean value) {
        boolean current = flags.getOrDefault(key, false);
        if (current != value) {
            flags.put(key, value);
            return true;
        }
        return false;
    }
}
