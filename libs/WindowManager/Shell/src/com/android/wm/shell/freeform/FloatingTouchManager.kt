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

import android.content.Context
import android.graphics.Rect
import android.os.*
import android.view.*
import android.widget.OverScroller
import com.android.wm.shell.R

class FloatingTouchManager(
    private val context: Context,
    private val mainHandler: Handler,
    private val windowManager: WindowManager,
    private val iconFactory: IconFactory,
    private val animationManager: FloatingIconAnimationManager,
    private val DismissAreaViewProvider: () -> View?,
    private val showDismissArea: () -> Unit,
    private val hideDismissArea: () -> Unit,
    private val onDismissArea: (View) -> Boolean,
    private val onDismissRequested: (View, Int) -> Unit,
    private val onRelaunchRequested: (Int) -> Unit
) {

    private val scroller = OverScroller(context)
    private val choreographer = Choreographer.getInstance()
    private var isFlinging = false

    fun createTouchListener(
        view: View,
        params: WindowManager.LayoutParams,
        taskId: Int
    ): View.OnTouchListener {
        return object : View.OnTouchListener {
            var initialTouchX = 0f
            var initialTouchY = 0f
            var lastTouchRawX = 0f
            var lastTouchRawY = 0f
            var moved = false
            var velocityTracker: VelocityTracker? = null

            val dragThreshold = context.resources.getDimensionPixelSize(R.dimen.floating_icon_drag_threshold)

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        view.animate().cancel()
                        stopFling()

                        initialTouchX = event.x
                        initialTouchY = event.y
                        lastTouchRawX = event.rawX
                        lastTouchRawY = event.rawY
                        moved = false

                        velocityTracker = VelocityTracker.obtain()
                        velocityTracker?.addMovement(event)
                        return true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        velocityTracker?.addMovement(event)

                        val dx = event.rawX - lastTouchRawX
                        val dy = event.rawY - lastTouchRawY

                        if (!moved &&
                            (kotlin.math.abs(dx) > dragThreshold || kotlin.math.abs(dy) > dragThreshold)
                        ) {
                            moved = true
                            showDismissArea()
                        }

                        if (moved) {
                            val newX = (params.x + dx).toInt()
                            val newY = (params.y + dy).toInt()
                            updateIconPosition(view, params, newX, newY)
                            lastTouchRawX = event.rawX
                            lastTouchRawY = event.rawY
                            onDismissArea(view)
                        }

                        return true
                    }

                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        velocityTracker?.addMovement(event)
                        velocityTracker?.computeCurrentVelocity(1000)
                        val velocityX = velocityTracker?.xVelocity?.toInt() ?: 0
                        val velocityY = velocityTracker?.yVelocity?.toInt() ?: 0
                        velocityTracker?.recycle()
                        velocityTracker = null

                        hideDismissArea()

                        return if (moved) {
                            if (onDismissArea(view)) {
                                onDismissRequested(view, taskId)
                            } else {
                                startFling(view, params, velocityX, velocityY, taskId)
                            }
                            true
                        } else {
                            iconFactory.saveIconPosition(view, params.x, params.y)
                            onRelaunchRequested(taskId)
                            true
                        }
                    }
                }
                return false
            }
        }
    }

    private fun updateIconPosition(view: View, params: WindowManager.LayoutParams, newX: Int, newY: Int) {
        val (screenWidth, screenHeight) = getScreenSize()
        val clampedX = newX.coerceIn(0, screenWidth - view.width)
        val clampedY = newY.coerceIn(0, screenHeight - view.height)
        if (params.x != clampedX || params.y != clampedY) {
            params.x = clampedX
            params.y = clampedY
            mainHandler.post {
                windowManager.updateViewLayout(view, params)
            }
        }
    }

    private fun startFling(view: View, params: WindowManager.LayoutParams, velocityX: Int, velocityY: Int, taskId: Int) {
        isFlinging = true
        val (screenWidth, screenHeight) = getScreenSize()

        scroller.fling(
            params.x, params.y,
            velocityX, velocityY,
            0, screenWidth - view.width,
            0, screenHeight - view.height
        )

        choreographer.postFrameCallback(object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (!isFlinging || !scroller.computeScrollOffset()) {
                    isFlinging = false
                    animationManager.snapToEdge(
                        view, params, velocityX.toFloat(), mainHandler,
                        onEnd = {
                            iconFactory.saveIconPosition(view, params.x, params.y)
                        }
                    )
                    return
                }

                val (screenWidth, screenHeight) = getScreenSize()
                val x = scroller.currX.coerceIn(0, screenWidth - view.width)
                val y = scroller.currY.coerceIn(0, screenHeight - view.height)

                updateIconPosition(view, params, x, y)
                choreographer.postFrameCallback(this)
            }
        })
    }

    private fun stopFling() {
        if (isFlinging) {
            scroller.forceFinished(true)
            isFlinging = false
        }
    }

    private fun getScreenSize(): Pair<Int, Int> {
        return FreeformDisplayRepository.widthPixels to FreeformDisplayRepository.heightPixels
    }
}
