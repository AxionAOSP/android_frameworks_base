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
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.view.WindowManager
import com.android.internal.logging.MetricsLogger
import com.android.internal.logging.nano.MetricsProto.MetricsEvent
import com.android.internal.util.ScreenshotHelper
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.plugins.qs.QSTile.BooleanState
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.qs.QSHost
import com.android.systemui.qs.QsEventLogger
import com.android.systemui.qs.logging.QSLogger
import com.android.systemui.qs.pipeline.domain.interactor.PanelInteractor
import com.android.systemui.qs.tileimpl.QSTileImpl
import com.android.systemui.res.R
import com.android.systemui.animation.Expandable
import javax.inject.Inject

class ScreenshotTile @Inject constructor(
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
    @Main private val handler: Handler,
) : QSTileImpl<BooleanState>(
    host, uiEventLogger, backgroundLooper, mainHandler, falsingManager,
    metricsLogger, statusBarStateController, activityStarter, qsLogger,
) {
    companion object {
        const val TILE_SPEC = "screenshot"
        private const val SCREENSHOT_DELAY_MS = 500L
    }

    private val screenshotHelper = ScreenshotHelper(mContext)

    override fun newTileState(): BooleanState = BooleanState()

    override fun handleClick(expandable: Expandable?) {
        handler.postDelayed({
            screenshotHelper.takeScreenshot(
                WindowManager.TAKE_SCREENSHOT_FULLSCREEN,
                WindowManager.ScreenshotSource.SCREENSHOT_OTHER,
                handler,
                null,
            )
        }, SCREENSHOT_DELAY_MS)
        panelInteractor.collapsePanels()
    }

    override fun handleLongClick(expandable: Expandable?) {
        handler.postDelayed({
            screenshotHelper.takeScreenshot(
                WindowManager.TAKE_SCREENSHOT_SELECTED_REGION,
                WindowManager.ScreenshotSource.SCREENSHOT_OTHER,
                handler,
                null,
            )
        }, SCREENSHOT_DELAY_MS)
        panelInteractor.collapsePanels()
    }

    override fun getLongClickIntent(): Intent? = null

    override fun getTileLabel(): CharSequence =
        mContext.getString(R.string.quick_settings_screenshot_label)

    override fun handleUpdateState(state: BooleanState, arg: Any?) {
        state.label = mContext.getString(R.string.quick_settings_screenshot_label)
        state.contentDescription = state.label
        state.state = Tile.STATE_ACTIVE
        state.icon = ResourceIcon.get(R.drawable.qs_screenshot_icon)
    }

    override fun isAvailable(): Boolean = true

    override fun getMetricsCategory(): Int = MetricsEvent.QS_PANEL
}
