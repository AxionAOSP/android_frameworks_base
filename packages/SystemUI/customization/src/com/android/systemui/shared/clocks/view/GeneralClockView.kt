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
import android.text.TextUtils
import android.util.AttributeSet
import androidx.compose.foundation.Canvas
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.android.systemui.customization.R
import com.android.systemui.shared.clocks.ClockSettingsRepository
import com.android.systemui.shared.clocks.extensions.scaledDimen
import com.android.systemui.shared.clocks.extensions.scaleRatio
import java.util.Locale
import kotlin.math.min

class GeneralClockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    defStyleRes: Int = 0
) : AxClockView(context, attrs, defStyleAttr, defStyleRes) {

    override val animationSpec: AxClockAnimationSpec = AxClockAnimationSpecs.General

    override fun getTag(): String =
        if (isLargeClock) "GeneralLargeClockView" else "GeneralClockView"

    private val digitResIds = intArrayOf(
        R.drawable.intervar_0, R.drawable.intervar_1, R.drawable.intervar_2,
        R.drawable.intervar_3, R.drawable.intervar_4, R.drawable.intervar_5,
        R.drawable.intervar_6, R.drawable.intervar_7, R.drawable.intervar_8,
        R.drawable.intervar_9
    )

    private val digitLightResIds = intArrayOf(
        R.drawable.intervar_0_light, R.drawable.intervar_1_light, R.drawable.intervar_2_light,
        R.drawable.intervar_3_light, R.drawable.intervar_4_light, R.drawable.intervar_5_light,
        R.drawable.intervar_6_light, R.drawable.intervar_7_light, R.drawable.intervar_8_light,
        R.drawable.intervar_9_light
    )

    private var bitmaps: Map<Char, Bitmap> = loadDigitBitmaps(digitResIds)
    private var lightBitmaps: Map<Char, Bitmap> = loadDigitBitmaps(digitLightResIds)

    override val clockHeightBase: Int
        get() {
            if (isLargeClock) return super.clockHeightBase
            val d = ContextCompat.getDrawable(context, digitResIds[0])
                ?: return super.clockHeightBase
            val density = context.resources.displayMetrics.density
            return (d.intrinsicHeight * scaleRatio + TEXT_AREA_DP * density * scaleRatio).toInt()
        }

    private val textPrimarySize = 28.sp
    private val textMajorSize = 20.sp

    override fun onDisplayMetricsChanged() {
        super.onDisplayMetricsChanged()
        reloadDigitBitmaps()
    }

    private fun reloadDigitBitmaps() {
        bitmaps = loadDigitBitmaps(digitResIds)
        lightBitmaps = loadDigitBitmaps(digitLightResIds)
    }

    private fun loadDigitBitmaps(resIds: IntArray): Map<Char, Bitmap> {
        val result = mutableMapOf<Char, Bitmap>()
        resIds.forEachIndexed { index, resId ->
            ContextCompat.getDrawable(context, resId)?.toBitmap()?.let { bmp ->
                result['0' + index] = bmp
            }
        }
        return result
    }

    @Composable
    override fun Content() {
        if (isLargeClock) {
            LargeContent()
        } else {
            SmallContent()
        }
    }

    @Composable
    private fun LargeContent() {
        val (time, date, isDoze, screenOff, regionDark, _, _, display) = rememberClockState()

        val largeScale = min(context.scaleRatio, MAX_TABLET_SCALE) * LARGE_SCALE_MULTIPLIER
        val digitSpacing = context.scaledDimen(R.dimen.large_clock_digit_spacing)
        val lineSpacing = context.scaledDimen(R.dimen.large_clock_line_spacing)
        val tintColor = tintColor(isDoze, screenOff, regionDark)

        val sampleBmp = remember { bitmaps['0'] }
        val digitH = (sampleBmp?.height ?: 0) * largeScale
        val canvasHeightDp = with(LocalDensity.current) {
            (digitH * 2 + lineSpacing).toDp()
        }
        Column(
            modifier = Modifier.fillMaxWidth().wrapContentHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(canvasHeightDp),
            ) {
                if (time.isEmpty() || !TextUtils.isDigitsOnly(time)) return@Canvas

                val (hours, minutes) = splitTimeLines(time)
                if (hours.isEmpty()) return@Canvas

                val sampleDigit = bitmaps['0'] ?: return@Canvas
                val dh = sampleDigit.height * largeScale

                fun lineWidth(digits: String): Float {
                    var w = 0f
                    digits.forEachIndexed { i, c ->
                        val bmp = bitmaps[c] ?: return@forEachIndexed
                        w += bmp.width * largeScale
                        if (i < digits.lastIndex) w += digitSpacing
                    }
                    return w
                }

                fun drawLine(digits: String, lineX: Float, lineY: Float) {
                    var x = lineX
                    digits.forEach { c ->
                        val bmp = bitmaps[c] ?: return@forEach
                        drawImage(
                            image = bmp.asImageBitmap(),
                            srcOffset = IntOffset.Zero,
                            srcSize = IntSize(bmp.width, bmp.height),
                            dstOffset = IntOffset(x.toInt(), lineY.toInt()),
                            dstSize = IntSize((bmp.width * largeScale).toInt(), (bmp.height * largeScale).toInt()),
                            colorFilter = ColorFilter.tint(tintColor, BlendMode.SrcIn),
                        )
                        x += bmp.width * largeScale + digitSpacing
                    }
                }

                val hoursW = lineWidth(hours)
                val minutesW = lineWidth(minutes)

                drawLine(hours, (size.width - hoursW) / 2f, 0f)
                drawLine(minutes, (size.width - minutesW) / 2f, dh + lineSpacing)
            }

            if (display !is DateDisplay.Hidden) {
                Spacer(modifier = Modifier.height(12.dp))
                EnhancedDateArea(
                    textColor = tintColor,
                    textSize = 16.sp,
                    iconSize = 18.dp,
                    rowArrangement = Arrangement.Center,
                )
            }
        }
    }

    @Composable
    private fun SmallContent() {
        val (time, date, isDoze, screenOff, regionDark, icon, tintIcon, display) = rememberClockState()

        val dynSizeScale = rememberSmallClockSizeScale()
        val scale = context.scaleRatio * dynSizeScale
        val paddingV = context.scaledDimen(R.dimen.clock_padding) * dynSizeScale
        val dotSz = context.scaledDimen(R.dimen.dot_small_size)
        val dotMgn = context.scaledDimen(R.dimen.dot_margin)

        val tintColor = tintColor(isDoze, screenOff, regionDark)
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
        val useLight = hasSpecialContent

        val sampleBmp = remember { bitmaps['0'] }
        val canvasHeight = remember(sampleBmp, scale) {
            ((sampleBmp?.height ?: 0) * scale).coerceAtLeast(1f)
        }
        val canvasHeightDp = with(LocalDensity.current) { canvasHeight.toDp() }

        val inverseModifier = inverseSizeScaleModifier()

        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = horizontalAlign,
        ) {
            if (display !is DateDisplay.Hidden) {
                Text(
                    text = dateStr,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = tintColor.copy(alpha = if (isDoze) 0.6f else 0.8f),
                        letterSpacing = 0.5.sp,
                    ),
                    modifier = Modifier
                        .padding(
                            start = if (isRightAligned) 0.dp else sidePadding,
                            end = if (isRightAligned) sidePadding else 0.dp,
                            top = 4.dp,
                            bottom = 4.dp,
                        )
                        .then(inverseModifier),
                )
            }

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(canvasHeightDp),
            ) {
                if (time.isEmpty() || !TextUtils.isDigitsOnly(time)) return@Canvas

                val bitmapMap = if (useLight) lightBitmaps else bitmaps

                var naturalWidth = 0f
                for ((i, char) in time.withIndex()) {
                    val bmp = bitmapMap[char] ?: continue
                    naturalWidth += bmp.width * scale
                    if (time.length - i == 3) naturalWidth += 2 * paddingV
                }
                if (naturalWidth <= 0f) return@Canvas

                val fitScale = fitToWidth(naturalWidth)
                val drawScale = scale * fitScale
                val drawPaddingV = paddingV * fitScale
                val totalWidth = naturalWidth * fitScale

                val startX = when {
                    isLeftAligned -> clockPaddingStart
                    isRightAligned -> size.width - clockPaddingStart - totalWidth
                    else -> (size.width - totalWidth) / 2f
                }
                var x = startX

                for ((i, char) in time.withIndex()) {
                    val bmp = bitmapMap[char] ?: continue
                    val yOffset = (size.height - bmp.height * drawScale) / 2f

                    drawImage(
                        image = bmp.asImageBitmap(),
                        srcOffset = IntOffset.Zero,
                        srcSize = IntSize(bmp.width, bmp.height),
                        dstOffset = IntOffset(x.toInt(), yOffset.toInt()),
                        dstSize = IntSize((bmp.width * drawScale).toInt(), (bmp.height * drawScale).toInt()),
                        colorFilter = ColorFilter.tint(tintColor, BlendMode.SrcIn),
                    )
                    x += bmp.width * drawScale

                    if (time.length - i == 3) {
                        val centerX = x + drawPaddingV
                        val centerY = size.height / 2f
                        val dotRadius = dotSz * fitScale / 2
                        val topDotY = centerY - (dotMgn * fitScale / 2 + dotRadius)
                        val bottomDotY = centerY + (dotMgn * fitScale / 2 + dotRadius)
                        val adjDotSz = dotSz * fitScale
                        drawOval(
                            color = tintColor,
                            topLeft = Offset(centerX - dotRadius, topDotY - dotRadius),
                            size = Size(adjDotSz, adjDotSz),
                        )
                        drawOval(
                            color = tintColor,
                            topLeft = Offset(centerX - dotRadius, bottomDotY - dotRadius),
                            size = Size(adjDotSz, adjDotSz),
                        )
                        x += 2 * drawPaddingV
                    }
                }
            }

            BottomArea(
                placeholderText = placeholderText,
                hasSpecialContent = hasSpecialContent,
                text = bottomText,
                icon = icon,
                tintIcon = tintIcon,
                textColor = tintColor,
                startPadding = sidePadding,
                horizontalAlign = horizontalAlign,
                modifier = inverseModifier,
            )
        }
    }

    @Composable
    private fun BottomArea(
        placeholderText: String?,
        hasSpecialContent: Boolean,
        text: String,
        icon: Bitmap?,
        tintIcon: Boolean,
        textColor: Color,
        startPadding: androidx.compose.ui.unit.Dp,
        horizontalAlign: Alignment.Horizontal,
        modifier: Modifier = Modifier,
    ) {
        Column(
            horizontalAlignment = horizontalAlign,
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight()
                .padding(
                    start = if (isRightAligned) 0.dp else startPadding,
                    end = if (isRightAligned) startPadding else 0.dp,
                    bottom = 4.dp,
                )
                .then(modifier),
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
                        text = text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(
                            fontSize = textMajorSize,
                            fontWeight = FontWeight.Medium,
                            color = textColor,
                        ),
                    )
                }
            } else if (placeholderText != null) {
                val isJpLang = Locale.getDefault().language == "ja"
                val fontName = if (isJpLang) "NDot77JPExtended" else "nothingdot"
                val fontFamily = FontFamily(Typeface.create(fontName, Typeface.NORMAL))
                Text(
                    text = placeholderText,
                    maxLines = 1,
                    style = TextStyle(
                        fontSize = textPrimarySize,
                        fontWeight = FontWeight.Normal,
                        fontFamily = fontFamily,
                        color = textColor,
                    ),
                )
            }
        }
    }

    companion object {
        private const val MAX_TABLET_SCALE = 1.2f
        private const val LARGE_SCALE_MULTIPLIER = 1.8f
        private const val TEXT_AREA_DP = 56f
    }
}
