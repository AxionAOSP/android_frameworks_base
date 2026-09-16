/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.systemui.keyguard.ui.transitions

import android.content.Context
import android.content.res.Resources
import android.os.UserHandle
import com.android.axion.blur.AxBlurConfig
import com.android.systemui.util.settings.SecureSettings
import com.android.systemui.util.settings.SettingsProxyExt.observerFlow
import javax.inject.Inject
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/** Config that provides the max and min blur radius for the window blurs. */
class BlurConfig(
    val minBlurRadiusPx: Float,
    private val defaultMaxBlurRadiusPx: Float,
    private val secureSettings: SecureSettings?,
    private val resources: Resources? = null,
) {
    // No-op config that will be used by dagger of other SysUI variants which don't blur the
    // background surface.
    @Inject constructor() : this(0.0f, 0.0f, null, null)

    constructor(minBlurRadiusPx: Float, maxBlurRadiusPx: Float) :
        this(minBlurRadiusPx, maxBlurRadiusPx, null, null)

    constructor(
        minBlurRadiusPx: Float,
        defaultMaxBlurRadiusPx: Float,
        secureSettings: SecureSettings?,
    ) : this(minBlurRadiusPx, defaultMaxBlurRadiusPx, secureSettings, null)

    constructor(resources: Resources, secureSettings: SecureSettings?) :
        this(0.0f, AxBlurConfig.getMaxBlurRadiusPx(resources), secureSettings, resources)

    constructor(context: Context, secureSettings: SecureSettings?) :
        this(context.resources, secureSettings)

    private val baseMaxRadius: Float
        get() = resources?.let { AxBlurConfig.getMaxBlurRadiusPx(it) } ?: defaultMaxBlurRadiusPx

    val maxBlurRadiusPx: Float
        get() {
            val base = baseMaxRadius
            val percent = readBlurPercent() ?: return base
            if (!percent.isFinite()) return base
            val factor = percent.coerceIn(MIN_BLUR_RADIUS_PCT, MAX_BLUR_RADIUS_PCT) / MAX_BLUR_RADIUS_PCT
            return base * factor
        }

    private fun readBlurPercent(): Float? =
        secureSettings?.getFloatForUser(KEY_BLUR_RADIUS_PCT, DEFAULT_BLUR_RADIUS_PCT, UserHandle.USER_CURRENT)

    val maxBlurRadiusFlow: Flow<Float> = secureSettings?.let { settings ->
        settings.observerFlow(KEY_BLUR_RADIUS_PCT)
            .onStart { emit(Unit) }
            .map { maxBlurRadiusPx }
            .distinctUntilChanged()
    } ?: emptyFlow()

    companion object {
        const val MAX_BLUR_RADIUS_PX = AxBlurConfig.BASE_MAX_BLUR_RADIUS_PX
        const val KEY_BLUR_RADIUS_PCT = AxBlurConfig.KEY_SYSTEM_BLUR_RADIUS_PCT
        private const val MIN_BLUR_RADIUS_PCT = AxBlurConfig.MIN_BLUR_RADIUS_PCT
        private const val MAX_BLUR_RADIUS_PCT = AxBlurConfig.MAX_BLUR_RADIUS_PCT
        private const val DEFAULT_BLUR_RADIUS_PCT = AxBlurConfig.DEFAULT_BLUR_RADIUS_PCT
    }
}
