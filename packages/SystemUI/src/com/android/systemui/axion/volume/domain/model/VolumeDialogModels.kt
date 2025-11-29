/*
 * Copyright (C) 2025 Axion OS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.axion.volume.domain.model

import android.media.AudioManager
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material.icons.filled.RingVolume
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeMute
import androidx.compose.ui.graphics.vector.ImageVector

enum class AxionRingerMode {
    NORMAL,
    VIBRATE,
    SILENT
}

data class AxionVolumeDialogState(
    val ringerMode: AxionRingerMode = AxionRingerMode.NORMAL,
    val volumeStreams: List<AxionVolumeStreamModel> = emptyList(),
    val captionsEnabled: Boolean = false,
    val captionsAvailable: Boolean = true,
    val supportedRingerModes: List<AxionRingerMode> = listOf(
        AxionRingerMode.NORMAL,
        AxionRingerMode.VIBRATE,
        AxionRingerMode.SILENT
    ),
    val appVolumes: List<AxionAppVolumeModel> = emptyList(),
)

data class AxionAppVolumeModel(
    val packageName: String,
    val volume: Float,
    val isMuted: Boolean,
    val isActive: Boolean
)

data class AxionVolumeStreamModel(
    val streamType: Int,
    val level: Float,
    val isMuted: Boolean,
    val maxLevel: Int,
    val minLevel: Int,
) {
    val streamInfo: AxionStreamInfo
        get() = AxionStreamInfo.fromStreamType(streamType)
}

enum class AxionStreamInfo(
    val streamType: Int,
    val label: String,
    val icon: ImageVector,
    val mutedIcon: ImageVector,
) {
    MUSIC(
        streamType = AudioManager.STREAM_MUSIC,
        label = "Media",
        icon = Icons.Filled.MusicNote,
        mutedIcon = Icons.AutoMirrored.Filled.VolumeOff,
    ),
    RING(
        streamType = AudioManager.STREAM_RING,
        label = "Ring",
        icon = Icons.Filled.RingVolume,
        mutedIcon = Icons.Filled.VolumeOff,
    ),
    ALARM(
        streamType = AudioManager.STREAM_ALARM,
        label = "Alarm",
        icon = Icons.Filled.Alarm,
        mutedIcon = Icons.Filled.Alarm,
    ),
    VOICE_CALL(
        streamType = AudioManager.STREAM_VOICE_CALL,
        label = "Call",
        icon = Icons.Filled.PhoneInTalk,
        mutedIcon = Icons.Filled.Call,
    ),
    NOTIFICATION(
        streamType = AudioManager.STREAM_NOTIFICATION,
        label = "Notification",
        icon = Icons.Filled.Notifications,
        mutedIcon = Icons.Filled.NotificationsOff,
    ),
    UNKNOWN(
        streamType = -1,
        label = "Media",
        icon = Icons.Filled.MusicNote,
        mutedIcon = Icons.AutoMirrored.Filled.VolumeOff,
    );

    companion object {
        fun fromStreamType(streamType: Int): AxionStreamInfo =
            entries.find { it.streamType == streamType } ?: UNKNOWN
    }
}