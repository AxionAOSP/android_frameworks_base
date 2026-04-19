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

package com.android.systemui.routines.data

import com.android.axion.platform.AxPlatformClient
import com.android.systemui.routines.model.Action
import com.android.systemui.routines.model.Condition
import com.android.systemui.routines.model.Routine
import com.android.systemui.routines.model.Trigger
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject

class RoutineSerializer @Inject constructor() {

    fun serializeRoutines(routines: List<Routine>): String {
        val array = JSONArray()
        routines.forEach { array.put(serializeRoutine(it)) }
        return array.toString()
    }

    fun deserializeRoutines(json: String): List<Routine> {
        if (json.isBlank()) return emptyList()
        val array = JSONArray(json)
        return (0 until array.length()).mapNotNull { i ->
            runCatching { deserializeRoutine(array.getJSONObject(i)) }.getOrNull()
        }
    }

    private fun serializeRoutine(routine: Routine): JSONObject = JSONObject().apply {
        put(KEY_ID, routine.id)
        put(KEY_NAME, routine.name)
        put(KEY_ENABLED, routine.enabled)
        put(KEY_TRIGGERS, JSONArray().apply {
            routine.triggers.forEach { put(serializeTrigger(it)) }
        })
        put(KEY_CONDITIONS, JSONArray().apply {
            routine.conditions.forEach { put(serializeCondition(it)) }
        })
        put(KEY_ACTIONS, JSONArray().apply {
            routine.actions.forEach { put(serializeAction(it)) }
        })
        put(KEY_CREATED_AT, routine.createdAt)
        routine.lastTriggeredAt?.let { put(KEY_LAST_TRIGGERED_AT, it) }
    }

    private fun deserializeRoutine(json: JSONObject): Routine = Routine(
        id = json.getString(KEY_ID),
        name = json.getString(KEY_NAME),
        enabled = json.optBoolean(KEY_ENABLED, true),
        triggers = deserializeList(json.getJSONArray(KEY_TRIGGERS)) { deserializeTrigger(it) },
        conditions = deserializeList(json.optJSONArray(KEY_CONDITIONS) ?: JSONArray()) {
            deserializeCondition(it)
        },
        actions = deserializeList(json.getJSONArray(KEY_ACTIONS)) { deserializeAction(it) },
        createdAt = json.optLong(KEY_CREATED_AT, System.currentTimeMillis()),
        lastTriggeredAt = json.optLong(KEY_LAST_TRIGGERED_AT, -1L).takeIf { it >= 0 },
    )

