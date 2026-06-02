/*
 * Copyright (C) 2026 AxionOS
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

package com.android.systemui.qs.tiles.impl.dataswitch.domain.model

data class DataSwitchSubscriptionModel(
    val subscriptionId: Int,
    val slotIndex: Int,
    val displayName: String,
)

data class DataSwitchTileModel(
    val subscriptions: List<DataSwitchSubscriptionModel>,
    val defaultDataSubscriptionId: Int?,
) {
    val isEnabled: Boolean = subscriptions.size >= 2

    val activeSubscription: DataSwitchSubscriptionModel? =
        subscriptions.firstOrNull { it.subscriptionId == defaultDataSubscriptionId }
            ?: subscriptions.firstOrNull()

    fun nextSubscription(): DataSwitchSubscriptionModel? {
        if (subscriptions.size < 2) return null
        val currentIndex = subscriptions.indexOfFirst {
            it.subscriptionId == defaultDataSubscriptionId
        }
        return subscriptions[
            if (currentIndex >= 0) (currentIndex + 1) % subscriptions.size else 0
        ]
    }
}
