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
package com.android.systemui.qs.tiles.volume

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.vector.ImageVector
import com.android.systemui.dagger.SysUISingleton
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

val LocalVolumeSliderViewModel = staticCompositionLocalOf<VolumeSliderViewModel> {
    error("VolumeSliderViewModel not provided")
}

@SysUISingleton
class VolumeSliderViewModel @Inject constructor(
    private val interactor: VolumeSliderInteractor
) {
    val volumeChanges: Flow<Float> get() = interactor.volumeChanges
    fun isActive(): Boolean = interactor.isActive()
    fun onTap(enabled: Boolean) = interactor.onTap(enabled)
    fun currentLevel(): Float = interactor.currentLevel()
    fun setLevel(level: Float) = interactor.setLevel(level)
    fun iconForLevel(level: Float): ImageVector = interactor.iconForLevel(level)
    fun labelForLevel(level: Float): String = interactor.labelForLevel(level)
}
