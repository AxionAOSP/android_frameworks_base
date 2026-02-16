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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.android.axion.compose.host.AxComposeView
import com.android.systemui.shared.clocks.ClockSettingsRepository

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
            val isDoze by state.dozeFlow.collectAsState()
            val animScale = remember { Animatable(1f) }
            var initialDoze by remember { mutableStateOf(true) }

            LaunchedEffect(trigger) {
                if (trigger == 0L || clock.useGlitchInteraction) return@LaunchedEffect
                animScale.snapTo(1f)
                animScale.animateTo(COMPOSE_FIDGET_SQUEEZE, tween(COMPOSE_FIDGET_PHASE_MS, easing = COMPOSE_FIDGET_EASING))
                animScale.animateTo(COMPOSE_FIDGET_EXPAND, tween(COMPOSE_FIDGET_PHASE_MS, easing = COMPOSE_FIDGET_EASING))
                animScale.animateTo(1f, tween(COMPOSE_FIDGET_SETTLE_MS, easing = COMPOSE_FIDGET_EASING))
            }

            LaunchedEffect(isDoze) {
                if (initialDoze) { initialDoze = false; return@LaunchedEffect }
                if (!isDoze) {
                    animScale.snapTo(DOZE_WAKE_START)
                    animScale.animateTo(1f, tween(DOZE_WAKE_MS, easing = DOZE_EASING))
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
                        val s = clock.sizeScale
                        val a = animScale.value
                        scaleX = a
                        scaleY = s * a
                        when (align) {
                            ClockSettingsRepository.ALIGNMENT_LEFT ->
                                transformOrigin = TransformOrigin(0f, 0.5f)
                            ClockSettingsRepository.ALIGNMENT_RIGHT ->
                                transformOrigin = TransformOrigin(1f, 0.5f)
                        }
                    }
            ) {
                content()
            }
        }
    }
}
