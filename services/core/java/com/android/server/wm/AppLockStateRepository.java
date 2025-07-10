/*
 * Copyright (C) 2025 AxionOS Project
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

import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

public class AppLockStateRepository {
    private final Set<String> unlockedApps = new HashSet<>();
    private final Consumer<Void> updateNotifier;

    public AppLockStateRepository(Consumer<Void> updateNotifier) {
        this.updateNotifier = updateNotifier;
    }

    public void unlockApp(String packageName) {
        if (packageName == null) return;
        unlockedApps.add(packageName);
        notifyChange();
    }

    public void lockApp(String packageName) {
        if (packageName == null) return;
        unlockedApps.remove(packageName);
        notifyChange();
    }

    public boolean isUnlocked(String packageName) {
        return unlockedApps.contains(packageName);
    }

    public boolean isEmpty() {
        return unlockedApps.isEmpty();
    }

    public void clearAll() {
        unlockedApps.clear();
        notifyChange();
    }

    private void notifyChange() {
        if (updateNotifier != null) updateNotifier.accept(null);
    }
}
