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
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.util.AttributeSet
import android.util.Log
import android.view.ViewGroup
import androidx.constraintlayout.core.motion.utils.TypedValues
import com.android.systemui.customization.R
import com.android.systemui.log.core.*
import com.android.systemui.plugins.clocks.*
import java.io.PrintWriter
import java.text.SimpleDateFormat
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
    val clockHandler = Handler(Looper.getMainLooper())

    var format: String? = null

    var timeStr: String = ""
        internal set

    var locale: Locale = Locale.getDefault()

    var isDoze: Boolean = false
    var isScreenOff: Boolean = false
    var isRegionDark: Boolean = false

    val scaleRatio: Float = (TypedValues.CycleType.TYPE_EASING / resources.displayMetrics.densityDpi).toFloat()

    init {
        setWillNotDraw(false)
    }

    abstract fun drawClock(canvas: Canvas)
    abstract override fun getTag(): String

    open fun onAlarmDataChanged(data: AlarmData) {}
    open fun onCalendarDataChanged(data: CalendarSimpleData) {}
    open fun onDateChanged() {}
    open fun onNTWeatherDataChanged(data: NTWeatherData) {}
    open fun onThemeChanged(isDarkTheme: Boolean) {
        invalidate()
    }
    
    open fun getClockColor(): Int {
        return if (isDoze || isScreenOff || isRegionDark) Color.WHITE else Color.BLACK
    }

    fun setMessageBuffer(buffer: MessageBuffer) {
    }

    fun onDozeChanged(doze: Boolean) {
        if (isDoze != doze) {
            isDoze = doze
            refreshColor()
        }
    }

    fun onStartedWakingUp() {
        isDoze = false
        isScreenOff = false
        refreshColor()
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
            contentDescription = getTalkBackContent()
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
    
    fun getClockHeight(): Int {
        val className = this::class.simpleName ?: return resources.getDimension(R.dimen.clock_height).toInt()
        val config = clockConfigMap[className]

        return config?.customHeightRes?.let { resources.getDimension(it).toInt() }
            ?: (resources.getDimension(R.dimen.clock_height) + getDateHeight()).toInt()
    }
    
    fun getDateHeight(): Int {
        val className = this::class.simpleName ?: return 0
        val config = clockConfigMap[className] ?: return 0

        if (!config.visible) return 0

        val textSize = resources.getDimension(R.dimen.clock_date_text_size)

        return when (config.position) {
            Position.ABOVE -> {
                val marginTop = resources.getDimension(R.dimen.clock_date_margin_top)
                val paddingTop = resources.getDimension(R.dimen.clock_padding_top)
                ((textSize + marginTop + paddingTop) * scaleRatio).toInt()
            }
            Position.BELOW -> {
                (textSize * scaleRatio).toInt()
            }
        }
    }

    fun getTalkBackContent(): String {
        val pattern = if (DateFormat.is24HourFormat(context)) CLOCK_PATTERN_24_STANDARD else "hh:mm"
        return SimpleDateFormat(pattern, Locale.ENGLISH).format(calendar.time)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawFilter = antiAliasFilter
        drawDateText(canvas)
        Log.d(tag, " onDraw: $isRegionDark")
        drawClock(canvas)
    }

    private val datePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = resources.getDimension(R.dimen.clock_date_text_size) * scaleRatio
        color = Color.BLACK
        textAlign = Paint.Align.CENTER
    }

    private fun drawDateText(canvas: Canvas) {
        val className = this::class.simpleName ?: return
        val config = clockConfigMap[className] ?: return
        if (!config.visible || dateStr.isEmpty()) return
        val x = when (config.align) {
            Align.LEFT -> resources.getDimension(R.dimen.clock_padding_start)
            Align.CENTER -> width / 2f
        }
        val y = when (config.position) {
            Position.ABOVE -> {
                resources.getDimension(R.dimen.clock_date_margin_top) * scaleRatio +
                datePaint.textSize
            }
            Position.BELOW -> {
                height - resources.getDimension(R.dimen.clock_date_margin_top) * scaleRatio -
                datePaint.textSize
            }
        }
        datePaint.color = getClockColor()
        datePaint.textAlign = when (config.align) {
            Align.LEFT -> Paint.Align.LEFT
            Align.CENTER -> Paint.Align.CENTER
        }
        canvas.drawText(dateStr, x, y, datePaint)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = ViewGroup.getDefaultSize(suggestedMinimumWidth, widthMeasureSpec)
        val height = getClockHeight()
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Log.d(tag, " onAttachedToWindow:")
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        Log.d(tag, " onDetachedFromWindow")
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val newLocale = newConfig.locale

        if (newLocale != locale) {
            locale = newLocale
            clockHandler.post {
                refreshFormat(DateFormat.is24HourFormat(context), newLocale)
            }
        }
    }

    override fun invalidate() {
        super.invalidate()
    }

    protected open fun refreshColor() {
        invalidate()
    }
    
    companion object {
        private const val CLOCK_PATTERN_12 = "hmm"
        private const val CLOCK_PATTERN_12_STANDARD = "h:mm"
        private const val CLOCK_PATTERN_24 = "HHmm"
        private const val CLOCK_PATTERN_24_STANDARD = "HH:mm"
        private const val CLOCK_PATTERN_ALL = "hmmss"
        
        data class ClockStyleConfig(
            val position: Position,
            val align: Align,
            val visible: Boolean = true,
            val customHeightRes: Int? = null
        )

        enum class Position { ABOVE, BELOW }
        enum class Align { LEFT, CENTER }

        private val clockConfigMap = mapOf(
            "GeneralClockView" to ClockStyleConfig(Position.BELOW, Align.LEFT),
            "GraphicClockView" to ClockStyleConfig(Position.ABOVE, Align.CENTER, visible = false),
            "LondonUGClockView" to ClockStyleConfig(Position.ABOVE, Align.CENTER, customHeightRes = R.dimen.center_clock_height),
            "NDotClockView" to ClockStyleConfig(Position.ABOVE, Align.CENTER, customHeightRes = R.dimen.center_clock_height),
            "NTypeClockView" to ClockStyleConfig(Position.ABOVE, Align.CENTER, customHeightRes = R.dimen.center_clock_height),
            "OldQuickLookClockView" to ClockStyleConfig(Position.ABOVE, Align.CENTER, visible = false, customHeightRes = R.dimen.old_clock_height)
        )
    }
}