    private fun serializeTrigger(trigger: Trigger): JSONObject = JSONObject().apply {
        when (trigger) {
            is Trigger.TimeOfDay -> {
                put(KEY_TYPE, Trigger.TYPE_TIME_OF_DAY)
                put(KEY_HOUR, trigger.hour)
                put(KEY_MINUTE, trigger.minute)
                put(KEY_DAYS_OF_WEEK, JSONArray(trigger.daysOfWeek.toList()))
            }
            is Trigger.Interval -> {
                put(KEY_TYPE, Trigger.TYPE_INTERVAL)
                put(KEY_INTERVAL_MINUTES, trigger.intervalMinutes)
            }
            is Trigger.ChargingState -> {
                put(KEY_TYPE, Trigger.TYPE_CHARGING_STATE)
                put(KEY_CHARGING, trigger.charging)
            }
            is Trigger.BatteryLevel -> {
                put(KEY_TYPE, Trigger.TYPE_BATTERY_LEVEL)
                put(KEY_THRESHOLD, trigger.threshold)
                put(KEY_DIRECTION, trigger.direction.name)
            }
            is Trigger.WifiState -> {
                put(KEY_TYPE, Trigger.TYPE_WIFI_STATE)
                put(KEY_CONNECTED, trigger.connected)
                trigger.ssid?.let { put(KEY_SSID, it) }
                trigger.ssidPattern?.let { put(KEY_SSID_PATTERN, it) }
            }
            is Trigger.BluetoothState -> {
                put(KEY_TYPE, Trigger.TYPE_BLUETOOTH_STATE)
                put(KEY_CONNECTED, trigger.connected)
                trigger.deviceAddress?.let { put(KEY_DEVICE_ADDRESS, it) }
            }
            is Trigger.ScreenState -> {
                put(KEY_TYPE, Trigger.TYPE_SCREEN_STATE)
                put(KEY_ON, trigger.on)
            }
            is Trigger.FeatureState -> {
                put(KEY_TYPE, Trigger.TYPE_FEATURE_STATE)
                put(KEY_FEATURE, trigger.feature)
                put(KEY_ACTIVE, trigger.active)
            }
            is Trigger.HeadphonesState -> {
                put(KEY_TYPE, Trigger.TYPE_HEADPHONES_STATE)
                put(KEY_CONNECTED, trigger.connected)
            }
            is Trigger.RingerMode -> {
                put(KEY_TYPE, Trigger.TYPE_RINGER_MODE)
                put(KEY_MODE, trigger.mode)
            }
            is Trigger.AppLaunch -> {
                put(KEY_TYPE, Trigger.TYPE_APP_LAUNCH)
                put(KEY_PACKAGE_NAME, trigger.packageName)
            }
            is Trigger.AppClose -> {
                put(KEY_TYPE, Trigger.TYPE_APP_CLOSE)
                put(KEY_PACKAGE_NAME, trigger.packageName)
            }
            is Trigger.SensorPrivacyState -> {
                put(KEY_TYPE, Trigger.TYPE_SENSOR_PRIVACY_STATE)
                put(KEY_SENSOR, trigger.sensor)
                put(KEY_BLOCKED, trigger.blocked)
            }
            is Trigger.Location -> {
                put(KEY_TYPE, Trigger.TYPE_LOCATION)
                put(KEY_LATITUDE, trigger.latitude)
                put(KEY_LONGITUDE, trigger.longitude)
                put(KEY_RADIUS_METERS, trigger.radiusMeters.toDouble())
                put(KEY_ENTERING, trigger.entering)
            }
            is Trigger.CaptivePortal -> {
                put(KEY_TYPE, Trigger.TYPE_CAPTIVE_PORTAL)
                trigger.ssid?.let { put(KEY_SSID, it) }
            }
        }
    }

    private fun deserializeTrigger(json: JSONObject): Trigger =
        when (json.getString(KEY_TYPE)) {
            Trigger.TYPE_TIME_OF_DAY -> Trigger.TimeOfDay(
                hour = json.getInt(KEY_HOUR),
                minute = json.getInt(KEY_MINUTE),
                daysOfWeek = deserializeIntSet(json.optJSONArray(KEY_DAYS_OF_WEEK)),
            )
            Trigger.TYPE_INTERVAL -> Trigger.Interval(
                intervalMinutes = json.getInt(KEY_INTERVAL_MINUTES),
            )
            Trigger.TYPE_CHARGING_STATE -> Trigger.ChargingState(
                charging = json.getBoolean(KEY_CHARGING),
            )
            Trigger.TYPE_BATTERY_LEVEL -> Trigger.BatteryLevel(
                threshold = json.getInt(KEY_THRESHOLD),
                direction = Trigger.BatteryLevel.Direction.valueOf(json.getString(KEY_DIRECTION)),
            )
            Trigger.TYPE_WIFI_STATE -> Trigger.WifiState(
                connected = json.getBoolean(KEY_CONNECTED),
                ssid = json.optString(KEY_SSID, null),
                ssidPattern = json.optString(KEY_SSID_PATTERN, null),
            )
            Trigger.TYPE_BLUETOOTH_STATE -> Trigger.BluetoothState(
                connected = json.getBoolean(KEY_CONNECTED),
                deviceAddress = json.optString(KEY_DEVICE_ADDRESS, null),
            )
            Trigger.TYPE_SCREEN_STATE -> Trigger.ScreenState(
                on = json.getBoolean(KEY_ON),
            )
            Trigger.TYPE_FEATURE_STATE -> Trigger.FeatureState(
                feature = resolveFeature(json.getString(KEY_FEATURE)),
                active = json.getBoolean(KEY_ACTIVE),
            )
            Trigger.TYPE_HEADPHONES_STATE -> Trigger.HeadphonesState(
                connected = json.getBoolean(KEY_CONNECTED),
            )
            Trigger.TYPE_RINGER_MODE -> Trigger.RingerMode(
                mode = json.getInt(KEY_MODE),
            )
            Trigger.TYPE_APP_LAUNCH -> Trigger.AppLaunch(
                packageName = json.getString(KEY_PACKAGE_NAME),
            )
            Trigger.TYPE_APP_CLOSE -> Trigger.AppClose(
                packageName = json.getString(KEY_PACKAGE_NAME),
            )
            Trigger.TYPE_SENSOR_PRIVACY_STATE -> Trigger.SensorPrivacyState(
                sensor = json.getInt(KEY_SENSOR),
                blocked = json.getBoolean(KEY_BLOCKED),
            )
            Trigger.TYPE_LOCATION -> Trigger.Location(
                latitude = json.getDouble(KEY_LATITUDE),
                longitude = json.getDouble(KEY_LONGITUDE),
                radiusMeters = json.getDouble(KEY_RADIUS_METERS).toFloat(),
                entering = json.getBoolean(KEY_ENTERING),
            )
            Trigger.TYPE_CAPTIVE_PORTAL -> Trigger.CaptivePortal(
                ssid = json.optString(KEY_SSID, null),
            )
            else -> throw IllegalArgumentException("Unknown trigger type: ${json.getString(KEY_TYPE)}")
        }

