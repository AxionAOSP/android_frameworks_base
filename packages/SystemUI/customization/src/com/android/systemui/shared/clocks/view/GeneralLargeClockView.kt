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

class GeneralLargeClockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    defStyleRes: Int = 0
) : BitmapDigitLargeClockView(context, attrs, defStyleAttr, defStyleRes) {

    override val tagName = "GeneralLargeClockView"

    override val digitScale: Float
        get() = largeClockScaleRatio * 1.8f

    override val digitResIds = intArrayOf(
        R.drawable.intervar_0,
        R.drawable.intervar_1,
        R.drawable.intervar_2,
        R.drawable.intervar_3,
        R.drawable.intervar_4,
        R.drawable.intervar_5,
        R.drawable.intervar_6,
        R.drawable.intervar_7,
        R.drawable.intervar_8,
        R.drawable.intervar_9
    )

    override fun clockColor(): Int = clockColor
}
