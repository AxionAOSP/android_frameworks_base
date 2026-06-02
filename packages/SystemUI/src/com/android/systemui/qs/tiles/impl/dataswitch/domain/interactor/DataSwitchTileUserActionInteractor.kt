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

import android.content.Intent
import android.provider.Settings
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import com.android.systemui.animation.Expandable
import com.android.systemui.qs.tiles.base.domain.actions.QSTileIntentUserInputHandler
import com.android.systemui.qs.tiles.base.domain.interactor.QSTileUserActionInteractor
import com.android.systemui.qs.tiles.base.domain.model.QSTileInput
import com.android.systemui.qs.tiles.base.shared.model.QSTileUserAction
import com.android.systemui.qs.tiles.impl.dataswitch.domain.model.DataSwitchTileModel
import javax.inject.Inject

class DataSwitchTileUserActionInteractor
@Inject
constructor(
    private val subscriptionManager: SubscriptionManager,
    private val telephonyManager: TelephonyManager,
    private val qsTileIntentUserInputHandler: QSTileIntentUserInputHandler,
) : QSTileUserActionInteractor<DataSwitchTileModel> {
    val longClickIntent: Intent
        get() {
            val intent = Intent(Settings.ACTION_NETWORK_OPERATOR_SETTINGS)
            val subId = SubscriptionManager.getDefaultDataSubscriptionId()
            if (SubscriptionManager.isValidSubscriptionId(subId)) {
                intent.putExtra(Settings.EXTRA_SUB_ID, subId)
            }
            return intent
        }

    override suspend fun handleInput(input: QSTileInput<DataSwitchTileModel>) {
        when (input.action) {
            is QSTileUserAction.Click -> handleClick(input.data)
            is QSTileUserAction.ToggleClick -> handleClick(input.data)
            is QSTileUserAction.LongClick -> handleLongClick(input.action.expandable)
        }
    }

    fun handleClick(model: DataSwitchTileModel) {
        val nextSubscriptionId = model.nextSubscription()?.subscriptionId ?: return
        if (!SubscriptionManager.isUsableSubscriptionId(nextSubscriptionId)) return
        switchTo(nextSubscriptionId)
    }

    fun handleLongClick(expandable: Expandable?) {
        qsTileIntentUserInputHandler.handle(expandable, longClickIntent)
    }

    private fun switchTo(subId: Int) {
        subscriptionManager.setDefaultDataSubId(subId)
        enableData(subId)
    }

    private fun enableData(subId: Int) {
        telephonyManager
            .createForSubscriptionId(subId)
            .setDataEnabledForReason(TelephonyManager.DATA_ENABLED_REASON_USER, true)
    }
}
