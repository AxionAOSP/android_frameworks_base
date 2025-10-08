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
    val viewId: Int
) {
    GENERAL(
        clockId = R.string.clock_id_general,
        viewId = R.layout.clock_general
    ),
    GRAPHIC(
        clockId = R.string.clock_id_graphic,
        viewId = R.layout.clock_graphic
    ),
    LONDON_UG(
        clockId = R.string.clock_id_london_ug,
        viewId = R.layout.clock_london_ug
    ),
    NDOT(
        clockId = R.string.clock_id_ndot,
        viewId = R.layout.clock_ndot
    ),
    NTYPE(
        clockId = R.string.clock_id_ntype,
        viewId = R.layout.clock_ntype
    ),
    OLD_QUICKLOOK(
        clockId = R.string.clock_id_old_quick_look,
        viewId = R.layout.clock_old_quick_look
    ),
    SPACE_AGE(
        clockId = R.string.clock_id_space_age,
        viewId = R.layout.clock_space_age
    ),
    POLYLINE(
        clockId = R.string.clock_id_polyline,
        viewId = R.layout.clock_polyline
    );

    companion object {
        val entries: List<NTClockType> = values().toList()
    }
}
