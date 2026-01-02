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

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.systemui.theme.UiStyleProvider

internal val VolumeButtonsSize = 56.dp
internal val VolumeButtonsSizeExpanded = 40.dp
internal val RingerIndicatorSize = 44.dp
internal val DialogAnimDuration = 200

internal val SliderHeightTarget = 220.dp
internal val SliderHeightMin = 160.dp
internal val SliderWidthCollapsed = 56.dp
internal val SliderWidthExpanded = 40.dp
internal val SliderSpacing = 8.dp
internal val SliderTrackWidthExpanded = 32.dp
internal val SliderTrackWidthCollapsed = 16.dp
internal val SliderIconSize = 20.dp
internal val SliderIconMinSize = 12.dp
internal val SliderExpandedContentWidth = (SliderWidthExpanded * 4) + (SliderSpacing * 3) + 48.dp

internal fun calculateExpandedPanelWidth(streamCount: Int, maxWidth: Dp? = null, isLandscape: Boolean = false): Dp {
    val count = streamCount.coerceAtLeast(4)
    val slidersWidth = SliderWidthExpanded * count
    val spacingWidth = SliderSpacing * (count - 1)
    val horizontalPadding = 32.dp
    val idealWidth = slidersWidth + spacingWidth + horizontalPadding
    return if (maxWidth != null) minOf(idealWidth, maxWidth) else idealWidth
}

@Composable
internal fun styledSliderHeight(): Dp {
    val style = UiStyleProvider.rememberCurrentStyle()
    return style.volumeSliderHeight
}

@Composable
internal fun styledTrackWidthExpanded(): Dp {
    val style = UiStyleProvider.rememberCurrentStyle()
    return style.volumeSliderTrackWidthExpanded
}

@Composable
internal fun styledTrackWidthCollapsed(): Dp {
    val style = UiStyleProvider.rememberCurrentStyle()
    return style.volumeSliderTrackWidthCollapsed
}
