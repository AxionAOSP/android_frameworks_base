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

package com.android.systemui.qs.tiles

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.coroutineScope
import androidx.lifecycle.repeatOnLifecycle
import com.android.app.tracing.coroutines.launchTraced as launch
import com.android.internal.logging.MetricsLogger
import com.android.systemui.animation.Expandable
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.plugins.qs.QSTile
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.qs.QSHost
import com.android.systemui.qs.QsEventLogger
import com.android.systemui.qs.asQSTileIcon
import com.android.systemui.qs.logging.QSLogger
import com.android.systemui.qs.tileimpl.QSTileImpl
import com.android.systemui.qs.tiles.base.shared.model.QSTileConfigProvider
import com.android.systemui.qs.tiles.base.shared.model.QSTileState
import com.android.systemui.qs.tiles.impl.dataswitch.domain.interactor.DataSwitchTileDataInteractor
import com.android.systemui.qs.tiles.impl.dataswitch.domain.interactor.DataSwitchTileUserActionInteractor
import com.android.systemui.qs.tiles.impl.dataswitch.domain.model.DataSwitchTileModel
import com.android.systemui.qs.tiles.impl.dataswitch.ui.mapper.DataSwitchTileMapper
import javax.inject.Inject

class DataSwitchTile
@Inject
constructor(
    host: QSHost,
    uiEventLogger: QsEventLogger,
    @Background backgroundLooper: Looper,
    @Main mainHandler: Handler,
    falsingManager: FalsingManager,
    metricsLogger: MetricsLogger,
    statusBarStateController: StatusBarStateController,
    activityStarter: ActivityStarter,
    qsLogger: QSLogger,
    qsTileConfigProvider: QSTileConfigProvider,
    private val dataInteractor: DataSwitchTileDataInteractor,
    private val tileMapper: DataSwitchTileMapper,
    private val userActionInteractor: DataSwitchTileUserActionInteractor,
) :
    QSTileImpl<QSTile.State>(
        host,
        uiEventLogger,
        backgroundLooper,
        mainHandler,
        falsingManager,
        metricsLogger,
        statusBarStateController,
        activityStarter,
        qsLogger,
    ) {
    private val config = qsTileConfigProvider.getConfig(TILE_SPEC)
    private var model: DataSwitchTileModel? = null

    init {
        lifecycle.coroutineScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                dataInteractor.tileData().collect { refreshState(it) }
            }
        }
    }

    override fun getTileLabel(): CharSequence = mContext.getString(config.uiConfig.labelRes)

    override fun newTileState(): QSTile.State =
        QSTile.State().apply { state = Tile.STATE_UNAVAILABLE }

    override fun handleClick(expandable: Expandable?) {
        val currentModel = model ?: return
        userActionInteractor.handleClick(currentModel)
    }

    override fun getLongClickIntent(): Intent = userActionInteractor.longClickIntent

    override fun handleUpdateState(state: QSTile.State, arg: Any?) {
        val data = arg as? DataSwitchTileModel ?: model ?: return
        model = data
        val tileState = tileMapper.map(config, data)
        state.apply {
            this.state = tileState.activationState.legacyState
            icon = tileState.icon?.asQSTileIcon()
            label = tileState.label
            secondaryLabel = tileState.secondaryLabel
            contentDescription = tileState.contentDescription
            stateDescription = tileState.stateDescription
            expandedAccessibilityClassName = tileState.expandedAccessibilityClassName
            handlesLongClick =
                tileState.supportedActions.contains(QSTileState.UserAction.LONG_CLICK)
            handlesSecondaryClick =
                tileState.supportedActions.contains(QSTileState.UserAction.TOGGLE_CLICK)
        }
    }

    override fun isAvailable(): Boolean = dataInteractor.isAvailable()

    companion object {
        const val TILE_SPEC = "dataswitch"
    }
}
