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
package com.android.systemui.util

class NTBoosterController private constructor() {

    val controller: NTCpuBindController get() = NTCpuBindController.get()

    private var expandFraction: Float = 0.0f
    private var enableBoost: Boolean = false

    fun acquireNotificationStackBoost() {
        acquireAnimationBoost(NTCpuBindController.REQUEST_ANIMATION_BOOST_TYPE_TRACKING_NOTIFICATION_STACK_SCROLL_LAYOUT, false)
    }

    fun releaseNotificationStackBoost() {
        releaseAnimationBoost(NTCpuBindController.REQUEST_ANIMATION_BOOST_TYPE_TRACKING_NOTIFICATION_STACK_SCROLL_LAYOUT, false)
    }

    fun acquireNPVExpandingBoost() {
        controller.requestLimitOtherProcessCPU(NTCpuBindController.REQUEST_LIMIT_OTHER_PROCESS_CPU_WHEN_NOTIFICATION_EXPAND)
        acquireAnimationBoost(NTCpuBindController.REQUEST_ANIMATION_BOOST_TYPE_SWIPE_DOWN_NOTIFICATION_ANIMATION, true)
        TaskWorkerManager.instance.taskWorker.postDelayed({
            controller.setLimitForegroundAppCpu(true)
        }, 50)
    }

    fun releaseNPVExpandingBoost() {
        controller.requestUnLimitOtherProcessCPU(NTCpuBindController.REQUEST_LIMIT_OTHER_PROCESS_CPU_WHEN_NOTIFICATION_EXPAND)
        TaskWorkerManager.instance.taskWorker.postDelayed({
            controller.setLimitForegroundAppCpu(false)
        }, 50)
        controller.animationBoostOff(NTCpuBindController.REQUEST_ANIMATION_BOOST_TYPE_SWIPE_DOWN_NOTIFICATION_ANIMATION)
    }

    fun acquireNPVFlingBoost() {
        controller.animationBoostOn(NTCpuBindController.REQUEST_ANIMATION_BOOST_TYPE_FLING_NOTIFICATION_PANEL_VIEW)
    }

    fun releaseNPVFlingBoost() {
        controller.animationBoostOff(NTCpuBindController.REQUEST_ANIMATION_BOOST_TYPE_FLING_NOTIFICATION_PANEL_VIEW)
    }

    fun acquireNPVTrackingBoost() {
        controller.animationBoostOn(NTCpuBindController.REQUEST_ANIMATION_BOOST_TYPE_TRACKING_NOTIFICATION_PANEL_VIEW)
    }

    fun releaseNPVTrackingBoost() {
        controller.animationBoostOff(NTCpuBindController.REQUEST_ANIMATION_BOOST_TYPE_TRACKING_NOTIFICATION_PANEL_VIEW)
    }

    fun acquireSpeedUpNPVExpanded() {
        controller.animationBoostOn(NTCpuBindController.REQUEST_ANIMATION_BOOST_TYPE_SPEED_UP_NOTIFICATION_PANEL_VIEW_EXPAND)
    }

    fun releaseSpeedUpNPVExpanded() {
        controller.animationBoostOff(NTCpuBindController.REQUEST_ANIMATION_BOOST_TYPE_SPEED_UP_NOTIFICATION_PANEL_VIEW_EXPAND)
    }

    fun acquireExpansionAnimationBoost() {
        controller.animationBoostOn(NTCpuBindController.REQUEST_ANIMATION_BOOST_TYPE_SPEED_UP_QS_EXPANSION_ANIMATION)
    }

    fun releaseExpansionAnimationBoost() {
        controller.animationBoostOff(NTCpuBindController.REQUEST_ANIMATION_BOOST_TYPE_SPEED_UP_QS_EXPANSION_ANIMATION)
    }

    fun acquireUnlockAnimationBoost() {
        acquireAnimationBoost(NTCpuBindController.REQUEST_ANIMATION_BOOST_TYPE_UNLOCK, true)
    }

