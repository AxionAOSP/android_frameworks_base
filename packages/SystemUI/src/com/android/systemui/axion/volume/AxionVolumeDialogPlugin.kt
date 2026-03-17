/*
 * Copyright (C) 2025 Axion OS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.axion.volume

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.os.Handler
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import com.android.systemui.axion.volume.dagger.AxionVolumeComponent
import com.android.systemui.axion.volume.ui.viewmodel.AxionVolumeDialogViewModel
import com.android.systemui.axion.volume.ui.viewmodel.ExpansionState
import com.android.systemui.axion.volume.ui.viewmodel.VisibilityState
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.plugins.VolumeDialog
import com.android.systemui.plugins.VolumeDialogController
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import javax.inject.Inject

class AxionVolumeDialogPlugin @Inject constructor(
    @Application private val context: Context,
    private val componentFactory: AxionVolumeComponent.Factory,
    private val controller: VolumeDialogController,
    @Main private val mainHandler: Handler,
    @Main private val mainDispatcher: CoroutineDispatcher,
) : VolumeDialog {

    private var pluginScope: CoroutineScope? = null
    private var callback: VolumeDialog.Callback? = null

    private lateinit var dialog: AxionVolumeDialog
    private lateinit var viewModel: AxionVolumeDialogViewModel
    private var autoDismissJob: Job? = null
    private var previousVisibilityState: VisibilityState? = null

    companion object {
        private const val DEFAULT_TIMEOUT_MS = 3000L
        private const val EXPANDED_TIMEOUT_MS = 5000L
    }

    private fun computeTimeout(): Long {
        val baseTimeout = if (viewModel.expansionState == ExpansionState.EXPANDED) {
            EXPANDED_TIMEOUT_MS
        } else {
            Settings.Secure.getInt(
                context.contentResolver,
                Settings.Secure.VOLUME_DIALOG_DISMISS_TIMEOUT,
                DEFAULT_TIMEOUT_MS.toInt()
            ).toLong()
        }
        val am = context.getSystemService(AccessibilityManager::class.java) ?: return baseTimeout
        return am.getRecommendedTimeoutMillis(
            baseTimeout.toInt(),
            AccessibilityManager.FLAG_CONTENT_CONTROLS
        ).toLong()
    }

    private val controllerCallbacks = object : VolumeDialogController.Callbacks {
        override fun onShowRequested(reason: Int, keyguardLocked: Boolean, lockTaskModeState: Int) {
            val wasShowing = viewModel.visibilityState == VisibilityState.SHOWING

            if (!wasShowing) {
                viewModel.expansionState = ExpansionState.COLLAPSED
            }
            viewModel.visibilityState = VisibilityState.SHOWING

            if (wasShowing) {
                 mainHandler.post {
                     if (::dialog.isInitialized && !dialog.isShowing) {
                         dialog.show()
                         controller.notifyVisible(true)
                     }
                     rescheduleAutoDismiss()
                 }
            }
        }

        override fun onDismissRequested(reason: Int) {
            viewModel.visibilityState = VisibilityState.DISMISSED
        }

        override fun onScreenOff() {
            viewModel.visibilityState = VisibilityState.QUICK_DISMISSED
        }

        override fun onVolumeChangedFromKey() {
            viewModel.triggerVolumeKeyHaptic()
            rescheduleAutoDismiss()
        }

        override fun onStateChanged(state: VolumeDialogController.State) {}
        override fun onLayoutDirectionChanged(layoutDirection: Int) {}
        override fun onConfigurationChanged() {}
        override fun onShowVibrateHint() {}
        override fun onShowSilentHint() {}
        override fun onShowSafetyWarning(flags: Int) {}
        override fun onAccessibilityModeChanged(showA11yStream: Boolean?) {}
        override fun onCaptionEnabledStateChanged(isEnabled: Boolean, checkBeforeSwitch: Boolean) {}
        override fun onCaptionComponentStateChanged(isComponentEnabled: Boolean?, fromTooltip: Boolean) {}
        override fun onShowCsdWarning(csdWarning: Int, durationMs: Int) {}
    }

    override fun init(windowType: Int, callback: VolumeDialog.Callback) {
        this.callback = callback
        pluginScope = CoroutineScope(Job() + mainDispatcher)

        val component = componentFactory.create(pluginScope!!)
        dialog = component.volumeDialog()
        viewModel = component.viewModel()

        controller.addCallback(controllerCallbacks, mainHandler)

        pluginScope?.launch {
            viewModel.rescheduleTimeoutTrigger.collect {
                rescheduleAutoDismiss()
            }
        }

        var dismissJob: Job? = null

        pluginScope?.launch {
            viewModel.uiState.collect { state ->
                dialog.isWakeLockAcquired = state.isInteracting
                dialog.isExpanded = state.isExpanded
                dialog.isLeftSide = state.isLeftSide
                dialog.updateWindowGravity(state.isLeftSide)

                if (state.isInteracting) {
                    autoDismissJob?.cancel()
                } else if (state.visibilityState == VisibilityState.SHOWING) {
                    rescheduleAutoDismiss()
                }

                val visibilityChanged = previousVisibilityState != state.visibilityState
                previousVisibilityState = state.visibilityState

                if (visibilityChanged) {
                    val isDismissEvent = state.visibilityState == VisibilityState.DISMISSED ||
                                       state.visibilityState == VisibilityState.QUICK_DISMISSED

                    if (isDismissEvent && state.isInteracting) {
                        dismissJob?.cancel()
                        dismissJob = null
                        autoDismissJob?.cancel()

                        if (viewModel.visibilityState != VisibilityState.SHOWING) {
                            viewModel.visibilityState = VisibilityState.SHOWING
                        }
                        return@collect
                    }

                    when (state.visibilityState) {
                        VisibilityState.SHOWING -> {
                            dismissJob?.cancel()
                            dismissJob = null
                            autoDismissJob?.cancel()
                            viewModel.resetState()

                            if (!dialog.isShowing) {
                                dialog.show()
                                controller.notifyVisible(true)
                            }

                            if (!state.isInteracting) {
                                rescheduleAutoDismiss()
                            }
                        }
                        VisibilityState.DISMISSED -> {
                            if (dialog.isShowing) {
                                viewModel.isInteracting = false

                                dismissJob = pluginScope?.launch {
                                    withTimeoutOrNull(500L) {
                                        viewModel.dismissAnimationEnd.first()
                                    }

                                    controller.notifyVisible(false)
                                    runCatching { dialog.dismiss() }

                                    viewModel.resetState()
                                    dismissJob = null
                                }
                            }
                            autoDismissJob?.cancel()
                        }
                        VisibilityState.QUICK_DISMISSED -> {
                            if (dialog.isShowing) {
                                viewModel.isInteracting = false
                                controller.notifyVisible(false)
                                runCatching { dialog.dismiss() }
                                viewModel.resetState()
                            }
                            autoDismissJob?.cancel()
                        }
                    }
                }
            }
        }
    }

    fun rescheduleAutoDismiss() {
        autoDismissJob?.cancel()
        val timeout = computeTimeout()
        autoDismissJob = pluginScope?.launch {
            delay(timeout)
            if (viewModel.visibilityState == VisibilityState.SHOWING && !viewModel.isInteracting) {
                viewModel.visibilityState = VisibilityState.DISMISSED
            }
        }
    }

    override fun destroy() {
        controller.removeCallback(controllerCallbacks)
        autoDismissJob?.cancel()
        dialog.dismiss()
        pluginScope?.cancel()
        pluginScope = null
        callback = null
    }
}
