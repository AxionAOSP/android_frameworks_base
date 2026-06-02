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

import android.content.Context
import android.content.res.Resources
import android.media.AudioManager
import android.os.Bundle
import com.android.axion.platform.AxFeatureState
import com.android.axion.platform.AxPlatformFeature
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.qs.tiles.base.shared.model.QSTileConfigProvider
import com.android.systemui.res.R
import com.android.systemui.statusbar.policy.BatteryController
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.util.icuMessageFormat
import javax.inject.Inject

@SysUISingleton
class AxPlatformFeatureMapper @Inject constructor(
    private val context: Context,
    private val batteryController: BatteryController,
    private val qsTileConfigProvider: QSTileConfigProvider,
    configurationController: ConfigurationController,
) : AxPlatformStateManager.LabelProvider {

    private val labelCache = mutableMapOf<String, String>()

    init {
        configurationController.addCallback(object : ConfigurationController.ConfigurationListener {
            override fun onLocaleListChanged() {
                labelCache.clear()
            }
        })
    }

    override fun getLabel(feature: String): String? {
        labelCache[feature]?.let { return it }
        val tileSpec = AxPlatformFeature.getPrimaryTileSpec(feature) ?: return null
        if (!qsTileConfigProvider.hasConfig(tileSpec)) return null
        val labelRes = qsTileConfigProvider.getConfig(tileSpec).uiConfig.labelRes
        if (labelRes == Resources.ID_NULL) return null
        return context.getString(labelRes).also { labelCache[feature] = it }
    }

    override fun getSecondaryLabel(feature: String, state: Bundle): String? = when (feature) {
        AxPlatformFeature.WIFI -> {
            val ssid = state.getString("ssid")
            if (state.getBoolean("connected") && !ssid.isNullOrEmpty()) ssid else null
        }
        AxPlatformFeature.BLUETOOTH -> {
            @Suppress("DEPRECATION")
            val devices = state.getParcelableArrayList<Bundle>("devices")
            devices?.firstOrNull { it.getBoolean("isConnected") }?.getString("name")
        }
        AxPlatformFeature.HOTSPOT -> {
            val numDevices = state.getInt("numDevices", 0)
            if (numDevices > 0) {
                icuMessageFormat(
                    context.resources,
                    R.string.quick_settings_hotspot_secondary_label_num_devices,
                    numDevices,
                )
            } else {
                null
            }
        }
        AxPlatformFeature.MOBILE_DATA -> state.getString("description")?.takeIf { it.isNotEmpty() }
        AxPlatformFeature.ZEN -> {
            val mode = state.getInt("mode", 0)
            if (mode != 0) context.getString(R.string.zen_mode_on) else null
        }
        AxPlatformFeature.RINGER_MODE -> {
            when (AxFeatureState.fromBundle(state).getRingerMode(AudioManager.RINGER_MODE_NORMAL)) {
                AudioManager.RINGER_MODE_VIBRATE ->
                    context.getString(R.string.volume_ringer_status_vibrate)
                AudioManager.RINGER_MODE_SILENT ->
                    context.getString(R.string.volume_ringer_status_silent)
                else -> context.getString(R.string.volume_ringer_status_normal)
            }
        }
        AxPlatformFeature.POWER_SHARE -> {
            if (batteryController.isAodPowerSave) {
                context.getString(R.string.quick_settings_powershare_off_powersave_label)
            } else {
                null
            }
        }
        AxPlatformFeature.VPN -> state.getString("name")?.takeIf { it.isNotEmpty() }
        AxPlatformFeature.CAST -> state.getString("deviceName")?.takeIf { it.isNotEmpty() }
        AxPlatformFeature.PROFILES -> state.getString("profileName")?.takeIf { it.isNotEmpty() }
        AxPlatformFeature.SCREEN_RECORD -> {
            val featureState = AxFeatureState.fromBundle(state)
            when {
                featureState.isActive() -> context.getString(R.string.quick_settings_screen_record_stop)
                featureState.isStarting() -> context.getString(R.string.quick_settings_screen_record_start)
                else -> null
            }
        }
        else -> null
    }
}
