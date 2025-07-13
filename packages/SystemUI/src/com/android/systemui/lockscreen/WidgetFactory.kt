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
package com.android.systemui.lockscreen

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.view.Gravity
import android.view.View
import android.widget.GridLayout
import androidx.core.content.ContextCompat
import com.android.systemui.animation.view.LaunchableImageView
import com.android.systemui.res.R
import com.google.android.flexbox.FlexboxLayout

class WidgetFactory(
    private val context: Context,
    private val controller: LockScreenWidgetsController
) {
    private val darkColor = ContextCompat.getColor(context, LsWidgetsRes.COLOR_BG_DARK)
    private val lightColor = ContextCompat.getColor(context, LsWidgetsRes.COLOR_BG_LIGHT)
    private val darkColorActive = ContextCompat.getColor(context, LsWidgetsRes.COLOR_BG_ADARK)
    private val lightColorActive = ContextCompat.getColor(context, LsWidgetsRes.COLOR_BG_ALIGHT)

    private fun isNightMode(): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES

    fun createWidgetView(action: WidgetAction): LaunchableImageView {
        return LaunchableImageView(context).apply {
            val widgetSize = context.resources.getDimensionPixelSize(LsWidgetsRes.WIDGET_CIRCLE_SIZE)
            layoutParams = FlexboxLayout.LayoutParams(widgetSize, widgetSize).apply {
                val spacing = context.resources.getDimensionPixelSize(LsWidgetsRes.WIDGET_MARGIN_HORIZONTAL)
                setMargins(spacing, spacing, spacing, spacing)
                flexGrow = 0f
                flexShrink = 0f
            }
            val iconPadding = context.resources.getDimensionPixelSize(LsWidgetsRes.WIDGET_ICON_PADDING)
            isFocusable = true
            isClickable = true
            setPadding(iconPadding, iconPadding, iconPadding, iconPadding)
            setBackgroundResource(getWidgetBackground(false))
            setImageResource(action.inactiveRes)
            setOnClickListener { action.onClick(controller) }
            action.onLongClick?.let { longClick ->
                setOnLongClickListener { v -> longClick(controller, v) }
            }
        }
    }

    fun updateWidgetState(view: LaunchableImageView, action: WidgetAction, active: Boolean) {
        view.setImageResource(if (active) action.activeRes else action.inactiveRes)
        view.setBackgroundResource(getWidgetBackground(active))

        if (!controller.dozing) {
            setTint(view, active)
        } else {
            view.backgroundTintList = null
            view.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, android.R.color.white))
        }
    }

    private fun setTint(view: LaunchableImageView, active: Boolean) {
        val (bgTint, iconTint) = when {
            active && isNightMode() -> darkColorActive to darkColor
            active -> lightColorActive to lightColor
            isNightMode() -> darkColor to lightColor
            else -> lightColor to darkColor
        }

        view.backgroundTintList = ColorStateList.valueOf(bgTint)
        view.imageTintList = ColorStateList.valueOf(iconTint)
    }

    private fun getWidgetBackground(active: Boolean): Int {
        return if (controller.dozing) {
            if (active) LsWidgetsRes.WIDGET_BG_DOZING_ACTIVE
            else LsWidgetsRes.WIDGET_BG_DOZING_INACTIVE
        } else {
            LsWidgetsRes.WIDGET_BG
        }
    }
}
