/*
 * Copyright 2025 AxionOS
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
package com.android.systemui.lockscreen

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.gestures.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.*
import com.android.systemui.res.R

@Composable
fun WidgetSmall(
    spec: WidgetSpec,
    bgColor: Color,
    border: Modifier,
    iconTint: Color,
    theme: Theme,
    dimens: Dimens,
    ctrl: LockScreenWidgetsController,
    active: Boolean
) {
    Box(
        modifier = Modifier
            .size(dimens.widgetSizeDp)
            .background(bgColor, CircleShape)
            .clip(CircleShape)
            .then(border)
            .combinedClickable(
                onClick = { spec.action.onClick(ctrl) },
                onLongClick = spec.action.onLongClick?.let { { it(ctrl) } }
            ),
        contentAlignment = Alignment.Center
    ) {
        WidgetIcon(spec, iconTint, theme, active, dimens, ctrl)
    }
}

@Composable
fun WidgetPill(
    spec: WidgetSpec,
    bgColor: Color,
    border: Modifier,
    iconTint: Color,
    theme: Theme,
    dimens: Dimens,
    ctrl: LockScreenWidgetsController,
    active: Boolean,
    isDozing: Boolean
) {
    if (spec.action == WidgetAction.RINGER) {
        LockscreenRingerSliderWidget(spec, border, theme, dimens, ctrl, isDozing)
        return
    }
    
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dimens.spacingDp),
        modifier = Modifier
            .width((dimens.widgetSizeDp * spec.type.span) + (dimens.spacingDp * spec.type.span))
            .height(dimens.widgetSizeDp)
            .background(bgColor, CircleShape)
            .clip(CircleShape)
            .then(border)
            .combinedClickable(
                onClick = { spec.action.onClick(ctrl) },
                onLongClick = spec.action.onLongClick?.let { { it(ctrl) } }
            )
            .padding(horizontal = dimens.labelStartDp)
    ) {
        WidgetIcon(spec, iconTint, theme, active, dimens, ctrl)
        WidgetLabel(
            action = spec.action,
            tintColor = iconTint,
            modifier = Modifier.weight(1f).basicMarquee(),
            active = active,
            ctrl = ctrl
        )
    }
}

@Composable
fun WidgetIcon(
    spec: WidgetSpec,
    tintColor: Color,
    theme: Theme,
    active: Boolean,
    dimens: Dimens,
    ctrl: LockScreenWidgetsController
) {
    val iconVector = WidgetIcon(spec.action, active, ctrl) 
    Icon(
        imageVector = iconVector,
        contentDescription = stringResource(spec.action.labelRes),
        tint = tintColor,
        modifier = Modifier.size(dimens.iconSizeDp)
    )
}

@Composable
fun WidgetLabel(
    action: WidgetAction,
    tintColor: Color,
    modifier: Modifier,
    active: Boolean,
    ctrl: LockScreenWidgetsController
) {
    val text = action.label(ctrl, active)
    Text(
        text = text,
        color = tintColor,
        maxLines = 1,
        modifier = modifier
    )
}
