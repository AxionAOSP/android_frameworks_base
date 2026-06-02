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

package com.android.systemui.qs.tiles.impl.dataswitch.domain.interactor

import android.os.UserHandle
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.qs.tiles.base.domain.interactor.QSTileDataInteractor
import com.android.systemui.qs.tiles.base.domain.model.DataUpdateTrigger
import com.android.systemui.qs.tiles.impl.dataswitch.domain.model.DataSwitchSubscriptionModel
import com.android.systemui.qs.tiles.impl.dataswitch.domain.model.DataSwitchTileModel
import com.android.systemui.statusbar.pipeline.mobile.data.repository.MobileConnectionsRepository
import com.android.systemui.utils.coroutines.flow.conflatedCallbackFlow
import java.util.concurrent.Executor
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flowOf

class DataSwitchTileDataInteractor
@Inject
constructor(
    private val subscriptionManager: SubscriptionManager,
    private val telephonyManager: TelephonyManager,
    private val mobileConnectionsRepository: MobileConnectionsRepository,
    @Background private val bgExecutor: Executor,
    @Background private val bgContext: CoroutineContext,
) : QSTileDataInteractor<DataSwitchTileModel> {
    private val subscriptions =
        conflatedCallbackFlow {
                val listener =
                    object : SubscriptionManager.OnSubscriptionsChangedListener() {
                        override fun onSubscriptionsChanged() {
                            trySend(loadSubscriptions())
                        }
                    }
                subscriptionManager.addOnSubscriptionsChangedListener(bgExecutor, listener)
                trySend(loadSubscriptions())
                awaitClose { subscriptionManager.removeOnSubscriptionsChangedListener(listener) }
            }
            .flowOn(bgContext)

    override fun tileData(
        user: UserHandle,
        triggers: Flow<DataUpdateTrigger>,
    ): Flow<DataSwitchTileModel> = tileData()

    fun tileData(): Flow<DataSwitchTileModel> =
        combine(
                subscriptions,
                mobileConnectionsRepository.defaultDataSubId,
            ) { subscriptions, defaultDataSubId ->
                DataSwitchTileModel(subscriptions, defaultDataSubId)
            }
            .flowOn(bgContext)

    override fun availability(user: UserHandle): Flow<Boolean> = flowOf(isAvailable())

    fun isAvailable(): Boolean = telephonyManager.supportedModemCount >= 2

    private fun loadSubscriptions(): List<DataSwitchSubscriptionModel> =
        subscriptionManager
            .getActiveSubscriptionInfoList()
            .orEmpty()
            .asSequence()
            .filter { SubscriptionManager.isUsableSubscriptionId(it.subscriptionId) }
            .filter { it.simSlotIndex != SubscriptionManager.INVALID_SIM_SLOT_INDEX }
            .filterNot { it.isOpportunistic }
            .filterNot {
                it.isEmbedded &&
                    (it.profileClass == SubscriptionManager.PROFILE_CLASS_PROVISIONING ||
                        it.isOnlyNonTerrestrialNetwork)
            }
            .sortedWith(
                compareBy<SubscriptionInfo> { it.simSlotIndex }.thenBy { it.subscriptionId }
            )
            .map {
                DataSwitchSubscriptionModel(
                    subscriptionId = it.subscriptionId,
                    slotIndex = it.simSlotIndex,
                    displayName = it.displayName.toString(),
                )
            }
            .toList()
}
