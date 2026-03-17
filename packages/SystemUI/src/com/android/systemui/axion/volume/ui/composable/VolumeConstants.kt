/*
 * Copyright (C) 2025 Axion OS
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

package com.android.systemui.axion.volume.ui.composable

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal val SliderTrackWidth = 42.dp
internal val SliderCornerRadius = 100.dp
internal val SliderIconSize = 20.dp
internal val SliderTrackHeight = 222.dp
internal val SliderSpacing = 12.dp

internal val SliderWidthCollapsed = SliderTrackWidth + 12.dp
internal val SliderWidthExpanded = SliderTrackWidth + 8.dp

internal val DialogCornerRadius = 28.dp
internal val PanelHorizontalPadding = 16.dp
internal val WindowPadding = 12.dp
internal val ContentSpacing = 8.dp
internal val ContentSpacingSmall = 6.dp
internal val PercentageSpacing = 4.dp

internal const val VOLUME_UPDATE_GRACE_PERIOD = 1000L

internal val VolumeButtonsSize = 40.dp
internal val VolumeButtonsSizeExpanded = 40.dp

internal val RingerDrawerItemSize = 40.dp
internal val RingerDrawerItemCornerRadius = 50.dp
internal val RingerDrawerIconSize = 20.dp

internal val ExpandButtonWidth = 48.dp
internal val ExpandButtonHeight = 48.dp
internal val ExpandIconSize = 24.dp

internal val HeaderIconSize = 20.dp
internal val TitlePaddingHorizontal = 8.dp

internal const val MaxVisibleSliders = 5

internal val ScrollIndicatorTrackWidth = 24.dp
internal val ScrollIndicatorHeight = 1.dp
internal val ScrollIndicatorTopPadding = 4.dp

internal fun calculateExpandedPanelWidth(streamCount: Int): Dp {
    val visibleCount = streamCount.coerceIn(2, MaxVisibleSliders)
    val slidersWidth = SliderWidthExpanded * visibleCount
    val spacingWidth = SliderSpacing * (visibleCount - 1)
    val edgePadding = PanelHorizontalPadding * 2
    return slidersWidth + spacingWidth + edgePadding
}
