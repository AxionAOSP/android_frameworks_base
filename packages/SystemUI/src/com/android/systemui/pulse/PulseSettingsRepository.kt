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
package com.android.systemui.pulse

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.util.settings.SecureSettings
import com.android.systemui.util.settings.SettingsProxyExt.observerFlow
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import javax.inject.Inject

data class PulseSettings(
    val isEnabled: Boolean = false,
    val barCount: Int = 32,
    val roundedBars: Boolean = true,
    val colorMode: PulseColorMode = PulseColorMode.LAVALAMP,
    val style: PulseStyle = PulseStyle.BARS
)

@SysUISingleton
class PulseSettingsRepository @Inject constructor(
    private val secureSettings: SecureSettings,
    @Background private val backgroundDispatcher: CoroutineDispatcher
) {
    companion object {
        private const val PULSE_ENABLED = "visualizer_pulse_enabled"
        private const val PULSE_BAR_COUNT = "visualizer_pulse_bar_count"
        private const val PULSE_ROUNDED_BARS = "visualizer_pulse_rounded_bars_enabled"
        private const val PULSE_COLOR = "visualizer_pulse_color"
        private const val PULSE_VIEW_STYLE = "pulse_view_style"

        private const val DEFAULT_ENABLED = false
        private const val DEFAULT_BAR_COUNT = 32
        private const val DEFAULT_ROUNDED_BARS = true
        private const val DEFAULT_STYLE = 0
    }

    val settingsFlow: Flow<PulseSettings> = secureSettings
        .observerFlow(
            PULSE_ENABLED,
            PULSE_BAR_COUNT,
            PULSE_ROUNDED_BARS,
            PULSE_COLOR,
            PULSE_VIEW_STYLE
        )
        .onStart { emit(Unit) }
        .map { readSettings() }
        .distinctUntilChanged()
        .flowOn(backgroundDispatcher)

    private fun readSettings(): PulseSettings {
        return PulseSettings(
            isEnabled = secureSettings.getInt(PULSE_ENABLED, if (DEFAULT_ENABLED) 1 else 0) == 1,
            barCount = secureSettings.getInt(PULSE_BAR_COUNT, DEFAULT_BAR_COUNT).coerceIn(8, 64),
            roundedBars = secureSettings.getInt(PULSE_ROUNDED_BARS, if (DEFAULT_ROUNDED_BARS) 1 else 0) == 1,
            colorMode = PulseColorMode.fromKey(secureSettings.getString(PULSE_COLOR)),
            style = PulseStyle.fromId(secureSettings.getInt(PULSE_VIEW_STYLE, DEFAULT_STYLE))
        )
    }
}
