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

package com.android.systemui.axdynamicbar.shared

import com.android.systemui.axdynamicbar.model.IslandEvent

private typealias EventType = Class<out IslandEvent>

internal val EVENT_ONGOING: List<EventType> =
    IslandEvent.ONGOING_TYPES.toList()

internal val EVENT_SHARED: List<EventType> =
    listOf(
        IslandEvent.Bluetooth::class.java,
        IslandEvent.Hotspot::class.java,
        IslandEvent.RingerMode::class.java,
        IslandEvent.BiometricUnlock::class.java,
        IslandEvent.Torch::class.java,
        IslandEvent.Vpn::class.java,
        IslandEvent.NowPlaying::class.java,
        IslandEvent.Alarm::class.java,
    )

internal val EVENT_KEYGUARD_ONLY: List<EventType> =
    listOf(
        IslandEvent.KeyguardIndication::class.java,
    )

internal val EVENT_STATUS_BAR_ONLY: List<EventType> =
    listOf(
        IslandEvent.Charging::class.java,
        IslandEvent.Clipboard::class.java,
        IslandEvent.AppSwitch::class.java,
        IslandEvent.Notification::class.java,
    )

internal val KEYGUARD_VISIBLE_EVENT_TYPES: List<EventType> =
    EVENT_ONGOING + EVENT_SHARED + EVENT_KEYGUARD_ONLY

internal val STATUS_BAR_VISIBLE_EVENT_TYPES: List<EventType> =
    EVENT_ONGOING + EVENT_SHARED + EVENT_STATUS_BAR_ONLY

internal fun IslandEvent.isVisibleOnDynamicBarSurface(onKeyguard: Boolean): Boolean =
    if (onKeyguard) isVisibleOnKeyguard() else isVisibleOnStatusBar()

internal fun IslandEvent.isVisibleOnKeyguard(): Boolean =
    KEYGUARD_VISIBLE_EVENT_TYPES.any { it.isInstance(this) }

internal fun IslandEvent.isVisibleOnStatusBar(): Boolean =
    STATUS_BAR_VISIBLE_EVENT_TYPES.any { it.isInstance(this) }
