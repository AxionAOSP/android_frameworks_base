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

import android.animation.ValueAnimator
import android.util.Log
import androidx.compose.ui.geometry.Offset

fun AxClockView.animateAppear() {
    Log.d(tag, "animateAppear")
    val spec = animationSpec.appear
    if (!spec.enabled || isPreviewMode) {
        animAlpha = 1f
        scaleX = 1f
        scaleY = 1f
        translationY = 0f
        return
    }
    val startTranslationY = spec.startTranslationYDp * resources.displayMetrics.density
    animAlpha = spec.startAlpha
    scaleX = spec.startScaleX
    scaleY = spec.startScaleY
    translationY = startTranslationY
    ValueAnimator.ofFloat(0f, 1f).apply {
        duration = spec.durationMs
        interpolator = spec.interpolator
        addUpdateListener {
            val fraction = it.animatedValue as Float
            animAlpha = lerp(spec.startAlpha, 1f, fraction)
            scaleX = lerp(spec.startScaleX, 1f, fraction)
            scaleY = lerp(spec.startScaleY, 1f, fraction)
            translationY = lerp(startTranslationY, 0f, fraction)
        }
        start()
    }
}

fun AxClockView.animateFidget(x: Float, y: Float) {
    Log.d(tag, "animateFidget")
    if (isPreviewMode) return
    state.fidgetPosition.value = Offset(x, y)
    state.fidgetTrigger.value = System.currentTimeMillis()
    onFidgetAnimation()
}

fun AxClockView.setAodFraction(fraction: Float) {
    if (isPreviewMode) return
    state.dozeAmountFlow.value = fraction.coerceIn(0f, 1f)
}

fun AxClockView.animateCharge() {
    Log.d(tag, "animateCharge")
    if (isPreviewMode) return
    animateFidget(width / 2f, height / 2f)
    onChargeAnimation()
}

private fun lerp(start: Float, end: Float, fraction: Float): Float =
    start + (end - start) * fraction
