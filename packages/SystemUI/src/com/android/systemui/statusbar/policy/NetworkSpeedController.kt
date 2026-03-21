/*
 * Copyright (C) 2025-2026 AxionOS Project
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
package com.android.systemui.statusbar.policy

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.TrafficStats
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.keyguard.KeyguardUpdateMonitorCallback
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.statusbar.phone.StatusBarIconControllerImplEx
import com.android.systemui.statusbar.policy.networkspeed.NetworkSpeedIconState
import com.android.systemui.util.settings.SecureSettings
import com.android.systemui.util.settings.SettingsProxyExt.observerFlow
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@SysUISingleton
class NetworkSpeedController @Inject constructor(
    private val connectivityManager: ConnectivityManager,
    private val secureSettings: SecureSettings,
    private val keyguardUpdateMonitor: KeyguardUpdateMonitor,
    private val iconControllerEx: StatusBarIconControllerImplEx,
    @Background private val bgDispatcher: CoroutineDispatcher,
    @Main private val mainDispatcher: CoroutineDispatcher,
) : CoreStartable {

    private var isSwitchOn = false
    private var isConnected = false
    private var networkVisibility = false

    private var lastTime = 0L
    private var lastTotalBytes = 0L

    private val scope = CoroutineScope(bgDispatcher + SupervisorJob())
    private var speedUpdateJob: Job? = null

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            updateConnectionState(true)
        }

        override fun onLost(network: Network) {
            updateConnectionState(false)
        }
    }

    override fun start() {
        connectivityManager.registerDefaultNetworkCallback(networkCallback)

        scope.launch {
            secureSettings
                .observerFlow(ICON_HIDE_LIST)
                .onStart { emit(Unit) }
                .map { readSwitchState() }
                .distinctUntilChanged()
                .flowOn(bgDispatcher)
                .collect { enabled ->
                    isSwitchOn = enabled
                    reset()
                    restartSpeedUpdates()
                }
        }

        keyguardUpdateMonitor.registerCallback(
            object : KeyguardUpdateMonitorCallback() {
                override fun onUserSwitchComplete(newUserId: Int) {
                    isSwitchOn = readSwitchState()
                    reset()
                    restartSpeedUpdates()
                }
            }
        )
    }

    private fun readSwitchState(): Boolean {
        val iconHideList = secureSettings.getString(ICON_HIDE_LIST)
        return !iconHideList.isNullOrEmpty()
            && !iconHideList.contains(SLOT_NETWORK_SPEED)
    }

    private fun updateConnectionState(connected: Boolean) {
        isConnected = connected
        restartSpeedUpdates()
    }

    private fun restartSpeedUpdates() {
        speedUpdateJob?.cancel()
        if (isConnected && isSwitchOn) {
            speedUpdateJob = scope.launch {
                while (isActive) {
                    updateNetworkSpeed()
                    delay(REFRESH_INTERVAL_MS)
                }
            }
        } else {
            scope.launch {
                updateNetworkSpeed()
            }
        }
    }

    private suspend fun updateNetworkSpeed() {
        val currentTime = System.currentTimeMillis()
        val totalBytes = getTotalBytes()

        var speed = 0L
        if (lastTime > 0 && lastTotalBytes > 0 &&
            totalBytes > lastTotalBytes && currentTime > lastTime
        ) {
            speed = (((totalBytes - lastTotalBytes) * 1000) / (currentTime - lastTime)).toLong()
        }

        val iconState = NetworkSpeedIconState().apply {
            setVisible(isConnected && isSwitchOn)
            setSpeedText(speed)
            setSlot(SLOT_NETWORK_SPEED)
        }

        withContext(mainDispatcher) {
            iconControllerEx.setNetworkSpeedIcon(SLOT_NETWORK_SPEED, iconState)
            if (networkVisibility != iconState.isVisible()) {
                networkVisibility = iconState.isVisible()
            }
        }

        lastTime = currentTime
        lastTotalBytes = totalBytes
    }

    private fun getTotalBytes(): Long {
        val rx = TrafficStats.getTotalRxBytes()
        val tx = TrafficStats.getTotalTxBytes()
        return if (rx >= 0 && tx >= 0) rx + tx else 0
    }

    private fun reset() {
        lastTime = 0
        lastTotalBytes = 0
    }

    fun isSwitchOn(): Boolean = isSwitchOn

    companion object {
        const val SLOT_NETWORK_SPEED = "network_speed"
        const val ICON_HIDE_LIST = "icon_blacklist"
        const val REFRESH_INTERVAL_MS = 4000L
    }
}
