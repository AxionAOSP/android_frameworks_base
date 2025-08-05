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
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.android.systemui.customization.R
import com.android.systemui.shared.clocks.extensions.*
import kotlin.math.min

class NTypeClockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    defStyleRes: Int = 0
) : NTClockView(context, attrs, defStyleAttr, defStyleRes) {

    private val tag = "NTypeClockView"

    private val paint = Paint()

    private var digitBitmaps: List<Bitmap?> = createDigitBitmaps()

    private val dotSize get() = context.scaledDimen(R.dimen.dot_size)
    private val dotMargin get() = context.scaledDimen(R.dimen.dot_margin)
    private val dotCenterMargin get() = context.scaledDimen(R.dimen.dot_margin_center)
    private val clockOffset get() = context.scaledDimen(R.dimen.clock_offset)
    private val topMargin get() = context.scaledDimen(R.dimen.clock_center_date_margin_top)
    private val overlapPadding get() = -context.scaledDimen(R.dimen.overlap_small_padding)

    private fun createDigitBitmaps(): List<Bitmap?> {
        val resIds = intArrayOf(
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
        return createBitmaps(context, resIds)
    }

    private inline fun String.sumOfIndexed(selector: (index: Int, Char) -> Float): Float {
        var sum = 0f
        for (i in indices) sum += selector(i, this[i])
        return sum
    }

    override fun getTag(): String = tag

    override fun drawClock(canvas: Canvas) {
        if (timeStr.isEmpty() || !TextUtils.isDigitsOnly(timeStr)) return

        val totalWidth = timeStr.sumOfIndexed { index, char ->
            val bitmap = getBitmapForDigit(char) ?: return
            var pad = getSpecialPadding(timeStr, index)
            if (timeStr.length - index == 3) pad += 2 * dotCenterMargin
            bitmap.width * scaleRatio + pad
        }

        val availableWidth = min(totalWidth, width.toFloat())
        val startX = (width - availableWidth) / 2
        paint.colorFilter = PorterDuffColorFilter(clockColor, PorterDuff.Mode.SRC_IN)

        var currentX = startX

        timeStr.forEachIndexed { index, char ->
            val bitmap = getBitmapForDigit(char) ?: return@forEachIndexed
            val yOffset = ((height - bitmap.height * scaleRatio) / 2f) - clockOffset + topMargin

            Matrix().apply {
                postScale(scaleRatio, scaleRatio)
                postTranslate(currentX, yOffset)
            }.also { matrix ->
                canvas.drawBitmap(bitmap, matrix, paint)
            }

            currentX += bitmap.width * scaleRatio + getSpecialPadding(timeStr, index)

            if (timeStr.length - index == 3) {
                val centerX = currentX + dotCenterMargin
                val radius = dotSize / 2
                val dotY = yOffset + bitmap.height * scaleRatio / 2 + clockOffset - dotMargin / 2

                canvas.drawOval(centerX - radius, dotY - radius, centerX + radius, dotY + radius, paint)
                canvas.drawOval(centerX - radius, dotY + dotMargin - radius, centerX + radius, dotY + dotMargin + radius, paint)

                currentX = centerX + dotCenterMargin
            }
        }
    }

    private fun getBitmapForDigit(char: Char): Bitmap? {
        val index = char.digitToIntOrNull() ?: return null
        return digitBitmaps.getOrNull(index)
    }

    private fun getSpecialPadding(time: String, index: Int): Float {
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

    override fun onFontSettingChanged() {
        super.onFontSettingChanged()
        digitBitmaps = createDigitBitmaps()
        invalidate()
    }
}
