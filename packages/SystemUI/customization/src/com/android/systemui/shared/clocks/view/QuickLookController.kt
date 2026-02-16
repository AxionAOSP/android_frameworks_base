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
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.android.systemui.plugins.keyguard.data.model.AlarmData
import com.android.systemui.plugins.keyguard.ui.clocks.ClockData
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.TimeUnit

class QuickLookController(private val view: AxClockView) {

    val clockDataFlow = MutableStateFlow(ClockData.EMPTY)
    val mediaStateFlow = MutableStateFlow(ClockMediaState())
    val nowPlayingFlow = MutableStateFlow("")
    val nowPlayingTapActionFlow = MutableStateFlow<PendingIntent?>(null)
    val nextAlarmFlow = MutableStateFlow("")

    fun onClockDataChanged(data: ClockData) {
        clockDataFlow.value = data
    }

    fun onPlaybackStateChanged(playing: Boolean) {
        mediaStateFlow.value = mediaStateFlow.value.copy(isPlaying = playing)
    }

    fun onMetadataChanged(track: String, artist: String, packageName: String) {
        mediaStateFlow.value = mediaStateFlow.value.copy(
            trackTitle = track,
            artistName = artist,
            packageName = packageName,
        )
    }

    fun onNowPlayingUpdate(text: String) {
        nowPlayingFlow.value = text
    }

    fun onAlarmDataChanged(data: AlarmData) {
        val nextAlarmMillis = data.nextAlarmMillis ?: 0L
        val now = System.currentTimeMillis()
        val futureLimit = now + TimeUnit.HOURS.toMillis(ALARM_VISIBILITY_HOURS)
        val withinHours = nextAlarmMillis in now..futureLimit
        nextAlarmFlow.value = if (withinHours) {
            val fmt = if (DateFormat.is24HourFormat(view.context)) "HH:mm" else "h:mm"
            DateFormat.format(fmt, nextAlarmMillis).toString()
        } else ""
    }

    @Composable
    fun rememberResolvedDisplay(dateStr: String): DateDisplay {
        val clockData by clockDataFlow.collectAsState()
        val media by mediaStateFlow.collectAsState()
        val nowPlaying by nowPlayingFlow.collectAsState()
        val nowPlayingTapAction by nowPlayingTapActionFlow.collectAsState()
        val alarm by nextAlarmFlow.collectAsState()

        return remember(clockData, media, nowPlaying, nowPlayingTapAction, alarm, dateStr) {
            view.resolveDisplay(clockData, media, nowPlaying, nowPlayingTapAction, alarm, dateStr)
        }
    }
}
