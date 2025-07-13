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
package com.android.systemui.lockscreen

import android.content.ContentResolver
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.Dispatchers

data class WidgetSettings(
    val settings: String,
    val isEnabled: Boolean
)

class LockscreenWidgetSettingsRepository(
    context: Context
) {
    private val contentResolver: ContentResolver = context.contentResolver

    val widgetSettingsFlow: Flow<WidgetSettings> = callbackFlow {
        val handler = Handler(Looper.getMainLooper())

        val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                trySend(getCurrentSettings())
            }
        }

        val extrasUri = Settings.System.getUriFor("lockscreen_widgets_extras")
        val enabledUri = Settings.System.getUriFor("lockscreen_widgets_enabled")

        contentResolver.registerContentObserver(extrasUri, false, observer, UserHandle.USER_CURRENT)
        contentResolver.registerContentObserver(enabledUri, false, observer, UserHandle.USER_CURRENT)

        trySend(getCurrentSettings())

        awaitClose {
            contentResolver.unregisterContentObserver(observer)
        }
    }
    .distinctUntilChanged()
    .debounce(500)
    .flowOn(Dispatchers.IO)

    private fun getCurrentSettings(): WidgetSettings {
        val settings = Settings.System.getStringForUser(
            contentResolver,
            "lockscreen_widgets_extras",
            UserHandle.USER_CURRENT
        ) ?: ""
        val isEnabled = Settings.System.getIntForUser(
            contentResolver,
            "lockscreen_widgets_enabled",
            0,
            UserHandle.USER_CURRENT
        ) == 1
        return WidgetSettings(settings, isEnabled)
    }
}
