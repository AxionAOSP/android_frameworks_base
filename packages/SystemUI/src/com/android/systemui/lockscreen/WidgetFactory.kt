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
import android.view.MotionEvent
import android.widget.FrameLayout
import androidx.compose.animation.*
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

    val dimens = Dimens(ctx)

    private val _widgetsList = mutableStateListOf<WidgetSpec>()
    val widgetsList: List<WidgetSpec> get() = _widgetsList

    private val _dozingState = mutableStateOf(false)
    val dozingState: State<Boolean> get() = _dozingState

    private val hostView by lazy { FrameLayoutTouchPassthrough(ctx) }

    private inner class FrameLayoutTouchPassthrough(context: Context) : FrameLayout(context), LaunchableView {
        private val composeView: ComposeView by lazy {
            ComposeView(context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )

                repeatWhenAttached {
                    repeatOnLifecycle(Lifecycle.State.STARTED) {
                        setViewCompositionStrategy(
                            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
                        )
                        setContent {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .wrapContentHeight(unbounded = true)
                            ) {
                                WidgetsArea()
                            }
                        }
                    }
                }
            }
        }

        init {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            addView(
                composeView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
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
        _widgetsList.clear()
        _widgetsList.addAll(ctrl.widgetSpecs.filterNotNull())
    }
    
    fun updateViews() {
        val newSpecs = ctrl.widgetSpecs.filterNotNull()
        val specsChanged = _widgetsList != newSpecs

        if (specsChanged) {
            _widgetsList.clear()
            _widgetsList.addAll(newSpecs)
        }

        val newDoze = ctrl.scrimUtils.isDozing()
        if (_dozingState.value != newDoze) {
            _dozingState.value = newDoze
        }
    }

    fun updateVisibility(vis: Int) {
        hostView.setVisibility(vis)
    }

    @Composable
    private fun WidgetsArea() {
        val theme = rememberTheme()
        val widgets = _widgetsList

        Row(
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight()
                .padding(top = dimens.topPaddingDp)
        ) {
            widgets.forEach { spec ->
                WidgetsContent(spec, theme)
            }
        }
    }

    @Composable
    private fun WidgetsContent(spec: WidgetSpec, theme: Theme) {
        val isActive by ctrl.states.getState(spec.action)
        val dozing by dozingState

        val activeBg = theme.activeBg
        val neutralBg = theme.neutralBg
        val activeIcon = theme.activeIcon
        val neutralIcon = theme.neutralIcon

        val targetBg = when {
            dozing -> Color.Transparent
            isActive -> activeBg
            else -> neutralBg
        }

        val targetIconTint = when {
            dozing -> Color.White
            isActive -> activeIcon
            else -> neutralIcon
        }

        val bgColor by animateColorAsState(targetValue = targetBg)
        val iconTint by animateColorAsState(targetValue = targetIconTint)

        val border = remember(dozing) {
            if (dozing) Modifier.border(dimens.dozeStrokeDp, Color.White, CircleShape)
            else Modifier
        }

        spec.type.content(spec, bgColor, border, iconTint, theme, dimens, ctrl, isActive, dozing)
    }
}
