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
import com.android.systemui.shared.clocks.view.ClockFaceStyle

enum class AxClockType(
    val clockId: Int,
    val viewId: Int,
    val largeViewId: Int = viewId,
    val bitmapFaceStyle: ClockFaceStyle? = null
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
    );

    companion object {
        val entries: List<AxClockType> = values().toList()
    }
}