    private fun serializeCondition(condition: Condition): JSONObject = JSONObject().apply {
        when (condition) {
            is Condition.TimeRange -> {
                put(KEY_TYPE, Condition.TYPE_TIME_RANGE)
                put(KEY_START_HOUR, condition.startHour)
                put(KEY_START_MINUTE, condition.startMinute)
                put(KEY_END_HOUR, condition.endHour)
                put(KEY_END_MINUTE, condition.endMinute)
            }
            is Condition.DayOfWeek -> {
                put(KEY_TYPE, Condition.TYPE_DAY_OF_WEEK)
                put(KEY_DAYS, JSONArray(condition.days.toList()))
            }
            is Condition.BatteryRange -> {
                put(KEY_TYPE, Condition.TYPE_BATTERY_RANGE)
                put(KEY_MIN, condition.min)
                put(KEY_MAX, condition.max)
            }
            is Condition.ChargingState -> {
                put(KEY_TYPE, Condition.TYPE_CHARGING_STATE)
                put(KEY_CHARGING, condition.charging)
            }
            is Condition.WifiConnected -> {
                put(KEY_TYPE, Condition.TYPE_WIFI_CONNECTED)
                condition.ssid?.let { put(KEY_SSID, it) }
                condition.ssidPattern?.let { put(KEY_SSID_PATTERN, it) }
            }
            is Condition.BluetoothConnected -> {
                put(KEY_TYPE, Condition.TYPE_BLUETOOTH_CONNECTED)
                condition.deviceAddress?.let { put(KEY_DEVICE_ADDRESS, it) }
            }
            is Condition.ScreenOn -> {
                put(KEY_TYPE, Condition.TYPE_SCREEN_ON)
                put(KEY_ON, condition.on)
            }
            is Condition.FeatureActive -> {
                put(KEY_TYPE, Condition.TYPE_FEATURE_ACTIVE)
                put(KEY_FEATURE, condition.feature)
                put(KEY_ACTIVE, condition.active)
            }
            is Condition.SensorBlocked -> {
                put(KEY_TYPE, Condition.TYPE_SENSOR_BLOCKED)
                put(KEY_SENSOR, condition.sensor)
                put(KEY_BLOCKED, condition.blocked)
            }
            is Condition.LocationNear -> {
                put(KEY_TYPE, Condition.TYPE_LOCATION_NEAR)
                put(KEY_LATITUDE, condition.latitude)
                put(KEY_LONGITUDE, condition.longitude)
                put(KEY_RADIUS_METERS, condition.radiusMeters.toDouble())
            }
            is Condition.IpAddress -> {
                put(KEY_TYPE, Condition.TYPE_IP_ADDRESS)
                put(KEY_CIDR, condition.cidr)
                put(KEY_IS_REGEX, condition.isRegex)
            }
        }
    }

