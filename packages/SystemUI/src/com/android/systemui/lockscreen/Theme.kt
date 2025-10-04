/*
 * Copyright (C) 2025 The AxionAOSP Project
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

import android.content.res.Configuration.UI_MODE_NIGHT_MASK
import android.content.res.Configuration.UI_MODE_NIGHT_YES

import android.content.Context
import android.content.res.Resources
import android.content.res.ColorStateList
import android.graphics.Color
import androidx.core.content.ContextCompat
import com.android.systemui.res.R
import androidx.compose.foundation.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.*
import androidx.compose.ui.unit.*
import kotlin.math.min

class Theme(val night: Boolean) {

    val n50 @Composable get() = colorResource(android.R.color.system_neutral1_50)
    val n900 @Composable get() = colorResource(android.R.color.system_neutral1_900)
    val a100 @Composable get() = colorResource(android.R.color.system_accent1_100)
    val a600 @Composable get() = colorResource(android.R.color.system_accent1_600)

    val activeBg @Composable get() = if (night) a100 else a600
    val neutralBg @Composable get() = if (night) n900 else n50
    val activeIcon @Composable get() = if (night) a600 else a100
    val neutralIcon @Composable get() = if (night) n50 else n900
}

@Composable
fun rememberTheme(): Theme {
    val night = isSystemInDarkTheme()
    return remember(night) { Theme(night) }
}

@Composable
fun WidgetIcon(action: WidgetAction, isActive: Boolean): ImageVector {
    return when (action) {
        WidgetAction.WIFI ->
            if (isActive) Icons.Filled.Wifi else Icons.Filled.WifiOff

        WidgetAction.DATA ->
            if (isActive) Icons.Filled.SignalCellularAlt else Icons.Filled.SignalCellularOff

        WidgetAction.BLUETOOTH ->
            if (isActive) Icons.Filled.Bluetooth else Icons.Filled.BluetoothDisabled

        WidgetAction.TORCH ->
            if (isActive) Icons.Filled.FlashlightOn else Icons.Filled.FlashlightOff

        WidgetAction.RINGER ->
            if (isActive) Icons.Filled.Vibration else Icons.Filled.VolumeUp

        WidgetAction.HOTSPOT ->
            if (isActive) Icons.Filled.Wifi else Icons.Filled.WifiOff
    }
}

class Dimens(private val ctx: Context) {

    val res: Resources get() = ctx.resources

    val CIRCLE_SIZE = R.dimen.kg_widget_circle_size
    val LABEL_START = R.dimen.kg_widgets_label_start_padding
    val ICON_SIZE = R.dimen.kg_widgets_icon_size
    val SPACING = R.dimen.kg_widgets_spacing
    val DOZE_STROKE = R.dimen.kg_widgets_doze_stroke
    val TOP_PADDING = R.dimen.kg_widgets_top_padding

    val spacingPx get() = ratioResPx(SPACING)
    val iconSizePx get() = ratioResPx(ICON_SIZE)
    val labelStartPx get() = ratioResPx(LABEL_START)
    val dozeStrokePx get() = ratioResPx(DOZE_STROKE)
    val topPaddingPx get() = ratioResPx(TOP_PADDING)
    val widgetSizePx get() = ratioResPx(CIRCLE_SIZE)
    val hostHeightPx get() = widgetSizePx + topPaddingPx

    val hostHeightDp get() = hostHeightPx.toDp()
    val widgetSizeDp get() = widgetSizePx.toDp()
    val spacingDp get() = spacingPx.toDp()
    val iconSizeDp get() = iconSizePx.toDp()
    val labelStartDp get() = labelStartPx.toDp()
    val dozeStrokeDp get() = dozeStrokePx.toDp()
    val topPaddingDp get() = topPaddingPx.toDp()

    private fun ratioResPx(r: Int): Int = (res.getDimensionPixelSize(r) * ratio()).toInt()

    private fun ratio(): Float {
        val d = res.displayMetrics
        val sw = min(d.widthPixels, d.heightPixels) / d.density
        return sw / 420f
    }

    private fun Int.toDp(): Dp = (this / res.displayMetrics.density).dp
}
