/*
 * Copyright (C) 2025-2026 Axion OS
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

package com.android.systemui.axion.volume.repository

import android.content.Context
import android.provider.Settings
import com.android.systemui.axion.volume.dagger.AxionVolumeScope
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.shared.settings.data.repository.SecureSettingsRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

interface AxionCaptionsRepository {
    val captionsEnabledFlow: Flow<Boolean>
    val captionsAvailableFlow: Flow<Boolean>
    fun setCaptionsEnabled(enabled: Boolean)
    fun isCaptionsEnabled(): Boolean
    fun isCaptionsAvailable(): Boolean
}

@AxionVolumeScope
class AxionCaptionsRepositoryImpl @Inject constructor(
    @Application private val context: Context,
    private val secureSettingsRepository: SecureSettingsRepository,
) : AxionCaptionsRepository {

    companion object {
        private const val GOOGLE_ASI_PACKAGE = "com.google.android.as"
    }

    override val captionsEnabledFlow: Flow<Boolean> =
        secureSettingsRepository
            .intSetting(Settings.Secure.ODI_CAPTIONS_ENABLED, 0)
            .map { it == 1 }

    override val captionsAvailableFlow: Flow<Boolean> =
        secureSettingsRepository
            .intSetting(Settings.Secure.ODI_CAPTIONS_VOLUME_UI_ENABLED, 0)
            .map { it == 1 && isGoogleAsiInstalled() }

    override fun isCaptionsEnabled(): Boolean {
        return Settings.Secure.getInt(
            context.contentResolver,
            Settings.Secure.ODI_CAPTIONS_ENABLED,
            0
        ) == 1
    }

    override fun setCaptionsEnabled(enabled: Boolean) {
        Settings.Secure.putInt(
            context.contentResolver,
            Settings.Secure.ODI_CAPTIONS_ENABLED,
            if (enabled) 1 else 0
        )
    }

    override fun isCaptionsAvailable(): Boolean {
        val isVolumeUiEnabled = Settings.Secure.getInt(
            context.contentResolver,
            Settings.Secure.ODI_CAPTIONS_VOLUME_UI_ENABLED,
            0
        ) == 1
        return isGoogleAsiInstalled() && isVolumeUiEnabled
    }

    private fun isGoogleAsiInstalled(): Boolean = try {
        context.packageManager.getPackageInfo(GOOGLE_ASI_PACKAGE, 0)
        true
    } catch (e: Exception) {
        false
    }
}
