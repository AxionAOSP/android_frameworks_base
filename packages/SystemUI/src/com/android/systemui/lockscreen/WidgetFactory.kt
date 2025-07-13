/*
 * Copyright 2025 AxionOS
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
import android.view.View
import android.view.ViewGroup
import com.android.systemui.animation.view.LaunchableImageView
import com.android.systemui.res.R
import com.google.android.flexbox.*

class WidgetFactory(
    private val context: Context,
    private val controller: LockScreenWidgetsController
) {
    private val theme = Theme(context)
    private val actions: List<WidgetAction?> 
        get() = controller.actions.take(4).let { it + List(4 - it.size) { null } }
    private val states: WidgetStates get() = controller.states
    private val enabled get() = controller.enabled

    val flexBox: FlexboxLayout by lazy {
        FlexboxLayout(context).apply {
            id = R.id.widgets_container
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPaddingRelative(theme.sidePadding, 0, theme.sidePadding, 0)
            flexDirection = FlexDirection.ROW
            justifyContent = JustifyContent.FLEX_START
            alignItems = AlignItems.CENTER
            flexWrap = FlexWrap.WRAP
        }
    }

    fun init() {
        flexBox.removeAllViews()
        actions.indices.forEach { index ->
            flexBox.addView(create(index))
        }
        controller.container.addView(flexBox)
    }

    fun updateViews(force: Boolean) {
        for (i in 0 until flexBox.childCount) {
            val action = actions.getOrNull(i)
            val view = flexBox.childAt(i) ?: continue
            if (action == null || !enabled) {
                view.visibility = View.GONE
                view.setOnClickListener(null)
                view.setOnLongClickListener(null)
            } else {
                if (force) {
                    bind(i, true)
                } else {
                    style(i, states.isActive(action))
                }
            }
        }
    }

    fun update(action: WidgetAction, isActive: Boolean) {
        actions.indexOf(action).takeIf { it >= 0 }?.let { index ->
            style(index, isActive)
        }
    }

    private fun create(index: Int): LaunchableImageView =
        LaunchableImageView(context).apply {
            isFocusable = true
            isClickable = true
            bind(index, force = true)
        }

    private fun bind(index: Int, force: Boolean) {
        val action = actions.getOrNull(index)
        val v = flexBox.childAt(index) ?: return
        if (action == null) {
            v.visibility = View.GONE
            v.setOnClickListener(null)
            v.setOnLongClickListener(null)
            return
        }
        v.visibility = View.VISIBLE
        style(index, states.isActive(action))
        if (!force) return
        setSize(index)
        v.setOnClickListener { action.onClick(controller) }
        v.setOnLongClickListener(action.onLongClick?.let { longClick ->
            { view -> longClick(controller, view) }
        })
    }

    private fun style(index: Int, isActive: Boolean) {
        val action = actions.getOrNull(index) ?: return
        val v = flexBox.childAt(index) ?: return

        val doze = controller.dozing
        val iconRes = theme.themed(action.activeRes, action.inactiveRes, isActive)
        val bgRes = when {
            doze -> theme.doze(isActive)
            isActive -> LsWidgetsRes.BG_ACTIVE
            else -> theme.default()
        }
        val iconTint = when {
            doze -> theme.white
            isActive -> theme.accent
            else -> theme.neutral
        }

        v.setImageResource(iconRes)
        v.setBackgroundResource(bgRes)
        v.backgroundTintList = if (isActive && !doze) theme.tint(theme.accentInverse) else null
        v.imageTintList = theme.tint(iconTint)
    }

    private fun setSize(index: Int) {
        val v = flexBox.childAt(index) ?: return
        val size = theme.widgetSize
        val gap = theme.spacing
        val padding = theme.iconPadding
        val total = actions.size
        val left = if (index == 0) 0 else gap
        val right = if (index == total - 1) 0 else gap
        v.layoutParams = FlexboxLayout.LayoutParams(size, size).apply {
            setMargins(left, gap, right, gap)
            flexGrow = 0f
            flexShrink = 0f
        }
        v.setPadding(padding, padding, padding, padding)
    }

    private fun ViewGroup.childAt(index: Int): LaunchableImageView? =
        getChildAt(index) as? LaunchableImageView
}
