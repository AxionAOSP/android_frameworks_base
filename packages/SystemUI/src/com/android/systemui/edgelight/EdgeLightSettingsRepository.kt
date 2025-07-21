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
package com.android.systemui.edgelight

import android.graphics.Color
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

data class EdgeLightSettings(
    val isEnabled: Boolean = false,
    val colorMode: String = "default",
    val customColor: Int = Color.WHITE
)

@SysUISingleton
class EdgeLightSettingsRepository @Inject constructor(
    private val secureSettings: SecureSettings,
    @Background private val backgroundDispatcher: CoroutineDispatcher
) {
    companion object {
        private const val SETTING_ENABLED = "edge_light_enabled"
        private const val SETTING_COLOR_MODE = "edge_light_color_mode"
        private const val SETTING_CUSTOM_COLOR = "edge_light_custom_color"

        private const val DEFAULT_ENABLED = false
        private const val DEFAULT_COLOR_MODE = "default"
        private const val DEFAULT_CUSTOM_COLOR = Color.WHITE
    }

    val settingsFlow: Flow<EdgeLightSettings> = secureSettings
        .observerFlow(
            SETTING_ENABLED,
            SETTING_COLOR_MODE,
            SETTING_CUSTOM_COLOR
        )
        .onStart { emit(Unit) }
        .map { readSettings() }
        .distinctUntilChanged()
        .flowOn(backgroundDispatcher)

    fun currentSettings(): EdgeLightSettings = readSettings()

    private fun readSettings(): EdgeLightSettings {
        return EdgeLightSettings(
            isEnabled = secureSettings.getInt(SETTING_ENABLED, if (DEFAULT_ENABLED) 1 else 0) == 1,
            colorMode = secureSettings.getString(SETTING_COLOR_MODE) ?: DEFAULT_COLOR_MODE,
            customColor = secureSettings.getInt(SETTING_CUSTOM_COLOR, DEFAULT_CUSTOM_COLOR)
        )
    }
}
