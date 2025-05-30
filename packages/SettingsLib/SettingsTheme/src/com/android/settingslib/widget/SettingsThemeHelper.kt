/*
* Copyright (C) 2024 The Android Open Source Project
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

package com.android.settingslib.widget

import android.content.Context
import android.os.Build

object SettingsThemeHelper {
    private const val IS_EXPRESSIVE_DESIGN_ENABLED = "is_expressive_design_enabled"
    private var expressiveThemeState: ExpressiveThemeState = ExpressiveThemeState.UNKNOWN

    enum class ExpressiveThemeState {
        UNKNOWN,
        ENABLED,
        DISABLED,
    }

    @JvmStatic
    fun isExpressiveTheme(context: Context): Boolean {
        tryInit(context)
        if (expressiveThemeState == ExpressiveThemeState.UNKNOWN) {
            throw Exception(
                "need to call com.android.settingslib.widget.SettingsThemeHelper.init(Context) first."
            )
        }

        return expressiveThemeState == ExpressiveThemeState.ENABLED
    }

    private fun tryInit(context: Context) {
        if (expressiveThemeState != ExpressiveThemeState.UNKNOWN) {
            return
        }

        expressiveThemeState = ExpressiveThemeState.ENABLED
    }
}
