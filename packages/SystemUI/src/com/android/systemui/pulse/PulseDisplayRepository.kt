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
package com.android.systemui.pulse

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.utils.coroutines.flow.conflatedCallbackFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import java.util.concurrent.Executor
import javax.inject.Inject

data class PulseDisplayState(
    val refreshRate: Float = 60f,
    val throttleMs: Long = 16L
)

@SysUISingleton
class PulseDisplayRepository @Inject constructor(
    @Application private val context: Context,
    @Application private val scope: CoroutineScope,
    @Main private val mainExecutor: Executor
) {
    private val displayManager = context.getSystemService(DisplayManager::class.java)!!

    val displayState: StateFlow<PulseDisplayState> = conflatedCallbackFlow {
        val callback = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {}
            override fun onDisplayRemoved(displayId: Int) {}
            override fun onDisplayChanged(displayId: Int) {
                if (displayId == Display.DEFAULT_DISPLAY) {
                    trySend(computeDisplayState())
                }
            }
        }

        displayManager.registerDisplayListener(mainExecutor, DisplayManager.EVENT_TYPE_DISPLAY_CHANGED, callback)
        trySend(computeDisplayState())

        awaitClose {
            displayManager.unregisterDisplayListener(callback)
        }
    }
    .distinctUntilChanged()
    .stateIn(
        scope = scope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = computeDisplayState()
    )

    private fun computeDisplayState(): PulseDisplayState {
        val refreshRate = context.display?.refreshRate ?: 60f
        val throttleMs = (1000f / refreshRate).toLong()

        return PulseDisplayState(
            refreshRate = refreshRate,
            throttleMs = throttleMs
        )
    }
}
