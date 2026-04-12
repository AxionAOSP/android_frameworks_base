/*
 * Copyright (C) 2023 The Android Open Source Project
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
import android.view.View
import androidx.constraintlayout.widget.Barrier
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.constraintlayout.widget.ConstraintSet.BOTTOM
import androidx.constraintlayout.widget.ConstraintSet.END
import androidx.constraintlayout.widget.ConstraintSet.GONE
import androidx.constraintlayout.widget.ConstraintSet.PARENT_ID
import androidx.constraintlayout.widget.ConstraintSet.START
import androidx.constraintlayout.widget.ConstraintSet.TOP
import androidx.constraintlayout.widget.ConstraintSet.VISIBLE
import androidx.constraintlayout.widget.ConstraintSet.MATCH_CONSTRAINT
import androidx.constraintlayout.widget.ConstraintSet.WRAP_CONTENT
import com.android.systemui.customization.R as custR
import com.android.systemui.customization.clocks.R as clocksR
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.domain.interactor.KeyguardBlueprintInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardClockInteractor
import com.android.systemui.keyguard.shared.model.KeyguardSection
import com.android.systemui.keyguard.ui.binder.KeyguardClockViewBinder
import com.android.systemui.keyguard.ui.viewmodel.AodBurnInViewModel
import com.android.systemui.keyguard.ui.viewmodel.KeyguardClockViewModel
import com.android.systemui.keyguard.ui.viewmodel.KeyguardRootViewModel
import com.android.systemui.keyguard.ui.viewmodel.KeyguardSmartspaceViewModel
import com.android.systemui.plugins.keyguard.ui.clocks.ClockController
import com.android.systemui.plugins.keyguard.ui.clocks.ClockFaceLayout
import com.android.systemui.plugins.keyguard.ui.clocks.ClockViewIds
import com.android.systemui.res.R
import com.android.systemui.shade.LargeScreenHeaderHelper
import com.android.systemui.shade.ShadeDisplayAware
import com.android.systemui.shared.R as sharedR
import com.android.systemui.shared.clocks.ClockSettingsRepository
import com.android.systemui.shared.clocks.useAxClocks
import com.android.systemui.shared.clocks.view.AxClockView
import com.android.systemui.shared.clocks.view.NoClockView
import com.android.systemui.util.ui.value
import dagger.Lazy
import javax.inject.Inject
import kotlinx.coroutines.DisposableHandle
import kotlin.math.roundToInt

internal fun ConstraintSet.setVisibility(views: Iterable<View>, visibility: Int) =
    views.forEach { view -> this.setVisibility(view.id, visibility) }

private fun ConstraintSet.setAlpha(views: Iterable<View>, alpha: Float) =
    views.forEach { view -> this.setAlpha(view.id, alpha) }

private fun ConstraintSet.setScaleX(views: Iterable<View>, scaleX: Float) =
    views.forEach { view -> this.setScaleX(view.id, scaleX) }

private fun ConstraintSet.setScaleY(views: Iterable<View>, scaleY: Float) =
    views.forEach { view -> this.setScaleY(view.id, scaleY) }

@SysUISingleton
class ClockSection
@Inject
constructor(
    private val clockInteractor: KeyguardClockInteractor,
    protected val keyguardClockViewModel: KeyguardClockViewModel,
    @ShadeDisplayAware private val context: Context,
    val smartspaceViewModel: KeyguardSmartspaceViewModel,
    val blueprintInteractor: Lazy<KeyguardBlueprintInteractor>,
    private val rootViewModel: KeyguardRootViewModel,
    private val aodBurnInViewModel: AodBurnInViewModel,
    private val largeScreenHeaderHelperLazy: Lazy<LargeScreenHeaderHelper>,
) : KeyguardSection() {
    private var disposableHandle: DisposableHandle? = null

    override fun addViews(constraintLayout: ConstraintLayout) {}

    override fun bindData(constraintLayout: ConstraintLayout) {
        disposableHandle?.dispose()
        disposableHandle =
            KeyguardClockViewBinder.bind(
                this,
                constraintLayout,
                keyguardClockViewModel,
                clockInteractor,
                blueprintInteractor.get(),
                rootViewModel,
                aodBurnInViewModel,
            )
    }

    override fun applyConstraints(constraintSet: ConstraintSet) {
        keyguardClockViewModel.currentClock.value?.let { clock ->
            constraintSet.applyDeltaFrom(buildConstraints(clock, constraintSet))
        }
    }

    override fun removeViews(constraintLayout: ConstraintLayout) {
        disposableHandle?.dispose()
    }

    private fun buildConstraints(
        clock: ClockController,
        constraintSet: ConstraintSet,
    ): ConstraintSet {
        val isLargeVisible = keyguardClockViewModel.isLargeClockVisible.value
        val targetFace = getTargetClockFace(clock)
        val nonTargetFace = getNonTargetClockFace(clock)
        val targetViews = targetFace.views
        val nonTargetViews = nonTargetFace.views
        // Add constraint between rootView and clockContainer
        applyDefaultConstraints(constraintSet, clock)
        nonTargetFace.applyConstraints(constraintSet)
        targetFace.applyConstraints(constraintSet)

        // Add constraint between elements in clock and clock container
        return constraintSet.apply {
            setVisibility(targetViews, VISIBLE)
            setVisibility(nonTargetViews, GONE)
            setAlpha(targetViews, 1F)
            setAlpha(nonTargetViews, 0F)

            if (!keyguardClockViewModel.isLargeClockVisible.value) {
                if (keyguardClockViewModel.shouldDateWeatherBeBelowSmallClock.value && !useAxClocks) {
                    connect(
                        sharedR.id.bc_smartspace_view,
                        TOP,
                        sharedR.id.date_smartspace_view,
                        BOTTOM,
                    )
                }
            } else {
                if (aodBurnInViewModel.movement.value.scaleClockOnly) {
                    setScaleX(
                        getTargetClockFace(clock).views,
                        aodBurnInViewModel.movement.value.scale,
                    )
                    setScaleY(
                        getTargetClockFace(clock).views,
                        aodBurnInViewModel.movement.value.scale,
                    )
                }
            }
        }
    }

    private fun getTargetClockFace(clock: ClockController): ClockFaceLayout =
        if (keyguardClockViewModel.isLargeClockVisible.value) clock.largeClock.layout
        else clock.smallClock.layout

    private fun getNonTargetClockFace(clock: ClockController): ClockFaceLayout =
        if (keyguardClockViewModel.isLargeClockVisible.value) clock.smallClock.layout
        else clock.largeClock.layout

    private fun getSmallClockHeight(clock: ClockController?): Int {
        val baseHeight = context.resources.getDimensionPixelSize(custR.dimen.clock_height)
        if (!useAxClocks) return baseHeight
        return (clock?.smallClock?.view as? AxClockView)?.clockHeight
            ?: (baseHeight * ClockSettingsRepository.sizeScale.value).roundToInt()
    }

    private fun constrainWeatherClockDateIconsBarrier(constraints: ConstraintSet) {
        constraints.apply {
            createBarrier(
                R.id.weather_clock_bc_smartspace_bottom,
                Barrier.BOTTOM,
                context.resources.getDimensionPixelSize(clocksR.dimen.enhanced_smartspace_height),
                (ClockViewIds.WEATHER_CLOCK_TIME),
            )
            if (
                rootViewModel.isNotifIconContainerVisible.value.value &&
                    keyguardClockViewModel.hasAodIcons.value
            ) {
                createBarrier(
                    ClockViewIds.WEATHER_CLOCK_DATE_BARRIER_BOTTOM,
                    Barrier.BOTTOM,
                    0,
                    *intArrayOf(
                        R.id.aod_notification_icon_container,
                        R.id.weather_clock_bc_smartspace_bottom,
                    ),
                )
            } else {
                createBarrier(
                    ClockViewIds.WEATHER_CLOCK_DATE_BARRIER_BOTTOM,
                    Barrier.BOTTOM,
                    0,
                    *intArrayOf(R.id.weather_clock_bc_smartspace_bottom),
                )
            }
        }
    }

    fun applyDefaultConstraints(
        constraints: ConstraintSet,
        clock: ClockController? = keyguardClockViewModel.currentClock.value,
    ) {
        val isLargeVisible = keyguardClockViewModel.isLargeClockVisible.value
        val smallClockHeight = getSmallClockHeight(clock)
        val smallClockView = clock?.smallClock?.view
        val hasVisibleAxSmallClock =
            useAxClocks && !isLargeVisible && smallClockView != null && smallClockView !is NoClockView
        val guideline =
            if (keyguardClockViewModel.clockShouldBeCentered.value) PARENT_ID
            else R.id.split_shade_guideline
        constraints.apply {
            connect(ClockViewIds.LOCKSCREEN_CLOCK_VIEW_LARGE, START, PARENT_ID, START)
            connect(ClockViewIds.LOCKSCREEN_CLOCK_VIEW_LARGE, END, guideline, END)
            if (
                com.android.systemui.shared.Flags.clockReactiveSmartspaceLayout() &&
                    !com.android.systemui.shared.Flags.clockReactiveVariants() 
                    && !useAxClocks
            ) {
                connect(
                    ClockViewIds.LOCKSCREEN_CLOCK_VIEW_LARGE,
                    BOTTOM,
                    R.id.device_entry_icon_view,
                    TOP,
                    context.resources.getDimensionPixelSize(
                        clocksR.dimen.date_weather_view_height
                    ) * 2,
                )
            } else {
                connect(
                    ClockViewIds.LOCKSCREEN_CLOCK_VIEW_LARGE,
                    BOTTOM,
                    R.id.device_entry_icon_view,
                    TOP,
                )
            }
            val largeClockTopMargin =
                if (useAxClocks) {
                    keyguardClockViewModel.getLargeClockTopMargin()
                } else if (com.android.systemui.shared.Flags.clockReactiveSmartspaceLayout()) {
                    keyguardClockViewModel.getLargeClockTopMargin() +
                        context.resources.getDimensionPixelSize(
                            clocksR.dimen.enhanced_smartspace_height
                        )
                } else {
                    keyguardClockViewModel.getLargeClockTopMargin() +
                        context.resources.getDimensionPixelSize(
                            clocksR.dimen.date_weather_view_height
                        ) +
                        context.resources.getDimensionPixelSize(
                            clocksR.dimen.enhanced_smartspace_height
                        )
                }
            connect(
                ClockViewIds.LOCKSCREEN_CLOCK_VIEW_LARGE,
                TOP,
                R.id.smart_space_barrier_bottom,
                BOTTOM,
                largeClockTopMargin,
            )
            constrainWidth(ClockViewIds.LOCKSCREEN_CLOCK_VIEW_LARGE, MATCH_CONSTRAINT)

            // The following two lines make LOCKSCREEN_CLOCK_VIEW_LARGE is constrained to available
            // height when it goes beyond constraints; otherwise, it use WRAP_CONTENT
            constrainHeight(ClockViewIds.LOCKSCREEN_CLOCK_VIEW_LARGE, WRAP_CONTENT)
            constrainMaxHeight(ClockViewIds.LOCKSCREEN_CLOCK_VIEW_LARGE, 0)
            constrainWidth(ClockViewIds.LOCKSCREEN_CLOCK_VIEW_SMALL, MATCH_CONSTRAINT)
            constrainHeight(
                ClockViewIds.LOCKSCREEN_CLOCK_VIEW_SMALL,
                smallClockHeight,
            )
            connect(
                ClockViewIds.LOCKSCREEN_CLOCK_VIEW_SMALL,
                START,
                PARENT_ID,
                START,
                0
            )
            connect(
                ClockViewIds.LOCKSCREEN_CLOCK_VIEW_SMALL,
                END,
                guideline,
                END,
            )
            val smallClockTopMargin = keyguardClockViewModel.getSmallClockTopMargin()
            create(R.id.small_clock_guideline_top, ConstraintSet.HORIZONTAL_GUIDELINE)
            setGuidelineBegin(R.id.small_clock_guideline_top, smallClockTopMargin)
            connect(
                ClockViewIds.LOCKSCREEN_CLOCK_VIEW_SMALL,
                TOP,
                R.id.small_clock_guideline_top,
                BOTTOM,
            )

            // Explicitly clear pivot to force recalculate pivot instead of using legacy value
            setTransformPivot(ClockViewIds.LOCKSCREEN_CLOCK_VIEW_LARGE, Float.NaN, Float.NaN)

            val smallClockBottom =
                keyguardClockViewModel.getSmallClockTopMargin() + smallClockHeight
            val marginBetweenSmartspaceAndNotification =
                context.resources.getDimensionPixelSize(
                    R.dimen.keyguard_status_view_bottom_margin
                ) +
                    if (context.resources.getBoolean(R.bool.config_use_large_screen_shade_header)) {
                        largeScreenHeaderHelperLazy.get().getLargeScreenHeaderHeight()
                    } else {
                        0
                    }

            if (hasVisibleAxSmallClock) {
                clockInteractor.setNotificationStackDefaultTop(
                    (smallClockBottom + marginBetweenSmartspaceAndNotification).toFloat()
                )
            } else if (!useAxClocks && keyguardClockViewModel.shouldDateWeatherBeBelowSmallClock.value) {
                val dateWeatherSmartspaceHeight =
                    context.resources
                        .getDimensionPixelSize(clocksR.dimen.date_weather_view_height)
                        .toFloat()
                clockInteractor.setNotificationStackDefaultTop(
                    smallClockBottom +
                        dateWeatherSmartspaceHeight +
                        marginBetweenSmartspaceAndNotification
                )
            } else {
                clockInteractor.setNotificationStackDefaultTop(
                    (keyguardClockViewModel.getSmallClockTopMargin() +
                            marginBetweenSmartspaceAndNotification)
                        .toFloat()
                )
            }
        }

        if (!useAxClocks) constrainWeatherClockDateIconsBarrier(constraints)
    }
}
