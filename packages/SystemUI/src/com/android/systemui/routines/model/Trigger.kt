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

import java.util.Calendar

sealed interface Trigger {

    data class TimeOfDay(
        val hour: Int,
        val minute: Int,
        val daysOfWeek: Set<Int> = ALL_DAYS,
    ) : Trigger

    data class Interval(
        val intervalMinutes: Int,
    ) : Trigger

    data class ChargingState(
        val charging: Boolean,
    ) : Trigger

    data class BatteryLevel(
        val threshold: Int,
        val direction: Direction,
    ) : Trigger {
        enum class Direction { ABOVE, BELOW }
    }

    data class WifiState(
        val connected: Boolean,
        val ssid: String? = null,
    ) : Trigger

    data class BluetoothState(
        val connected: Boolean,
        val deviceAddress: String? = null,
    ) : Trigger

    data class ScreenState(
        val on: Boolean,
    ) : Trigger

    data class FeatureState(
        val feature: String,
        val active: Boolean,
    ) : Trigger

    data class HeadphonesState(
        val connected: Boolean,
    ) : Trigger

    data class RingerMode(
        val mode: Int,
    ) : Trigger

    data class AppLaunch(
        val packageName: String,
    ) : Trigger

    data class AppClose(
        val packageName: String,
    ) : Trigger

    data class SensorPrivacyState(
        val sensor: Int,
        val blocked: Boolean,
    ) : Trigger

    data class Location(
        val latitude: Double,
        val longitude: Double,
        val radiusMeters: Float,
        val entering: Boolean,
    ) : Trigger

    companion object {
        val ALL_DAYS = setOf(
            Calendar.SUNDAY, Calendar.MONDAY, Calendar.TUESDAY,
            Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY,
            Calendar.SATURDAY,
        )

        const val TYPE_TIME_OF_DAY = "time_of_day"
        const val TYPE_INTERVAL = "interval"
        const val TYPE_CHARGING_STATE = "charging_state"
        const val TYPE_BATTERY_LEVEL = "battery_level"
        const val TYPE_WIFI_STATE = "wifi_state"
        const val TYPE_BLUETOOTH_STATE = "bluetooth_state"
        const val TYPE_SCREEN_STATE = "screen_state"
        const val TYPE_FEATURE_STATE = "feature_state"
        const val TYPE_HEADPHONES_STATE = "headphones_state"
        const val TYPE_RINGER_MODE = "ringer_mode"
        const val TYPE_APP_LAUNCH = "app_launch"
        const val TYPE_APP_CLOSE = "app_close"
        const val TYPE_SENSOR_PRIVACY_STATE = "sensor_privacy_state"
        const val TYPE_LOCATION = "location"
    }
}
