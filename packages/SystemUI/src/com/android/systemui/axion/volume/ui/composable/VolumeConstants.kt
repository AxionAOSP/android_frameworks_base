/*
 * Copyright (C) 2025-2026 Axion OS
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

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

internal val SliderTrackHeight = 224.dp
internal val SliderTrackWidthThick = 40.dp
internal val SliderThumbWidth = 52.dp
internal val SliderThumbHeight = 4.dp
internal val SliderIconSize = 20.dp

internal val CollapsedPanelWidth = 56.dp
internal val ExpandedPanelWidth = 288.dp
internal val SliderWidthExpanded = 56.dp
internal val SliderRowSpacing = 8.dp
internal val SliderRowHorizontalPadding = 8.dp

internal val RingerCircleSize = 56.dp
internal val RingerRowIconSize = 42.dp
internal val RingerRowOuterPadding = 8.dp
internal val RingerRowHeight = RingerRowIconSize + RingerRowOuterPadding * 2

internal val RingerToSliderGap = 10.dp
internal val SliderTopPadding = 4.dp
internal val TrackToIconSpacing = 4.dp
internal val IconToExpandSpacing = 4.dp
internal val SliderIconContainerSize = 40.dp

internal val DialogCornerRadius = 28.dp
internal val WindowPadding = 12.dp
internal val ContentSpacingSmall = 8.dp

internal const val MaxVisibleSliders = 5

internal val SeeMoreHeight = SliderIconContainerSize
internal val HeaderIconSize = 20.dp

@Composable
internal fun volumePanelSecondaryContentColor() =
    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
