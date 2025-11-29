/*
 * Copyright (C) 2025 Axion OS
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
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.android.systemui.axion.volume.dagger.AxionVolumeScope
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn

interface AxionCaptionsRepository {
    val captionsEnabledFlow: Flow<Boolean>
    fun setCaptionsEnabled(enabled: Boolean)
    fun isCaptionsEnabled(): Boolean
    fun isCaptionsAvailable(): Boolean
}

@AxionVolumeScope
class AxionCaptionsRepositoryImpl @Inject constructor(
    @Application private val context: Context,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
) : AxionCaptionsRepository {

    private val handler = Handler(Looper.getMainLooper())
    
    companion object {
        private const val GOOGLE_ASI_PACKAGE = "com.google.android.as"
    }

    override val captionsEnabledFlow: Flow<Boolean> = callbackFlow {
        val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                trySend(isCaptionsEnabled())
            }
        }

        context.contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.ODI_CAPTIONS_ENABLED),
            false,
            observer
        )

        trySend(isCaptionsEnabled())

        awaitClose {
            context.contentResolver.unregisterContentObserver(observer)
        }
    }
        .distinctUntilChanged()
        .flowOn(backgroundDispatcher)

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
        val isPackageInstalled = try {
            context.packageManager.getPackageInfo(GOOGLE_ASI_PACKAGE, 0)
            true
        } catch (e: Exception) {
            false
        }
        
        val isVolumeUiEnabled = Settings.Secure.getInt(
            context.contentResolver,
            Settings.Secure.ODI_CAPTIONS_VOLUME_UI_ENABLED,
            0
        ) == 1
        
        return isPackageInstalled && isVolumeUiEnabled
    }
}
