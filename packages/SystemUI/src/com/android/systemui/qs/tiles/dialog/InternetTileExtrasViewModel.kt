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

import android.content.Context
import android.telephony.RadioAccessFamily
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.statusbar.policy.HotspotController
import java.util.concurrent.Executor
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@SysUISingleton
class InternetTileExtrasViewModel @Inject constructor(
    private val context: Context,
    private val hotspotController: HotspotController,
    private val subscriptionManager: SubscriptionManager,
    @Background private val bgExecutor: Executor,
    private val dataUsageRepository: DataUsageRepository,
) {

    private val telephonyManager: TelephonyManager =
        context.getSystemService(TelephonyManager::class.java)

    private val _hotspotEnabled = MutableStateFlow(false)
    val hotspotEnabled: StateFlow<Boolean> = _hotspotEnabled.asStateFlow()

    private val _hotspotAvailable = MutableStateFlow(false)
    val hotspotAvailable: StateFlow<Boolean> = _hotspotAvailable.asStateFlow()

    private val _fiveGEnabled = MutableStateFlow(false)
    val fiveGEnabled: StateFlow<Boolean> = _fiveGEnabled.asStateFlow()

    private val _fiveGAvailable = MutableStateFlow(false)
    val fiveGAvailable: StateFlow<Boolean> = _fiveGAvailable.asStateFlow()

    val mobileDataUsage: StateFlow<String?> = dataUsageRepository.mobileUsageFormatted
    val wifiDataUsage: StateFlow<String?> = dataUsageRepository.wifiUsageFormatted

    private val hotspotCallback = object : HotspotController.Callback {
        override fun onHotspotChanged(enabled: Boolean, numDevices: Int) {
            _hotspotEnabled.value = enabled
        }

        override fun onHotspotAvailabilityChanged(available: Boolean) {
            _hotspotAvailable.value = available
        }
    }

    fun start() {
        _hotspotAvailable.value = hotspotController.isHotspotSupported
        _hotspotEnabled.value = hotspotController.isHotspotEnabled
        hotspotController.addCallback(hotspotCallback)
        bgExecutor.execute { refreshFiveGState() }
        dataUsageRepository.refresh()
    }

    fun stop() {
        hotspotController.removeCallback(hotspotCallback)
    }

    fun toggleHotspot() {
        val newState = !_hotspotEnabled.value
        hotspotController.setHotspotEnabled(newState)
    }

    fun toggleFiveG() {
        bgExecutor.execute {
            val subInfoList = subscriptionManager.activeSubscriptionInfoList ?: return@execute
            val enable = !_fiveGEnabled.value
            for (subInfo in subInfoList) {
                val subId = subInfo.subscriptionId
                val tm = telephonyManager.createForSubscriptionId(subId)
                val currentRaf = tm.getAllowedNetworkTypesForReason(
                    TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_CARRIER
                )
                val newRaf = if (enable) {
                    currentRaf or TelephonyManager.NETWORK_TYPE_BITMASK_NR
                } else {
                    currentRaf and TelephonyManager.NETWORK_TYPE_BITMASK_NR.inv()
                }
                tm.setAllowedNetworkTypesForReason(
                    TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_CARRIER,
                    newRaf
                )
            }
            refreshFiveGState()
        }
    }

    private fun refreshFiveGState() {
        _fiveGAvailable.value = modemSupportsNr()
        if (!_fiveGAvailable.value) {
            _fiveGEnabled.value = false
            return
        }
        val subInfoList = subscriptionManager.activeSubscriptionInfoList
        if (subInfoList.isNullOrEmpty()) {
            _fiveGEnabled.value = false
            return
        }
        _fiveGEnabled.value = subInfoList.any { subInfo ->
            val allowed = telephonyManager.createForSubscriptionId(subInfo.subscriptionId)
                .getAllowedNetworkTypesForReason(
                    TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_CARRIER
                )
            (allowed and TelephonyManager.NETWORK_TYPE_BITMASK_NR) > 0
        }
    }

    private fun modemSupportsNr(): Boolean {
        val defaultNetwork = TelephonyManager.getTelephonyProperty(
            0, "ro.telephony.default_network", "1"
        ).toIntOrNull() ?: return false
        val raf = RadioAccessFamily.getRafFromNetworkType(defaultNetwork).toLong()
        return (raf and TelephonyManager.NETWORK_TYPE_BITMASK_NR) > 0
    }
}
