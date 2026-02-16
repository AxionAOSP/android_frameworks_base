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

package com.android.systemui.plugins.keyguard.ui.clocks

import android.app.PendingIntent

data class ClockWeatherData(
    val temp: String = "",
    val condition: String = "",
    val conditionCode: Int = 0,
    val city: String? = null,
    val humidity: String? = null,
    val wind: String? = null,
    val windDirection: String? = null,
    val tempUnit: String? = null,
    val windUnit: String? = null,
    val pinWheel: String? = null,
    val timestamp: Long = 0L,
    val iconBytes: ByteArray? = null,
    val tintIcon: Boolean = true,
    val tapAction: PendingIntent? = null,
) {
    val formattedTemp: String
        get() = if (temp.isNotEmpty()) "$temp${tempUnit ?: "°"}" else ""

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ClockWeatherData) return false
        return temp == other.temp &&
            condition == other.condition &&
            conditionCode == other.conditionCode &&
            city == other.city &&
            timestamp == other.timestamp &&
            tintIcon == other.tintIcon &&
            iconBytes.contentEquals(other.iconBytes)
    }

    override fun hashCode(): Int {
        var result = temp.hashCode()
        result = 31 * result + condition.hashCode()
        result = 31 * result + conditionCode
        result = 31 * result + (city?.hashCode() ?: 0)
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + tintIcon.hashCode()
        result = 31 * result + (iconBytes?.contentHashCode() ?: 0)
        return result
    }

    companion object {
        val EMPTY = ClockWeatherData()
    }
}

