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

package com.android.systemui.qs.tiles

import android.telephony.SubscriptionManager
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import java.util.concurrent.Executor
import javax.inject.Inject

@SysUISingleton
class FiveGCoreStartable @Inject constructor(
    private val fiveGUtils: AxFiveGUtils,
    private val subscriptionManager: SubscriptionManager,
    @Background private val bgExecutor: Executor,
) : CoreStartable {

    private val subscriptionsChangedListener =
        object : SubscriptionManager.OnSubscriptionsChangedListener() {
            override fun onSubscriptionsChanged() {
                bgExecutor.execute { fiveGUtils.restoreNrState() }
            }
        }

    override fun start() {
        subscriptionManager.addOnSubscriptionsChangedListener(bgExecutor, subscriptionsChangedListener)
    }
}
