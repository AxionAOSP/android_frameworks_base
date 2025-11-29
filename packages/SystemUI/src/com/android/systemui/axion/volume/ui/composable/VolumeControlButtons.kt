/*
 * Copyright (C) 2025 Axion OS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.axion.volume.ui.composable

import androidx.compose.*
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.*
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.*
import com.android.systemui.axion.volume.ui.viewmodel.*

@Composable
fun ExpandableCaptionButton(
    isExpanded: Boolean,
    isLeftSide: Boolean,
    captionsAvailable: Boolean,
    captionsEnabled: Boolean,
    viewModel: AxionVolumeDialogViewModel,
    buttonSize: Dp = 48.dp
) {
    val isCollapsed = !isExpanded
    val showCaption = isExpanded && captionsAvailable

    val targetContainerColor = when {
        isCollapsed -> Color.Transparent
        showCaption -> MaterialTheme.colorScheme.secondaryContainer
        else -> Color.Transparent
    }

    val containerColor by animateColorAsState(targetContainerColor, label = "btnContainer")

    val targetContentColor = when {
        isCollapsed -> MaterialTheme.colorScheme.onSurface
        showCaption -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> Color.Transparent
    }

    val contentColor by animateColorAsState(targetContentColor, label = "btnContent")

    Surface(
        modifier = Modifier.size(buttonSize),
        shape = CircleShape,
        color = containerColor,
        onClick = {
            if (isCollapsed) {
                viewModel.toggleExpanded()
            } else if (showCaption) {
                viewModel.toggleCaptions()
            }
        },
        enabled = isCollapsed || showCaption
    ) {
        Box(contentAlignment = Alignment.Center) {
            Crossfade(
                targetState = isCollapsed,
                label = "iconSwap",
                modifier = Modifier.fillMaxSize(),
                animationSpec = tween(200)
            ) { collapsed ->
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    if (collapsed) {
                        Icon(
                            imageVector = if (isLeftSide) Icons.AutoMirrored.Filled.KeyboardArrowRight
                            else Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                            contentDescription = "Expand",
                            tint = contentColor,
                            modifier = Modifier.size(32.dp)
                        )
                    } else if (showCaption) {
                        val icon = if (captionsEnabled) Icons.Filled.ClosedCaption else Icons.Filled.ClosedCaptionDisabled
                        val desc = if (captionsEnabled) "Captions on" else "Captions off"

                        Icon(
                            imageVector = icon,
                            contentDescription = desc,
                            tint = contentColor,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ControlTextButton(
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier.fillMaxHeight().fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp)
    ) {
        Text(
            text = label,
            maxLines = 1,
            style = MaterialTheme.typography.labelLarge
        )
    }
}

@Composable
private fun VolumeTabRow(
    showingAppVolumes: Boolean,
    onSystemClick: () -> Unit,
    onAppsClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val containerColor = MaterialTheme.colorScheme.surfaceVariant
    val contentColor = MaterialTheme.colorScheme.onSurfaceVariant
    val activeContainerColor = MaterialTheme.colorScheme.primary
    val activeContentColor = MaterialTheme.colorScheme.onPrimary

    Box(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(24.dp))
            .background(containerColor)
            .padding(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (!showingAppVolumes) activeContainerColor else Color.Transparent)
                    .clickable(onClick = onSystemClick),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "System",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (!showingAppVolumes) activeContentColor else contentColor,
                    maxLines = 1
                )
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (showingAppVolumes) activeContainerColor else Color.Transparent)
                    .clickable(onClick = onAppsClick),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Apps",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (showingAppVolumes) activeContentColor else contentColor,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun VolumeFooterButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier.fillMaxHeight().fillMaxWidth(),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
        ),
        contentPadding = PaddingValues(horizontal = 12.dp)
    ) {
        Text(
            text = text,
            maxLines = 1,
            style = MaterialTheme.typography.labelMedium
        )
    }
}

@Composable
fun VolumeFooterRow(
    isLeftSide: Boolean,
    buttonSize: Dp = 48.dp,
    expandButton: @Composable () -> Unit,
    hasAppVolumes: Boolean,
    showingAppVolumes: Boolean,
    onSystemClick: () -> Unit,
    onAppsClick: () -> Unit,
    onSeeMoreClick: () -> Unit
) {
    val buttonContent: @Composable RowScope.() -> Unit = {
        if (hasAppVolumes) {
            VolumeTabRow(
                showingAppVolumes = showingAppVolumes,
                onSystemClick = onSystemClick,
                onAppsClick = onAppsClick,
                modifier = Modifier.weight(1f)
            )
            
            FilledTonalButton(
                onClick = onSeeMoreClick,
                modifier = Modifier.size(buttonSize),
                shape = CircleShape,
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                ),
                contentPadding = PaddingValues(0.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = "See more",
                    modifier = Modifier.size(20.dp)
                )
            }
        } else {
            VolumeFooterButton(
                text = "See more",
                onClick = onSeeMoreClick,
                modifier = Modifier.weight(1f)
            )
        }
    }
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(buttonSize),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (isLeftSide) {
            expandButton()
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                content = buttonContent
            )
        } else {
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                content = buttonContent
            )
            expandButton()
        }
    }
}
