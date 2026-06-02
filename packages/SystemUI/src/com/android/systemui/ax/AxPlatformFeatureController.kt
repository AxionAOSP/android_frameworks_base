/*
 * Copyright (C) 2025-2026 AxionOS
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

package com.android.systemui.ax

import android.app.UiModeManager
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.hardware.SensorPrivacyManager
import android.hardware.display.ColorDisplayManager
import android.media.AudioManager
import android.media.projection.StopReason
import android.net.TetheringManager
import android.nfc.NfcAdapter
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.RemoteException
import android.os.ServiceManager
import android.os.UserHandle
import android.os.Vibrator
import android.provider.Settings
import android.service.dreams.IDreamManager
import android.util.Log
import android.view.WindowManager
import com.android.axion.platform.AxFeatureState
import com.android.axion.platform.AxPlatformClient
import com.android.axion.platform.AxPlatformFeature
import com.android.internal.util.ScreenshotHelper
import com.android.settingslib.bluetooth.CachedBluetoothDevice
import com.android.settingslib.bluetooth.LocalBluetoothManager
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.screenrecord.ScreenRecordUxController
import com.android.systemui.statusbar.connectivity.AccessPointController
import com.android.systemui.statusbar.connectivity.NetworkController
import com.android.systemui.statusbar.phone.ManagedProfileController
import com.android.systemui.statusbar.policy.BatteryController
import com.android.systemui.statusbar.policy.BluetoothController
import com.android.systemui.statusbar.policy.CastController
import com.android.systemui.statusbar.policy.DataSaverController
import com.android.systemui.statusbar.policy.FlashlightController
import com.android.systemui.statusbar.policy.HotspotController
import com.android.systemui.statusbar.policy.IndividualSensorPrivacyController
import com.android.systemui.statusbar.policy.LocationController
import com.android.systemui.statusbar.policy.RotationLockController
import com.android.systemui.statusbar.policy.SecurityController
import com.android.systemui.statusbar.policy.ZenModeController
import com.android.wifitrackerlib.WifiEntry
import javax.inject.Inject
import lineageos.app.ProfileManager
import lineageos.hardware.LineageHardwareManager
import lineageos.providers.LineageSettings

@SysUISingleton
class AxPlatformFeatureController @Inject constructor(
    private val context: Context,
    private val stateManager: AxPlatformStateManager,
    private val networkController: NetworkController,
    private val accessPointController: AccessPointController,
    private val bluetoothController: BluetoothController,
    private val hotspotController: HotspotController,
    private val flashlightController: FlashlightController,
    private val locationController: LocationController,
    private val rotationLockController: RotationLockController,
    private val batteryController: BatteryController,
    private val zenModeController: ZenModeController,
    private val dataSaverController: DataSaverController,
    private val localBluetoothManager: LocalBluetoothManager?,
    private val sensorPrivacyController: IndividualSensorPrivacyController,
    private val managedProfileController: ManagedProfileController,
    private val securityController: SecurityController,
    private val castController: CastController,
    private val screenRecordUxController: ScreenRecordUxController,
    powerManager: PowerManager,
) {

    internal val wakeLock: PowerManager.WakeLock =
        powerManager.newWakeLock(PowerManager.FULL_WAKE_LOCK, "AxPlatform:Caffeine")
    internal val dreamManager: IDreamManager? =
        IDreamManager.Stub.asInterface(ServiceManager.getService("dreams"))
    internal val lineageHardware: LineageHardwareManager? = try {
        LineageHardwareManager.getInstance(context)
    } catch (_: Exception) {
        null
    }

    private val profileManager: ProfileManager? = try {
        ProfileManager.getInstance(context)
    } catch (_: Exception) {
        null
    }
    private val nfcAdapter: NfcAdapter? = NfcAdapter.getDefaultAdapter(context)
    private val audioManager: AudioManager? = context.getSystemService(AudioManager::class.java)
    private val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    private val uiModeManager: UiModeManager = context.getSystemService(UiModeManager::class.java)
    private val colorDisplayManager: ColorDisplayManager =
        context.getSystemService(ColorDisplayManager::class.java)
    private val tetheringManager: TetheringManager? =
        context.getSystemService(TetheringManager::class.java)
    private val screenshotHelper = ScreenshotHelper(context)
    private val screenshotHandler = Handler(Looper.getMainLooper())

    private data class FeatureHandler(
        val feature: String,
        val isSupported: () -> Boolean = { true },
        val toggle: (() -> Unit)? = null,
        val setEnabled: ((Boolean) -> Unit)? = null,
        val setValue: ((Int) -> Unit)? = null,
    )

    private val handlers: Map<String, FeatureHandler> by lazy {
        listOf(
            FeatureHandler(
                AxPlatformFeature.WIFI,
                toggle = { networkController.setWifiEnabled(!featureState(AxPlatformFeature.WIFI).isEnabled) },
                setEnabled = networkController::setWifiEnabled,
            ),
            FeatureHandler(
                AxPlatformFeature.MOBILE_DATA,
                toggle = { networkController.mobileDataController?.let { it.isMobileDataEnabled = !it.isMobileDataEnabled } },
                setEnabled = { enabled -> networkController.mobileDataController?.let { it.isMobileDataEnabled = enabled } },
            ),
            FeatureHandler(
                AxPlatformFeature.BLUETOOTH,
                toggle = { bluetoothController.setBluetoothEnabled(!bluetoothController.isBluetoothEnabled) },
                setEnabled = bluetoothController::setBluetoothEnabled,
            ),
            FeatureHandler(
                AxPlatformFeature.HOTSPOT,
                toggle = { hotspotController.setHotspotEnabled(!featureState(AxPlatformFeature.HOTSPOT).isEnabled) },
                setEnabled = hotspotController::setHotspotEnabled,
            ),
            FeatureHandler(
                AxPlatformFeature.FLASHLIGHT,
                toggle = { setFlashlightEnabled(!flashlightController.isEnabled) },
                setEnabled = ::setFlashlightEnabled,
            ),
            FeatureHandler(
                AxPlatformFeature.LOCATION,
                toggle = { locationController.setLocationEnabled(!locationController.isLocationEnabled) },
                setEnabled = locationController::setLocationEnabled,
            ),
            FeatureHandler(
                AxPlatformFeature.ROTATION,
                toggle = { rotationLockController.setRotationLocked(!rotationLockController.isRotationLocked, TAG) },
                setEnabled = { enabled -> rotationLockController.setRotationLocked(!enabled, TAG) },
            ),
            FeatureHandler(
                AxPlatformFeature.BATTERY_SAVER,
                toggle = { batteryController.setPowerSaveMode(!batteryController.isPowerSave) },
                setEnabled = batteryController::setPowerSaveMode,
            ),
            FeatureHandler(
                AxPlatformFeature.ZEN,
                toggle = { setZenMode(if (zenModeController.zen == 0) 1 else 0) },
                setEnabled = { enabled -> setZenMode(if (enabled) 1 else 0) },
                setValue = ::setZenMode,
            ),
            FeatureHandler(
                AxPlatformFeature.AOD,
                toggle = { stateManager.toggleSecure(Settings.Secure.DOZE_ALWAYS_ON) },
                setEnabled = { enabled -> stateManager.setSecureBool(Settings.Secure.DOZE_ALWAYS_ON, enabled) },
            ),
            FeatureHandler(
                AxPlatformFeature.AMBIENT_DISPLAY,
                toggle = { stateManager.toggleSecure(Settings.Secure.DOZE_ENABLED) },
                setEnabled = { enabled -> stateManager.setSecureBool(Settings.Secure.DOZE_ENABLED, enabled) },
            ),
            FeatureHandler(
                AxPlatformFeature.DATA_SAVER,
                toggle = { dataSaverController.setDataSaverEnabled(!dataSaverController.isDataSaverEnabled) },
                setEnabled = dataSaverController::setDataSaverEnabled,
            ),
            FeatureHandler(
                AxPlatformFeature.AIRPLANE_MODE,
                toggle = { setAirplaneModeEnabled(!stateManager.getGlobalBool(Settings.Global.AIRPLANE_MODE_ON)) },
                setEnabled = ::setAirplaneModeEnabled,
            ),
            FeatureHandler(
                AxPlatformFeature.DARK_MODE,
                toggle = { uiModeManager.setNightModeActivated(!isDarkMode(context.resources.configuration)) },
                setEnabled = { enabled -> uiModeManager.setNightModeActivated(enabled) },
            ),
            FeatureHandler(
                AxPlatformFeature.NIGHT_LIGHT,
                toggle = { colorDisplayManager.setNightDisplayActivated(!colorDisplayManager.isNightDisplayActivated) },
                setEnabled = { enabled -> colorDisplayManager.setNightDisplayActivated(enabled) },
            ),
            FeatureHandler(
                AxPlatformFeature.COLOR_INVERSION,
                toggle = { stateManager.toggleSecure(Settings.Secure.ACCESSIBILITY_DISPLAY_INVERSION_ENABLED) },
                setEnabled = { enabled -> stateManager.setSecureBool(Settings.Secure.ACCESSIBILITY_DISPLAY_INVERSION_ENABLED, enabled) },
            ),
            FeatureHandler(
                AxPlatformFeature.COLOR_CORRECTION,
                toggle = { stateManager.toggleSecure(Settings.Secure.ACCESSIBILITY_DISPLAY_DALTONIZER_ENABLED) },
                setEnabled = { enabled -> stateManager.setSecureBool(Settings.Secure.ACCESSIBILITY_DISPLAY_DALTONIZER_ENABLED, enabled) },
            ),
            FeatureHandler(
                AxPlatformFeature.REDUCE_BRIGHTNESS,
                toggle = { stateManager.toggleSecure(SETTING_REDUCE_BRIGHT) },
                setEnabled = { enabled -> stateManager.setSecureBool(SETTING_REDUCE_BRIGHT, enabled) },
            ),
            FeatureHandler(
                AxPlatformFeature.ONE_HANDED_MODE,
                toggle = { stateManager.toggleSecure(SETTING_ONE_HANDED) },
                setEnabled = { enabled -> stateManager.setSecureBool(SETTING_ONE_HANDED, enabled) },
            ),
            FeatureHandler(
                AxPlatformFeature.HEADS_UP,
                toggle = { stateManager.toggleGlobal(Settings.Global.HEADS_UP_NOTIFICATIONS_ENABLED) },
                setEnabled = { enabled -> stateManager.setGlobalBool(Settings.Global.HEADS_UP_NOTIFICATIONS_ENABLED, enabled) },
            ),
            FeatureHandler(
                AxPlatformFeature.AUTO_SYNC,
                toggle = { ContentResolver.setMasterSyncAutomatically(!ContentResolver.getMasterSyncAutomatically()) },
                setEnabled = { enabled -> ContentResolver.setMasterSyncAutomatically(enabled) },
            ),
            FeatureHandler(
                AxPlatformFeature.NFC,
                isSupported = { nfcAdapter != null },
                toggle = { nfcAdapter?.let { if (it.isEnabled) it.disable() else it.enable() } },
                setEnabled = { enabled -> nfcAdapter?.let { if (enabled) it.enable() else it.disable() } },
            ),
            sensorPrivacyHandler(AxPlatformFeature.CAMERA_PRIVACY, SensorPrivacyManager.Sensors.CAMERA),
            sensorPrivacyHandler(AxPlatformFeature.MIC_PRIVACY, SensorPrivacyManager.Sensors.MICROPHONE),
            FeatureHandler(
                AxPlatformFeature.WORK_PROFILE,
                toggle = { managedProfileController.setWorkModeEnabled(!managedProfileController.isWorkModeEnabled) },
                setEnabled = managedProfileController::setWorkModeEnabled,
            ),
            FeatureHandler(
                AxPlatformFeature.USB_TETHER,
                isSupported = { tetheringManager?.isTetheringSupported == true },
                toggle = { tetheringManager?.setUsbTethering(!featureState(AxPlatformFeature.USB_TETHER).isActive) },
                setEnabled = { enabled -> tetheringManager?.setUsbTethering(enabled) },
            ),
            FeatureHandler(
                AxPlatformFeature.DREAM,
                isSupported = { dreamManager != null },
                toggle = ::toggleDream,
                setEnabled = ::setDreamEnabled,
            ),
            FeatureHandler(
                AxPlatformFeature.READING_MODE,
                isSupported = { lineageHardware?.isSupported(LineageHardwareManager.FEATURE_READING_ENHANCEMENT) == true },
                toggle = ::toggleReadingMode,
                setEnabled = ::setReadingModeEnabled,
            ),
            FeatureHandler(
                AxPlatformFeature.POWER_SHARE,
                isSupported = { batteryController.isReverseSupported },
                toggle = { batteryController.setReverseState(!batteryController.isReverseOn) },
                setEnabled = batteryController::setReverseState,
            ),
            FeatureHandler(
                AxPlatformFeature.CAFFEINE,
                toggle = { setCaffeineEnabled(!wakeLock.isHeld) },
                setEnabled = ::setCaffeineEnabled,
            ),
            FeatureHandler(
                AxPlatformFeature.VPN,
                toggle = ::disconnectVpn,
                setEnabled = { enabled -> if (!enabled) disconnectVpn() },
            ),
            FeatureHandler(
                AxPlatformFeature.CAST,
                toggle = ::stopCasting,
                setEnabled = { enabled -> if (!enabled) stopCasting() },
            ),
            FeatureHandler(
                AxPlatformFeature.PROFILES,
                isSupported = { profileManager != null },
                toggle = ::toggleProfiles,
                setEnabled = ::setProfilesFeatureEnabled,
            ),
            FeatureHandler(
                AxPlatformFeature.SMART_PIXELS,
                toggle = { stateManager.toggleSecure(SETTING_SMART_PIXELS) },
                setEnabled = { enabled -> stateManager.setSecureBool(SETTING_SMART_PIXELS, enabled) },
            ),
            FeatureHandler(
                AxPlatformFeature.RINGER_MODE,
                isSupported = { audioManager != null },
                toggle = ::toggleRingerMode,
                setEnabled = { enabled -> setRingerMode(if (enabled) AudioManager.RINGER_MODE_NORMAL else AudioManager.RINGER_MODE_SILENT) },
                setValue = ::setRingerMode,
            ),
            FeatureHandler(
                AxPlatformFeature.SCREEN_RECORD,
                toggle = ::toggleScreenRecord,
                setEnabled = { enabled -> if (!enabled && screenRecordUxController.isRecording) screenRecordUxController.stopRecording(StopReason.STOP_QS_TILE) },
            ),
            FeatureHandler(
                AxPlatformFeature.SCREENSHOT,
                toggle = ::takeScreenshot,
                setEnabled = { enabled -> if (enabled) takeScreenshot() },
            ),
        ).filter { it.isSupported() }.associateBy { it.feature }
    }

    var latestAccessPoints: List<WifiEntry> = emptyList()

    val supportedFeatures: Array<String> by lazy { handlers.keys.toTypedArray() }

    fun toggle(feature: String) {
        handlers[feature]?.toggle?.invoke() ?: Log.w(TAG, "Unknown toggle: $feature")
    }

    fun setEnabled(feature: String, enabled: Boolean) {
        handlers[feature]?.setEnabled?.invoke(enabled) ?: Log.w(TAG, "Unknown setEnabled: $feature")
    }

    fun setValue(feature: String, value: Int) {
        handlers[feature]?.setValue?.invoke(value) ?: Log.w(TAG, "Unknown setValue: $feature")
    }

    fun performAction(feature: String, param: String) {
        when (feature) {
            AxPlatformClient.ACTION_WIFI_CONNECT ->
                latestAccessPoints
                    .find { it.getKey() == param || it.getTitle() == param }
                    ?.let { accessPointController.connect(it) }
            AxPlatformClient.ACTION_BT_CONNECT ->
                getAllBluetoothDevices().find { it.getAddress() == param }?.let {
                    if (it.isConnected()) it.disconnect() else it.connect(true)
                }
            else -> Log.w(TAG, "Unknown performAction: $feature")
        }
    }

    fun getAllBluetoothDevices(): Collection<CachedBluetoothDevice> =
        localBluetoothManager?.cachedDeviceManager?.cachedDevicesCopy
            ?: bluetoothController.connectedDevices

    private fun sensorPrivacyHandler(feature: String, sensor: Int) =
        FeatureHandler(
            feature,
            isSupported = { sensorPrivacyController.supportsSensorToggle(sensor) },
            toggle = {
                sensorPrivacyController.setSensorBlocked(
                    SensorPrivacyManager.Sources.QS_TILE,
                    sensor,
                    !sensorPrivacyController.isSensorBlocked(sensor),
                )
            },
            setEnabled = { enabled ->
                sensorPrivacyController.setSensorBlocked(
                    SensorPrivacyManager.Sources.QS_TILE,
                    sensor,
                    enabled,
                )
            },
        )

    private fun featureState(feature: String): AxFeatureState =
        AxFeatureState.fromBundle(stateManager.getState(feature))

    private fun setFlashlightEnabled(enabled: Boolean) {
        if (flashlightController.hasFlashlight()) flashlightController.setFlashlight(enabled)
    }

    private fun setZenMode(mode: Int) {
        if (zenModeController.zen != mode) zenModeController.setZen(mode, null, TAG)
    }

    private fun setAirplaneModeEnabled(enabled: Boolean) {
        stateManager.setGlobalBool(Settings.Global.AIRPLANE_MODE_ON, enabled)
        context.sendBroadcastAsUser(
            Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED).putExtra("state", enabled),
            UserHandle.ALL,
        )
    }

    private fun toggleDream() {
        try {
            dreamManager?.let { if (it.isDreaming) it.awaken() else it.dream() }
        } catch (e: RemoteException) {
            Log.w(TAG, "Dream toggle failed", e)
        }
    }

    private fun setDreamEnabled(enabled: Boolean) {
        try {
            dreamManager?.let { if (enabled) it.dream() else it.awaken() }
        } catch (e: RemoteException) {
            Log.w(TAG, "Dream setEnabled failed", e)
        }
    }

    private fun toggleReadingMode() {
        val manager = lineageHardware ?: return
        val current = manager.get(LineageHardwareManager.FEATURE_READING_ENHANCEMENT)
        manager.set(LineageHardwareManager.FEATURE_READING_ENHANCEMENT, !current)
        stateManager.broadcastBool(AxPlatformFeature.READING_MODE, !current)
    }

    private fun setReadingModeEnabled(enabled: Boolean) {
        lineageHardware?.set(LineageHardwareManager.FEATURE_READING_ENHANCEMENT, enabled)
        stateManager.broadcastBool(AxPlatformFeature.READING_MODE, enabled)
    }

    private fun setCaffeineEnabled(enabled: Boolean) {
        if (enabled && !wakeLock.isHeld) {
            wakeLock.acquire(CAFFEINE_DURATION_MS)
        } else if (!enabled && wakeLock.isHeld) {
            wakeLock.release()
        }
        stateManager.broadcastBool(AxPlatformFeature.CAFFEINE, wakeLock.isHeld)
    }

    private fun disconnectVpn() {
        if (securityController.isVpnEnabled) securityController.disconnectPrimaryVpn()
    }

    private fun stopCasting() {
        castController.castDevices.firstOrNull { it.isCasting }
            ?.let { castController.stopCasting(it, StopReason.STOP_QS_TILE) }
    }

    private fun setProfilesFeatureEnabled(enabled: Boolean) {
        setProfilesEnabled(enabled)
        stateManager.broadcastBool(AxPlatformFeature.PROFILES, enabled)
    }

    private fun toggleProfiles() {
        setProfilesFeatureEnabled(!profilesEnabled())
    }

    private fun toggleScreenRecord() {
        when {
            screenRecordUxController.isStarting -> screenRecordUxController.cancelCountdown()
            screenRecordUxController.isRecording ->
                screenRecordUxController.stopRecording(StopReason.STOP_QS_TILE)
            else -> screenRecordUxController.createScreenRecordDialog(null).show()
        }
    }

    private fun takeScreenshot() {
        screenshotHandler.postDelayed({
            screenshotHelper.takeScreenshot(
                WindowManager.TAKE_SCREENSHOT_FULLSCREEN,
                WindowManager.ScreenshotSource.SCREENSHOT_OTHER,
                screenshotHandler,
                null,
            )
        }, SCREENSHOT_DELAY_MS)
    }

    private fun toggleRingerMode() {
        val manager = audioManager ?: return
        val modes = availableRingerModes()
        val currentIndex = modes.indexOf(manager.ringerModeInternal)
        manager.ringerModeInternal = modes[if (currentIndex >= 0) (currentIndex + 1) % modes.size else 0]
    }

    private fun setRingerMode(mode: Int) {
        if (mode in availableRingerModes()) audioManager?.ringerModeInternal = mode
    }

    private fun availableRingerModes(): IntArray =
        if (vibrator?.hasVibrator() == true) {
            intArrayOf(
                AudioManager.RINGER_MODE_NORMAL,
                AudioManager.RINGER_MODE_VIBRATE,
                AudioManager.RINGER_MODE_SILENT,
            )
        } else {
            intArrayOf(
                AudioManager.RINGER_MODE_NORMAL,
                AudioManager.RINGER_MODE_SILENT,
            )
        }

    private fun profilesEnabled(): Boolean =
        LineageSettings.System.getIntForUser(
            context.contentResolver,
            LineageSettings.System.SYSTEM_PROFILES_ENABLED,
            1,
            UserHandle.USER_CURRENT,
        ) == 1

    private fun setProfilesEnabled(enabled: Boolean) {
        LineageSettings.System.putIntForUser(
            context.contentResolver,
            LineageSettings.System.SYSTEM_PROFILES_ENABLED,
            if (enabled) 1 else 0,
            UserHandle.USER_CURRENT,
        )
    }

    companion object {
        private const val TAG = "AxPlatformFeatureCtrl"
        private const val CAFFEINE_DURATION_MS = 5L * 60 * 1000
        private const val SCREENSHOT_DELAY_MS = 500L
        const val SETTING_NIGHT_DISPLAY = "night_display_activated"
        const val SETTING_REDUCE_BRIGHT = "reduce_bright_colors_activated"
        const val SETTING_ONE_HANDED = "one_handed_mode_enabled"
        const val SETTING_SMART_PIXELS = "ax_smart_pixel_filter_enabled"

        fun isDarkMode(config: Configuration): Boolean =
            (config.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }
}
