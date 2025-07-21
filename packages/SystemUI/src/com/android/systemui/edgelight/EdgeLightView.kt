/*
 * Copyright (C) 2025 The AxionAOSP Project
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
package com.android.systemui.edgelight

import android.animation.*
import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import androidx.core.view.isVisible
import com.android.systemui.res.R

class EdgeLightView(context: Context) : FrameLayout(context) {

    private val leftEdge = View(context).apply {
        layoutParams = LayoutParams(EDGE_WIDTH, LayoutParams.MATCH_PARENT)
    }

    private val rightEdge = View(context).apply {
        layoutParams = LayoutParams(EDGE_WIDTH, LayoutParams.MATCH_PARENT).apply {
            gravity = Gravity.END
        }
    }

    private var pulseAnimator: Animator? = null
    private var isCancelled = false

    init {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        addView(leftEdge)
        addView(rightEdge)
        setVisible(false)
    }

    fun setVisible(visible: Boolean) {
        isVisible = visible
    }

    fun updateColor(color: Int) {
        leftEdge.setBackgroundColor(color)
        rightEdge.setBackgroundColor(color)
    }

    fun cancelPulse() {
        isCancelled = true
        pulseAnimator?.cancel()
        setVisible(false)
    }

    fun pulse(pulseCount: Int = DEFAULT_PULSE_COUNT) {
        val fadeInDuration = resources.getInteger(R.integer.doze_pulse_duration_visible) / 3L
        val holdDuration = fadeInDuration
        val fadeOutDuration = resources.getInteger(R.integer.doze_pulse_duration_out).toLong()

        var currentPulse = 0
        isCancelled = false

        fun createPulseAnimator(): AnimatorSet = AnimatorSet().apply {
            playSequentially(
                ObjectAnimator.ofFloat(this@EdgeLightView, View.ALPHA, 0f, 1f).apply {
                    duration = fadeInDuration
                    interpolator = AccelerateDecelerateInterpolator()
                },
                ObjectAnimator.ofFloat(this@EdgeLightView, View.ALPHA, 1f, 1f).apply {
                    duration = holdDuration
                },
                ObjectAnimator.ofFloat(this@EdgeLightView, View.ALPHA, 1f, 0f).apply {
                    duration = fadeOutDuration
                    interpolator = AccelerateDecelerateInterpolator()
                }
            )
        }

        fun animatePulse() {
            if (isCancelled || currentPulse++ >= pulseCount) {
                setVisible(false)
                return
            }

            setVisible(true)
            alpha = 0f

            pulseAnimator = createPulseAnimator().apply {
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (!isCancelled) animatePulse()
                    }

                    override fun onAnimationCancel(animation: Animator) {
                        setVisible(false)
                    }
                })
                start()
            }
        }

        animatePulse()
    }

    companion object {
        private const val EDGE_WIDTH = 16
        private const val DEFAULT_PULSE_COUNT = 3
    }
}
