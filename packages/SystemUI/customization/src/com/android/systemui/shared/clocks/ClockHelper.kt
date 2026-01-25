/*
 * Copyright (C) 2026 AxionOS
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

package com.android.systemui.shared.clocks

import android.content.Context
import android.net.Uri
import android.provider.Settings
import org.json.JSONObject

object ClockHelper {
    const val LOCK_SCREEN_CUSTOM_CLOCK_FACE = "lock_screen_custom_clock_face"

    @JvmField
    val clockFaceUri: Uri = Settings.Secure.getUriFor(LOCK_SCREEN_CUSTOM_CLOCK_FACE)

    @JvmStatic
    fun shouldCenterIcons(context: Context): Boolean {
        val clockFace = Settings.Secure.getString(
            context.contentResolver,
            LOCK_SCREEN_CUSTOM_CLOCK_FACE
        ) ?: return true

        return try {
            val json = JSONObject(clockFace)
            val clockId = json.optString("clockId", "")
            !isLeftAlignedClock(clockId)
        } catch (e: Exception) {
            true
        }
    }

    @JvmStatic
    fun isLeftAlignedClock(clockId: String): Boolean {
        return clockId.equals("GENERAL", ignoreCase = true) ||
               clockId.equals("OLD_QUICKLOOK", ignoreCase = true) ||
               clockId.equals("AXION_AGE", ignoreCase = true)
    }
}
