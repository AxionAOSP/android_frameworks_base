/*
 * Copyright (C) 2025-2026 AxionOS
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
import android.util.AttributeSet
import android.view.View
import androidx.compose.runtime.Composable
import com.android.systemui.customization.R

class NoClockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    defStyleRes: Int = 0
) : AxClockView(context, attrs, defStyleAttr, defStyleRes) {

    override val animationSpec: AxClockAnimationSpec = AxClockAnimationSpecs.None

    init {
        touchEnabled = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }

    override fun getTag(): String = "NoClockView"

    @Composable
    override fun Content() = Unit

    override fun refreshTime() {
        contentDescription = null
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = getDefaultSize(suggestedMinimumWidth, widthMeasureSpec)
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val heightSize = MeasureSpec.getSize(heightMeasureSpec)
        val reservedHeight = context.resources.getDimensionPixelSize(R.dimen.clock_height)
        val height =
            when (heightMode) {
                MeasureSpec.EXACTLY -> maxOf(heightSize, reservedHeight)
                MeasureSpec.AT_MOST ->
                    if (heightSize > 0) minOf(heightSize, reservedHeight) else reservedHeight
                else -> reservedHeight
            }
        setMeasuredDimension(width, height)
    }
}
