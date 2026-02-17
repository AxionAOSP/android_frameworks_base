/*
 * Copyright (C) 2025 AxionOS Project
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

package com.android.systemui.keyguard.ui.view.layout.sections

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.constraintlayout.widget.Barrier
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import com.android.systemui.keyguard.shared.model.KeyguardSection
import com.android.systemui.keyguard.ui.viewmodel.KeyguardClockViewModel
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.lockscreen.KeyguardWidgetHostController
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.keyguard.ui.clocks.ClockViewIds
import com.android.systemui.res.R
import com.android.systemui.statusbar.policy.ConfigurationController
import javax.inject.Inject

class KeyguardWidgetViewSection
@Inject
constructor(
    private val context: Context,
    private val layoutInflater: LayoutInflater,
    private val activityStarter: ActivityStarter,
    private val configurationController: ConfigurationController,
    private val broadcastDispatcher: BroadcastDispatcher,
    private val keyguardClockViewModel: KeyguardClockViewModel,
) : KeyguardSection() {
    private lateinit var widgetsView: ViewGroup
    private lateinit var controller: KeyguardWidgetHostController

    private val isSplitShade: Boolean
        get() = context.resources.getBoolean(R.bool.config_use_split_notification_shade)

    override fun addViews(constraintLayout: ConstraintLayout) {
        widgetsView =
            layoutInflater.inflate(R.layout.keyguard_widgets_area, null, false) as ViewGroup
        constraintLayout.addView(widgetsView)
    }

    override fun bindData(constraintLayout: ConstraintLayout) {
        controller = KeyguardWidgetHostController(context, widgetsView, activityStarter, configurationController, broadcastDispatcher)
        controller.init()
    }

    override fun applyConstraints(constraintSet: ConstraintSet) {
        constraintSet.apply {
            connect(
                R.id.keyguard_widgets_area,
                ConstraintSet.TOP,
                ClockViewIds.LOCKSCREEN_CLOCK_VIEW_SMALL,
                ConstraintSet.BOTTOM,
            )
            connect(
                R.id.keyguard_widgets_area,
                ConstraintSet.START,
                ConstraintSet.PARENT_ID,
                ConstraintSet.START,
            )
            connect(
                R.id.keyguard_widgets_area,
                ConstraintSet.END,
                ConstraintSet.PARENT_ID,
                ConstraintSet.END,
            )
            constrainWidth(R.id.keyguard_widgets_area, ConstraintSet.MATCH_CONSTRAINT)
            constrainMaxWidth(
                R.id.keyguard_widgets_area,
                context.resources.getDimensionPixelSize(R.dimen.shade_panel_width),
            )
            val centered = !isSplitShade || keyguardClockViewModel.clockShouldBeCentered.value
            setHorizontalBias(R.id.keyguard_widgets_area, if (centered) 0.5f else 0f)
            if (::controller.isInitialized) {
                controller.clockCentered = centered
            }
            constrainHeight(R.id.keyguard_widgets_area, ConstraintSet.WRAP_CONTENT)
            val density = context.resources.displayMetrics.density
            constrainMaxHeight(
                R.id.keyguard_widgets_area,
                (KeyguardWidgetHostController.MAX_CONTAINER_HEIGHT_DP * density).toInt(),
            )

            createBarrier(
                R.id.smart_space_barrier_bottom,
                Barrier.BOTTOM,
                0,
                *intArrayOf(R.id.keyguard_widgets_area)
            )
        }
    }

    override fun removeViews(constraintLayout: ConstraintLayout) {
        controller.dispose()
    }

}

