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
package com.android.systemui.qs.tiles.ringer

import androidx.compose.runtime.staticCompositionLocalOf
import com.android.systemui.dagger.SysUISingleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

val LocalRingerSliderViewModel = staticCompositionLocalOf<RingerSliderViewModel> {
    error("RingerSliderViewModel not provided")
}

@SysUISingleton
class RingerSliderViewModel @Inject constructor(
    private val interactor: RingerSliderInteractor
) {
    val availableModes: List<RingerModeOption> get() = interactor.availableModes
    val numModes: Int get() = interactor.numModes
    val maxOffset: Float get() = interactor.maxOffset
    val currentMode: Int get() = interactor.currentMode
    val ringerModeChanges: Flow<Int> get() = interactor.ringerModeChanges
    val isZenMuted: StateFlow<Boolean> get() = interactor.isZenMuted
    fun setRingerMode(mode: Int) = interactor.setRingerMode(mode)
    fun targetPosition(ringerMode: Int): Float = interactor.targetPosition(ringerMode)
    fun snapToIndex(offset: Float): Int = interactor.snapToIndex(offset)
}
