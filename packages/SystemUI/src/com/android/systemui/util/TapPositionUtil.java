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
 * limitations under the License
 */
package com.android.systemui.util;

import android.graphics.Point;

public class TapPositionUtil {
    private static final TapPositionUtil INSTANCE = new TapPositionUtil();
    private Point tapPos = null;

    private TapPositionUtil() {}

    public static TapPositionUtil INSTANCE() {
        return INSTANCE;
    }

    public void setTapPos(int x, int y) {
        if (tapPos == null) {
            tapPos = new Point(x, y);
        } else {
            tapPos.set(x, y);
        }
    }

    public void clearTapPos() {
        tapPos = null;
    }

    public Point getTapPos() {
        return tapPos == null ? null : new Point(tapPos);
    }
}
