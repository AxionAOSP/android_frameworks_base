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
import android.content.Intent
import android.view.MotionEvent
import android.widget.FrameLayout
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.gestures.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.*
import androidx.lifecycle.*
import com.android.systemui.animation.LaunchableView
import com.android.systemui.animation.LaunchableViewDelegate
import com.android.systemui.util.repeatWhenAttached
import com.android.systemui.res.R

class WidgetFactory(
    private val ctx: Context,
    private val ctrl: LockScreenWidgetsController
) {
    private val dimens = Dimens(ctx)

    private val widgetsState = mutableStateListOf<WidgetSpec>()

    private val _dozingState = mutableStateOf(false)
    private val dozingState: State<Boolean> = _dozingState

    private val _activeStates = mutableStateMapOf<WidgetAction, Boolean>()
    private val activeStates: Map<WidgetAction, Boolean> = _activeStates

    private val hostView by lazy { FrameLayoutTouchPassthrough(ctx) }

    private inner class FrameLayoutTouchPassthrough(context: Context) : FrameLayout(context), LaunchableView {
        private val composeView: ComposeView by lazy {
            ComposeView(context).apply {
                repeatWhenAttached {
                    repeatOnLifecycle(Lifecycle.State.STARTED) {
                        setViewCompositionStrategy(
                            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
                        )
                        setContent {
                            WidgetsArea(widgetsState)
                        }
                    }
                }
            }
        }

        init {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dimens.hostHeightPx
            )
            addView(
                composeView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }

        private val delegate = LaunchableViewDelegate(
            this,
            superSetVisibility = { super.setVisibility(it) }
        )

        override fun setShouldBlockVisibilityChanges(block: Boolean) {
            delegate.setShouldBlockVisibilityChanges(block)
        }

        override fun setVisibility(visibility: Int) {
            delegate.setVisibility(visibility)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            composeView.onTouchEvent(event)
            return super.onTouchEvent(event)
        }

        override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
            composeView.onInterceptTouchEvent(event)
            return super.onInterceptTouchEvent(event)
        }
    }

    fun init() {
        ctrl.container.addView(hostView)
        widgetsState.clear()
        widgetsState.addAll(ctrl.widgetSpecs.filterNotNull())
        update()
    }

    fun updateViews(force: Boolean = false) {
        widgetsState.clear()
        widgetsState.addAll(ctrl.widgetSpecs.filterNotNull())
        update()
    }

    fun update(action: WidgetAction, isActive: Boolean) {
        val idx = widgetsState.indexOfFirst { it.action == action }
        if (idx >= 0) {
            val spec = widgetsState[idx]
            widgetsState[idx] = spec.copy()
        }
        update()
    }

    private fun update() {
        _dozingState.value = ctrl.dozing
        ctrl.widgetSpecs.forEach { spec ->
            _activeStates[spec.action] = ctrl.states.isActive(spec.action)
        }
    }
    
    fun updateVisibility(vis: Int) {
        hostView.setVisibility(vis)
    }

    @Composable
    private fun WidgetsArea(widgets: List<WidgetSpec>) {
        val theme = rememberTheme()

        Row(
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(dimens.hostHeightDp)
                .padding(top = dimens.topPaddingDp)
        ) {
            widgets.forEach { spec ->
                WidgetsContent(spec, theme)
            }
        }
    }

    @Composable
    private fun WidgetsContent(spec: WidgetSpec, theme: Theme) {
        val isActive by derivedStateOf { activeStates[spec.action] ?: ctrl.states.isActive(spec.action) }
        val dozing by dozingState

        val bgColor = when {
            dozing -> Color.Transparent
            isActive -> theme.activeBg
            else -> theme.neutralBg
        }

        val border = if (dozing) {
            Modifier.border(
                width = dimens.dozeStrokeDp,
                color = Color.White,
                shape = CircleShape
            )
        } else {
            Modifier
        }

        val iconTint = when {
            dozing -> Color.White
            isActive -> theme.activeIcon
            else -> theme.neutralIcon
        }
        
        val slotSize = dimens.widgetSizeDp
        val spacing = dimens.spacingDp

        if (spec.span == 2) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing),
                modifier = Modifier
                    .width((slotSize * 2) + (spacing * 2))
                    .height(slotSize)
                    .background(bgColor, CircleShape)
                    .clip(CircleShape)
                    .then(border)
                    .combinedClickable(
                        onClick = { spec.action.onClick(ctrl) },
                        onLongClick = spec.action.onLongClick?.let { { it(ctrl) } }
                    )
                    .padding(horizontal = dimens.labelStartDp)
            ) {
                WidgetIcon(spec, iconTint, theme)
                WidgetLabel(
                    action = spec.action, 
                    tintColor = iconTint, 
                    modifier = Modifier.weight(1f).basicMarquee()
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .size(slotSize)
                    .background(bgColor, CircleShape)
                    .clip(CircleShape)
                    .then(border)
                    .combinedClickable(
                        onClick = { spec.action.onClick(ctrl) },
                        onLongClick = spec.action.onLongClick?.let { { it(ctrl) } }
                    ),
                contentAlignment = Alignment.Center
            ) {
                WidgetIcon(spec, iconTint, theme)
            }
        }
    }

    @Composable
    private fun WidgetIcon(spec: WidgetSpec, tintColor: Color, theme: Theme) {
        val active by derivedStateOf { activeStates[spec.action] ?: ctrl.states.isActive(spec.action) }
        val iconVector = WidgetIconFactory.getIcon(spec.action, active)
        Icon(
            imageVector = iconVector,
            contentDescription = spec.action.label(ctx),
            tint = tintColor,
            modifier = Modifier.size(dimens.iconSizeDp)
        )
    }

    @Composable
    private fun WidgetLabel(action: WidgetAction, tintColor: Color, modifier: Modifier) {
        val active by derivedStateOf { activeStates[action] ?: ctrl.states.isActive(action) }
        
        val label = action.label(ctx)

        val text = when {
            action == WidgetAction.BLUETOOTH && active -> {
                ctrl.callbacks.connectedDeviceName
            }
            action == WidgetAction.WIFI && active -> {
                ctrl.callbacks.wifiInfo.ssid?.removeSurrounding("\"")
            }
            action == WidgetAction.DATA && active -> {
                ctrl.networkController.getMobileDataNetworkName()
            }
            action == WidgetAction.RINGER -> {
                val label = 
                    if (active) R.string.ringer_vibrate 
                    else R.string.ringer_normal
                stringResource(label)
            }
            else -> label
        } ?: label

        Text(
            text = text,
            color = tintColor,
            maxLines = 1,
            modifier = modifier
        )
    }
}
