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

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.Handler
import android.view.View
import android.view.ViewGroup
import com.android.systemui.bluetooth.qsdialog.BluetoothDetailsContentViewModel
import com.android.systemui.qs.tiles.dialog.InternetDialogManager
import com.android.systemui.statusbar.connectivity.*
import com.android.systemui.statusbar.policy.*
import com.android.systemui.util.*
import dagger.Lazy

data class WidgetSpec(val action: WidgetAction, val span: Int)

class LockScreenWidgetsController(
    internal val container: ViewGroup,
    internal val context: Context,
    internal val networkController: NetworkController,
    internal val configurationController: ConfigurationController,
    internal val bluetoothController: BluetoothController,
    internal val hotspotController: HotspotController,
    internal val accessPointController: AccessPointController,
    internal val internetDialogManager: InternetDialogManager,
    internal val detailsContentViewModel: Lazy<BluetoothDetailsContentViewModel>,
    internal val flashlightController: FlashlightController,
    internal val handler: Handler,
) {
    val repository = LockscreenWidgetSettingsRepository(context, this)

    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    val dataController = networkController.mobileDataController
    val states = WidgetStates(this)
    val factory = WidgetFactory(context, this)
    val callbacks = LsWidgetsCallbacksController(this)

    var updatingViews = false

    var cameraId: String? = null
    var isFlashOn = false
    var isRingerRegistered = false
    val listeners = mutableMapOf<String, () -> Unit>()

    var settings: WidgetSettings = repository.settings
        set(value) {
            field = value
            updateVisibility(value.isEnabled)
            updateViews(true)
        }

    val widgetSpecs: List<WidgetSpec>
        get() = settings.value
            .split(",")
            .mapNotNull { token ->
                val parts = token.split(":")
                val name = parts.getOrNull(0)?.trim() ?: return@mapNotNull null
                val span = parts.getOrNull(1)?.toIntOrNull() ?: 1
                WidgetAction.values()
                    .find { it.name.equals(name, ignoreCase = true) }
                    ?.let { WidgetSpec(it, span) }
            }

    var listening = false
        set(value) {
            if (field == value) return
            if (value && enabled) startListeners() else stopListeners()
            field = value && enabled
        }

    val scrimUtils get() = ScrimUtils.get()
    val enabled get() = settings.isEnabled && widgetSpecs.isNotEmpty()
    val bluetoothEnabled get() = BluetoothAdapter.getDefaultAdapter()?.isEnabled == true
    val dozing get() = scrimUtils.isDozing()

    init {
        runCatching { cameraId = cameraManager.cameraIdList.firstOrNull() }
        callbacks.observe()
        factory.init()
    }

    fun dispose() {
        callbacks.dispose()
        container.removeAllViews()
    }

    private fun startListeners() {
        listeners += widgetSpecs.associate { spec ->
            spec.action.registerCallback(this)
            "widget_${spec.action.name}" to { spec.action.unregisterCallback(this) }
        }
    }

    private fun stopListeners() {
        listeners.values.forEach { it.invoke() }
        listeners.clear()
    }

    private fun postUpdate(action: () -> Unit) = handler.post(action)

    fun updateViews(force: Boolean = false) {
        if (updatingViews) return
        updatingViews = true
        postUpdate {
            try {
                factory.updateViews(force)
            } finally {
                updatingViews = false
            }
        }
    }
    
    fun updateVisibility(visible: Boolean) {
        val vis = if (visible) View.VISIBLE else View.GONE
        factory.updateVisibility(vis)
        container.visibility = vis
    }

    fun showInternetDialog() = postUpdate {
        internetDialogManager.create(
            true,
            accessPointController.canConfigMobileData(),
            accessPointController.canConfigWifi(),
            null
        )
    }

    fun showBluetoothDialog() = postUpdate {
        detailsContentViewModel.get().showDialog(null)
    }
}
