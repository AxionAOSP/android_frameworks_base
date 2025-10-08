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
import android.graphics.drawable.Drawable
import android.os.Vibrator
import android.util.Log
import android.view.LayoutInflater
import com.android.systemui.customization.R
import com.android.systemui.plugins.clocks.*

class NTClockProvider(
    private val context: Context,
    private val layoutInflater: LayoutInflater,
    private val resources: Resources,
    private val isClockReactiveVariantsEnabled: Boolean = false,
    private val vibrator: Vibrator?,
) : ClockProvider {

    private val tag = "NTClockProvider"
    private var messageBuffers: ClockMessageBuffers? = null

    init {
        Log.d(tag, "Initialized NTClockProvider")
    }

    override fun getClockPickerConfig(settings: ClockSettings): ClockPickerConfig {
        return ClockPickerConfig(
                settings.clockId ?: "NTYPE",
                resources.getString(R.string.clock_id_general),
                resources.getString(R.string.clock_id_general),
                resources.getDrawable(R.drawable.clock_default_thumbnail, null),
                isReactiveToTone = true,
                axes = emptyList(),
                presetConfig = null,
            )
    }

    override fun createClock(settings: ClockSettings): NTClockController {
        val clockId = settings.clockId
        val resolvedType = NTClockType.values().firstOrNull {
            clockId == context.getString(it.clockId)
        } ?: NTClockType.NTYPE

        return NTClockController(context, resolvedType, layoutInflater, messageBuffers)
    }

    override fun getClocks(): List<ClockMetadata> {
        val availableTypes = buildList {
            add(NTClockType.NTYPE)
            add(NTClockType.NDOT)
            add(NTClockType.GRAPHIC)
            add(NTClockType.GENERAL)
            add(NTClockType.LONDON_UG)
            add(NTClockType.OLD_QUICKLOOK)
            add(NTClockType.SPACE_AGE)
            add(NTClockType.POLYLINE)
        }

        return availableTypes.map { type ->
            val id = context.getString(type.clockId)
            ClockMetadata(id)
        }
    }

    override fun initialize(buffers: ClockMessageBuffers?) {
        messageBuffers = buffers
    }
}
