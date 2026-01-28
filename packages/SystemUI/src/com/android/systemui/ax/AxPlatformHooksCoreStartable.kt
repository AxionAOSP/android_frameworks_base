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

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ResolveInfo
import android.service.quicksettings.TileService
import com.android.systemui.util.settings.SecureSettings
import com.android.systemui.util.settings.SettingsProxyExt.observerFlow
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.android.settingslib.bluetooth.LocalBluetoothManager
import com.android.systemui.res.R
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.statusbar.connectivity.AccessPointController
import com.android.systemui.statusbar.connectivity.MobileDataIndicators
import com.android.systemui.statusbar.connectivity.NetworkController
import com.android.systemui.statusbar.connectivity.SignalCallback
import com.android.systemui.statusbar.connectivity.WifiIndicators
import com.android.systemui.statusbar.policy.BatteryController
import com.android.systemui.statusbar.policy.BluetoothController
import com.android.systemui.statusbar.policy.FlashlightController
import com.android.systemui.statusbar.policy.HotspotController
import com.android.systemui.statusbar.policy.LocationController
import com.android.systemui.statusbar.policy.RotationLockController
import com.android.systemui.statusbar.policy.ZenModeController
import com.android.wifitrackerlib.WifiEntry
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import androidx.annotation.Nullable
import com.android.systemui.qs.QSHost
import com.android.systemui.plugins.qs.QSTile

