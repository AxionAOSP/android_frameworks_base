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

package com.android.systemui.qs.ax.ui.compose

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.systemui.qs.ax.shared.model.AxQsSpan
import com.android.systemui.qs.panels.ui.compose.infinitegrid.CommonTileDefaults

@Immutable
internal sealed interface AxMediaSizing {
    val horizontalPadding: Dp
    val verticalPadding: Dp
    val headerIconSize: Dp
    val outputChipCompact: Boolean
    val actionSize: Dp
    val actionIconSize: Dp
    val heroActionSize: Dp
    val heroIconSize: Dp
    val actionSpacing: Dp
    val showTimestamps: Boolean

    @Immutable
    data class Compact(
        override val horizontalPadding: Dp = CompactHorizontalPadding,
        override val verticalPadding: Dp = CompactVerticalPadding,
        override val headerIconSize: Dp = CompactHeaderIconSize,
        override val outputChipCompact: Boolean = true,
        override val actionSize: Dp = CompactActionSize,
        override val actionIconSize: Dp = CompactActionIconSize,
        override val heroActionSize: Dp = CompactHeroActionSize,
        override val heroIconSize: Dp = CompactHeroIconSize,
        override val actionSpacing: Dp = CompactActionSpacing,
        override val showTimestamps: Boolean = false,
    ) : AxMediaSizing

    @Immutable
    data class Medium(
        override val horizontalPadding: Dp = MediumHorizontalPadding,
        override val verticalPadding: Dp = MediumVerticalPadding,
        override val headerIconSize: Dp = MediumHeaderIconSize,
        override val outputChipCompact: Boolean = true,
        override val actionSize: Dp = MediumActionSize,
        override val actionIconSize: Dp = MediumActionIconSize,
        override val heroActionSize: Dp = MediumHeroActionSize,
        override val heroIconSize: Dp = MediumHeroIconSize,
        override val actionSpacing: Dp = MediumActionSpacing,
        override val showTimestamps: Boolean = true,
    ) : AxMediaSizing

    @Immutable
    data class Expanded(
        override val horizontalPadding: Dp = ExpandedHorizontalPadding,
        override val verticalPadding: Dp = ExpandedVerticalPadding,
        override val headerIconSize: Dp = ExpandedHeaderIconSize,
        override val outputChipCompact: Boolean = false,
        override val actionSize: Dp = ExpandedActionSize,
        override val actionIconSize: Dp = ExpandedActionIconSize,
        override val heroActionSize: Dp = ExpandedHeroActionSize,
        override val heroIconSize: Dp = ExpandedHeroIconSize,
        override val actionSpacing: Dp = ExpandedActionSpacing,
        override val showTimestamps: Boolean = true,
    ) : AxMediaSizing

    companion object {
        val DefaultCompact = Compact()
        val DefaultMedium = Medium()
        val DefaultExpanded = Expanded()

        fun from(width: Dp, height: Dp, span: AxQsSpan? = null): AxMediaSizing =
            when {
                width < CompactWidthThreshold || height < CompactHeightThreshold -> DefaultCompact
                width < MediumWidthThreshold -> DefaultMedium
                else -> DefaultExpanded
            }
    }
}

internal fun mediaActionLimit(columns: Int): Int =
    when {
        columns <= 2 -> 3
        columns == 3 -> 4
        else -> 5
    }

internal val nonQsGridMediaHeight: Dp = CommonTileDefaults.TileHeight * 2 + 16.dp

private val CompactWidthThreshold = 180.dp
private val CompactHeightThreshold = 120.dp
private val MediumWidthThreshold = 300.dp

private val CompactHorizontalPadding = 12.dp
private val CompactVerticalPadding = 6.dp
private val CompactHeaderIconSize = 16.dp
private val CompactActionSize = 36.dp
private val CompactActionIconSize = 20.dp
private val CompactHeroActionSize = 40.dp
private val CompactHeroIconSize = 24.dp
private val CompactActionSpacing = 16.dp

private val MediumHorizontalPadding = 16.dp
private val MediumVerticalPadding = 8.dp
private val MediumHeaderIconSize = 18.dp
private val MediumActionSize = 38.dp
private val MediumActionIconSize = 22.dp
private val MediumHeroActionSize = 42.dp
private val MediumHeroIconSize = 26.dp
private val MediumActionSpacing = 20.dp

private val ExpandedHorizontalPadding = 16.dp
private val ExpandedVerticalPadding = 8.dp
private val ExpandedHeaderIconSize = 14.dp
private val ExpandedActionSize = 42.dp
private val ExpandedActionIconSize = 24.dp
private val ExpandedHeroActionSize = 46.dp
private val ExpandedHeroIconSize = 28.dp
private val ExpandedActionSpacing = 20.dp
