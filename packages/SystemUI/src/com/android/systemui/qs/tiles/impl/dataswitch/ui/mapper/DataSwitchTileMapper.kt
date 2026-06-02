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

package com.android.systemui.qs.tiles.impl.dataswitch.ui.mapper

import android.content.res.Resources
import android.widget.Button
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.qs.tiles.base.shared.model.QSTileConfig
import com.android.systemui.qs.tiles.base.shared.model.QSTileState
import com.android.systemui.qs.tiles.base.ui.model.QSTileDataToStateMapper
import com.android.systemui.qs.tiles.impl.dataswitch.domain.model.DataSwitchTileModel
import com.android.systemui.res.R
import com.android.systemui.shade.ShadeDisplayAware
import javax.inject.Inject

private val simIcons =
    listOf(
        R.drawable.ic_qs_data_switch_sim_1,
        R.drawable.ic_qs_data_switch_sim_2,
    )

class DataSwitchTileMapper
@Inject
constructor(
    @ShadeDisplayAware private val resources: Resources,
    private val theme: Resources.Theme,
) : QSTileDataToStateMapper<DataSwitchTileModel> {
    override fun map(config: QSTileConfig, data: DataSwitchTileModel): QSTileState =
        QSTileState.build(resources, theme, config.uiConfig) {
            label = resources.getString(config.uiConfig.labelRes)
            val iconRes = data.iconRes
            icon = Icon.Loaded(resources.getDrawable(iconRes, theme), null, iconRes)
            secondaryLabel = data.secondaryLabel
            stateDescription = secondaryLabel
            contentDescription = listOfNotNull(label, secondaryLabel).joinToString(", ")
            expandedAccessibilityClass = Button::class
            activationState =
                if (data.isEnabled) {
                    QSTileState.ActivationState.ACTIVE
                } else {
                    QSTileState.ActivationState.UNAVAILABLE
                }
            supportedActions =
                if (data.isEnabled) {
                    setOf(QSTileState.UserAction.CLICK, QSTileState.UserAction.LONG_CLICK)
                } else {
                    setOf(QSTileState.UserAction.LONG_CLICK)
                }
        }

    private val DataSwitchTileModel.iconRes: Int
        get() {
            if (subscriptions.isEmpty()) return R.drawable.ic_qs_data_switch_unavailable
            return activeSubscription?.slotIndex?.let(simIcons::getOrNull)
                ?: R.drawable.ic_qs_data_switch
        }

    private val DataSwitchTileModel.secondaryLabel: CharSequence
        get() =
            if (isEnabled) {
                activeSubscription?.displayName?.takeIf(String::isNotBlank)
                    ?: resources.getString(R.string.quick_settings_cellular_detail_title)
            } else {
                resources.getString(R.string.tile_unavailable)
            }
}
