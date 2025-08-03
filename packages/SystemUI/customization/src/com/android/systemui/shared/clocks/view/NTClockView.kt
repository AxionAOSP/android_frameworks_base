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
import android.content.res.Configuration
import android.graphics.*
import android.text.format.DateFormat
import android.util.AttributeSet
import android.util.Log
import android.view.ViewGroup
import androidx.constraintlayout.core.motion.utils.TypedValues
import com.android.systemui.customization.R
import com.android.systemui.log.core.MessageBuffer
import com.android.systemui.plugins.clocks.*
import com.android.systemui.shared.clocks.ClockConfigs
import kotlinx.coroutines.*
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.concurrent.TimeUnit
import java.util.*

abstract class NTClockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    defStyleRes: Int = 0
) : ViewGroup(context, attrs, defStyleAttr, defStyleRes) {

    var dateStr: String = ""
        private set

    val antiAliasFilter = PaintFlagsDrawFilter(0, 3)
    val calendar: Calendar = Calendar.getInstance()
    val alarmVisibilityHours = 12

    private var uiScope: CoroutineScope? = null

    var format: String? = null
    var timeStr: String = ""
        internal set
    var locale: Locale = Locale.getDefault()

    var isDoze: Boolean = false
    var isScreenOff: Boolean = false
    var isRegionDark: Boolean = false
    var isPlaying: Boolean = false
    var trackTitle: String = ""
    var artistName: String = ""
    var nowPlayingText: String = ""
    var nextAlarm: String = ""
    
    var nowPlayingAvailable = false
        get() = nowPlayingText.isNotBlank()

    var isNowPlaying = false
        get() = isPlaying && isDoze
        
    var clockColor = Color.WHITE
        get() = if (isDoze || isScreenOff || isRegionDark) Color.WHITE else Color.BLACK

    val clockHeight: Int
        get() {
            val className = this::class.simpleName ?: return resources.getDimension(R.dimen.clock_height).toInt()
            val config = ClockConfigs.clockConfigMap[className]
            return config?.customHeightRes?.let { resources.getDimension(it).toInt() }
                ?: (resources.getDimension(R.dimen.clock_height) + dateHeight).toInt()
        }

    val dateHeight: Int
        get() {
            val className = this::class.simpleName ?: return 0
            val config = ClockConfigs.clockConfigMap[className] ?: return 0
            if (!config.visible) return 0

            val textSize = resources.getDimension(R.dimen.clock_date_text_size)
            val marginTop = config.customDateMarginTop?.let {
                resources.getDimension(it)
            } ?: resources.getDimension(R.dimen.clock_date_margin_top)
            val paddingTop = resources.getDimension(R.dimen.clock_padding_top)

            return when (config.position) {
                ClockConfigs.Position.ABOVE -> ((textSize + marginTop + paddingTop) * scaleRatio).toInt()
                ClockConfigs.Position.BELOW -> (textSize * scaleRatio).toInt()
            }
        }

    val talkBackContent: String
        get() {
            val pattern =
                if (DateFormat.is24HourFormat(context)) CLOCK_PATTERN_24_STANDARD else "hh:mm"
            return SimpleDateFormat(pattern, Locale.ENGLISH).format(calendar.time)
        }

    val scaleRatio: Float
        get() {
            val densityDpi = resources.displayMetrics.densityDpi
            val ratio = 420f / densityDpi
            if (DEBUG) Log.d("ScaleRatio", "densityDpi = $densityDpi, ratio = $ratio")
            return ratio
        }

    private var dateTextX: Float = 0f
    private var dateTextY: Float = 0f
    private var dateVisible: Boolean = false

    private val datePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = resources.getDimension(R.dimen.clock_date_text_size) * scaleRatio
        val resId = resources.getIdentifier("config_bodyFontFamily", "string", "android")
        val fontFamilyName =
            if (resId != 0) resources.getString(resId) else "sans-serif"
        val bodyTypeface = Typeface.create(fontFamilyName, Typeface.NORMAL)
        typeface = Typeface.create(bodyTypeface, 500, false)
    }

    init {
        setWillNotDraw(false)
        initDatePaintAndPosition()
    }

    abstract fun drawClock(canvas: Canvas)
    abstract override fun getTag(): String

    open fun onAlarmDataChanged(data: AlarmData) {}
    open fun onCalendarDataChanged(data: CalendarSimpleData) {}
    open fun onDateChanged() {}
    open fun onNTWeatherDataChanged(data: NTWeatherData) {}

    open fun onThemeChanged(isDarkTheme: Boolean) {
        refreshColor()
        invalidate()
    }

    open fun onPlaybackStateChanged(playing: Boolean) {
        if (isPlaying != playing) {
            isPlaying = playing
        }
    }

    open fun onMetadataChanged(track: String, artist: String) {
        trackTitle = track
        artistName = artist
    }
    
    open fun onNowPlayingUpdate(npText: String) {
        nowPlayingText = npText
    }

    fun setMessageBuffer(buffer: MessageBuffer) {}

    open fun onDozeChanged(doze: Boolean) {
        if (isDoze != doze) {
            isDoze = doze
            refreshColor()
        }
    }

    fun onStartedWakingUp() {
        isDoze = false
        isScreenOff = false
        refreshColor()
        uiScope?.launch {
            delay(1250)
            refreshTime()
            postInvalidateOnAnimation()
        }
    }

    fun onScreenOff(screenOff: Boolean) {
        if (isScreenOff != screenOff) {
            isScreenOff = screenOff
            refreshColor()
        }
    }

    fun onRegionDarknessChanged(regionDark: Boolean) {
        if (isRegionDark != regionDark) {
            isRegionDark = regionDark
            refreshColor()
        }
    }

    open fun onTimeZoneChanged(timeZone: TimeZone) {
        calendar.timeZone = timeZone
    }

    open fun refreshFormat(use24: Boolean, newLocale: Locale = locale) {
        val newFormat = when (this) {
            is OldQuickLookClockView -> if (use24) CLOCK_PATTERN_24_STANDARD else CLOCK_PATTERN_12_STANDARD
            is GraphicClockView -> CLOCK_PATTERN_ALL
            else -> if (use24) CLOCK_PATTERN_24 else CLOCK_PATTERN_12
        }

        if (format == newFormat && locale == newLocale) return

        format = newFormat
        locale = newLocale
        refreshTime()
    }

    open fun refreshTime() {
        format ?: return
        calendar.timeInMillis = System.currentTimeMillis()
        val newTime = SimpleDateFormat(format, Locale.ENGLISH).format(calendar.time)
        refreshDate()
        if (timeStr != newTime) {
            timeStr = newTime
            contentDescription = talkBackContent
            invalidate()
        }
    }

    fun refreshDate() {
        val dateFormat = SimpleDateFormat("EEE, dd MMM", locale)
        dateStr = dateFormat.format(calendar.time)
    }

    fun dump(pw: PrintWriter) {
        pw.println("view=$tag")
        pw.println("    measuredWidth=$measuredWidth")
        pw.println("    measuredHeight=$measuredHeight")
        pw.println("    time=${calendar.timeInMillis}")
        pw.println("    formattedTime=$timeStr")
        pw.println("    locale=$locale")
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawFilter = antiAliasFilter
        if (dateVisible && dateStr.isNotEmpty()) {
            canvas.drawText(dateStr, dateTextX, dateTextY, datePaint)
        }
        Log.d(tag, " onDraw: $isRegionDark")
        drawClock(canvas)
    }

    private fun initDatePaintAndPosition() {
        val className = this::class.simpleName ?: return
        val config = ClockConfigs.clockConfigMap[className] ?: return
        dateVisible = config.visible
        datePaint.textAlign = when (config.align) {
            ClockConfigs.Align.LEFT -> Paint.Align.LEFT
            ClockConfigs.Align.CENTER -> Paint.Align.CENTER
        }
        dateTextX = when (config.align) {
            ClockConfigs.Align.LEFT -> resources.getDimension(R.dimen.clock_padding_start)
            ClockConfigs.Align.CENTER -> width / 2f
        }
        val topMargin = config.customDateMarginTop?.let {
            resources.getDimension(it)
        } ?: resources.getDimension(R.dimen.clock_date_margin_top)
        dateTextY = when (config.position) {
            ClockConfigs.Position.ABOVE -> (topMargin * scaleRatio) + datePaint.textSize
            ClockConfigs.Position.BELOW -> height -
                (resources.getDimension(R.dimen.clock_date_margin_top) * scaleRatio) -
                datePaint.textSize
        }
    }

    fun withinNHoursLocked(data: AlarmData, hours: Int): Boolean {
        val nextAlarmMillis = data.nextAlarmMillis ?: return false
        val jCurrentTimeMillis = System.currentTimeMillis() + TimeUnit.HOURS.toMillis(hours.toLong())
        return nextAlarmMillis.toLong() <= jCurrentTimeMillis
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = ViewGroup.getDefaultSize(suggestedMinimumWidth, widthMeasureSpec)
        setMeasuredDimension(width, clockHeight)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        initDatePaintAndPosition()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Log.d(tag, " onAttachedToWindow:")
        uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        Log.d(tag, " onDetachedFromWindow")
        uiScope?.cancel()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val newLocale = newConfig.locale
        if (newLocale != locale) {
            locale = newLocale
            uiScope?.launch {
                refreshFormat(DateFormat.is24HourFormat(context), newLocale)
            }
        }
        initDatePaintAndPosition()
    }

    protected open fun refreshColor() {
        datePaint.color = clockColor
        invalidate()
    }

    companion object {
        const val DEBUG = false
        private const val CLOCK_PATTERN_12 = "hmm"
        private const val CLOCK_PATTERN_12_STANDARD = "h:mm"
        private const val CLOCK_PATTERN_24 = "HHmm"
        private const val CLOCK_PATTERN_24_STANDARD = "HH:mm"
        private const val CLOCK_PATTERN_ALL = "hmmss"
    }
}
