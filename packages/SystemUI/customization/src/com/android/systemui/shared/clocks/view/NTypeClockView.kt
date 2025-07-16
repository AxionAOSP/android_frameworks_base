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
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.util.AttributeSet
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.android.systemui.customization.R
import kotlin.Lazy
import kotlin.LazyThreadSafetyMode
import kotlin.math.min

class NTypeClockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    defStyleRes: Int = 0
) : NTClockView(context, attrs, defStyleAttr, defStyleRes) {

    private val tag = "NTypeClockView"

    private val paint = Paint()

    private val digitBitmaps: List<Lazy<Bitmap?>> = listOf(
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
    ).map { resId ->
        lazy(LazyThreadSafetyMode.NONE) {
            ContextCompat.getDrawable(context, resId)?.toBitmap()
        }
    }

    private inline fun String.sumOfIndexed(selector: (index: Int, Char) -> Float): Float {
        var sum = 0f
        for (i in indices) {
            sum += selector(i, this[i])
        }
        return sum
    }

    override fun getTag(): String = tag

    override fun drawClock(canvas: Canvas) {
        if (timeStr.isEmpty() || !TextUtils.isDigitsOnly(timeStr)) return

        val dotSize = resources.getDimension(R.dimen.dot_size) * scaleRatio
        val dotMargin = resources.getDimension(R.dimen.dot_margin) * scaleRatio
        val dotCenterMargin = resources.getDimension(R.dimen.dot_margin_center) * scaleRatio
        val clockOffset = resources.getDimension(R.dimen.clock_offset) * scaleRatio

        val totalWidth = timeStr.sumOfIndexed { index, char ->
            val bitmap = getBitmapForDigit(char) ?: return
            var pad = getSpecialPadding(timeStr, index)
            if (timeStr.length - index == 3) {
                pad += 2 * dotCenterMargin
            }
            bitmap.width * scaleRatio + pad
        }

        val maxWidth = width.toFloat()
        val availableWidth = min(totalWidth, maxWidth)
        val startX = (width - availableWidth) / 2

        val color = clockColor
        paint.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)

        var currentX = startX

        timeStr.forEachIndexed { index, char ->
            val bitmap = getBitmapForDigit(char) ?: return@forEachIndexed
            
            val topMargin = resources.getDimension(R.dimen.clock_center_date_margin_top) * scaleRatio

            val yOffset = ((height - bitmap.height * scaleRatio) / 2f) - clockOffset + topMargin

            val matrix = Matrix().apply {
                postScale(scaleRatio, scaleRatio)
                postTranslate(currentX, yOffset)
            }

            canvas.drawBitmap(bitmap, matrix, paint)

            currentX += bitmap.width * scaleRatio + getSpecialPadding(timeStr, index)

            if (timeStr.length - index == 3) {
                val centerX = currentX + dotCenterMargin
                val radius = dotSize / 2
                val dotY = yOffset + (bitmap.height * scaleRatio / 2) + clockOffset - (dotMargin / 2)

                canvas.drawOval(centerX - radius, dotY - radius, centerX + radius, dotY + radius, paint)
                canvas.drawOval(centerX - radius, dotY + dotMargin - radius, centerX + radius, dotY + dotMargin + radius, paint)

                currentX = centerX + dotCenterMargin
            }
        }
    }

    private fun getBitmapForDigit(char: Char): Bitmap? {
        val index = char.digitToIntOrNull() ?: return null
        return digitBitmaps.getOrNull(index)?.value
    }

    private fun getSpecialPadding(time: String, index: Int): Float {
        val overlapPadding = -resources.getDimension(R.dimen.overlap_small_padding) * scaleRatio

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
