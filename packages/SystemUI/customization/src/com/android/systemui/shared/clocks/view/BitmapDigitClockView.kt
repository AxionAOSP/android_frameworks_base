/*
 * Copyright (C) 2025 AxionOS
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

abstract class BitmapDigitClockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    defStyleRes: Int = 0
) : NTClockView(context, attrs, defStyleAttr, defStyleRes) {

    protected open val tagName: String = "BitmapDigitClockView"
    protected abstract val digitResIds: IntArray

    protected open val digitSpacing: Float get() = context.scaledDimen(R.dimen.clock_padding)
    protected open val topMargin: Float get() = context.scaledDimen(R.dimen.clock_center_date_margin_top)

    protected val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    protected var digitBitmaps: Map<Char, Bitmap?> = emptyMap()
    private var bitmapsReady = false

    override fun getTag(): String = tagName

    protected abstract fun clockColor(): Int

    protected open val digitScale: Float
        get() = scaleRatio

    protected open fun createDigitBitmaps(): Map<Char, Bitmap?> {
        val ids = digitResIds
        if (ids.isEmpty()) return emptyMap()
        val bitmaps = ids.map { resId ->
            ContextCompat.getDrawable(context, resId)?.toBitmap()
        }
        return ('0'..'9').zip(bitmaps).toMap()
    }

    override fun onFontSettingChanged() {
        loadBitmaps()
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        loadBitmaps()
    }

    private fun loadBitmaps() {
        if (!bitmapsReady) {
            digitBitmaps = createDigitBitmaps()
            bitmapsReady = true
        }
    }

    override fun drawClock(canvas: Canvas) {
        if (!bitmapsReady) loadBitmaps()
        val time = timeStr
        if (time.isEmpty() || !TextUtils.isDigitsOnly(time)) return
        
        val scale = digitScale

        val totalWidth = computeTotalWidth(time, scale)
        if (totalWidth <= 0f) return

        val availableWidth = width.toFloat()
        val finalWidth = min(totalWidth, availableWidth)
        val startX = (availableWidth - finalWidth) / 2f
        val centerY = (height / 2f) + topMargin

        paint.colorFilter = PorterDuffColorFilter(clockColor(), PorterDuff.Mode.SRC_IN)

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
                if (index < time.lastIndex) x += digitSpacing
            }
        }
    }

    private fun computeTotalWidth(time: String, scale: Float): Float {
        var total = 0f
        time.forEachIndexed { index, char ->
            val bmp = digitBitmaps[char]
            if (bmp != null) {
                total += bmp.width * scale
                if (index < time.lastIndex) total += digitSpacing
            }
        }
        return total
    }
}
