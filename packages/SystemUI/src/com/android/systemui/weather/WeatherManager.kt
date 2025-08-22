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
package com.android.systemui.weather

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.android.internal.util.android.OmniJawsClient
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.plugins.clocks.NTWeatherData
import com.android.systemui.util.WeakListenerManager
import javax.inject.Inject

@SysUISingleton
class WeatherManager @Inject constructor(
    private val context: Context
) : OmniJawsClient.OmniJawsObserver {

    interface Callback {
        fun onWeatherUpdated(data: NTWeatherData)
    }

    private val handler = Handler(Looper.getMainLooper())
    private var isObservingWeather = false
    private var isActive = false
    private var quicklookEnabled = false

    private val callbacks = WeakListenerManager<Callback>().apply {
        setLifecycleCallbacks(
            onActive = {
                quicklookEnabled = isQuicklookEnabled()
                if (quicklookEnabled) {
                    startWeatherListening()
                    queryWeather()
                } else {
                    notifyCallbacks(NTWeatherData.EMPTY)
                }
                isActive = true
            },
            onInactive = {
                stopWeatherListening()
                isActive = false
            }
        )
    }

    fun addCallback(callback: Callback) {
        callbacks.addListener(callback)
        quicklookEnabled = isQuicklookEnabled()
        if (quicklookEnabled) {
            queryWeather()
        } else {
            callback.onWeatherUpdated(NTWeatherData.EMPTY)
        }
    }

    fun removeCallback(callback: Callback) {
        callbacks.removeListener(callback)
        if (callbacks.isEmpty()) {
            stopWeatherListening()
        }
    }

    private fun startWeatherListening() {
        if (isObservingWeather) return
        if (!OmniJawsClient.get().isOmniJawsEnabled(context)) return

        OmniJawsClient.get().addObserver(context, this)
        isObservingWeather = true
    }

    private fun stopWeatherListening() {
        if (!isObservingWeather) return
        OmniJawsClient.get().removeObserver(context, this)
        isObservingWeather = false
    }

    private fun queryWeather() {
        if (!isQuicklookEnabled() || !OmniJawsClient.get().isOmniJawsEnabled(context)) {
            notifyCallbacks(NTWeatherData.EMPTY)
            return
        }

        OmniJawsClient.get().queryWeather(context)
        val info = OmniJawsClient.get().weatherInfo

        val data = info?.run {
            NTWeatherData(
                city = city,
                conditionCode = conditionCode,
                temp = temp,
                tempUnits = tempUnits,
                condition = condition,
                windSpeed = windSpeed,
                windUnits = windUnits,
                pinWheel = pinWheel,
                humidity = humidity,
                timeStamp = timeStamp ?: System.currentTimeMillis()
            )
        } ?: NTWeatherData.EMPTY

        notifyCallbacks(data)
    }

    private fun notifyCallbacks(data: NTWeatherData) {
        callbacks.notify { it.onWeatherUpdated(data) }
    }

    private fun isQuicklookEnabled(): Boolean {
        return Settings.Secure.getInt(
            context.contentResolver,
            "nt_quicklook_weather",
            1
        ) == 1
    }

    override fun weatherUpdated() {
        if (!isQuicklookEnabled() || !OmniJawsClient.get().isOmniJawsEnabled(context)) return
        queryWeather()
    }

    override fun weatherError(errorReason: Int) {
    }
}
