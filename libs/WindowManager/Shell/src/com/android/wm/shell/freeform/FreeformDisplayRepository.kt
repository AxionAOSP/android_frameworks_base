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
package com.android.wm.shell.freeform

import android.content.Context
import android.content.res.Configuration
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.SystemProperties
import android.util.DisplayMetrics
import android.view.Display

object FreeformDisplayRepository {

    private var context: Context? = null
    private var displayMetrics: DisplayMetrics? = null

    fun init(appContext: Context) {
        context = appContext.applicationContext
        updateMetrics()
    }

    fun onConfigurationChanged(newConfiguration: Configuration) {
        updateMetrics()
    }

    val freeformDensity: Int
        get() = SystemProperties.getInt("persist.wm.debug.desktop_mode_density", 284)

    private val display: Display
        get() = requireNotNull(context) {
            "FreeformDisplayRepository not initialized. Call init(context) first."
        }.getSystemService(DisplayManager::class.java)
            .getDisplay(Display.DEFAULT_DISPLAY)
            ?: error("Default display not found")

    private fun updateMetrics() {
        val dm = DisplayMetrics()
        display.getRealMetrics(dm)
        displayMetrics = dm
    }

    private val metrics: DisplayMetrics
        get() = displayMetrics ?: run {
            updateMetrics()
            displayMetrics!!
        }

    val widthPixels: Int
        get() = metrics.widthPixels

    val heightPixels: Int
        get() = metrics.heightPixels

    val density: Float
        get() = metrics.density

    val densityDpi: Int
        get() = metrics.densityDpi

    val defaultBounds: Rect
        get() {
            val px500 = (500 * density).toInt()
            val centerX = widthPixels / 2
            val centerY = heightPixels / 2
            return Rect(
                centerX - px500 / 2,
                centerY - px500 / 2,
                centerX + px500 / 2,
                centerY + px500 / 2
            )
        }
}
