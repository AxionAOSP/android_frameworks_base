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
package com.android.systemui.shared.clocks.view

import android.content.Context
import android.util.AttributeSet
import com.android.systemui.customization.R
import com.android.systemui.shared.clocks.extensions.*

class NTypeClockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    defStyleRes: Int = 0
) : BitmapDigitClockView(context, attrs, defStyleAttr, defStyleRes) {

    override val tagName = "NTypeClockView"

    override val digitResIds = intArrayOf(
        R.drawable.ntype_0,
        R.drawable.ntype_1,
        R.drawable.ntype_2,
        R.drawable.ntype_3,
        R.drawable.ntype_4,
        R.drawable.ntype_5,
        R.drawable.ntype_6,
        R.drawable.ntype_7,
        R.drawable.ntype_8,
        R.drawable.ntype_9
    )

    override val useSeparator = true
    override val separatorType = SeparatorType.DOTS
    
    override val dotSize get() = context.scaledDimen(R.dimen.dot_size)
    override val dotMargin get() = context.scaledDimen(R.dimen.dot_margin)
    override val dotCenterMargin get() = context.scaledDimen(R.dimen.dot_margin_center)
    override val clockOffset get() = context.scaledDimen(R.dimen.clock_offset)
    override val topMargin get() = context.scaledDimen(R.dimen.bitmap_digit_clocks_margin_top_v2)
    
    private val overlapPadding get() = -context.scaledDimen(R.dimen.overlap_small_padding)

    override fun clockColor(): Int = clockColor

    override fun getCustomSpacing(time: String, index: Int): Float {
        val str = when {
            time.length == 4 && (index == 0 || index == 2) -> time.substring(index, index + 2)
            time.length == 3 && index == 1 -> time.substring(index)
            else -> ""
        }
        return when (str) {
            "14" -> overlapPadding * 6
            "17" -> overlapPadding
            "19" -> overlapPadding * 2
            else -> 0f
        }
    }
}
