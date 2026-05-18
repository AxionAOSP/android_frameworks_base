/*
 * Copyright (C) 2026 AxionOS Project
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

import android.content.Context
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.util.AttributeSet
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.systemui.shared.clocks.ClockEditScaleGeometry
import com.android.systemui.shared.clocks.ClockSettingsRepository
import kotlin.math.min

private const val OPLUS_CLOCK_EDIT_FRAME_HORIZONTAL_PADDING_DP = 32f
private const val OPLUS_CLASSIC_TARGET_WIDTH_DP = 300f
private const val OPLUS_CLASSIC_STACKED_TARGET_WIDTH_DP = 220f
private const val OPLUS_BIG_TARGET_WIDTH_DP = 330f
private const val OPLUS_BIG_TARGET_HEIGHT_DP = 300f
private const val OPLUS_BIG_WIDE_TARGET_WIDTH_DP = 512f
private const val OPLUS_BIG_WIDE_BASE_SCALE = 0.72f
private const val OPLUS_BIG_WIDE_EXPAND_RATE = 0.7f
private const val OPLUS_BIG_WIDE_MIN_VISIBLE_SCALE = 0.36f
private const val OPLUS_BIG_WIDE_TARGET_HEIGHT_DP = 300f
private const val OPLUS_GRAFFITI_TARGET_WIDTH_DP = 330f

abstract class OplusComposeClockView(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    defStyleRes: Int = 0,
    private val smallHeightDp: Float,
    private val largeHeightDp: Float,
) : AxClockView(context, attrs, defStyleAttr, defStyleRes) {

    protected val sansFont by lazy {
        assetFont(OPLUS_SANS, "sans-serif", 400, "'wght' 400, 'opsz' 1010")
    }
    protected val sansMediumFont by lazy {
        assetFont(OPLUS_SANS, "sans-serif-medium", 500, "'wght' 500, 'opsz' 1010")
    }
    protected val bigClockFont by lazy { oplusBigFont(OPLUS_BIG_CLOCK, 440, 800) }
    protected val bigClockWideTypeface by lazy {
        oplusBigTypeface(OPLUS_BIG_CLOCK_06, 1000, 460)
    }
    protected val cursiveFont by lazy { digitClockFont(OPLUS_DIGIT) }
    protected val cursive04Font by lazy { digitClockFont(OPLUS_DIGIT_04) }
    protected val cursive06Font by lazy { digitClockFont(OPLUS_DIGIT_06) }
    protected val cursive07Font by lazy { digitClockFont(OPLUS_DIGIT_07) }
    protected val cursive08Font by lazy { digitClockFont(OPLUS_DIGIT_08) }
    protected val nuovoFont by lazy { assetFont(OPLUS_NUOVO, "serif") }
    protected val wendaoFont by lazy { assetFont(OPLUS_WENDAO, "serif") }
    protected val shenqiFont by lazy { assetFont(OPLUS_SHENQI, "serif") }
    protected val galadaFont by lazy { assetFont(OPLUS_GALADA, "serif") }
    protected val modakFont by lazy { assetFont(OPLUS_MODAK, "serif") }

    override val clockHeightBase: Int
        get() {
            val density = context.resources.displayMetrics.density
            val height = when {
                isLargeClock -> largeHeightDp
                isPreviewLayoutLocked -> smallHeightDp / sizeScale.coerceAtLeast(0.01f)
                else -> smallHeightDp
            }
            return (height * density).toInt()
        }

    @Composable
    protected fun activeScale(): Float {
        val scaleState = rememberClockScaleState()
        return if (scaleState.appliesToContent) scaleState.value else 1f
    }

    protected val isPreviewLayoutLocked: Boolean
        get() = isPreviewMode && previewSizeScaleOverride == null

    protected val layoutLeftAligned: Boolean
        get() = isLeftAligned

    protected val layoutRightAligned: Boolean
        get() = isRightAligned

    protected val layoutSideAligned: Boolean
        get() = layoutLeftAligned || layoutRightAligned

    protected fun sidePadding(): Dp = if (layoutSideAligned) {
        (clockPaddingStart / context.resources.displayMetrics.density).dp
    } else {
        0.dp
    }

    protected fun boxAlignment(): Alignment = when {
        layoutLeftAligned -> Alignment.CenterStart
        layoutRightAligned -> Alignment.CenterEnd
        else -> Alignment.Center
    }

    protected fun horizontalAlignment(): Alignment.Horizontal = when {
        layoutLeftAligned -> Alignment.Start
        layoutRightAligned -> Alignment.End
        else -> Alignment.CenterHorizontally
    }

    protected fun rowArrangement(): Arrangement.Horizontal = when {
        layoutLeftAligned -> Arrangement.Start
        layoutRightAligned -> Arrangement.End
        else -> Arrangement.Center
    }

    protected fun paddedModifier(): Modifier {
        val padding = sidePadding()
        return Modifier.padding(
            start = if (layoutRightAligned) 0.dp else padding,
            end = if (layoutRightAligned) padding else 0.dp,
        )
    }

    @Composable
    protected fun constrainedScale(
        maxWidth: Dp,
        targetWidth: Float,
        minScale: Float = 0.72f,
        maxHeight: Dp? = null,
        targetHeight: Float = 0f,
    ): Float {
        val scaleState = rememberClockScaleState()
        val scale = scaleState.contentValue
        if (isLargeClock || isPreviewLayoutLocked || targetWidth <= 0f || maxWidth.value <= 0f) {
            return scale
        }
        val widthScale = maxWidth.value / targetWidth
        val heightScale = maxHeight
            ?.takeIf { targetHeight > 0f && it.value > 0f }
            ?.let { it.value / targetHeight } ?: widthScale
        return scaleState.constrainedContentValue(min(widthScale, heightScale), minScale)
    }

    protected fun linearClockEditScaleGeometry(
        availableWidthDp: Float,
        requestedScale: Float,
        targetWidthDp: Float,
        targetHeightDp: Float = smallHeightDp,
    ): ClockEditScaleGeometry {
        val geometry = ClockEditScaleGeometry.forContentWidth(
            availableWidthDp = availableWidthDp,
            requestedScale = requestedScale,
            scaleRange = ClockSettingsRepository.sizeScaleRange,
            contentWidthDp = targetWidthDp,
            horizontalPaddingDp = OPLUS_CLOCK_EDIT_FRAME_HORIZONTAL_PADDING_DP,
        )
        val contentScale =
            ((geometry.frameWidthDp - OPLUS_CLOCK_EDIT_FRAME_HORIZONTAL_PADDING_DP) /
                targetWidthDp.coerceAtLeast(1f))
        return geometry.copy(frameHeightDp = targetHeightDp * contentScale)
    }

    @Composable
    protected fun clockTint(clock: ClockUiState): Color =
        tintColor(clock.isDoze, clock.screenOff, clock.regionDark)

    protected fun canUseAccentPalette(clock: ClockUiState): Boolean =
        !isPreviewMode && !clock.isDoze && !clock.screenOff && clock.colorOverride == null && !clock.regionDark

    private fun assetTypeface(
        path: String,
        fallback: String,
        weight: Int? = null,
        variationSettings: String? = null,
    ): Typeface {
        return try {
            val builder = Typeface.Builder(context.assets, path)
            weight?.let {
                builder.setWeight(it)
                builder.setItalic(false)
            }
            variationSettings?.let { builder.setFontVariationSettings(it) }
            builder.build()
        } catch (_: RuntimeException) {
            Typeface.create(fallback, Typeface.NORMAL)
        }
    }

    private fun assetFont(
        path: String,
        fallback: String,
        weight: Int? = null,
        variationSettings: String? = null,
    ): FontFamily {
        return FontFamily(assetTypeface(path, fallback, weight, variationSettings))
    }

    private fun oplusBigTypeface(
        path: String,
        weight: Int,
        height: Int,
    ): Typeface =
        assetTypeface(path, "sans-serif-black", weight, "'wght' $weight, 'opsz' 1010, 'HGHT' $height")

    private fun oplusBigFont(
        path: String,
        weight: Int,
        height: Int,
    ): FontFamily =
        FontFamily(oplusBigTypeface(path, weight, height))

    private fun digitClockFont(path: String): FontFamily =
        assetFont(path, "cursive", 500, "'wght' 500")

    private companion object {
        const val OPLUS_SANS = "oplus_clocks/OPPOSans4.0No.ttf"
        const val OPLUS_BIG_CLOCK = "oplus_clocks/fonts/OPPOBigClock01.ttf"
        const val OPLUS_BIG_CLOCK_06 = "oplus_clocks/fonts/OPPOBigClock06.ttf"
        const val OPLUS_DIGIT = "oplus_clocks/OPPODigit01.ttf"
        const val OPLUS_DIGIT_04 = "oplus_clocks/OPPODigit04.ttf"
        const val OPLUS_DIGIT_06 = "oplus_clocks/OPPODigit06.ttf"
        const val OPLUS_DIGIT_07 = "oplus_clocks/OPPODigit07.ttf"
        const val OPLUS_DIGIT_08 = "oplus_clocks/OPPODigit08.ttf"
        const val OPLUS_NUOVO = "oplus_clocks/NuovoRomaSerif.ttf"
        const val OPLUS_WENDAO = "oplus_clocks/OPPOWendaoRegular.ttf"
        const val OPLUS_SHENQI = "oplus_clocks/OPPOShenqiRegularExport.ttf"
        const val OPLUS_GALADA = "oplus_clocks/OPPOGaladaRegularExport.ttf"
        const val OPLUS_MODAK = "oplus_clocks/OPPOModakHeavyExport.ttf"
    }
}

open class OplusClassicClockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    defStyleRes: Int = 0,
    smallHeightDp: Float = 166f,
    largeHeightDp: Float = 400f,
) : OplusComposeClockView(context, attrs, defStyleAttr, defStyleRes, smallHeightDp, largeHeightDp) {

    var previewClassicFace: String? = null

    override fun getTag(): String =
        if (isLargeClock) "OplusClassicLargeClockView" else "OplusClassicClockView"

    override fun getClockEditScaleGeometry(
        availableWidthDp: Float,
        requestedScale: Float,
    ): ClockEditScaleGeometry {
        if (isLargeClock) return super.getClockEditScaleGeometry(availableWidthDp, requestedScale)
        val targetWidth = if (currentResolvedClassicFace() == ClockSettingsRepository.OPLUS_CLASSIC_FACE_STACKED) {
            OPLUS_CLASSIC_STACKED_TARGET_WIDTH_DP
        } else {
            OPLUS_CLASSIC_TARGET_WIDTH_DP
        }
        return linearClockEditScaleGeometry(
            availableWidthDp,
            requestedScale,
            targetWidth,
        )
    }

    @Composable
    override fun Content() {
        val clock = rememberClockState()
        val tint = clockTint(clock)
        val selectedFace by ClockSettingsRepository.oplusClassicFace.collectAsState()
        val clockId by ClockSettingsRepository.clockId.collectAsState()
        val face = resolvedClassicFace(previewClassicFace, selectedFace, clockId)
        if (face == ClockSettingsRepository.OPLUS_CLASSIC_FACE_STACKED) {
            StackedClassic(clock, tint)
        } else if (isLargeClock) {
            LargeClassic(clock, tint)
        } else {
            SmallClassic(clock, tint)
        }
    }

    private fun currentResolvedClassicFace(): String {
        return resolvedClassicFace(
            previewClassicFace,
            ClockSettingsRepository.oplusClassicFace.value,
            ClockSettingsRepository.clockId.value,
        )
    }

    private fun resolvedClassicFace(previewFace: String?, selectedFace: String, clockId: String): String {
        previewFace?.let { return it }
        if (selectedFace != ClockSettingsRepository.OPLUS_CLASSIC_FACE_DEFAULT) return selectedFace
        return when (clockId) {
            "OPLUS_CLASSIC_STACKED",
            "OPLUS_CLASSIC_START_VERTICAL",
            "OPLUS_CLASSIC_END_VERTICAL",
            "OPLUS_CLASSIC_DUAL_VERTICAL" -> ClockSettingsRepository.OPLUS_CLASSIC_FACE_STACKED
            else -> selectedFace
        }
    }

    @Composable
    private fun SmallClassic(clock: ClockUiState, tint: Color) {
        val dateBelow by state.dateBelowState
        val hasInfo = clock.display !is DateDisplay.Hidden
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize().then(paddedModifier()),
            contentAlignment = boxAlignment(),
        ) {
            val scale = constrainedScale(maxWidth, OPLUS_CLASSIC_TARGET_WIDTH_DP, 0.82f, maxHeight, 142f)
            Column(
                horizontalAlignment = horizontalAlignment(),
                verticalArrangement = Arrangement.Center,
            ) {
                if (!dateBelow && hasInfo) {
                    EnhancedDateArea(
                        textColor = tint.copy(alpha = if (clock.isDoze) 0.6f else 0.72f),
                        textSize = (16f * scale).sp,
                        iconSize = (17f * scale).dp,
                        fontFamily = sansMediumFont,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = (0.2f * scale).sp,
                        rowArrangement = rowArrangement(),
                    )
                    Spacer(modifier = Modifier.height((3f * scale).dp))
                }
                ClassicTimeRow(
                    time = clock.time,
                    tint = tint,
                    scale = scale,
                    fontSize = (80f * scale).sp,
                    colonSize = (80f * scale).sp,
                    lineHeight = (78f * scale).sp,
                    horizontalArrangement = rowArrangement(),
                    fontFamily = sansMediumFont,
                    colonFontFamily = sansFont,
                )
                if (dateBelow && hasInfo) {
                    Spacer(modifier = Modifier.height((3f * scale).dp))
                    EnhancedDateArea(
                        textColor = tint.copy(alpha = if (clock.isDoze) 0.6f else 0.72f),
                        textSize = (16f * scale).sp,
                        iconSize = (17f * scale).dp,
                        fontFamily = sansMediumFont,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = (0.2f * scale).sp,
                        rowArrangement = rowArrangement(),
                    )
                }
            }
        }
    }

    @Composable
    private fun LargeClassic(clock: ClockUiState, tint: Color) {
        val (hours, minutes) = splitTimeLines(clock.time)
        val dateBelow by state.dateBelowState
        val hasInfo = clock.display !is DateDisplay.Hidden
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (!dateBelow && hasInfo) {
                    EnhancedDateArea(
                        textColor = tint.copy(alpha = if (clock.isDoze) 0.6f else 0.75f),
                        textSize = 18.sp,
                        iconSize = 18.dp,
                        fontFamily = sansMediumFont,
                        rowArrangement = Arrangement.Center,
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                }
                OplusText(
                    text = hours,
                    color = tint,
                    fontSize = 150.sp,
                    lineHeight = 126.sp,
                    fontFamily = sansMediumFont,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = (-6).sp,
                    overflow = TextOverflow.Visible,
                )
                OplusText(
                    text = minutes,
                    color = tint,
                    fontSize = 150.sp,
                    lineHeight = 126.sp,
                    fontFamily = sansMediumFont,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = (-6).sp,
                    overflow = TextOverflow.Visible,
                )
                if (dateBelow && hasInfo) {
                    Spacer(modifier = Modifier.height(10.dp))
                    EnhancedDateArea(
                        textColor = tint.copy(alpha = if (clock.isDoze) 0.6f else 0.75f),
                        textSize = 18.sp,
                        iconSize = 18.dp,
                        fontFamily = sansMediumFont,
                        rowArrangement = Arrangement.Center,
                    )
                }
            }
        }
    }

    @Composable
    private fun StackedClassic(clock: ClockUiState, tint: Color) {
        val (hours, minutes) = splitTimeLines(clock.time)
        val dateBelow by state.dateBelowState
        val hasInfo = clock.display !is DateDisplay.Hidden
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize().then(paddedModifier()),
            contentAlignment = boxAlignment(),
        ) {
            val scale = if (isLargeClock) {
                1f
            } else {
                constrainedScale(
                    maxWidth,
                    OPLUS_CLASSIC_STACKED_TARGET_WIDTH_DP,
                    0.78f,
                    maxHeight,
                    164f,
                )
            }
            val timeSize = if (isLargeClock) 150f else 72f * scale
            val timeHeight = if (isLargeClock) 126f else 58f * scale
            val textScale = if (isLargeClock) 1f else scale
            Column(
                horizontalAlignment = horizontalAlignment(),
                verticalArrangement = Arrangement.Center,
            ) {
                if (!dateBelow && hasInfo) {
                    EnhancedDateArea(
                        textColor = tint.copy(alpha = if (clock.isDoze) 0.6f else 0.72f),
                        textSize = (18f * textScale).sp,
                        iconSize = (19f * textScale).dp,
                        fontFamily = sansMediumFont,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = (0.2f * textScale).sp,
                        rowArrangement = rowArrangement(),
                    )
                    Spacer(modifier = Modifier.height(if (isLargeClock) 10.dp else (3f * scale).dp))
                }
                OplusText(
                    text = hours,
                    color = tint,
                    fontSize = timeSize.sp,
                    lineHeight = timeHeight.sp,
                    fontFamily = sansMediumFont,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = (if (isLargeClock) -6f else -4f * scale).sp,
                    overflow = TextOverflow.Visible,
                )
                OplusText(
                    text = minutes,
                    color = tint,
                    fontSize = timeSize.sp,
                    lineHeight = timeHeight.sp,
                    fontFamily = sansMediumFont,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = (if (isLargeClock) -6f else -4f * scale).sp,
                    overflow = TextOverflow.Visible,
                )
                if (dateBelow && hasInfo) {
                    Spacer(modifier = Modifier.height(if (isLargeClock) 10.dp else (3f * scale).dp))
                    EnhancedDateArea(
                        textColor = tint.copy(alpha = if (clock.isDoze) 0.6f else 0.72f),
                        textSize = (18f * textScale).sp,
                        iconSize = (19f * textScale).dp,
                        fontFamily = sansMediumFont,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = (0.2f * textScale).sp,
                        rowArrangement = rowArrangement(),
                    )
                }
            }
        }
    }
}

class OplusBigClockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    defStyleRes: Int = 0,
) : OplusComposeClockView(context, attrs, defStyleAttr, defStyleRes, 166f, 430f) {

    var previewBigFace: String? = null
    var previewBigDualTone: Boolean? = null

    private fun wideClockScale(scale: Float): Float {
        if (scale < 1f) {
            return (OPLUS_BIG_WIDE_BASE_SCALE * scale).coerceAtLeast(OPLUS_BIG_WIDE_MIN_VISIBLE_SCALE)
        }
        return (OPLUS_BIG_WIDE_BASE_SCALE +
            (scale - 1f).coerceIn(0f, 0.4f) * OPLUS_BIG_WIDE_EXPAND_RATE)
            .coerceAtMost(1f)
    }

    override val clockHeightBase: Int
        get() {
            if (!isLargeClock) {
                val density = context.resources.displayMetrics.density
                if (currentResolvedBigFace() != ClockSettingsRepository.OPLUS_BIG_FACE_WIDE) {
                    return (OPLUS_BIG_TARGET_HEIGHT_DP * density).toInt()
                }
                val settingScale = sizeScale.coerceAtLeast(0.01f)
                val visibleScale = if (isPreviewLayoutLocked) 1f else wideClockScale(settingScale)
                return (280f * visibleScale / settingScale * density).toInt()
            }
            return super.clockHeightBase
        }

    override fun getClockEditScaleGeometry(
        availableWidthDp: Float,
        requestedScale: Float,
    ): ClockEditScaleGeometry {
        if (isLargeClock) return super.getClockEditScaleGeometry(availableWidthDp, requestedScale)
        val range = ClockSettingsRepository.sizeScaleRange
        return when (currentResolvedBigFace()) {
            ClockSettingsRepository.OPLUS_BIG_FACE_WIDE -> {
                val maxScale = wideClockMaxScale(availableWidthDp, range.max)
                    .coerceIn(range.min, range.max)
                val scale = requestedScale.coerceIn(range.min, maxScale)
                ClockEditScaleGeometry(
                    scaleRange = range.copy(max = maxScale),
                    requestedScale = scale,
                    frameWidthDp = OPLUS_BIG_WIDE_TARGET_WIDTH_DP * wideClockScale(scale) +
                        OPLUS_CLOCK_EDIT_FRAME_HORIZONTAL_PADDING_DP,
                    resizeDpPerScale = wideClockResizeDpPerScale(scale),
                    frameHeightDp = OPLUS_BIG_WIDE_TARGET_HEIGHT_DP * wideClockScale(scale),
                )
            }
            else -> linearClockEditScaleGeometry(
                availableWidthDp,
                requestedScale,
                OPLUS_BIG_TARGET_WIDTH_DP,
                targetHeightDp = OPLUS_BIG_TARGET_HEIGHT_DP,
            )
        }
    }

    override fun getTag(): String =
        if (isLargeClock) "OplusBigLargeClockView" else "OplusBigClockView"

    private fun wideClockMaxScale(availableWidthDp: Float, repositoryMax: Float): Float {
        if (availableWidthDp <= 0f) return repositoryMax
        val availableTextWidth =
            (availableWidthDp - OPLUS_CLOCK_EDIT_FRAME_HORIZONTAL_PADDING_DP).coerceAtLeast(1f)
        val widthScale = availableTextWidth / OPLUS_BIG_WIDE_TARGET_WIDTH_DP
        if (widthScale < OPLUS_BIG_WIDE_BASE_SCALE) return widthScale / OPLUS_BIG_WIDE_BASE_SCALE
        return 1f + (widthScale - OPLUS_BIG_WIDE_BASE_SCALE) / OPLUS_BIG_WIDE_EXPAND_RATE
    }

    private fun wideClockResizeDpPerScale(scale: Float): Float {
        val rate = if (scale < 1f) OPLUS_BIG_WIDE_BASE_SCALE else OPLUS_BIG_WIDE_EXPAND_RATE
        return OPLUS_BIG_WIDE_TARGET_WIDTH_DP * rate
    }

    private fun currentResolvedBigFace(): String {
        return resolvedBigFace(
            previewBigFace,
            ClockSettingsRepository.oplusBigFace.value,
            ClockSettingsRepository.clockId.value,
        )
    }

    @Composable
    override fun Content() {
        val clock = rememberClockState()
        val tint = clockTint(clock)
        val selectedFace by ClockSettingsRepository.oplusBigFace.collectAsState()
        val clockId by ClockSettingsRepository.clockId.collectAsState()
        val selectedDualTone by ClockSettingsRepository.oplusBigDualTone.collectAsState()
        val dualTone = previewBigDualTone ?: selectedDualTone
        when (resolvedBigFace(previewBigFace, selectedFace, clockId)) {
            ClockSettingsRepository.OPLUS_BIG_FACE_WIDE -> BigWide(clock, tint, dualTone)
            else -> if (isLargeClock) LargeBig(clock, tint, dualTone) else SmallBig(clock, tint, dualTone)
        }
    }

    private fun resolvedBigFace(previewFace: String?, selectedFace: String, clockId: String): String {
        previewFace?.let {
            return if (it == ClockSettingsRepository.OPLUS_BIG_FACE_STRETCH) {
                ClockSettingsRepository.OPLUS_BIG_FACE_WIDE
            } else {
                it
            }
        }
        if (selectedFace == ClockSettingsRepository.OPLUS_BIG_FACE_STRETCH) {
            return ClockSettingsRepository.OPLUS_BIG_FACE_WIDE
        }
        if (selectedFace != ClockSettingsRepository.OPLUS_BIG_FACE_DEFAULT) return selectedFace
        return when (clockId) {
            "OPLUS_BIG_VERTICAL",
            "OPLUS_BIG_START_HORIZONTAL",
            "OPLUS_BIG_END_HORIZONTAL" -> ClockSettingsRepository.OPLUS_BIG_FACE_WIDE
            "OPLUS_BIG_WIDE" -> ClockSettingsRepository.OPLUS_BIG_FACE_WIDE
            else -> selectedFace
        }
    }

    @Composable
    private fun SmallBig(clock: ClockUiState, tint: Color, dualTone: Boolean) {
        val (hours, minutes) = splitTimeLines(clock.time)
        val dateBelow by state.dateBelowState
        val hasInfo = clock.display !is DateDisplay.Hidden
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize().then(paddedModifier()),
            contentAlignment = boxAlignment(),
        ) {
            val scale = constrainedScale(maxWidth, OPLUS_BIG_TARGET_WIDTH_DP, 0.68f)
            Column(
                horizontalAlignment = horizontalAlignment(),
                verticalArrangement = Arrangement.Center,
            ) {
                if (!dateBelow && hasInfo) {
                    EnhancedDateArea(
                        textColor = tint.copy(alpha = if (clock.isDoze) 0.6f else 0.72f),
                        textSize = (15.5f * scale).sp,
                        iconSize = (17.5f * scale).dp,
                        fontFamily = sansMediumFont,
                        rowArrangement = rowArrangement(),
                    )
                    Spacer(modifier = Modifier.height((1f * scale).dp))
                }
                OplusBigDigitLine(
                    hours,
                    tint,
                    bigClockFont,
                    (190f * scale).sp,
                    (122f * scale).sp,
                    spacing = (6f * scale).dp,
                )
                OplusBigDigitLine(
                    minutes,
                    secondaryTone(clock, tint, dualTone),
                    bigClockFont,
                    (190f * scale).sp,
                    (122f * scale).sp,
                    spacing = (6f * scale).dp,
                )
                if (dateBelow && hasInfo) {
                    Spacer(modifier = Modifier.height((1f * scale).dp))
                    EnhancedDateArea(
                        textColor = tint.copy(alpha = if (clock.isDoze) 0.6f else 0.72f),
                        textSize = (15.5f * scale).sp,
                        iconSize = (17.5f * scale).dp,
                        fontFamily = sansMediumFont,
                        rowArrangement = rowArrangement(),
                    )
                }
            }
        }
    }

    @Composable
    private fun LargeBig(clock: ClockUiState, tint: Color, dualTone: Boolean) {
        val (hours, minutes) = splitTimeLines(clock.time)
        val dateBelow by state.dateBelowState
        val hasInfo = clock.display !is DateDisplay.Hidden
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (!dateBelow && hasInfo) {
                    EnhancedDateArea(
                        textColor = tint.copy(alpha = if (clock.isDoze) 0.6f else 0.72f),
                        textSize = 18.sp,
                        iconSize = 20.dp,
                        fontFamily = sansMediumFont,
                        rowArrangement = Arrangement.Center,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }
                OplusBigDigitLine(hours, tint, bigClockFont, 260.sp, 166.sp)
                OplusBigDigitLine(
                    minutes,
                    secondaryTone(clock, tint, dualTone),
                    bigClockFont,
                    260.sp,
                    166.sp,
                )
                if (dateBelow && hasInfo) {
                    Spacer(modifier = Modifier.height(12.dp))
                    EnhancedDateArea(
                        textColor = tint.copy(alpha = if (clock.isDoze) 0.6f else 0.72f),
                        textSize = 18.sp,
                        iconSize = 20.dp,
                        fontFamily = sansMediumFont,
                        rowArrangement = Arrangement.Center,
                    )
                }
            }
        }
    }

    @Composable
    private fun BigWide(clock: ClockUiState, tint: Color, dualTone: Boolean) {
        val dateBelow by state.dateBelowState
        val hasInfo = clock.display !is DateDisplay.Hidden
        val alignment by ClockSettingsRepository.resolvedClockAlignment.collectAsState()
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = bigWideBoxAlignment(alignment),
        ) {
            val requestedScale = activeScale()
            val wideScale = if (isLargeClock || isPreviewLayoutLocked) {
                1f
            } else {
                wideClockScale(requestedScale)
            }
            val horizontalPadding = 16.dp
            val scale = if (isLargeClock || isPreviewLayoutLocked || maxWidth.value <= 0f) {
                1f
            } else {
                min(
                    wideScale,
                    (maxWidth.value - OPLUS_CLOCK_EDIT_FRAME_HORIZONTAL_PADDING_DP)
                        .coerceAtLeast(1f) / OPLUS_BIG_WIDE_TARGET_WIDTH_DP,
                ).coerceAtLeast(OPLUS_BIG_WIDE_MIN_VISIBLE_SCALE)
            }
            val timeParts = splitTimeLines(clock.time)
            val timeText = timeParts.first + timeParts.second
            val letterSpacing = if (isLargeClock) -0.08f else -0.06f
            val size = if (isLargeClock) 150.sp else (160f * scale).sp
            val density = LocalDensity.current
            val textSizePx = with(density) { size.toPx() }
            val horizontalPaddingPx = with(density) { horizontalPadding.toPx() }
            val minCanvasHeightPx = with(density) {
                if (isLargeClock) {
                    300.dp.toPx()
                } else {
                    (232f * scale).dp.toPx()
                }
            }
            val availableTextWidthPx = with(density) {
                (maxWidth.toPx() - horizontalPaddingPx * 2f).coerceAtLeast(1f)
            }
            val canvasMetrics = remember(
                timeText,
                bigClockWideTypeface,
                textSizePx,
                letterSpacing,
                availableTextWidthPx,
            ) {
                measureCanvasTextBounds(
                    timeText,
                    bigClockWideTypeface,
                    textSizePx,
                    letterSpacing,
                    availableTextWidthPx,
                )
            }
            val canvasHeight = with(density) {
                maxOf(minCanvasHeightPx, canvasMetrics.height + 16.dp.toPx())
                    .toDp()
            }
            val infoTextSize = if (isLargeClock) 22.sp else (20f * scale).sp
            val infoIconSize = if (isLargeClock) 24.dp else (22f * scale).dp
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = bigWideHorizontalAlignment(alignment),
                verticalArrangement = Arrangement.Center,
            ) {
                if (!dateBelow && hasInfo) {
                    EnhancedDateArea(
                        textColor = tint.copy(alpha = if (clock.isDoze) 0.6f else 0.72f),
                        textSize = infoTextSize,
                        iconSize = infoIconSize,
                        fontFamily = sansMediumFont,
                        modifier = Modifier.padding(horizontal = horizontalPadding),
                        rowArrangement = bigWideRowArrangement(alignment),
                    )
                    Spacer(modifier = Modifier.height(if (isLargeClock) 6.dp else (3f * scale).dp))
                }
                OplusBigCanvasLine(
                    text = timeText,
                    tint = tint,
                    secondaryTint = secondaryTone(clock, tint, dualTone),
                    splitIndex = if (dualTone) timeParts.first.length else timeText.length,
                    typeface = bigClockWideTypeface,
                    metrics = canvasMetrics,
                    height = canvasHeight,
                    horizontalPadding = horizontalPadding,
                    letterSpacing = letterSpacing,
                    textAlign = bigWideTextAlign(alignment),
                )
                if (dateBelow && hasInfo) {
                    Spacer(modifier = Modifier.height(if (isLargeClock) 6.dp else (3f * scale).dp))
                    EnhancedDateArea(
                        textColor = tint.copy(alpha = if (clock.isDoze) 0.6f else 0.72f),
                        textSize = infoTextSize,
                        iconSize = infoIconSize,
                        fontFamily = sansMediumFont,
                        modifier = Modifier.padding(horizontal = horizontalPadding),
                        rowArrangement = bigWideRowArrangement(alignment),
                    )
                }
            }
        }
    }

    private fun secondaryTone(clock: ClockUiState, tint: Color, dualTone: Boolean): Color {
        if (clock.isDoze) return tint
        return tint.copy(alpha = if (dualTone) 0.58f else 0.84f)
    }

    private fun bigWideBoxAlignment(alignment: String): Alignment {
        if (isLargeClock) return Alignment.Center
        return when (alignment) {
            ClockSettingsRepository.ALIGNMENT_LEFT -> Alignment.CenterStart
            ClockSettingsRepository.ALIGNMENT_RIGHT -> Alignment.CenterEnd
            else -> Alignment.Center
        }
    }

    private fun bigWideHorizontalAlignment(alignment: String): Alignment.Horizontal {
        if (isLargeClock) return Alignment.CenterHorizontally
        return when (alignment) {
            ClockSettingsRepository.ALIGNMENT_LEFT -> Alignment.Start
            ClockSettingsRepository.ALIGNMENT_RIGHT -> Alignment.End
            else -> Alignment.CenterHorizontally
        }
    }

    private fun bigWideRowArrangement(alignment: String): Arrangement.Horizontal {
        if (isLargeClock) return Arrangement.Center
        return when (alignment) {
            ClockSettingsRepository.ALIGNMENT_LEFT -> Arrangement.Start
            ClockSettingsRepository.ALIGNMENT_RIGHT -> Arrangement.End
            else -> Arrangement.Center
        }
    }

    private fun bigWideTextAlign(alignment: String): TextAlign {
        if (isLargeClock) return TextAlign.Center
        return when (alignment) {
            ClockSettingsRepository.ALIGNMENT_LEFT -> TextAlign.Start
            ClockSettingsRepository.ALIGNMENT_RIGHT -> TextAlign.End
            else -> TextAlign.Center
        }
    }
}

open class OplusGraffitiClockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    defStyleRes: Int = 0,
) : OplusComposeClockView(context, attrs, defStyleAttr, defStyleRes, 166f, 360f) {

    var previewGraffitiFace: String? = null
    var previewGraffitiAngle: String? = null

    private val graffitiColor = Color(0xFFFFEA00)

    override fun getTag(): String =
        if (isLargeClock) "OplusPlayfulLargeClockView" else "OplusPlayfulClockView"

    override fun getClockEditScaleGeometry(
        availableWidthDp: Float,
        requestedScale: Float,
    ): ClockEditScaleGeometry {
        if (isLargeClock) return super.getClockEditScaleGeometry(availableWidthDp, requestedScale)
        return linearClockEditScaleGeometry(
            availableWidthDp,
            requestedScale,
            OPLUS_GRAFFITI_TARGET_WIDTH_DP,
        )
    }

    @Composable
    override fun Content() {
        val clock = rememberClockState()
        val tint = clockTint(clock)
        val selectedFace by ClockSettingsRepository.oplusGraffitiFace.collectAsState()
        val selectedAngle by ClockSettingsRepository.oplusGraffitiAngle.collectAsState()
        val font = graffitiFont(previewGraffitiFace ?: selectedFace)
        val angle = graffitiAngle(previewGraffitiAngle ?: selectedAngle)
        if (isLargeClock) {
            LargeGraffiti(clock, tint, font, angle)
        } else {
            SmallGraffiti(clock, tint, font, angle)
        }
    }

    private fun graffitiFont(face: String): FontFamily {
        return when (face) {
            ClockSettingsRepository.OPLUS_GRAFFITI_FACE_SANS -> sansMediumFont
            ClockSettingsRepository.OPLUS_GRAFFITI_FACE_BRIGHT -> cursive06Font
            ClockSettingsRepository.OPLUS_GRAFFITI_FACE_CUTE -> nuovoFont
            ClockSettingsRepository.OPLUS_GRAFFITI_FACE_DIGIT04 -> cursive04Font
            ClockSettingsRepository.OPLUS_GRAFFITI_FACE_GAME -> cursive07Font
            ClockSettingsRepository.OPLUS_GRAFFITI_FACE_KEEP -> cursive08Font
            ClockSettingsRepository.OPLUS_GRAFFITI_FACE_WENDAO -> wendaoFont
            ClockSettingsRepository.OPLUS_GRAFFITI_FACE_SHENQI -> shenqiFont
            ClockSettingsRepository.OPLUS_GRAFFITI_FACE_GALADA -> galadaFont
            ClockSettingsRepository.OPLUS_GRAFFITI_FACE_MODAK -> modakFont
            else -> cursiveFont
        }
    }

    private fun graffitiAngle(angle: String): Float {
        return when (angle) {
            ClockSettingsRepository.OPLUS_GRAFFITI_ANGLE_LEFT -> -10f
            ClockSettingsRepository.OPLUS_GRAFFITI_ANGLE_RIGHT -> 10f
            else -> 0f
        }
    }

    @Composable
    private fun SmallGraffiti(clock: ClockUiState, tint: Color, font: FontFamily, angle: Float) {
        val usePalette = canUseAccentPalette(clock)
        val contentColor = if (usePalette) graffitiColor else tint
        val dateBelow by state.dateBelowState
        val hasInfo = clock.display !is DateDisplay.Hidden
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize().then(paddedModifier()),
            contentAlignment = boxAlignment(),
        ) {
            val scale = constrainedScale(maxWidth, OPLUS_GRAFFITI_TARGET_WIDTH_DP, 0.68f, maxHeight, 136f)
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = horizontalAlignment(),
                verticalArrangement = Arrangement.Center,
            ) {
                if (!dateBelow && hasInfo) {
                    EnhancedDateArea(
                        textColor = contentColor.copy(alpha = if (clock.isDoze) 0.58f else 0.76f),
                        textSize = (18f * scale).sp,
                        iconSize = (20f * scale).dp,
                        fontFamily = sansMediumFont,
                        rowArrangement = rowArrangement(),
                    )
                    Spacer(modifier = Modifier.height((2f * scale).dp))
                }
                GraffitiTime(
                    time = timeWithColon(clock.time),
                    tint = tint,
                    usePalette = usePalette,
                    fontSize = (84f * scale).sp,
                    lineHeight = (80f * scale).sp,
                    font = font,
                    modifier = Modifier
                        .padding(horizontal = (10f * scale).dp, vertical = (8f * scale).dp)
                        .rotate(angle),
                )
                if (dateBelow && hasInfo) {
                    Spacer(modifier = Modifier.height((2f * scale).dp))
                    EnhancedDateArea(
                        textColor = contentColor.copy(alpha = if (clock.isDoze) 0.58f else 0.76f),
                        textSize = (18f * scale).sp,
                        iconSize = (20f * scale).dp,
                        fontFamily = sansMediumFont,
                        rowArrangement = rowArrangement(),
                    )
                }
            }
        }
    }

    @Composable
    private fun LargeGraffiti(clock: ClockUiState, tint: Color, font: FontFamily, angle: Float) {
        val usePalette = canUseAccentPalette(clock)
        val contentColor = if (usePalette) graffitiColor else tint
        val dateBelow by state.dateBelowState
        val hasInfo = clock.display !is DateDisplay.Hidden
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (!dateBelow && hasInfo) {
                    EnhancedDateArea(
                        textColor = contentColor.copy(alpha = if (clock.isDoze) 0.58f else 0.74f),
                        textSize = 20.sp,
                        iconSize = 22.dp,
                        fontFamily = sansMediumFont,
                        rowArrangement = Arrangement.Center,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }
                GraffitiTime(
                    time = timeWithColon(clock.time),
                    tint = tint,
                    usePalette = usePalette,
                    fontSize = 116.sp,
                    lineHeight = 108.sp,
                    font = font,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp).rotate(angle),
                )
                if (dateBelow && hasInfo) {
                    Spacer(modifier = Modifier.height(4.dp))
                    EnhancedDateArea(
                        textColor = contentColor.copy(alpha = if (clock.isDoze) 0.58f else 0.74f),
                        textSize = 20.sp,
                        iconSize = 22.dp,
                        fontFamily = sansMediumFont,
                        rowArrangement = Arrangement.Center,
                    )
                }
            }
        }
    }

    @Composable
    private fun GraffitiTime(
        time: String,
        tint: Color,
        usePalette: Boolean,
        fontSize: TextUnit,
        lineHeight: TextUnit,
        font: FontFamily,
        modifier: Modifier = Modifier,
    ) {
        Row(
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            time.forEach { char ->
                OplusText(
                    text = char.toString(),
                    color = if (usePalette) graffitiColor else tint,
                    fontSize = fontSize,
                    lineHeight = lineHeight,
                    fontFamily = font,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-3).sp,
                    overflow = TextOverflow.Visible,
                )
            }
        }
    }
}

@Composable
private fun OplusBigDigitLine(
    text: String,
    tint: Color,
    fontFamily: FontFamily,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    spacing: Dp = 6.dp,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(spacing)) {
        text.forEach { char ->
            OplusBigDigit(char, tint, fontFamily, fontSize, lineHeight)
        }
    }
}

@Composable
private fun OplusBigDigit(
    char: Char?,
    tint: Color,
    fontFamily: FontFamily,
    fontSize: TextUnit,
    lineHeight: TextUnit,
) {
    OplusText(
        text = char?.toString().orEmpty(),
        color = tint,
        fontSize = fontSize,
        lineHeight = lineHeight,
        fontFamily = fontFamily,
        fontWeight = FontWeight.Black,
        letterSpacing = (-5).sp,
        textAlign = TextAlign.Center,
        overflow = TextOverflow.Visible,
    )
}

@Composable
private fun OplusBigCanvasLine(
    text: String,
    tint: Color,
    secondaryTint: Color = tint,
    splitIndex: Int = text.length,
    typeface: Typeface,
    metrics: CanvasTextMetrics,
    height: Dp,
    horizontalPadding: Dp,
    letterSpacing: Float,
    textAlign: TextAlign,
) {
    val density = LocalDensity.current
    val horizontalPaddingPx = with(density) { horizontalPadding.toPx() }
    val heightPx = with(density) { height.toPx() }
    val paint = remember(typeface) { Paint(Paint.ANTI_ALIAS_FLAG) }
    Canvas(modifier = Modifier.fillMaxWidth().height(height)) {
        paint.apply {
            color = tint.toArgb()
            this.typeface = typeface
            this.textSize = metrics.textSize
            this.letterSpacing = letterSpacing
            this.textAlign = Paint.Align.LEFT
        }
        val x = when (textAlign) {
            TextAlign.End, TextAlign.Right -> size.width - horizontalPaddingPx -
                metrics.right.toFloat()
            TextAlign.Center -> size.width / 2f - (metrics.left + metrics.right) / 2f
            else -> horizontalPaddingPx - metrics.left.toFloat()
        }
        val baseline = (heightPx - metrics.height) / 2f - metrics.top
        drawIntoCanvas { canvas ->
            if (splitIndex in 1 until text.length && secondaryTint != tint) {
                val primary = text.take(splitIndex)
                val secondary = text.drop(splitIndex)
                paint.color = tint.toArgb()
                canvas.nativeCanvas.drawText(primary, x, baseline, paint)
                paint.color = secondaryTint.toArgb()
                canvas.nativeCanvas.drawText(
                    secondary,
                    x + paint.measureText(primary),
                    baseline,
                    paint,
                )
            } else {
                canvas.nativeCanvas.drawText(text, x, baseline, paint)
            }
        }
    }
}

@Composable
private fun ClassicTimeRow(
    time: String,
    tint: Color,
    scale: Float,
    fontSize: TextUnit,
    colonSize: TextUnit,
    lineHeight: TextUnit,
    horizontalArrangement: Arrangement.Horizontal,
    fontFamily: FontFamily,
    colonFontFamily: FontFamily,
) {
    val (hours, minutes) = splitTimeLines(time)
    Row(
        horizontalArrangement = horizontalArrangement,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OplusText(
            text = hours,
            color = tint,
            fontSize = fontSize,
            lineHeight = lineHeight,
            fontFamily = fontFamily,
            fontWeight = FontWeight.Medium,
            letterSpacing = (-3f * scale).sp,
            overflow = TextOverflow.Visible,
        )
        OplusText(
            text = ":",
            color = tint.copy(alpha = 0.75f),
            fontSize = colonSize,
            lineHeight = lineHeight,
            fontFamily = colonFontFamily,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = (4f * scale).dp),
            overflow = TextOverflow.Visible,
        )
        OplusText(
            text = minutes,
            color = tint,
            fontSize = fontSize,
            lineHeight = lineHeight,
            fontFamily = fontFamily,
            fontWeight = FontWeight.Medium,
            letterSpacing = (-3f * scale).sp,
            overflow = TextOverflow.Visible,
        )
    }
}

@Composable
private fun OplusText(
    text: String,
    color: Color,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    fontFamily: FontFamily,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight = FontWeight.Normal,
    letterSpacing: TextUnit = 0.sp,
    textAlign: TextAlign = TextAlign.Start,
    maxLines: Int = 1,
    overflow: TextOverflow = TextOverflow.Ellipsis,
) {
    Text(
        text = text,
        modifier = modifier,
        maxLines = maxLines,
        overflow = overflow,
        softWrap = maxLines > 1,
        textAlign = textAlign,
        style = TextStyle(
            color = color,
            fontSize = fontSize,
            lineHeight = lineHeight,
            fontFamily = fontFamily,
            fontWeight = fontWeight,
            letterSpacing = letterSpacing,
        ),
    )
}

private fun timeWithColon(time: String): String {
    val (hours, minutes) = splitTimeLines(time)
    if (hours.isEmpty() || minutes.isEmpty()) return time
    return "$hours:$minutes"
}

private data class CanvasTextMetrics(
    val textSize: Float,
    val width: Float,
    val left: Int,
    val right: Int,
    val top: Int,
    val height: Int,
)

private fun measureCanvasTextBounds(
    text: String,
    typeface: Typeface,
    textSize: Float,
    letterSpacing: Float,
    availableWidth: Float,
): CanvasTextMetrics {
    val natural = readCanvasTextBounds(text, typeface, textSize, letterSpacing)
    if (natural.width <= availableWidth) return natural
    val fit = availableWidth / natural.width.coerceAtLeast(1f)
    return readCanvasTextBounds(text, typeface, textSize * fit, letterSpacing)
}

private fun readCanvasTextBounds(
    text: String,
    typeface: Typeface,
    textSize: Float,
    letterSpacing: Float,
): CanvasTextMetrics {
    val bounds = Rect()
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.typeface = typeface
        this.textSize = textSize
        this.letterSpacing = letterSpacing
    }
    paint.getTextBounds(text, 0, text.length, bounds)
    val width = maxOf(bounds.width().toFloat(), paint.measureText(text))
    return CanvasTextMetrics(
        textSize,
        width,
        bounds.left,
        bounds.right,
        bounds.top,
        bounds.height(),
    )
}
