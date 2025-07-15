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
import android.graphics.Rect
import android.view.View
import com.android.systemui.log.core.MessageBuffer
import com.android.systemui.plugins.clocks.*
import com.android.systemui.shared.clocks.view.NTClockView

class NTClockFaceController(
    context: Context,
    override val view: NTClockView,
    clockViewId: String,
    clockTickRate: ClockTickRate = ClockTickRate.PER_MINUTE,
    messageBuffer: MessageBuffer? = null
) : ClockFaceController {

    override var animations: ClockAnimations = NTClockAnimations(view, 0.0f, 0.0f)
        internal set

    override val config: ClockFaceConfig =
        ClockFaceConfig(clockTickRate, false, false, false)

    override var theme = ThemeConfig(true, null)

    override val events =
        object : ClockFaceEvents {
            override fun onTimeTick() = view.refreshTime()

            override fun onThemeChanged(theme: ThemeConfig) {
            }

            override fun onTargetRegionChanged(targetRegion: Rect?) {
            }

            override fun onFontSettingChanged(fontSizePx: Float) {
                view.onFontSettingChanged()
            }

            override fun onSecondaryDisplayChanged(onSecondaryDisplay: Boolean) {}
            
            override fun onDozeChanged(dozing: Boolean) {
                view.onDozeChanged(dozing)
            }

            override fun onScreenOff(screenOff: Boolean) {
                view.onScreenOff(screenOff)
            }
            
            override fun onRegionDarknessChanged(isRegionDark: Boolean) {
                view.onRegionDarknessChanged(isRegionDark)
            }

            override fun onStartedWakingUp() {
                view.onStartedWakingUp()
            }
        }

    override val layout =
        DefaultClockFaceLayout(view).apply {
            val resourceId = context.resources.getIdentifier(
                clockViewId, "id", context.packageName
            )
            views[0].id = resourceId
        }
}
