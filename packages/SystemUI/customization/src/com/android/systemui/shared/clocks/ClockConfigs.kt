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

import com.android.systemui.customization.R

object ClockConfigs {

    data class ClockStyleConfig(
        val position: Position,
        val align: Align,
        val visible: Boolean = true,
        val customHeightRes: Int? = null,
        val customDateMarginTop: Int? = null
    )

    enum class Position { ABOVE, BELOW }
    enum class Align { LEFT, CENTER }

    val clockConfigMap = mapOf(
        "GeneralClockView" to ClockStyleConfig(
            Position.ABOVE,
            Align.LEFT,
            customDateMarginTop = R.dimen.clock_general_date_top_margin,
            customHeightRes = R.dimen.clock_general_height
        ),
        "GraphicClockView" to ClockStyleConfig(
            Position.ABOVE,
            Align.CENTER,
            visible = false
        ),
        "LondonUGClockView" to ClockStyleConfig(
            Position.ABOVE,
            Align.CENTER,
            customHeightRes = R.dimen.bitmap_digit_clocks_height_v2,
            customDateMarginTop = R.dimen.bitmap_digit_clocks_date_top_margin_v2
        ),
        "NDotClockView" to ClockStyleConfig(
            Position.ABOVE,
            Align.CENTER,
            customHeightRes = R.dimen.bitmap_digit_clocks_height_v2,
            customDateMarginTop = R.dimen.bitmap_digit_clocks_date_top_margin_v2
        ),
        "NTypeClockView" to ClockStyleConfig(
            Position.ABOVE,
            Align.CENTER,
            customHeightRes = R.dimen.bitmap_digit_clocks_height_v2,
            customDateMarginTop = R.dimen.bitmap_digit_clocks_date_top_margin_v2
        ),
        "OldQuickLookClockView" to ClockStyleConfig(
            Position.ABOVE,
            Align.CENTER,
            visible = false,
            customHeightRes = R.dimen.old_clock_height
        ),
        "PolylineClockView" to ClockStyleConfig(
            Position.ABOVE,
            Align.CENTER,
            customHeightRes = R.dimen.bitmap_digit_clocks_height,
            customDateMarginTop = R.dimen.bitmap_digit_clocks_top_margin
        ),
        "SpaceAgeClockView" to ClockStyleConfig(
            Position.ABOVE,
            Align.CENTER,
            customHeightRes = R.dimen.bitmap_digit_clocks_height,
            customDateMarginTop = R.dimen.bitmap_digit_clocks_top_margin
        ),
        "NTypeLargeClockView" to ClockStyleConfig(
            Position.BELOW,
            Align.CENTER,
            customHeightRes = R.dimen.large_clock_height
        ),
        "NDotLargeClockView" to ClockStyleConfig(
            Position.BELOW,
            Align.CENTER,
            customHeightRes = R.dimen.large_clock_height
        ),
        "LondonUGLargeClockView" to ClockStyleConfig(
            Position.BELOW,
            Align.CENTER,
            customHeightRes = R.dimen.large_clock_height
        ),
        "SpaceAgeLargeClockView" to ClockStyleConfig(
            Position.BELOW,
            Align.CENTER,
            customHeightRes = R.dimen.large_clock_height
        ),
        "PolylineLargeClockView" to ClockStyleConfig(
            Position.BELOW,
            Align.CENTER,
            customHeightRes = R.dimen.large_clock_height
        ),
        "GeneralLargeClockView" to ClockStyleConfig(
            Position.BELOW,
            Align.CENTER,
            customHeightRes = R.dimen.large_clock_height
        ),
        "CyberpunkClockView" to ClockStyleConfig(
            Position.ABOVE,
            Align.CENTER,
            customHeightRes = R.dimen.cyberpunk_clock_height,
            customDateMarginTop = R.dimen.bitmap_digit_clocks_date_top_margin_v2
        ),
        "CyberpunkLargeClockView" to ClockStyleConfig(
            Position.BELOW,
            Align.CENTER,
            customHeightRes = R.dimen.large_clock_height,
            customDateMarginTop = R.dimen.large_clock_date_margin_top
        ),
        "AxionAgeClockView" to ClockStyleConfig(
            Position.ABOVE,
            Align.CENTER,
            customHeightRes = R.dimen.axion_age_clock_height_small,
            customDateMarginTop = R.dimen.bitmap_digit_clocks_date_top_margin_v2
        ),
        "AxionAgeLargeClockView" to ClockStyleConfig(
            Position.BELOW,
            Align.CENTER,
            customHeightRes = R.dimen.large_clock_height,
            customDateMarginTop = R.dimen.large_clock_date_margin_top
        )
    )
}
