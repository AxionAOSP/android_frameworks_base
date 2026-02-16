/*
 * Copyright (C) 2025-2026 AxionOS Project
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

package com.android.systemui.shared.clocks.view

import android.graphics.Typeface
import androidx.compose.ui.text.font.FontFamily

fun splitTimeLines(time: String): Pair<String, String> = when (time.length) {
    4 -> time.substring(0, 2) to time.substring(2, 4)
    3 -> time.substring(0, 1) to time.substring(1, 3)
    else -> "" to ""
}

fun resolveBodyFontFamily(): FontFamily {
    val typeface = Typeface.create(FONT_FAMILY_BODY, Typeface.NORMAL)
    return FontFamily(typeface)
}

fun resolveDateFontFamily(): FontFamily {
    val typeface = Typeface.create(FONT_FAMILY_DATE, Typeface.NORMAL)
    return FontFamily(typeface)
}