    fun releaseUnlockAnimationBoost() {
        releaseAnimationBoost(NTCpuBindController.REQUEST_ANIMATION_BOOST_TYPE_UNLOCK, true)
    }

    fun acquireUnlockedScreenAnimationOffBoost() {
        val controller = controller
        controller.requestLimitOtherProcessCPU(NTCpuBindController.REQUEST_LIMIT_OTHER_PROCESS_CPU_WHEN_PLAY_SCREEN_OFF_ANIMATION)
        acquireAnimationBoost(NTCpuBindController.REQUEST_ANIMATION_BOOST_TYPE_LIGHT_REVEAL, true)
    }

    fun releaseUnlockedScreenAnimationOffBoost() {
        val controller = controller
        controller.requestUnLimitOtherProcessCPU(NTCpuBindController.REQUEST_LIMIT_OTHER_PROCESS_CPU_WHEN_PLAY_SCREEN_OFF_ANIMATION)
        releaseAnimationBoost(NTCpuBindController.REQUEST_ANIMATION_BOOST_TYPE_LIGHT_REVEAL, true)
    }

    fun acquireDozeAnimationBoost() {
        acquireAnimationBoost(NTCpuBindController.REQUEST_ANIMATION_BOOST_TYPE_START_DOZE_ANIMATION, false)
    }

    fun releaseDozeAnimationBoost() {
        releaseAnimationBoost(NTCpuBindController.REQUEST_ANIMATION_BOOST_TYPE_START_DOZE_ANIMATION, false)
    }

    fun acquireRippleAnimationBoost() {
        acquireAnimationBoost(NTCpuBindController.REQUEST_ANIMATION_BOOST_TYPE_UNLOCK, false)
    }

    fun releaseRippleAnimationBoost() {
        releaseAnimationBoost(NTCpuBindController.REQUEST_ANIMATION_BOOST_TYPE_UNLOCK, true)
        controller.requestUnLimitOtherProcessCPU(NTCpuBindController.REQUEST_LIMIT_OTHER_PROCESS_CPU_WHEN_UNLOCK)
    }

    fun setLimitOtherAppCpu(on: Boolean) {
        if (on) {
            controller.requestLimitOtherProcessCPU(NTCpuBindController.REQUEST_LIMIT_OTHER_PROCESS_CPU_WHEN_UNLOCK)
        } else {
            controller.requestUnLimitOtherProcessCPU(NTCpuBindController.REQUEST_LIMIT_OTHER_PROCESS_CPU_WHEN_UNLOCK)
        }
        controller.setLimitForegroundAppCpu(on)
    }

    fun acquireKeyguardGoneAnimationBoost() {
        setLimitOtherAppCpu(true)
    }

    fun releaseKeyguardGoneAnimationBoost() {
        setLimitOtherAppCpu(false)
        controller.unbind()
        TaskWorkerManager.instance.taskWorker.postDelayed({
            releaseUnlockAnimationBoost()
        }, 800L)
    }

    private fun acquireAnimationBoost(sceneId: Int, needBindBigCore: Boolean = false) {
        if (needBindBigCore) controller.bindBigCore()
        controller.animationBoostOn(sceneId)
    }

    private fun releaseAnimationBoost(sceneId: Int, needReleaseCore: Boolean = false) {
        if (needReleaseCore) controller.unbind()
        controller.animationBoostOff(sceneId)
    }

    fun setExpansionEx(expansion: Float) {
        if (expansion != expandFraction) {
            if (expansion == 1.0f || expansion == 0.0f) {
                if (enableBoost) {
                    releaseExpansionAnimationBoost()
                }
                enableBoost = false
            } else {
                if (!enableBoost) {
                    acquireExpansionAnimationBoost()
                }
                enableBoost = true
            }
        }
        expandFraction = expansion
    }

    companion object {
        private var instance: NTBoosterController? = null

        @JvmStatic
        fun get(): NTBoosterController {
            if (instance == null) {
                instance = NTBoosterController()
            }
            return instance!!
        }
    }
}
