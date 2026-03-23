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

import android.content.Intent
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.quicksettings.Tile
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
import com.android.systemui.qs.logging.QSLogger
import com.android.systemui.qs.pipeline.domain.interactor.PanelInteractor
import com.android.systemui.qs.tileimpl.QSTileImpl
import com.android.systemui.res.R
import javax.inject.Inject

class VolumeSliderTileImpl
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
    private val panelInteractor: PanelInteractor,
    private val audioManager: AudioManager,
) :
    QSTileImpl<QSTile.BooleanState>(
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

    override fun getTileLabel(): CharSequence =
        mContext.getString(R.string.stream_music)

    override fun newTileState(): QSTile.BooleanState = QSTile.BooleanState()

    override fun handleClick(expandable: Expandable?) {
        panelInteractor.collapsePanels()
        audioManager.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            AudioManager.ADJUST_SAME,
            AudioManager.FLAG_SHOW_UI,
        )
    }

    override fun handleUpdateState(state: QSTile.BooleanState, arg: Any?) {
        state.label = mContext.getString(R.string.stream_music)
        state.icon = ResourceIcon.get(R.drawable.ic_volume_media)
        state.state = Tile.STATE_ACTIVE
        state.contentDescription = state.label
    }

    override fun getLongClickIntent(): Intent = SOUND_SETTINGS

    companion object {
        const val TILE_SPEC = "volume"
        private val SOUND_SETTINGS = Intent(Settings.ACTION_SOUND_SETTINGS)
    }
}
