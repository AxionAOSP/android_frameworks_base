/*
 * Copyright (C) 2025 AxionAOSP Project
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
package com.android.internal.util.android;

import java.util.concurrent.ConcurrentHashMap;

public class PinnerUtils {
    private static final PinnerUtils SINGLE_INSTANCE = new PinnerUtils();

    private final ConcurrentHashMap<String, Boolean> pinnedProcesses = new ConcurrentHashMap<>();

    private PinnerUtils() {}

    public static PinnerUtils INSTANCE() {
        return SINGLE_INSTANCE;
    }

    public void setPinned(String packageName, boolean isPinned) {
        pinnedProcesses.put(packageName, isPinned);
    }

    public boolean isPinned(String packageName) {
        return pinnedProcesses.getOrDefault(packageName, false);
    }

    public void clear() {
        pinnedProcesses.clear();
    }
}
