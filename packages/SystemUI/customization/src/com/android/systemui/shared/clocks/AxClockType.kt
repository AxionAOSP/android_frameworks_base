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

import android.content.res.Resources
import com.android.systemui.customization.R
import com.android.systemui.shared.clocks.view.ClockFaceStyle

enum class AxClockType(
    val clockId: Int,
    val viewId: Int,
    val largeViewId: Int = viewId,
    val bitmapFaceStyle: ClockFaceStyle? = null,
    val pickerName: Int = R.string.clock_id_general,
    val pickerDescription: Int = pickerName,
    val visibleInPicker: Boolean = true,
) {
    GENERAL(
        clockId = R.string.clock_id_general,
        viewId = R.layout.clock_general,
        largeViewId = R.layout.clock_general_large
    ),
    GRAPHIC(
        clockId = R.string.clock_id_graphic,
        viewId = R.layout.clock_bitmap_compose,
        largeViewId = R.layout.clock_bitmap_compose_large,
        bitmapFaceStyle = ClockFaceStyle.GRAPHIC
    ),
    LONDON_UG(
        clockId = R.string.clock_id_london_ug,
        viewId = R.layout.clock_bitmap_compose,
        largeViewId = R.layout.clock_bitmap_compose_large,
        bitmapFaceStyle = ClockFaceStyle.LONDON_UG
    ),
    NDOT(
        clockId = R.string.clock_id_ndot,
        viewId = R.layout.clock_bitmap_compose,
        largeViewId = R.layout.clock_bitmap_compose_large,
        bitmapFaceStyle = ClockFaceStyle.NDOT
    ),
    NTYPE(
        clockId = R.string.clock_id_ntype,
        viewId = R.layout.clock_bitmap_compose,
        largeViewId = R.layout.clock_bitmap_compose_large,
        bitmapFaceStyle = ClockFaceStyle.NTYPE
    ),
    OLD_QUICKLOOK(
        clockId = R.string.clock_id_old_quick_look,
        viewId = R.layout.clock_old_quick_look,
        largeViewId = R.layout.clock_old_quick_look_large
    ),
    SPACE_AGE(
        clockId = R.string.clock_id_space_age,
        viewId = R.layout.clock_bitmap_compose,
        largeViewId = R.layout.clock_bitmap_compose_large,
        bitmapFaceStyle = ClockFaceStyle.SPACE_AGE
    ),
    POLYLINE(
        clockId = R.string.clock_id_polyline,
        viewId = R.layout.clock_bitmap_compose,
        largeViewId = R.layout.clock_bitmap_compose_large,
        bitmapFaceStyle = ClockFaceStyle.POLYLINE
    ),
    CYBERPUNK(
        clockId = R.string.clock_id_cyberpunk,
        viewId = R.layout.clock_cyberpunk,
        largeViewId = R.layout.clock_cyberpunk_large
    ),
    AXION_AGE(
        clockId = R.string.clock_id_axion_age,
        viewId = R.layout.clock_axion_age,
        largeViewId = R.layout.clock_axion_age_large
    ),
    SEGMENTS(
        clockId = R.string.clock_id_segments,
        viewId = R.layout.clock_bitmap_compose,
        largeViewId = R.layout.clock_bitmap_compose_large,
        bitmapFaceStyle = ClockFaceStyle.SEGMENTS
    ),
    OPLUS_CLASSIC(
        clockId = R.string.clock_id_oplus_classic,
        viewId = R.layout.clock_oplus_classic,
        pickerName = R.string.clock_oplus_classic_name,
        pickerDescription = R.string.clock_oplus_classic_description
    ),
    OPLUS_BIG(
        clockId = R.string.clock_id_oplus_big,
        viewId = R.layout.clock_oplus_big,
        pickerName = R.string.clock_oplus_big_name,
        pickerDescription = R.string.clock_oplus_big_description
    ),
    OPLUS_PLAYFUL(
        clockId = R.string.clock_id_oplus_playful,
        viewId = R.layout.clock_oplus_playful,
        pickerName = R.string.clock_oplus_playful_name,
        pickerDescription = R.string.clock_oplus_playful_description
    ),
    NONE(
        clockId = R.string.clock_id_none,
        viewId = R.layout.clock_none,
        largeViewId = R.layout.clock_none
    );

    val clockConfigKey: String?
        get() = when (this) {
            GENERAL -> "GeneralClockView"
            GRAPHIC,
            LONDON_UG,
            NDOT,
            NTYPE,
            SPACE_AGE,
            POLYLINE,
            SEGMENTS -> "BitmapDigitComposeClockView"
            OLD_QUICKLOOK -> "OldQuickLookClockView"
            CYBERPUNK -> "CyberpunkClockView"
            AXION_AGE -> "AxionAgeClockView"
            OPLUS_CLASSIC -> "OplusClassicClockView"
            OPLUS_BIG -> "OplusBigClockView"
            OPLUS_PLAYFUL -> "OplusGraffitiClockView"
            NONE -> null
        }

    companion object {
        val entries: List<AxClockType> = values().toList()
        val pickerEntries: List<AxClockType> = entries.filter { it.visibleInPicker }

        fun resolve(resources: Resources, clockId: String?): AxClockType {
            val id = clockId ?: return NTYPE
            return legacyClockAliases(resources)[id]
                ?: entries.firstOrNull { resources.getString(it.clockId) == id }
                ?: NTYPE
        }

        private fun legacyClockAliases(resources: Resources): Map<String, AxClockType> =
            mapOf(
                resources.getString(R.string.clock_id_oplus_classic_start) to OPLUS_CLASSIC,
                resources.getString(R.string.clock_id_oplus_classic_end) to OPLUS_CLASSIC,
                resources.getString(R.string.clock_id_oplus_classic_stacked) to OPLUS_CLASSIC,
                resources.getString(R.string.clock_id_oplus_classic_start_vertical) to OPLUS_CLASSIC,
                resources.getString(R.string.clock_id_oplus_classic_end_vertical) to OPLUS_CLASSIC,
                resources.getString(R.string.clock_id_oplus_classic_dual_horizontal) to OPLUS_CLASSIC,
                resources.getString(R.string.clock_id_oplus_classic_dual_vertical) to OPLUS_CLASSIC,
                resources.getString(R.string.clock_id_oplus_big_center_vertical) to OPLUS_BIG,
                resources.getString(R.string.clock_id_oplus_big_vertical) to OPLUS_BIG,
                resources.getString(R.string.clock_id_oplus_big_wide) to OPLUS_BIG,
                resources.getString(R.string.clock_id_oplus_big_start_horizontal) to OPLUS_BIG,
                resources.getString(R.string.clock_id_oplus_big_end_horizontal) to OPLUS_BIG,
                resources.getString(R.string.clock_id_oplus_snapshot) to NTYPE,
                resources.getString(R.string.clock_id_oplus_snapshot_old) to NTYPE,
                resources.getString(R.string.clock_id_oplus_playful_bright) to OPLUS_PLAYFUL,
                resources.getString(R.string.clock_id_oplus_playful_cute) to OPLUS_PLAYFUL,
                resources.getString(R.string.clock_id_oplus_playful_game) to OPLUS_PLAYFUL,
                resources.getString(R.string.clock_id_oplus_playful_keep) to OPLUS_PLAYFUL,
                resources.getString(R.string.clock_id_oplus_cover) to NTYPE,
                resources.getString(R.string.clock_id_oplus_cover_start) to NTYPE,
                resources.getString(R.string.clock_id_oplus_cover_center) to NTYPE,
                resources.getString(R.string.clock_id_oplus_cover_end) to NTYPE,
            )
    }
}
