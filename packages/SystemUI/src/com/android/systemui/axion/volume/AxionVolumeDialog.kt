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
package com.android.systemui.axion.volume

import android.content.Context
import android.os.PowerManager
import android.view.*
import android.widget.FrameLayout
import androidx.activity.ComponentDialog
import androidx.compose.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.*
import androidx.lifecycle.*
import com.android.axion.compose.lifecycle.*
import com.android.systemui.animation.*
import com.android.systemui.axion.volume.ui.composable.*
import com.android.systemui.axion.volume.ui.viewmodel.*
import com.android.systemui.dagger.qualifiers.*
import com.android.systemui.res.R
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
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
            addFlags(
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
            )
            addPrivateFlags(WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY)
            setType(WindowManager.LayoutParams.TYPE_VOLUME_OVERLAY)
            setWindowAnimations(-1)
            attributes = attributes.apply { title = "AxionVolumeDialog" }
            setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        setCancelable(false)
        setCanceledOnTouchOutside(true)

        setupContent()
        setupWakeLock()
    }

    fun updateWindowGravity(isLeftSide: Boolean) {
        window?.setGravity((if (isLeftSide) Gravity.START else Gravity.END) or Gravity.CENTER_VERTICAL)
    }

    private fun setupContent() {
        val root = FrameLayoutTouchPassthrough(context).apply { id = R.id.volume_dialog }
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
        if (e.action == MotionEvent.ACTION_OUTSIDE && isShowing) {
            viewModel.visibilityState = VisibilityState.DISMISSED
            return true
        }
        return false
    }

    private inner class FrameLayoutTouchPassthrough(
        context: Context,
    ) : FrameLayout(context), DialogWindowProvider {

        override val window: Window
            get() = this@AxionVolumeDialog.window!!

        private val compose = ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)

            repeatWhenAttached { _ ->
                setContent {
                    MaterialTheme(
                        colorScheme = if (isSystemInDarkTheme())
                            dynamicDarkColorScheme(LocalContext.current)
                        else
                            dynamicLightColorScheme(LocalContext.current)
                    ) {
                        Box(
                            modifier = Modifier
                                .wrapContentSize()
                                .padding(12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            AxionVolumeDialogContent(viewModel)
                        }
                    }
                }
            }
        }

        init {
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
            addView(compose, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        }

        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            if (ev.action == MotionEvent.ACTION_DOWN) {
                if (isTouchOutsidePanelBounds(ev.x, ev.y)) {
                    viewModel.visibilityState = VisibilityState.DISMISSED
                    return true
                }
            }
            return super.dispatchTouchEvent(ev)
        }

        private fun isTouchOutsidePanelBounds(x: Float, y: Float): Boolean {
            if (isExpanded) return false
            val density = resources.displayMetrics.density
            val widthPx = 66 * density
            val w = width.toFloat()
            val ls = isLeftSide
            return if (ls) x > widthPx else x < (w - widthPx)
        }
    }
}
