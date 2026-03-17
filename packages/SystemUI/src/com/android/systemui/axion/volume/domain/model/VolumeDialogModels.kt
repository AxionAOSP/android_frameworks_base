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
import androidx.annotation.DrawableRes
import com.android.systemui.res.R

enum class AxionRingerMode(@DrawableRes val iconRes: Int, val label: String) {
    NORMAL(R.drawable.ic_volume_ringer, "Ring"),
    VIBRATE(R.drawable.ic_volume_ringer_vibrate, "Vibrate"),
    SILENT(R.drawable.ic_volume_ringer_mute, "Silent")
}

data class AxionVolumeDialogState(
    val ringerMode: AxionRingerMode = AxionRingerMode.NORMAL,
    val volumeStreams: List<AxionVolumeStreamModel> = emptyList(),
    val captionsEnabled: Boolean = false,
    val captionsAvailable: Boolean = true,
    val activeStream: Int = AudioManager.STREAM_MUSIC,
    val supportedRingerModes: List<AxionRingerMode> = listOf(
        AxionRingerMode.NORMAL,
        AxionRingerMode.VIBRATE,
        AxionRingerMode.SILENT
    ),
    val appVolumes: List<AxionAppVolumeModel> = emptyList(),
    val activeAppPackageName: String? = null
)

data class AxionAppVolumeModel(
    val packageName: String,
    val label: String,
    val volume: Float,
    val isMuted: Boolean,
    val isActive: Boolean
)

sealed class VolumeSliderItem {
    data class Stream(val model: AxionVolumeStreamModel) : VolumeSliderItem()
    data class AppVolume(val model: AxionAppVolumeModel) : VolumeSliderItem()
}

data class AxionVolumeStreamModel(
    val streamType: Int,
    val level: Float,
    val isMuted: Boolean,
    val maxLevel: Int,
    val minLevel: Int,
    val isRoutedToBluetooth: Boolean = false,
) {
    val streamInfo: AxionStreamInfo
        get() = AxionStreamInfo.fromStreamType(streamType)

    @get:DrawableRes
    val iconRes: Int
        get() = when {
            streamType == AudioManager.STREAM_MUSIC && isRoutedToBluetooth -> R.drawable.ic_volume_media_bt
            isMuted -> streamInfo.mutedIconRes
            else -> streamInfo.iconRes
        }

    @get:DrawableRes
    val mutedIconRes: Int
        get() = when {
            streamType == AudioManager.STREAM_MUSIC && isRoutedToBluetooth -> R.drawable.ic_volume_media_bt_mute
            else -> streamInfo.mutedIconRes
        }
}

enum class AxionStreamInfo(
    val streamType: Int,
    val label: String,
    @DrawableRes val iconRes: Int,
    @DrawableRes val mutedIconRes: Int,
) {
    MUSIC(
        streamType = AudioManager.STREAM_MUSIC,
        label = "Media",
        iconRes = R.drawable.ic_volume_media,
        mutedIconRes = R.drawable.ic_volume_media_mute,
    ),
    RING(
        streamType = AudioManager.STREAM_RING,
        label = "Ring",
        iconRes = R.drawable.ic_ring_volume,
        mutedIconRes = R.drawable.ic_ring_volume_off,
    ),
    ALARM(
        streamType = AudioManager.STREAM_ALARM,
        label = "Alarm",
        iconRes = R.drawable.ic_volume_alarm,
        mutedIconRes = R.drawable.ic_volume_alarm_mute,
    ),
    VOICE_CALL(
        streamType = AudioManager.STREAM_VOICE_CALL,
        label = "Call",
        iconRes = R.drawable.ic_call,
        mutedIconRes = R.drawable.ic_call,
    ),
    NOTIFICATION(
        streamType = AudioManager.STREAM_NOTIFICATION,
        label = "Notification",
        iconRes = R.drawable.ic_volume_ringer,
        mutedIconRes = R.drawable.ic_volume_ringer_mute,
    ),
    BLUETOOTH_SCO(
        streamType = AudioManager.STREAM_BLUETOOTH_SCO,
        label = "Bluetooth",
        iconRes = R.drawable.ic_volume_bt_sco,
        mutedIconRes = R.drawable.ic_volume_bt_sco,
    ),
    UNKNOWN(
        streamType = -1,
        label = "Media",
        iconRes = R.drawable.ic_volume_media,
        mutedIconRes = R.drawable.ic_volume_media_mute,
    );

    companion object {
        fun fromStreamType(streamType: Int): AxionStreamInfo =
            entries.find { it.streamType == streamType } ?: UNKNOWN
    }
}
