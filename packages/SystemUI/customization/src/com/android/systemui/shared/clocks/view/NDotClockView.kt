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
import android.graphics.Color
import android.util.AttributeSet
import com.android.systemui.customization.R
import com.android.systemui.shared.clocks.extensions.*

class NDotClockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    defStyleRes: Int = 0
) : BitmapDigitClockView(context, attrs, defStyleAttr, defStyleRes) {

    override val tagName = "NDotClockView"

    override val digitResIdPairs = mapOf(
        '0' to (R.drawable.ndot_0 to R.drawable.ndot_0_light),
        '1' to (R.drawable.ndot_1 to R.drawable.ndot_1_light),
        '2' to (R.drawable.ndot_2 to R.drawable.ndot_2_light),
        '3' to (R.drawable.ndot_3 to R.drawable.ndot_3_light),
        '4' to (R.drawable.ndot_4 to R.drawable.ndot_4_light),
        '5' to (R.drawable.ndot_5 to R.drawable.ndot_5_light),
        '6' to (R.drawable.ndot_6 to R.drawable.ndot_6_light),
        '7' to (R.drawable.ndot_7 to R.drawable.ndot_7_light),
        '8' to (R.drawable.ndot_8 to R.drawable.ndot_8_light),
        '9' to (R.drawable.ndot_9 to R.drawable.ndot_9_light)
    )

    private val clockPadding get() = context.scaledDimen(R.dimen.clock_padding)
    private val overlapPadding get() = context.scaledDimen(R.dimen.overlap_padding)
    override val topMargin get() = context.scaledDimen(R.dimen.bitmap_digit_clocks_margin_top_v2)
    override val clockOffset get() = context.scaledDimen(R.dimen.ndot_clock_offset)

    override fun clockColor(): Int {
        val isDozeOrOff = isDoze || isScreenOff
        val isDarkRegion = isRegionDark ?: true
        return if (isDarkRegion || isDozeOrOff) Color.WHITE else Color.BLACK
    }

    override fun shouldUseLightVariant(time: String, index: Int): Boolean {
        val isDozeOrOff = isDoze || isScreenOff
        if (isDozeOrOff) return false
        return when (time.length) {
            3 -> index >= 1 
            4 -> index >= 2
            else -> false
        }
    }

    override fun getCustomSpacing(time: String, index: Int): Float {
        return when (time.length) {
            3 -> {
                when (index) {
                    0 -> clockPadding
                    1 -> {
                        val secondHalf = time.substring(1)
                        if ('1' !in secondHalf) overlapPadding else clockPadding
                    }
                    else -> 0f
                }
            }
            4 -> {
                when (index) {
                    0 -> {
                        val firstHalf = time.substring(0, 2)
                        if ('1' in firstHalf) clockPadding else overlapPadding
                    }
                    1 -> clockPadding
                    2 -> {
                        val secondHalf = time.substring(2)
                        if ('1' !in secondHalf) overlapPadding else clockPadding
                    }
                    else -> 0f
                }
            }
            else -> 0f
        }
    }
}