@SysUISingleton
class AxPlatformHooksCoreStartable @Inject constructor(
    private val secureSettings: SecureSettings,
    @Main private val mainDispatcher: CoroutineDispatcher,
    @Background private val bgDispatcher: CoroutineDispatcher,
    @Application private val scope: CoroutineScope,
    private val networkController: NetworkController,
    private val accessPointController: AccessPointController,
    private val bluetoothController: BluetoothController,
    private val hotspotController: HotspotController,
    private val flashlightController: FlashlightController,
    private val locationController: LocationController,
    private val rotationLockController: RotationLockController,
    private val batteryController: BatteryController,
    private val zenModeController: ZenModeController,
    @Nullable private val localBluetoothManager: LocalBluetoothManager?,
    private val qsHost: QSHost
) : CoreStartable {

    companion object {
        private const val TAG = "AxPlatformHooks"
        
        const val KEY_WIFI_ENABLED = "ax_wifi_enabled"
        const val KEY_WIFI_INFO = "ax_wifi_info"
        const val KEY_WIFI_SCAN_LIST = "ax_wifi_scan_list"
        const val KEY_WIFI_CONNECT_KEY = "ax_wifi_connect_key"
        
        const val KEY_MOBILE_DATA_ENABLED = "ax_mobile_data_enabled"
        const val KEY_MOBILE_DATA_INFO = "ax_mobile_data_info"
        
        const val KEY_BLUETOOTH_ENABLED = "ax_bluetooth_enabled"
        const val KEY_BLUETOOTH_INFO = "ax_bluetooth_info"
        const val KEY_BLUETOOTH_CONNECT_DEVICE = "ax_bluetooth_connect_device"
        
        const val KEY_HOTSPOT_ENABLED = "ax_hotspot_enabled"
        const val KEY_HOTSPOT_INFO = "ax_hotspot_info"
        
        const val KEY_FLASHLIGHT_ENABLED = "ax_flashlight_enabled"
        
        const val KEY_ROTATION_LOCKED = "ax_rotation_locked"
        
        const val KEY_LOCATION_ENABLED = "ax_location_enabled"
        
        const val KEY_BATTERY_SAVER_ENABLED = "ax_battery_saver_enabled"
        const val KEY_BATTERY_INFO = "ax_battery_info"
        
        const val KEY_ZEN_MODE = "ax_zen_mode"
        
        const val KEY_QS_TILES_JSON = "ax_qs_tiles_json"
        const val KEY_QS_TILE_CLICK = "ax_qs_tile_click"
        const val KEY_QS_LISTENERS_COUNT = "ax_qs_listeners_count"
        
        const val KEY_QS_TILES_AVAILABLE_JSON = "ax_qs_tiles_available_json"
        const val KEY_QS_TILES_AVAILABLE_QUERY = "ax_qs_tiles_available_query"
        const val KEY_QS_TILES_UPDATE = "ax_qs_tiles_update"
    }

    private var latestAccessPoints: List<WifiEntry> = emptyList()

    override fun start() {
        startObserving()
        registerCallbacks()
        accessPointController.addAccessPointCallback(accessPointCallback)
        setupQSTilesExport()
    }

    private fun startObserving() {
        mapOf(
            KEY_WIFI_ENABLED to ::updateWifi,
            KEY_WIFI_CONNECT_KEY to ::updateWifiConnect,
            KEY_MOBILE_DATA_ENABLED to ::updateMobileData,
            KEY_BLUETOOTH_ENABLED to ::updateBluetooth,
            KEY_BLUETOOTH_CONNECT_DEVICE to ::updateBluetoothConnect,
            KEY_HOTSPOT_ENABLED to ::updateHotspot,
            KEY_FLASHLIGHT_ENABLED to ::updateFlashlight,
            KEY_ROTATION_LOCKED to ::updateRotation,
            KEY_LOCATION_ENABLED to ::updateLocation,
            KEY_BATTERY_SAVER_ENABLED to ::updateBatterySaver,
            KEY_ZEN_MODE to ::updateZenMode,
            KEY_QS_TILES_AVAILABLE_QUERY to ::handleAvailableTilesQuery,
            KEY_QS_TILES_UPDATE to ::handleTilesUpdate
        ).forEach { (key, action) ->
            scope.launch(mainDispatcher) {
                secureSettings.observerFlow(key)
                    .collect { action() }
            }
        }
    }

    private fun updateWifi() {
        val enabled = secureSettings.getInt(KEY_WIFI_ENABLED, 0) == 1
        networkController.setWifiEnabled(enabled)
    }

    private fun updateWifiConnect() {
        val key = secureSettings.getString(KEY_WIFI_CONNECT_KEY)
        if (key.isNullOrEmpty()) return
        secureSettings.putString(KEY_WIFI_CONNECT_KEY, "") 

        val entry = latestAccessPoints.find { it.getKey() == key || it.getTitle() == key }
        if (entry != null && entry.getConnectedState() != 2) {
            if (entry.isSaved()) {
                accessPointController.connect(entry)
            } else {
                accessPointController.connect(entry)
            }
        }
    }
    
    private val accessPointCallback = object : AccessPointController.AccessPointCallback {
        override fun onAccessPointsChanged(accessPoints: List<WifiEntry>) {
            latestAccessPoints = accessPoints.toList()
            val jsonArray = JSONArray()
            accessPoints.forEach { ap ->
                if (ap.getLevel() != -1) {
                    val obj = JSONObject().apply {
                         put("title", ap.getTitle())
                         put("key", ap.getKey())
                         put("isConnected", ap.getConnectedState() == 2)
                         put("isSaved", ap.isSaved())
                         put("level", ap.getLevel())
                         put("security", ap.getSecurity())
                    }
                    jsonArray.put(obj)
                }
            }
            putString(KEY_WIFI_SCAN_LIST, jsonArray.toString())
        }

        override fun onSettingsActivityTriggered(intent: Intent?) {
        }

        override fun onWifiScan(isScan: Boolean) {
        }
    }

    private fun updateMobileData() {
        val enabled = secureSettings.getInt(KEY_MOBILE_DATA_ENABLED, 0) == 1
        networkController.mobileDataController?.isMobileDataEnabled = enabled
    }

    private val signalCallback = object : SignalCallback {
        override fun setWifiIndicators(wifiIndicators: WifiIndicators) {
            val info = JSONObject().apply {
                put("enabled", wifiIndicators.enabled)
                put("connected", wifiIndicators.statusIcon?.visible == true)
                put("ssid", wifiIndicators.description ?: "")
                put("statusLabel", wifiIndicators.statusLabel ?: "")
                put("isDefault", wifiIndicators.isDefault)
            }
            putString(KEY_WIFI_INFO, info.toString())

            val currentSetting = getInt(KEY_WIFI_ENABLED, if (wifiIndicators.enabled) 1 else 0)
            val newState = if (wifiIndicators.enabled) 1 else 0
            if (currentSetting != newState) {
                putInt(KEY_WIFI_ENABLED, newState)
            }
        }
        
        override fun setMobileDataIndicators(mobileDataIndicators: MobileDataIndicators) {
            val info = JSONObject().apply {
                put("type", mobileDataIndicators.typeContentDescription?.toString() ?: "")
                put("description", mobileDataIndicators.qsDescription?.toString() ?: "")
                put("roaming", mobileDataIndicators.roaming)
                put("isDefault", mobileDataIndicators.isDefault)
                put("subId", mobileDataIndicators.subId)
                put("showTriangle", mobileDataIndicators.showTriangle)
                put("activityIn", mobileDataIndicators.activityIn)
                put("activityOut", mobileDataIndicators.activityOut)
                put("level", mobileDataIndicators.level)
                put("statusDescription", mobileDataIndicators.statusIcon?.contentDescription?.toString() ?: "")
            }
            putString(KEY_MOBILE_DATA_INFO, info.toString())
            
            val isEnabled = networkController.mobileDataController?.isMobileDataEnabled == true
            val currentSetting = getInt(KEY_MOBILE_DATA_ENABLED, if (isEnabled) 1 else 0)
            val newState = if (isEnabled) 1 else 0

            if (currentSetting != newState) {
                putInt(KEY_MOBILE_DATA_ENABLED, newState)
            }
        }

        override fun setNoSims(show: Boolean, simDetected: Boolean) {
            if (!simDetected) {
                putString(KEY_MOBILE_DATA_INFO, "")
            }
        }
    }

    
    private fun getAllBluetoothDevices(): Collection<com.android.settingslib.bluetooth.CachedBluetoothDevice> {
        return localBluetoothManager?.cachedDeviceManager?.cachedDevicesCopy ?: bluetoothController.connectedDevices
    }

    private fun updateBluetooth() {
        val enabled = getInt(KEY_BLUETOOTH_ENABLED, 0) == 1
        bluetoothController.setBluetoothEnabled(enabled)
    }

    private fun updateBluetoothConnect() {
        val address = secureSettings.getString(KEY_BLUETOOTH_CONNECT_DEVICE)
        if (address.isNullOrEmpty()) return

        
        if (secureSettings.getString(KEY_BLUETOOTH_CONNECT_DEVICE) != "") {
             secureSettings.putString(KEY_BLUETOOTH_CONNECT_DEVICE, "")
        }

        val device = getAllBluetoothDevices().find { it.getAddress() == address }
        device?.let {
            if (it.isConnected()) {
                it.disconnect()
            } else {
                it.connect(true)
            }
        }
    }

    private val bluetoothCallback = object : BluetoothController.Callback {
        override fun onBluetoothStateChange(enabled: Boolean) {
            val newState = if (enabled) 1 else 0
            val current = getInt(KEY_BLUETOOTH_ENABLED, newState)
            if (current != newState) {
                putInt(KEY_BLUETOOTH_ENABLED, newState)
            }
        }

        override fun onBluetoothDevicesChanged() {
            val devices = getAllBluetoothDevices()
            val jsonArray = JSONArray()
            devices.forEach { device ->
                val devJson = JSONObject().apply {
                    put("name", device.getName())
                    put("address", device.getAddress())
                    put("isConnected", device.isConnected())
                    put("connectionState", device.getMaxConnectionState()) 
                    put("bondState", device.getBondState())
                    put("batteryLevel", device.getBatteryLevel())
                }
                jsonArray.put(devJson)
            }
            putString(KEY_BLUETOOTH_INFO, jsonArray.toString())
        }
    }

    
    private fun updateHotspot() {
        val enabled = getInt(KEY_HOTSPOT_ENABLED, 0) == 1
        hotspotController.setHotspotEnabled(enabled)
    }

    private val hotspotCallback = object : HotspotController.Callback {
        override fun onHotspotChanged(enabled: Boolean, numDevices: Int) {
            val newState = if (enabled) 1 else 0
            val current = getInt(KEY_HOTSPOT_ENABLED, newState)
            if (current != newState) {
                putInt(KEY_HOTSPOT_ENABLED, newState)
            }
            
            val info = JSONObject().apply {
                put("enabled", enabled)
                put("numDevices", numDevices)
            }
            putString(KEY_HOTSPOT_INFO, info.toString())
        }
    }

    private fun updateFlashlight() {
        val enabled = getInt(KEY_FLASHLIGHT_ENABLED, 0) == 1
        if (flashlightController.hasFlashlight()) {
             flashlightController.setFlashlight(enabled)
        }
    }

    private val flashlightListener = object : FlashlightController.FlashlightListener {
        override fun onFlashlightChanged(enabled: Boolean) {
            val newState = if (enabled) 1 else 0
            val current = getInt(KEY_FLASHLIGHT_ENABLED, newState)
            if (current != newState) {
                putInt(KEY_FLASHLIGHT_ENABLED, newState)
            }
        }

        override fun onFlashlightError() {}
        override fun onFlashlightAvailabilityChanged(available: Boolean) {}
    }

    
    private fun updateRotation() {
        val locked = getInt(KEY_ROTATION_LOCKED, 0) == 1
        rotationLockController.setRotationLocked(locked, TAG)
    }

    private val rotationCallback = object : RotationLockController.RotationLockControllerCallback {
        override fun onRotationLockStateChanged(rotationLocked: Boolean, affordanceVisible: Boolean) {
            val newState = if (rotationLocked) 1 else 0
            val current = getInt(KEY_ROTATION_LOCKED, newState)
            if (current != newState) {
                putInt(KEY_ROTATION_LOCKED, newState)
            }
        }
    }

    private fun updateLocation() {
        val enabled = getInt(KEY_LOCATION_ENABLED, 0) == 1
        locationController.setLocationEnabled(enabled)
    }

    private val locationCallback = object : LocationController.LocationChangeCallback {
        override fun onLocationSettingsChanged(locationEnabled: Boolean) {
             val newState = if (locationEnabled) 1 else 0
             val current = getInt(KEY_LOCATION_ENABLED, newState)
             if (current != newState) {
                 putInt(KEY_LOCATION_ENABLED, newState)
             }
        }
    }
    
    private fun updateBatterySaver() {
        val enabled = getInt(KEY_BATTERY_SAVER_ENABLED, 0) == 1
        batteryController.setPowerSaveMode(enabled)
    }
    
    private val batteryCallback = object : BatteryController.BatteryStateChangeCallback {
        override fun onPowerSaveChanged(isPowerSave: Boolean) {
            val newState = if (isPowerSave) 1 else 0
            val current = getInt(KEY_BATTERY_SAVER_ENABLED, newState)
            if (current != newState) {
                putInt(KEY_BATTERY_SAVER_ENABLED, newState)
            }
        }
        
        override fun onBatteryLevelChanged(level: Int, pluggedIn: Boolean, charging: Boolean) {
             val info = JSONObject().apply {
                 put("level", level)
                 put("pluggedIn", pluggedIn)
                 put("charging", charging)
                 put("powerSave", batteryController.isPowerSave)
                 put("wireless", batteryController.isWirelessCharging)
             }
             putString(KEY_BATTERY_INFO, info.toString())
        }
    }
    
    private fun updateZenMode() {
        val zen = getInt(KEY_ZEN_MODE, 0)
        if (zenModeController.zen != zen) {
            zenModeController.setZen(zen, null, TAG)
        }
    }
    
    private val zenCallback = object : ZenModeController.Callback {
        override fun onZenChanged(zen: Int) {
             val current = getInt(KEY_ZEN_MODE, zen)
             if (current != zen) {
                 putInt(KEY_ZEN_MODE, zen)
             }
        }
    }

    private fun registerCallbacks() {
        scope.launch(mainDispatcher) {
            networkController.addCallback(signalCallback)
            bluetoothController.addCallback(bluetoothCallback)
            hotspotController.addCallback(hotspotCallback)
            flashlightController.addCallback(flashlightListener)
            locationController.addCallback(locationCallback)
            rotationLockController.addCallback(rotationCallback)
            batteryController.addCallback(batteryCallback)
            zenModeController.addCallback(zenCallback)
        }
    }

    private fun getInt(key: String, def: Int): Int {
        return secureSettings.getInt(key, def)
    }

    private fun putInt(key: String, value: Int) {
        secureSettings.putInt(key, value)
    }
    
    private fun putString(key: String, value: String) {
        secureSettings.putString(key, value)
    }
    
    private val tileCallbacks = mutableMapOf<String, QSTile.Callback>()
    private var exportJob: Job? = null
    private var isListeningToTiles = false
    
    private fun setupQSTilesExport() {
        qsHost.addCallback(qsHostCallback)
        
        scope.launch(mainDispatcher) {
            secureSettings.observerFlow(KEY_QS_LISTENERS_COUNT)
                .collect { handleListenerCountChanged() }
        }
        
        scope.launch(mainDispatcher) {
            secureSettings.observerFlow(KEY_QS_TILE_CLICK)
                .collect { handleTileClick() }
        }
    }
    
    private fun handleListenerCountChanged() {
        val count = secureSettings.getInt(KEY_QS_LISTENERS_COUNT, 0)
        
        scope.launch(mainDispatcher) {
            if (count > 0 && !isListeningToTiles) {
                startListening()
            } else if (count <= 0 && isListeningToTiles) {
                stopListening()
            }
        }
    }
    
    private fun startListening() {
        isListeningToTiles = true
        registerTileCallbacks()
        scope.launch(mainDispatcher) {
            delay(100)
            exportTiles()
        }
    }
    
    private fun stopListening() {
        isListeningToTiles = false
        unregisterTileCallbacks()
    }
    
    private val qsHostCallback = object : QSHost.Callback {
        override fun onTilesChanged() {
            scope.launch(mainDispatcher) {
                unregisterTileCallbacks()
                registerTileCallbacks()
                exportTiles()
            }
        }
    }
    
    private fun registerTileCallbacks() {
        qsHost.tiles.forEach { tile ->
            tile.setListening(this@AxPlatformHooksCoreStartable, true)
            
            val callback = object : QSTile.Callback {
                override fun onStateChanged(state: QSTile.State) {
                    exportTilesDebounced()
                }
            }
            tile.addCallback(callback)
            tileCallbacks[tile.tileSpec] = callback
        }
    }
    
    private fun unregisterTileCallbacks() {
        qsHost.tiles.forEach { tile ->
            tile.setListening(this@AxPlatformHooksCoreStartable, false)
            tileCallbacks[tile.tileSpec]?.let { callback ->
                tile.removeCallback(callback)
            }
        }
        tileCallbacks.clear()
    }
    
    private fun exportTilesDebounced() {
        exportJob?.cancel()
        exportJob = scope.launch(mainDispatcher) {
            delay(100)
            exportTiles()
        }
    }
    
    private fun exportTiles() {
        val tiles = qsHost.tiles.toList()
        scope.launch(bgDispatcher) {
            val tilesJson = JSONArray()
            
            tiles.forEach { tile ->
                try {
                    val state = tile.state
                    val tileJson = JSONObject().apply {
                        put("spec", tile.tileSpec)
                        put("label", state.label?.toString() ?: "")
                        put("secondaryLabel", state.secondaryLabel?.toString() ?: "")
                        put("state", state.state)
                        put("isTransient", state.isTransient)
                    }
                    tilesJson.put(tileJson)
                } catch (e: Exception) {
                }
            }
            
            putString(KEY_QS_TILES_JSON, tilesJson.toString())
        }
    }
    
    private fun handleTileClick() {
        val spec = secureSettings.getString(KEY_QS_TILE_CLICK)
        if (spec.isNullOrEmpty()) return
        secureSettings.putString(KEY_QS_TILE_CLICK, "")
        scope.launch(mainDispatcher) {
            val tile = qsHost.tiles.find { it.tileSpec == spec }
            tile?.click(null)
        }
    }

    private fun handleAvailableTilesQuery() {
        val query = secureSettings.getInt(KEY_QS_TILES_AVAILABLE_QUERY, 0)
        if (query == 0) return
        
        secureSettings.putInt(KEY_QS_TILES_AVAILABLE_QUERY, 0)
        exportAvailableTiles()
    }

    private fun exportAvailableTiles() {
        scope.launch(mainDispatcher) {
            val availableTilesJson = JSONArray()
            val stockTilesStr = qsHost.context.resources.getString(R.string.quick_settings_tiles_stock)
            val stockTiles = stockTilesStr.split(",")
            
            stockTiles.forEach { spec ->
                if (spec.isNotEmpty() && !spec.startsWith("custom")) {
                    try {
                        val tile = qsHost.createTile(spec)
                        if (tile != null) {
                            val label = tile.getTileLabel()?.toString() ?: spec
                            val obj = JSONObject().apply {
                                put("spec", spec)
                                put("isSystem", true)
                                put("label", label)
                            }
                            availableTilesJson.put(obj)
                            tile.destroy()
                        }
                    } catch (e: Exception) {
                        val obj = JSONObject().apply {
                            put("spec", spec)
                            put("isSystem", true)
                            put("label", spec)
                        }
                        availableTilesJson.put(obj)
                    }
                }
            }
            
            scope.launch(bgDispatcher) {
                val pm = qsHost.context.packageManager
                val services = pm.queryIntentServices(Intent(TileService.ACTION_QS_TILE), 0)
                services.forEach { info: ResolveInfo ->
                    val packageName = info.serviceInfo.packageName
                    val componentName = ComponentName(packageName, info.serviceInfo.name)
                    val spec = "custom(" + componentName.flattenToString() + ")"
                    
                    val obj = JSONObject().apply {
                        put("spec", spec)
                        put("isSystem", false)
                        put("label", info.serviceInfo.loadLabel(pm).toString())
                        put("packageName", packageName)
                    }
                    availableTilesJson.put(obj)
                }
                
                putString(KEY_QS_TILES_AVAILABLE_JSON, availableTilesJson.toString())
            }
        }
    }

    private fun handleTilesUpdate() {
        val newTilesJson = secureSettings.getString(KEY_QS_TILES_UPDATE)
        if (newTilesJson.isNullOrEmpty()) return
        
        secureSettings.putString(KEY_QS_TILES_UPDATE, "")
        
        try {
            val jsonArray = JSONArray(newTilesJson)
            val newList = mutableListOf<String>()
            for (i in 0 until jsonArray.length()) {
                newList.add(jsonArray.getString(i))
            }
            
            scope.launch(mainDispatcher) {
                val currentSpecs = qsHost.specs
                qsHost.changeTilesByUser(currentSpecs, newList)
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to update tiles: $e")
        }
    }
}
