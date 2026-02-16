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
import android.icu.util.TimeZone
import android.text.format.DateFormat
import android.view.View
import com.android.systemui.plugins.keyguard.data.model.*
import com.android.systemui.plugins.keyguard.ui.clocks.*
import com.android.systemui.shared.clocks.view.AxClockView
import java.util.*

class AxClockEvents(
    private val smallClockView: AxClockView,
    private val largeClockView: AxClockView? = null
) : ClockEvents {

    private val views: List<AxClockView>
        get() = listOfNotNull(smallClockView, largeClockView)

    override var isReactiveTouchInteractionEnabled: Boolean = false

    override fun onAlarmDataChanged(data: AlarmData) {
        views.forEach { it.onAlarmDataChanged(data) }
    }

    override fun onWeatherDataChanged(data: WeatherData) {}

    override fun onZenDataChanged(data: ZenData) {
        requireNotNull(data) { "ZenData cannot be null" }
    }

    override fun onLocaleChanged(locale: Locale) {
        requireNotNull(locale) { "Locale cannot be null" }
        val is24Hour = DateFormat.is24HourFormat(smallClockView.context)
        views.forEach { it.refreshFormat(is24Hour, locale) }
    }

    override fun onTimeFormatChanged(formatKind: TimeFormatKind) {
        val is24Hour = DateFormat.is24HourFormat(smallClockView.context)
        views.forEach { it.refreshFormat(is24Hour) }
    }

    override fun onTimeZoneChanged(timeZone: TimeZone) {
        requireNotNull(timeZone) { "TimeZone cannot be null" }
        views.forEach { it.onTimeZoneChanged(timeZone) }

        val is24Hour = DateFormat.is24HourFormat(smallClockView.context)
        views.forEach { it.refreshFormat(is24Hour) }
    }

    override fun onUiModeChanged(isDarkTheme: Boolean) {
        views.forEach { it.onThemeChanged(isDarkTheme) }
    }

    override fun onDateChanged() {
        views.forEach { it.onDateChanged() }
    }

    override fun onClockDataChanged(data: ClockData) {
        views.forEach { it.onClockDataChanged(data) }
    }

    override fun onMetadataChanged(track: String, artist: String, packageName: String) {
        views.forEach { it.onMetadataChanged(track, artist, packageName) }
    }

    override fun onPlaybackStateChanged(playing: Boolean) {
        views.forEach { it.onPlaybackStateChanged(playing) }
    }

    override fun onNowPlayingUpdate(nowPlayingText: String) {
        views.forEach { it.onNowPlayingUpdate(nowPlayingText) }
    }
    override fun onClockLayoutChanged(isCentered: Boolean, isLargeClockVisible: Boolean) {
        views.forEach { it.onClockLayoutChanged(isCentered, isLargeClockVisible) }
    }
    override fun onDepthEffectVisibilityChanged(visible: Boolean) {
        views.forEach { it.onDepthEffectVisibilityChanged(visible) }
    }
}
