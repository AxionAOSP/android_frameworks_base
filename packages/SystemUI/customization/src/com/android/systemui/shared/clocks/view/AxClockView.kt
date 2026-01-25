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

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.ViewGroup
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.axion.compose.lifecycle.repeatWhenAttached
import com.android.systemui.shared.clocks.ClockConfigs

abstract class AxClockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    defStyleRes: Int = 0
) : NTClockView(context, attrs, defStyleAttr, defStyleRes) {

    private val composeView: ComposeView = ComposeView(context)

    protected val timeState = mutableStateOf("")
    protected val dateState = mutableStateOf("")
    protected val dozeState = mutableStateOf(false)
    protected val dozeAmountState = mutableStateOf(0f)
    protected val regionDarkState = mutableStateOf(false)
    protected val fidgetTrigger = mutableStateOf(0L)
    protected val fidgetPosition = mutableStateOf(Offset.Zero)
    protected open val showSystemDate: Boolean = true
    protected open val useGlitchInteraction: Boolean = false

    init {
        addView(composeView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        composeView.repeatWhenAttached {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                composeView.setViewCompositionStrategy(
                    ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
                )
                composeView.setContent {
                    Content()
                }
            }
        }
    }

    override fun animateFidgetTap(x: Float, y: Float) {
        fidgetPosition.value = Offset(x, y)
        if (useGlitchInteraction) {
            fidgetTrigger.value = System.currentTimeMillis()
            
            (context.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator)?.vibrate(
                android.os.VibrationEffect.createPredefined(android.os.VibrationEffect.EFFECT_CLICK)
            )
        } else {
            super.animateFidgetTap(x, y)
            fidgetTrigger.value = System.currentTimeMillis()
        }
    }

    @Composable
    protected abstract fun Content()
    
    protected val iconState = mutableStateOf<android.graphics.Bitmap?>(null)

    protected override fun updateDisplayContent() {
        super.updateDisplayContent()
        val text = displayText
        val secondary = displaySecondaryText
        val mode = displayMode
        
        iconState.value = displayIcon

        dateState.value = when (mode) {
            DisplayMode.WEATHER -> {
                if (secondary.isNotEmpty()) "$text  $secondary" else text
            }
            DisplayMode.NOW_PLAYING,
            DisplayMode.CALENDAR -> {
                "$dateStr  $text"
            }
            else -> text
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (showSystemDate) {
            super.onDraw(canvas)
        } else {
            canvas.drawFilter = antiAliasFilter
            drawClock(canvas)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        refreshTime()
        timeState.value = timeStr
        dozeState.value = isDoze
        regionDarkState.value = isRegionDark
    }

    override fun refreshTime() {
        super.refreshTime()
        timeState.value = timeStr
    }

    override fun onDozeChanged(doze: Boolean) {
        super.onDozeChanged(doze)
        dozeState.value = doze
    }

    override fun onDozeAmountChanged(linear: Float, eased: Float) {
        super.onDozeAmountChanged(linear, eased)
        dozeAmountState.value = eased
    }
    
    override fun onRegionDarknessChanged(regionDark: Boolean) {
        super.onRegionDarknessChanged(regionDark)
        regionDarkState.value = regionDark
    }

    override fun drawClock(canvas: Canvas) {
        
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val w = measuredWidth
        val reservedHeight = if (showSystemDate) {
            if (config?.position == ClockConfigs.Position.BELOW) {
                dateHeight + dateMarginTop
            } else {
                dateHeight
            }
        } else 0
        
        val h = measuredHeight - reservedHeight
        if (w > 0 && h > 0) {
            composeView.measure(
                MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY)
            )
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        if (!showSystemDate) {
            composeView.layout(0, 0, width, height)
            return
        }

        val dateH = dateHeight
        val dateM = dateMarginTop
        if (config?.position == ClockConfigs.Position.ABOVE) {
            composeView.layout(0, dateH, width, height)
        } else {
            composeView.layout(0, 0, width, height - (dateH + dateM))
        }
    }
}