    private fun deserializeCondition(json: JSONObject): Condition =
        when (json.getString(KEY_TYPE)) {
            Condition.TYPE_TIME_RANGE -> Condition.TimeRange(
                startHour = json.getInt(KEY_START_HOUR),
                startMinute = json.getInt(KEY_START_MINUTE),
                endHour = json.getInt(KEY_END_HOUR),
                endMinute = json.getInt(KEY_END_MINUTE),
            )
            Condition.TYPE_DAY_OF_WEEK -> Condition.DayOfWeek(
                days = deserializeIntSet(json.optJSONArray(KEY_DAYS)),
            )
            Condition.TYPE_BATTERY_RANGE -> Condition.BatteryRange(
                min = json.getInt(KEY_MIN),
                max = json.getInt(KEY_MAX),
            )
            Condition.TYPE_CHARGING_STATE -> Condition.ChargingState(
                charging = json.getBoolean(KEY_CHARGING),
            )
            Condition.TYPE_WIFI_CONNECTED -> Condition.WifiConnected(
                ssid = json.optString(KEY_SSID, null),
                ssidPattern = json.optString(KEY_SSID_PATTERN, null),
            )
            Condition.TYPE_BLUETOOTH_CONNECTED -> Condition.BluetoothConnected(
                deviceAddress = json.optString(KEY_DEVICE_ADDRESS, null),
            )
            Condition.TYPE_SCREEN_ON -> Condition.ScreenOn(
                on = json.getBoolean(KEY_ON),
            )
            Condition.TYPE_FEATURE_ACTIVE -> Condition.FeatureActive(
                feature = resolveFeature(json.getString(KEY_FEATURE)),
                active = json.getBoolean(KEY_ACTIVE),
            )
            Condition.TYPE_SENSOR_BLOCKED -> Condition.SensorBlocked(
                sensor = json.getInt(KEY_SENSOR),
                blocked = json.getBoolean(KEY_BLOCKED),
            )
            Condition.TYPE_LOCATION_NEAR -> Condition.LocationNear(
                latitude = json.getDouble(KEY_LATITUDE),
                longitude = json.getDouble(KEY_LONGITUDE),
                radiusMeters = json.getDouble(KEY_RADIUS_METERS).toFloat(),
            )
            Condition.TYPE_IP_ADDRESS -> Condition.IpAddress(
                cidr = json.getString(KEY_CIDR),
                isRegex = json.optBoolean(KEY_IS_REGEX, false),
            )
            else -> throw IllegalArgumentException("Unknown condition type: ${json.getString(KEY_TYPE)}")
        }

    private fun serializeAction(action: Action): JSONObject = JSONObject().apply {
        when (action) {
            is Action.SetFeature -> {
                put(KEY_TYPE, Action.TYPE_SET_FEATURE)
                put(KEY_FEATURE, action.feature)
                put(KEY_ENABLED, action.enabled)
            }
            is Action.ToggleFeature -> {
                put(KEY_TYPE, Action.TYPE_TOGGLE_FEATURE)
                put(KEY_FEATURE, action.feature)
            }
            is Action.SetVolume -> {
                put(KEY_TYPE, Action.TYPE_SET_VOLUME)
                put(KEY_STREAM_TYPE, action.streamType)
                put(KEY_LEVEL, action.level)
            }
            is Action.SetBrightness -> {
                put(KEY_TYPE, Action.TYPE_SET_BRIGHTNESS)
                put(KEY_LEVEL, action.level)
            }
            is Action.SetRingerMode -> {
                put(KEY_TYPE, Action.TYPE_SET_RINGER_MODE)
                put(KEY_MODE, action.mode)
            }
            is Action.LaunchApp -> {
                put(KEY_TYPE, Action.TYPE_LAUNCH_APP)
                put(KEY_PACKAGE_NAME, action.packageName)
            }
            is Action.SendBroadcast -> {
                put(KEY_TYPE, Action.TYPE_SEND_BROADCAST)
                put(KEY_ACTION, action.action)
                put(KEY_EXTRAS, JSONObject(action.extras))
            }
            is Action.ShowNotification -> {
                put(KEY_TYPE, Action.TYPE_SHOW_NOTIFICATION)
                put(KEY_TITLE, action.title)
                put(KEY_TEXT, action.text)
            }
            is Action.Delay -> {
                put(KEY_TYPE, Action.TYPE_DELAY)
                put(KEY_DURATION_MS, action.durationMs)
            }
            is Action.SetSetting -> {
                put(KEY_TYPE, Action.TYPE_SET_SETTING)
                put(KEY_TABLE, action.table.name)
                put(KEY_SETTING_KEY, action.key)
                put(KEY_VALUE, action.value)
            }
            is Action.SetSensorPrivacy -> {
                put(KEY_TYPE, Action.TYPE_SET_SENSOR_PRIVACY)
                put(KEY_SENSOR, action.sensor)
                put(KEY_BLOCKED, action.blocked)
            }
            is Action.PlaySound -> {
                put(KEY_TYPE, Action.TYPE_PLAY_SOUND)
                put(KEY_SOUND_TYPE, action.soundType)
                action.uri?.let { put(KEY_URI, it) }
            }
            is Action.HttpRequest -> {
                put(KEY_TYPE, Action.TYPE_HTTP_REQUEST)
                put(KEY_URL, action.url)
                put(KEY_METHOD, action.method)
                if (action.headers.isNotEmpty()) {
                    put(KEY_HEADERS, JSONObject(action.headers))
                }
                action.body?.let { put(KEY_BODY, it) }
                put(KEY_TIMEOUT_MS, action.timeoutMs)
                put(KEY_IGNORE_SSL_ERRORS, action.ignoreSslErrors)
                put(KEY_REQUIRE_VALIDATED_INTERNET, action.requireValidatedInternet)
            }
        }
    }

