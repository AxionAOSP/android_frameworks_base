/*
 * Copyright (C) 2026 AxionOS Project
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

import android.graphics.Color
import com.android.systemui.customization.R
import com.android.systemui.shared.clocks.ClockSettingsRepository
import kotlin.math.roundToInt

enum class ClockFaceStyle(val key: String) {
    NTYPE("ntype"),
    NDOT("ndot"),
    LONDON_UG("london_ug"),
    SPACE_AGE("space_age"),
    POLYLINE("polyline"),
    CYBERPUNK("cyberpunk"),
    AXION_AGE("axion_age"),
    SEGMENTS("segments"),
    GRAPHIC("graphic");

    val isComposeClock: Boolean
        get() = this != CYBERPUNK && this != AXION_AGE

    val needsPerSecondTick: Boolean
        get() = this == GRAPHIC

    companion object {
        const val SETTINGS_KEY = "ax_clock_compose_face"
        val DEFAULT = NTYPE

        fun fromKey(key: String?): ClockFaceStyle =
            entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}

sealed interface RenderMode {
    data object BitmapDigit : RenderMode
    data class FontDigit(
        val fontPath: String,
        val fontSize: Float,
        val lsFontWeight: Int,
        val aodFontWeight: Int,
        val largeScale: Float = 1.85f,
        val lineSpacing: Float = 16f,
        val normalizeDigitOneHeight: Boolean = false,
    ) : RenderMode
    data object AnalogClock : RenderMode
}

data class BitmapFaceConfig(
    val renderMode: RenderMode = RenderMode.BitmapDigit,
    val digitResIds: IntArray = intArrayOf(),
    val digitLightResIds: IntArray? = null,
    val digitLargeResIds: IntArray? = null,
    val digitLightLargeResIds: IntArray? = null,
    val digitSpacingRes: Int = R.dimen.clock_padding,
    val topMarginRes: Int = R.dimen.clock_center_date_margin_top,
    val clockOffsetRes: Int = 0,
    val smallScaleMultiplier: Float = 1f,
    val largeScaleMultiplier: Float = 1.8f,
    val largeDigitSpacingRes: Int = R.dimen.large_clock_digit_spacing,
    val largeLineSpacingRes: Int = R.dimen.large_clock_line_spacing,
    val hasSeparator: Boolean = false,
    val negativeSpacing: Boolean = false,
    val hasLightVariants: Boolean = false,
    val colorMode: ColorMode = ColorMode.STANDARD,
    val overlapSpacingRes: Int = 0,
    val overlapPairs: Map<String, Float> = emptyMap(),
    val lightVariantRule: LightVariantRule = LightVariantRule.NONE,
    val dotSizeRes: Int = R.dimen.dot_size,
    val dotMarginRes: Int = R.dimen.dot_margin,
    val dotCenterMarginRes: Int = R.dimen.dot_margin_center,
    val minuteAlpha: Float = 1f,
    val dateSpacingDp: Float = DATE_SPACING_DEFAULT,
    val topPaddingDp: Float = 0f,
    val bottomPaddingDp: Float = 0f,
    val tickResIds: IntArray = intArrayOf(),
) {
    enum class ColorMode { STANDARD, LONDON_UG }
    enum class LightVariantRule { NONE, SECOND_HALF }

    companion object {
        const val DATE_SPACING_DEFAULT = 24f
    }

    fun clockColor(isDoze: Boolean, isScreenOff: Boolean, isRegionDark: Boolean): Int {
        if (isDoze || isScreenOff) return Color.WHITE
        ClockSettingsRepository.clockColorOverride.value?.let { return it }
        return when (colorMode) {
            ColorMode.STANDARD -> {
                if (isRegionDark) Color.WHITE else Color.BLACK
            }
            ColorMode.LONDON_UG -> {
                if (isRegionDark) Color.WHITE
                else Color.argb((0.6f * 255).roundToInt(), 0, 0, 0)
            }
        }
    }
}

object BitmapFaceConfigs {
    val configs: Map<ClockFaceStyle, BitmapFaceConfig> = mapOf(
        ClockFaceStyle.NTYPE to BitmapFaceConfig(
            digitResIds = intArrayOf(
                R.drawable.ntype_0, R.drawable.ntype_1, R.drawable.ntype_2,
                R.drawable.ntype_3, R.drawable.ntype_4, R.drawable.ntype_5,
                R.drawable.ntype_6, R.drawable.ntype_7, R.drawable.ntype_8,
                R.drawable.ntype_9
            ),
            digitLargeResIds = intArrayOf(
                R.drawable.ntype_0_16b, R.drawable.ntype_1_16b, R.drawable.ntype_2_16b,
                R.drawable.ntype_3_16b, R.drawable.ntype_4_16b, R.drawable.ntype_5_16b,
                R.drawable.ntype_6_16b, R.drawable.ntype_7_16b, R.drawable.ntype_8_16b,
                R.drawable.ntype_9_16b
            ),
            topMarginRes = R.dimen.bitmap_digit_clocks_margin_top_v2,
            clockOffsetRes = R.dimen.clock_offset,
            hasSeparator = true,
            largeScaleMultiplier = 1.1f,
            overlapSpacingRes = R.dimen.overlap_small_padding,
            overlapPairs = mapOf("14" to 6f, "17" to 1f, "19" to 2f),
            dateSpacingDp = 0f,
        ),
        ClockFaceStyle.NDOT to BitmapFaceConfig(
            digitResIds = intArrayOf(
                R.drawable.ndot_0, R.drawable.ndot_1, R.drawable.ndot_2,
                R.drawable.ndot_3, R.drawable.ndot_4, R.drawable.ndot_5,
                R.drawable.ndot_6, R.drawable.ndot_7, R.drawable.ndot_8,
                R.drawable.ndot_9
            ),
            digitLightResIds = intArrayOf(
                R.drawable.ndot_0_light, R.drawable.ndot_1_light, R.drawable.ndot_2_light,
                R.drawable.ndot_3_light, R.drawable.ndot_4_light, R.drawable.ndot_5_light,
                R.drawable.ndot_6_light, R.drawable.ndot_7_light, R.drawable.ndot_8_light,
                R.drawable.ndot_9_light
            ),
            topMarginRes = R.dimen.bitmap_digit_clocks_margin_top_v2,
            clockOffsetRes = R.dimen.ndot_clock_offset,
            hasLightVariants = true,
            largeScaleMultiplier = 1.5f,
            overlapSpacingRes = R.dimen.overlap_padding,
            lightVariantRule = BitmapFaceConfig.LightVariantRule.SECOND_HALF,
            minuteAlpha = 0.6f,
            dateSpacingDp = 0f,
        ),
        ClockFaceStyle.LONDON_UG to BitmapFaceConfig(
            digitResIds = intArrayOf(
                R.drawable.london_ug_0, R.drawable.london_ug_1, R.drawable.london_ug_2,
                R.drawable.london_ug_3, R.drawable.london_ug_4, R.drawable.london_ug_5,
                R.drawable.london_ug_6, R.drawable.london_ug_7, R.drawable.london_ug_8,
                R.drawable.london_ug_9
            ),
            topMarginRes = R.dimen.bitmap_digit_clocks_margin_top_v2,
            smallScaleMultiplier = 56f / 32f,
            largeScaleMultiplier = 2.2f,
            colorMode = BitmapFaceConfig.ColorMode.LONDON_UG,
            dateSpacingDp = 20f,
            topPaddingDp = 16f,
            bottomPaddingDp = 24f,
        ),
        ClockFaceStyle.SPACE_AGE to BitmapFaceConfig(
            digitResIds = intArrayOf(
                R.drawable.space_age_0, R.drawable.space_age_1, R.drawable.space_age_2,
                R.drawable.space_age_3, R.drawable.space_age_4, R.drawable.space_age_5,
                R.drawable.space_age_6, R.drawable.space_age_7, R.drawable.space_age_8,
                R.drawable.space_age_9
            ),
            digitSpacingRes = R.dimen.space_age_clock_sticky_offset,
            negativeSpacing = true,
            smallScaleMultiplier = 0.85f,
            largeScaleMultiplier = 1.5f,
            dateSpacingDp = 20f,
            topPaddingDp = 16f,
            bottomPaddingDp = 24f,
        ),
        ClockFaceStyle.POLYLINE to BitmapFaceConfig(
            digitResIds = intArrayOf(
                R.drawable.polyline_0, R.drawable.polyline_1, R.drawable.polyline_2,
                R.drawable.polyline_3, R.drawable.polyline_4, R.drawable.polyline_5,
                R.drawable.polyline_6, R.drawable.polyline_7, R.drawable.polyline_8,
                R.drawable.polyline_9
            ),
            digitSpacingRes = R.dimen.polyline_clock_padding,
            largeScaleMultiplier = 1.5f,
            dateSpacingDp = 20f,
            topPaddingDp = 16f,
            bottomPaddingDp = 24f,
        ),
        ClockFaceStyle.SEGMENTS to BitmapFaceConfig(
            renderMode = RenderMode.FontDigit(
                fontPath = "fonts/nsegments_vf.otf",
                fontSize = 280f,
                lsFontWeight = 700,
                aodFontWeight = 100,
                largeScale = 1.85f,
                lineSpacing = 16f,
                normalizeDigitOneHeight = true,
            ),
            dateSpacingDp = 0f,
            topPaddingDp = 16f,
        ),
        ClockFaceStyle.GRAPHIC to BitmapFaceConfig(
            renderMode = RenderMode.AnalogClock,
            tickResIds = intArrayOf(R.drawable.graphic_tick, R.drawable.graphic_tick_light),
            dateSpacingDp = 40f,
            topPaddingDp = 16f,
            bottomPaddingDp = 24f,
        ),
    )

    fun getConfig(style: ClockFaceStyle): BitmapFaceConfig? = configs[style]
}
