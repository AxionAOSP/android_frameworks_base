/*
 * Copyright (C) 2025 AxionOS Project
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
import android.content.res.Resources
import android.view.LayoutInflater
import android.view.ViewGroup
import android.util.Log
import android.widget.FrameLayout
import com.android.systemui.plugins.keyguard.ui.clocks.*
import com.android.systemui.shared.clocks.view.BitmapDigitComposeClockView
import com.android.systemui.shared.clocks.view.AxClockView
import com.android.systemui.shared.clocks.view.BitmapFaceConfig
import java.io.PrintWriter

class AxClockController @JvmOverloads constructor(
    context: Context,
    clockType: AxClockType,
    layoutInflater: LayoutInflater,
    clockMessageBuffers: ClockMessageBuffers? = null,
    val pluginContext: Context? = null,
    val customConfig: BitmapFaceConfig? = null
) : ClockController {
    private val TAG = "AxClockController"

    override val smallClock: AxClockFaceController
    override val largeClock: AxClockFaceController
    override val eventListeners = ClockEventListeners()
    override val events: ClockEvents
    override val config: ClockConfig

    init {
        val container = FrameLayout(context)

        val tickRate = if (clockType.bitmapFaceStyle?.needsPerSecondTick == true) {
            ClockTickRate.PER_SECOND
        } else {
            ClockTickRate.PER_MINUTE
        }

        val smallClockView = layoutInflater.inflate(clockType.viewId, container, false) as AxClockView
        smallClockView.pluginContext = pluginContext
        customConfig?.style?.let { (smallClockView as? BitmapDigitComposeClockView)?.faceStyle = it }
        clockType.bitmapFaceStyle?.let { style ->
            (smallClockView as? BitmapDigitComposeClockView)?.faceStyle = style
        }

        smallClock = AxClockFaceController(
            context,
            smallClockView,
            "lockscreen_clock_view",
            tickRate,
            clockMessageBuffers?.smallClockMessageBuffer,
            isLargeClock = false
        )

        val largeClockView = layoutInflater.inflate(clockType.largeViewId, container, false) as AxClockView
        largeClockView.isLargeClock = true
        largeClockView.pluginContext = pluginContext
        customConfig?.style?.let { (largeClockView as? BitmapDigitComposeClockView)?.faceStyle = it }
        clockType.bitmapFaceStyle?.let { style ->
            (largeClockView as? BitmapDigitComposeClockView)?.faceStyle = style
        }

        largeClock = AxClockFaceController(
            context,
            largeClockView,
            "lockscreen_clock_view_large",
            tickRate,
            clockMessageBuffers?.largeClockMessageBuffer,
            isLargeClock = true
        )

        events = AxClockEvents(smallClockView, largeClockView)

        val clockName = context.getString(clockType.clockId)

        config = ClockConfig(clockName, "", "", false, false)

        Log.d(TAG, "init")
    }

    override fun dump(pw: PrintWriter) {}

    override fun initialize(isDarkTheme: Boolean, dozeFraction: Float, foldFraction: Float) {
        smallClock.animations = AxClockAnimations(smallClock.view, dozeFraction, foldFraction)
        largeClock.animations = AxClockAnimations(largeClock.view, dozeFraction, foldFraction)

        events.onUiModeChanged(isDarkTheme)

        smallClock.events.onTimeTick()
        largeClock.events.onTimeTick()

        Log.d(TAG, "initialize")
    }
}
