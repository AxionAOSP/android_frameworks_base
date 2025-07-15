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
import com.android.systemui.customization.R
import com.android.systemui.shared.clocks.extensions.*
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

class GraphicClockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    defStyleRes: Int = 0
) : NTClockView(context, attrs, defStyleAttr, defStyleRes) {

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val faceInnerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.TRANSPARENT
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }

    private val handPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        strokeCap = Paint.Cap.ROUND
    }

    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val dotSize get() = context.scaledDimen(R.dimen.dot_size)
    private val handSize get() = context.scaledDimen(R.dimen.clock_hand_size)

    private val bitmaps: List<Bitmap?> get() = createBitmaps(context, intArrayOf(
        R.drawable.graphic_tick,
        R.drawable.graphic_tick_light
    ))

    private val tick get() = bitmaps[0]
    private val tickLight get() = bitmaps[1]

    override fun getTag(): String = "GraphicClockView"

    override fun drawClock(canvas: Canvas) {
        if (timeStr.isNullOrBlank() || !TextUtils.isDigitsOnly(timeStr)) return
        val dotColor = clockColor
        drawClockTicks(canvas)
        drawClockDot(canvas, dotSize * 1.5f, dotColor)
        drawClockHands(canvas, timeStr)
        if (!isDoze && !isScreenOff) {
            drawClockDot(canvas, dotSize, ContextCompat.getColor(context, R.color.clock_dot_color))
        } else {
            drawClockDot(canvas, dotSize, 0, PorterDuffXfermode(PorterDuff.Mode.CLEAR))
        }
    }

    private fun drawClockDot(canvas: Canvas, size: Float, color: Int, mode: Xfermode? = null) {
        dotPaint.color = color
        dotPaint.xfermode = mode
        val cx = width / 2f
        val cy = height / 2f
        val radius = size / 2f
        canvas.drawOval(RectF(cx - radius, cy - radius, cx + radius, cy + radius), dotPaint)
    }

    private fun drawClockHands(canvas: Canvas, time: String) {
        try {
            val seconds = time.takeLast(2).toInt()
            val minutes = time.dropLast(2).takeLast(2).toInt()
            val hours = time.dropLast(4).toInt()
            val baseColor = clockColor
            val highlightColor = ContextCompat.getColor(context, R.color.clock_dot_color)
            val hourAngle = (hours + minutes / 60f) * 5
            val minuteAngle = minutes + seconds / 60f
            drawHand(canvas, hourAngle, handSize * 2, 0.22f to 0.38f, baseColor)
            drawHand(canvas, minuteAngle, handSize, 0.22f to 0.42f, baseColor)
            drawHand(canvas, seconds.toFloat(), handSize / 2, 0.19f to 0.42f, highlightColor)
        } catch (_: NumberFormatException) {
        }
    }

    private fun drawHand(
        canvas: Canvas,
        position: Float,
        thickness: Float,
        multipliers: Pair<Float, Float>,
        color: Int
    ) {
        val centerX = width / 2f
        val centerY = height / 2f
        val angleRad = Math.toRadians((position * 6 - 90).toDouble())
        val startLength = multipliers.first * height
        val endLength = multipliers.second * height
        val startX = centerX - cos(angleRad) * startLength
        val startY = centerY - sin(angleRad) * startLength
        val endX = centerX + cos(angleRad) * endLength
        val endY = centerY + sin(angleRad) * endLength
        handPaint.color = color
        handPaint.strokeWidth = thickness
        canvas.drawLine(startX.toFloat(), startY.toFloat(), endX.toFloat(), endY.toFloat(), handPaint)
    }

    private fun drawClockTicks(canvas: Canvas) {
        val tick = this.tick ?: return
        val tickLight = this.tickLight ?: return
        val color = clockColor
        tickPaint.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
        val tickWidth = tick.width * scaleRatio
        val tickHeight = tick.height * scaleRatio
        val left = (width - tickWidth) / 2f
        val top = (height - tickHeight) / 2f
        val matrix = Matrix().apply {
            postScale(scaleRatio, scaleRatio)
            postTranslate(left, top)
        }
        if (!isDoze && !isScreenOff) {
            canvas.drawBitmap(tickLight, matrix, tickPaint)
        }
        canvas.drawBitmap(tick, matrix, tickPaint)
    }

    override fun refreshTime() {
        if (format == null) return
        val now = System.currentTimeMillis()
        if (now - calendar.timeInMillis in 0..900) return
        calendar.timeInMillis = now
        val str = SimpleDateFormat(format, Locale.ENGLISH).format(calendar.time)
        if (timeStr != str && str.isNotEmpty()) {
            timeStr = str
            contentDescription = talkBackContent
            invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        bitmaps
        refreshTime()
        postInvalidateOnAnimation()
    }
}
