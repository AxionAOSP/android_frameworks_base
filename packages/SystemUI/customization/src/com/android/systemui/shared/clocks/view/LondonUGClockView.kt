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
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.android.systemui.customization.R
import kotlin.math.roundToInt

class LondonUGClockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    defStyleRes: Int = 0
) : NTClockView(context, attrs, defStyleAttr, defStyleRes) {

    private val digitBitmaps: Map<Char, Lazy<Bitmap?>> = mapOf(
        '0' to lazy { loadBitmap(R.drawable.london_ug_0) },
        '1' to lazy { loadBitmap(R.drawable.london_ug_1) },
        '2' to lazy { loadBitmap(R.drawable.london_ug_2) },
        '3' to lazy { loadBitmap(R.drawable.london_ug_3) },
        '4' to lazy { loadBitmap(R.drawable.london_ug_4) },
        '5' to lazy { loadBitmap(R.drawable.london_ug_5) },
        '6' to lazy { loadBitmap(R.drawable.london_ug_6) },
        '7' to lazy { loadBitmap(R.drawable.london_ug_7) },
        '8' to lazy { loadBitmap(R.drawable.london_ug_8) },
        '9' to lazy { loadBitmap(R.drawable.london_ug_9) },
    )

    private val paint = Paint()
    private val tagName = "LondonUGClockView"

    override fun getTag(): String = tagName

    private fun loadBitmap(resId: Int): Bitmap? {
        val drawable: Drawable? = ContextCompat.getDrawable(context, resId)
        return drawable?.toBitmap()
    }

    override fun drawClock(canvas: Canvas) {
        val time = timeStr

        if (time.isEmpty() || !TextUtils.isDigitsOnly(time)) return

        val padding = resources.getDimension(R.dimen.clock_padding) * scaleRatio
        val scale = (scaleRatio * 56f) / 32f

        var totalWidth = 0f
        time.forEachIndexed { index, char ->
            val bitmap = digitBitmaps[char]?.value
            if (bitmap != null) {
                totalWidth += bitmap.width * scale
                if (index < time.lastIndex) totalWidth += padding
            }
        }

        if (totalWidth <= 0f) return
        val availableWidth = width.toFloat()
        val finalWidth = minOf(totalWidth, availableWidth)
        val startX = (availableWidth - finalWidth) / 2f
        val topMargin = resources.getDimension(R.dimen.clock_center_date_margin_top) * scaleRatio
        val centerY = (height / 2f) + topMargin

        val color = if (isRegionDark || isDoze || isScreenOff) {
            Color.WHITE
        } else {
            Color.argb((0.6f * 255).roundToInt(), 0, 0, 0)
        }
        paint.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)

        var x = startX
        time.forEachIndexed { index, char ->
            val bitmap = digitBitmaps[char]?.value
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
}
