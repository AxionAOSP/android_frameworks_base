/*
 * Copyright (C) 2025-2026 AxionOS Project
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

package com.android.systemui.shared.clocks.view

import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.android.axion.compose.host.AxComposeView
import com.android.systemui.shared.clocks.ClockSettingsRepository
import kotlinx.coroutines.launch

class AxClockHost(private val clock: AxClockView) {

    private lateinit var composeView: AxComposeView

    fun attach(content: @Composable () -> Unit) {
        clock.setWillNotDraw(false)
        clock.clipChildren = false
        clock.clipToPadding = false
        clock.layoutDirection = View.LAYOUT_DIRECTION_LTR
        ClockSettingsRepository.init(clock.context)

        composeView = AxComposeView(clock.context).apply {
            setContent { Host { content() } }
        }

        try {
            clock.addView(composeView, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ))
        } catch (e: Exception) {
            Log.d("AxClockHost", "AxClockHost init failed error: $e")
        }
    }

    val view: AxComposeView get() = composeView

    @Composable
    private fun Host(content: @Composable () -> Unit) {
        val state = clock.state

        LaunchedEffect(Unit) {
            ClockSettingsRepository.isDateBelow.collect { state.dateBelowState.value = it }
        }
        LaunchedEffect(Unit) {
            ClockSettingsRepository.alignment.collect { state.alignmentState.value = it }
        }
        LaunchedEffect(Unit) {
            ClockSettingsRepository.clockColorOverride.collect { state.clockColorOverrideState.value = it }
        }

        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            val align = state.alignmentState.value
            val trigger by state.fidgetTrigger
            val dozeAmount by state.dozeAmountFlow.collectAsState()
            val configurationVersion = state.configurationVersion.intValue
            val density = LocalDensity.current.density
            val animationSpec = clock.animationSpec
            val fidgetSpec = animationSpec.fidget
            val aodSpec = animationSpec.aod
            val fidgetScaleX = remember { Animatable(1f) }
            val fidgetScaleY = remember { Animatable(1f) }
            val fidgetTranslationX = remember { Animatable(0f) }
            val fidgetTranslationY = remember { Animatable(0f) }
            val fidgetRotation = remember { Animatable(0f) }
            val fidgetAlpha = remember { Animatable(1f) }

            LaunchedEffect(trigger, fidgetSpec) {
                if (trigger == 0L || !fidgetSpec.enabled) {
                    fidgetScaleX.snapTo(1f)
                    fidgetScaleY.snapTo(1f)
                    fidgetTranslationX.snapTo(0f)
                    fidgetTranslationY.snapTo(0f)
                    fidgetRotation.snapTo(0f)
                    fidgetAlpha.snapTo(1f)
                    return@LaunchedEffect
                }
                launch {
                    fidgetScaleX.animateFidgetValue(1f, fidgetSpec.firstScaleX, fidgetSpec.secondScaleX, fidgetSpec)
                }
                launch {
                    fidgetScaleY.animateFidgetValue(1f, fidgetSpec.firstScaleY, fidgetSpec.secondScaleY, fidgetSpec)
                }
                launch {
                    fidgetTranslationX.animateFidgetValue(
                        0f,
                        fidgetSpec.firstTranslationXDp,
                        fidgetSpec.secondTranslationXDp,
                        fidgetSpec,
                    )
                }
                launch {
                    fidgetTranslationY.animateFidgetValue(
                        0f,
                        fidgetSpec.firstTranslationYDp,
                        fidgetSpec.secondTranslationYDp,
                        fidgetSpec,
                    )
                }
                launch {
                    fidgetRotation.animateFidgetValue(
                        0f,
                        fidgetSpec.firstRotationZ,
                        fidgetSpec.secondRotationZ,
                        fidgetSpec,
                    )
                }
                launch {
                    fidgetAlpha.animateFidgetValue(1f, fidgetSpec.firstAlpha, fidgetSpec.secondAlpha, fidgetSpec)
                }
            }

            val sizeModifier = if (clock.isLargeClock) {
                Modifier.fillMaxWidth().wrapContentHeight()
            } else {
                Modifier.fillMaxSize()
            }
            Box(
                modifier = sizeModifier
                    .graphicsLayer {
                        val amount = dozeAmount.coerceIn(0f, 1f)
                        val aodScaleX = lerp(1f, aodSpec.scaleX, amount)
                        val aodScaleY = lerp(1f, aodSpec.scaleY, amount)
                        val aodTranslationX = lerp(0f, aodSpec.translationXDp * density, amount)
                        val aodTranslationY = lerp(0f, aodSpec.translationYDp * density, amount)
                        val aodRotation = lerp(0f, aodSpec.rotationZ, amount)
                        val aodAlpha = lerp(1f, aodSpec.alpha, amount)
                        alpha = aodAlpha * fidgetAlpha.value
                        scaleX = aodScaleX * fidgetScaleX.value
                        scaleY = aodScaleY * fidgetScaleY.value
                        translationX = aodTranslationX + fidgetTranslationX.value * density
                        translationY = aodTranslationY + fidgetTranslationY.value * density
                        rotationZ = aodRotation + fidgetRotation.value
                        transformOrigin = when (align) {
                            ClockSettingsRepository.ALIGNMENT_LEFT -> TransformOrigin(0f, 0.5f)
                            ClockSettingsRepository.ALIGNMENT_RIGHT -> TransformOrigin(1f, 0.5f)
                            else -> TransformOrigin(0.5f, 0.5f)
                        }
                    }
            ) {
                key(configurationVersion) {
                    content()
                }
            }
        }
    }
}

private suspend fun Animatable<Float, AnimationVector1D>.animateFidgetValue(
    neutral: Float,
    first: Float,
    second: Float,
    spec: AxClockFidgetAnimationSpec,
) {
    snapTo(neutral)
    animateTo(first, tween(durationMillis = spec.phaseMs, easing = spec.easing))
    animateTo(second, tween(durationMillis = spec.phaseMs, easing = spec.easing))
    animateTo(neutral, tween(durationMillis = spec.settleMs, easing = spec.easing))
}

private fun lerp(start: Float, end: Float, fraction: Float): Float =
    start + (end - start) * fraction
