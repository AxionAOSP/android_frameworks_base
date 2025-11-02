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
package com.android.systemui.qs.tiles.impl.ringer

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.systemui.common.ringer.RingerSliderDimens
import com.android.systemui.common.ringer.RingerSliderTheme
import com.android.systemui.util.AxColorScheme

class QSTileRingerTheme(
) : RingerSliderTheme {
    override val activeBg: Color
        @Composable get() = AxColorScheme.primary
    
    override val neutralBg: Color
        @Composable get() = AxColorScheme.secondary
    
    override val activeIcon: Color
        @Composable get() = AxColorScheme.onPrimary
    
    override val neutralIcon: Color
        @Composable get() = AxColorScheme.onSurface
    
    override val dozeStroke: Dp = 2.dp
}

class QSTileRingerDimens(
    private val tileHeight: Dp
) : RingerSliderDimens {
    override val totalWidth: Dp? = null
    override val thumbSize: Dp = tileHeight
    override val iconSize: Dp = 24.dp
    override val thumbPadding: Dp = 8.dp
    override val dotSize: Dp = 6.dp
}
