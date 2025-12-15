/*
 * Copyright (C) 2025 AxionOS
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
package com.android.systemui.media

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

data class MediaArtSettings(
    val isEnabled: Boolean = false,
    val blurLevel: Int = 100
)

@SysUISingleton
class MediaArtSettingsRepository @Inject constructor(
    private val secureSettings: SecureSettings,
    @Background private val backgroundDispatcher: CoroutineDispatcher
) {
    companion object {
        const val LS_MEDIA_ART_ENABLED = "ls_media_art_enabled"
        const val LS_MEDIA_ART_BLUR = "ls_media_art_blur"
        private const val DEFAULT_ENABLED = false
        private const val DEFAULT_BLUR = 0
    }

    val settingsFlow: Flow<MediaArtSettings> = secureSettings
        .observerFlow(
            LS_MEDIA_ART_ENABLED,
            LS_MEDIA_ART_BLUR
        )
        .onStart { emit(Unit) }
        .map { readSettings() }
        .distinctUntilChanged()
        .flowOn(backgroundDispatcher)

    private fun readSettings(): MediaArtSettings {
        return MediaArtSettings(
            isEnabled = secureSettings.getInt(LS_MEDIA_ART_ENABLED, if (DEFAULT_ENABLED) 1 else 0) == 1,
            blurLevel = secureSettings.getInt(LS_MEDIA_ART_BLUR, DEFAULT_BLUR)
        )
    }
}
