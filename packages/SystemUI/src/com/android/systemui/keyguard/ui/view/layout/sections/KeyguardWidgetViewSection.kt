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
 *
 */

package com.android.systemui.keyguard.ui.view.layout.sections

import android.content.Context
import android.os.Handler
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.constraintlayout.widget.Barrier
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import com.android.settingslib.net.DataUsageController
import com.android.systemui.bluetooth.qsdialog.BluetoothDetailsContentViewModel
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.customization.R as custR
import com.android.systemui.keyguard.shared.model.KeyguardSection
import com.android.systemui.qs.tiles.dialog.InternetDialogManager
import com.android.systemui.statusbar.connectivity.*
import com.android.systemui.statusbar.policy.*
import com.android.systemui.res.R
import dagger.Lazy
import javax.inject.Inject

import com.android.systemui.lockscreen.LockScreenWidgetsController

class KeyguardWidgetViewSection
@Inject
constructor(
    private val context: Context,
    private val networkController: NetworkController,
    private val configurationController: ConfigurationController,
    private val bluetoothController: BluetoothController,
    private val hotspotController: HotspotController,
    private val accessPointController: AccessPointController,
    private val internetDialogManager: InternetDialogManager,
    private val detailsContentViewModel: Lazy<BluetoothDetailsContentViewModel>,
    private val flashlightController: FlashlightController,
    private val layoutInflater: LayoutInflater,
    @Main val handler: Handler,
) : KeyguardSection() {
    private lateinit var widgetsView: ViewGroup
    private lateinit var controller: LockScreenWidgetsController

    override fun addViews(constraintLayout: ConstraintLayout) {
        widgetsView =
            layoutInflater.inflate(R.layout.keyguard_widgets_area, null, false) as ViewGroup
        constraintLayout.addView(widgetsView)
    }

    override fun bindData(constraintLayout: ConstraintLayout) {
        controller = 
            LockScreenWidgetsController(
                widgetsView,
                context,
                networkController,
                configurationController,
                bluetoothController,
                hotspotController,
                accessPointController,
                internetDialogManager,
                detailsContentViewModel,
                flashlightController,
                handler)
    }

    override fun applyConstraints(constraintSet: ConstraintSet) {
        constraintSet.apply {
            connect(
                R.id.keyguard_widgets_area,
                ConstraintSet.START,
                ConstraintSet.PARENT_ID,
                ConstraintSet.START,
                0,
            )
            connect(
                R.id.keyguard_widgets_area,
                ConstraintSet.END,
                ConstraintSet.PARENT_ID,
                ConstraintSet.END
            )
            constrainHeight(R.id.keyguard_widgets_area, ConstraintSet.WRAP_CONTENT)

            connect(
                R.id.keyguard_widgets_area,
                ConstraintSet.TOP,
                R.id.keyguard_slice_view,
                ConstraintSet.BOTTOM
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
