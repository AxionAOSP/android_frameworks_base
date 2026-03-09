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

sealed interface Condition {

    data class TimeRange(
        val startHour: Int,
        val startMinute: Int,
        val endHour: Int,
        val endMinute: Int,
    ) : Condition

    data class DayOfWeek(
        val days: Set<Int>,
    ) : Condition

    data class BatteryRange(
        val min: Int,
        val max: Int,
    ) : Condition

    data class ChargingState(
        val charging: Boolean,
    ) : Condition

    data class WifiConnected(
        val ssid: String? = null,
    ) : Condition

    data class BluetoothConnected(
        val deviceAddress: String? = null,
    ) : Condition

    data class ScreenOn(
        val on: Boolean,
    ) : Condition

    data class FeatureActive(
        val feature: String,
        val active: Boolean,
    ) : Condition

    data class SensorBlocked(
        val sensor: Int,
        val blocked: Boolean,
    ) : Condition

    data class LocationNear(
        val latitude: Double,
        val longitude: Double,
        val radiusMeters: Float,
    ) : Condition

    companion object {
        const val TYPE_TIME_RANGE = "time_range"
        const val TYPE_DAY_OF_WEEK = "day_of_week"
        const val TYPE_BATTERY_RANGE = "battery_range"
        const val TYPE_CHARGING_STATE = "charging_state"
        const val TYPE_WIFI_CONNECTED = "wifi_connected"
        const val TYPE_BLUETOOTH_CONNECTED = "bluetooth_connected"
        const val TYPE_SCREEN_ON = "screen_on"
        const val TYPE_FEATURE_ACTIVE = "feature_active"
        const val TYPE_SENSOR_BLOCKED = "sensor_blocked"
        const val TYPE_LOCATION_NEAR = "location_near"
    }
}
