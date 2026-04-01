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

package com.android.systemui.qs.tiles

import android.telephony.RadioAccessFamily
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import com.android.systemui.dagger.SysUISingleton
import javax.inject.Inject

@SysUISingleton
class AxFiveGUtils @Inject constructor(
    private val telephonyManager: TelephonyManager,
) {

    fun getDefaultDataSubId(): Int? {
        val subId = SubscriptionManager.getDefaultDataSubscriptionId()
        return if (SubscriptionManager.isValidSubscriptionId(subId)) subId else null
    }

    fun modemSupportsNr(): Boolean {
        for (slot in 0 until telephonyManager.activeModemCount) {
            val defaultNetwork = TelephonyManager.getTelephonyProperty(
                slot, PROP_DEFAULT_NETWORK, DEFAULT_NETWORK_FALLBACK
            ).toIntOrNull() ?: continue
            val raf = RadioAccessFamily.getRafFromNetworkType(defaultNetwork).toLong()
            if ((raf and NR_BITMASK) > 0) return true
        }
        return false
    }

    fun subscriptionSupportsNr(subId: Int): Boolean {
        val raf = telephonyManager.createForSubscriptionId(subId).supportedRadioAccessFamily
        return (raf and NR_BITMASK) != 0L
    }

    fun isNrAvailable(): Boolean {
        if (!modemSupportsNr()) return false
        val subId = getDefaultDataSubId() ?: return false
        return subscriptionSupportsNr(subId)
    }

    fun isNrEnabled(): Boolean {
        val subId = getDefaultDataSubId() ?: return false
        if (!subscriptionSupportsNr(subId)) return false
        val carrierRaf = telephonyManager.createForSubscriptionId(subId)
            .getAllowedNetworkTypesForReason(REASON_CARRIER)
        return (carrierRaf and NR_BITMASK) != 0L
    }

    fun setNrEnabled(enable: Boolean) {
        val subId = getDefaultDataSubId() ?: return
        if (!subscriptionSupportsNr(subId)) return
        val tm = telephonyManager.createForSubscriptionId(subId)
        val carrierRaf = tm.getAllowedNetworkTypesForReason(REASON_CARRIER)
        val newCarrierRaf = if (enable) {
            carrierRaf or NR_BITMASK
        } else {
            carrierRaf and NR_BITMASK.inv()
        }
        tm.setAllowedNetworkTypesForReason(REASON_CARRIER, newCarrierRaf)
        if (enable) {
            val userRaf = tm.getAllowedNetworkTypesForReason(REASON_USER)
            if ((userRaf and NR_BITMASK) == 0L) {
                tm.setAllowedNetworkTypesForReason(REASON_USER, userRaf or NR_BITMASK)
            }
        }
    }

    fun createForSubscriptionId(subId: Int): TelephonyManager =
        telephonyManager.createForSubscriptionId(subId)

    companion object {
        private const val PROP_DEFAULT_NETWORK = "ro.telephony.default_network"
        private const val DEFAULT_NETWORK_FALLBACK = "1"
        private const val NR_BITMASK = TelephonyManager.NETWORK_TYPE_BITMASK_NR
        private const val REASON_CARRIER = TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_CARRIER
        private const val REASON_USER = TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_USER
    }
}
