/*
 * Copyright (C) 2025 The AxionAOSP Project
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
package com.android.systemui.edgelight

import android.app.Notification
import android.content.Context
import android.graphics.Color
import android.service.notification.NotificationListenerService.RankingMap
import android.service.notification.StatusBarNotification
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.statusbar.NotificationListener
import com.android.systemui.util.ScrimUtils
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject

data class EdgeLightUiState(
    val isEnabled: Boolean = false,
    val isVisible: Boolean = false,
    val isPulsing: Boolean = false,
    val color: Int = Color.WHITE,
    val pulseAlpha: Float = 0f,
)

@SysUISingleton
class EdgeLightInteractor
@Inject
constructor(
    @Application private val context: Context,
    private val listener: NotificationListener,
    @Application private val scope: CoroutineScope,
    private val settingsRepo: EdgeLightSettingsRepository,
) : ScrimUtils.ScrimEventListener, NotificationListener.NotificationHandler {
    
    private val _uiState = MutableStateFlow(EdgeLightUiState())
    val uiState: StateFlow<EdgeLightUiState> = _uiState.asStateFlow()

    private val accentColor: Int
        get() = context.getColor(android.R.color.system_accent1_100)

    private val dozing: Boolean
        get() = ScrimUtils.get().isDozing()

    private var lastNotificationKey: String? = null
    private var lastNotificationText: CharSequence? = null
    private var lastNotificationTime: Long = 0L
    private val deduplicationWindowMs = 5000L

    private var pendingNotification: StatusBarNotification? = null
    
    private var pulseJob: Job? = null

    private val totalPulseDuration: Long by lazy {
        context.resources.getInteger(com.android.systemui.res.R.integer.doze_pulse_duration_visible).toLong()
    }
    private val pulseCount = 3
    private val singlePulseDuration: Long by lazy { totalPulseDuration / pulseCount }
    private val fadeFraction = 0.2f

    init {
        ScrimUtils.get().addListener(this)
        listener.addNotificationHandler(this)
        observeSettings()
    }

    private fun observeSettings() {
        scope.launch {
            settingsRepo.settingsFlow.collect { settings ->
                _uiState.update { current ->
                    current.copy(
                        isEnabled = settings.isEnabled,
                        color = when (settings.colorMode) {
                            COLOR_MODE_CUSTOM -> settings.customColor
                            else -> accentColor
                        }
                    )
                }
            }
        }
    }

    private fun getColor(sbn: StatusBarNotification?): Int {
        val settings = settingsRepo.currentSettings()
        return when (settings.colorMode) {
            COLOR_MODE_CUSTOM -> settings.customColor
            else -> sbn?.notification?.color ?: accentColor
        }
    }

    private fun startPulse(color: Int) {
        pulseJob?.cancel()
        pulseJob = scope.launch {
            _uiState.update { it.copy(isVisible = true, isPulsing = true, color = color) }
            
            val startTime = System.currentTimeMillis()
            while (isActive) {
                val elapsed = System.currentTimeMillis() - startTime
                if (elapsed >= totalPulseDuration) break
                
                val progress = elapsed.toFloat() / totalPulseDuration * pulseCount
                val pulseIndex = progress.toInt()
                val fraction = progress - pulseIndex
                
                val alpha = when {
                    fraction < fadeFraction -> fraction / fadeFraction
                    fraction > 1f - fadeFraction -> (1f - fraction) / fadeFraction
                    else -> 1f
                }
                
                _uiState.update { it.copy(pulseAlpha = alpha) }
                delay(16)
            }
            
            _uiState.update { it.copy(isVisible = false, isPulsing = false, pulseAlpha = 0f) }
        }
    }

    private fun stopPulse() {
        pulseJob?.cancel()
        pulseJob = null
        _uiState.update { it.copy(isVisible = false, isPulsing = false, pulseAlpha = 0f) }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap) {
        val currentState = _uiState.value
        if (!currentState.isEnabled || !dozing) return

        val currentKey = sbn.key
        val currentText = sbn.notification.extras.getCharSequence(Notification.EXTRA_TEXT)
        val now = System.currentTimeMillis()

        if (currentKey == lastNotificationKey &&
            currentText == lastNotificationText &&
            (now - lastNotificationTime < deduplicationWindowMs)) {
            return
        }

        lastNotificationKey = currentKey
        lastNotificationText = currentText
        lastNotificationTime = now
        pendingNotification = sbn
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap) {}
    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap, reason: Int) {}
    override fun onNotificationsInitialized() {}
    override fun onNotificationRankingUpdate(rankingMap: RankingMap) {}

    override fun onDozingChanged() {
        if (!dozing) {
            stopPulse()
        }
    }

    override fun onKeyguardShowingChanged(showing: Boolean) {
        if (!showing) {
            stopPulse()
            listener.removeNotificationHandler(this)
        } else {
            listener.addNotificationHandler(this)
        }
    }

    override fun onKeyguardFadingAwayChanged(fadingAway: Boolean) {
        if (fadingAway) {
            stopPulse()
        }
    }

    override fun onKeyguardGoingAwayChanged(goingAway: Boolean) {
        if (goingAway) {
            stopPulse()
        }
    }

    override fun setPulsing(pulsing: Boolean) {
        val currentState = _uiState.value
        if (!pulsing || !currentState.isEnabled || !dozing) return
        
        val sbn = pendingNotification
        pendingNotification = null
        val color = getColor(sbn)
        startPulse(color)
    }

    companion object {
        private const val COLOR_MODE_CUSTOM = "custom"
    }
}
