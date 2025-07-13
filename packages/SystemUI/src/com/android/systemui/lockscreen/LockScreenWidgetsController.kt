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

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.view.View
import com.android.systemui.animation.Expandable
import com.android.systemui.animation.view.LaunchableImageView
import com.android.systemui.bluetooth.qsdialog.BluetoothDetailsContentViewModel
import com.android.systemui.qs.tiles.dialog.InternetDialogManager
import com.android.systemui.res.R
import com.android.systemui.statusbar.connectivity.*
import com.android.systemui.statusbar.policy.*
import com.android.systemui.util.*
import com.google.android.flexbox.FlexboxLayout
import dagger.Lazy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

class LockScreenWidgetsController(
    internal val view: View,
    internal val context: Context,
    internal val networkController: NetworkController,
    internal val configurationController: ConfigurationController,
    internal val bluetoothController: BluetoothController,
    internal val hotspotController: HotspotController,
    internal val accessPointController: AccessPointController,
    internal val internetDialogManager: InternetDialogManager,
    internal val detailsContentViewModel: Lazy<BluetoothDetailsContentViewModel>,
    internal val flashlightController: FlashlightController
) {

    val widgetSettingsRepository = LockscreenWidgetSettingsRepository(context)
    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    val dataController = networkController.mobileDataController
    val states = WidgetStates(this)
    val widgetFactory = WidgetFactory(context, this)

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var widgetFlowJob: Job? = null
    private var listening = false

    private var currentSettings: WidgetSettings? = null
    private val container: FlexboxLayout = view.findViewById(R.id.main_widgets_container)

    var cameraId: String? = null
    var dozing = false
    var isFlashOn = false
    var isRingerRegistered = false

    val listeners = mutableMapOf<String, () -> Unit>()
    val callbacks = LsWidgetsCallbacksController(this)

    private var mainWidgets = mutableListOf<WidgetAction>()
    val widgetButtons = mutableMapOf<WidgetAction, LaunchableImageView>()
    private val widgetViewCache = mutableMapOf<WidgetAction, LaunchableImageView>()

    fun init() {
        runCatching {
            cameraId = cameraManager.cameraIdList.firstOrNull()
        }
        ScrimUtils.get().addListener(callbacks.scrimUtils)
        startListening()
    }

    fun dispose() {
        stopListening()
        ScrimUtils.get().removeListener(callbacks.scrimUtils)
        scope.cancel()
    }

    private fun addListener(key: String, register: () -> Unit, unregister: () -> Unit) {
        register()
        listeners[key] = unregister
    }

    fun startListening() {
        if (widgetFlowJob != null) return
        widgetFlowJob = scope.launch {
            widgetSettingsRepository.widgetSettingsFlow.collectLatest { settings ->
                currentSettings = settings
                updateWidgetViews()
                val shouldEnableListeners = settings.isEnabled && widgetList.isNotEmpty()
                if (shouldEnableListeners && !listening) {
                    addListeners()
                } else if (!shouldEnableListeners && listening) {
                    cancelListeners()
                }
            }
        }
    }

    fun stopListening() {
        widgetFlowJob?.cancel()
        widgetFlowJob = null
        cancelListeners()
    }

    private fun addListeners() {
        if (listening) return
        addListener(
            key = "configurationListener",
            register = { configurationController.addCallback(callbacks.configurationListener) },
            unregister = { configurationController.removeCallback(callbacks.configurationListener) }
        )
        widgetList.forEach { widget ->
            widget.registerCallback(this)
            listeners["widget_${widget.name}"] = {
                widget.unregisterCallback(this)
            }
        }
        listening = true
    }

    private fun cancelListeners() {
        if (!listening) return
        listeners.values.forEach { it.invoke() }
        listeners.clear()
        listening = false
    }

    val widgetList: List<WidgetAction>
        get() = currentSettings?.settings?.let(::parseWidgets) ?: emptyList()

    private fun parseWidgets(setting: String): List<WidgetAction> {
        return setting.split(",")
            .mapNotNull { WidgetAction.values().find { wa -> wa.name.equals(it.trim(), ignoreCase = true) } }
    }

    fun updateWidgetViews() {
        container.removeAllViews()
        mainWidgets.clear()
        mainWidgets.addAll(widgetList)
        widgetButtons.clear()
        widgetList.take(4).forEach { action ->
            val widgetView = widgetViewCache[action] ?: widgetFactory.createWidgetView(action).also {
                widgetViewCache[action] = it
            }
            container.addView(widgetView)
            widgetButtons[action] = widgetView
        }
        updateWidgetsVisibility()
    }

    private fun updateWidgetsVisibility() {
        val settings = currentSettings ?: return
        val visible = settings.isEnabled && mainWidgets.isNotEmpty()
        val visibility = if (visible) View.VISIBLE else View.GONE
        container.visibility = visibility
        view.visibility = visibility
    }

    fun showInternetDialog(view: View) {
        view.post {
            internetDialogManager.create(
                true,
                accessPointController.canConfigMobileData(),
                accessPointController.canConfigWifi(),
                Expandable.fromView(view)
            )
        }
    }

    fun showBluetoothDialog(view: View) {
        view.post {
            detailsContentViewModel.get().showDialog(Expandable.fromView(view))
        }
    }

    fun isBluetoothEnabled() = BluetoothAdapter.getDefaultAdapter()?.isEnabled == true
    
    fun maybeKeyguardDismiss(dismiss: Boolean) {
        if (dismiss) {
            stopListening()
        } else {
            scope.launch {
                delay(500)
                startListening()
            }
        }
    }
}
