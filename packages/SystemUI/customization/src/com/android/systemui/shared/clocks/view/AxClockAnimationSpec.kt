/*
 * Copyright (C) 2026 AxionOS Project
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

import android.animation.TimeInterpolator
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import com.android.app.animation.Interpolators

data class AxClockAnimationSpec(
    val appear: AxClockAppearAnimationSpec = AxClockAppearAnimationSpec(),
    val fidget: AxClockFidgetAnimationSpec = AxClockFidgetAnimationSpec(),
    val aod: AxClockAodAnimationSpec = AxClockAodAnimationSpec(),
)

data class AxClockAppearAnimationSpec(
    val enabled: Boolean = true,
    val startAlpha: Float = 0f,
    val startScaleX: Float = 0.96f,
    val startScaleY: Float = 0.96f,
    val startTranslationYDp: Float = 16f,
    val durationMs: Long = 420L,
    val interpolator: TimeInterpolator = Interpolators.EMPHASIZED_DECELERATE,
)

data class AxClockFidgetAnimationSpec(
    val enabled: Boolean = true,
    val firstScaleX: Float = 0.96f,
    val firstScaleY: Float = 0.96f,
    val firstTranslationXDp: Float = 0f,
    val firstTranslationYDp: Float = 0f,
    val firstRotationZ: Float = 0f,
    val firstAlpha: Float = 1f,
    val secondScaleX: Float = 1.03f,
    val secondScaleY: Float = 1.03f,
    val secondTranslationXDp: Float = 0f,
    val secondTranslationYDp: Float = 0f,
    val secondRotationZ: Float = 0f,
    val secondAlpha: Float = 1f,
    val phaseMs: Int = 120,
    val settleMs: Int = 150,
    val easing: Easing = CubicBezierEasing(0.26873f, 0f, 0.45042f, 1f),
)

data class AxClockAodAnimationSpec(
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val translationXDp: Float = 0f,
    val translationYDp: Float = 0f,
    val rotationZ: Float = 0f,
    val alpha: Float = 1f,
)

object AxClockAnimationSpecs {
    val None = AxClockAnimationSpec(
        appear = AxClockAppearAnimationSpec(enabled = false),
        fidget = AxClockFidgetAnimationSpec(enabled = false),
    )

    val Default = AxClockAnimationSpec()

    val General = AxClockAnimationSpec(
        appear = AxClockAppearAnimationSpec(startScaleX = 0.94f, startScaleY = 0.94f, startTranslationYDp = 18f),
        fidget = AxClockFidgetAnimationSpec(
            firstScaleX = 0.97f,
            firstScaleY = 0.94f,
            secondScaleX = 1.02f,
            secondScaleY = 1.04f,
        ),
        aod = AxClockAodAnimationSpec(scaleX = 0.99f, scaleY = 0.98f, translationYDp = -2f, alpha = 0.96f),
    )

    val NType = AxClockAnimationSpec(
        appear = AxClockAppearAnimationSpec(startScaleX = 0.92f, startScaleY = 0.98f, startTranslationYDp = 10f),
        fidget = AxClockFidgetAnimationSpec(
            firstScaleX = 0.94f,
            firstScaleY = 1.02f,
            secondScaleX = 1.04f,
            secondScaleY = 0.98f,
        ),
        aod = AxClockAodAnimationSpec(scaleX = 0.99f, scaleY = 0.96f, translationYDp = -3f, alpha = 0.95f),
    )

    val NDot = AxClockAnimationSpec(
        appear = AxClockAppearAnimationSpec(
            startAlpha = 0.2f,
            startScaleX = 0.9f,
            startScaleY = 0.9f,
            startTranslationYDp = 6f,
        ),
        fidget = AxClockFidgetAnimationSpec(
            firstScaleX = 0.98f,
            firstScaleY = 0.98f,
            firstAlpha = 0.82f,
            secondScaleX = 1.04f,
            secondScaleY = 1.04f,
        ),
        aod = AxClockAodAnimationSpec(scaleX = 0.98f, scaleY = 0.98f, alpha = 0.92f),
    )

    val LondonUnderground = AxClockAnimationSpec(
        appear = AxClockAppearAnimationSpec(startScaleX = 0.98f, startScaleY = 0.98f, startTranslationYDp = 20f),
        fidget = AxClockFidgetAnimationSpec(
            firstScaleX = 0.98f,
            firstScaleY = 0.98f,
            firstTranslationXDp = -2f,
            firstRotationZ = -1.5f,
            secondScaleX = 1.02f,
            secondScaleY = 1.02f,
            secondTranslationXDp = 2f,
            secondRotationZ = 1.5f,
        ),
        aod = AxClockAodAnimationSpec(scaleX = 0.98f, scaleY = 0.98f, translationYDp = -2f, alpha = 0.94f),
    )

    val SpaceAge = AxClockAnimationSpec(
        appear = AxClockAppearAnimationSpec(startScaleX = 0.88f, startScaleY = 1.02f, startTranslationYDp = 4f),
        fidget = AxClockFidgetAnimationSpec(
            firstScaleX = 1.05f,
            firstScaleY = 0.96f,
            firstTranslationXDp = -3f,
            secondScaleX = 0.98f,
            secondScaleY = 1.02f,
            secondTranslationXDp = 3f,
        ),
        aod = AxClockAodAnimationSpec(scaleX = 0.97f, scaleY = 1f, translationXDp = 2f, alpha = 0.96f),
    )

    val Polyline = AxClockAnimationSpec(
        appear = AxClockAppearAnimationSpec(
            startAlpha = 0.15f,
            startScaleX = 1.03f,
            startScaleY = 1.03f,
            startTranslationYDp = 12f,
        ),
        fidget = AxClockFidgetAnimationSpec(
            firstScaleX = 0.98f,
            firstScaleY = 0.98f,
            firstRotationZ = -2f,
            secondScaleX = 1.02f,
            secondScaleY = 1.02f,
            secondRotationZ = 2f,
        ),
        aod = AxClockAodAnimationSpec(scaleX = 0.99f, scaleY = 0.99f, rotationZ = -0.4f, alpha = 0.9f),
    )

    val Segments = AxClockAnimationSpec(
        appear = AxClockAppearAnimationSpec(startScaleX = 0.94f, startScaleY = 0.9f, startTranslationYDp = 10f),
        fidget = AxClockFidgetAnimationSpec(enabled = false),
        aod = AxClockAodAnimationSpec(scaleX = 0.98f, scaleY = 0.96f, translationYDp = -2f, alpha = 0.94f),
    )

    val Graphic = AxClockAnimationSpec(
        appear = AxClockAppearAnimationSpec(
            startScaleX = 0.9f,
            startScaleY = 0.9f,
            startTranslationYDp = 14f,
            durationMs = 520L,
        ),
        fidget = AxClockFidgetAnimationSpec(
            firstScaleX = 0.97f,
            firstScaleY = 0.97f,
            firstRotationZ = -3f,
            secondScaleX = 1.02f,
            secondScaleY = 1.02f,
            secondRotationZ = 3f,
        ),
        aod = AxClockAodAnimationSpec(scaleX = 0.96f, scaleY = 0.96f, translationYDp = -4f, alpha = 0.95f),
    )

    val OldQuickLook = AxClockAnimationSpec(
        appear = AxClockAppearAnimationSpec(startScaleX = 0.98f, startScaleY = 0.92f, startTranslationYDp = 14f),
        fidget = AxClockFidgetAnimationSpec(
            firstScaleX = 0.97f,
            firstScaleY = 1.02f,
            firstRotationZ = -1f,
            secondScaleX = 1.02f,
            secondScaleY = 0.98f,
            secondRotationZ = 1f,
        ),
        aod = AxClockAodAnimationSpec(scaleX = 0.98f, scaleY = 0.96f, alpha = 0.9f),
    )

    val Cyberpunk = AxClockAnimationSpec(
        appear = AxClockAppearAnimationSpec(
            startAlpha = 0.05f,
            startScaleX = 1.04f,
            startScaleY = 0.94f,
            startTranslationYDp = 8f,
            durationMs = 300L,
        ),
        fidget = AxClockFidgetAnimationSpec(enabled = false),
        aod = AxClockAodAnimationSpec(scaleX = 0.99f, scaleY = 0.96f, alpha = 0.86f),
    )

    val AxionAge = AxClockAnimationSpec(
        appear = AxClockAppearAnimationSpec(
            startAlpha = 0.12f,
            startScaleX = 0.92f,
            startScaleY = 0.88f,
            startTranslationYDp = 18f,
            durationMs = 520L,
        ),
        fidget = AxClockFidgetAnimationSpec(enabled = false),
        aod = AxClockAodAnimationSpec(scaleX = 0.98f, scaleY = 0.96f, translationYDp = -2f, alpha = 0.9f),
    )

    val OplusClassic = AxClockAnimationSpec(
        appear = AxClockAppearAnimationSpec(startScaleX = 0.96f, startScaleY = 0.96f, startTranslationYDp = 18f),
        fidget = AxClockFidgetAnimationSpec(
            firstScaleX = 0.98f,
            firstScaleY = 0.95f,
            secondScaleX = 1.01f,
            secondScaleY = 1.03f,
            secondTranslationYDp = -2f,
        ),
        aod = AxClockAodAnimationSpec(scaleX = 0.99f, scaleY = 0.98f, translationYDp = -2f, alpha = 0.95f),
    )

    val OplusBig = AxClockAnimationSpec(
        appear = AxClockAppearAnimationSpec(
            startScaleX = 0.98f,
            startScaleY = 0.92f,
            startTranslationYDp = 22f,
            durationMs = 480L,
        ),
        fidget = AxClockFidgetAnimationSpec(
            firstScaleX = 0.99f,
            firstScaleY = 0.94f,
            firstTranslationYDp = 2f,
            secondScaleX = 1.01f,
            secondScaleY = 1.02f,
            secondTranslationYDp = -2f,
        ),
        aod = AxClockAodAnimationSpec(scaleX = 1f, scaleY = 0.985f, translationYDp = -2f, alpha = 0.96f),
    )

    val OplusGraffiti = AxClockAnimationSpec(
        appear = AxClockAppearAnimationSpec(
            startAlpha = 0.1f,
            startScaleX = 0.94f,
            startScaleY = 0.94f,
            startTranslationYDp = 16f,
        ),
        fidget = AxClockFidgetAnimationSpec(
            firstScaleX = 0.97f,
            firstScaleY = 0.97f,
            firstRotationZ = -3f,
            secondScaleX = 1.03f,
            secondScaleY = 1.03f,
            secondRotationZ = 3f,
        ),
        aod = AxClockAodAnimationSpec(scaleX = 0.98f, scaleY = 0.98f, alpha = 0.92f),
    )

    fun forFaceStyle(style: ClockFaceStyle): AxClockAnimationSpec = when (style) {
        ClockFaceStyle.NTYPE -> NType
        ClockFaceStyle.NDOT -> NDot
        ClockFaceStyle.LONDON_UG -> LondonUnderground
        ClockFaceStyle.SPACE_AGE -> SpaceAge
        ClockFaceStyle.POLYLINE -> Polyline
        ClockFaceStyle.CYBERPUNK -> Cyberpunk
        ClockFaceStyle.AXION_AGE -> AxionAge
        ClockFaceStyle.SEGMENTS -> Segments
        ClockFaceStyle.GRAPHIC -> Graphic
    }
}
