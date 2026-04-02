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

package com.android.systemui.qs.tiles.dialog

import android.net.NetworkStats
import android.content.Context
import android.net.NetworkTemplate
import android.net.wifi.WifiManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import com.android.settingslib.net.DataUsageController
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.res.R
import android.util.Log
import java.text.DecimalFormat
import java.util.concurrent.Executor
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@SysUISingleton
class DataUsageRepository @Inject constructor(
    private val context: Context,
    @Background private val bgExecutor: Executor,
) {

    private val mobileDataUsageController = DataUsageController(context)
    private val wifiDataUsageController = DataUsageController(context)
    private val telephonyManager =
        context.getSystemService(TelephonyManager::class.java)
    private val wifiManager =
        context.getSystemService(WifiManager::class.java)

    private val wifiTemplate = NetworkTemplate.Builder(NetworkTemplate.MATCH_WIFI).build()

    private val _mobileUsageFormatted = MutableStateFlow<String?>(null)
    val mobileUsageFormatted: StateFlow<String?> = _mobileUsageFormatted.asStateFlow()

    private val _mobileCarrier = MutableStateFlow<String?>(null)
    val mobileCarrier: StateFlow<String?> = _mobileCarrier.asStateFlow()

    private val _wifiUsageFormatted = MutableStateFlow<String?>(null)
    val wifiUsageFormatted: StateFlow<String?> = _wifiUsageFormatted.asStateFlow()

    private val _wifiSsid = MutableStateFlow<String?>(null)
    val wifiSsid: StateFlow<String?> = _wifiSsid.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        Log.d(TAG, "refresh() called")
        bgExecutor.execute {
            queryMobileUsage()
            queryWifiUsage()
        }
    }

    private fun queryMobileUsage() {
        try {
            val subId = SubscriptionManager.getDefaultDataSubscriptionId()
            if (!SubscriptionManager.isValidSubscriptionId(subId)) {
                _mobileUsageFormatted.value = null
                _mobileCarrier.value = null
                return
            }
            val template = getMobileTemplateForSubId(subId)
            mobileDataUsageController.setSubscriptionId(subId)
            val info = mobileDataUsageController.getDataUsageInfo(template)
            if (info != null && info.usageLevel >= 0) {
                val formatted = context.getString(
                    R.string.quick_settings_cellular_detail_data_used,
                    formatBytes(info.usageLevel),
                )
                Log.d(TAG, "mobile usage: ${info.usageLevel} bytes -> $formatted")
                _mobileUsageFormatted.value = formatted
                _mobileCarrier.value = info.carrier
            } else {
                Log.d(TAG, "mobile usage: info=${info != null}, level=${info?.usageLevel}")
                _mobileUsageFormatted.value = null
                _mobileCarrier.value = null
            }
        } catch (e: Exception) {
            Log.e(TAG, "queryMobileUsage failed", e)
            _mobileUsageFormatted.value = null
            _mobileCarrier.value = null
        }
    }

    private fun queryWifiUsage() {
        try {
            val info = wifiDataUsageController.getDataUsageInfo(wifiTemplate)
            if (info != null && info.usageLevel >= 0) {
                val formatted = context.getString(
                    R.string.quick_settings_cellular_detail_data_used,
                    formatBytes(info.usageLevel),
                )
                Log.d(TAG, "wifi usage: ${info.usageLevel} bytes -> $formatted")
                _wifiUsageFormatted.value = formatted
                
                val ssid = wifiManager.connectionInfo.ssid
                _wifiSsid.value = if (ssid != WifiManager.UNKNOWN_SSID) {
                    removeDoubleQuotes(ssid)
                } else {
                    null
                }
            } else {
                Log.d(TAG, "wifi usage: info=${info != null}, level=${info?.usageLevel}")
                _wifiUsageFormatted.value = null
                _wifiSsid.value = null
            }
        } catch (e: Exception) {
            Log.e(TAG, "queryWifiUsage failed", e)
            _wifiUsageFormatted.value = null
            _wifiSsid.value = null
        }
    }

    private fun getMobileTemplateForSubId(subId: Int): NetworkTemplate {
        val subscriberId = telephonyManager.createForSubscriptionId(subId).subscriberId
        val builder = if (subscriberId != null) {
            NetworkTemplate.Builder(NetworkTemplate.MATCH_CARRIER)
                .setSubscriberIds(setOf(subscriberId))
        } else {
            NetworkTemplate.Builder(NetworkTemplate.MATCH_MOBILE)
        }
        return builder.setMeteredness(NetworkStats.METERED_YES).build()
    }

    companion object {
        private const val TAG = "DataUsageRepository"
        private const val KB = 1024.0
        private const val MB = 1024.0 * KB
        private const val GB = 1024.0 * MB
        private val FORMAT = DecimalFormat("#.##")

        fun formatBytes(bytes: Long): String {
            val b = bytes.toDouble()
            val value: Double
            val suffix: String
            when {
                b > 100 * MB -> { value = b / GB; suffix = "GB" }
                b > 100 * KB -> { value = b / MB; suffix = "MB" }
                else -> { value = b / KB; suffix = "KB" }
            }
            return FORMAT.format(value) + " " + suffix
        }

        fun appendUsage(label: CharSequence?, usage: String?): CharSequence? {
            if (usage == null) return label
            if (label == null) return usage
            return "$label · $usage"
        }

        fun removeDoubleQuotes(string: String?): String? {
            if (string == null) return null
            return if (string.firstOrNull() == '"' && string.lastOrNull() == '"') {
                string.substring(1, string.length - 1)
            } else string
        }
    }
}
