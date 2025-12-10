/*
 * Copyright (C) 2025 Axion OS
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

package com.android.systemui.statusbar.connectivity;

import android.content.Context;
import android.content.res.ThemeEngine;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public class ThemedStatusBarIcons {
    
    private static final String TAG = "ThemedStatusBarIcons";
    
    private static final String[] SIGNAL_ICON_NAMES = {
        "ic_signal_0",
        "ic_signal_1",
        "ic_signal_2",
        "ic_signal_3",
        "ic_signal_4",
        "ic_signal_5"
    };
    
    @Nullable
    public static Drawable getThemedSignalIcon(@NonNull Context context, int level, int numLevels) {
        ThemeEngine engine = ThemeEngine.getInstance(context);
        if (engine == null) {
            return null;
        }
        
        if (level < 0 || level >= SIGNAL_ICON_NAMES.length) {
            return null;
        }
        
        String iconName = SIGNAL_ICON_NAMES[level];
        
        Drawable result = engine.getIconThemeDrawable(iconName);
        
        return result;
    }
    
    public static boolean hasThemedSignalIcons(@NonNull Context context) {
        ThemeEngine engine = ThemeEngine.getInstance(context);
        if (engine == null) return false;
        
        return engine.isTargetedResource(SIGNAL_ICON_NAMES[0]);
    }
}
