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
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.content.ContextCompat
import com.android.systemui.customization.R
import com.android.systemui.plugins.clocks.*
import com.android.systemui.shared.clocks.*
import com.android.systemui.shared.clocks.extensions.*

class GeneralClockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    defStyleRes: Int = 0
) : NTClockView(context, attrs, defStyleAttr, defStyleRes) {

    override fun getTag(): String = "GeneralClockView"

    val paint = Paint()
    var alarmText: String = ""
    var alarmActive: Boolean = false

    val digitResIds = intArrayOf(
        R.drawable.intervar_0, R.drawable.intervar_1, R.drawable.intervar_2,
        R.drawable.intervar_3, R.drawable.intervar_4, R.drawable.intervar_5,
        R.drawable.intervar_6, R.drawable.intervar_7, R.drawable.intervar_8,
        R.drawable.intervar_9
    )

    val digitLightResIds = intArrayOf(
        R.drawable.intervar_0_light, R.drawable.intervar_1_light, R.drawable.intervar_2_light,
        R.drawable.intervar_3_light, R.drawable.intervar_4_light, R.drawable.intervar_5_light,
        R.drawable.intervar_6_light, R.drawable.intervar_7_light, R.drawable.intervar_8_light,
        R.drawable.intervar_9_light
    )

    val contentMargin get() = context.scaledDimenInt(R.dimen.clock_content_margin)
    val clockGeneralHeight get() = context.scaledDimenInt(R.dimen.clock_general_height)
    val clockPaddingHorizontal get() = context.scaledDimen(R.dimen.clock_padding_horizontal)
    val clockPaddingVertical get() = context.scaledDimen(R.dimen.clock_padding)
    val clockTopPadding get() = context.scaledDimen(R.dimen.clock_general_padding_top)
    val containerTopPadding get() = context.scaledDimenInt(R.dimen.clock_general_container_padding_top)
    val containerBottomPadding get() = context.scaledDimenInt(R.dimen.clock_general_container_padding_bottom)
    val dotSize get() = context.scaledDimen(R.dimen.dot_small_size)
    val dotMargin get() = context.scaledDimen(R.dimen.dot_margin)
    val contentSecondaryPadding get() = context.scaledDimenInt(R.dimen.clock_content_secondary_padding)
    val contentPrimaryPadding get() = context.scaledDimenInt(R.dimen.clock_content_primary_padding)
    val textPrimarySize get() = context.scaledDimen(R.dimen.clock_text_primary_size)
    val textSecondarySize get() = context.scaledDimen(R.dimen.clock_text_secondary_size)
    val textMajorSize get() = context.scaledDimen(R.dimen.clock_text_major_size)

    val containerLayout: RelativeLayout get() = bindView(R.id.container_layout)
    val quickLookIcon: ImageView get() = bindView(R.id.quick_look_icon_view)
    val infoTopTextView: TextView get() = bindView(R.id.info_top_text_view)
    val infoFrontTextView: TextView get() = bindView(R.id.info_front_text_view)
    val infoRearTextView: TextView get() = bindView(R.id.info_rear_text_view)
    val infoPlaceholderTextView: TextView get() = bindView(R.id.info_placeholder_text_view)
    val infoBottomContainer: ViewGroup get() = bindView(R.id.info_bottom_container_layout)

    private var _num: List<Bitmap?> = createBitmaps(context, digitResIds)
    private var _numLight: List<Bitmap?> = createBitmaps(context, digitLightResIds)

    val num: List<Bitmap?> get() = _num
    val numLight: List<Bitmap?> get() = _numLight

    val scale get() = scaleRatio
    val dateTopOffset get() = dateTextY + clockTopPadding

    override fun drawClock(canvas: Canvas) {
        val timeStr = timeStr
        if (timeStr.isNullOrEmpty() || !timeStr.all { it.isDigit() }) return

        val isEventVisible = calendarData?.isEventVisible() == true
        val hasTitle = !calendarData?.title.isNullOrEmpty()
        val showCalendar = isEventVisible && hasTitle

        paint.colorFilter = PorterDuffColorFilter(clockColor, PorterDuff.Mode.SRC_IN)

        var calculatedWidth = 0f
        for ((i, char) in timeStr.withIndex()) {
            getNumBitmap(char, showCalendar)?.let {
                calculatedWidth += it.width * scale
                if (timeStr.length - i == 3) {
                    calculatedWidth += 2 * clockPaddingVertical
                }
            }
        }

        if (calculatedWidth == 0f) return
        if (calculatedWidth > width) {
            calculatedWidth = this.width.toFloat()
        }

        var xOffset = if (resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL) {
            this.width.toFloat() - clockPaddingHorizontal - calculatedWidth
        } else {
            clockPaddingHorizontal
        }

        for ((i, char) in timeStr.withIndex()) {
            val bmp = getNumBitmap(char, showCalendar) ?: continue

            val matrix = Matrix().apply {
                postScale(scale, scale)
                postTranslate(xOffset, dateTopOffset)
            }

            canvas.drawBitmap(bmp, matrix, paint)
            xOffset += bmp.width * scale

            if (timeStr.length - i == 3) {
                val centerX = xOffset + clockPaddingVertical
                val centerY = dateTopOffset + (bmp.height * scale / 2)
                val dotRadius = dotSize / 2
                val topDotY = centerY - (dotMargin / 2 + dotRadius)
                val bottomDotY = centerY + (dotMargin / 2 + dotRadius)
                canvas.drawOval(centerX - dotRadius, topDotY - dotRadius, centerX + dotRadius, topDotY + dotRadius, paint)
                canvas.drawOval(centerX - dotRadius, bottomDotY - dotRadius, centerX + dotRadius, bottomDotY + dotRadius, paint)
                xOffset += 2 * clockPaddingVertical
            }
        }
    }

    private fun getNumBitmap(char: Char, isLight: Boolean): Bitmap? {
        val index = char.digitToIntOrNull() ?: return null
        return if (isLight) numLight.getOrNull(index) else num.getOrNull(index)
    }

    override fun onCalendarDataChanged(data: CalendarSimpleData) {
        super.onCalendarDataChanged(data)
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
        val w = ViewGroup.getDefaultSize(suggestedMinimumWidth, widthMeasureSpec)
        setMeasuredDimension(w, clockGeneralHeight)

        val screenWidth = resources.displayMetrics.widthPixels
        val availableWidth = screenWidth - 2 * clockPaddingHorizontal.toInt()

        containerLayout.setPadding(0, containerTopPadding, 0, containerBottomPadding)
        containerLayout.layoutParams = containerLayout.layoutParams.apply {
            width = availableWidth
            height = clockGeneralHeight
        }

        quickLookIcon.layoutParams = (quickLookIcon.layoutParams as? LinearLayout.LayoutParams)?.apply {
            width = iconSize
            height = iconSize
            marginEnd = contentSecondaryPadding
        }

        infoTopTextView.setTextSize(0, textSecondarySize)
        (infoTopTextView.layoutParams as? RelativeLayout.LayoutParams)?.setMargins(0, 0, 0, contentMargin)

        infoPlaceholderTextView.setTextSize(0, textPrimarySize)
    }

    override fun onNTWeatherDataChanged(data: NTWeatherData) {
        super.onNTWeatherDataChanged(data)
        refreshInfo(calendarData, weatherData)
    }

    override fun onAlarmDataChanged(data: AlarmData) {
        super.onAlarmDataChanged(data)
        val withinHours = withinNHoursLocked(data, alarmVisibilityHours)
        val nextAlarmMillis = data.nextAlarmMillis ?: 0L
        nextAlarm = if (withinHours) {
            val format = if (android.text.format.DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm"
            android.text.format.DateFormat.format(format, nextAlarmMillis).toString()
        } else ""
        val visible = nextAlarm.isNotBlank()
        alarmText = if (visible) nextAlarm else ""
        alarmActive = visible
        refreshInfo(calendarData, weatherData)
    }

    override fun refreshColor() {
        super.refreshColor()
        val color = clockColor

        listOf(
            infoFrontTextView,
            infoRearTextView,
            infoTopTextView,
            infoPlaceholderTextView
        ).forEach { it.setTextColor(color) }

        quickLookIcon.setColorFilter(
            PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
        )
    }

    override fun onPlaybackStateChanged(playing: Boolean) {
        super.onPlaybackStateChanged(playing)
        refreshInfo(calendarData, weatherData)
    }

    override fun onMetadataChanged(track: String, artist: String) {
        super.onMetadataChanged(track, artist)
        refreshInfo(calendarData, weatherData)
    }

    override fun onDozeChanged(doze: Boolean) {
        super.onDozeChanged(doze)
        refreshInfo(calendarData, weatherData)
    }

    override fun onNowPlayingUpdate(npText: String) {
        super.onNowPlayingUpdate(npText)
        refreshInfo(calendarData, weatherData)
    }

    override fun refreshTime() {
        super.refreshTime()
        refreshInfo(calendarData, weatherData)
    }

    override fun onFontSettingChanged() {
        super.onFontSettingChanged()
        _num = createBitmaps(context, digitResIds)
        _numLight = createBitmaps(context, digitLightResIds)
        requestLayout()
        invalidate()
    }

    private fun refreshInfo(calendar: CalendarSimpleData?, weather: NTWeatherData?) {
        val temp = weather?.temp ?: ""
        val conditionCode = weather?.conditionCode ?: 0

        val isEventVisible = calendar?.isEventVisible() == true
        val title = calendar?.title ?: ""

        val hasCalendarData = calendar != null && calendar != CalendarSimpleData.EMPTY
        val showCalendar = hasCalendarData && isEventVisible && title.isNotEmpty()

        val hasValidWeatherData = temp.isNotEmpty() && conditionCode != 0
        val hasWeatherData = weather != null && weather != NTWeatherData.EMPTY && hasValidWeatherData

        val showWeather = !showCalendar && hasWeatherData
        val showPlaceholder = !showCalendar && !showWeather && !isNowPlaying
        
        val showSecondaryInfo = alarmActive || nowPlayingAvailable || isNowPlaying

        if (!isPlaying) NowPlayingIconBinder.get().stop()

        val infoText = when {
            showCalendar -> CalendarUtils.getCalendarDescription(context, calendar!!)
            showSecondaryInfo -> ""
            showWeather -> "$temp°"
            else -> ""
        }

        val secondaryText = when {
            showCalendar -> calendar?.location ?: ""
            alarmActive -> alarmText
            nowPlayingAvailable -> nowPlayingText
            isNowPlaying -> "$trackTitle - $artistName"
            else -> weather?.condition ?: ""
        }

        infoBottomContainer.visibility = if (showPlaceholder) View.GONE else View.VISIBLE
        infoPlaceholderTextView.visibility = if (showPlaceholder) View.VISIBLE else View.GONE
        quickLookIcon.visibility = View.GONE

        if (!showCalendar) {
            when {
                alarmActive || nowPlayingAvailable || isNowPlaying || showWeather -> {
                    if (alarmActive) {
                        quickLookIcon.setImageDrawable(ContextCompat.getDrawable(context, R.drawable.old_quick_look_alarm_icon))
                    } else if (nowPlayingAvailable || isNowPlaying) {
                        NowPlayingIconBinder.get().bindAndStart(quickLookIcon)
                    } else if (showWeather) {
                        quickLookIcon.setImageDrawable(WeatherUtils.getWeatherIcon(context, conditionCode))
                    }
                    quickLookIcon.visibility = View.VISIBLE
                }
                else -> {
                    quickLookIcon.setImageDrawable(null)
                }
            }
            quickLookIcon.layoutParams = (quickLookIcon.layoutParams as? LinearLayout.LayoutParams)?.apply {
                marginStart = if (showSecondaryInfo) 0 else contentSecondaryPadding
            }
        }

        infoFrontTextView.text = infoText
        infoFrontTextView.setTextSize(0, if (title.isEmpty()) textMajorSize else textSecondarySize)

        infoRearTextView.text = secondaryText
        infoRearTextView.setTextSize(0, if (title.isEmpty()) textSecondarySize else textMajorSize)
        (infoRearTextView.layoutParams as? LinearLayout.LayoutParams)?.setMarginStart(
            if (showCalendar) contentPrimaryPadding else 0
        )

        infoTopTextView.text = title
    }
}
