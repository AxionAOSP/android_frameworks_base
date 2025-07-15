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
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.android.systemui.customization.R
import com.android.systemui.shared.clocks.extensions.*
import kotlin.math.roundToInt

class LondonUGClockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    defStyleRes: Int = 0
) : NTClockView(context, attrs, defStyleAttr, defStyleRes) {

    private val padding: Float
        get() = context.scaledDimen(R.dimen.clock_padding)

    private val scale: Float
        get() = (context.scaleRatio * 56f) / 32f

    private val topMargin: Float
        get() = context.scaledDimen(R.dimen.clock_center_date_margin_top)

    private var digitBitmaps: Map<Char, Bitmap?> = createDigitBitmaps()

    private val paint = Paint()
    private val tagName = "LondonUGClockView"

    override fun getTag(): String = tagName

    private fun createDigitBitmaps(): Map<Char, Bitmap?> {
        val digitResIds = intArrayOf(
            R.drawable.london_ug_0,
            R.drawable.london_ug_1,
            R.drawable.london_ug_2,
            R.drawable.london_ug_3,
            R.drawable.london_ug_4,
            R.drawable.london_ug_5,
            R.drawable.london_ug_6,
            R.drawable.london_ug_7,
            R.drawable.london_ug_8,
            R.drawable.london_ug_9,
        )
        val bitmaps = createBitmaps(context, digitResIds)
        return ('0'..'9').zip(bitmaps).toMap()
    }

    override fun drawClock(canvas: Canvas) {
        val time = timeStr
        if (time.isEmpty() || !TextUtils.isDigitsOnly(time)) return

        var totalWidth = 0f
        time.forEachIndexed { index, char ->
            val bitmap = digitBitmaps[char]
            if (bitmap != null) {
                totalWidth += bitmap.width * scale
                if (index < time.lastIndex) totalWidth += padding
            }
        }
        if (totalWidth <= 0f) return

        val availableWidth = width.toFloat()
        val finalWidth = minOf(totalWidth, availableWidth)
        val startX = (availableWidth - finalWidth) / 2f
        val centerY = (height / 2f) + topMargin

        val color = if (isRegionDark || isDoze || isScreenOff) {
            Color.WHITE
        } else {
            Color.argb((0.6f * 255).roundToInt(), 0, 0, 0)
        }
        paint.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)

        var x = startX
        time.forEachIndexed { index, char ->
            val bitmap = digitBitmaps[char]
            if (bitmap != null) {
                val matrix = Matrix().apply {
                    postScale(scale, scale)
                    postTranslate(x, centerY - (bitmap.height * scale / 2))
                }
                canvas.drawBitmap(bitmap, matrix, paint)
                x += bitmap.width * scale
                if (index < time.lastIndex) x += padding
            }
        }
    }

    override fun onFontSettingChanged() {
        digitBitmaps = createDigitBitmaps()
        invalidate()
    }
}
