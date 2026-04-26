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

package com.android.systemui.routines.ui.qs

import android.content.ComponentName
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import com.android.internal.jank.InteractionJankMonitor
import com.android.internal.logging.MetricsLogger
import com.android.internal.logging.nano.MetricsProto.MetricsEvent
import com.android.systemui.animation.DialogCuj
import com.android.systemui.animation.DialogTransitionAnimator
import com.android.systemui.animation.Expandable
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.plugins.qs.QSTile.BooleanState
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.qs.QSHost
import com.android.systemui.qs.QsEventLogger
import com.android.systemui.qs.logging.QSLogger
import com.android.systemui.qs.tileimpl.QSTileImpl
import com.android.systemui.res.R
import com.android.systemui.routines.data.RoutinesRepository
import com.android.systemui.statusbar.phone.SystemUIDialog
import com.android.systemui.statusbar.policy.KeyguardStateController
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Provider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

class RoutinesTile @Inject constructor(
    host: QSHost,
    uiEventLogger: QsEventLogger,
    @Background backgroundLooper: Looper,
    @Main mainHandler: Handler,
    falsingManager: FalsingManager,
    metricsLogger: MetricsLogger,
    statusBarStateController: StatusBarStateController,
    activityStarter: ActivityStarter,
    qsLogger: QSLogger,
    private val repository: RoutinesRepository,
    private val keyguardStateController: KeyguardStateController,
    private val dialogTransitionAnimator: DialogTransitionAnimator,
    private val dialogDelegateProvider: Provider<RoutinesTileDialogDelegate>,
    @Main private val mainExecutor: Executor,
    @Application private val appScope: CoroutineScope,
) : QSTileImpl<BooleanState>(
    host, uiEventLogger, backgroundLooper, mainHandler, falsingManager,
    metricsLogger, statusBarStateController, activityStarter, qsLogger,
) {

    companion object {
        const val TILE_SPEC = "routines"
        private const val INTERACTION_JANK_TAG = "routines"
        private const val AXION_PARTS_PACKAGE = "com.android.axion.axionparts"
        private const val AXION_PARTS_DASHBOARD = "com.android.axion.axionparts.DashboardActivity"
    }

    private var observerJob: Job? = null

    override fun newTileState(): BooleanState =
        BooleanState().apply { handlesLongClick = true }

    override fun handleSetListening(listening: Boolean) {
        super.handleSetListening(listening)
        if (listening) {
            if (observerJob == null) {
                observerJob = appScope.launch {
                    repository.routines.onEach { refreshState() }.collect()
                }
            }
        } else {
            observerJob?.cancel()
            observerJob = null
        }
    }

    override fun handleClick(expandable: Expandable?) {
        val animateFromExpandable = expandable != null && !keyguardStateController.isShowing
        val runnable = Runnable {
            val dialog: SystemUIDialog = dialogDelegateProvider.get().createDialog()
            if (animateFromExpandable) {
                val controller = expandable?.dialogTransitionController(
                    DialogCuj(
                        InteractionJankMonitor.CUJ_SHADE_DIALOG_OPEN,
                        INTERACTION_JANK_TAG,
                    )
                )
                controller?.let { dialogTransitionAnimator.show(dialog, it) } ?: dialog.show()
            } else {
                dialog.show()
            }
        }
        mainExecutor.execute {
            mActivityStarter.executeRunnableDismissingKeyguard(
                runnable, null, true, true, false,
            )
        }
    }

    override fun getLongClickIntent(): Intent =
        Intent().apply {
            component = ComponentName(AXION_PARTS_PACKAGE, AXION_PARTS_DASHBOARD)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

    override fun getTileLabel(): CharSequence =
        mContext.getString(R.string.quick_settings_routines_label)

    override fun handleUpdateState(state: BooleanState, arg: Any?) {
        val routines = repository.routines.value
        val enabledCount = routines.count { it.enabled }
        state.label = mContext.getString(R.string.quick_settings_routines_label)
        state.secondaryLabel = mContext.resources.getQuantityString(
            R.plurals.quick_settings_routines_count, enabledCount, enabledCount,
        )
        state.contentDescription = "${state.label}, ${state.secondaryLabel}"
        state.icon = ResourceIcon.get(R.drawable.qs_routines_icon)
        state.state = if (enabledCount > 0) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
    }

    override fun isAvailable(): Boolean = true

    override fun getMetricsCategory(): Int = MetricsEvent.QS_PANEL
}