    private fun deserializeAction(json: JSONObject): Action =
        when (json.getString(KEY_TYPE)) {
            Action.TYPE_SET_FEATURE -> Action.SetFeature(
                feature = resolveFeature(json.getString(KEY_FEATURE)),
                enabled = json.getBoolean(KEY_ENABLED),
            )
            Action.TYPE_TOGGLE_FEATURE -> Action.ToggleFeature(
                feature = resolveFeature(json.getString(KEY_FEATURE)),
            )
            Action.TYPE_SET_VOLUME -> Action.SetVolume(
                streamType = json.getInt(KEY_STREAM_TYPE),
                level = json.getInt(KEY_LEVEL),
            )
            Action.TYPE_SET_BRIGHTNESS -> Action.SetBrightness(
                level = json.getInt(KEY_LEVEL),
            )
            Action.TYPE_SET_RINGER_MODE -> Action.SetRingerMode(
                mode = json.getInt(KEY_MODE),
            )
            Action.TYPE_LAUNCH_APP -> Action.LaunchApp(
                packageName = json.getString(KEY_PACKAGE_NAME),
            )
            Action.TYPE_SEND_BROADCAST -> Action.SendBroadcast(
                action = json.getString(KEY_ACTION),
                extras = deserializeStringMap(json.optJSONObject(KEY_EXTRAS)),
            )
            Action.TYPE_SHOW_NOTIFICATION -> Action.ShowNotification(
                title = json.getString(KEY_TITLE),
                text = json.getString(KEY_TEXT),
            )
            Action.TYPE_DELAY -> Action.Delay(
                durationMs = json.getLong(KEY_DURATION_MS),
            )
            Action.TYPE_SET_SETTING -> Action.SetSetting(
                table = Action.SetSetting.SettingsTable.valueOf(json.getString(KEY_TABLE)),
                key = json.getString(KEY_SETTING_KEY),
                value = json.getString(KEY_VALUE),
            )
            Action.TYPE_SET_SENSOR_PRIVACY -> Action.SetSensorPrivacy(
                sensor = json.getInt(KEY_SENSOR),
                blocked = json.getBoolean(KEY_BLOCKED),
            )
            Action.TYPE_PLAY_SOUND -> Action.PlaySound(
                soundType = json.getInt(KEY_SOUND_TYPE),
                uri = json.optString(KEY_URI, null),
            )
            Action.TYPE_HTTP_REQUEST -> Action.HttpRequest(
                url = json.getString(KEY_URL),
                method = json.optString(KEY_METHOD, Action.METHOD_GET),
                headers = deserializeStringMap(json.optJSONObject(KEY_HEADERS)),
                body = json.optString(KEY_BODY, null),
                timeoutMs = json.optInt(KEY_TIMEOUT_MS, Action.DEFAULT_HTTP_TIMEOUT_MS),
                ignoreSslErrors = json.optBoolean(KEY_IGNORE_SSL_ERRORS, false),
                requireValidatedInternet = json.optBoolean(KEY_REQUIRE_VALIDATED_INTERNET, true),
            )
            else -> throw IllegalArgumentException("Unknown action type: ${json.getString(KEY_TYPE)}")
        }

