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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.Vibrator
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
import com.android.systemui.qs.tileimpl.QSTileImpl
import com.android.systemui.res.R
import javax.inject.Inject

class RingerModeTileImpl
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

    private val hasVibrator: Boolean =
        (mContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)
            ?.hasVibrator() == true

    private val ringerModeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            refreshState()
        }
    }

    override fun getTileLabel(): CharSequence =
        mContext.getString(R.string.volume_ringer_mode)

    override fun newTileState(): QSTile.BooleanState = QSTile.BooleanState()

    override fun handleSetListening(listening: Boolean) {
        super.handleSetListening(listening)
        if (listening) {
            mContext.registerReceiver(
                ringerModeReceiver,
                IntentFilter(AudioManager.INTERNAL_RINGER_MODE_CHANGED_ACTION),
            )
        } else {
            mContext.unregisterReceiver(ringerModeReceiver)
        }
    }

    override fun handleClick(expandable: Expandable?) {
        val nextMode = when (audioManager.ringerModeInternal) {
            AudioManager.RINGER_MODE_NORMAL ->
                if (hasVibrator) AudioManager.RINGER_MODE_VIBRATE
                else AudioManager.RINGER_MODE_SILENT
            AudioManager.RINGER_MODE_VIBRATE -> AudioManager.RINGER_MODE_SILENT
            else -> AudioManager.RINGER_MODE_NORMAL
        }
        audioManager.ringerModeInternal = nextMode
    }

    override fun handleUpdateState(state: QSTile.BooleanState, arg: Any?) {
        val mode = audioManager.ringerModeInternal
        state.label = when (mode) {
            AudioManager.RINGER_MODE_VIBRATE ->
                mContext.getString(R.string.volume_ringer_status_vibrate)
            AudioManager.RINGER_MODE_SILENT ->
                mContext.getString(R.string.volume_ringer_status_silent)
            else -> mContext.getString(R.string.volume_ringer_status_normal)
        }
        state.icon = when (mode) {
            AudioManager.RINGER_MODE_VIBRATE ->
                ResourceIcon.get(R.drawable.ic_volume_ringer_vibrate)
            AudioManager.RINGER_MODE_SILENT ->
                ResourceIcon.get(R.drawable.ic_volume_ringer_mute)
            else -> ResourceIcon.get(R.drawable.ic_volume_ringer)
        }
        state.state = if (mode == AudioManager.RINGER_MODE_NORMAL)
            Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        state.contentDescription = state.label
    }

    override fun getLongClickIntent(): Intent = SOUND_SETTINGS

    companion object {
        const val TILE_SPEC = "sound"
        private val SOUND_SETTINGS = Intent(Settings.ACTION_SOUND_SETTINGS)
    }
}
