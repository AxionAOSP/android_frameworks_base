/*
 * Copyright (C) 2025-2026 AxionOS Project
 *
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

import android.app.PendingIntent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.android.axion.quicklook.client.R as QuickLookR
import com.android.systemui.customization.R
import com.android.systemui.plugins.keyguard.ui.clocks.CalendarSimpleData
import com.android.systemui.plugins.keyguard.ui.clocks.ClockData
import com.android.systemui.plugins.keyguard.ui.clocks.ClockWeatherData
import com.android.systemui.shared.clocks.ClockSettingsRepository
import com.android.systemui.shared.clocks.WeatherUtils

internal fun AxClockView.resolveDisplay(
    clockData: ClockData,
    media: ClockMediaState,
    nowPlaying: String,
    nowPlayingTapAction: PendingIntent?,
    alarm: String,
    date: String,
    displayMode: String,
    displaySources: Set<String>,
): DateDisplay {
    if (displayMode == ClockSettingsRepository.INFO_DISPLAY_OFF) {
        return DateDisplay.Hidden
    }

    fun mediaDisplay(): DateDisplay? {
        if (media.isPlaying && media.trackTitle.isNotEmpty()) {
            val fullText = if (media.artistName.isNotEmpty()) {
                "${media.trackTitle} - ${media.artistName}"
            } else {
                context.getString(R.string.clock_now_playing, media.trackTitle)
            }
            return DateDisplay.IconText(
                fullText,
                loadNowPlayingIcon(media.packageName),
                media.packageName.isEmpty(),
            )
        }
        if (nowPlaying.isNotBlank()) {
            return DateDisplay.IconText(
                nowPlaying,
                loadNowPlayingIcon(media.packageName),
                false,
                nowPlayingTapAction,
            )
        }
        return null
    }

    fun smartspaceDisplay(): DateDisplay? {
        val activeSmartspace = clockData.smartspace.firstOrNull {
            it.title.isNotEmpty() && !it.isSensitive
        } ?: return null
        val text = if (activeSmartspace.subtitle.isNotEmpty()) {
            "${activeSmartspace.title} · ${activeSmartspace.subtitle}"
        } else {
            activeSmartspace.title
        }
        return DateDisplay.IconText(
            text,
            decodeSmartspaceIcon(activeSmartspace.iconBytes),
            true,
            activeSmartspace.tapAction,
        )
    }

    fun alarmDisplay(): DateDisplay? {
        return if (alarm.isNotBlank()) {
            DateDisplay.IconText(alarm, loadDrawableIcon(QuickLookR.drawable.ic_alarm), true)
        } else {
            null
        }
    }

    fun calendarDisplay(): DateDisplay? {
        val cal = clockData.calendar.takeIf { it != CalendarSimpleData.EMPTY }
        return if (cal != null && cal.isEventVisible() && !cal.title.isNullOrEmpty()) {
            DateDisplay.IconText(
                cal.description.ifEmpty { cal.title.orEmpty() },
                loadDrawableIcon(QuickLookR.drawable.ic_calendar),
                true,
                cal.tapAction,
            )
        } else {
            null
        }
    }

    fun weatherDisplay(): DateDisplay? {
        val weather = clockData.weather.takeIf { it != ClockWeatherData.EMPTY }
        return if (weather != null && weather.temp.isNotEmpty()) {
            DateDisplay.Weather(
                date,
                weather.formattedTemp,
                WeatherUtils.resolveWeatherBitmap(context, weather, iconSize),
                weather.tintIcon,
                weather.tapAction,
            )
        } else {
            null
        }
    }

    fun sourceDisplay(source: String): DateDisplay? {
        return when (source) {
            ClockSettingsRepository.INFO_DISPLAY_MEDIA -> mediaDisplay()
            ClockSettingsRepository.INFO_DISPLAY_SMARTSPACE -> smartspaceDisplay()
            ClockSettingsRepository.INFO_DISPLAY_ALARM -> alarmDisplay()
            ClockSettingsRepository.INFO_DISPLAY_CALENDAR -> calendarDisplay()
            ClockSettingsRepository.INFO_DISPLAY_WEATHER -> weatherDisplay()
            else -> null
        }
    }

    fun autoDisplay(): DateDisplay {
        for (source in ClockSettingsRepository.INFO_DISPLAY_SOURCE_PRIORITY) {
            if (source in displaySources) {
                sourceDisplay(source)?.let { return it }
            }
        }
        return DateDisplay.DateOnly(date)
    }

    val dateDisplay = DateDisplay.DateOnly(date)
    return when (displayMode) {
        ClockSettingsRepository.INFO_DISPLAY_DATE -> dateDisplay
        ClockSettingsRepository.INFO_DISPLAY_MEDIA,
        ClockSettingsRepository.INFO_DISPLAY_SMARTSPACE,
        ClockSettingsRepository.INFO_DISPLAY_ALARM,
        ClockSettingsRepository.INFO_DISPLAY_CALENDAR,
        ClockSettingsRepository.INFO_DISPLAY_WEATHER -> sourceDisplay(displayMode) ?: dateDisplay
        else -> autoDisplay()
    }
}

internal fun AxClockView.loadDrawableIcon(resId: Int): Bitmap? {
    return try {
        ContextCompat.getDrawable(context, resId)
            ?.toBitmap(iconSize, iconSize, Bitmap.Config.ARGB_8888)
    } catch (e: Exception) {
        Log.e(tag, "Error loading icon", e)
        null
    }
}

internal fun AxClockView.loadNowPlayingIcon(packageName: String): Bitmap? {
    if (packageName.isNotEmpty()) {
        try {
            val appIcon = context.packageManager.getApplicationIcon(packageName)
            return appIcon.toBitmap(iconSize, iconSize, Bitmap.Config.ARGB_8888)
        } catch (_: Exception) {}
    }
    return loadDrawableIcon(QuickLookR.drawable.ic_now_playing_music_note)
}

internal fun AxClockView.decodeSmartspaceIcon(iconBytes: ByteArray?): Bitmap? {
    if (iconBytes == null || iconBytes.isEmpty()) return null
    return try {
        val raw = BitmapFactory.decodeByteArray(iconBytes, 0, iconBytes.size) ?: return null
        Bitmap.createScaledBitmap(raw, iconSize, iconSize, true)
    } catch (e: Exception) {
        Log.e(tag, "Error decoding smartspace icon", e)
        null
    }
}
