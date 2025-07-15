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

import com.android.systemui.customization.R

object WeatherUtils {

    private val sunnyTypes = setOf(1, 2)
    private val cloudyTypes = setOf(7)
    private val hazySunshineTypes = setOf(5)
    private val mostlyCloudyTypes = setOf(3, 4, 6)
    private val overcastTypes = setOf(8)
    private val fogTypes = setOf(11)
    private val rainTypes = setOf(12, 13, 14, 18, 39, 40)
    private val thunderstormTypes = setOf(15, 16, 17, 41, 42)
    private val snowTypes = setOf(19, 20, 21, 22, 23, 24, 26, 29, 43, 44)
    private val sleetTypes = setOf(25)
    private val windyTypes = setOf(32)
    private val clearNightTypes = setOf(33, 34)
    private val cloudyNightTypes = setOf(35, 36, 38)
    private val hazyNightTypes = setOf(37)
    private val hotTypes = setOf(30)
    private val coldTypes = setOf(31)

    fun celsiusToFahrenheit(celsius: Int): Int {
        return kotlin.math.ceil(32 + celsius * 1.8).toInt()
    }

    fun getWeatherIcon(type: Int): Int = 0
    /*
    fun getWeatherIcon(type: Int): Int = when (type) {
        in sunnyTypes -> R.drawable.ic_widget_sunny
        in cloudyTypes -> R.drawable.ic_widget_cloudy
        in hazySunshineTypes -> R.drawable.ic_widget_hazy_sunshine
        in mostlyCloudyTypes -> R.drawable.ic_widget_partly_cloudy
        in overcastTypes -> R.drawable.ic_widget_overcast
        in fogTypes -> R.drawable.ic_widget_fog
        in rainTypes -> R.drawable.ic_widget_rainy
        in thunderstormTypes -> R.drawable.ic_widget_thunderstorms
        in snowTypes -> R.drawable.ic_widget_snow
        in sleetTypes -> R.drawable.ic_widget_sleet
        in windyTypes -> R.drawable.ic_widget_windy
        in clearNightTypes -> R.drawable.ic_widget_clear_night
        in cloudyNightTypes -> R.drawable.ic_widget_cloudy_night
        in hazyNightTypes -> R.drawable.ic_widget_hazy_night
        in hotTypes -> R.drawable.ic_widget_hot
        in coldTypes -> R.drawable.ic_widget_cold
        else -> 0
    }*/
}
