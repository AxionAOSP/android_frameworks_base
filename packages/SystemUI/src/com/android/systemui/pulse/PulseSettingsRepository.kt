/*
 * Copyright (C) 2025 The AxionAOSP Project
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
package com.android.systemui.pulse

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings

class PulseSettingsRepository(private val context: Context) {

    companion object {
        private const val PULSE_ENABLED = "visualizer_pulse_enabled"
        private const val PULSE_BAR_COUNT = "visualizer_pulse_bar_count"
        private const val PULSE_ROUNDED_BARS = "visualizer_pulse_rounded_bars_enabled"
        private const val PULSE_COLOR = "visualizer_pulse_color"

        private const val DEFAULT_ENABLED = false
        private const val DEFAULT_BAR_COUNT = 32
        private const val DEFAULT_ROUNDED_BARS = true
        private const val DEFAULT_COLOR = "lavalamp"
    }

    private val handler = Handler(Looper.getMainLooper())
    private var settingsObserver: SettingsObserver? = null
    private var onSettingsChangedListener: (() -> Unit)? = null

    private var cachedEnabled: Boolean? = null
    private var cachedBarCount: Int? = null
    private var cachedRoundedBars: Boolean? = null
    private var cachedColorMode: String? = null

    fun startObserving() {
        if (settingsObserver != null) return

        settingsObserver = SettingsObserver(handler) { invalidateCache() }

        listOf(
            Settings.Secure.getUriFor(PULSE_ENABLED),
            Settings.Secure.getUriFor(PULSE_BAR_COUNT),
            Settings.Secure.getUriFor(PULSE_ROUNDED_BARS),
            Settings.Secure.getUriFor(PULSE_COLOR)
        ).forEach { uri ->
            context.contentResolver.registerContentObserver(uri, false, settingsObserver!!)
        }
    }

    fun stopObserving() {
        settingsObserver?.let {
            context.contentResolver.unregisterContentObserver(it)
            settingsObserver = null
        }
    }

    fun setOnSettingsChangedListener(listener: () -> Unit) {
        onSettingsChangedListener = listener
    }

    fun isPulseEnabled(): Boolean {
        if (cachedEnabled == null) {
            cachedEnabled = getSecureSetting(PULSE_ENABLED, DEFAULT_ENABLED)
        }
        return cachedEnabled!!
    }

    fun getBarCount(): Int {
        if (cachedBarCount == null) {
            cachedBarCount = getSecureSetting(PULSE_BAR_COUNT, DEFAULT_BAR_COUNT).coerceIn(8, 64)
        }
        return cachedBarCount!!
    }

    fun isRoundedBarsEnabled(): Boolean {
        if (cachedRoundedBars == null) {
            cachedRoundedBars = getSecureSetting(PULSE_ROUNDED_BARS, DEFAULT_ROUNDED_BARS)
        }
        return cachedRoundedBars!!
    }

    fun getColorMode(): String {
        if (cachedColorMode == null) {
            cachedColorMode = getSecureStringSetting(PULSE_COLOR, DEFAULT_COLOR)
        }
        return cachedColorMode!!
    }

    private fun invalidateCache() {
        cachedEnabled = null
        cachedBarCount = null
        cachedRoundedBars = null
        cachedColorMode = null
        onSettingsChangedListener?.invoke()
    }

    private fun getSecureSetting(key: String, defaultValue: Boolean): Boolean {
        return Settings.Secure.getInt(context.contentResolver, key, if (defaultValue) 1 else 0) == 1
    }

    private fun getSecureSetting(key: String, defaultValue: Int): Int {
        return Settings.Secure.getInt(context.contentResolver, key, defaultValue)
    }

    private fun getSecureStringSetting(key: String, defaultValue: String): String {
        return Settings.Secure.getString(context.contentResolver, key) ?: defaultValue
    }

    private inner class SettingsObserver(
        handler: Handler,
        private val onChange: () -> Unit
    ) : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            super.onChange(selfChange, uri)
            onChange()
        }
    }
}
