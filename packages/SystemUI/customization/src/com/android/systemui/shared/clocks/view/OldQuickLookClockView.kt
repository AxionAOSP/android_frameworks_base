/*
 * Copyright (C) 2025 AxionOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.shared.clocks.view

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Typeface
import android.util.AttributeSet
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.systemui.customization.clocks.R as clocksR
import com.android.systemui.shared.clocks.ClockSettingsRepository
import java.util.Locale

class OldQuickLookClockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    defStyleRes: Int = 0
) : AxClockView(context, attrs, defStyleAttr, defStyleRes) {

    override fun getTag(): String = "OLDQuickLookClockView"

    private val primaryTextSize = 80.sp
    private val secondaryTextSize = 18.sp
    private val dateTextSize = 14.sp

    override val clockHeightBase: Int
        get() {
            if (isLargeClock) return super.clockHeightBase
            val density = context.resources.displayMetrics.density
            val scaledDensity = context.resources.displayMetrics.scaledDensity
            val timePx = PRIMARY_TEXT_SP * scaledDensity * scaleRatio
            val infoPx = INFO_AREA_DP * density * scaleRatio
            return (timePx + infoPx).toInt()
        }

    @Composable
    override fun Content() {
        if (isLargeClock) LargeContent() else SmallContent()
    }

    @Composable
    private fun SmallContent() {
        val (time, date, isDoze, screenOff, regionDark, icon, tintIcon, display) = rememberClockState()

        val dynSizeScale = rememberSmallClockSizeScale()
        val textColor = tintColor(isDoze, screenOff, regionDark)
        val horizontalAlign = when {
            isLeftAligned -> Alignment.Start
            isRightAligned -> Alignment.End
            else -> Alignment.CenterHorizontally
        }
        val sidePadding = if (isSideAligned) {
            (clockPaddingStart / context.resources.displayMetrics.density).dp
        } else {
            0.dp
        }

        val isJpLang = Locale.getDefault().language == "ja"
        val infoFontName = if (isJpLang) "NDot77JPExtended" else "nothingdot"
        val infoFontFamily = FontFamily(Typeface.create(infoFontName, Typeface.NORMAL))
        val clockFontFamily = FontFamily(Typeface.create("nothingdot57", Typeface.NORMAL))

        val placeholderText = if (display is DateDisplay.Hidden) {
            null
        } else {
            config?.placeholderTextRes?.let { context.getString(it) }
        }
        val hasSpecialContent = display !is DateDisplay.DateOnly && display !is DateDisplay.Hidden
        val bottomText = when (display) {
            is DateDisplay.Weather -> (display as DateDisplay.Weather).temp
            is DateDisplay.Hidden -> ""
            else -> date
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = if (isRightAligned) 0.dp else sidePadding,
                    end = if (isRightAligned) sidePadding else 0.dp,
                ),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = horizontalAlign,
        ) {
            Text(
                text = time,
                maxLines = 1,
                style = TextStyle(
                    fontSize = primaryTextSize * dynSizeScale,
                    fontWeight = FontWeight.Bold,
                    fontFamily = clockFontFamily,
                    color = textColor,
                ),
            )

            if (display !is DateDisplay.Hidden) {
                Text(
                    text = dateStr,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(
                        fontSize = dateTextSize,
                        fontWeight = FontWeight.Normal,
                        fontFamily = infoFontFamily,
                        color = textColor,
                    ),
                )
            }

            Column(
                horizontalAlignment = horizontalAlign,
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight()
                    .padding(top = 6.dp),
            ) {
                if (hasSpecialContent) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        icon?.let {
                            Image(
                                bitmap = it.asImageBitmap(),
                                contentDescription = null,
                                colorFilter = if (tintIcon) ColorFilter.tint(textColor) else null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(modifier = Modifier.width(7.dp))
                        }
                        Text(
                            text = bottomText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = TextStyle(
                                fontSize = secondaryTextSize,
                                fontWeight = FontWeight.Normal,
                                fontFamily = infoFontFamily,
                                color = textColor,
                            ),
                        )
                    }
                } else if (placeholderText != null) {
                    Text(
                        text = placeholderText,
                        maxLines = 1,
                        style = TextStyle(
                            fontSize = secondaryTextSize,
                            fontWeight = FontWeight.Normal,
                            fontFamily = infoFontFamily,
                            color = textColor,
                        ),
                    )
                }
            }
        }
    }

    @Composable
    private fun LargeContent() {
        val (time, _, isDoze, screenOff, regionDark, _, _, display) = rememberClockState()

        val textColor = tintColor(isDoze, screenOff, regionDark)
        val clockFontFamily = FontFamily(Typeface.create("nothingdot57", Typeface.NORMAL))

        val (hours, minutes) = splitTimeLines(time)

        val largeFontSize = with(LocalDensity.current) {
            context.resources.getDimensionPixelSize(
                clocksR.dimen.large_clock_text_size
            ).toSp()
        }

        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                    Text(
                        text = hours,
                        maxLines = 1,
                        style = TextStyle(
                            fontSize = largeFontSize,
                            fontWeight = FontWeight.Bold,
                            fontFamily = clockFontFamily,
                            color = textColor,
                            lineHeight = largeFontSize,
                        ),
                    )
                    Text(
                        text = minutes,
                        maxLines = 1,
                        style = TextStyle(
                            fontSize = largeFontSize,
                            fontWeight = FontWeight.Bold,
                            fontFamily = clockFontFamily,
                            color = textColor,
                            lineHeight = largeFontSize,
                        ),
                    )
                }

                if (display !is DateDisplay.Hidden) {
                    Spacer(modifier = Modifier.height(16.dp))
                    EnhancedDateArea(
                        textColor = textColor,
                        textSize = 16.sp,
                        iconSize = 18.dp,
                        rowArrangement = Arrangement.Center,
                    )
                }
            }
        }
    }

    companion object {
        private const val PRIMARY_TEXT_SP = 80f
        private const val INFO_AREA_DP = 60f
    }
}
