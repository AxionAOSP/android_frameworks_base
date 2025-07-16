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
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.android.internal.util.android.OmniJawsClient
import com.android.systemui.plugins.clocks.NTWeatherData
import com.android.systemui.util.WeakListenerManager

class WeatherManager private constructor() : OmniJawsClient.OmniJawsObserver {

    interface Callback {
        fun onWeatherUpdated(data: NTWeatherData)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = WeakListenerManager<Callback>()

    private var quicklookEnabled = false
    private var settingsObserver: ContentObserver? = null
    private var isRegistered = false

    init {
        listeners.setLifecycleCallbacks(
            onActive = {
                quicklookEnabled = readQuicklookEnabled()
                registerSettingsObservers()
                maybeRegisterWeatherObserver()
                if (quicklookEnabled) updateWeatherInfo()
            },
            onInactive = {
                unregisterWeatherObserver()
                unregisterSettingsObservers()
            }
        )
    }

    fun addCallback(callback: Callback) {
        listeners.addListener(callback)
    }

    fun removeCallback(callback: Callback) {
        listeners.removeListener(callback)
    }

    override fun weatherUpdated() {
        if (!quicklookEnabled) return
        updateWeatherInfo()
    }

    fun updateWeatherInfo() {
        OmniJawsClient.get().queryWeather(appContext)
        val info = OmniJawsClient.get().weatherInfo

        val data = info?.run {
            NTWeatherData(
                city = city,
                conditionCode = conditionCode,
                temp = temp,
                tempUnits = tempUnits,
                condition  = condition,
                windSpeed = windSpeed,
                windUnits = windUnits,
                pinWheel = pinWheel,
                humidity = humidity,
                timeStamp = timeStamp ?: System.currentTimeMillis()
            )
        } ?: NTWeatherData.EMPTY

        dispatch(data)
    }

    override fun weatherError(errorReason: Int) {}

    private fun dispatch(data: NTWeatherData) = mainHandler.post {
        listeners.notify { it.onWeatherUpdated(data) }
    }

    private fun readQuicklookEnabled(): Boolean =
        Settings.Secure.getInt(
            appContext.contentResolver,
            "nt_quicklook_weather",
            1
        ) == 1

    private val CLOCK_URI:     Uri = Settings.Secure.getUriFor(Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_FACE)
    private val QUICKLOOK_URI: Uri = Settings.Secure.getUriFor("nt_quicklook_weather")

    private fun registerSettingsObservers() {
        if (settingsObserver == null) {
            settingsObserver = object : ContentObserver(mainHandler) {
                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    when (uri) {
                        CLOCK_URI -> {
                            if (quicklookEnabled) {
                                updateWeatherInfo()
                            }
                        }
                        QUICKLOOK_URI -> {
                            val enabled = readQuicklookEnabled()
                            if (enabled != quicklookEnabled) {
                                quicklookEnabled = enabled
                                handleQuicklookToggle()
                            }
                        }
                    }
                }
            }.also { observer ->
                appContext.contentResolver.registerContentObserver(CLOCK_URI, false, observer)
                appContext.contentResolver.registerContentObserver(QUICKLOOK_URI, false, observer)
            }
        }
    }

    private fun unregisterSettingsObservers() {
        settingsObserver?.let {
            appContext.contentResolver.unregisterContentObserver(it)
            settingsObserver = null
        }
    }

    private fun maybeRegisterWeatherObserver() {
        if (!isRegistered && quicklookEnabled && OmniJawsClient.get().isOmniJawsEnabled(appContext)) {
            OmniJawsClient.get().addObserver(appContext, this)
            isRegistered = true
        }
    }

    private fun unregisterWeatherObserver() {
        if (isRegistered) {
            OmniJawsClient.get().removeObserver(appContext, this)
            isRegistered = false
        }
    }

    private fun handleQuicklookToggle() {
        if (quicklookEnabled) {
            maybeRegisterWeatherObserver()
            updateWeatherInfo()
        } else {
            unregisterWeatherObserver()
        }
    }

    companion object {
        @Volatile private var INSTANCE: WeatherManager? = null
        private lateinit var appContext: Context

        fun init(context: Context) {
            appContext = context.applicationContext
            get()
        }

        fun get(): WeatherManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: WeatherManager().also { INSTANCE = it }
            }
        }
    }
}
