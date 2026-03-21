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
package com.android.systemui.pulse

import androidx.compose.ui.unit.dp

enum class PulseStyle(val id: Int) {
    BARS(0),
    FADING_BLOCKS(1),
    SOLID_LINE(2),
    CENTER_MIRROR(3);
    companion object {
        fun fromId(id: Int): PulseStyle = entries.find { it.id == id } ?: BARS
    }
}

enum class PulseColorMode(val key: String) {
    LAVALAMP("lavalamp"),
    ALBUM("album"),
    ACCENT("accent");
    companion object {
        fun fromKey(key: String?): PulseColorMode = entries.find { it.key == key } ?: LAVALAMP
    }
}

object PulseConstants {
    val BAR_GAP = 2.dp
    val MAX_HEIGHT = 300.dp
    const val CORNER_RADIUS = 32f
    const val BAR_ALPHA = 0.88f
    const val SMOOTHING_SPEED = 30f
}
