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
import android.graphics.*
import android.text.TextUtils
import android.util.AttributeSet
import com.android.systemui.customization.R
import com.android.systemui.shared.clocks.extensions.*
import kotlin.LazyThreadSafetyMode
import kotlin.math.min

class NDotClockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    defStyleRes: Int = 0
) : NTClockView(context, attrs, defStyleAttr, defStyleRes) {

    private val tagName = "NDotClockView"
    override fun getTag() = tagName

    private val paint = Paint()

    private val digitResIds = mapOf(
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

    private var digitBitmaps: Map<Char, Pair<Lazy<Bitmap?>, Lazy<Bitmap?>>> = createDigitBitmaps()

    private val clockPadding get() = context.scaledDimen(R.dimen.clock_padding)
    private val overlapPadding get() = context.scaledDimen(R.dimen.overlap_padding)
    private val topMargin get() = context.scaledDimen(R.dimen.clock_center_date_margin_top)
    private val yOffset get() = context.scaledDimen(R.dimen.ndot_clock_offset)

    private fun createDigitBitmaps(): Map<Char, Pair<Lazy<Bitmap?>, Lazy<Bitmap?>>> {
        val mode = LazyThreadSafetyMode.NONE
        return digitResIds.mapValues { (_, resPair) ->
            val (normalBmp, lightBmp) = createBitmaps(context, intArrayOf(resPair.first, resPair.second))
            lazy(mode) { normalBmp } to lazy(mode) { lightBmp }
        }
    }

    private fun getDigitBitmap(char: Char, isDoze: Boolean, isLight: Boolean): Bitmap? {
        val (normal, light) = digitBitmaps[char] ?: return null
        return if (isLight) light.value else normal.value
    }

    override fun drawClock(canvas: Canvas) {
        if (timeStr.isEmpty() || !TextUtils.isDigitsOnly(timeStr)) return

        val isDozeOrOff = isDoze || isScreenOff
        val isDarkRegion = isRegionDark ?: true
        val length = timeStr.length

        val drawMask = when (length) {
            3 -> booleanArrayOf(false, true, true)
            4 -> booleanArrayOf(false, false, true, true)
            else -> return
        }

        val spacing = calculateSpacing(timeStr, clockPadding, overlapPadding)

        var totalWidth = 0f
        for (i in timeStr.indices) {
            val bmp = getDigitBitmap(timeStr[i], isDozeOrOff, drawMask[i]) ?: continue
            totalWidth += bmp.width * context.scaleRatio
            if (i < spacing.size) totalWidth += spacing[i]
        }

        if (totalWidth <= 0f) return

        val availableWidth = min(width.toFloat(), totalWidth)
        val startX = (width - availableWidth) / 2f

        paint.colorFilter = PorterDuffColorFilter(
            if (isDarkRegion || isDozeOrOff) Color.WHITE else Color.BLACK,
            PorterDuff.Mode.SRC_IN
        )

        var x = startX
        for (i in timeStr.indices) {
            val bmp = getDigitBitmap(timeStr[i], isDozeOrOff, drawMask[i]) ?: continue
            val scale = context.scaleRatio
            val y = (height - bmp.height * scale) / 2f - yOffset + topMargin
            val matrix = Matrix().apply {
                postScale(scale, scale)
                postTranslate(x, y)
            }
            canvas.drawBitmap(bmp, matrix, paint)
            x += bmp.width * scale + if (i < spacing.size) spacing[i] else 0f
        }
    }

    private fun calculateSpacing(timeStr: String, padding: Float, overlap: Float): FloatArray {
        return when (timeStr.length) {
            3 -> {
                val secondHalf = timeStr.substring(1)
                floatArrayOf(padding, if ('1' !in secondHalf) overlap else padding)
            }
            4 -> {
                val firstHalf = timeStr.substring(0, 2)
                val secondHalf = timeStr.substring(2)
                floatArrayOf(
                    if ('1' in firstHalf) padding else overlap,
                    padding,
                    if ('1' !in secondHalf) overlap else padding
                )
            }
            else -> floatArrayOf()
        }
    }

    override fun onFontSettingChanged() {
        super.onFontSettingChanged()
        digitBitmaps = createDigitBitmaps()
        invalidate()
    }
}
