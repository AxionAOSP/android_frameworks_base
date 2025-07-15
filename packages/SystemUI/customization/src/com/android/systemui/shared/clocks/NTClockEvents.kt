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

import android.content.res.Resources
import android.text.format.DateFormat
import android.view.View
import com.android.systemui.plugins.clocks.*
import com.android.systemui.shared.clocks.view.NTClockView
import java.util.*

class NTClockEvents(private val view: NTClockView) : ClockEvents {

    override var isReactiveTouchInteractionEnabled: Boolean = false

    override fun onAlarmDataChanged(data: AlarmData) {
        view.onAlarmDataChanged(data)
    }

    override fun onWeatherDataChanged(data: WeatherData) {}

    override fun onZenDataChanged(data: ZenData) {
        requireNotNull(data) { "ZenData cannot be null" }
    }

    override fun onLocaleChanged(locale: Locale) {
        requireNotNull(locale) { "Locale cannot be null" }
        val is24Hour = DateFormat.is24HourFormat(view.context)
        view.refreshFormat(is24Hour, locale)
    }

    override fun onTimeFormatChanged(is24Hr: Boolean) {
        view.refreshFormat(is24Hr)
    }

    override fun onTimeZoneChanged(timeZone: TimeZone) {
        requireNotNull(timeZone) { "TimeZone cannot be null" }
        view.onTimeZoneChanged(timeZone)

        val is24Hour = DateFormat.is24HourFormat(view.context)
        view.refreshFormat(is24Hour)
    }
    
    override fun onUiModeChanged(isDarkTheme: Boolean) {
        view.onThemeChanged(isDarkTheme)
    }
    
    override fun onNTWeatherDataChanged(data: NTWeatherData) {
        view.onNTWeatherDataChanged(data)
    }
    
    override fun onCalendarDataChanged(data: CalendarSimpleData) {
        view.onCalendarDataChanged(data)
    }
    
    override fun onDateChanged() {
        view.onDateChanged()
    }
    
    override fun onMetadataChanged(track: String, artist: String) {
        view.onMetadataChanged(track, artist)
    }
    
    override fun onPlaybackStateChanged(playing: Boolean) {
        view.onPlaybackStateChanged(playing)
    }
    
    override fun onNowPlayingUpdate(nowPlayingText: String) {
        view.onNowPlayingUpdate(nowPlayingText)
    }
}
