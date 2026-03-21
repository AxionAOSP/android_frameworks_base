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
package com.android.systemui.statusbar

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.shared.settings.data.repository.SecureSettingsRepository
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class WifiStandardState(val standard: Int, val enabled: Boolean)

@SysUISingleton
class WifiStandardRepository @Inject constructor(
    private val context: Context,
    private val secureSettingsRepository: SecureSettingsRepository,
    @Application private val scope: CoroutineScope,
    @Background private val bgDispatcher: CoroutineDispatcher,
) {
    val state: StateFlow<WifiStandardState> = combine(
        wifiStandardFlow(),
        wifiStandardEnabledFlow()
    ) { standard, enabled ->
        WifiStandardState(standard, enabled)
    }
        .stateIn(
            scope,
            SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
            WifiStandardState(-1, false)
        )

    private fun wifiStandardFlow(): Flow<Int> = callbackFlow {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val wm = context.getSystemService(Context.WIFI_SERVICE) as WifiManager

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(n: Network, nc: NetworkCapabilities) {
                trySend(
                    if (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
                        wm.connectionInfo.wifiStandard else -1
                )
            }

            override fun onLost(n: Network) {
                trySend(-1)
            }
        }
        cm.registerDefaultNetworkCallback(callback)

        trySend(
            if (cm.getNetworkCapabilities(cm.activeNetwork)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            ) wm.connectionInfo.wifiStandard else -1
        )

        awaitClose { cm.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged().flowOn(bgDispatcher)

    private fun wifiStandardEnabledFlow(): Flow<Boolean> =
        secureSettingsRepository
            .intSetting(WIFI_STANDARD_KEY, defaultValue = 0)
            .map { it == 1 }
            .flowOn(bgDispatcher)

    companion object {
        private const val WIFI_STANDARD_KEY = "wifi_standard_icon"
    }
}
