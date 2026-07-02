/*
 * Copyright 2025-2026 AxionOS
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

package com.android.systemui.wallpapers

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.os.SystemClock
import android.view.animation.PathInterpolator
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.util.WallpaperController
import java.util.concurrent.Executor
import javax.inject.Inject

class AxWallpaperDepthAnimator
@Inject
constructor(
    @Main private val mainExecutor: Executor,
    private val wallpaperController: WallpaperController,
) {
    private var animator: ValueAnimator? = null
    private var zoomOut = HOME_ZOOM
    private var lastFrameTime = 0L

    fun clear() {
        mainExecutor.execute {
            stop()
            apply(HOME_ZOOM, force = true)
        }
    }

    fun holdDepth() {
        mainExecutor.execute { holdDepthNow() }
    }

    fun playReveal() {
        mainExecutor.execute {
            if (zoomOut == HOME_ZOOM || animator?.isRunning == true) {
                return@execute
            }
            reveal()
        }
    }

    private fun reveal() {
        animate(if (animator?.isRunning == true) zoomOut else WAKE_START_ZOOM, HOME_ZOOM)
    }

    private fun holdDepthNow() {
        stop()
        apply(WAKE_START_ZOOM, force = true)
    }

    private fun animate(from: Float, to: Float) {
        stop()
        if (from == to) {
            apply(to, force = true)
            return
        }

        animator =
            ValueAnimator.ofFloat(from, to).apply {
                duration = REVEAL_DURATION_MS
                interpolator = INTERPOLATOR
                addUpdateListener { apply(it.animatedValue as Float, force = false) }
                addListener(
                    object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            if (animator == animation) {
                                animator = null
                                apply(to, force = true)
                            }
                        }
                    }
                )
                start()
            }
    }

    private fun stop() {
        animator?.removeAllUpdateListeners()
        animator?.removeAllListeners()
        animator?.cancel()
        animator = null
    }

    private fun apply(value: Float, force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastFrameTime < MIN_FRAME_MS) {
            return
        }

        wallpaperController.setScreenOnZoom(value)
        zoomOut = value
        lastFrameTime = now
    }

    companion object {
        private const val REVEAL_DURATION_MS = 600L
        private const val MIN_FRAME_MS = 12L
        private const val HOME_ZOOM = 0f
        private const val WAKE_START_ZOOM = 1f
        private val INTERPOLATOR = PathInterpolator(0.17f, 0.17f, 0.4f, 1f)
    }
}
