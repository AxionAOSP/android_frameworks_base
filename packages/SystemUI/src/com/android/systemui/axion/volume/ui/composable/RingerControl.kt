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
@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.android.systemui.axion.volume.ui.composable

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.axion.blur.AxBlurSurface
import com.android.systemui.axion.volume.domain.model.AxionRingerMode

@Composable
fun RingerCircleButton(
    ringerMode: AxionRingerMode,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AxBlurSurface(
        modifier = modifier
            .size(RingerCircleSize)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        shape = CircleShape,
        cornerRadius = RingerCircleSize / 2f,
        contentAlignment = Alignment.Center,
    ) {
        Crossfade(targetState = ringerMode, label = "ringerIcon") { mode ->
            Box(
                modifier = Modifier
                    .size(RingerRowIconSize)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(mode.iconRes),
                    contentDescription = mode.label,
                    modifier = Modifier.size(SliderIconSize),
                    tint = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
    }
}

@Composable
fun RingerRow(
    ringerMode: AxionRingerMode,
    supportedModes: List<AxionRingerMode>,
    panelWidth: Dp,
    onModeSelected: (AxionRingerMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val modeCount = supportedModes.size.coerceAtLeast(1)
    val activeIndex = supportedModes.indexOf(ringerMode).coerceAtLeast(0)
    val indicatorX = ringerIndicatorX(panelWidth, modeCount, activeIndex)

    val motionScheme = MaterialTheme.motionScheme
    val animatable = remember { Animatable(RingerRowOuterPadding.value) }

    LaunchedEffect(Unit) {
        animatable.snapTo(RingerRowOuterPadding.value)
        animatable.animateTo(indicatorX.value, motionScheme.defaultSpatialSpec())
    }

    LaunchedEffect(indicatorX) {
        animatable.animateTo(indicatorX.value, motionScheme.defaultSpatialSpec())
    }

    val animatedX = animatable.value

    val primary = MaterialTheme.colorScheme.primary
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    val inactiveContent = volumePanelSecondaryContentColor()

    Box(modifier = modifier.size(width = panelWidth, height = RingerRowHeight)) {
        Box(
            modifier = Modifier
                .size(RingerRowIconSize)
                .offset(x = animatedX.dp, y = RingerRowOuterPadding)
                .clip(CircleShape)
                .background(primary)
        )

        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = RingerRowOuterPadding, vertical = RingerRowOuterPadding),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (modeCount >= 1) {
                RingerIconSlot(
                    mode = supportedModes[0],
                    isActive = activeIndex == 0,
                    activeColor = onPrimary,
                    inactiveColor = inactiveContent,
                    onClick = { onModeSelected(supportedModes[0]) }
                )
            }

            if (modeCount >= 3) {
                val mid = modeCount / 2
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    RingerIconSlot(
                        mode = supportedModes[mid],
                        isActive = activeIndex == mid,
                        activeColor = onPrimary,
                        inactiveColor = inactiveContent,
                        onClick = { onModeSelected(supportedModes[mid]) }
                    )
                }
                RingerIconSlot(
                    mode = supportedModes[modeCount - 1],
                    isActive = activeIndex == modeCount - 1,
                    activeColor = onPrimary,
                    inactiveColor = inactiveContent,
                    onClick = { onModeSelected(supportedModes[modeCount - 1]) }
                )
            } else if (modeCount == 2) {
                Box(modifier = Modifier.weight(1f))
                RingerIconSlot(
                    mode = supportedModes[1],
                    isActive = activeIndex == 1,
                    activeColor = onPrimary,
                    inactiveColor = inactiveContent,
                    onClick = { onModeSelected(supportedModes[1]) }
                )
            }

        }
    }
}

@Composable
private fun RingerIconSlot(
    mode: AxionRingerMode,
    isActive: Boolean,
    activeColor: Color,
    inactiveColor: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(RingerRowIconSize)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(mode.iconRes),
            contentDescription = mode.label,
            modifier = Modifier.size(SliderIconSize),
            tint = if (isActive) activeColor else inactiveColor
        )
    }
}

private fun ringerIndicatorX(panelWidth: Dp, modeCount: Int, activeIndex: Int): Dp {
    val outerPad = RingerRowOuterPadding
    val iconSize = RingerRowIconSize
    if (modeCount <= 1) return outerPad
    return when (activeIndex) {
        0 -> outerPad
        modeCount - 1 -> panelWidth - outerPad - iconSize
        else -> {
            val fillLeft = outerPad + iconSize
            val fillRight = panelWidth - outerPad - iconSize
            (fillLeft + fillRight) / 2 - iconSize / 2
        }
    }
}
