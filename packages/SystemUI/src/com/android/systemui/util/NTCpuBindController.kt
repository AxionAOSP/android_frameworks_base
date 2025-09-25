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

import android.os.Process
import android.os.SystemProperties
import com.android.internal.util.BoostHelper

class NTCpuBindController private constructor() {

    private var mAnimationBoostType = 0
    private var mBindStatus = STATUS_UNBIND
    private var mAnimationBoost = ANIMATION_BOOST_OFF

    private var mLimitOtherProcessCpuReason = 0
    private var mLimitForegroundAppCpu = false
    private var mLimitOtherProcessCpu = false
    
    private val pid get() = Process.myUid()

    fun bindBigCore() {
        if (mBindStatus == STATUS_BIND_BIG_CORE) {
            return
        }
        mBindStatus = STATUS_BIND_BIG_CORE
        BoostHelper.setThreadAffinity(pid, STATUS_BIND_BIG_CORE)
    }

    fun bindSmallCore() {
        if (mBindStatus == STATUS_BIND_SMALL_CORE) {
            return
        }
        mBindStatus = STATUS_BIND_SMALL_CORE
        BoostHelper.setThreadAffinity(pid, STATUS_BIND_SMALL_CORE)
    }

    fun unbind() {
        if (mBindStatus == STATUS_UNBIND) {
            return
        }
        mBindStatus = STATUS_UNBIND
        BoostHelper.setThreadAffinity(pid, STATUS_UNBIND)
    }

    fun animationBoostOn(type: Int) {
        mAnimationBoostType = mAnimationBoostType or type
        if (mAnimationBoost == ANIMATION_BOOST_ON) {
            return
        }
        bindBigCore()
        mAnimationBoost = ANIMATION_BOOST_ON
        BoostHelper.animationBoost(pid, ANIMATION_BOOST_ON)
    }

    fun animationBoostOff(type: Int) {
        mAnimationBoostType = mAnimationBoostType and type.inv()
        if (mAnimationBoostType <= 0 && mAnimationBoost != ANIMATION_BOOST_OFF) {
            unbind()
            mAnimationBoost = ANIMATION_BOOST_OFF
            BoostHelper.animationBoost(pid, ANIMATION_BOOST_OFF)
        }
    }

    fun setLimitForegroundAppCpu(limit: Boolean) {
        if (limit != mLimitForegroundAppCpu) {
            if (limit) {
                BoostHelper.adjustCpusetCpus(TOP_APP_GROUP, CPUS_PARAMS_SMALL_LIMIT, 0L)
            } else { 
                BoostHelper.adjustCpusetCpus(TOP_APP_GROUP, CPUS_PARAMS_UI_UNLIMIT, 0L)
            }
            mLimitForegroundAppCpu = limit
        }
    }

    fun setLimitOtherProcessCpu(limit: Boolean) {
        if (limit != mLimitOtherProcessCpu) {
            if (limit) {
                BoostHelper.adjustCpusetCpus(CAMERA_DAEMON_GROUP, CPUS_PARAMS_BG_LIMIT, 0L)
                BoostHelper.adjustCpusetCpus(DEX2OAT_GROUP, CPUS_PARAMS_BG_LIMIT, 0L)
            } else {
                BoostHelper.adjustCpusetCpus(CAMERA_DAEMON_GROUP, CPUS_PARAMS_UI_UNLIMIT, 0L)
                BoostHelper.adjustCpusetCpus(DEX2OAT_GROUP, CPUS_PARAMS_UI_UNLIMIT, 0L)
            }
            mLimitOtherProcessCpu = limit
        }
    }

    fun requestLimitOtherProcessCPU(type: Int) {
        mLimitOtherProcessCpuReason = mLimitOtherProcessCpuReason or type
        limitCameraHalCpu()
    }

    fun requestUnLimitOtherProcessCPU(type: Int) {
        mLimitOtherProcessCpuReason = mLimitOtherProcessCpuReason and type.inv()
        limitCameraHalCpu()
    }

