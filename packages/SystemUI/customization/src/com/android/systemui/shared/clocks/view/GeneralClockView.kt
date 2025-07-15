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
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.android.systemui.customization.R
import com.android.systemui.plugins.clocks.CalendarSimpleData
import com.android.systemui.plugins.clocks.NTWeatherData
import com.android.systemui.shared.clocks.CalendarUtils
import com.android.systemui.shared.clocks.WeatherUtils
import kotlin.Lazy
import kotlin.LazyThreadSafetyMode
import kotlin.math.max

class GeneralClockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    defStyleRes: Int = 0
) : NTClockView(context, attrs, defStyleAttr, defStyleRes) {

    override fun getTag(): String = "GeneralClockView"

    val paint = Paint()
    var weatherData: NTWeatherData? = null
    var calendarData: CalendarSimpleData? = null

    private val digitResIds = intArrayOf(
        R.drawable.intervar_0, R.drawable.intervar_1, R.drawable.intervar_2,
        R.drawable.intervar_3, R.drawable.intervar_4, R.drawable.intervar_5,
        R.drawable.intervar_6, R.drawable.intervar_7, R.drawable.intervar_8,
        R.drawable.intervar_9
    )

    private val digitLightResIds = intArrayOf(
        R.drawable.intervar_0_light, R.drawable.intervar_1_light, R.drawable.intervar_2_light,
        R.drawable.intervar_3_light, R.drawable.intervar_4_light, R.drawable.intervar_5_light,
        R.drawable.intervar_6_light, R.drawable.intervar_7_light, R.drawable.intervar_8_light,
        R.drawable.intervar_9_light
    )

    val num by lazyBitmaps(context, digitResIds)
    val numLight by lazyBitmaps(context, digitLightResIds)

    private fun lazyBitmaps(context: Context, resIds: IntArray): Lazy<List<Bitmap?>> = lazy {
        resIds.map { resId ->
            ContextCompat.getDrawable(context, resId)?.toBitmap()
        }
    }

    override fun drawClock(canvas: Canvas) {
        val timeStr = timeStr
        if (timeStr.isNullOrEmpty() || !timeStr.all { it.isDigit() }) return

        val isEventVisible = calendarData?.isEventVisible() == true
        val hasTitle = !calendarData?.title.isNullOrEmpty()
        val showCalendar = isEventVisible && hasTitle

        val scale = scaleRatio
        val topPadding = resources.getDimension(R.dimen.clock_padding_top) * scale
        val horizontalPadding = resources.getDimension(R.dimen.clock_padding_horizontal) * scale
        val digitPadding = resources.getDimension(R.dimen.clock_padding) * scale
        val dotSize = resources.getDimension(R.dimen.dot_small_size) * scale
        val dotMargin = resources.getDimension(R.dimen.dot_margin) * scale

        val color = getClockColor()
        paint.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)

        var calculatedWidth = 0f
        for ((i, char) in timeStr.withIndex()) {
            getNumBitmap(char, showCalendar)?.let {
                calculatedWidth += it.width * scale
                if (timeStr.length - i == 3) {
                    calculatedWidth += 2 * digitPadding
                }
            }
        }

        if (calculatedWidth == 0f) return
        if (calculatedWidth > width) {
            calculatedWidth = this.width.toFloat()
        }
        var xOffset = if (resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL) {
            this.width.toFloat() - horizontalPadding - calculatedWidth
        } else {
            horizontalPadding
        }

        for ((i, char) in timeStr.withIndex()) {
            val bmp = getNumBitmap(char, showCalendar) ?: continue

            val matrix = Matrix().apply {
                postScale(scale, scale)
                postTranslate(xOffset, topPadding)
            }

            canvas.drawBitmap(bmp, matrix, paint)
            xOffset += bmp.width * scale

            if (timeStr.length - i == 3) {
                val centerX = xOffset + digitPadding
                val centerY = topPadding + (bmp.height * scale / 2)
                val dotRadius = dotSize / 2
                val topDotY = centerY - (dotMargin / 2 + dotRadius)
                val bottomDotY = centerY + (dotMargin / 2 + dotRadius)
                canvas.drawOval(centerX - dotRadius, topDotY - dotRadius, centerX + dotRadius, topDotY + dotRadius, paint)
                canvas.drawOval(centerX - dotRadius, bottomDotY - dotRadius, centerX + dotRadius, bottomDotY + dotRadius, paint)
                xOffset += 2 * digitPadding
            }
        }
    }

    private fun getNumBitmap(char: Char, isLight: Boolean): Bitmap? {
        val index = char.digitToIntOrNull() ?: return null
        return if (isLight) numLight.getOrNull(index) else num.getOrNull(index)
    }

    override fun onCalendarDataChanged(data: CalendarSimpleData) {
        if (calendarData == data) return
        calendarData = data
        refreshInfo(calendarData, weatherData)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        getChildAt(0)?.let { child ->
            val left = (width - child.measuredWidth) / 2
            val top = (height - child.measuredHeight) / 2
            child.layout(left, top, left + child.measuredWidth, top + child.measuredHeight)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        measureChildren(widthMeasureSpec, heightMeasureSpec)
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)

        val scale = scaleRatio
        val dimension = resources.getDimension(R.dimen.clock_padding_horizontal) * scale
        val dimension2 = (resources.getDimension(R.dimen.clock_content_secondary_padding) * scale).toInt()
        val dimension3 = (resources.getDimension(R.dimen.clock_icon_secondary_size) * scale).toInt()
        val dimension4 = resources.getDimension(R.dimen.clock_text_primary_size) * scale
        val dimension5 = resources.getDimension(R.dimen.clock_text_secondary_size) * scale
        val screenWidth = resources.displayMetrics.widthPixels
        val availableWidth = (screenWidth - (dimension * 2)).toInt()
        val desiredHeight = (resources.getDimension(R.dimen.clock_height) * scale).toInt()

        val containerLayout = findViewById<RelativeLayout>(R.id.container_layout)
        containerLayout?.let {
            it.setPadding(
                0,
                (it.resources.getDimension(R.dimen.clock_padding_top) * scale).toInt(),
                0,
                (it.resources.getDimension(R.dimen.clock_padding_bottom) * scale).toInt()
            )
            it.layoutParams = it.layoutParams.apply {
                width = availableWidth
                height = desiredHeight
            }
        }

        val weatherIcon = findViewById<ImageView>(R.id.weather_icon_view)
        weatherIcon?.let {
            val lp = it.layoutParams as? LinearLayout.LayoutParams
            lp?.apply {
                width = dimension3
                height = dimension3
                marginStart = dimension2
                marginEnd = dimension2
            }
            it.layoutParams = lp
        }

        val topTextView = findViewById<TextView>(R.id.info_top_text_view)
        topTextView?.let {
            it.setTextSize(0, dimension5)
            val marginBottom = (it.resources.getDimension(R.dimen.clock_content_margin) * scale).toInt()
            val lp = it.layoutParams as? RelativeLayout.LayoutParams
            lp?.setMargins(0, 0, 0, marginBottom)
            it.layoutParams = lp
        }

        val placeholderTextView = findViewById<TextView>(R.id.info_placeholder_text_view)
        placeholderTextView?.setTextSize(0, dimension4)
    }

    override fun onNTWeatherDataChanged(data: NTWeatherData) {
        if (weatherData == data) return
        weatherData = data
        refreshInfo(calendarData, weatherData)
    }

    override fun refreshColor() {
        super.refreshColor()

        val color = getClockColor()

        listOf(
            R.id.info_front_text_view,
            R.id.info_rear_text_view,
            R.id.info_top_text_view,
            R.id.info_placeholder_text_view
        ).forEach { id ->
            findViewById<TextView>(id)?.setTextColor(color)
        }

        findViewById<ImageView>(R.id.weather_icon_view)?.setColorFilter(
            PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
        )
    }

    private fun refreshInfo(calendar: CalendarSimpleData?, weather: NTWeatherData?) {
        val temp = weather?.temp ?: Int.MIN_VALUE
        var title = ""
        var location = weather?.phrase ?: ""

        val isEventVisible = calendar?.isEventVisible() == true
        val hasTitle = !calendar?.title.isNullOrEmpty()
        val hasLocation = !calendar?.location.isNullOrEmpty()

        val infoText: String = when {
            isEventVisible -> CalendarUtils.getCalendarDescription(context, calendar!!)
            temp != Int.MIN_VALUE -> "$temp°"
            else -> ""
        }

        if (isEventVisible) {
            location = calendar?.location ?: ""
        }

        if (isEventVisible && hasTitle) {
            title = calendar?.title ?: ""
        }

        val isPlaceholderVisible = infoText.isEmpty() && location.isEmpty() && title.isNotEmpty()

        val majorSize = resources.getDimension(R.dimen.clock_text_major_size) * scaleRatio
        val secondarySize = resources.getDimension(R.dimen.clock_text_secondary_size) * scaleRatio
        val primaryPadding = (resources.getDimension(R.dimen.clock_content_primary_padding) * scaleRatio).toInt()

        findViewById<ViewGroup>(R.id.info_bottom_container_layout)?.visibility =
            if (isPlaceholderVisible) View.GONE else View.VISIBLE

        findViewById<TextView>(R.id.info_placeholder_text_view)?.apply {
            visibility = if (isPlaceholderVisible) View.VISIBLE else View.GONE
        }

        findViewById<ImageView>(R.id.weather_icon_view)?.apply {
            visibility = if (!isEventVisible && !isPlaceholderVisible) View.VISIBLE else View.GONE
            setImageResource(WeatherUtils.getWeatherIcon(weather?.iconType ?: 0))
        }

        findViewById<TextView>(R.id.info_front_text_view)?.apply {
            text = infoText
            textSize = if (title.isEmpty()) majorSize else secondarySize
        }

        findViewById<TextView>(R.id.info_rear_text_view)?.apply {
            text = location
            textSize = if (title.isNotEmpty()) secondarySize else majorSize
            val layoutParams = layoutParams as? LinearLayout.LayoutParams
            layoutParams?.setMarginStart(if (isEventVisible) primaryPadding else 0)
            this.layoutParams = layoutParams
        }

        findViewById<TextView>(R.id.info_top_text_view)?.text = title
    }

    override fun refreshTime() {
        super.refreshTime()
        refreshInfo(calendarData, weatherData)
    }
}
