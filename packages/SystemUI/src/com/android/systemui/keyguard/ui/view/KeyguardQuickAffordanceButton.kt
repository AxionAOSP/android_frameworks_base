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

package com.android.systemui.keyguard.ui.view

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import com.android.axion.blur.BlurEngine
import com.android.internal.R as AndroidR
import com.android.systemui.animation.view.LaunchableImageView
import com.android.systemui.common.shared.colors.SurfaceEffectColors

class KeyguardQuickAffordanceButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LaunchableImageView(context, attrs, defStyleAttr) {
    private val blur = BlurEngine(this)
    private var overlayColor = SurfaceEffectColors.surfaceEffect1(context)

    init {
        blur.setEnabled(true)
        updateThemeColors()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateThemeColors()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        blur.onVisibilityAggregated(isVisible)
        if (isVisible) {
            updateThemeColors()
        }
    }

    override fun verifyDrawable(who: Drawable): Boolean =
        blur.verifyDrawable(who) || super.verifyDrawable(who)

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateThemeColors()
    }

    override fun draw(canvas: Canvas) {
        drawBlurBackground(canvas)
        super.onDraw(canvas)
        onDrawForeground(canvas)
    }

    fun updateThemeColors() {
        overlayColor = backgroundOverlayColor()
        backgroundTintList = ColorStateList.valueOf(backgroundColor())
        drawable?.setTint(
            context.getColor(
                if (isActivated) {
                    AndroidR.color.materialColorOnPrimaryFixed
                } else {
                    AndroidR.color.materialColorOnSurface
                }
            )
        )
        foregroundTintList = textColorPrimary()
        invalidate()
    }

    private fun backgroundColor(): Int {
        if (usesActivatedBackground()) {
            return context.getColor(AndroidR.color.materialColorPrimaryFixed)
        }
        return SurfaceEffectColors.surfaceEffect1(context)
    }

    private fun backgroundOverlayColor(): Int {
        if (usesActivatedBackground()) {
            return context.getColor(AndroidR.color.materialColorPrimaryFixed)
        }
        return SurfaceEffectColors.surfaceEffect1(context)
    }

    private fun usesActivatedBackground(): Boolean = !isSelected && isActivated

    private fun drawBlurBackground(canvas: Canvas) {
        if (width <= 0 || height <= 0) {
            blur.clear()
            return
        }
        blur.setOverlayColor(overlayColor)
        if (!blur.draw(canvas, this, 255)) {
            background?.let { it.setBounds(0, 0, width, height); it.draw(canvas) }
        }
    }

    private fun textColorPrimary(): ColorStateList? {
        val typedArray = context.theme.obtainStyledAttributes(TEXT_COLOR_PRIMARY_ATTRS)
        return try {
            typedArray.getColorStateList(0)
        } finally {
            typedArray.recycle()
        }
    }

    companion object {
        private val TEXT_COLOR_PRIMARY_ATTRS = intArrayOf(AndroidR.attr.textColorPrimary)
    }
}
