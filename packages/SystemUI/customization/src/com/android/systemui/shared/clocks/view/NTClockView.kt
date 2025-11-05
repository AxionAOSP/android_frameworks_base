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
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.android.systemui.customization.R
import com.android.systemui.log.core.MessageBuffer
import com.android.systemui.plugins.clocks.*
import com.android.systemui.shared.clocks.ClockConfigs
import com.android.systemui.shared.clocks.extensions.*
import com.android.systemui.shared.clocks.CalendarUtils
import com.android.systemui.shared.clocks.WeatherUtils
import com.android.systemui.shared.clocks.NowPlayingIconBinder
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.math.absoluteValue

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
    var weatherData: NTWeatherData? = null
    var calendarData: CalendarSimpleData? = null

    val nowPlayingAvailable get() = nowPlayingText.isNotBlank()
    val isNowPlaying get() = isPlaying
    val clockColor get() = if (isDoze || isScreenOff || isRegionDark) Color.WHITE else Color.BLACK

    val clockHeightBase get() = context.scaledDimenInt(R.dimen.clock_height)
    val clockPaddingTop get() = context.scaledDimen(R.dimen.clock_padding_top)
    val clockPaddingStart get() = context.scaledDimen(R.dimen.clock_padding_start)
    val clockDateTextSize get() = context.scaledDimen(R.dimen.clock_date_text_size)
    val clockDateMarginTop get() = context.scaledDimen(R.dimen.clock_date_margin_top)
    val scaleRatio get() = context.scaleRatio
    val iconSize get() = context.scaledDimenInt(R.dimen.clock_icon_secondary_size)
    val elementSpacing get() = context.scaledDimen(R.dimen.clock_date_element_spacing)

    protected val config: ClockConfigs.ClockStyleConfig?
        get() {
            val className = this::class.simpleName ?: return null
            return ClockConfigs.clockConfigMap[className]
        }

    val clockHeight: Int
        get() {
            val resHeight = config?.customHeightRes?.let { context.scaledDimenInt(it) } ?: clockHeightBase
            return resHeight + dateHeight
        }

    val dateMarginTop: Int
        get() {
            val cfg = config ?: return 0
            if (!cfg.visible) return 0
            val marginTop = cfg.customDateMarginTop?.let { context.scaledDimen(it) } ?: clockDateMarginTop
            return marginTop.toInt()
        }

    val dateHeight: Int
        get() {
            val cfg = config ?: return 0
            if (!cfg.visible) return 0
            val textSize = clockDateTextSize
            return when (cfg.position) {
                ClockConfigs.Position.ABOVE -> (textSize + dateMarginTop + clockPaddingTop).toInt()
                ClockConfigs.Position.BELOW -> textSize.toInt()
                else -> 0
            }
        }

    val talkBackContent: String
        get() {
            val pattern = if (DateFormat.is24HourFormat(context)) CLOCK_PATTERN_24_STANDARD else "hh:mm"
            return SimpleDateFormat(pattern, Locale.ENGLISH).format(calendar.time)
        }

    val dateTextX: Float
        get() = when (config?.align) {
            ClockConfigs.Align.LEFT -> clockPaddingStart
            else -> width / 2f
        }

    val dateTextY: Float
        get() = when (config?.position) {
            ClockConfigs.Position.ABOVE -> dateMarginTop + datePaint.textSize
            else -> height - dateMarginTop - datePaint.textSize
        }

    var dateVisible: Boolean = false

    val datePaint: Paint
        get() {
            return Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = clockDateTextSize
                color = clockColor
                val resId = resources.getIdentifier("config_bodyFontFamily", "string", "android")
                val fontFamilyName = if (resId != 0) resources.getString(resId) else "sans-serif"
                val bodyTypeface = Typeface.create(fontFamilyName, Typeface.NORMAL)
                typeface = Typeface.create(bodyTypeface, 500, false)
                textAlign = when (config?.align) {
                    ClockConfigs.Align.CENTER -> Paint.Align.CENTER
                    else -> Paint.Align.LEFT
                }
            }
        }

    private val iconPaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG)

    private enum class DisplayMode {
        CALENDAR,
        NOW_PLAYING,
        WEATHER,
        DATE_ONLY
    }

    private var displayMode: DisplayMode = DisplayMode.DATE_ONLY
    private var displayText: String = ""
    private var displayIcon: Bitmap? = null
    private var displaySecondaryText: String = ""
    private var displaySecondaryIcon: Bitmap? = null

    init {
        setWillNotDraw(false)
        initDatePaintAndPosition()
    }

    abstract fun drawClock(canvas: Canvas)
    abstract override fun getTag(): String

    open fun onAlarmDataChanged(data: AlarmData) {}

    open fun onCalendarDataChanged(data: CalendarSimpleData) {
        calendarData = data
        updateDisplayContent()
    }

    open fun onDateChanged() {
        invalidate()
    }

    open fun onNTWeatherDataChanged(data: NTWeatherData) {
        weatherData = data
        updateDisplayContent()
    }

    open fun onThemeChanged(isDarkTheme: Boolean) {
        refreshColor()
        invalidate()
    }

    open fun onPlaybackStateChanged(playing: Boolean) {
        if (isPlaying != playing) {
            isPlaying = playing
            updateDisplayContent()
        }
    }

    open fun onMetadataChanged(track: String, artist: String) {
        trackTitle = track
        artistName = artist
        updateDisplayContent()
    }

    open fun onNowPlayingUpdate(npText: String) {
        nowPlayingText = npText
        updateDisplayContent()
    }

    fun setMessageBuffer(buffer: MessageBuffer) {}

    open fun onDozeChanged(doze: Boolean) {
        if (isDoze != doze) {
            isDoze = doze
            refreshColor()
        }
    }
    
    open fun onPulsingChanged(doze: Boolean) {
        refreshColor()
    }

    fun onDozeAmountChanged(linear: Float, eased: Float) {
        invalidate()
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
    
    open fun onFontSettingChanged() {
        invalidate()
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
            updateDisplayContent()
            invalidate()
        }
    }

    fun refreshDate() {
        val dateFormat = SimpleDateFormat("EEE, dd MMM", locale)
        dateStr = dateFormat.format(calendar.time)
    }

    private fun updateDisplayContent() {
        val isCenterAligned = config?.align == ClockConfigs.Align.CENTER
        
        if (!isCenterAligned) {
            displayMode = DisplayMode.DATE_ONLY
            displayText = dateStr
            displayIcon = null
            displaySecondaryText = ""
            displaySecondaryIcon = null
            invalidate()
            return
        }

        val isEventVisible = calendarData?.isEventVisible() == true
        val calendarTitle = calendarData?.title ?: ""
        val hasCalendarData = calendarData != null && 
                              calendarData != CalendarSimpleData.EMPTY &&
                              isEventVisible && 
                              calendarTitle.isNotEmpty()

        if (hasCalendarData) {
            displayMode = DisplayMode.CALENDAR
            displayText = CalendarUtils.getCalendarDescription(context, calendarData!!)
            displayIcon = loadCalendarIcon()
            displaySecondaryText = ""
            displaySecondaryIcon = null
            invalidate()
            return
        }

        if (isNowPlaying && trackTitle.isNotEmpty()) {
            displayMode = DisplayMode.NOW_PLAYING
            displayText = if (artistName.isNotEmpty()) {
                "$trackTitle - $artistName"
            } else {
                trackTitle
            }
            displayIcon = loadNowPlayingIcon()
            displaySecondaryText = ""
            displaySecondaryIcon = null
            invalidate()
            return
        }

        if (nowPlayingAvailable) {
            displayMode = DisplayMode.NOW_PLAYING
            displayText = nowPlayingText
            displayIcon = loadNowPlayingIcon()
            displaySecondaryText = ""
            displaySecondaryIcon = null
            invalidate()
            return
        }

        val hasValidWeather = weatherData != null && 
                              weatherData != NTWeatherData.EMPTY &&
                              (weatherData?.temp?.isNotEmpty() == true) &&
                              (weatherData?.conditionCode ?: 0) != 0
        
        if (hasValidWeather) {
            displayMode = DisplayMode.WEATHER
            displayText = dateStr
            displayIcon = loadWeatherIcon()
            displaySecondaryText = "${weatherData?.temp}°"
            displaySecondaryIcon = null
            invalidate()
            return
        }
        
        displayMode = DisplayMode.DATE_ONLY
        displayText = dateStr
        displayIcon = null
        displaySecondaryText = ""
        displaySecondaryIcon = null
        invalidate()
    }

    private fun loadCalendarIcon(): Bitmap? {
        return try {
            ContextCompat.getDrawable(context, R.drawable.old_quick_look_alarm_icon)
                ?.toBitmap(iconSize, iconSize, Bitmap.Config.ARGB_8888)
        } catch (e: Exception) {
            Log.e(tag, "Error loading calendar icon", e)
            null
        }
    }

    private fun loadNowPlayingIcon(): Bitmap? {
        return try {
            ContextCompat.getDrawable(context, R.drawable.ic_music_note)
                ?.toBitmap(iconSize, iconSize, Bitmap.Config.ARGB_8888)
        } catch (e: Exception) {
            Log.e(tag, "Error loading now playing icon", e)
            null
        }
    }

    private fun loadWeatherIcon(): Bitmap? {
        val conditionCode = weatherData?.conditionCode ?: 0
        if (conditionCode == 0) return null

        return try {
            WeatherUtils.getWeatherIcon(context, conditionCode)
                ?.toBitmap(iconSize, iconSize, Bitmap.Config.ARGB_8888)
        } catch (e: Exception) {
            Log.e(tag, "Error loading weather icon", e)
            null
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawFilter = antiAliasFilter

        if (dateVisible && displayText.isNotEmpty()) {
            drawEnhancedDateArea(canvas)
        }
        drawClock(canvas)
    }

    private fun drawEnhancedDateArea(canvas: Canvas) {
        val baseY = dateTextY
        val centerX = width / 2f

        when (displayMode) {
            DisplayMode.DATE_ONLY -> {
                canvas.drawText(displayText, dateTextX, baseY, datePaint)
            }

            DisplayMode.CALENDAR, DisplayMode.NOW_PLAYING -> {
                val icon = displayIcon
                if (icon != null) {
                    val textWidth = datePaint.measureText(displayText)
                    val totalWidth = icon.width + elementSpacing + textWidth
                    
                    val startX = centerX - (totalWidth / 2f)
                    val iconY = baseY - datePaint.textSize + (datePaint.textSize - icon.height) / 2f
                    
                    iconPaint.colorFilter = PorterDuffColorFilter(clockColor, PorterDuff.Mode.SRC_IN)
                    canvas.drawBitmap(icon, startX, iconY, iconPaint)
                    
                    val textPaint = Paint(datePaint).apply { textAlign = Paint.Align.LEFT }
                    canvas.drawText(displayText, startX + icon.width + elementSpacing, baseY, textPaint)
                } else {
                    canvas.drawText(displayText, centerX, baseY, datePaint)
                }
            }

            DisplayMode.WEATHER -> {
                val icon = displayIcon
                if (icon != null && displaySecondaryText.isNotEmpty()) {
                    val dateWidth = datePaint.measureText(dateStr)
                    val tempWidth = datePaint.measureText(displaySecondaryText)
                    val totalWidth = dateWidth + elementSpacing + icon.width + elementSpacing + tempWidth
                    
                    val startX = centerX - (totalWidth / 2f)
                    var currentX = startX
                    
                    val textPaint = Paint(datePaint).apply { textAlign = Paint.Align.LEFT }
                    canvas.drawText(dateStr, currentX, baseY, textPaint)
                    currentX += dateWidth + elementSpacing
                    
                    val iconY = baseY - datePaint.textSize + (datePaint.textSize - icon.height) / 2f
                    iconPaint.colorFilter = PorterDuffColorFilter(clockColor, PorterDuff.Mode.SRC_IN)
                    canvas.drawBitmap(icon, currentX, iconY, iconPaint)
                    currentX += icon.width + elementSpacing
                    
                    canvas.drawText(displaySecondaryText, currentX, baseY, textPaint)
                } else {
                    canvas.drawText(displayText, centerX, baseY, datePaint)
                }
            }
        }
    }

    private fun initDatePaintAndPosition() {
        val cfg = config
        dateVisible = cfg?.visible ?: false
        datePaint.textAlign = when (cfg?.align) {
            ClockConfigs.Align.LEFT -> Paint.Align.LEFT
            else -> Paint.Align.CENTER
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
        updateDisplayContent()
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
        updateDisplayContent()
    }

    protected open fun refreshColor() {
        datePaint.color = clockColor
        iconPaint.colorFilter = PorterDuffColorFilter(clockColor, PorterDuff.Mode.SRC_IN)
        updateDisplayContent()
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
