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

enum class NTClockType(
    val clockId: Int,
    val viewId: Int,
    val largeViewId: Int = viewId
) {
    GENERAL(
        clockId = R.string.clock_id_general,
        viewId = R.layout.clock_general,
        largeViewId = R.layout.clock_general_large
    ),
    GRAPHIC(
        clockId = R.string.clock_id_graphic,
        viewId = R.layout.clock_graphic,
        largeViewId = R.layout.clock_graphic_large
    ),
    LONDON_UG(
        clockId = R.string.clock_id_london_ug,
        viewId = R.layout.clock_london_ug,
        largeViewId = R.layout.clock_london_ug_large
    ),
    NDOT(
        clockId = R.string.clock_id_ndot,
        viewId = R.layout.clock_ndot,
        largeViewId = R.layout.clock_ndot_large
    ),
    NTYPE(
        clockId = R.string.clock_id_ntype,
        viewId = R.layout.clock_ntype,
        largeViewId = R.layout.clock_ntype_large
    ),
    OLD_QUICKLOOK(
        clockId = R.string.clock_id_old_quick_look,
        viewId = R.layout.clock_old_quick_look,
        largeViewId = R.layout.clock_old_quick_look_large
    ),
    SPACE_AGE(
        clockId = R.string.clock_id_space_age,
        viewId = R.layout.clock_space_age,
        largeViewId = R.layout.clock_space_age_large
    ),
    POLYLINE(
        clockId = R.string.clock_id_polyline,
        viewId = R.layout.clock_polyline,
        largeViewId = R.layout.clock_polyline_large
    );

    companion object {
        val entries: List<NTClockType> = values().toList()
    }
}