    private fun <T> deserializeList(
        array: JSONArray,
        mapper: (JSONObject) -> T,
    ): List<T> = (0 until array.length()).mapNotNull { i ->
        runCatching { mapper(array.getJSONObject(i)) }.getOrNull()
    }

    private fun deserializeIntSet(array: JSONArray?): Set<Int> {
        if (array == null || array.length() == 0) return Trigger.ALL_DAYS
        return (0 until array.length()).map { array.getInt(it) }.toSet()
    }

    private fun deserializeStringMap(json: JSONObject?): Map<String, String> {
        if (json == null) return emptyMap()
        return json.keys().asSequence().associateWith { json.getString(it) }
    }

    private fun resolveFeature(name: String): String =
        AxPlatformClient.resolveFeature(name)
            ?: GUI_TO_FEATURE[name]
            ?: name

    companion object {

        private val GUI_TO_FEATURE = mapOf(
            "do_not_disturb" to AxPlatformClient.FEATURE_ZEN,
            "auto_rotate" to AxPlatformClient.FEATURE_ROTATION,
        )
        private const val KEY_ID = "id"
        private const val KEY_NAME = "name"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_TRIGGERS = "triggers"
        private const val KEY_CONDITIONS = "conditions"
        private const val KEY_ACTIONS = "actions"
        private const val KEY_CREATED_AT = "created_at"
        private const val KEY_LAST_TRIGGERED_AT = "last_triggered_at"
        private const val KEY_TYPE = "type"
        private const val KEY_HOUR = "hour"
        private const val KEY_MINUTE = "minute"
        private const val KEY_DAYS_OF_WEEK = "days_of_week"
        private const val KEY_INTERVAL_MINUTES = "interval_minutes"
        private const val KEY_CHARGING = "charging"
        private const val KEY_THRESHOLD = "threshold"
        private const val KEY_DIRECTION = "direction"
        private const val KEY_CONNECTED = "connected"
        private const val KEY_SSID = "ssid"
        private const val KEY_SSID_PATTERN = "ssid_pattern"
        private const val KEY_DEVICE_ADDRESS = "device_address"
        private const val KEY_ON = "on"
        private const val KEY_FEATURE = "feature"
        private const val KEY_ACTIVE = "active"
        private const val KEY_MODE = "mode"
        private const val KEY_STREAM_TYPE = "stream_type"
        private const val KEY_LEVEL = "level"
        private const val KEY_PACKAGE_NAME = "package_name"
        private const val KEY_ACTION = "action"
        private const val KEY_EXTRAS = "extras"
        private const val KEY_TITLE = "title"
        private const val KEY_TEXT = "text"
        private const val KEY_DURATION_MS = "duration_ms"
        private const val KEY_TABLE = "table"
        private const val KEY_SETTING_KEY = "setting_key"
        private const val KEY_VALUE = "value"
        private const val KEY_DAYS = "days"
        private const val KEY_MIN = "min"
        private const val KEY_MAX = "max"
        private const val KEY_START_HOUR = "start_hour"
        private const val KEY_START_MINUTE = "start_minute"
        private const val KEY_END_HOUR = "end_hour"
        private const val KEY_END_MINUTE = "end_minute"
        private const val KEY_SENSOR = "sensor"
        private const val KEY_BLOCKED = "blocked"
        private const val KEY_LATITUDE = "latitude"
        private const val KEY_LONGITUDE = "longitude"
        private const val KEY_RADIUS_METERS = "radius_meters"
        private const val KEY_ENTERING = "entering"
        private const val KEY_SOUND_TYPE = "sound_type"
        private const val KEY_URI = "uri"
        private const val KEY_CIDR = "cidr"
        private const val KEY_URL = "url"
        private const val KEY_METHOD = "method"
        private const val KEY_HEADERS = "headers"
        private const val KEY_BODY = "body"
        private const val KEY_TIMEOUT_MS = "timeout_ms"
        private const val KEY_IS_REGEX = "is_regex"
        private const val KEY_IGNORE_SSL_ERRORS = "ignore_ssl_errors"
        private const val KEY_REQUIRE_VALIDATED_INTERNET = "require_validated_internet"
    }
}
