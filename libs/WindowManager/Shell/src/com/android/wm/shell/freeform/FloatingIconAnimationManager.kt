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
package com.android.wm.shell.freeform

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.view.View
import android.view.WindowManager
import android.view.animation.*
import androidx.core.animation.doOnEnd
import com.android.wm.shell.R

class FloatingIconAnimationManager(
    private val context: Context,
    private val windowManager: WindowManager
) {

    fun animateSlideIn(view: View, params: WindowManager.LayoutParams, mainHandler: Handler) {
        mainHandler.post {
            val screenWidth = FreeformDisplayRepository.widthPixels
            val isLeft = params.x < screenWidth / 2

            val offset = view.width.toFloat() + 20f
            val fromX = if (isLeft) -offset else offset

            view.translationX = fromX
            view.alpha = 0f

            view.animate()
                .translationX(0f)
                .alpha(1f)
                .setDuration(300)
                .setInterpolator(OvershootInterpolator(1.1f))
                .start()
        }
    }

    fun snapToEdge(
        view: View,
        params: WindowManager.LayoutParams,
        velocityX: Float,
        mainHandler: Handler,
        onEnd: () -> Unit
    ) {
        val screenWidth = FreeformDisplayRepository.widthPixels
        val viewWidth = view.width
        val startX = params.x
        val minVelocity = 300f

        val targetX = when {
            velocityX > minVelocity -> screenWidth - viewWidth
            velocityX < -minVelocity -> 0
            else -> if (params.x + viewWidth / 2 < screenWidth / 2) 0 else screenWidth - viewWidth
        }

        val duration = (300 - (kotlin.math.abs(velocityX) / 10)).toLong().coerceIn(200, 400)

        ValueAnimator.ofInt(startX, targetX).apply {
            this.duration = duration
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                mainHandler.post {
                    params.x = (it.animatedValue as Int).coerceIn(0, screenWidth - viewWidth)
                    windowManager.updateViewLayout(view, params)
                }
            }
            doOnEnd { onEnd() }
            start()
        }

        mainHandler.post {
            view.animate()
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(duration)
                .start()
        }
    }

    fun playDismissAnimation(
        view: View,
        taskId: Int,
        onEnd: () -> Unit
    ) {
        view.animate()
            .alpha(0f)
            .scaleX(0f)
            .scaleY(0f)
            .setDuration(250)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction(onEnd)
            .start()
    }

    fun animateDismissAreaShow(view: View) {
        view.visibility = View.VISIBLE
        view.scaleX = 0f
        view.scaleY = 0f
        view.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(200)
            .setInterpolator(OvershootInterpolator())
            .start()
    }

    fun animateDismissAreaHide(view: View) {
        view.animate()
            .scaleX(0f)
            .scaleY(0f)
            .setDuration(150)
            .withEndAction { view.visibility = View.GONE }
            .start()
    }
}
