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

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.quicksettings.Tile
import android.telephony.RadioAccessFamily
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import android.widget.Switch
import com.android.internal.logging.MetricsLogger
import com.android.systemui.animation.Expandable
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.plugins.qs.QSTile
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.qs.QSHost
import com.android.systemui.qs.QsEventLogger
import com.android.systemui.qs.logging.QSLogger
import com.android.systemui.qs.tileimpl.QSTileImpl
import com.android.systemui.res.R
import java.util.concurrent.Executor
import javax.inject.Inject

class FiveGTile @Inject constructor(
    host: QSHost,
    uiEventLogger: QsEventLogger,
    @Background backgroundLooper: Looper,
    @Main mainHandler: Handler,
    falsingManager: FalsingManager,
    metricsLogger: MetricsLogger,
    private val statusBarStateController: StatusBarStateController,
    activityStarter: ActivityStarter,
    private val qsLogger: QSLogger,
    private val subscriptionManager: SubscriptionManager,
    @Background private val bgExecutor: Executor
) : QSTileImpl<QSTile.BooleanState>(
    host,
    uiEventLogger,
    backgroundLooper,
    mainHandler,
    falsingManager,
    metricsLogger,
    statusBarStateController,
    activityStarter,
    qsLogger,
) {

    private val telephonyManager: TelephonyManager =
        mContext.getSystemService(TelephonyManager::class.java)

    private val callbacks = mutableMapOf<Int, FiveGCallback>()

    private val subscriptionsChangedListener = object : SubscriptionManager.OnSubscriptionsChangedListener() {
        override fun onSubscriptionsChanged() {
            refreshCallbacks()
        }
    }

    private inner class FiveGCallback : TelephonyCallback(), TelephonyCallback.AllowedNetworkTypesListener {
        override fun onAllowedNetworkTypesChanged(reason: Int, allowedNetworkType: Long) {
            if (reason == TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_USER) {
                refreshState()
            }
        }
    }

    override fun newTileState(): QSTile.BooleanState {
        return QSTile.BooleanState()
    }

    override fun handleClick(expandable: Expandable?) {
        val enable = !mState.value
        qsLogger.logTileClick(tileSpec, statusBarStateController.state, mState.state, mState.state)
        bgExecutor.execute {
            try {
                val subInfoList = subscriptionManager.activeSubscriptionInfoList ?: return@execute
                for (subInfo in subInfoList) {
                    val subId = subInfo.subscriptionId
                    val tm = telephonyManager.createForSubscriptionId(subId)
                    val currentRaf = tm.getAllowedNetworkTypesForReason(
                        TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_USER
                    )
                    val newRaf = if (enable) {
                        currentRaf or TelephonyManager.NETWORK_TYPE_BITMASK_NR
                    } else {
                        currentRaf and TelephonyManager.NETWORK_TYPE_BITMASK_NR.inv()
                    }
                    tm.setAllowedNetworkTypesForReason(
                        TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_USER,
                        newRaf
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG_5G, "Error handling click", e)
            }
            refreshState()
        }
    }

    override fun handleUpdateState(state: QSTile.BooleanState, arg: Any?) {
        state.label = mContext.getString(R.string.quick_settings_5g_label)
        state.contentDescription = state.label
        state.icon = ResourceIcon.get(R.drawable.ic_5g_toggle)
        state.value = isFiveGEnabled()
        state.state = if (state.value) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        state.expandedAccessibilityClassName = Switch::class.java.name
    }

    override fun isAvailable(): Boolean {
        return modemSupportsNr()
    }

    override fun getLongClickIntent(): Intent {
        return Intent(Settings.ACTION_NETWORK_OPERATOR_SETTINGS)
    }

    override fun getTileLabel(): CharSequence {
        return mContext.getString(R.string.quick_settings_5g_editor_label)
    }

    override fun getMetricsCategory(): Int {
        return 0
    }

    override fun handleSetListening(listening: Boolean) {
        super.handleSetListening(listening)
        if (listening) {
            subscriptionManager.addOnSubscriptionsChangedListener(bgExecutor, subscriptionsChangedListener)
            refreshCallbacks()
        } else {
            subscriptionManager.removeOnSubscriptionsChangedListener(subscriptionsChangedListener)
            clearCallbacks()
        }
    }

    private fun refreshCallbacks() {
        bgExecutor.execute {
            val subInfoList = subscriptionManager.activeSubscriptionInfoList ?: emptyList()
            val activeSubIds = subInfoList.map { it.subscriptionId }.toSet()

            // Remove callbacks for inactive subscriptions
            val iterator = callbacks.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.key !in activeSubIds) {
                    telephonyManager.createForSubscriptionId(entry.key).unregisterTelephonyCallback(entry.value)
                    iterator.remove()
                }
            }

            // Add callbacks for new active subscriptions
            for (subId in activeSubIds) {
                if (subId !in callbacks) {
                    val callback = FiveGCallback()
                    callbacks[subId] = callback
                    telephonyManager.createForSubscriptionId(subId).registerTelephonyCallback(bgExecutor, callback)
                }
            }
            refreshState()
        }
    }

    private fun clearCallbacks() {
        bgExecutor.execute {
            for ((subId, callback) in callbacks) {
                telephonyManager.createForSubscriptionId(subId).unregisterTelephonyCallback(callback)
            }
            callbacks.clear()
        }
    }

    private fun isFiveGEnabled(): Boolean {
        val subInfoList = subscriptionManager.activeSubscriptionInfoList
        if (subInfoList.isNullOrEmpty()) {
            return false
        }
        return subInfoList.any { subInfo ->
            val allowed = telephonyManager.createForSubscriptionId(subInfo.subscriptionId)
                .getAllowedNetworkTypesForReason(
                    TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_USER
                )
            (allowed and TelephonyManager.NETWORK_TYPE_BITMASK_NR) > 0
        }
    }

    private fun modemSupportsNr(): Boolean {
        for (slot in 0 until telephonyManager.activeModemCount) {
            val defaultNetwork = TelephonyManager.getTelephonyProperty(
                slot, "ro.telephony.default_network", "1"
            ).toIntOrNull() ?: continue
            val raf = RadioAccessFamily.getRafFromNetworkType(defaultNetwork).toLong()
            if ((raf and TelephonyManager.NETWORK_TYPE_BITMASK_NR) > 0) {
                return true
            }
        }
        return false
    }

    companion object {
        private const val TAG_5G = "FiveGTile"
        const val TILE_SPEC = "five_g"
    }
}
