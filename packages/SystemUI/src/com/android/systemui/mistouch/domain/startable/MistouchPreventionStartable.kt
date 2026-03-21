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
package com.android.systemui.mistouch.domain.startable

import com.android.app.tracing.coroutines.launchTraced as launch
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.keyguard.KeyguardUpdateMonitorCallback
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.mistouch.data.repository.MistouchSensorRepository
import com.android.systemui.mistouch.data.repository.MistouchSettingsRepository
import com.android.systemui.mistouch.domain.interactor.MistouchInteractor
import com.android.systemui.mistouch.domain.model.MistouchEvent
import com.android.systemui.mistouch.shared.model.MistouchSensorState
import com.android.systemui.mistouch.ui.view.MistouchPreventionOverlay
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.android.systemui.power.shared.model.ScreenPowerState
import com.android.systemui.statusbar.phone.BiometricUnlockController
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.util.ScrimUtils
import com.android.systemui.util.concurrency.DelayableExecutor
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope

private const val DEFAULT_SUPPRESSION_DURATION_MS = 5000L
private const val KEYGUARD_INTERACTION_SUPPRESSION_DURATION_MS = 15000L

@SysUISingleton
class MistouchPreventionStartable @Inject constructor(
    private val settingsRepository: MistouchSettingsRepository,
    private val sensorRepository: MistouchSensorRepository,
    private val keyguardStateController: KeyguardStateController,
    private val keyguardUpdateMonitor: KeyguardUpdateMonitor,
    private val biometricUnlockController: BiometricUnlockController,
    @Application private val applicationScope: CoroutineScope,
    @Main private val mainExecutor: DelayableExecutor,
    private val powerInteractor: PowerInteractor,
    private val preventionOverlay: MistouchPreventionOverlay,
) : CoreStartable,
    MistouchInteractor.MistouchEventListener,
    MistouchPreventionOverlay.Callback,
    ScrimUtils.ScrimEventListener {

    private var mistouchPreventionEnabled = false
    private var registered = false
    private var suppressionStartedAtMillis = 0L
    private var suppressionDurationMillis = DEFAULT_SUPPRESSION_DURATION_MS
    private var sensorState = MistouchSensorState()
    private var cancelSuppressionExpiration: Runnable? = null

    private val keyguardShowing get() = ScrimUtils.get().isKeyguardShowing()
    private val dozing get() = ScrimUtils.get().isDozing()
    private val displayActive get() = dozing ||
        powerInteractor.screenPowerState.value != ScreenPowerState.SCREEN_OFF
    private val shouldListen
        get() = mistouchPreventionEnabled &&
            displayActive &&
            keyguardShowing &&
            !keyguardStateController.isOccluded &&
            !keyguardStateController.isKeyguardGoingAway &&
            !keyguardStateController.isKeyguardFadingAway &&
            !biometricUnlockController.isWakeAndUnlock()
    private val overlaySuppressed
        get() = suppressionRemainingMillis > 0L
    private val suppressionRemainingMillis
        get() = suppressionDurationMillis - (System.currentTimeMillis() - suppressionStartedAtMillis)

    private fun updateState() {
        if (!registered) return

        if (overlaySuppressed) {
            preventionOverlay.hide()
            return
        }

        if (sensorState.shouldShowOverlay) preventionOverlay.show() else preventionOverlay.hide()
    }

    private val keyguardCallback = object : KeyguardUpdateMonitorCallback() {
        override fun onUserSwitching(userId: Int) {
            disable()
        }

        override fun onUserSwitchComplete(userId: Int) {
            updateListening()
        }
    }

    private val keyguardStateCallback = object : KeyguardStateController.Callback {
        override fun onKeyguardShowingChanged() {
            updateListening()
        }

        override fun onUnlockedChanged() {
            updateListening()
        }

        override fun onKeyguardFadingAwayChanged() {
            updateListening()
        }

        override fun onKeyguardGoingAwayChanged() {
            updateListening()
        }
    }

    override fun start() {
        preventionOverlay.setCallback(this)

        if (sensorRepository.hasSensors) {
            keyguardUpdateMonitor.registerCallback(keyguardCallback)
            keyguardStateController.addCallback(keyguardStateCallback)
            ScrimUtils.get().addListener(this)
            MistouchInteractor.get().addListener(this)
            applicationScope.launch {
                settingsRepository.isEnabled.collect {
                    mistouchPreventionEnabled = it
                    updateListening()
                }
            }
            applicationScope.launch {
                powerInteractor.screenPowerState.collect { updateListening() }
            }
            updateListening()
        }
    }

    private fun updateListening() {
        if (shouldListen) enable() else disable()
    }

    private fun enable() {
        if (!sensorRepository.hasSensors || registered) return

        registered = sensorRepository.startListening {
            sensorState = it
            updateState()
        }
        if (!registered) return

        preventionOverlay.attach()
        if (overlaySuppressed) scheduleSuppressionExpiration()
    }

    private fun disable() {
        if (!sensorRepository.hasSensors) return

        cancelSuppressionExpiration?.run()
        cancelSuppressionExpiration = null
        preventionOverlay.detach()
        sensorState = MistouchSensorState()

        if (registered) {
            sensorRepository.stopListening()
            registered = false
        }
    }

    private fun suppressOverlay(durationMillis: Long = DEFAULT_SUPPRESSION_DURATION_MS) {
        suppressionStartedAtMillis = System.currentTimeMillis()
        suppressionDurationMillis = durationMillis
        preventionOverlay.hide()
        if (registered) scheduleSuppressionExpiration()
    }

    private fun scheduleSuppressionExpiration() {
        cancelSuppressionExpiration?.run()
        val delayMillis = suppressionRemainingMillis
        if (delayMillis <= 0L) {
            cancelSuppressionExpiration = null
            updateState()
            return
        }
        cancelSuppressionExpiration = mainExecutor.executeDelayed({
            cancelSuppressionExpiration = null
            updateState()
        }, delayMillis)
    }

    override fun onVolumeUpPressed() {
        suppressOverlay()
    }

    override fun onMistouchEvent(event: MistouchEvent) {
        when (event) {
            MistouchEvent.KEYGUARD_INTERACTION -> {
                if (keyguardShowing) {
                    suppressOverlay(KEYGUARD_INTERACTION_SUPPRESSION_DURATION_MS)
                }
            }
            MistouchEvent.AFFORDANCE_LONG_CLICK -> {
                suppressOverlay(KEYGUARD_INTERACTION_SUPPRESSION_DURATION_MS)
            }
            else -> suppressOverlay()
        }
    }

    override fun onDozingChanged() {
        updateListening()
    }

    override fun onKeyguardShowingChanged(showing: Boolean) {
        updateListening()
    }

    override fun onStartedWakingUp() {
        updateListening()
    }

    override fun onScreenTurnedOff() {
        if (dozing) updateListening() else disable()
    }
}
