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

package com.android.systemui.shared.clocks

object AxClockNotificationSpace {
    fun availableHeight(height: Float, density: Float, onLockscreen: Boolean): Float {
        if (!onLockscreen) return height
        val downwardOffsetDp = ClockSettingsRepository.topPaddingDp.value.coerceAtLeast(0f)
        return (height - downwardOffsetDp * density).coerceAtLeast(0f)
    }
}
