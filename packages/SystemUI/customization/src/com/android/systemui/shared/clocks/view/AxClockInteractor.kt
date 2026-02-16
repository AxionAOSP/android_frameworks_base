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

import android.content.Context
import android.icu.util.TimeZone
import android.text.format.DateFormat
import com.android.systemui.plugins.keyguard.data.model.AlarmData
import com.android.systemui.plugins.keyguard.ui.clocks.ClockData
import java.text.SimpleDateFormat
import java.util.*
import java.util.TimeZone as JavaTimeZone

class AxClockInteractor(
    private val context: Context,
    val state: AxClockState,
    val quickLook: QuickLookController,
) {

    val calendar: Calendar = Calendar.getInstance()
    var format: String? = null
    var timeStr: String = ""
        internal set
    var locale: Locale = Locale.getDefault()

    var needsSeconds: Boolean = false
    var useStandardFormat: Boolean = false

    fun onAlarmDataChanged(data: AlarmData) { quickLook.onAlarmDataChanged(data) }
    fun onClockDataChanged(data: ClockData) { quickLook.onClockDataChanged(data) }
    fun onPlaybackStateChanged(playing: Boolean) { quickLook.onPlaybackStateChanged(playing) }
    fun onMetadataChanged(track: String, artist: String, packageName: String) { quickLook.onMetadataChanged(track, artist, packageName) }
    fun onNowPlayingUpdate(text: String) { quickLook.onNowPlayingUpdate(text) }

    fun onDozeChanged(doze: Boolean) { state.isDoze = doze }
    fun onScreenOff(screenOff: Boolean) { state.isScreenOff = screenOff }
    fun onRegionDarknessChanged(regionDark: Boolean) { state.isRegionDark = regionDark }
    fun onFontSettingChanged() { state.fontVersion.intValue++ }

    fun onStartedWakingUp() {
        state.isDoze = false
        state.isScreenOff = false
    }

    fun onTimeZoneChanged(timeZone: TimeZone) {
        calendar.timeZone = JavaTimeZone.getTimeZone(timeZone.id)
    }

    fun refreshFormat(use24: Boolean, newLocale: Locale = locale) {
        val newFormat = when {
            useStandardFormat -> if (use24) CLOCK_PATTERN_24_STANDARD else CLOCK_PATTERN_12_STANDARD
            needsSeconds -> CLOCK_PATTERN_ALL
            else -> if (use24) CLOCK_PATTERN_24 else CLOCK_PATTERN_12
        }

        if (format == newFormat && locale == newLocale) return
        format = newFormat
        locale = newLocale
        refreshTime()
    }

    fun refreshTime(): Boolean {
        format ?: return false
        calendar.timeInMillis = System.currentTimeMillis()
        val newTime = SimpleDateFormat(format, Locale.ENGLISH).format(calendar.time)
        refreshDate()
        val changed = timeStr != newTime
        if (changed) {
            timeStr = newTime
        }
        state.timeState.value = timeStr
        return changed
    }

    fun refreshDate() {
        val dateFormat = SimpleDateFormat("EEE, dd MMM", locale)
        state.dateStrFlow.value = dateFormat.format(calendar.time)
    }

    fun setupPreview(setPreviewMode: () -> Unit) {
        setPreviewMode()

        val use24 = DateFormat.is24HourFormat(context)
        refreshFormat(use24)

        val previewFormat = format ?: if (use24) CLOCK_PATTERN_24 else CLOCK_PATTERN_12
        timeStr = when (previewFormat) {
            CLOCK_PATTERN_12_STANDARD, CLOCK_PATTERN_24_STANDARD -> PREVIEW_TIME_12_STANDARD
            CLOCK_PATTERN_ALL -> PREVIEW_TIME_ALL
            else -> PREVIEW_TIME_12
        }
        state.dateStrFlow.value = PREVIEW_DATE
        state.timeState.value = timeStr
        state.dozeAmountFlow.value = 0f
    }

    val talkBackContent: String
        get() {
            val pattern = if (DateFormat.is24HourFormat(context)) CLOCK_PATTERN_24_STANDARD else "hh:mm"
            return SimpleDateFormat(pattern, Locale.ENGLISH).format(calendar.time)
        }
}
