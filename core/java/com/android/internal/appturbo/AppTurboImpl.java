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
package com.android.internal.appturbo;

import android.app.ActivityThread;
import android.util.ArrayMap;

public final class AppTurboImpl {

    private static final ArrayMap<String, TurboConfig> CONFIGS = new ArrayMap<>();

    private static final int DEF_SLOP = 6;
    private static final int DEF_FRICTION = 100;
    private static final int DEF_VELOCITY = 110;

    private static final TurboConfig CFG =
            new TurboConfig(DEF_SLOP, DEF_FRICTION, DEF_VELOCITY);

    static {
        add("com.android.settings", new TurboConfig(8, 70, 150));
        add("com.android.launcher3", new TurboConfig(CFG.slop, 70, CFG.velocity));
        add("com.android.systemui", new TurboConfig(CFG.slop, 70, 150));
        add("com.facebook.katana", new TurboConfig(8, 80, 150));
        add("com.instagram.android", new TurboConfig(16, 80, 150));
    }

    private AppTurboImpl() {}

    private static void add(String pkg, TurboConfig cfg) {
        CONFIGS.put(pkg, cfg);
    }

    private static TurboConfig getConfigOrDefault() {
        String pkg = ActivityThread.currentPackageName();
        TurboConfig cfg = (pkg != null) ? CONFIGS.get(pkg) : null;
        return cfg != null ? cfg : CFG;
    }

    public static int getScaledTouchSlop() {
        return getConfigOrDefault().slop;
    }

    public static float getScrollFriction(float def) {
        return (getConfigOrDefault().friction / 100f) * def;
    }

    public static float getYVelocity(float def) {
        return (getConfigOrDefault().velocity / 100f) * def;
    }

    private static final class TurboConfig {
        final int slop;
        final int friction;
        final int velocity;

        TurboConfig(int slop, int friction, int velocity) {
            this.slop = slop;
            this.friction = friction;
            this.velocity = velocity;
        }
    }
}
