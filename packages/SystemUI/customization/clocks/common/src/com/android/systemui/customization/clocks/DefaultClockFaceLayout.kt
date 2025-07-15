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

package com.android.systemui.customization.clocks

import android.util.DisplayMetrics
import android.view.View
import androidx.constraintlayout.widget.ConstraintSet
import androidx.constraintlayout.widget.ConstraintSet.BOTTOM
import androidx.constraintlayout.widget.ConstraintSet.END
import androidx.constraintlayout.widget.ConstraintSet.PARENT_ID
import androidx.constraintlayout.widget.ConstraintSet.START
import androidx.constraintlayout.widget.ConstraintSet.TOP
import androidx.constraintlayout.widget.ConstraintSet.WRAP_CONTENT
import com.android.systemui.customization.clocks.ContextUtil.getSafeStatusBarHeight
import com.android.systemui.customization.clocks.R as clocksR
import com.android.systemui.plugins.clocks.AodClockBurnInModel
import com.android.systemui.plugins.clocks.ClockFaceLayout
import com.android.systemui.plugins.clocks.ClockPreviewConfig
import com.android.systemui.plugins.clocks.ClockViewIds

/** A ClockFaceLayout that applies the default lockscreen layout to a single view */
open class DefaultClockFaceLayout(val view: View) : ClockFaceLayout {
    override val views = listOf(view)

    override fun applyConstraints(constraints: ConstraintSet): ConstraintSet {
        if (views.size != 1) {
            throw IllegalArgumentException(
                "Should have only one container view when using DefaultClockFaceLayout"
            )
        }
        return constraints
    }

    override fun applyExternalDisplayPresentationConstraints(
        constraints: ConstraintSet
    ): ConstraintSet {
        return constraints.apply {
            constrainWidth(ClockViewIds.LOCKSCREEN_CLOCK_VIEW_LARGE, WRAP_CONTENT)
            constrainHeight(ClockViewIds.LOCKSCREEN_CLOCK_VIEW_LARGE, WRAP_CONTENT)

            connect(ClockViewIds.LOCKSCREEN_CLOCK_VIEW_LARGE, TOP, PARENT_ID, TOP)
            connect(ClockViewIds.LOCKSCREEN_CLOCK_VIEW_LARGE, BOTTOM, PARENT_ID, BOTTOM)
            connect(ClockViewIds.LOCKSCREEN_CLOCK_VIEW_LARGE, START, PARENT_ID, START)
            connect(ClockViewIds.LOCKSCREEN_CLOCK_VIEW_LARGE, END, PARENT_ID, END)
        }
    }

    override fun applyPreviewConstraints(
        clockPreviewConfig: ClockPreviewConfig,
        constraints: ConstraintSet,
    ): ConstraintSet {
        val res = view.context.resources
        return constraints.apply {
            constrainWidth(ClockViewIds.LOCKSCREEN_CLOCK_VIEW_LARGE, WRAP_CONTENT)
            constrainHeight(ClockViewIds.LOCKSCREEN_CLOCK_VIEW_LARGE, WRAP_CONTENT)
            constrainMaxHeight(ClockViewIds.LOCKSCREEN_CLOCK_VIEW_LARGE, 0)

            val largeClockTopMargin =
                if (com.android.systemui.shared.Flags.clockReactiveSmartspaceLayout()) {
                    view.context.getSafeStatusBarHeight() / 2 +
                        res.getDimensionPixelSize(clocksR.dimen.keyguard_smartspace_top_offset) +
                        res.getDimensionPixelSize(clocksR.dimen.enhanced_smartspace_height)
                } else {
                    view.context.getSafeStatusBarHeight() +
                        res.getDimensionPixelSize(clocksR.dimen.small_clock_padding_top) +
                        res.getDimensionPixelSize(clocksR.dimen.keyguard_smartspace_top_offset) +
                        res.getDimensionPixelSize(clocksR.dimen.date_weather_view_height) +
                        res.getDimensionPixelSize(clocksR.dimen.enhanced_smartspace_height)
                }

            connect(
                ClockViewIds.LOCKSCREEN_CLOCK_VIEW_LARGE,
                TOP,
                PARENT_ID,
                TOP,
                largeClockTopMargin,
            )
            connect(ClockViewIds.LOCKSCREEN_CLOCK_VIEW_LARGE, START, PARENT_ID, START)
            connect(ClockViewIds.LOCKSCREEN_CLOCK_VIEW_LARGE, END, PARENT_ID, END)

            clockPreviewConfig.udfpsTop?.let { udfpsTop ->
                connect(
                    ClockViewIds.LOCKSCREEN_CLOCK_VIEW_LARGE,
                    BOTTOM,
                    PARENT_ID,
                    BOTTOM,
                    (res.displayMetrics.heightPixels - udfpsTop).toInt(),
                )
            }
                ?: run {
                    // Copied calculation codes from applyConstraints in DefaultDeviceEntrySection
                    clockPreviewConfig.lockViewId?.let { lockViewId ->
                        connect(ClockViewIds.LOCKSCREEN_CLOCK_VIEW_LARGE, BOTTOM, lockViewId, TOP)
                    }
                        ?: run {
                            val bottomPaddingPx =
                                res.getDimensionPixelSize(clocksR.dimen.lock_icon_margin_bottom)
                            val defaultDensity =
                                DisplayMetrics.DENSITY_DEVICE_STABLE.toFloat() /
                                    DisplayMetrics.DENSITY_DEFAULT.toFloat()
                            val lockIconRadiusPx = (defaultDensity * 36).toInt()
                            val clockBottomMargin = bottomPaddingPx + 2 * lockIconRadiusPx
                            connect(
                                ClockViewIds.LOCKSCREEN_CLOCK_VIEW_LARGE,
                                BOTTOM,
                                PARENT_ID,
                                BOTTOM,
                                clockBottomMargin,
                            )
                        }
                }

            constrainWidth(ClockViewIds.LOCKSCREEN_CLOCK_VIEW_SMALL, WRAP_CONTENT)
            constrainHeight(
                ClockViewIds.LOCKSCREEN_CLOCK_VIEW_SMALL,
                res.getDimensionPixelSize(clocksR.dimen.small_clock_height),
            )
            connect(
                ClockViewIds.LOCKSCREEN_CLOCK_VIEW_SMALL,
                START,
                PARENT_ID,
                START,
                0,
            )

            val smallClockTopMargin = clockPreviewConfig.getSmallClockTopPadding()
            connect(
                ClockViewIds.LOCKSCREEN_CLOCK_VIEW_SMALL,
                TOP,
                PARENT_ID,
                TOP,
                smallClockTopMargin,
            )
        }
    }

    override fun applyAodBurnIn(aodBurnInModel: AodClockBurnInModel) {
        // Default clock doesn't need detailed control of view
    }
}
