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
import android.content.res.Resources
import android.graphics.*
import android.graphics.drawable.AnimatedVectorDrawable
import android.icu.text.DateFormat
import android.icu.text.DisplayContext
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.AttributeSet
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.android.systemui.customization.R
import com.android.systemui.plugins.clocks.*
import com.android.systemui.shared.clocks.*
import java.util.*

class OldQuickLookClockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    defStyleRes: Int = 0
) : NTClockView(context, attrs, defStyleAttr, defStyleRes) {

    private val handler = Handler(Looper.getMainLooper())
    private val currentTime = Date()
    private val datePattern = context.getString(R.string.system_ui_aod_date_pattern)

    private var lastText: String? = null
    private var calendarData: CalendarSimpleData? = null
    private var weatherData: NTWeatherData? = null
    private var isDarkTheme: Boolean? = null

    override fun getTag(): String = "OLDQuickLookClockView"

    private val clockTextView get() = findViewById<TextView>(R.id.clock_text_view)
    private val placeholderTextView get() = findViewById<TextView>(R.id.placeholder_text_view)
    private val dateTextView get() = findViewById<TextView>(R.id.date_text_view)
    private val alarmInfoTextView get() = findViewById<TextView>(R.id.alarm_info)
    private val weatherTextView get() = findViewById<TextView>(R.id.weather_info_text_view)
    private val calendarTitleTextView get() = findViewById<TextView>(R.id.calendar_title)
    private val calendarInfoTextView get() = findViewById<TextView>(R.id.calendar_info)

    private val alarmIconView get() = findViewById<ImageView>(R.id.alarm_icon_view)
    private val weatherIconView get() = findViewById<ImageView>(R.id.weather_icon_view)
    private val npIconView get() = findViewById<ImageView>(R.id.now_playing_icon)

    private val dateContainerView get() = findViewById<LinearLayout>(R.id.date_container_view)
    private val weatherContainerView get() = findViewById<View>(R.id.weather_container_view)
    private val calendarContainerView get() = findViewById<View>(R.id.calendar_info_container_view)
    private val containerLayout get() = findViewById<LinearLayout>(R.id.container_layout)

    override fun drawClock(canvas: Canvas) {}

    override fun refreshTime() {
        super.refreshTime()
        clockTextView?.text = timeStr
        refreshInfo(calendarData, weatherData)
        updateDateInfo()
        requestLayout()
    }

    override fun refreshColor() {
        val color = clockColor

        listOf(
            clockTextView, dateTextView, placeholderTextView,
            weatherTextView, calendarTitleTextView, calendarInfoTextView, alarmInfoTextView
        ).forEach { it?.setTextColor(color) }

        listOf(alarmIconView, weatherIconView).forEach {
            it?.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
        }

        val paddingHorizontal = resources.getDimension(R.dimen.clock_padding_horizontal) * scaleRatio
        val displayMetrics = resources.displayMetrics
        var w = displayMetrics.heightPixels
        if (displayMetrics.widthPixels < displayMetrics.heightPixels) {
            w = displayMetrics.widthPixels
        }
        val h = (resources.getDimension(R.dimen.old_clock_height) * scaleRatio).toInt()

        containerLayout?.layoutParams?.apply {
            this.width = (w - (2 * paddingHorizontal)).toInt()
            this.height = h
        }
        super.refreshColor()
    }

    fun refreshUI() {
        val resources = context.resources

        val topMarginWithWeather = (resources.getDimension(R.dimen.old_clock_text_margin_top) * scaleRatio).toInt()
        val topMarginNoWeather = (resources.getDimension(R.dimen.old_one_line_info_clock_text_margin_top) * scaleRatio).toInt()
        val bottomMarginValue = (resources.getDimension(R.dimen.old_clock_text_margin_bottom) * scaleRatio).toInt()
        val infoPadding = (resources.getDimension(R.dimen.old_clock_info_text_padding) * scaleRatio).toInt()
        val weatherPadding = (resources.getDimension(R.dimen.old_clock_weather_info_text_padding) * scaleRatio).toInt()
        val iconSize = (resources.getDimension(R.dimen.old_clock_icon_primary_size) * scaleRatio).toInt()
        val alarmIconSize = (resources.getDimension(R.dimen.old_clock_alarm_icon_primary_size) * scaleRatio).toInt()
        val primaryTextSize = resources.getDimension(R.dimen.old_clock_primary_text_size) * scaleRatio
        val secondaryTextSize = resources.getDimension(R.dimen.old_clock_secondary_text_size) * scaleRatio

        clockTextView?.apply {
            setTextSize(0, primaryTextSize)
            (layoutParams as? LinearLayout.LayoutParams)?.apply {
                width = LinearLayout.LayoutParams.WRAP_CONTENT
                height = LinearLayout.LayoutParams.WRAP_CONTENT
                topMargin = if (weatherData != null) topMarginWithWeather else topMarginNoWeather
                bottomMargin = bottomMarginValue
            }
        }

        placeholderTextView?.apply {
            setTextSize(0, secondaryTextSize)
            setBottomMargin(infoPadding)
        }

        dateContainerView?.apply {
            setBottomMargin(infoPadding)
        }

        dateTextView?.apply {
            setTextSize(0, secondaryTextSize)
            (layoutParams as? LinearLayout.LayoutParams)?.apply {
                marginEnd = infoPadding
            }
        }

        alarmInfoTextView?.apply {
            setTextSize(0, secondaryTextSize)
        }

        alarmIconView?.apply {
            (layoutParams as? LinearLayout.LayoutParams)?.apply {
                width = alarmIconSize
                height = alarmIconSize
                marginEnd = infoPadding
            }
        }

        weatherTextView?.apply {
            setTextSize(0, secondaryTextSize)
        }

        weatherIconView?.apply {
            (layoutParams as? LinearLayout.LayoutParams)?.apply {
                width = iconSize
                height = iconSize
                marginEnd = weatherPadding
            }
        }

        npIconView?.apply {
            (layoutParams as? LinearLayout.LayoutParams)?.apply {
                width = iconSize
                height = iconSize
                marginEnd = weatherPadding
            }
            setBottomMargin(infoPadding)
        }

        calendarTitleTextView?.apply {
            setTextSize(0, secondaryTextSize)
            setBottomMargin(infoPadding)
        }

        calendarInfoTextView?.apply {
            setTextSize(0, secondaryTextSize)
        }
    }

    private fun View.setBottomMargin(margin: Int) {
        (layoutParams as? LinearLayout.LayoutParams)?.let {
            it.bottomMargin = margin
            layoutParams = it
        }
    }

    private fun updateDateInfo() {
        handler.postDelayed({ updateDateInfoInternal() }, 100)
    }

    private fun updateDateInfoInternal() {
        val newDate = formattedDate
        if (newDate != lastText) {
            lastText = newDate
            dateTextView?.text = newDate
        }
        refreshInfo(calendarData, weatherData)
    }

    private val formattedDate: String
        get() {
            val locale = Locale.getDefault()
            val instanceForSkeleton = DateFormat.getInstanceForSkeleton(this.datePattern, locale)
            instanceForSkeleton.setContext(DisplayContext.CAPITALIZATION_FOR_BEGINNING_OF_SENTENCE)
            currentTime.setTime(System.currentTimeMillis())
            if (instanceForSkeleton != null) {
                return instanceForSkeleton.format(this.currentTime)
            }
            return ""
        }

    private fun refreshInfo(calendar: CalendarSimpleData?, weather: NTWeatherData?) {
        val isJpLang = Locale.getDefault().language == "ja"
        val fontFamily = if (isJpLang) "NDot77JPExtended" else "nothingdot"
        val textTypeface = Typeface.create(fontFamily, Typeface.NORMAL)

        val temperature = weather?.temp ?: ""
        val weatherCondition = weather?.condition ?: ""
        val conditionCode = weather?.conditionCode ?: 0
        val weatherIcon = WeatherUtils.getWeatherIcon(context, conditionCode)

        val hasCalendarData = calendar != null && calendar != CalendarSimpleData.EMPTY
        val showWeather = weather != null && weather != NTWeatherData.EMPTY 
            && temperature.isNotEmpty() 
            && weatherCondition.isNotEmpty() && conditionCode != 0
        val showCalendar = hasCalendarData && calendar?.isEventVisible() == true

        if (!isPlaying && !nowPlayingAvailable) {
            NowPlayingIconBinder.get().stop()
            npIconView.setImageDrawable(null)
            npIconView.visibility = View.GONE
        }

        if (nowPlayingAvailable) {
            npIconView.visibility = View.VISIBLE
            NowPlayingIconBinder.get().bindAndStart(npIconView)
            calendarTitleTextView?.text = "$nowPlayingText"
            calendarInfoTextView?.text = ""
            calendarContainerView?.visibility = View.VISIBLE
            weatherContainerView?.visibility = View.GONE
            placeholderTextView?.visibility = View.GONE
            dateContainerView?.visibility = View.GONE
        } else if (isNowPlaying) {
            npIconView.visibility = View.VISIBLE
            NowPlayingIconBinder.get().bindAndStart(npIconView)
            calendarTitleTextView?.text = "$trackTitle"
            calendarInfoTextView?.text = "$artistName"
            calendarContainerView?.visibility = View.VISIBLE
            weatherContainerView?.visibility = View.GONE
            placeholderTextView?.visibility = View.GONE
            dateContainerView?.visibility = View.GONE
        } else if (showCalendar) {
            calendarTitleTextView?.text = calendar.title ?: ""
            val location = calendar.location.orEmpty()
            var calTime = CalendarUtils.getCalendarWidgetTime(context, calendar)
            if (location.isNotBlank()) {
                calTime += " $location"
            }
            calendarInfoTextView?.text = calTime
            calendarContainerView?.visibility = View.VISIBLE
            weatherContainerView?.visibility = View.GONE
            placeholderTextView?.visibility = View.GONE
            dateContainerView?.visibility = View.GONE
        } else if (showWeather) {
            val weatherText = "$temperature° $weatherCondition"
            weatherIconView?.setImageDrawable(weatherIcon)
            weatherTextView?.text = weatherText
            calendarContainerView?.visibility = View.GONE
            weatherContainerView?.visibility = View.VISIBLE
            placeholderTextView?.visibility = View.GONE
            dateContainerView?.visibility = View.VISIBLE
        } else {
            calendarContainerView?.visibility = View.GONE
            weatherContainerView?.visibility = View.GONE
            placeholderTextView?.visibility = View.VISIBLE
            dateContainerView?.visibility = View.VISIBLE
        }

        calendarTitleTextView?.includeFontPadding = false
        calendarInfoTextView?.includeFontPadding = true
        weatherTextView?.includeFontPadding = true
        placeholderTextView?.includeFontPadding = true
        dateTextView?.includeFontPadding = true
        alarmInfoTextView?.includeFontPadding = true

        clockTextView?.typeface = Typeface.create("nothingdot57", Typeface.NORMAL)

        val infoTextViews = listOf(
            calendarTitleTextView,
            calendarInfoTextView,
            weatherTextView,
            placeholderTextView,
            dateTextView,
            alarmInfoTextView
        )

        infoTextViews.forEach { it?.typeface = textTypeface }

        refreshColor()
        refreshUI()
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

    override fun onAlarmDataChanged(data: AlarmData) {
        super.onAlarmDataChanged(data)
        val withinHours = withinNHoursLocked(data, alarmVisibilityHours)
        val nextAlarmMillis = data.nextAlarmMillis ?: 0L
        nextAlarm = if (withinHours) {
            val format = if (android.text.format.DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm"
            android.text.format.DateFormat.format(format, nextAlarmMillis).toString()
        } else ""

        val visible = nextAlarm.isNotBlank() == true
        alarmIconView?.visibility = if (visible) View.VISIBLE else View.GONE
        alarmInfoTextView?.apply {
            visibility = if (visible) View.VISIBLE else View.GONE
            text = nextAlarm
        }

        refreshInfo(calendarData, weatherData)
    }

    override fun onNTWeatherDataChanged(data: NTWeatherData) {
        weatherData = data
        refreshInfo(calendarData, weatherData)
    }

    override fun onCalendarDataChanged(data: CalendarSimpleData) {
        calendarData = data
        refreshInfo(calendarData, weatherData)
    }

    override fun refreshFormat(use24HourFormat: Boolean, newLocale: Locale) {
        super.refreshFormat(use24HourFormat, newLocale)
        updateDateInfo()
    }

    override fun onDateChanged() {
        super.onDateChanged()
        updateDateInfo()
    }

    override fun onTimeZoneChanged(timeZone: TimeZone) {
        super.onTimeZoneChanged(timeZone)
        updateDateInfo()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        measureChildren(widthMeasureSpec, heightMeasureSpec)
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun onThemeChanged(isDark: Boolean) {
        if (this.isDarkTheme == isDark) return
        this.isDarkTheme = isDark
        refreshColor()
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val childAt = getChildAt(0)
        if (childAt != null) {
            val w = (width - childAt.measuredWidth) / 2
            val h = (height - childAt.measuredHeight) / 2
            childAt.layout(w, h, childAt.measuredWidth + w, childAt.measuredHeight + h)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        refreshUI()
    }
}
