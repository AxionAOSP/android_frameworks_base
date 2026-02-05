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
import android.util.AttributeSet
import com.android.systemui.customization.R
import com.android.systemui.shared.clocks.extensions.*

class PolylineLargeClockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    defStyleRes: Int = 0
) : BitmapDigitLargeClockView(context, attrs, defStyleAttr, defStyleRes) {

    override val tagName = "PolylineLargeClockView"

    override val digitScale: Float
        get() = largeClockScaleRatio * if (isSplitShade) 1.2f else 1.5f
        
    override val digitResIds = intArrayOf(
        R.drawable.polyline_0,
        R.drawable.polyline_1,
        R.drawable.polyline_2,
        R.drawable.polyline_3,
        R.drawable.polyline_4,
        R.drawable.polyline_5,
        R.drawable.polyline_6,
        R.drawable.polyline_7,
        R.drawable.polyline_8,
        R.drawable.polyline_9
    )

    override val digitSpacing get() = context.scaledDimen(R.dimen.polyline_clock_padding)

    override fun clockColor(): Int = clockColor
}
