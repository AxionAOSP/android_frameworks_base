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

import android.content.Context
import android.graphics.drawable.Drawable
import android.service.quicksettings.Tile.STATE_ACTIVE
import android.service.quicksettings.Tile.STATE_INACTIVE
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.android.compose.modifiers.thenIf
import com.android.compose.theme.LocalAndroidColorScheme
import com.android.systemui.Flags
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.qs.ax.shared.model.AxQsSpan
import com.android.systemui.qs.composefragment.LocalBlurEnabled
import com.android.systemui.qs.panels.ui.compose.infinitegrid.CommonTileDefaults
import com.android.systemui.qs.panels.ui.compose.infinitegrid.CommonTileDefaults.longPressLabelSettings
import com.android.systemui.qs.panels.ui.compose.infinitegrid.LargeTileLabels
import com.android.systemui.qs.panels.ui.compose.infinitegrid.LocalTileScale
import com.android.systemui.qs.panels.ui.compose.infinitegrid.SmallTileContent
import com.android.systemui.qs.panels.ui.compose.infinitegrid.TileColors
import com.android.systemui.qs.panels.ui.compose.infinitegrid.bounceScale
import com.android.systemui.qs.panels.ui.viewmodel.AccessibilityUiState
import com.android.systemui.qs.ui.compose.borderOnFocus

internal object AxTileDefaults {
    val LargeIconSize = 24.dp
    val DividerWidth = 1.dp
    val DividerHeight = 16.dp
    val IconDividerSpacing = 12.dp
    val DividerLabelSpacing = 16.dp
    val LargeTileStartPadding = 24.dp
    val LargeTileEndPadding = 16.dp

    @Composable
    fun dividerColor(state: Int): Color {
        return when (state) {
            STATE_ACTIVE -> MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.2f)
            STATE_INACTIVE -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)
            else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f)
        }
    }

    @Composable
    fun backgroundColor(): Color {
        return if (LocalBlurEnabled.current) {
            LocalAndroidColorScheme.current.surfaceEffect1
        } else {
            MaterialTheme.colorScheme.surfaceBright
        }
    }
}

