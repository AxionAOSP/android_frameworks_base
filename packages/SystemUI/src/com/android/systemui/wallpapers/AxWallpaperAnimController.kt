/*
 * Copyright 2025-2026 AxionOS
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

package com.android.systemui.wallpapers

import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.keyguard.domain.interactor.KeyguardEnabledInteractor
import com.android.systemui.keyguard.domain.interactor.LightRevealScrimInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardWakeDirectlyToGoneInteractor
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.android.systemui.power.shared.model.ScreenPowerState
import com.android.systemui.power.shared.model.WakefulnessState
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.statusbar.policy.KeyguardStateController.Callback
import com.android.systemui.util.WallpaperController
import com.android.systemui.wallpapers.data.repository.WallpaperRepository
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@SysUISingleton
class AxWallpaperAnimController
@Inject
constructor(
    private val powerInteractor: PowerInteractor,
    private val keyguardEnabledInteractor: KeyguardEnabledInteractor,
    private val lightRevealScrimInteractor: LightRevealScrimInteractor,
    private val wakeToGoneInteractor: KeyguardWakeDirectlyToGoneInteractor,
    private val keyguardStateController: KeyguardStateController,
    private val wallpaperRepository: WallpaperRepository,
    private val wallpaperController: WallpaperController,
    private val animator: AxWallpaperDepthAnimator,
    @Application private val scope: CoroutineScope,
    @Main private val mainDispatcher: CoroutineDispatcher,
) : CoreStartable {
    private var wakefulness = powerInteractor.detailedWakefulness.value.internalWakefulnessState
    private var screenPowerState = powerInteractor.screenPowerState.value
    private var keyguardEnabled = keyguardEnabledInteractor.isKeyguardEnabled.value
    private var canWakeToGone = false
    private var aodWallpaper = false
    private var lightRevealAmount = 1f

    private val keyguardCallback =
        object : Callback {
            override fun onUnlockedChanged() = sync()

            override fun onKeyguardShowingChanged() = sync()

            override fun onKeyguardFadingAwayChanged() = sync()

            override fun onKeyguardGoingAwayChanged() = sync()
        }

    override fun start() {
        keyguardStateController.addCallback(keyguardCallback)
        scope.launch(mainDispatcher) {
            powerInteractor.detailedWakefulness
                .map { it.internalWakefulnessState }
                .distinctUntilChanged()
                .collect {
                    wakefulness = it
                    if (
                        it == WakefulnessState.ASLEEP ||
                            it == WakefulnessState.STARTING_TO_SLEEP
                    ) {
                        lightRevealAmount = 0f
                    }
                    sync()
                }
        }
        scope.launch(mainDispatcher) {
            powerInteractor.screenPowerState.collect {
                screenPowerState = it
                sync()
            }
        }
        scope.launch(mainDispatcher) {
            lightRevealScrimInteractor.revealAmount.collect {
                lightRevealAmount = it
                sync()
            }
        }
        scope.launch(mainDispatcher) {
            wallpaperRepository.lockscreenWallpaperInfo.collect { sync() }
        }
        scope.launch(mainDispatcher) {
            wallpaperRepository.wallpaperSupportsAmbientMode.distinctUntilChanged().collect {
                aodWallpaper = it
                sync()
            }
        }
        scope.launch(mainDispatcher) {
            keyguardEnabledInteractor.isKeyguardEnabled.collect {
                keyguardEnabled = it
                sync()
            }
        }
        scope.launch(mainDispatcher) {
            wakeToGoneInteractor.canWakeDirectlyToGone.distinctUntilChanged().collect {
                canWakeToGone = it
                sync()
            }
        }
        sync()
    }

    private fun sync() {
        val skipWake = shouldSkipWake()
        wallpaperController.setLauncherZoomEnabled(
            wakefulness == WakefulnessState.AWAKE &&
                screenPowerState == ScreenPowerState.SCREEN_ON &&
                !keyguardStateController.isShowing
        )

        if (!canUseWallpaper()) {
            animator.clear()
            return
        }

        if (skipWake) {
            animator.clear()
            return
        }

        when (wakefulness) {
            WakefulnessState.ASLEEP,
            WakefulnessState.STARTING_TO_SLEEP -> animator.clear()
            WakefulnessState.STARTING_TO_WAKE,
            WakefulnessState.AWAKE -> {
                if (!keyguardStateController.isShowing) {
                    if (wakefulness == WakefulnessState.AWAKE) {
                        animator.clear()
                    } else {
                        animator.holdDepth()
                    }
                } else if (canRevealWallpaper()) {
                    animator.playReveal()
                } else {
                    animator.holdDepth()
                }
            }
        }
    }

    private fun canUseWallpaper(): Boolean {
        return wallpaperRepository.lockscreenWallpaperInfo.value == null &&
            keyguardEnabled &&
            !aodWallpaper
    }

    private fun shouldSkipWake(): Boolean {
        return keyguardStateController.isKeyguardGoingAway ||
            keyguardStateController.isKeyguardFadingAway ||
            (isWakingOrAwake() && canWakeToGone)
    }

    private fun canRevealWallpaper(): Boolean {
        return screenPowerState == ScreenPowerState.SCREEN_ON &&
            lightRevealAmount >= SCRIM_REVEAL_START
    }

    private fun isWakingOrAwake(): Boolean {
        return wakefulness == WakefulnessState.STARTING_TO_WAKE ||
            wakefulness == WakefulnessState.AWAKE
    }

    companion object {
        private const val SCRIM_REVEAL_START = 0.55f
    }
}
