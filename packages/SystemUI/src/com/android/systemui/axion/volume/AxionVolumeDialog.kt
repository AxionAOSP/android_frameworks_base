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
@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.android.systemui.axion.volume

import android.content.Context
import android.graphics.Rect
import android.graphics.Region
import android.os.PowerManager
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.Window
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentDialog
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import com.android.systemui.axion.volume.ui.composable.AxionVolumeDialogContent
import com.android.systemui.axion.volume.ui.viewmodel.AxionVolumeDialogViewModel
import com.android.systemui.axion.volume.ui.viewmodel.VisibilityState
import com.android.systemui.dagger.qualifiers.Application
import com.android.axion.compose.lifecycle.repeatWhenAttached
import com.android.systemui.res.R
import javax.inject.Inject

class AxionVolumeDialog @Inject constructor(
    @Application private val context: Context,
    private val viewModel: AxionVolumeDialogViewModel,
) : ComponentDialog(context, R.style.Theme_SystemUI_Dialog_Volume) {

    private var wakeLock: PowerManager.WakeLock? = null
    var isExpanded: Boolean = false
    var isLeftSide: Boolean = false

    init {
        with(window!!) {
            clearFlags(
                WindowManager.LayoutParams.FLAG_DIM_BEHIND or
                    WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR
            )
            addFlags(
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
            )
            addPrivateFlags(WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY)
            setType(WindowManager.LayoutParams.TYPE_VOLUME_OVERLAY)
            setWindowAnimations(-1)
            attributes = attributes.apply { title = "AxionVolumeDialog" }
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        setCancelable(false)
        setCanceledOnTouchOutside(true)

        setupContent()
        setupWakeLock()
    }

    fun updateWindowGravity(isLeftSide: Boolean) {
        this.isLeftSide = isLeftSide
    }

    private fun setupContent() {
        val root = FrameLayoutTouchPassthrough(context).apply {
            id = R.id.volume_dialog
            clipChildren = false
            clipToPadding = false
        }
        setContentView(root)
    }

    private fun setupWakeLock() {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "AxionVolumeDialog:WakeLock"
        ).apply { setReferenceCounted(false) }
    }

    var isWakeLockAcquired: Boolean
        get() = wakeLock?.isHeld == true
        set(v) {
            wakeLock?.let {
                if (v && !it.isHeld) it.acquire(10_000L)
                if (!v && it.isHeld) it.release()
            }
        }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (isShowing && e.action == MotionEvent.ACTION_OUTSIDE) {
            if (viewModel.visibilityState == VisibilityState.SHOWING) {
                viewModel.visibilityState = VisibilityState.DISMISSED
            }
            return true
        }
        return false
    }

    private inner class FrameLayoutTouchPassthrough(
        context: Context,
    ) : FrameLayout(context), DialogWindowProvider,
        ViewTreeObserver.OnComputeInternalInsetsListener {

        override val window: Window
            get() = this@AxionVolumeDialog.window!!

        private val contentBounds = Rect()

        private val compose = ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)

            repeatWhenAttached {
                setContent {
                    MaterialExpressiveTheme(
                        colorScheme = if (isSystemInDarkTheme()) {
                            dynamicDarkColorScheme(LocalContext.current)
                        } else {
                            dynamicLightColorScheme(LocalContext.current)
                        },
                        MotionScheme.expressive()
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(12.dp),
                            contentAlignment = if (isLeftSide) Alignment.CenterStart else Alignment.CenterEnd
                        ) {
                            Box(
                                modifier = Modifier.onGloballyPositioned { coords ->
                                    val bounds = coords.boundsInWindow()
                                    contentBounds.set(
                                        bounds.left.toInt(),
                                        bounds.top.toInt(),
                                        bounds.right.toInt(),
                                        bounds.bottom.toInt()
                                    )
                                }
                            ) {
                                AxionVolumeDialogContent(viewModel)
                            }
                        }
                    }
                }
            }
        }

        init {
            clipChildren = false
            clipToPadding = false
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            addView(compose, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            setOnApplyWindowInsetsListener { v, insets ->
                val safe = insets.getInsets(
                    WindowInsets.Type.displayCutout() or
                        WindowInsets.Type.navigationBars() or
                        WindowInsets.Type.statusBars()
                )
                v.setPadding(safe.left, safe.top, safe.right, safe.bottom)
                WindowInsets.CONSUMED
            }
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            viewTreeObserver.addOnComputeInternalInsetsListener(this)
        }

        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            viewTreeObserver.removeOnComputeInternalInsetsListener(this)
        }

        override fun onComputeInternalInsets(info: ViewTreeObserver.InternalInsetsInfo) {
            info.setTouchableInsets(ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_REGION)
            if (!contentBounds.isEmpty) {
                info.touchableRegion.set(Region(contentBounds))
            }
        }

        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            return super.dispatchTouchEvent(ev)
        }
    }
}
