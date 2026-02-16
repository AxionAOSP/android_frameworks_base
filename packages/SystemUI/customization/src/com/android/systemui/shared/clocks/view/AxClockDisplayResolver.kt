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
import com.android.systemui.shared.clocks.WeatherUtils

internal fun AxClockView.resolveDisplay(
    clockData: ClockData,
    media: ClockMediaState,
    nowPlaying: String,
    nowPlayingTapAction: PendingIntent?,
    alarm: String,
    date: String,
): DateDisplay {
    if (media.isPlaying && media.trackTitle.isNotEmpty()) {
        val fullText = buildString {
            append("Now playing ")
            append(media.trackTitle)
            if (media.artistName.isNotEmpty()) {
                append(" by ")
                append(media.artistName)
            }
        }
        return DateDisplay.IconText(fullText, loadNowPlayingIcon(media.packageName), media.packageName.isEmpty())
    }

    if (nowPlaying.isNotBlank()) {
        return DateDisplay.IconText("Now playing $nowPlaying", loadNowPlayingIcon(media.packageName), false, nowPlayingTapAction)
    }

    val activeSmartspace = clockData.smartspace.firstOrNull {
        it.title.isNotEmpty() && !it.isSensitive
    }
    if (activeSmartspace != null) {
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

    if (alarm.isNotBlank()) {
        return DateDisplay.IconText(alarm, loadDrawableIcon(QuickLookR.drawable.ic_alarm), true)
    }

    val cal = clockData.calendar.takeIf { it != CalendarSimpleData.EMPTY }
    if (cal != null && cal.isEventVisible() && !cal.title.isNullOrEmpty()) {
        return DateDisplay.IconText(
            cal.description,
            loadDrawableIcon(QuickLookR.drawable.ic_calendar),
            true,
            cal.tapAction,
        )
    }

    val weather = clockData.weather.takeIf { it != ClockWeatherData.EMPTY }
    if (weather != null && weather.temp.isNotEmpty()) {
        return DateDisplay.Weather(
            date,
            weather.formattedTemp,
            WeatherUtils.resolveWeatherBitmap(context, weather, iconSize),
            weather.tintIcon,
            weather.tapAction,
        )
    }

    return DateDisplay.DateOnly(date)
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

