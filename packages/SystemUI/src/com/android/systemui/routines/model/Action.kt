/*
 * Copyright (C) 2025-2026 AxionOS
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

package com.android.systemui.routines.model

sealed interface Action {

    data class SetFeature(
        val feature: String,
        val enabled: Boolean,
    ) : Action

    data class ToggleFeature(
        val feature: String,
    ) : Action

    data class SetVolume(
        val streamType: Int,
        val level: Int,
    ) : Action

    data class SetBrightness(
        val level: Int,
    ) : Action

    data class SetRingerMode(
        val mode: Int,
    ) : Action

    data class LaunchApp(
        val packageName: String,
    ) : Action

    data class SendBroadcast(
        val action: String,
        val extras: Map<String, String> = emptyMap(),
    ) : Action

    data class ShowNotification(
        val title: String,
        val text: String,
    ) : Action

    data class Delay(
        val durationMs: Long,
    ) : Action

    data class SetSetting(
        val table: SettingsTable,
        val key: String,
        val value: String,
    ) : Action {
        enum class SettingsTable { SECURE, GLOBAL, SYSTEM }
    }

    data class SetSensorPrivacy(
        val sensor: Int,
        val blocked: Boolean,
    ) : Action

    data class PlaySound(
        val soundType: Int,
        val uri: String? = null,
    ) : Action

    data class HttpRequest(
        val url: String,
        val method: String = METHOD_GET,
        val headers: Map<String, String> = emptyMap(),
        val body: String? = null,
        val timeoutMs: Int = DEFAULT_HTTP_TIMEOUT_MS,
        val ignoreSslErrors: Boolean = false,
        val requireValidatedInternet: Boolean = true,
    ) : Action

    companion object {
        const val TYPE_SET_FEATURE = "set_feature"
        const val TYPE_TOGGLE_FEATURE = "toggle_feature"
        const val TYPE_SET_VOLUME = "set_volume"
        const val TYPE_SET_BRIGHTNESS = "set_brightness"
        const val TYPE_SET_RINGER_MODE = "set_ringer_mode"
        const val TYPE_LAUNCH_APP = "launch_app"
        const val TYPE_SEND_BROADCAST = "send_broadcast"
        const val TYPE_SHOW_NOTIFICATION = "show_notification"
        const val TYPE_DELAY = "delay"
        const val TYPE_SET_SETTING = "set_setting"
        const val TYPE_SET_SENSOR_PRIVACY = "set_sensor_privacy"
        const val TYPE_PLAY_SOUND = "play_sound"
        const val TYPE_HTTP_REQUEST = "http_request"
        const val METHOD_GET = "GET"
        const val DEFAULT_HTTP_TIMEOUT_MS = 15_000
        const val MAX_HTTP_TIMEOUT_MS = 30_000
    }
}
