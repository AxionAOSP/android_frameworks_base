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

import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.util.Log
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.qs.tiles.AxFiveGUtils
import com.android.systemui.statusbar.policy.HotspotController
import java.util.concurrent.Executor
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@SysUISingleton
class InternetTileExtrasViewModel @Inject constructor(
    private val hotspotController: HotspotController,
    private val subscriptionManager: SubscriptionManager,
    private val fiveGUtils: AxFiveGUtils,
    @Background private val bgExecutor: Executor,
    private val dataUsageRepository: DataUsageRepository,
) {

    private val _hotspotEnabled = MutableStateFlow(false)
    val hotspotEnabled: StateFlow<Boolean> = _hotspotEnabled.asStateFlow()

    private val _hotspotAvailable = MutableStateFlow(false)
    val hotspotAvailable: StateFlow<Boolean> = _hotspotAvailable.asStateFlow()

    private val _fiveGEnabled = MutableStateFlow(false)
    val fiveGEnabled: StateFlow<Boolean> = _fiveGEnabled.asStateFlow()

    private val _fiveGAvailable = MutableStateFlow(false)
    val fiveGAvailable: StateFlow<Boolean> = _fiveGAvailable.asStateFlow()

    val mobileDataUsage: StateFlow<String?> = dataUsageRepository.mobileUsageFormatted
    val mobileCarrier: StateFlow<String?> = dataUsageRepository.mobileCarrier
    val wifiDataUsage: StateFlow<String?> = dataUsageRepository.wifiUsageFormatted
    val wifiSsid: StateFlow<String?> = dataUsageRepository.wifiSsid

    private val callbacks = mutableMapOf<Int, FiveGCallback>()

    private val hotspotCallback = object : HotspotController.Callback {
        override fun onHotspotChanged(enabled: Boolean, numDevices: Int) {
            _hotspotEnabled.value = enabled
        }

        override fun onHotspotAvailabilityChanged(available: Boolean) {
            _hotspotAvailable.value = available
        }
    }

    private val subscriptionsChangedListener = object : SubscriptionManager.OnSubscriptionsChangedListener() {
        override fun onSubscriptionsChanged() {
            refreshCallbacks()
        }
    }

    private inner class FiveGCallback : TelephonyCallback(), TelephonyCallback.AllowedNetworkTypesListener {
        override fun onAllowedNetworkTypesChanged(reason: Int, allowedNetworkType: Long) {
            bgExecutor.execute { refreshFiveGState() }
        }
    }

    fun start() {
        _hotspotAvailable.value = hotspotController.isHotspotSupported
        _hotspotEnabled.value = hotspotController.isHotspotEnabled
        hotspotController.addCallback(hotspotCallback)
        subscriptionManager.addOnSubscriptionsChangedListener(bgExecutor, subscriptionsChangedListener)
        refreshCallbacks()
        dataUsageRepository.refresh()
    }

    fun stop() {
        hotspotController.removeCallback(hotspotCallback)
        subscriptionManager.removeOnSubscriptionsChangedListener(subscriptionsChangedListener)
        clearCallbacks()
    }

    fun toggleHotspot() {
        val newState = !_hotspotEnabled.value
        hotspotController.setHotspotEnabled(newState)
    }

    fun toggleFiveG() {
        bgExecutor.execute {
            try {
                fiveGUtils.setNrEnabled(!_fiveGEnabled.value)
            } catch (e: Exception) {
                Log.e(TAG, "Error toggling 5G", e)
            }
            refreshFiveGState()
        }
    }

    private fun refreshCallbacks() {
        bgExecutor.execute {
            val subInfoList = subscriptionManager.activeSubscriptionInfoList ?: emptyList()
            val activeSubIds = subInfoList.map { it.subscriptionId }.toSet()

            val iterator = callbacks.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.key !in activeSubIds) {
                    fiveGUtils.createForSubscriptionId(entry.key)
                        .unregisterTelephonyCallback(entry.value)
                    iterator.remove()
                }
            }

            for (subId in activeSubIds) {
                if (subId !in callbacks) {
                    val callback = FiveGCallback()
                    callbacks[subId] = callback
                    fiveGUtils.createForSubscriptionId(subId)
                        .registerTelephonyCallback(bgExecutor, callback)
                }
            }
            refreshFiveGState()
        }
    }

    private fun clearCallbacks() {
        bgExecutor.execute {
            for ((subId, callback) in callbacks) {
                fiveGUtils.createForSubscriptionId(subId)
                    .unregisterTelephonyCallback(callback)
            }
            callbacks.clear()
        }
    }

    private fun refreshFiveGState() {
        _fiveGAvailable.value = fiveGUtils.isNrAvailable()
        _fiveGEnabled.value = if (_fiveGAvailable.value) fiveGUtils.isNrEnabled() else false
    }

    companion object {
        private const val TAG = "InternetTileExtrasVM"
    }
}
