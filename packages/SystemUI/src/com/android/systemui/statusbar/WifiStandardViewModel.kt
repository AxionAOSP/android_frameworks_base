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

import android.content.ContentResolver
import android.content.Context
import android.database.ContentObserver
import android.net.*
import android.net.wifi.WifiManager
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.*
import java.util.Collections
import java.util.WeakHashMap

data class WifiStandardState(val standard: Int, val enabled: Boolean)

class WifiStandardViewModel private constructor(
    private val context: Context
) : ViewModel() {

    private val TAG = "WifiStandardViewModel"
    private val WIFI_STANDARD_KEY = "wifi_standard_icon"

    private val views: MutableSet<WifiStandardImageView> =
        Collections.newSetFromMap(WeakHashMap<WifiStandardImageView, Boolean>())

    private var lastState: WifiStandardState? = null
    private var collectJob: Job? = null

    private fun observeState() {
        if (collectJob?.isActive == true) return

        collectJob = viewModelScope.launch {
            combine(wifiStandardFlow(), wifiStandardEnabledFlow()) { standard, enabled ->
                WifiStandardState(standard, enabled)
            }
                .distinctUntilChanged()
                .collectLatest { state ->
                    lastState = state
                    updateViews(state)
                }
        }
    }

    private fun stopObservers() {
        collectJob?.cancel()
        collectJob = null
    }

    private fun updateViews(state: WifiStandardState) {
        val iterator = views.iterator()
        while (iterator.hasNext()) {
            val view = iterator.next()
            view?.updateView(state.standard, state.enabled)
        }
        if (views.isEmpty()) {
            stopObservers()
        }
    }

    fun attachView(view: WifiStandardImageView) {
        val alreadyEmpty = views.isEmpty()
        views.add(view)
        lastState?.let {
            view.updateView(it.standard, it.enabled)
        }
        if (alreadyEmpty) {
            observeState()
        }
    }

    fun detachView(view: WifiStandardImageView) {
        views.remove(view)
        if (views.isEmpty()) {
            stopObservers()
        }
    }

    private fun wifiStandardFlow(): Flow<Int> = callbackFlow {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                trySend(
                    if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
                        wifiManager.connectionInfo.wifiStandard
                    else -1
                ).isSuccess
            }

            override fun onLost(network: Network) {
                trySend(-1).isSuccess
            }
        }

        connectivityManager.registerDefaultNetworkCallback(callback)

        trySend(
            if (connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            ) wifiManager.connectionInfo.wifiStandard else -1
        ).isSuccess

        awaitClose {
            connectivityManager.unregisterNetworkCallback(callback)
        }
    }.distinctUntilChanged()

    private fun wifiStandardEnabledFlow(): Flow<Boolean> = callbackFlow {
        val resolver = context.contentResolver
        val uri = Settings.System.getUriFor(WIFI_STANDARD_KEY)

        val observer = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) {
                trySend(isWifiStandardEnabled()).isSuccess
            }
        }

        resolver.registerContentObserver(uri, false, observer)
        trySend(isWifiStandardEnabled()).isSuccess

        awaitClose {
            resolver.unregisterContentObserver(observer)
        }
    }.distinctUntilChanged()

    private fun isWifiStandardEnabled(): Boolean {
        return Settings.System.getIntForUser(
            context.contentResolver,
            WIFI_STANDARD_KEY,
            0,
            UserHandle.USER_CURRENT
        ) == 1
    }

    companion object {
        @Volatile
        private var INSTANCE: WifiStandardViewModel? = null

        @Volatile
        private var appContext: Context? = null

        fun init(context: Context) {
            appContext = context.applicationContext
        }

        fun INSTANCE(): WifiStandardViewModel {
            val context = appContext
                ?: throw IllegalStateException("invalid context, init on centralsurfacesimpl must be called first!!")
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: WifiStandardViewModel(context).also {
                    INSTANCE = it
                }
            }
        }
    }
}
