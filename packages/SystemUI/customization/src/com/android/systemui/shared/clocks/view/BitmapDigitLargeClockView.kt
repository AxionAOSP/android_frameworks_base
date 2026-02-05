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
import android.graphics.*
import android.text.TextUtils
import android.util.AttributeSet
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.constraintlayout.widget.ConstraintLayout
import com.android.systemui.customization.R
import com.android.systemui.shared.clocks.extensions.*
import kotlin.math.min

abstract class BitmapDigitLargeClockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    defStyleRes: Int = 0
) : NTClockView(context, attrs, defStyleAttr, defStyleRes) {

    protected open val tagName: String = "BitmapDigitLargeClockView"
    
    protected open val digitResIds: IntArray = intArrayOf()
    
    protected open val digitSpacing: Float get() = context.scaledDimen(R.dimen.large_clock_digit_spacing)
    protected open val lineSpacing: Float get() = context.scaledDimen(R.dimen.large_clock_line_spacing)

    protected val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    protected var digitBitmaps: Map<Char, Bitmap?> = emptyMap()
    private var bitmapsReady = false

    override fun getTag(): String = tagName

    protected abstract fun clockColor(): Int

    protected val largeClockScaleRatio: Float
        get() = minOf(scaleRatio, MAX_TABLET_SCALE)

    protected open val digitScale: Float
        get() = largeClockScaleRatio * 1.8f

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
        
        val (hours, minutes) = when (time.length) {
            4 -> time.substring(0, 2) to time.substring(2, 4)
            3 -> time.substring(0, 1) to time.substring(1, 3)
            else -> return
        }
        
        val scale = digitScale
        paint.colorFilter = PorterDuffColorFilter(clockColor(), PorterDuff.Mode.SRC_IN)

        val hoursWidth = computeLineWidth(hours, scale)
        val minutesWidth = computeLineWidth(minutes, scale)
        
        val sampleBitmap = digitBitmaps['0'] ?: return
        val digitHeight = sampleBitmap.height * scale
        
        val clockContentHeight = digitHeight * 2 + lineSpacing
        val dateAreaHeight = if (dateVisible) largeDateTopMargin + clockDateTextSize else 0f
        val totalContentHeight = clockContentHeight + dateAreaHeight
        
        val location = IntArray(2)
        getLocationOnScreen(location)
        val viewY = location[1]
        val screenHeight = context.resources.displayMetrics.heightPixels
        val startY = (screenHeight - totalContentHeight) / 2f - viewY
        
        val guidelineId = resources.getIdentifier("split_shade_guideline", "id", "com.android.systemui")
        val params = layoutParams as? ConstraintLayout.LayoutParams
        val isConstrainedToSplit = guidelineId != 0 && params?.endToEnd == guidelineId
        
        val screenWidth = context.resources.displayMetrics.widthPixels
        val alignLeft = isSplitShade && isConstrainedToSplit
        
        val viewX = location[0]
        val hoursX = if (alignLeft) 0f else (screenWidth - hoursWidth) / 2f - viewX
        val minutesX = if (alignLeft) 0f else (screenWidth - minutesWidth) / 2f - viewX

        drawLine(canvas, hours, scale, hoursX, startY)
        drawLine(canvas, minutes, scale, minutesX, startY + digitHeight + lineSpacing)
        
        clockTopY = startY
        clockBottomY = startY + clockContentHeight
        val maxWidth = maxOf(hoursWidth, minutesWidth)
        clockLeft = if (alignLeft) 0f else (width - maxWidth) / 2f
        clockRight = clockLeft + maxWidth
    }
    
    private var clockTopY: Float = 0f
    private var clockBottomY: Float = 0f
    private var clockLeft: Float = 0f
    private var clockRight: Float = 0f
    
    private val largeDateTopMargin: Float
        get() = context.resources.displayMetrics.density * 24f
    
    override val dateTextY: Float
        get() = if (clockBottomY > 0) {
            clockBottomY + largeDateTopMargin + clockDateTextSize
        } else {
            super.dateTextY
        }

    private fun drawLine(canvas: Canvas, digits: String, scale: Float, startX: Float, y: Float) {
        var x = startX
        digits.forEach { char ->
            val bitmap = digitBitmaps[char] ?: return@forEach
            val matrix = Matrix().apply {
                postScale(scale, scale)
                postTranslate(x, y)
            }
            canvas.drawBitmap(bitmap, matrix, paint)
            x += bitmap.width * scale + digitSpacing
        }
    }

    private fun computeLineWidth(digits: String, scale: Float): Float {
        var total = 0f
        digits.forEachIndexed { index, char ->
            val bmp = digitBitmaps[char]
            if (bmp != null) {
                total += bmp.width * scale
                if (index < digits.lastIndex) {
                    total += digitSpacing
                }
            }
        }
        return total
    }

    private val largeClockHeight: Int
        get() {
            val sampleBitmap = digitBitmaps['0']
            return if (sampleBitmap != null) {
                val digitHeight = (sampleBitmap.height * digitScale).toInt()
                (digitHeight * 2 + lineSpacing).toInt()
            } else {
                context.scaledDimenInt(R.dimen.large_clock_height)
            }
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (!bitmapsReady) loadBitmaps()
        
        val time = timeStr
        val (hours, minutes) = when (time.length) {
            4 -> time.substring(0, 2) to time.substring(2, 4)
            3 -> time.substring(0, 1) to time.substring(1, 3)
            else -> "12" to "00"
        }
        
        val scale = digitScale
        val hWidth = computeLineWidth(hours, scale)
        val mWidth = computeLineWidth(minutes, scale)
        val contentWidth = maxOf(hWidth, mWidth).toInt() + paddingLeft + paddingRight
        
        val desiredWidth = contentWidth
        val desiredHeight = largeClockHeight + paddingTop + paddingBottom + dateHeight

        setMeasuredDimension(
            resolveSize(desiredWidth, widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec)
        )
    }

    override fun getContentBounds(): RectF? {
        if (clockBottomY <= 0f) return null
        return RectF(clockLeft, clockTopY, clockRight, clockBottomY)
    }

    companion object {
        private const val MAX_TABLET_SCALE = 1.2f
    }
}
