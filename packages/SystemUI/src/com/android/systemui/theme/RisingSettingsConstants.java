/*
 * Copyright (C) 2023-2024 The RisingOS Android Project
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
package com.android.systemui.theme;

import android.provider.Settings;
import lineageos.providers.LineageSettings;

public class RisingSettingsConstants {
    private static final String PREF_CHROMA_FACTOR ="monet_engine_chroma_factor";
    private static final String PREF_LUMINANCE_FACTOR ="monet_engine_luminance_factor";
    private static final String PREF_TINT_BACKGROUND ="monet_engine_tint_background";
    private static final String PREF_CUSTOM_ACCENT_COLOR ="monet_engine_accent_color";
    private static final String PREF_CUSTOM_BG_COLOR ="monet_engine_bg_color";
    private static final String PREF_OVERRIDE_COLOR_ACCENT ="monet_engine_color_override_accent";
    private static final String PREF_OVERRIDE_BGCOLOR ="monet_engine_override_bg_color";
    private static final String PREF_THEME_STYLE = "monet_engine_theme_style";
    private static final String PREF_COLOR_SOURCE = "monet_engine_color_source";
    private static final String PREF_WHOLE_PALETTE = "monet_engine_whole_palette";

    public static final String[] SYSTEM_SETTINGS_KEYS = {};
    
    public static final String[] SECURE_SETTINGS_KEYS = {
        PREF_CHROMA_FACTOR,
        PREF_LUMINANCE_FACTOR,
        PREF_TINT_BACKGROUND,
        PREF_CUSTOM_ACCENT_COLOR,
        PREF_CUSTOM_BG_COLOR,
        PREF_OVERRIDE_COLOR_ACCENT,
        PREF_OVERRIDE_BGCOLOR,
        PREF_THEME_STYLE,
        PREF_COLOR_SOURCE,
        PREF_WHOLE_PALETTE
    };
    
    public static final String[] SYSTEM_SETTINGS_NOTIFY_ONLY_KEYS = {};
    
    public static final String[] SECURE_SETTINGS_NOTIFY_ONLY_KEYS = {
    };
    
    public static final String[] LINEAGE_SECURE_SETTINGS_KEYS = {
    };
    
    public static final String[] LINEAGE_SYSTEM_SETTINGS_KEYS = {
    };
}
