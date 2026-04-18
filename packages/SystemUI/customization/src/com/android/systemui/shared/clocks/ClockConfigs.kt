/*
 * Copyright (C) 2025 AxionOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.shared.clocks

import android.content.Context
import com.android.systemui.customization.R

object ClockConfigs {

    const val ALIGNMENT_LEFT = "left"
    const val ALIGNMENT_CENTER = "center"
    const val ALIGNMENT_RIGHT = "right"

    data class ClockStyleConfig(
        val position: Position,
        val align: Align,
        val visible: Boolean = true,
        val customHeightRes: Int? = null,
        val customDateMarginTop: Int? = null,
        val placeholderTextRes: Int? = null,
    )

    enum class Position { ABOVE, BELOW }
    enum class Align { LEFT, CENTER, RIGHT }

    fun resolveConfig(context: Context, className: String, isLarge: Boolean = false): ClockStyleConfig? =
        resolveConfig(className, isLarge, ClockSettingsRepository.alignment.value)

    fun resolveConfig(className: String, isLarge: Boolean, alignValue: String): ClockStyleConfig? {
        val key = if (isLarge) "${className}_large" else className
        val base = clockConfigMap[key] ?: clockConfigMap[className] ?: return null
        if (base.position == Position.BELOW) return base

        val resolvedAlign = when (alignValue) {
            ALIGNMENT_LEFT -> Align.LEFT
            ALIGNMENT_CENTER -> Align.CENTER
            ALIGNMENT_RIGHT -> Align.RIGHT
            else -> base.align
        }

        if (resolvedAlign == base.align) return base
        return base.copy(align = resolvedAlign)
    }

    data class DateParts(val main: String, val secondary: String)

    fun parseDateDisplay(date: String): DateParts {
        val parts = date.split("  ")
        return DateParts(
            parts.getOrNull(0)?.trim().orEmpty(),
            parts.getOrNull(1)?.trim().orEmpty(),
        )
    }

    val clockConfigMap = mapOf(
        "GeneralClockView" to ClockStyleConfig(
            Position.ABOVE,
            Align.CENTER,
            visible = false,
            placeholderTextRes = R.string.clock_text_placeholder,
        ),
        "OldQuickLookClockView" to ClockStyleConfig(
            Position.ABOVE,
            Align.CENTER,
            visible = false,
            placeholderTextRes = R.string.clock_old_quick_look_image_placeholder,
        ),
        "OldQuickLookClockView_large" to ClockStyleConfig(
            Position.BELOW,
            Align.CENTER,
            visible = false,
        ),
        "GeneralClockView_large" to ClockStyleConfig(
            Position.BELOW,
            Align.CENTER,
            visible = false,
        ),
        "BitmapDigitComposeClockView" to ClockStyleConfig(
            Position.ABOVE,
            Align.CENTER,
            visible = false,
        ),
        "BitmapDigitComposeClockView_large" to ClockStyleConfig(
            Position.BELOW,
            Align.CENTER,
            visible = false,
        ),
        "CyberpunkClockView" to ClockStyleConfig(
            Position.ABOVE,
            Align.CENTER,
            visible = false,
        ),
        "CyberpunkClockView_large" to ClockStyleConfig(
            Position.BELOW,
            Align.CENTER,
            visible = false,
        ),
        "AxionAgeClockView" to ClockStyleConfig(
            Position.ABOVE,
            Align.CENTER,
            visible = false,
        ),
        "AxionAgeClockView_large" to ClockStyleConfig(
            Position.BELOW,
            Align.CENTER,
            visible = false,
        ),
    )
}

