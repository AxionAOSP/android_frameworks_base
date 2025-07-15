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
package com.android.systemui.shared.clocks

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.icu.text.SimpleDateFormat
import android.provider.CalendarContract
import android.text.format.DateFormat
import android.widget.RemoteViews
import com.android.systemui.customization.R
import com.android.systemui.plugins.clocks.*
import java.util.Calendar
import java.util.Locale

object CalendarUtils {

    private const val ONE_DAY_MILLIS = 86_400_000L
    private const val REQUEST_CODE_OPEN_EVENT = 101

    private const val FORMAT_12_HOUR = "hh:mm"
    private const val FORMAT_24_HOUR = "HH:mm"
    private const val FORMAT_SAME_YEAR = "d/MMM "
    private const val FORMAT_DIFF_YEAR = "d/MMM/yyyy "

    fun setCalendarEventClick(
        context: Context,
        remoteViews: RemoteViews,
        viewId: Int,
        event: CalendarSimpleData
    ) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = CalendarContract.Events.CONTENT_URI
                .buildUpon()
                .appendPath(event.id.toString())
                .build()
            putExtra("beginTime", event.startTime)
            putExtra("endTime", event.endTime)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            REQUEST_CODE_OPEN_EVENT,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        remoteViews.setOnClickPendingIntent(viewId, pendingIntent)
    }

    fun formatTime(timestamp: Long, patternSkeleton: String): String {
        val pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), patternSkeleton)
        return SimpleDateFormat(pattern, Locale.getDefault()).format(timestamp)
    }

    private fun isSameYear(time1: Long, time2: Long): Boolean {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = time1
        val year1 = calendar.get(Calendar.YEAR)

        calendar.timeInMillis = time2
        val year2 = calendar.get(Calendar.YEAR)

        return year1 == year2
    }

    private fun isSameDay(time1: Long, time2: Long): Boolean {
        if (!isSameYear(time1, time2)) return false

        val calendar = Calendar.getInstance()
        calendar.timeInMillis = time1
        val dayOfYear1 = calendar.get(Calendar.DAY_OF_YEAR)

        calendar.timeInMillis = time2
        val dayOfYear2 = calendar.get(Calendar.DAY_OF_YEAR)

        return dayOfYear1 == dayOfYear2
    }

    private fun isNextDay(base: Long, target: Long): Boolean {
        return isSameDay(base + ONE_DAY_MILLIS, target)
    }

    private fun isPreviousDay(base: Long, target: Long): Boolean {
        return isSameDay(target + ONE_DAY_MILLIS, base)
    }

    private fun getFormattedTime(
        context: Context,
        eventTime: Long,
        referenceTime: Long
    ): String {
        val use24Hour = DateFormat.is24HourFormat(context)
        val timeFormat = if (use24Hour) FORMAT_24_HOUR else FORMAT_12_HOUR

        return when {
            isSameDay(referenceTime, eventTime) -> {
                formatTime(eventTime, timeFormat)
            }
            isNextDay(referenceTime, eventTime) -> {
                "${context.getString(R.string.quick_look_widget_calendar_tomorrow)} ${formatTime(eventTime, timeFormat)}"
            }
            isPreviousDay(referenceTime, eventTime) -> {
                "${context.getString(R.string.quick_look_widget_calendar_yesterday)} ${formatTime(eventTime, timeFormat)}"
            }
            isSameYear(referenceTime, eventTime) -> {
                formatTime(eventTime, FORMAT_SAME_YEAR + timeFormat)
            }
            else -> {
                formatTime(eventTime, FORMAT_DIFF_YEAR + timeFormat)
            }
        }
    }

    fun getCalendarWidgetTime(context: Context, event: CalendarSimpleData): String {
        val now = System.currentTimeMillis()

        var startTimeStr = getFormattedTime(context, event.startTime, now)
        var endTimeStr = getFormattedTime(context, event.endTime, now)

        if (isNextDay(now, event.startTime) && isNextDay(now, event.endTime)) {
            val tomorrowPrefix = "${context.getString(R.string.quick_look_widget_calendar_tomorrow)} "
            if (endTimeStr.startsWith(tomorrowPrefix)) {
                endTimeStr = endTimeStr.removePrefix(tomorrowPrefix)
            }
        }

        return "$startTimeStr - $endTimeStr"
    }

    fun getCalendarDescription(context: Context, event: CalendarSimpleData): String {
        return when (event.getEventStatus()) {
            1 -> context.getString(
                R.string.quick_look_widget_calendar_in_time,
                event.getToBeginTime()
            )
            2 -> context.getString(R.string.quick_look_widget_calendar_now)
            else -> ""
        }
    }
}
