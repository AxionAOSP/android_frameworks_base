/*
 * Copyright (C) 2025 AxionOS
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
import android.net.*
import android.net.wifi.WifiManager
import android.os.UserHandle
import android.provider.Settings
import com.android.systemui.dagger.SysUISingleton
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import java.util.concurrent.Executors
import javax.inject.Inject

data class WifiStandardState(val standard: Int, val enabled: Boolean)

@SysUISingleton
class WifiStandardRepository @Inject constructor(
    private val context: Context
) {
    private val bgDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + bgDispatcher)

    private val WIFI_STANDARD_KEY = "wifi_standard_icon"

    val state: StateFlow<WifiStandardState> = combine(
        wifiStandardFlow(),
        wifiStandardEnabledFlow()
    ) { standard, enabled ->
        WifiStandardState(standard, enabled)
    }
        .distinctUntilChanged()
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

    private fun wifiStandardEnabledFlow(): Flow<Boolean> = callbackFlow {
        val resolver = context.contentResolver
        val uri = Settings.Secure.getUriFor(WIFI_STANDARD_KEY)

        val observer = object : android.database.ContentObserver(null) {
            override fun onChange(selfChange: Boolean) {
                trySend(isWifiStandardEnabled())
            }
        }
        resolver.registerContentObserver(uri, false, observer)
        trySend(isWifiStandardEnabled())

        awaitClose { resolver.unregisterContentObserver(observer) }
    }.distinctUntilChanged().flowOn(bgDispatcher)

    private fun isWifiStandardEnabled(): Boolean =
        Settings.Secure.getIntForUser(
            context.contentResolver,
            WIFI_STANDARD_KEY,
            0,
            UserHandle.USER_CURRENT
        ) == 1
}
