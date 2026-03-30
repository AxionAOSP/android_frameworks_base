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

package com.android.systemui.qs.panels.ui.compose.infinitegrid

import android.content.Context
import android.content.res.Configuration
import android.graphics.drawable.Drawable
import android.service.quicksettings.Tile.STATE_ACTIVE
import android.service.quicksettings.Tile.STATE_INACTIVE
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.compose.modifiers.thenIf
import com.android.systemui.Flags
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.qs.panels.ui.compose.infinitegrid.CommonTileDefaults.longPressLabelSettings
import com.android.systemui.qs.panels.ui.viewmodel.AccessibilityUiState
import com.android.systemui.qs.ui.compose.borderOnFocus


object AxTileDefaults {
    val TileCornerRadius = 36.dp
    val ActiveTileCornerRadius = 36.dp
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
}

object QSTileScaling {
    private const val REFERENCE_WIDTH_DP = 411f

    @Composable
    fun scaleFactor(): Float {
        val screenWidth = LocalConfiguration.current.screenWidthDp.toFloat()
        return (screenWidth / REFERENCE_WIDTH_DP).coerceIn(0.80f, 1.25f)
    }

    @Composable
    fun tileMarginHorizontal(): Dp {
        return (8.dp * scaleFactor()).coerceIn(4.dp, 12.dp)
    }

    @Composable
    fun largeTileStartPadding(): Dp {
        return (24.dp * scaleFactor()).coerceIn(14.dp, 28.dp)
    }

    @Composable
    fun largeTileEndPadding(): Dp {
        return (16.dp * scaleFactor()).coerceIn(8.dp, 20.dp)
    }

    @Composable
    fun dividerLabelSpacing(): Dp {
        return (16.dp * scaleFactor()).coerceIn(8.dp, 20.dp)
    }

    @Composable
    fun iconDividerSpacing(): Dp {
        return (12.dp * scaleFactor()).coerceIn(6.dp, 14.dp)
    }

    @Composable
    fun tileLabelBlurWidth(): Dp {
        return (32.dp * scaleFactor()).coerceIn(16.dp, 36.dp)
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
    modifier: Modifier = Modifier,
    isVisible: () -> Boolean = { true },
    accessibilityUiState: AccessibilityUiState? = null,
    iconShape: RoundedCornerShape = RoundedCornerShape(CommonTileDefaults.InactiveCornerRadius),
    textScale: () -> Float = { 1f },
    toggleClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    val isDualTarget = toggleClick != null
    val dividerColor = AxTileDefaults.dividerColor(tileState)
    val focusBorderColor = MaterialTheme.colorScheme.secondary

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.padding(
            start = QSTileScaling.largeTileStartPadding(),
            end = QSTileScaling.largeTileEndPadding(),
        ),
    ) {
        val longPressLabel = longPressLabelSettings().takeIf { onLongClick != null }
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .thenIf(isDualTarget) {
                    Modifier
                        .borderOnFocus(color = focusBorderColor, iconShape.topEnd)
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
                size = { AxTileDefaults.LargeIconSize },
                modifier = Modifier,
            )
        }

        if (isDualTarget) {
            Spacer(modifier = Modifier.width(QSTileScaling.iconDividerSpacing()))
            Box(
                modifier = Modifier
                    .width(AxTileDefaults.DividerWidth)
                    .height(AxTileDefaults.DividerHeight)
                    .background(dividerColor)
            )
            Spacer(modifier = Modifier.width(QSTileScaling.dividerLabelSpacing()))
        } else {
            Spacer(modifier = Modifier.width(QSTileScaling.dividerLabelSpacing()))
        }

        LargeTileLabels(
            label = label,
            secondaryLabel = secondaryLabel,
            colors = colors,
            accessibilityUiState = accessibilityUiState,
            isVisible = isVisible,
            modifier = Modifier
                .weight(1f)
                .bounceScale(TransformOrigin(0f, .5f), textScale),
        )
    }
}
