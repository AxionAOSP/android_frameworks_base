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
 * limitations under the License
 */

package com.android.systemui.doze

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemProperties
import android.util.Log
import android.view.Display
import com.android.systemui.Dependency
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.doze.DozeMachine
import com.android.systemui.settings.DisplayTracker
import com.android.systemui.statusbar.phone.UnlockedScreenOffAnimationControllerExt
import com.android.systemui.statusbar.phone.ScreenOffAnimationCallback
import com.android.systemui.statusbar.StatusBarState.KEYGUARD
import com.android.systemui.SystemUIApplication
import com.android.systemui.util.ScrimUtils
import java.util.concurrent.Executor
import java.util.function.Consumer
import javax.inject.Inject

@SysUISingleton
class DozeScreenStateEx @Inject constructor(
    private val context: Context,
    @Main private val executor: Executor,
    private val displayTracker: DisplayTracker
) {

    companion object {
        private const val TAG = "DozeScreenStateEx"
        @JvmField
        val SUSPEND_DELAY_TIME = SystemProperties.getInt("persist.sys.doze_suspend_duration", 3000)
        val NEEDS_DOZE_FIX = SystemProperties.getBoolean("persist.sys.enable_doze_fix", false)

        @JvmStatic
        fun get(context: Context): DozeScreenStateEx {
            val app = context.applicationContext as SystemUIApplication
            return app.sysUIComponent.dozeScreenStateEx()
        }
    }

    private val handler: Handler = Handler(Looper.getMainLooper())
    private var curDisplayState: Int = 0
    private val displayManager: DisplayManager =
        context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

    private var unlockAnimPlaying: Boolean = false
    private var curState: DozeMachine.State = DozeMachine.State.UNINITIALIZED
    private var screenStateConsumer: Consumer<Int>? = null

    private val screenOffAnimationCallback = object : ScreenOffAnimationCallback() {
        override fun onAnimationStart() {
            ScrimUtils.get().setBarState(KEYGUARD)
            ScrimUtils.get().onDozingChanged(true)
        }
        override fun onAnimationEnd() {
            unlockAnimPlaying = false
            Log.d(TAG, "ScreenOffAnimation animationEnd: $curState, display state: $curDisplayState")
            if (NEEDS_DOZE_FIX && (curState == DozeMachine.State.DOZE_AOD 
                    || curState == DozeMachine.State.DOZE_AOD_PAUSING)) {
                screenStateConsumer?.accept(Display.STATE_DOZE)
            }
        }

        override fun onAnimateInKeyguardEnd() {
            Log.d(TAG, "ScreenOffAnimation animateInKeyguardEnd: $curState")
        }
    }

    private val displayCallback: DisplayTracker.Callback = object : DisplayTracker.Callback {
        override fun onDisplayChanged(state: Int) {
            if (state == Display.STATE_UNKNOWN) {
                curDisplayState = displayTracker.getDefaultDisplayCommittedState()
            }
        }
    }

    init {
        curDisplayState = displayManager.getDisplay(0).committedState
        displayTracker.addCommittedStateChangeCallback(displayCallback, executor)
    }

    fun init(consumer: Consumer<Int>) {
        screenStateConsumer = consumer
    }

    val isUnlockAnimPlaying: Boolean
        get() = unlockAnimPlaying

    val curDisplay: Int
        get() = curDisplayState

    fun transitionTo(prevState: DozeMachine.State, newState: DozeMachine.State) {
        curState = newState
        when (newState) {
            DozeMachine.State.INITIALIZED -> {
                UnlockedScreenOffAnimationControllerExt.addCallback(screenOffAnimationCallback)
                unlockAnimPlaying = UnlockedScreenOffAnimationControllerExt.isAnimationPlaying
            }
            DozeMachine.State.FINISH -> {
                UnlockedScreenOffAnimationControllerExt.removeCallback(screenOffAnimationCallback)
                screenStateConsumer = null
            }
            else -> {}
        }
    }
}
