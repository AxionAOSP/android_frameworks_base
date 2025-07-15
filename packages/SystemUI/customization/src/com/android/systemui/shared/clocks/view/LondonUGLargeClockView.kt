/*
 * Copyright (C) 2025 AxionOS
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

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import com.android.systemui.customization.R
import kotlin.math.roundToInt

class LondonUGLargeClockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    defStyleRes: Int = 0
) : BitmapDigitLargeClockView(context, attrs, defStyleAttr, defStyleRes) {

    override val tagName = "LondonUGLargeClockView"

    override val digitResIds = intArrayOf(
        R.drawable.london_ug_0,
        R.drawable.london_ug_1,
        R.drawable.london_ug_2,
        R.drawable.london_ug_3,
        R.drawable.london_ug_4,
        R.drawable.london_ug_5,
        R.drawable.london_ug_6,
        R.drawable.london_ug_7,
        R.drawable.london_ug_8,
        R.drawable.london_ug_9
    )

    override val digitScale: Float
        get() = scaleRatio * 2.2f

    override fun clockColor(): Int {
        return if (isRegionDark || isDoze || isScreenOff) {
            Color.WHITE
        } else {
            Color.argb((0.6f * 255).roundToInt(), 0, 0, 0)
        }
    }
}
