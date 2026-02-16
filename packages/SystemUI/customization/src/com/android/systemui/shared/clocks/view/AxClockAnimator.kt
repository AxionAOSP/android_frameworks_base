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
import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.compose.ui.geometry.Offset
import com.android.app.animation.Interpolators

fun AxClockView.animateAppear() {
    Log.d(tag, "animateAppear")
    animAlpha = 0f
    ValueAnimator.ofFloat(0f, 1f).apply {
        duration = APPEAR_DURATION
        interpolator = Interpolators.EMPHASIZED_DECELERATE
        addUpdateListener { animAlpha = it.animatedValue as Float }
        start()
    }
}

fun AxClockView.animateCharge() {
    Log.d(tag, "animateCharge")
    if (isPreviewMode) return
    val cx = width / 2f
    val cy = height / 2f
    state.fidgetPosition.value = Offset(cx, cy)
    state.fidgetTrigger.value = System.currentTimeMillis()
    onChargeAnimation()
}

fun AxClockView.animateFidgetTapDefault(x: Float, y: Float) {
    state.fidgetPosition.value = Offset(x, y)
    state.fidgetTrigger.value = System.currentTimeMillis()
    val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    if (useGlitchInteraction) {
        vibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
    } else {
        vibrator?.vibrate(FIDGET_HAPTICS)
    }
}