    private fun limitCameraHalCpu() {
        val limit = mLimitOtherProcessCpuReason > 0
        setLimitOtherProcessCpu(limit)
    }
    
    companion object {
        private const val TAG = "NTCpuBindController"

        private const val CPUSET_PATH = "/dev/cpuset/"
        private const val CAMERA_DAEMON_GROUP = CPUSET_PATH + "camera-daemon/cpus"
        private const val TOP_APP_GROUP = CPUSET_PATH + "top-app/cpus"
        private const val FG_GROUP = CPUSET_PATH + "foreground/cpus"
        private const val FG_WINDOW_GROUP = CPUSET_PATH + "foreground_window/cpus"
        private const val SYS_BG_GROUP = CPUSET_PATH + "system-background/cpus"
        private const val BG_GROUP = CPUSET_PATH + "background/cpus"
        private const val DEX2OAT_GROUP = CPUSET_PATH + "dex2oat/cpus"

        private val CPUS_PARAMS_BG_LIMIT = SystemProperties.get("persist.sys.axion_cpu_limit_bg", "0-1")
        private val CPUS_PARAMS_UI_UNLIMIT = SystemProperties.get("persist.sys.axion_cpu_all", "0-7")
        private val CPUS_PARAMS_SMALL_CORES = SystemProperties.get("persist.sys.axion_cpu_small", "0,1,2,3")
        private val CPUS_PARAMS_SMALL_LIMIT = getCpuRange(CPUS_PARAMS_SMALL_CORES)

        const val REQUEST_ANIMATION_BOOST_TYPE_BASE = 1
        const val REQUEST_ANIMATION_BOOST_TYPE_FLING_NOTIFICATION_PANEL_VIEW = 1
        const val REQUEST_LIMIT_OTHER_PROCESS_CPU_WHEN_NOTIFICATION_EXPAND = 16
        const val REQUEST_LIMIT_OTHER_PROCESS_CPU_WHEN_PLAY_SCREEN_OFF_ANIMATION = 256
        const val REQUEST_LIMIT_OTHER_PROCESS_CPU_WHEN_UNLOCK = 1

        const val REQUEST_ANIMATION_BOOST_TYPE_TRACKING_NOTIFICATION_PANEL_VIEW = 1 shl 1
        const val REQUEST_ANIMATION_BOOST_TYPE_SPEED_UP_NOTIFICATION_PANEL_VIEW_EXPAND = 1 shl 2
        const val REQUEST_ANIMATION_BOOST_TYPE_UNLOCK = 1 shl 3
        const val REQUEST_ANIMATION_BOOST_TYPE_LIGHT_REVEAL = 1 shl 4
        const val REQUEST_ANIMATION_BOOST_TYPE_TRACKING_NOTIFICATION_STACK_SCROLL_LAYOUT = 1 shl 5
        const val REQUEST_ANIMATION_BOOST_TYPE_SPEED_UP_QS_EXPANSION_ANIMATION = 1 shl 6
        const val REQUEST_ANIMATION_BOOST_TYPE_SWIPE_DOWN_NOTIFICATION_ANIMATION = 1 shl 7
        const val REQUEST_ANIMATION_BOOST_TYPE_START_DOZE_ANIMATION = 1 shl 8

        private const val STATUS_BIND_BIG_CORE = 0
        private const val STATUS_BIND_SMALL_CORE = 1
        private const val STATUS_UNBIND = 2

        private const val ANIMATION_BOOST_ON = 0L
        private const val ANIMATION_BOOST_OFF = -1L

        private var instance: NTCpuBindController? = null

        @JvmStatic
        fun get(): NTCpuBindController {
            if (instance == null) {
                instance = NTCpuBindController()
            }
            return instance!!
        }

        private fun getCpuRange(cpuList: String): String {
            val range = cpuList.split(",").map { it.toInt() }
            return "${range.minOrNull() ?: 0}-${range.maxOrNull() ?: 0}"
        }
    }
}
