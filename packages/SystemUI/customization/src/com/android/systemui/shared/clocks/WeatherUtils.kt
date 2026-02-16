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

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import com.android.internal.util.android.OmniJawsClient
import com.android.systemui.plugins.keyguard.ui.clocks.ClockWeatherData

object WeatherUtils {

    fun getWeatherIcon(context: Context, conditionCode: Int): Drawable? {
        return OmniJawsClient.get().getWeatherConditionImage(context, conditionCode)
    }

    fun resolveWeatherBitmap(
        context: Context,
        weather: ClockWeatherData?,
        iconSize: Int
    ): Bitmap? {
        if (weather == null || weather == ClockWeatherData.EMPTY) return null

        val iconBytes = weather.iconBytes
        if (iconBytes != null && iconBytes.isNotEmpty()) {
            return try {
                val raw = BitmapFactory.decodeByteArray(iconBytes, 0, iconBytes.size)
                    ?: return null
                Bitmap.createScaledBitmap(raw, iconSize, iconSize, true)
            } catch (_: Exception) { null }
        }

        if (weather.conditionCode == 0) return null
        return try {
            val drawable = getWeatherIcon(context, weather.conditionCode) ?: return null
            val bmp = Bitmap.createBitmap(iconSize, iconSize, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            drawable.setBounds(0, 0, iconSize, iconSize)
            drawable.draw(canvas)
            bmp
        } catch (_: Exception) { null }
    }
}

