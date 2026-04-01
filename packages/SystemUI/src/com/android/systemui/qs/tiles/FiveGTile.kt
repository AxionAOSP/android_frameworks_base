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
import com.android.systemui.statusbar.connectivity.IconState
import com.android.systemui.statusbar.connectivity.NetworkController
import com.android.systemui.statusbar.connectivity.SignalCallback
import com.android.systemui.statusbar.policy.BatteryController
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
    private val fiveGUtils: AxFiveGUtils,
    @Background private val bgExecutor: Executor,
    batteryController: BatteryController,
    networkController: NetworkController,
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

    private var airplaneMode = false
    private var activeCallback: FiveGCallback? = null
    private var trackedSubId = SubscriptionManager.INVALID_SUBSCRIPTION_ID

    private val signalCallback = object : SignalCallback {
        override fun setIsAirplaneMode(icon: IconState) {
            val was = airplaneMode
            airplaneMode = icon.visible
            if (was != airplaneMode) refreshState()
        }

        override fun setNoSims(show: Boolean, simDetected: Boolean) {
            refreshState()
        }
    }

    private val subscriptionsChangedListener =
        object : SubscriptionManager.OnSubscriptionsChangedListener() {
            override fun onSubscriptionsChanged() {
                updateTelephonyCallback()
                refreshState()
            }
        }

    private inner class FiveGCallback :
        TelephonyCallback(), TelephonyCallback.AllowedNetworkTypesListener {
        override fun onAllowedNetworkTypesChanged(reason: Int, allowedNetworkType: Long) {
            refreshState()
        }
    }

    init {
        networkController.observe(lifecycle, signalCallback)
        batteryController.observe(
            lifecycle,
            object : BatteryController.BatteryStateChangeCallback {
                override fun onPowerSaveChanged(isPowerSave: Boolean) {
                    mHandler.postDelayed({ refreshState() }, POWER_SAVE_DELAY)
                }
            },
        )
    }

    override fun newTileState() = QSTile.BooleanState()

    override fun handleClick(expandable: Expandable?) {
        if (mState.state == Tile.STATE_UNAVAILABLE) return
        val enable = !mState.value
        qsLogger.logTileClick(tileSpec, statusBarStateController.state, mState.state, mState.state)
        bgExecutor.execute {
            try {
                fiveGUtils.setNrEnabled(enable)
            } catch (e: Exception) {
                Log.e(TAG_5G, "Error handling click", e)
            }
            refreshState()
        }
    }

    override fun handleUpdateState(state: QSTile.BooleanState, arg: Any?) {
        state.label = mContext.getString(R.string.quick_settings_5g_label)
        state.icon = ResourceIcon.get(R.drawable.ic_5g_toggle)
        if (airplaneMode) {
            state.value = false
            state.state = Tile.STATE_UNAVAILABLE
        } else if (!fiveGUtils.isNrAvailable()) {
            state.value = false
            state.state = Tile.STATE_UNAVAILABLE
        } else {
            state.value = fiveGUtils.isNrEnabled()
            state.state = if (state.value) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        }
        state.contentDescription = state.label
        state.expandedAccessibilityClassName = Switch::class.java.name
    }

    override fun isAvailable() = fiveGUtils.modemSupportsNr()

    override fun getLongClickIntent(): Intent {
        if (mState.state == Tile.STATE_UNAVAILABLE) {
            return Intent(Settings.ACTION_WIRELESS_SETTINGS)
        }
        val intent = Intent(Settings.ACTION_NETWORK_OPERATOR_SETTINGS)
        val subId = SubscriptionManager.getDefaultDataSubscriptionId()
        if (SubscriptionManager.isValidSubscriptionId(subId)) {
            intent.putExtra(Settings.EXTRA_SUB_ID, subId)
        }
        return intent
    }

    override fun getTileLabel(): CharSequence =
        mContext.getString(R.string.quick_settings_5g_editor_label)

    override fun getMetricsCategory() = 0

    override fun handleSetListening(listening: Boolean) {
        super.handleSetListening(listening)
        if (listening) {
            mContext.getSystemService(SubscriptionManager::class.java)
                ?.addOnSubscriptionsChangedListener(bgExecutor, subscriptionsChangedListener)
            updateTelephonyCallback()
        } else {
            mContext.getSystemService(SubscriptionManager::class.java)
                ?.removeOnSubscriptionsChangedListener(subscriptionsChangedListener)
            clearTelephonyCallback()
        }
    }

    private fun updateTelephonyCallback() {
        bgExecutor.execute {
            val subId = fiveGUtils.getDefaultDataSubId()
                ?: SubscriptionManager.INVALID_SUBSCRIPTION_ID
            if (subId == trackedSubId) return@execute
            clearTelephonyCallbackInternal()
            trackedSubId = subId
            if (SubscriptionManager.isValidSubscriptionId(subId)) {
                val callback = FiveGCallback()
                activeCallback = callback
                fiveGUtils.createForSubscriptionId(subId)
                    .registerTelephonyCallback(bgExecutor, callback)
            }
        }
    }

    private fun clearTelephonyCallback() {
        bgExecutor.execute { clearTelephonyCallbackInternal() }
    }

    private fun clearTelephonyCallbackInternal() {
        activeCallback?.let { cb ->
            if (SubscriptionManager.isValidSubscriptionId(trackedSubId)) {
                fiveGUtils.createForSubscriptionId(trackedSubId)
                    .unregisterTelephonyCallback(cb)
            }
        }
        activeCallback = null
        trackedSubId = SubscriptionManager.INVALID_SUBSCRIPTION_ID
    }

    companion object {
        private const val TAG_5G = "FiveGTile"
        private const val POWER_SAVE_DELAY = 350L
        const val TILE_SPEC = "five_g"
    }
}
