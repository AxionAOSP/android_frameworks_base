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
package com.android.systemui.lockscreen

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.systemui.common.ringer.RingerSliderDimens
import com.android.systemui.common.ringer.RingerSliderTheme

class LockscreenRingerTheme(
    private val theme: Theme,
    private val dimens: Dimens
) : RingerSliderTheme {
    override val activeBg: Color @Composable get() = theme.activeBg
    override val neutralBg: Color @Composable get() = theme.neutralBg
    override val activeIcon: Color @Composable get() = theme.activeIcon
    override val neutralIcon: Color @Composable get() = theme.neutralIcon
    override val dozeStroke: Dp get() = dimens.dozeStrokeDp
}

class LockscreenRingerDimens(
    private val spec: WidgetSpec,
    private val dimens: Dimens
) : RingerSliderDimens {
    override val totalWidth: Dp = 
        (dimens.widgetSizeDp * spec.type.span) + (dimens.spacingDp * spec.type.span)
    override val thumbSize: Dp = dimens.widgetSizeDp
    override val iconSize: Dp = dimens.iconSizeDp
    override val thumbPadding: Dp = 6.dp
    override val dotSize: Dp = 8.dp
}
