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
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings

data class WidgetSettings(
    val value: String,
    val isEnabled: Boolean
)

class LockscreenWidgetSettingsRepository(
    private val context: Context,
    private val controller: LockScreenWidgetsController
) {
    private val contentResolver: ContentResolver = context.contentResolver
    private val handler = Handler(Looper.getMainLooper())
    private var observing = false

    private val SETTINGS_URI: Uri =
        Settings.System.getUriFor("lockscreen_widgets_extras")
    private val ENABLED_URI: Uri =
        Settings.System.getUriFor("lockscreen_widgets_enabled")

    val settings: WidgetSettings
        get() {
            val value = Settings.System.getStringForUser(
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
            return WidgetSettings(value, isEnabled)
        }

    private var observer: ContentObserver = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                controller.settings = settings
            }
        }

    fun observe() {
        if (observing) return
        contentResolver.registerContentObserver(
            SETTINGS_URI, false, observer, UserHandle.USER_CURRENT
        )
        contentResolver.registerContentObserver(
            ENABLED_URI, false, observer, UserHandle.USER_CURRENT
        )
        controller.settings = settings
        observing = true
    }

    fun dispose() {
        if (!observing) return
        contentResolver.unregisterContentObserver(observer)
        observing = false
    }
}