@Composable
fun AxLargeTileContent(
    label: String,
    secondaryLabel: String?,
    iconProvider: Context.() -> Icon,
    sideDrawable: Drawable?,
    colors: TileColors,
    squishiness: () -> Float,
    tileState: Int,
    span: AxQsSpan,
    modifier: Modifier = Modifier,
    isVisible: () -> Boolean = { true },
    accessibilityUiState: AccessibilityUiState? = null,
    iconShape: RoundedCornerShape = RoundedCornerShape(CommonTileDefaults.InactiveCornerRadius),
    textScale: () -> Float = { 1f },
    toggleClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    if (span.rows == 1) {
        val isDualTarget = toggleClick != null
        val scale = LocalTileScale.current
        val longPressLabel = longPressLabelSettings().takeIf { onLongClick != null }
        val focusBorderColor = MaterialTheme.colorScheme.secondary
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                modifier.padding(
                    start = AxTileDefaults.LargeTileStartPadding * scale,
                    end = AxTileDefaults.LargeTileEndPadding * scale,
                ),
        ) {
            Box(
                modifier =
                    Modifier.fillMaxHeight().thenIf(isDualTarget) {
                        Modifier.borderOnFocus(color = focusBorderColor, iconShape.topEnd)
                            .combinedClickable(
                                onClick = toggleClick!!,
                                onLongClick = onLongClick,
                                onLongClickLabel = longPressLabel,
                                hapticFeedbackEnabled = !Flags.msdlFeedback(),
                            )
                    },
                contentAlignment = Alignment.Center,
            ) {
                SmallTileContent(
                    iconProvider = iconProvider,
                    color = colors.icon,
                    size = { AxTileDefaults.LargeIconSize * scale },
                )
            }

            if (isDualTarget) {
                Spacer(Modifier.width(AxTileDefaults.IconDividerSpacing * scale))
                Box(
                    Modifier.width(AxTileDefaults.DividerWidth)
                        .height(AxTileDefaults.DividerHeight * scale)
                        .background(AxTileDefaults.dividerColor(tileState))
                )
                Spacer(Modifier.width(AxTileDefaults.DividerLabelSpacing * scale))
            } else {
                Spacer(Modifier.width(AxTileDefaults.DividerLabelSpacing * scale))
            }

            LargeTileLabels(
                label = label,
                secondaryLabel = secondaryLabel,
                colors = colors,
                accessibilityUiState = accessibilityUiState,
                isVisible = isVisible,
                modifier =
                    Modifier.weight(1f)
                        .bounceScale(TransformOrigin(0f, .5f), textScale),
            )
        }
        return
    }

    val scale = LocalTileScale.current
    val layoutDirection = LocalLayoutDirection.current
    val textOrigin = if (layoutDirection == LayoutDirection.Ltr) 1f else 0f
    val textLayoutDirection =
        if (layoutDirection == LayoutDirection.Ltr) LayoutDirection.Rtl else LayoutDirection.Ltr

    Column(
        modifier =
            modifier.padding(
                start = AxTileDefaults.LargeTileStartPadding * scale,
                top = AxTileDefaults.LargeTileEndPadding * scale,
                end = AxTileDefaults.LargeTileEndPadding * scale,
                bottom = AxTileDefaults.LargeTileEndPadding * scale,
            )
    ) {
        AxLargeTileIcon(
            iconProvider = iconProvider,
            colors = colors,
            scale = scale,
            toggleClick = toggleClick,
            onLongClick = onLongClick,
            modifier = Modifier.size(CommonTileDefaults.ToggleTargetSize * scale),
        )
        Spacer(Modifier.weight(1f))
        Box(
            modifier = Modifier.fillMaxWidth().height(CommonTileDefaults.TileHeight * scale),
            contentAlignment = Alignment.CenterEnd,
        ) {
            CompositionLocalProvider(LocalLayoutDirection provides textLayoutDirection) {
                LargeTileLabels(
                    label = label,
                    secondaryLabel = secondaryLabel,
                    colors = colors,
                    accessibilityUiState = accessibilityUiState,
                    isVisible = isVisible,
                    modifier =
                        Modifier.fillMaxWidth(0.75f)
                            .bounceScale(TransformOrigin(textOrigin, .5f), textScale),
                )
            }
        }
    }
}

@Composable
private fun AxLargeTileIcon(
    iconProvider: Context.() -> Icon,
    colors: TileColors,
    scale: Float,
    toggleClick: (() -> Unit)?,
    onLongClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val longPressLabel = longPressLabelSettings().takeIf { onLongClick != null }
    val focusBorderColor = MaterialTheme.colorScheme.secondary
    val chipColor by
        animateColorAsState(
            targetValue =
                if (colors.iconBackground == Color.Transparent) {
                    colors.icon.copy(alpha = ICON_CHIP_ALPHA)
                } else {
                    colors.iconBackground
                },
            label = "AxLargeTileIconBackground",
        )
    Box(
        modifier =
            modifier.background(chipColor, CircleShape).thenIf(toggleClick != null) {
                Modifier.borderOnFocus(color = focusBorderColor, CircleShape.topEnd)
                    .combinedClickable(
                        onClick = toggleClick!!,
                        onLongClick = onLongClick,
                        onLongClickLabel = longPressLabel,
                        hapticFeedbackEnabled = !Flags.msdlFeedback(),
                    )
            },
        contentAlignment = Alignment.Center,
    ) {
        SmallTileContent(
            iconProvider = iconProvider,
            color = colors.icon,
            size = { AxTileDefaults.LargeIconSize * scale },
        )
    }
}

private const val ICON_CHIP_ALPHA = 0.12f
