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
package com.android.systemui.edgelight

import android.app.Notification
import android.content.Context
import android.graphics.Color
import android.service.notification.NotificationListenerService.RankingMap
import android.service.notification.StatusBarNotification
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.res.R
import com.android.systemui.statusbar.NotificationListener
import com.android.systemui.util.ScrimUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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

    @Volatile
    private var lastNotificationKey: String? = null
    @Volatile
    private var lastNotificationText: CharSequence? = null
    @Volatile
    private var lastNotificationTime: Long = 0L

    @Volatile
    private var pendingNotification: StatusBarNotification? = null

    @Volatile
    private var pulseJob: Job? = null

    @Volatile
    private var cachedSettings: EdgeLightSettings = EdgeLightSettings()

    private val totalPulseDuration: Long by lazy {
        context.resources.getInteger(R.integer.doze_pulse_duration_visible).toLong()
    }
    private val fadeFraction = 0.2f

    init {
        ScrimUtils.get().addListener(this)
        listener.addNotificationHandler(this)
        observeSettings()
    }

    private fun observeSettings() {
        scope.launch {
            settingsRepo.settingsFlow.collect { settings ->
                cachedSettings = settings
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
        val settings = cachedSettings
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

                val progress = elapsed.toFloat() / totalPulseDuration * PULSE_COUNT

                val fraction = progress - progress.toInt()

                val alpha = when {
                    fraction < fadeFraction -> fraction / fadeFraction
                    fraction > 1f - fadeFraction -> (1f - fraction) / fadeFraction
                    else -> 1f
                }

                _uiState.update { it.copy(pulseAlpha = alpha) }
                delay(FRAME_DELAY_MS)
            }

            _uiState.update { it.copy(isVisible = false, isPulsing = false, pulseAlpha = 0f) }
        }
    }

    private fun stopPulse() {
        pulseJob?.cancel()
        pulseJob = null
        _uiState.update { it.copy(isVisible = false, isPulsing = false, pulseAlpha = 0f) }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap?) {
        val currentState = _uiState.value
        if (!currentState.isEnabled || !dozing) return

        val currentKey = sbn.key
        val currentText = sbn.notification.extras.getCharSequence(Notification.EXTRA_TEXT)
        val now = System.currentTimeMillis()

        if (currentKey == lastNotificationKey &&
            currentText == lastNotificationText &&
            (now - lastNotificationTime < DEDUPLICATION_WINDOW_MS)) {
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
        private const val DEDUPLICATION_WINDOW_MS = 5000L
        private const val PULSE_COUNT = 3
        private const val FRAME_DELAY_MS = 16L
    }
}
