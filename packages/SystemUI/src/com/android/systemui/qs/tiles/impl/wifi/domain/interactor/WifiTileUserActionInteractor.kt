/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.systemui.qs.tiles.impl.wifi.domain.interactor

import com.android.systemui.animation.Expandable
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.qs.tiles.base.domain.interactor.QSTileUserActionInteractor
import com.android.systemui.qs.tiles.base.domain.model.QSTileInput
import com.android.systemui.qs.tiles.base.shared.model.QSTileUserAction
import com.android.systemui.qs.tiles.dialog.InternetDialogManager
import com.android.systemui.qs.tiles.impl.wifi.domain.model.WifiTileModel
import com.android.systemui.statusbar.connectivity.AccessPointController
import com.android.systemui.statusbar.pipeline.wifi.data.repository.WifiRepository
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.withContext

/** Handles wifi tile clicks. */
class WifiTileUserActionInteractor
@Inject
constructor(
    @Main private val mainContext: CoroutineContext,
    private val internetDialogManager: InternetDialogManager,
    private val accessPointController: AccessPointController,
    private val wifiRepository: WifiRepository,
) : QSTileUserActionInteractor<WifiTileModel> {

    override suspend fun handleInput(input: QSTileInput<WifiTileModel>): Unit =
        with(input) {
            when (action) {
                is QSTileUserAction.Click -> handleClick()
                is QSTileUserAction.LongClick -> handleLongClick(action.expandable)
                is QSTileUserAction.ToggleClick -> {}
            }
        }

    fun handleClick() {
        if (!wifiRepository.isWifiEnabled.value) {
            wifiRepository.enableWifi()
        } else {
            wifiRepository.disableWifi()
        }
    }

    suspend fun handleLongClick(expandable: Expandable?) {
        withContext(mainContext) {
            internetDialogManager.create(
                aboveStatusBar = true,
                false,
                accessPointController.canConfigWifi(),
                expandable,
            )
        }
    }
}
