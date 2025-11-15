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
import kotlin.LazyThreadSafetyMode
import kotlin.math.min

abstract class BitmapDigitClockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    defStyleRes: Int = 0
) : NTClockView(context, attrs, defStyleAttr, defStyleRes) {

    protected open val tagName: String = "BitmapDigitClockView"
    
    protected open val digitResIds: IntArray = intArrayOf()
    
    protected open val digitResIdPairs: Map<Char, Pair<Int, Int>> = emptyMap()

    protected open val digitSpacing: Float get() = context.scaledDimen(R.dimen.clock_padding)
    protected open val topMargin: Float get() = context.scaledDimen(R.dimen.clock_center_date_margin_top)
    protected open val clockOffset: Float get() = 0f

    protected open val useSeparator: Boolean get() = false
    protected open val separatorType: SeparatorType get() = SeparatorType.NONE
    protected open val dotSize: Float get() = context.scaledDimen(R.dimen.dot_size)
    protected open val dotMargin: Float get() = context.scaledDimen(R.dimen.dot_margin)
    protected open val dotCenterMargin: Float get() = context.scaledDimen(R.dimen.dot_margin_center)

    protected val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    protected var digitBitmaps: Map<Char, Bitmap?> = emptyMap()
    protected var digitBitmapPairs: Map<Char, Pair<Lazy<Bitmap?>, Lazy<Bitmap?>>> = emptyMap()
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

    protected open fun createDigitBitmapPairs(): Map<Char, Pair<Lazy<Bitmap?>, Lazy<Bitmap?>>> {
        if (digitResIdPairs.isEmpty()) return emptyMap()
        val mode = LazyThreadSafetyMode.NONE
        return digitResIdPairs.mapValues { (_, resPair) ->
            val (normalBmp, lightBmp) = createBitmaps(context, intArrayOf(resPair.first, resPair.second))
            lazy(mode) { normalBmp } to lazy(mode) { lightBmp }
        }
    }

    protected open fun getCustomSpacing(time: String, index: Int): Float = 0f

    protected open fun shouldDrawSeparator(time: String, index: Int): Boolean {
        if (!useSeparator) return false
        return when (time.length) {
            4 -> index == 1
            3 -> index == 0
            else -> false
        }
    }

    protected open fun shouldUseLightVariant(time: String, index: Int): Boolean = false

    protected open fun getBitmapForDigit(char: Char, useLightVariant: Boolean = false): Bitmap? {
        return if (digitBitmapPairs.isNotEmpty()) {
            val (normal, light) = digitBitmapPairs[char] ?: return null
            if (useLightVariant) light.value else normal.value
        } else {
            digitBitmaps[char]
        }
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
            if (digitResIdPairs.isNotEmpty()) {
                digitBitmapPairs = createDigitBitmapPairs()
            } else {
                digitBitmaps = createDigitBitmaps()
            }
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
        
        paint.colorFilter = PorterDuffColorFilter(clockColor(), PorterDuff.Mode.SRC_IN)

        var x = startX
        time.forEachIndexed { index, char ->
            val useLightVariant = shouldUseLightVariant(time, index)
            val bitmap = getBitmapForDigit(char, useLightVariant) ?: return@forEachIndexed
            
            val centerY = (height / 2f) + topMargin
            val yOffset = centerY - (bitmap.height * scale / 2) - clockOffset
            
            val matrix = Matrix().apply {
                postScale(scale, scale)
                postTranslate(x, yOffset)
            }
            canvas.drawBitmap(bitmap, matrix, paint)
            
            x += bitmap.width * scale
            
            val customSpace = getCustomSpacing(time, index)
            x += customSpace
            
            if (shouldDrawSeparator(time, index)) {
                x += drawSeparator(canvas, x, yOffset, bitmap.height * scale)
            } else if (index < time.lastIndex) {
                x += digitSpacing
            }
        }
    }

    protected open fun drawSeparator(canvas: Canvas, x: Float, baseY: Float, digitHeight: Float): Float {
        return when (separatorType) {
            SeparatorType.DOTS -> {
                val centerX = x + dotCenterMargin
                val radius = dotSize / 2
                val dotY = baseY + digitHeight / 2 + clockOffset - dotMargin / 2
                
                canvas.drawOval(
                    centerX - radius, dotY - radius,
                    centerX + radius, dotY + radius,
                    paint
                )
                canvas.drawOval(
                    centerX - radius, dotY + dotMargin - radius,
                    centerX + radius, dotY + dotMargin + radius,
                    paint
                )
                
                dotCenterMargin * 2
            }
            SeparatorType.NONE -> 0f
        }
    }

    private fun computeTotalWidth(time: String, scale: Float): Float {
        var total = 0f
        time.forEachIndexed { index, char ->
            val useLightVariant = shouldUseLightVariant(time, index)
            val bmp = getBitmapForDigit(char, useLightVariant)
            if (bmp != null) {
                total += bmp.width * scale
                total += getCustomSpacing(time, index)
                
                if (shouldDrawSeparator(time, index)) {
                    total += when (separatorType) {
                        SeparatorType.DOTS -> dotCenterMargin * 2
                        SeparatorType.NONE -> 0f
                    }
                } else if (index < time.lastIndex) {
                    total += digitSpacing
                }
            }
        }
        return total
    }

    enum class SeparatorType {
        NONE,
        DOTS
    }
}
