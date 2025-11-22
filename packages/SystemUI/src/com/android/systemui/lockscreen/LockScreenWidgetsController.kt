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
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Handler
import android.view.View
import android.view.ViewGroup
import com.android.systemui.bluetooth.qsdialog.BluetoothDetailsContentViewModel
import com.android.systemui.common.ringer.RingerModeInteractorImpl
import com.android.systemui.qs.tiles.dialog.InternetDialogManager
import com.android.systemui.statusbar.connectivity.AccessPointController
import com.android.systemui.statusbar.connectivity.NetworkController
import com.android.systemui.statusbar.policy.*
import com.android.systemui.util.ScrimUtils
import dagger.Lazy

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
    val dataController = networkController.mobileDataController

    val ringerInteractor: RingerModeInteractorImpl = RingerModeInteractorImpl(context, audioManager)

    val states = WidgetStates()
    val factory = WidgetFactory(context, this)
    val callbacks = LsWidgetsCallbacksController(this)

    var settings: WidgetSettings = repository.settings
        set(value) {
            field = value
            updateVisibility(value.isEnabled)
            factory.updateViews()
        }

    val widgetSpecs: List<WidgetSpec>
        get() = settings.value
            .split(",")
            .mapNotNull { token ->
                val parts = token.split(":")
                val name = parts.getOrNull(0)?.trim() ?: return@mapNotNull null
                val typeStr = parts.getOrNull(1)?.trim()

                val action = WidgetAction.values()
                    .find { it.name.equals(name, ignoreCase = true) }
                    ?: return@mapNotNull null

                val type = WidgetType.fromString(typeStr)

                WidgetSpec(action, type)
            }

    var listening: Boolean = false
        set(value) {
            if (field == value) return
            if (value && enabled) startListeners() else stopListeners()
            field = value && enabled
        }

    val scrimUtils get() = ScrimUtils.get()
    val enabled get() = settings.isEnabled && widgetSpecs.isNotEmpty()

    init {
        callbacks.observe()
        factory.init()
    }

    private fun startListeners() {
        widgetSpecs.forEach { spec ->
            spec.action.registerCallback(this)
        }
    }

    private fun stopListeners() {
        widgetSpecs.forEach { spec ->
            spec.action.unregisterCallback(this)
        }
    }

    fun dispose() {
        callbacks.dispose()
        container.removeAllViews()
    }

    fun updateVisibility(visible: Boolean) {
        val vis = if (visible) View.VISIBLE else View.GONE
        factory.updateVisibility(vis)
        container.visibility = vis
    }

    fun showInternetDialog() {
        internetDialogManager.create(
            true,
            accessPointController.canConfigMobileData(),
            accessPointController.canConfigWifi(),
            null
        )
    }

    fun showBluetoothDialog() {
        detailsContentViewModel.get().showDialog(null)
    }
}
