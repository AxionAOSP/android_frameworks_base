/*
 * Copyright (C) 2025 AxionOS
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
package com.android.systemui.biometrics

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Animatable
import android.graphics.drawable.AnimationDrawable
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import com.android.compose.ui.graphics.painter.rememberDrawablePainter
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.rememberLottieComposition
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import com.android.systemui.res.R

private class WaterRippleUdfpsView(
    context: Context,
    private val offsetY: Int,
    private val animSizePx: Int,
    shaderInstance: WaterRippleShader?,
    private val shaderConfig: UdfpsShaderConfig
) : View(context) {
    private val waterShader = shaderInstance ?: WaterRippleShader()
    private val waterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        shader = waterShader
    }
    private val activeDurationSec: Float
        get() = shaderConfig.activeDurationSec
    private val cycleDurationMs: Long
        get() = shaderConfig.cycleDurationMs
    private val maxRadiusDp: Float
        get() = shaderConfig.maxRadiusDp
    private val peakAmplitude: Float
        get() = shaderConfig.peakAmplitude
    private val startRipplesDp: Float
        get() = shaderConfig.startRipplesDp
    private val endRipplesDp: Float
        get() = shaderConfig.endRipplesDp
    private val chromaticOffsetDp: Float
        get() = shaderConfig.chromaticOffsetDp

    private val easeOutInterpolator = PathInterpolator(0f, 0f, 0.1f, 1f)
    private val smoothInterpolator = PathInterpolator(0.33f, 0f, 0.67f, 1f)
    private var animator: ValueAnimator? = null
    private var currentRadiusDp = 0f
    private var currentRipplesDp = shaderConfig.startRipplesDp
    private val tmpLoc = IntArray(2)
    fun setWallpaperBitmap(bmp: Bitmap?) {
        val dm = resources.displayMetrics
        waterShader.setBackgroundBitmap(bmp, dm.widthPixels, dm.heightPixels)
    }
    init {
        val dm = resources.displayMetrics
        waterShader.setDensity(dm.density)
        val sizeF = maxOf(animSizePx, 1).toFloat()
        waterShader.setResolution(sizeF, sizeF)
        waterShader.setOrigin(sizeF * 0.5f, sizeF * 0.5f)
        val left = (dm.widthPixels - animSizePx) * 0.5f
        waterShader.setScreenSpace(
            left,
            offsetY.toFloat(),
            dm.widthPixels.toFloat(),
            dm.heightPixels.toFloat()
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            val dm = resources.displayMetrics
            waterShader.setResolution(w.toFloat(), h.toFloat())
            waterShader.setOrigin(w * 0.5f, h * 0.5f)
            val left = (dm.widthPixels - w) * 0.5f
            waterShader.setScreenSpace(
                left,
                offsetY.toFloat(),
                dm.widthPixels.toFloat(),
                dm.heightPixels.toFloat()
            )
        }
    }

    private fun updateScreenSpaceCoords() {
        val dm = resources.displayMetrics
        val w = if (width > 0) width else animSizePx
        val h = if (height > 0) height else animSizePx
        if (w <= 0 || h <= 0) return
        getLocationOnScreen(tmpLoc)
        val left = if (tmpLoc[0] > 0) tmpLoc[0] else (dm.widthPixels - w) / 2
        val top = if (tmpLoc[1] > 0) tmpLoc[1] else offsetY
        waterShader.setScreenSpace(
            left.toFloat(),
            top.toFloat(),
            dm.widthPixels.toFloat(),
            dm.heightPixels.toFloat()
        )
    }

    fun startAnimation() {
        if (animator?.isRunning == true) return
        updateScreenSpaceCoords()
        val cycleSec = cycleDurationMs / 1000f
        val peakSec = activeDurationSec / 3f
        val decaySec = (activeDurationSec - peakSec).coerceAtLeast(0.05f)
        animator = ValueAnimator.ofFloat(0f, cycleSec).apply {
            duration = cycleDurationMs
            interpolator = LinearInterpolator()
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            addUpdateListener { anim ->
                val tSec = anim.animatedValue as Float
                if (tSec <= activeDurationSec) {
                    val p = (tSec / activeDurationSec).coerceIn(0f, 1f)
                    val radiusDp = maxRadiusDp * easeOutInterpolator.getInterpolation(p)
                    val amplitude: Float
                    val ripplesDp: Float
                    if (tSec <= peakSec) {
                        val ap = (tSec / peakSec).coerceIn(0f, 1f)
                        amplitude = peakAmplitude * easeOutInterpolator.getInterpolation(ap)
                        ripplesDp = startRipplesDp
                    } else {
                        val ap = ((tSec - peakSec) / decaySec).coerceIn(0f, 1f)
                        val s = smoothInterpolator.getInterpolation(ap)
                        amplitude = peakAmplitude * (1f - s)
                        ripplesDp = startRipplesDp + (endRipplesDp - startRipplesDp) * s
                    }
                    val fadeOut = 1f - ((p - 0.82f) / 0.18f).coerceIn(0f, 1f)
                    currentRadiusDp = radiusDp
                    currentRipplesDp = ripplesDp
                    waterShader.updateWave(
                        timeSec = tSec,
                        radiusDp = radiusDp,
                        amplitude = amplitude,
                        ripplesDp = ripplesDp,
                        minRadiusDp = 0f,
                        offsetDp = chromaticOffsetDp,
                        colorFullAlpha = 1.0f,
                        fadeOut = fadeOut
                    )
                } else {
                    currentRadiusDp = 0f
                    waterShader.updateWave(
                        timeSec = tSec,
                        radiusDp = 0f,
                        amplitude = 0f,
                        ripplesDp = startRipplesDp,
                        fadeOut = 0f
                    )
                }
                invalidate()
            }
            start()
        }
    }

    fun stopAnimation() {
        animator?.cancel()
        animator = null
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        if (currentRadiusDp <= 0.1f) return
        val density = maxOf(resources.displayMetrics.density, 1f)
        val outerRadiusPx = (currentRadiusDp + currentRipplesDp * 3.2f) * density
        canvas.drawCircle(width * 0.5f, height * 0.5f, outerRadiusPx, waterPaint)
    }
}

@Composable
fun UdfpsAnimation(
    state: UdfpsAnimationUiState,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val animType = state.animType
    val isShaderMode = state.isShaderMode
    val preloadedDrawable = state.preloadedDrawable
    val preloadedWallpaper = state.preloadedWallpaper

    val animationSizeDp = with(LocalDensity.current) {
        state.animationSize.toDp()
    }
    val drawableSizeDp = with(LocalDensity.current) {
        context.resources.getDimensionPixelSize(R.dimen.udfps_animation_drawable_size).toDp()
    }

    AnimatedVisibility(
        visible = state.isVisible,
        enter = EnterTransition.None,
        exit = fadeOut(),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier.size(animationSizeDp),
            contentAlignment = Alignment.Center
        ) {
            if (isShaderMode) {
                AndroidView(
                    factory = { ctx ->
                        WaterRippleUdfpsView(
                            ctx,
                            state.animationOffsetY,
                            state.animationSize,
                            state.preloadedShader,
                            state.shaderConfig
                        ).apply {
                            setWallpaperBitmap(preloadedWallpaper)
                            startAnimation()
                        }
                    },
                    update = { view ->
                        view.setWallpaperBitmap(preloadedWallpaper)
                        if (state.isVisible) {
                            view.startAnimation()
                        } else {
                            view.stopAnimation()
                        }
                    },
                    onRelease = { view ->
                        view.stopAnimation()
                    },
                    modifier = Modifier.fillMaxSize()
                )
                val drawable = preloadedDrawable
                if (drawable != null) {
                    DisposableEffect(drawable) {
                        val animDrawable = drawable as? AnimationDrawable
                        val animatable = drawable as? Animatable
                        animatable?.stop()
                        animDrawable?.selectDrawable(0)
                        animatable?.start()
                        onDispose {
                            animatable?.stop()
                        }
                    }
                    val painter = rememberDrawablePainter(drawable)
                    Image(
                        painter = painter,
                        contentDescription = null,
                        modifier = Modifier.size(drawableSizeDp),
                        contentScale = ContentScale.Fit
                    )
                }
            } else if (animType == "drawable") {
                val drawable = preloadedDrawable
                if (drawable != null) {
                    DisposableEffect(drawable) {
                        val animDrawable = drawable as? AnimationDrawable
                        val animatable = drawable as? Animatable
                        animatable?.stop()
                        animDrawable?.selectDrawable(0)
                        animatable?.start()
                        onDispose {
                            animatable?.stop()
                        }
                    }
                    val painter = rememberDrawablePainter(drawable)
                    Image(
                        painter = painter,
                        contentDescription = null,
                        modifier = Modifier.size(animationSizeDp),
                        contentScale = ContentScale.Fit
                    )
                }
            } else {
                val compositionResult = rememberLottieComposition(
                    LottieCompositionSpec.RawRes(R.raw.nt_udfps_lockscreen_fp_scanning)
                )
                val composition by compositionResult
                val progress by animateLottieCompositionAsState(
                    composition = composition,
                    iterations = LottieConstants.IterateForever,
                    speed = 1.7f,
                    isPlaying = true
                )

                LottieAnimation(
                    composition = composition,
                    progress = { progress },
                    modifier = Modifier.size(animationSizeDp)
                )
            }
        }
    }
}
