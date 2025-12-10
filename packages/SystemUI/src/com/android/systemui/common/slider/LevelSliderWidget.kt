/*
 * Copyright (C) 2025 AxionOS
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
package com.android.systemui.common.slider

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.gestures.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.systemui.theme.UiStyleProvider
import com.android.systemui.util.AxColorScheme

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun LevelSliderWidget(
    interactor: LevelSliderInteractor,
    theme: LevelSliderTheme,
    dimens: LevelSliderDimens,
    modifier: Modifier = Modifier,
    isDozing: Boolean = false,
    border: Modifier = Modifier
) {
    val style = UiStyleProvider.rememberCurrentStyle()
    val tileShape = style.qsTileShape()
    var level by remember { mutableFloatStateOf(interactor.getCurrentLevel()) }
    var dragLevel by remember { mutableFloatStateOf(level) }
    var isDragging by remember { mutableStateOf(false) }
    var isEnabled by remember { mutableStateOf(interactor.isActive()) }

    LaunchedEffect(Unit) {
        interactor.level.collect { newLevel ->
            level = newLevel
            if (!isDragging) {
                dragLevel = newLevel
            }
        }
    }

    LaunchedEffect(Unit) {
        interactor.isActiveFlow.collect { active ->
            isEnabled = active
        }
    }

    val animatedLevel by animateFloatAsState(
        targetValue = if (isDragging) dragLevel else level,
        animationSpec = tween(200, easing = LinearOutSlowInEasing),
        label = "level_animation"
    )

    val density = LocalDensity.current

    val activeContentColor = if (isDozing) Color.White else AxColorScheme.onPrimary
    val progressFillColor = if (isDozing || !isEnabled) Color.Transparent else AxColorScheme.primary
    val trackBgColor = if (isDozing) Color.Transparent else AxColorScheme.primarySurface
    val disabledContentColor = if (isDozing) Color.Transparent else AxColorScheme.onSurface 
    val disabledBgColor = if (isDozing) Color.Transparent else AxColorScheme.secondary

    val animatedTrackColor by animateColorAsState(
        targetValue = if (!isEnabled) disabledBgColor else trackBgColor,
        animationSpec = tween(durationMillis = 300, easing = LinearOutSlowInEasing),
        label = "track_color_animation"
    )

    val animatedContentColor by animateColorAsState(
        targetValue = if (!isEnabled) disabledContentColor else activeContentColor,
        animationSpec = tween(durationMillis = 300, easing = LinearOutSlowInEasing),
        label = "content_color_animation"
    )

    val enabledScale by animateFloatAsState(
        targetValue = if (isEnabled) 1.05f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "enabled_scale"
    )

    BoxWithConstraints(
        modifier = modifier
            .height(dimens.height)
            .scale(enabledScale)
            .clip(tileShape)
            .then(if (isDozing) Modifier.border(theme.dozeStroke, Color.White, tileShape) else border)
            .then(
                if (isEnabled) Modifier.border(2.dp, AxColorScheme.primary.copy(alpha = 0.6f), tileShape)
                else Modifier
            )
            .pointerInput(Unit) {
                detectTapGestures { tapOffset ->
                    interactor.onTap(!interactor.isActive())
                }
            }
            .pointerInput(isEnabled) {
                if (isEnabled) {
                    detectHorizontalDragGestures(
                        onDragStart = { isDragging = true },
                        onDragEnd = {
                            isDragging = false
                        },
                        onDragCancel = { isDragging = false }
                    ) { change, dragAmount ->
                        change.consume()
                        val delta = dragAmount / size.width
                        dragLevel = (dragLevel + delta).coerceIn(0f, 1f)
                        interactor.setLevel(dragLevel)
                    }
                }
            }
    ) {
        val boxWidthPx = with(density) { maxWidth.toPx() }
        val fillWidth = boxWidthPx * animatedLevel

        Box(Modifier.fillMaxSize().background(animatedTrackColor, tileShape).clip(tileShape))

        Canvas(Modifier.fillMaxSize().clip(tileShape)) {
            drawRoundRect(
                color = progressFillColor,
                size = Size(fillWidth, size.height),
                cornerRadius = CornerRadius(size.height / 2)
            )
        }

        val icon = interactor.getIcon(animatedLevel)
        val label = interactor.getLabel(animatedLevel)

        Row(
            modifier = Modifier
                .fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.width(24.dp))

            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = animatedContentColor,
                modifier = Modifier.size(dimens.iconSize)
            )

            Spacer(Modifier.width(14.dp))

            Text(
                text = label,
                color = animatedContentColor,
                fontSize = 14.sp,
                fontWeight = if (isDragging) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 24.dp)
                    .basicMarquee(iterations = 1, initialDelayMillis = 2000)
            )
        }
    }
}
