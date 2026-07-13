/*
 * Copyright 2025-2026 AxionOS
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

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.android.systemui.qs.ax.ui.compose

import android.content.res.Configuration
import android.graphics.Typeface
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import androidx.annotation.ColorInt
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.android.compose.animation.scene.SceneKey
import com.android.systemui.privacy.OngoingPrivacyChip
import com.android.systemui.privacy.PrivacyItem
import com.android.systemui.qs.ax.shared.model.AxQsLayoutPadding
import com.android.systemui.res.R
import com.android.systemui.shade.ui.composable.BatteryInfo
import com.android.systemui.shade.ui.composable.CutoutAwareShadeHeader
import com.android.systemui.shade.ui.composable.LocalStatusIconContext
import com.android.systemui.shade.ui.composable.ShadeHeader
import com.android.systemui.shade.ui.viewmodel.ShadeHeaderViewModel
import com.android.systemui.statusbar.policy.Clock as ClockView
import com.android.systemui.statusbar.systemstatusicons.SystemStatusIconsInCompose
import com.android.systemui.statusbar.systemstatusicons.ui.compose.SystemStatusIcons
import com.android.systemui.statusbar.systemstatusicons.ui.compose.SystemStatusIconsLegacy

private val AxQuickSettingsHeaderContent = SceneKey("AxQuickSettingsHeader")

@Composable
fun AxQuickSettingsHeader(
    viewModel: ShadeHeaderViewModel,
    isTransitioning: Boolean,
    modifier: Modifier = Modifier,
) {
    val foregroundColor = MaterialTheme.colorScheme.onSurface
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val landscape =
            LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
        val sidePadding =
            maxWidth *
                if (landscape) {
                    AxQuickSettingsLayoutDefaults.LANDSCAPE_SIDE_PADDING_FRACTION
                } else {
                    AxQuickSettingsLayoutDefaults.PORTRAIT_SIDE_PADDING_FRACTION
                }
        val startContent: @Composable () -> Unit = {
            Row(
                modifier = Modifier.padding(start = sidePadding),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AxQuickSettingsClock(viewModel)
                if (landscape) {
                    AxQuickSettingsDate(viewModel)
                }
            }
        }
        val endContent: @Composable () -> Unit = {
            Row(
                modifier = Modifier.padding(end = sidePadding),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AxStatusIcons(
                    viewModel = viewModel,
                    isTransitioning = isTransitioning,
                    foregroundColor = foregroundColor.toArgb(),
                    backgroundColor = Color.Transparent.toArgb(),
                )
                BatteryInfo(
                    viewModel = viewModel,
                    showIcon = true,
                    useExpandedFormat = false,
                    textColor = foregroundColor,
                    iconTint = foregroundColor,
                    iconBackgroundColor = Color.Transparent,
                    onClick = viewModel::onBatteryClicked,
                )
                if (viewModel.isPrivacyChipVisible) {
                    AxPrivacyChip(
                        privacyList = viewModel.privacyItems,
                        onClick = viewModel::onPrivacyChipClicked,
                    )
                }
            }
        }
        if (landscape) {
            Box(
                Modifier.fillMaxWidth().height(AxQuickSettingsLayoutDefaults.LandscapeHeaderHeight)
            ) {
                Box(modifier = Modifier.align(Alignment.TopStart)) { startContent() }
                Box(modifier = Modifier.align(Alignment.TopEnd)) { endContent() }
            }
        } else {
            CutoutAwareShadeHeader(
                modifier = Modifier.fillMaxWidth(),
                startContent = startContent,
                endContent = endContent,
            )
        }
    }
}

@Composable
private fun AxStatusIcons(
    viewModel: ShadeHeaderViewModel,
    isTransitioning: Boolean,
    @ColorInt foregroundColor: Int,
    @ColorInt backgroundColor: Int,
    modifier: Modifier = Modifier,
) {
    key(viewModel.configChangeToken) {
        if (SystemStatusIconsInCompose.isEnabled) {
            SystemStatusIcons(
                viewModelFactory = viewModel.systemStatusIconsViewModelFactory,
                tint = Color(foregroundColor),
                modifier = modifier,
            )
        } else {
            val statusIconContext = LocalStatusIconContext.current
            val iconContainer = statusIconContext.iconContainer(AxQuickSettingsHeaderContent)
            val iconManager = statusIconContext.iconManager(AxQuickSettingsHeaderContent)
            val movableContent =
                remember(statusIconContext, iconManager) {
                    statusIconContext.movableContent(iconManager)
                }
            SystemStatusIconsLegacy(
                iconContainer = iconContainer,
                iconManager = iconManager,
                statusBarIconController = viewModel.statusBarIconController,
                useExpandedFormat = false,
                isTransitioning = isTransitioning,
                foregroundColor = foregroundColor,
                backgroundColor = backgroundColor,
                isSingleCarrier = viewModel.isSingleCarrier,
                isMicCameraIndicationEnabled = viewModel.isMicCameraIndicationEnabled,
                isPrivacyChipEnabled = viewModel.isPrivacyChipVisible,
                isLocationIndicationEnabled = viewModel.isLocationIndicationEnabled,
                modifier = modifier,
                content = movableContent,
            )
        }
    }
}

@Composable
private fun AxPrivacyChip(
    privacyList: List<PrivacyItem>,
    onClick: (OngoingPrivacyChip) -> Unit,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        factory = { context ->
            OngoingPrivacyChip(context, null).also { chip ->
                chip.privacyList = privacyList
                chip.setOnClickListener { onClick(chip) }
            }
        },
        update = { it.privacyList = privacyList },
        modifier = modifier,
    )
}

@Composable
fun AxQuickSettingsClock(viewModel: ShadeHeaderViewModel, modifier: Modifier = Modifier) {
    val textColor = MaterialTheme.colorScheme.onSurface
    AxHeaderClock(onClick = viewModel::onClockClicked, textColor = textColor, modifier = modifier)
}

@Composable
fun AxQuickSettingsDate(viewModel: ShadeHeaderViewModel, modifier: Modifier = Modifier) {
    AxHeaderDate(
        longerDateText = viewModel.longerDateText,
        shorterDateText = viewModel.shorterDateText,
        textColor = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.clickable(onClick = viewModel::onDateClicked),
    )
}

@Composable
private fun AxHeaderClock(onClick: () -> Unit, textColor: Color, modifier: Modifier = Modifier) {
    val configuration = LocalConfiguration.current
    key(configuration.densityDpi, configuration.fontScale) {
        AndroidView(
            factory = { context ->
                ClockView(
                        ContextThemeWrapper(context, R.style.Theme_SystemUI_QuickSettings_Header),
                        null,
                    )
                    .apply {
                        isSingleLine = true
                        setIncludeFontPadding(true)
                        textDirection = View.TEXT_DIRECTION_LOCALE
                        gravity = Gravity.START or Gravity.CENTER_VERTICAL
                        setTypeface(typeface, Typeface.NORMAL)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, AX_CLOCK_DATE_TEXT_SIZE)
                    }
            },
            update = { view ->
                view.setTextColor(textColor.toArgb())
                view.setTypeface(view.typeface, Typeface.NORMAL)
                view.setTextSize(TypedValue.COMPLEX_UNIT_SP, AX_CLOCK_DATE_TEXT_SIZE)
            },
            modifier = modifier.wrapContentWidth(unbounded = true).clickable(onClick = onClick),
        )
    }
}

@Composable
private fun AxHeaderDate(
    longerDateText: String,
    shorterDateText: String,
    textColor: Color,
    modifier: Modifier = Modifier,
) {
    val textStyle =
        MaterialTheme.typography.bodyLarge.copy(
            fontSize = AX_CLOCK_DATE_TEXT_SIZE.sp,
            platformStyle = PlatformTextStyle(includeFontPadding = true),
        )
    Layout(
        contents =
            listOf(
                {
                    Text(
                        text = longerDateText,
                        style = textStyle,
                        color = textColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        softWrap = false,
                    )
                },
                {
                    Text(
                        text = shorterDateText,
                        style = textStyle,
                        color = textColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        softWrap = false,
                    )
                },
            ),
        modifier = modifier,
    ) { measurables, constraints ->
        val longer = measurables[0][0]
        val shorter = measurables[1][0]
        val intrinsicHeight =
            if (constraints.hasBoundedHeight) constraints.maxHeight else Constraints.Infinity
        val longerFits = longer.maxIntrinsicWidth(intrinsicHeight) <= constraints.maxWidth
        val shorterFits = shorter.maxIntrinsicWidth(intrinsicHeight) <= constraints.maxWidth
        val selected = if (longerFits) longer else shorter
        val selectedConstraints =
            if (!longerFits && !shorterFits) {
                constraints.copy(minWidth = 0, minHeight = 0)
            } else {
                constraints.copy(
                    minWidth = 0,
                    maxWidth = Constraints.Infinity,
                    minHeight = 0,
                    maxHeight = Constraints.Infinity,
                )
            }
        val placeable = selected.measure(selectedConstraints)
        layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
    }
}

private const val AX_CLOCK_DATE_TEXT_SIZE = 18f

object AxQuickSettingsLayoutDefaults {
    const val PORTRAIT_SIDE_PADDING_FRACTION = AxQsLayoutPadding.PORTRAIT_SIDE_FRACTION
    const val LANDSCAPE_SIDE_PADDING_FRACTION = AxQsLayoutPadding.LANDSCAPE_SIDE_FRACTION
    val LandscapeGridSpacing = 16.dp
    val LandscapeSplitGridSpacing = AxQsLayoutPadding.LANDSCAPE_SPLIT_GRID_SPACING_DP.dp
    val LandscapeHeaderContentSpacing = 20.dp
    val LandscapeHeaderHeight: Dp
        @Composable get() = ShadeHeader.Dimensions.StatusBarHeight
}
