/*
 * Copyright (C) 2025 the AxionAOSP Project
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
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.UserHandle
import android.provider.Settings
import android.view.KeyEvent
import android.view.View

import androidx.core.content.ContextCompat

import com.android.settingslib.net.DataUsageController

import com.android.systemui.Dependency
import com.android.systemui.res.R
import com.android.systemui.animation.Expandable
import com.android.systemui.animation.view.LaunchableImageView
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.bluetooth.qsdialog.BluetoothTileDialogViewModel
import com.android.systemui.qs.tiles.dialog.InternetDialogManager
import com.android.systemui.statusbar.connectivity.*
import com.android.systemui.statusbar.policy.*
import com.android.systemui.util.*

import com.android.internal.util.android.VibrationUtils

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

object LockScreenWidgetsController {
    private val viewControllers = mutableMapOf<View, ViewController>()

    fun addView(view: View) {
        if (viewControllers.containsKey(view)) return
        val controller = ViewController(view)
        controller.init()
        viewControllers[view] = controller
    }

    fun removeView(view: View) {
        viewControllers.remove(view)?.deInit()
    }

    class ViewController(private val view: View) : MediaSessionManagerHelper.MediaMetadataListener {
        val context: Context = view.context
        val mediaSessionManagerHelper = MediaSessionManagerHelper.getInstance(context)
        val activityLauncherUtils = ActivityLauncherUtils(context)
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val dataController: DataUsageController =
            Dependency.get(NetworkController::class.java).mobileDataController
        
        private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        data class WidgetSettings(val settings: String, val isEnabled: Boolean)
        private val widgetsFlow = MutableStateFlow(getCurrentWidgetSettings())

        private var darkColor = 0
        private var darkColorActive = 0
        private var lightColor = 0
        private var lightColorActive = 0

        private var cameraId: String? = null
        private var mainWidgets = mutableListOf<WidgetAction>()
        private val widgetButtons = mutableMapOf<WidgetAction, LaunchableImageView>()

        var dozing = false
        var isFlashOn = false
        var isRingerReceiverRegistered = false

        val callbacks = LsWidgetsCallbacksController(this)

        fun init() {
            observeWidgetSettings()
            runCatching {
                cameraId = cameraManager.cameraIdList.firstOrNull()
            }
            scope.launch {
                widgetsFlow.collectLatest { (settings, isEnabled) ->
                    updateWidgetViews()
                }
            }
            updateWidgetViews()
        }
        
        fun deInit() {
            clearCallbacks()
            scope.cancel()
        }
        
        fun clearCallbacks() {
            if (isRingerReceiverRegistered) {
                context.unregisterReceiver(callbacks.ringerModeReceiver)
                isRingerReceiverRegistered = false
            }

            Dependency.get(ConfigurationController::class.java).removeCallback(callbacks.configurationListener)
            Dependency.get(StatusBarStateController::class.java).removeCallback(callbacks.statusBarStateListener)

            mainWidgets.forEach { it.unregisterCallback(this) }   
        }
        
        private fun observeWidgetSettings() {
            scope.launch {
                while (isActive) {
                    val newSettings = getCurrentWidgetSettings()
                    if (widgetsFlow.value != newSettings) {
                        widgetsFlow.value = newSettings
                    }
                    delay(1000)
                }
            }
        }

        private fun getCurrentWidgetSettings(): WidgetSettings {
            val settings = Settings.System.getStringForUser(
                context.contentResolver, "lockscreen_widgets_extras", UserHandle.USER_CURRENT
            ) ?: ""
            val isEnabled = Settings.System.getIntForUser(
                context.contentResolver, "lockscreen_widgets_enabled", 0, UserHandle.USER_CURRENT
            ) == 1
            return WidgetSettings(settings, isEnabled)
        }

        fun updateWidgetViews() {
            val widgetsSetting = Settings.System.getStringForUser(
                context.contentResolver, "lockscreen_widgets_extras", UserHandle.USER_CURRENT
            )
            
            mainWidgets.clear()
            widgetsSetting?.split(",")
                ?.mapNotNull { type ->
                    WidgetAction.values().find { it.name.equals(type.trim(), ignoreCase = true) }
                }
                ?.let(mainWidgets::addAll)

            updateColors()

            LsWidgetsRes.WIDGETS_VIEW_IDS.forEach { id ->
                view.findViewById<LaunchableImageView>(id)?.visibility = View.GONE
            }

            val widgetViews = LsWidgetsRes.WIDGETS_VIEW_IDS
                .map { view.findViewById<LaunchableImageView>(it) }
                .filterNotNull()

            mainWidgets.forEachIndexed { index, action ->
                if (index >= widgetViews.size) return@forEachIndexed
                val widgetView = widgetViews[index]
                widgetView.visibility = View.VISIBLE
                setUpWidgetView(widgetView, action)
                updateWidgetResources(widgetView, action)
            }

            clearCallbacks()

            Dependency.get(ConfigurationController::class.java).addCallback(callbacks.configurationListener)
            Dependency.get(StatusBarStateController::class.java).addCallback(callbacks.statusBarStateListener)
            callbacks.statusBarStateListener.onDozingChanged(
                Dependency.get(StatusBarStateController::class.java).isDozing
            )

            mainWidgets.forEach { it.registerCallback(this) }
            updateContainerVisibility()
        }

        fun updateContainerVisibility() {
            val enabled = Settings.System.getIntForUser(
                context.contentResolver, "lockscreen_widgets_enabled", 0, UserHandle.USER_CURRENT
            ) == 1
            val hasWidgets = mainWidgets.isNotEmpty()
            val lockscreenWidgetsEnabled = hasWidgets && enabled
            
            view.findViewById<View>(R.id.main_widgets_container)?.visibility = 
                if (lockscreenWidgetsEnabled) View.VISIBLE else View.GONE
            view.visibility = if (lockscreenWidgetsEnabled && !dozing) View.VISIBLE else View.GONE
        }

        private fun setUpWidgetView(iv: LaunchableImageView, action: WidgetAction) {
            iv.setOnClickListener { action.onClick(this) }
            action.onLongClick?.let { longClick ->
                iv.setOnLongClickListener { longClick(this, it) }
            }
            iv.setImageResource(action.inactiveRes)
            widgetButtons[action] = iv
        }

        private fun updateWidgetResources(iv: LaunchableImageView, action: WidgetAction) {
            iv.setImageResource(action.inactiveRes)
            iv.setBackgroundResource(R.drawable.lockscreen_widget_background_circle)
            setButtonActiveState(iv, false)
        }

        private fun updateColors() {
            darkColor = ContextCompat.getColor(context, R.color.lockscreen_widget_background_color_dark)
            lightColor = ContextCompat.getColor(context, R.color.lockscreen_widget_background_color_light)
            darkColorActive = ContextCompat.getColor(context, R.color.lockscreen_widget_active_color_dark)
            lightColorActive = ContextCompat.getColor(context, R.color.lockscreen_widget_active_color_light)
        }

        private fun isNightMode(): Boolean =
            (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES

        private fun setButtonActiveState(iv: LaunchableImageView?, active: Boolean) {
            iv?.apply {
                val (bgTint, tintColor) = when {
                    active && isNightMode() -> darkColorActive to darkColor
                    active -> lightColorActive to lightColor
                    isNightMode() -> darkColor to lightColor
                    else -> lightColor to darkColor
                }
                backgroundTintList = ColorStateList.valueOf(bgTint)
                imageTintList = ColorStateList.valueOf(tintColor)
            }
        }

        fun updateTileButtonState(action: WidgetAction, active: Boolean) {
            view.post {
                widgetButtons[action]?.let { iv ->
                    iv.setImageResource(if (active) action.activeRes else action.inactiveRes)
                    setButtonActiveState(iv, active)
                }
            }
        }

        fun toggleMediaPlaybackState() {
            mediaSessionManagerHelper.toggleMediaPlaybackState()
            view.postDelayed(::updateMediaPlaybackState, 250)
        }

        fun updateMediaPlaybackState() {
            updateTileButtonState(WidgetAction.MEDIA, mediaSessionManagerHelper.isMediaPlaying())
        }

        fun toggleFlashlight() {
            cameraId?.let { id ->
                runCatching {
                    cameraManager.setTorchMode(id, !isFlashOn)
                    isFlashOn = !isFlashOn
                    updateTorchButtonState()
                }
            }
        }

        fun toggleWiFi() {
            val enabled = !callbacks.getWifiCallbackInfo().enabled
            Dependency.get(NetworkController::class.java).setWifiEnabled(enabled)
            updateTileButtonState(WidgetAction.WIFI, enabled)
        }

        fun toggleMobileData() {
            val enabled = !dataController.isMobileDataEnabled
            dataController.setMobileDataEnabled(enabled)
            updateTileButtonState(WidgetAction.DATA, enabled)
        }

        fun toggleRingerMode() {
            audioManager.ringerMode = when (audioManager.ringerMode) {
                AudioManager.RINGER_MODE_NORMAL -> AudioManager.RINGER_MODE_VIBRATE
                else -> AudioManager.RINGER_MODE_NORMAL
            }
            updateTileButtonState(WidgetAction.RINGER, audioManager.ringerMode == AudioManager.RINGER_MODE_VIBRATE)
        }

        fun toggleBluetooth() {
            val enabled = !isBluetoothEnabled()
            Dependency.get(BluetoothController::class.java).setBluetoothEnabled(enabled)
            updateTileButtonState(WidgetAction.BT, enabled)
        }

        fun isBluetoothEnabled() = BluetoothAdapter.getDefaultAdapter()?.isEnabled == true

        fun toggleHotspot() {
            val controller = Dependency.get(HotspotController::class.java)
            controller.setHotspotEnabled(!controller.isHotspotEnabled)
            updateTileButtonState(WidgetAction.HOTSPOT, controller.isHotspotEnabled)
        }

        fun updateTorchButtonState() {
            updateTileButtonState(WidgetAction.TORCH, isFlashOn)
        }

        fun updateWiFiButtonState(enabled: Boolean) {
            updateTileButtonState(WidgetAction.WIFI, enabled)
        }

        fun updateRingerButtonState() {
            updateTileButtonState(WidgetAction.RINGER, audioManager.ringerMode == AudioManager.RINGER_MODE_VIBRATE)
        }

        fun updateMobileDataState(enabled: Boolean) {
            updateTileButtonState(WidgetAction.DATA, enabled)
        }
        
        fun updateBtState() {
            updateTileButtonState(WidgetAction.BT, isBluetoothEnabled())
        }

        fun updateHotspotState() {
            updateTileButtonState(WidgetAction.HOTSPOT, Dependency.get(HotspotController::class.java).isHotspotEnabled)
        }

        fun showInternetDialog(view: View) {
            view.post {
                Dependency.get(InternetDialogManager::class.java).create(
                    true,
                    Dependency.get(AccessPointController::class.java).canConfigMobileData(),
                    Dependency.get(AccessPointController::class.java).canConfigWifi(),
                    Expandable.fromView(view)
                )
            }
            VibrationUtils.triggerVibration(context, 2)
        }

        fun showBluetoothDialog(view: View) {
            view.post {
                Dependency.get(BluetoothTileDialogViewModel::class.java)
                    .showDialog(Expandable.fromView(view))
            }
            VibrationUtils.triggerVibration(context, 2)
        }

        override fun onMediaMetadataChanged() {
            updateMediaPlaybackState()
        }

        override fun onPlaybackStateChanged() {
            updateMediaPlaybackState()
        }
    }
}
