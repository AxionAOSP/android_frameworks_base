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
package com.android.systemui.plugins.clocks

data class NTWeatherData(
    val city: String? = null,
    val conditionCode: Int = 0,
    val temp: String? = null,
    val tempUnits: String? = null,
    val condition: String? = null,
    val windSpeed: String? = null,
    val windUnits: String? = null,
    val pinWheel: String? = null,
    val humidity: String? = null,
    val timeStamp: Long = 0L
) {
    companion object {
        val EMPTY = NTWeatherData()
    }
}
