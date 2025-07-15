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

class NDotLargeClockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    defStyleRes: Int = 0
) : BitmapDigitLargeClockView(context, attrs, defStyleAttr, defStyleRes) {

    override val tagName = "NDotLargeClockView"

    override val yOffset: Float
        get() = context.resources.displayMetrics.density * 100f

    override val digitScale: Float
        get() = scaleRatio * 1.5f

    override val digitResIds = intArrayOf(
        R.drawable.ndot_0,
        R.drawable.ndot_1,
        R.drawable.ndot_2,
        R.drawable.ndot_3,
        R.drawable.ndot_4,
        R.drawable.ndot_5,
        R.drawable.ndot_6,
        R.drawable.ndot_7,
        R.drawable.ndot_8,
        R.drawable.ndot_9
    )

    override fun clockColor(): Int = clockColor
}
