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

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.*
import android.icu.util.TimeZone
import android.os.Vibrator
import android.text.format.DateFormat
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.android.app.animation.Interpolators
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
import android.os.VibrationEffect
import android.view.animation.PathInterpolator
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

    protected open val isLargeClock = false

    var animScale: Float = 1f
        set(value) {
            field = value
            invalidate()
        }

    var animAlpha: Float = 1f
        set(value) {
            field = value
            alpha = value
        }

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

    val isSplitShade: Boolean
        get() {
             val resId = resources.getIdentifier("config_use_split_notification_shade", "bool", "com.android.systemui")
             return if (resId != 0) resources.getBoolean(resId) else false
        }

    val dateTextX: Float
        get() = when (config?.align) {
            ClockConfigs.Align.LEFT -> clockPaddingStart
            else -> width / 2f
        }

    open val dateTextY: Float
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

    protected enum class DisplayMode {
        CALENDAR,
        NOW_PLAYING,
        WEATHER,
        DATE_ONLY
    }

    protected var displayMode: DisplayMode = DisplayMode.DATE_ONLY
    protected var displayText: String = ""
    protected var displayIcon: Bitmap? = null
    protected var displaySecondaryText: String = ""
    protected var displaySecondaryIcon: Bitmap? = null

    init {
        setWillNotDraw(false)
        clipChildren = false
        clipToPadding = false
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

    private var lastDozeEased: Float = 0f

    open fun onDozeAmountChanged(linear: Float, eased: Float) {
        if (eased == lastDozeEased) return
        lastDozeEased = eased
        if (isAnimating) return
        animScale = 1f + (eased * (DOZE_SCALE - 1f))
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

    open fun onRegionDarknessChanged(regionDark: Boolean) {
        if (isRegionDark != regionDark) {
            isRegionDark = regionDark
            refreshColor()
        }
    }
    
    open fun onFontSettingChanged() {
        invalidate()
    }

    open fun onTimeZoneChanged(timeZone: TimeZone) {
        calendar.timeZone = java.util.TimeZone.getTimeZone(timeZone.id)
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

    protected open fun updateDisplayContent() {
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
            val fullText = if (artistName.isNotEmpty()) {
                "$trackTitle - $artistName"
            } else {
                trackTitle
            }
            displayText = if (fullText.length > MAX_NOW_PLAYING_LENGTH) {
                fullText.take(MAX_NOW_PLAYING_LENGTH - 1) + "…"
            } else {
                fullText
            }
            displayIcon = loadNowPlayingIcon()
            displaySecondaryText = ""
            displaySecondaryIcon = null
            invalidate()
            return
        }

        if (nowPlayingAvailable) {
            displayMode = DisplayMode.NOW_PLAYING
            displayText = if (nowPlayingText.length > MAX_NOW_PLAYING_LENGTH) {
                nowPlayingText.take(MAX_NOW_PLAYING_LENGTH - 1) + "…"
            } else {
                nowPlayingText
            }
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

    override fun draw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        canvas.save()
        canvas.scale(animScale, animScale, cx, cy)
        super.draw(canvas)
        canvas.restore()
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

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        pivotX = w / 2f
        pivotY = h / 2f
    }

    protected open fun getContentBounds(): RectF? = null

    private fun isTouchOnContent(x: Float, y: Float): Boolean {
        val bounds = getContentBounds()
        if (bounds != null) {
            return bounds.contains(x, y)
        }
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.VISIBLE) {
                if (x >= child.left && x <= child.right && y >= child.top && y <= child.bottom) {
                    return true
                }
            }
        }
        return false
    }

    private var touchDownTime = 0L
    
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                if (!isTouchOnContent(event.x, event.y)) {
                    return false
                }
                touchDownTime = System.currentTimeMillis()
                return true
            }
            MotionEvent.ACTION_UP -> {
                val elapsed = System.currentTimeMillis() - touchDownTime
                if (elapsed < 300 && !isDoze) {
                    animateFidgetTap(event.x, event.y)
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Log.d(tag, "onAttachedToWindow")
        uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        (parent as? ViewGroup)?.let {
            it.clipChildren = false
            it.clipToPadding = false
        }
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
    private var currentAnimator: AnimatorSet? = null
    private var isAnimating = false

    fun animateAppear() {
        Log.d(tag, "animateAppear")
        currentAnimator?.cancel()
        animScale = APPEAR_START_SCALE
        animAlpha = 0f

        val scaleAnim = ValueAnimator.ofFloat(APPEAR_START_SCALE, 1f).apply {
            duration = APPEAR_DURATION
            interpolator = OvershootInterpolator(BOUNCE_TENSION)
            addUpdateListener { animScale = it.animatedValue as Float }
        }
        val alphaAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = APPEAR_DURATION
            interpolator = Interpolators.EMPHASIZED_DECELERATE
            addUpdateListener { animAlpha = it.animatedValue as Float }
        }

        currentAnimator = AnimatorSet().apply {
            playTogether(scaleAnim, alphaAnim)
            start()
        }
    }

    fun animateCharge() {
        Log.d(tag, "animateCharge")
        currentAnimator?.cancel()
        isAnimating = true

        val bounceDown = ValueAnimator.ofFloat(1f, CHARGE_SHRINK_SCALE).apply {
            duration = CHARGE_DURATION / 3
            interpolator = FIDGET_INTERPOLATOR
            addUpdateListener { animScale = it.animatedValue as Float }
        }
        val bounceUp = ValueAnimator.ofFloat(CHARGE_SHRINK_SCALE, CHARGE_EXPAND_SCALE).apply {
            duration = CHARGE_DURATION / 3
            interpolator = FIDGET_INTERPOLATOR
            addUpdateListener { animScale = it.animatedValue as Float }
        }
        val settle = ValueAnimator.ofFloat(CHARGE_EXPAND_SCALE, 1f).apply {
            duration = CHARGE_DURATION / 3
            interpolator = FIDGET_INTERPOLATOR
            addUpdateListener { animScale = it.animatedValue as Float }
        }

        currentAnimator = AnimatorSet().apply {
            playSequentially(bounceDown, bounceUp, settle)
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    isAnimating = false
                }
                override fun onAnimationCancel(animation: Animator) {
                    isAnimating = false
                }
            })
            start()
        }
    }

    open fun animateFidgetTap(x: Float, y: Float) {
        Log.d(tag, "animateFidgetTap x=$x y=$y animScale=$animScale")
        currentAnimator?.cancel()
        isAnimating = true

        (context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)?.vibrate(FIDGET_HAPTICS)

        val pulse = ValueAnimator.ofFloat(1f, FIDGET_SCALE, 1f).apply {
            duration = FIDGET_DURATION
            interpolator = FIDGET_INTERPOLATOR
            addUpdateListener { animScale = it.animatedValue as Float }
        }

        currentAnimator = AnimatorSet().apply {
            play(pulse)
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    isAnimating = false
                }
                override fun onAnimationCancel(animation: Animator) {
                    isAnimating = false
                }
            })
            start()
        }
    }

    companion object {
        const val DEBUG = false
        private const val CLOCK_PATTERN_12 = "hhmm"
        private const val CLOCK_PATTERN_12_STANDARD = "hh:mm"
        private const val CLOCK_PATTERN_24 = "HHmm"
        private const val CLOCK_PATTERN_24_STANDARD = "HH:mm"
        private const val CLOCK_PATTERN_ALL = "hhmmss"

        private const val APPEAR_DURATION = 400L
        private const val APPEAR_START_SCALE = 0.85f
        private const val BOUNCE_TENSION = 1.5f

        private const val DOZE_DURATION = 500L
        private const val DOZE_SCALE = 1.1f
        private const val DOZE_ALPHA = 0.9f

        private const val CHARGE_DURATION = 400L
        private const val CHARGE_SHRINK_SCALE = 0.94f
        private const val CHARGE_EXPAND_SCALE = 1.08f

        private const val FOLD_DURATION = 400L

        private const val FIDGET_DURATION = 200L
        private const val FIDGET_SCALE = 1.04f
        private val FIDGET_INTERPOLATOR = PathInterpolator(0.26873f, 0f, 0.45042f, 1f)
        private val FIDGET_HAPTICS = VibrationEffect.startComposition()
            .addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, 1.0f, 0)
            .addPrimitive(VibrationEffect.Composition.PRIMITIVE_QUICK_RISE, 1.0f, 43)
            .compose()

        private const val MAX_NOW_PLAYING_LENGTH = 24
    }
}
