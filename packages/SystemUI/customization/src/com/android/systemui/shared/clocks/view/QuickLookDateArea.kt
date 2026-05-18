/*
 * Copyright (C) 2025-2026 AxionOS Project
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

package com.android.systemui.shared.clocks.view

import android.app.PendingIntent
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private const val TAG = "QuickLookDateArea"

@Composable
fun QuickLookDateArea(
    modifier: Modifier = Modifier,
    display: DateDisplay,
    dateStr: String,
    sizeScale: Float,
    textColor: Color,
    textSize: TextUnit = 18.sp,
    fontFamily: FontFamily,
    fontWeight: FontWeight = FontWeight.Medium,
    letterSpacing: TextUnit = 0.sp,
    iconSize: Dp = 16.dp,
    uppercase: Boolean = false,
    rowArrangement: Arrangement.Horizontal = Arrangement.Center,
) {
    if (display is DateDisplay.Hidden) return

    val style = TextStyle(
        fontSize = textSize,
        fontWeight = fontWeight,
        fontFamily = fontFamily,
        color = textColor,
        letterSpacing = letterSpacing,
        lineHeight = 24.sp,
    )

    val inverseScale = if (sizeScale > 0f) 1f / sizeScale else 1f
    val tapModifier = display.tapAction?.let { action ->
        Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
        ) { fireTapAction(action) }
    } ?: Modifier

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = rowArrangement,
        modifier = modifier
            .widthIn(max = 280.dp)
            .graphicsLayer {
                scaleX = inverseScale
                scaleY = inverseScale
            }
            .then(tapModifier),
    ) {
        if (display is DateDisplay.Weather) {
            val dateText = if (uppercase) dateStr.uppercase() else dateStr
            Text(text = dateText, maxLines = 1, style = style, modifier = Modifier.basicMarquee())
            display.icon?.let {
                Spacer(modifier = Modifier.width(6.dp))
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = null,
                    colorFilter = if (display.tintIcon) ColorFilter.tint(textColor) else null,
                    modifier = Modifier.size(iconSize),
                )
            }
            if (display.temp.isNotEmpty()) {
                Spacer(modifier = Modifier.width(6.dp))
                val tempText = if (uppercase) display.temp.uppercase() else display.temp
                Text(text = tempText, maxLines = 1, style = style)
            }
        } else {
            display.icon?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = null,
                    colorFilter = if (display.tintIcon) ColorFilter.tint(textColor) else null,
                    modifier = Modifier.size(iconSize),
                )
                Spacer(modifier = Modifier.width(6.dp))
            }
            val text = when (display) {
                is DateDisplay.IconText -> display.text
                is DateDisplay.DateOnly -> dateStr
                else -> dateStr
            }
            val displayText = if (uppercase) text.uppercase() else text
            Text(
                text = displayText,
                maxLines = 1,
                style = style,
                modifier = Modifier.basicMarquee(),
            )
        }
    }
}

internal fun fireTapAction(action: PendingIntent) {
    try {
        action.send()
    } catch (e: PendingIntent.CanceledException) {
        Log.w(TAG, "Tap action cancelled", e)
    }
}
