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
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Vibrator
import android.util.Log
import android.view.LayoutInflater
import com.android.systemui.customization.R
import com.android.systemui.plugins.keyguard.ui.clocks.*

const val useAxClocks = true

class AxClockProvider(
    private val layoutInflater: LayoutInflater,
    private val resources: Resources,
    private val isClockReactiveVariantsEnabled: Boolean = false,
    private val vibrator: Vibrator?,
) : ClockProvider {

    private val tag = "AxClockProvider"
    private var messageBuffers: ClockMessageBuffers? = null

    init {
        Log.d(tag, "Initialized AxClockProvider")
    }

    override fun getClockPickerConfig(settings: ClockSettings): ClockPickerConfig {
        val resolvedType = resolveClockType(settings.clockId)
        val clockId = resources.getString(resolvedType.clockId)
        if (resolvedType == AxClockType.NONE) {
            return ClockPickerConfig(
                clockId,
                resources.getString(R.string.clock_none_name),
                resources.getString(R.string.clock_none_description),
                ColorDrawable(Color.TRANSPARENT),
                isReactiveToTone = false,
                axes = emptyList(),
                presetConfig = null,
            )
        }
        return ClockPickerConfig(
            clockId,
            resources.getString(resolvedType.pickerName),
            resources.getString(resolvedType.pickerDescription),
            resources.getDrawable(R.drawable.clock_default_thumbnail, null),
            isReactiveToTone = true,
            axes = emptyList(),
            presetConfig = null,
        )
    }

    override fun createClock(ctx: Context, settings: ClockSettings): AxClockController {
        return AxClockController(
            ctx,
            resolveClockType(settings.clockId),
            layoutInflater,
            messageBuffers,
        )
    }

    override fun getClocks(): List<ClockMetadata> {
        return pickerClockTypes.map { type ->
            val id = resources.getString(type.clockId)
            ClockMetadata(id)
        }
    }

    override fun initialize(buffers: ClockMessageBuffers?) {
        messageBuffers = buffers
    }

    private fun resolveClockType(clockId: String?): AxClockType {
        return AxClockType.resolve(resources, clockId)
    }

    private companion object {
        val pickerClockTypes = listOf(
            AxClockType.NTYPE,
            AxClockType.NDOT,
            AxClockType.GRAPHIC,
            AxClockType.GENERAL,
            AxClockType.LONDON_UG,
            AxClockType.OLD_QUICKLOOK,
            AxClockType.SPACE_AGE,
            AxClockType.POLYLINE,
            AxClockType.CYBERPUNK,
            AxClockType.AXION_AGE,
            AxClockType.SEGMENTS,
            AxClockType.OPLUS_CLASSIC,
            AxClockType.OPLUS_BIG,
            AxClockType.OPLUS_PLAYFUL,
            AxClockType.NONE,
        )
    }
}
