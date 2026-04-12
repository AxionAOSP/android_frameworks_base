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
package com.android.systemui.statusbar.phone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.nfc.NfcAdapter
import android.os.Handler
import com.android.systemui.CoreStartable
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.res.R
import com.android.systemui.statusbar.phone.ui.StatusBarIconController
import com.android.systemui.util.settings.SecureSettings
import com.android.systemui.util.settings.SettingsProxyExt.observerFlow
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private const val SLOT_BLUETOOTH_BATTERY = "bluetooth_show_battery"

@SysUISingleton
class PhoneStatusBarPolicyExt @Inject constructor(
    private val context: Context,
    private val iconController: StatusBarIconController,
    private val broadcastDispatcher: BroadcastDispatcher,
    private val secureSettings: SecureSettings,
    @Main private val mainHandler: Handler,
    @Application private val applicationScope: CoroutineScope,
    @Main private val mainDispatcher: CoroutineDispatcher,
) : CoreStartable {

    private val slotNfc: String =
        context.resources.getString(com.android.internal.R.string.status_bar_nfc)

    private var nfcAdapter: NfcAdapter? = null
    private var hideListListener: Runnable? = null

    private val nfcReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == NfcAdapter.ACTION_ADAPTER_STATE_CHANGED) {
                updateNfc()
            }
        }
    }

    override fun start() {
        broadcastDispatcher.registerReceiverWithHandler(
            nfcReceiver,
            IntentFilter(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED),
            mainHandler,
        )
    }

    fun setOnHideListChangedListener(listener: Runnable) {
        hideListListener = listener
        iconController.setIcon(
            slotNfc,
            R.drawable.stat_sys_nfc,
            context.getString(R.string.status_bar_nfc),
        )
        iconController.setIconVisibility(slotNfc, false)
        updateNfc()

        applicationScope.launch(mainDispatcher) {
            secureSettings
                .observerFlow(StatusBarIconController.ICON_HIDE_LIST)
                .collect {
                    updateNfc()
                    listener.run()
                }
        }
    }

    fun isBluetoothBatteryEnabled(): Boolean {
        val hideList = StatusBarIconController.getIconHideList(
            context,
            secureSettings.getString(StatusBarIconController.ICON_HIDE_LIST),
        )
        return !hideList.contains(SLOT_BLUETOOTH_BATTERY)
    }

    private fun updateNfc() {
        val adapter = getNfcAdapter()
        iconController.setIconVisibility(slotNfc, adapter != null && adapter.isEnabled)
    }

    private fun getNfcAdapter(): NfcAdapter? {
        if (nfcAdapter == null) {
            nfcAdapter = try {
                NfcAdapter.getDefaultAdapter(context)
            } catch (e: Exception) {
                null
            }
        }
        return nfcAdapter
    }
}
