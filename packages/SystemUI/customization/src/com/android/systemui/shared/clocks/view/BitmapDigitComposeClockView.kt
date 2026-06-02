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

import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Point
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextUtils
import android.text.format.DateFormat
import android.util.AttributeSet
import android.util.LruCache
import android.view.animation.PathInterpolator
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.basicMarquee
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.android.systemui.customization.R
import com.android.systemui.shared.clocks.ClockSettingsRepository
import com.android.systemui.shared.clocks.extensions.createBitmaps
import com.android.systemui.shared.clocks.extensions.scaledDimen
import com.android.systemui.shared.clocks.extensions.scaleRatio
import kotlin.math.min

private data class LargeFontLayout(
    val visualTopOffset: Float,
    val visualHeight: Float,
    val cellWidth: Float,
    val canvasHeightDp: Float,
)

private data class FontDigitVisualBounds(
    val top: Float,
    val bottom: Float,
) {
    val height: Float get() = bottom - top
}

class BitmapDigitComposeClockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    defStyleRes: Int = 0
) : AxClockView(context, attrs, defStyleAttr, defStyleRes) {

    override val animationSpec: AxClockAnimationSpec
        get() = AxClockAnimationSpecs.forFaceStyle(faceStyle)

    var faceStyle: ClockFaceStyle = ClockFaceStyle.DEFAULT
        set(value) {
            field = value
            cachedConfig = BitmapFaceConfigs.getConfig(value)
            (cachedConfig?.renderMode as? RenderMode.FontDigit)?.let { initFontMode(it) }
        }

    private var cachedConfig: BitmapFaceConfig? = BitmapFaceConfigs.getConfig(faceStyle)

    private val typefaceCache = LruCache<Int, Typeface>(16)
    private val fontPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fontWeights = IntArray(DIGIT_COUNT) { 0 }
    private val digitAnimators = arrayOfNulls<ValueAnimator>(DIGIT_COUNT)
    private val digitAnimStarted = BooleanArray(DIGIT_COUNT)
    private val digitRects = arrayOfNulls<Rect>(DIGIT_COUNT)
    private var isWakingUp = true
    private var isKeyguardVisibleState = true
    private var maxRadius = -1
    private var tapPos = Point(0, 0)
    private var dozingAmountChanging = false
    private var fidgetWeightAnimator: AnimatorSet? = null
    private val weightVersion = mutableIntStateOf(0)
    private val screenWidth = context.resources.displayMetrics.widthPixels
    private val screenHeight = context.resources.displayMetrics.heightPixels

    private val forceResetRunnable = Runnable {
        val mode = fontDigitMode ?: return@Runnable
        if (!isWakingUp) return@Runnable
        for (i in 0 until DIGIT_COUNT) {
            animateDigitTo(i, mode.lsFontWeight, FORCE_ANIM_MS)
        }
        dozingAmountChanging = false
    }

    private val fontDigitMode: RenderMode.FontDigit?
        get() = (cachedConfig?.renderMode as? RenderMode.FontDigit)

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val handPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        strokeCap = Paint.Cap.ROUND
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private fun initFontMode(mode: RenderMode.FontDigit) {
        fontPaint.textSize = mode.fontSize * context.scaleRatio
        for (i in 0 until DIGIT_COUNT) {
            fontWeights[i] = mode.lsFontWeight
        }
    }

    private fun getCachedTypeface(weight: Int, fontPath: String): Typeface {
        val snapped = (weight / WEIGHT_SNAP) * WEIGHT_SNAP
        return typefaceCache.get(snapped) ?: Typeface.Builder(context.assets, fontPath)
            .setFontVariationSettings("'wght' $snapped")
            .build()
            .also { typefaceCache.put(snapped, it) }
    }

    private fun dateTextColor(
        config: BitmapFaceConfig,
        isDoze: Boolean,
        screenOff: Boolean,
        regionDark: Boolean,
    ): Color {
        val base = androidColorToComposeColor(config.clockColor(isDoze, screenOff, regionDark))
        return base.copy(alpha = if (isDoze) 0.6f else 0.8f)
    }

    private fun animateDigitTo(
        index: Int,
        target: Int,
        duration: Long = RIPPLE_ANIM_MS,
    ) {
        val current = fontWeights[index]
        if (current == target) return
        digitAnimators[index]?.cancel()
        digitAnimators[index] = ValueAnimator.ofInt(current, target).apply {
            this.duration = duration
            interpolator = RIPPLE_INTERPOLATOR
            addUpdateListener {
                fontWeights[index] = it.animatedValue as Int
                weightVersion.intValue++
            }
            start()
        }
    }

    private fun cancelDigitAnimators() {
        for (i in 0 until DIGIT_COUNT) {
            digitAnimators[i]?.cancel()
            digitAnimators[i] = null
        }
    }

    override val clockHeightBase: Int
        get() {
            if (isLargeClock) return super.clockHeightBase
            val config = cachedConfig ?: return super.clockHeightBase
            val density = context.resources.displayMetrics.density
            val contentHeight = when (val mode = config.renderMode) {
                is RenderMode.BitmapDigit -> {
                    if (config.digitResIds.isEmpty()) return super.clockHeightBase
                    val d = ContextCompat.getDrawable(context, config.digitResIds[0])
                        ?: return super.clockHeightBase
                    d.intrinsicHeight * scaleRatio * config.smallScaleMultiplier
                }
                is RenderMode.FontDigit -> {
                    fontPaint.textSize = mode.fontSize * scaleRatio
                    val metrics = fontPaint.fontMetrics
                    metrics.descent - metrics.ascent
                }
                is RenderMode.AnalogClock -> analogFaceHeightPx(config)
            }
            return (contentHeight + (DATE_ROW_DP + config.dateSpacingDp + config.topPaddingDp + config.bottomPaddingDp) * density).toInt()
        }

    @Composable
    override fun Content() {
        if (isLargeClock) LargeContent() else SmallContent()
    }

    @Composable
    private fun SmallContent() {
        val config = cachedConfig ?: return
        when (config.renderMode) {
            is RenderMode.BitmapDigit -> SmallBitmapContent(config)
            is RenderMode.FontDigit -> SmallFontContent(config, config.renderMode)
            is RenderMode.AnalogClock -> SmallAnalogContent(config)
        }
    }

    @Composable
    private fun SmallBitmapContent(config: BitmapFaceConfig) {
        val (time, _, isDoze, screenOff, regionDark) = rememberClockState()

        val bitmaps = remember(config) { loadDigitBitmaps(config.digitResIds) }
        val lightBitmaps = remember(config) {
            config.digitLightResIds?.let { loadDigitBitmaps(it) }
        }

        val dynSizeScale = rememberSmallClockSizeScale()
        val scale = context.scaleRatio * config.smallScaleMultiplier * dynSizeScale
        val spacing = if (config.negativeSpacing) {
            -context.scaledDimen(config.digitSpacingRes) * dynSizeScale
        } else {
            context.scaledDimen(config.digitSpacingRes) * dynSizeScale
        }
        val overlapPadding = if (config.overlapSpacingRes != 0) context.scaledDimen(config.overlapSpacingRes) * dynSizeScale else 0f

        val dotSize = context.scaledDimen(config.dotSizeRes) * dynSizeScale
        val dotMargin = context.scaledDimen(config.dotMarginRes) * dynSizeScale
        val dotCenterMargin = context.scaledDimen(config.dotCenterMarginRes) * dynSizeScale

        val tintColor = androidColorToComposeColor(
            config.clockColor(isDoze, screenOff, regionDark)
        )

        val sampleBitmap = remember(config) {
            ContextCompat.getDrawable(context, config.digitResIds[0])
        }
        val canvasHeight = remember(sampleBitmap, scale) {
            ((sampleBitmap?.intrinsicHeight ?: 0) * scale).coerceAtLeast(1f)
        }
        val canvasHeightDp = with(LocalDensity.current) { canvasHeight.toDp() }

        val dateColor = dateTextColor(config, isDoze, screenOff, regionDark)

        SmallShell(config, dateColor) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(canvasHeightDp),
            ) {
                state.clockColorOverrideState.value
                if (time.isEmpty() || !TextUtils.isDigitsOnly(time)) return@Canvas

                val naturalWidth = computeTotalWidth(
                    time, bitmaps, scale, spacing, overlapPadding,
                    config, dotCenterMargin
                )
                if (naturalWidth <= 0f) return@Canvas

                val fitScale = fitToWidth(naturalWidth)
                val drawScale = scale * fitScale
                val drawSpacing = spacing * fitScale
                val drawOverlap = overlapPadding * fitScale
                val drawDotSize = dotSize * fitScale
                val drawDotMargin = dotMargin * fitScale
                val drawDotCenterMargin = dotCenterMargin * fitScale
                val finalWidth = naturalWidth * fitScale
                val startX = when {
                    isLeftAligned -> clockPaddingStart
                    isRightAligned -> size.width - clockPaddingStart - finalWidth
                    else -> (size.width - finalWidth) / 2f
                }
                val minuteStartIndex = if (time.length == 4) 2 else 1
                val minuteTint = if (config.minuteAlpha < 1f && !isDoze && !screenOff) {
                    tintColor.copy(alpha = config.minuteAlpha)
                } else {
                    tintColor
                }

                var x = startX
                time.forEachIndexed { index, char ->
                    val useLightVariant = shouldUseLightVariant(config, time, index, isDoze, screenOff)
                    val bitmapMap = if (useLightVariant && lightBitmaps != null) lightBitmaps else bitmaps
                    val bitmap = bitmapMap[char] ?: return@forEachIndexed

                    val yOffset = (size.height - bitmap.height * drawScale) / 2f

                    val digitTint = if (index >= minuteStartIndex) minuteTint else tintColor
                    drawScaledBitmap(bitmap, x, yOffset, drawScale, digitTint)

                    x += bitmap.width * drawScale
                    x += getCustomSpacing(config, time, index, drawSpacing, drawOverlap)

                    if (shouldDrawSeparator(config, time, index)) {
                        x += drawDotSeparator(
                            x, yOffset, bitmap.height * drawScale,
                            drawDotSize, drawDotMargin, drawDotCenterMargin, tintColor
                        )
                    } else if (index < time.lastIndex) {
                        x += drawSpacing
                    }
                }
            }
        }
    }

    @Composable
    private fun SmallFontContent(config: BitmapFaceConfig, mode: RenderMode.FontDigit) {
        val (time, _, isDoze, screenOff, regionDark) = rememberClockState()

        val dynSizeScale = rememberSmallClockSizeScale()
        val scale = context.scaleRatio * dynSizeScale
        val canvasHeight = remember(scale) {
            fontPaint.textSize = mode.fontSize * scale
            val metrics = fontPaint.fontMetrics
            (metrics.descent - metrics.ascent).coerceAtLeast(1f)
        }
        val canvasHeightDp = with(LocalDensity.current) { canvasHeight.toDp() }
        val dateColor = dateTextColor(config, isDoze, screenOff, regionDark)

        SmallShell(config, dateColor) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(canvasHeightDp),
            ) {
                weightVersion.intValue
                state.clockColorOverrideState.value
                if (time.isEmpty() || !TextUtils.isDigitsOnly(time)) return@Canvas

                val nativeCanvas = drawContext.canvas.nativeCanvas
                val displayTime = formatDisplayTime(time)
                if (displayTime.isEmpty()) return@Canvas

                fontPaint.textSize = mode.fontSize * scale
                fontPaint.color = config.clockColor(isDoze, screenOff, regionDark)

                val naturalWidths = FloatArray(displayTime.length) { i ->
                    fontPaint.typeface = getCachedTypeface(
                        fontWeights.getOrElse(i) { mode.lsFontWeight }, mode.fontPath
                    )
                    fontPaint.measureText(displayTime[i].toString())
                }
                val naturalWidth = naturalWidths.sum()
                val fitScale = fitToWidth(naturalWidth)
                if (fitScale < 1f) fontPaint.textSize = mode.fontSize * scale * fitScale
                val digitWidths = if (fitScale < 1f) {
                    FloatArray(displayTime.length) { i ->
                        fontPaint.typeface = getCachedTypeface(
                            fontWeights.getOrElse(i) { mode.lsFontWeight }, mode.fontPath
                        )
                        fontPaint.measureText(displayTime[i].toString())
                    }
                } else naturalWidths
                val totalWidth = digitWidths.sum()

                val metrics = fontPaint.fontMetrics
                val baselineY = (size.height / 2f) - ((metrics.top + metrics.bottom) / 2f)

                val indices = displayTime.indices.let { range ->
                    if (isWakingUp && !isSideAligned) range.reversed() else range.toList()
                }

                val firstBounds = Rect()
                fontPaint.typeface = getCachedTypeface(
                    fontWeights.getOrElse(0) { mode.lsFontWeight }, mode.fontPath
                )
                fontPaint.getTextBounds(displayTime[0].toString(), 0, 1, firstBounds)
                val leftBearing = firstBounds.left.toFloat()

                val startX = when {
                    isLeftAligned -> clockPaddingStart - leftBearing
                    isRightAligned -> size.width - clockPaddingStart - totalWidth
                    isWakingUp -> (size.width / 2f) + (totalWidth / 2f)
                    else -> (size.width - totalWidth) / 2f
                }
                var x = startX

                for (i in indices) {
                    if (isWakingUp && !isSideAligned) {
                        x -= digitWidths[i]
                    }

                    fontPaint.typeface = getCachedTypeface(
                        fontWeights.getOrElse(i) { mode.lsFontWeight }, mode.fontPath
                    )
                    drawFontDigit(nativeCanvas, displayTime[i], x, baselineY, mode)

                    val charRect = Rect()
                    fontPaint.getTextBounds(displayTime[i].toString(), 0, 1, charRect)
                    if (shouldNormalizeDigitHeight(mode, displayTime[i])) {
                        val visualBounds = getFontDigitVisualBounds(fontPaint)
                        charRect.top = visualBounds.top.toInt()
                        charRect.bottom = visualBounds.bottom.toInt()
                    }
                    charRect.offset(x.toInt(), baselineY.toInt())
                    val loc = IntArray(2)
                    getLocationOnScreen(loc)
                    charRect.offset(loc[0], loc[1])
                    if (i < DIGIT_COUNT) digitRects[i] = charRect

                    if (!isWakingUp || isSideAligned) {
                        x += digitWidths[i]
                    }
                }
            }
        }
    }

    @Composable
    private fun SmallAnalogContent(config: BitmapFaceConfig) {
        val (time, _, isDoze, screenOff, regionDark) = rememberClockState()
        val dateColor = dateTextColor(config, isDoze, screenOff, regionDark)
        val dynSizeScale = rememberSmallClockSizeScale()
        val analogScale = dynSizeScale
        val analogHeightPx = remember(config, analogScale) { analogFaceHeightPx(config, analogScale) }
        val analogHeight = with(LocalDensity.current) { analogHeightPx.toDp() }

        SmallShell(config, dateColor) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(analogHeight)
                    .analogDrawModifier(config, time, isDoze, screenOff, regionDark),
            )
        }
    }

    @Composable
    private fun SmallShell(
        config: BitmapFaceConfig,
        textColor: Color,
        content: @Composable ColumnScope.() -> Unit,
    ) {
        val dateBelow by state.dateBelowState
        val display = viewModel.rememberResolvedDisplay()
        val hasInfo = display !is DateDisplay.Hidden
        val horizontalAlign = when {
            isLeftAligned -> Alignment.Start
            isRightAligned -> Alignment.End
            else -> Alignment.CenterHorizontally
        }
        val topPadding = config.topPaddingDp.dp
        val bottomPadding = config.bottomPaddingDp.dp
        val dateSpacing = config.dateSpacingDp.dp
        val sidePadding = if (isSideAligned) {
            (clockPaddingStart / context.resources.displayMetrics.density).dp
        } else {
            0.dp
        }
        val datePaddingModifier = if (isSideAligned) Modifier.padding(
            start = if (isRightAligned) 0.dp else sidePadding,
            end = if (isRightAligned) sidePadding else 0.dp,
        ) else Modifier

        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = horizontalAlign,
        ) {
            if (!dateBelow && hasInfo) {
                if (topPadding > 0.dp) Spacer(modifier = Modifier.height(topPadding))
                EnhancedDateArea(modifier = datePaddingModifier, textColor = textColor)
                Spacer(modifier = Modifier.height(dateSpacing))
            }

            content()

            if (dateBelow && hasInfo) {
                Spacer(modifier = Modifier.height(dateSpacing))
                EnhancedDateArea(modifier = datePaddingModifier, textColor = textColor)
                if (topPadding > 0.dp) Spacer(modifier = Modifier.height(topPadding))
            }

            if (!dateBelow && bottomPadding > 0.dp) Spacer(modifier = Modifier.height(bottomPadding))
        }
    }

    @Composable
    private fun LargeContent() {
        val config = cachedConfig ?: return
        when (config.renderMode) {
            is RenderMode.BitmapDigit -> LargeBitmapContent(config)
            is RenderMode.FontDigit -> LargeFontContent(config, config.renderMode)
            is RenderMode.AnalogClock -> LargeAnalogContent(config)
        }
    }

    @Composable
    private fun LargeBitmapContent(config: BitmapFaceConfig) {
        val (time, date, isDoze, screenOff, regionDark) = rememberClockState()
        val display = viewModel.rememberResolvedDisplay()
        val hasInfo = display !is DateDisplay.Hidden

        val bitmaps = remember(config) {
            loadDigitBitmaps(config.digitLargeResIds ?: config.digitResIds)
        }

        val baseScale = minOf(context.scaleRatio, MAX_TABLET_SCALE)
        val scale = baseScale * config.largeScaleMultiplier
        val digitSpacing = context.scaledDimen(config.largeDigitSpacingRes)
        val lineSpacing = context.scaledDimen(config.largeLineSpacingRes)

        val tintColor = androidColorToComposeColor(
            config.clockColor(isDoze, screenOff, regionDark)
        )

        val finalSpacing = if (config.negativeSpacing) {
            -context.scaledDimen(config.largeDigitSpacingRes)
        } else {
            digitSpacing
        }

        val sampleBmp = remember(config) { bitmaps['0'] }
        val digitHeightPx = (sampleBmp?.height ?: 0) * scale
        val canvasHeightDp = with(LocalDensity.current) {
            (digitHeightPx * 2 + lineSpacing).toDp()
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
                state.clockColorOverrideState.value
                if (time.isEmpty() || !TextUtils.isDigitsOnly(time)) return@Canvas

                val (hours, minutes) = splitTimeLines(time)
                if (hours.isEmpty()) return@Canvas

                val hoursWidth = computeLineWidth(hours, bitmaps, scale, finalSpacing)
                val minutesWidth = computeLineWidth(minutes, bitmaps, scale, finalSpacing)

                val sampleBitmap = bitmaps['0'] ?: return@Canvas
                val digitHeight = sampleBitmap.height * scale

                val hoursX = (size.width - hoursWidth) / 2f
                val minutesX = (size.width - minutesWidth) / 2f

                drawDigitLine(hours, bitmaps, scale, hoursX, 0f, finalSpacing, tintColor)
                drawDigitLine(minutes, bitmaps, scale, minutesX, digitHeight + lineSpacing, finalSpacing, tintColor)
            }

            if (hasInfo) {
                Spacer(modifier = Modifier.height(16.dp))
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
    private fun LargeFontContent(config: BitmapFaceConfig, mode: RenderMode.FontDigit) {
        val (time, date, isDoze, screenOff, regionDark) = rememberClockState()
        val display = viewModel.rememberResolvedDisplay()
        val hasInfo = display !is DateDisplay.Hidden

        val tintColor = androidColorToComposeColor(
            config.clockColor(isDoze, screenOff, regionDark)
        )

        val scale = context.scaleRatio
        val largeFontSize = mode.fontSize * scale * mode.largeScale
        val fontLayout = remember(largeFontSize, mode.lineSpacing, scale, mode.fontPath, mode.lsFontWeight) {
            val tempPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = largeFontSize
                typeface = getCachedTypeface(mode.lsFontWeight, mode.fontPath)
            }
            val bounds = Rect()
            var topMin = Int.MAX_VALUE
            var bottomMax = Int.MIN_VALUE
            var advanceMax = 0f
            for (d in '0'..'9') {
                tempPaint.getTextBounds(d.toString(), 0, 1, bounds)
                if (bounds.top < topMin) topMin = bounds.top
                if (bounds.bottom > bottomMax) bottomMax = bounds.bottom
                val adv = tempPaint.measureText(d.toString())
                if (adv > advanceMax) advanceMax = adv
            }
            val visualHeight = (bottomMax - topMin).toFloat()
            val canvasPx = visualHeight * 2f + mode.lineSpacing * scale
            LargeFontLayout(
                visualTopOffset = topMin.toFloat(),
                visualHeight = visualHeight,
                cellWidth = advanceMax,
                canvasHeightDp = canvasPx / context.resources.displayMetrics.density,
            )
        }
        val canvasHeightDp = fontLayout.canvasHeightDp

        Column(
            modifier = Modifier.fillMaxWidth().wrapContentHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(canvasHeightDp.dp),
            ) {
                weightVersion.intValue
                state.clockColorOverrideState.value
                if (time.isEmpty() || !TextUtils.isDigitsOnly(time)) return@Canvas

                val nativeCanvas = drawContext.canvas.nativeCanvas
                fontPaint.textSize = largeFontSize
                fontPaint.typeface = getCachedTypeface(mode.lsFontWeight, mode.fontPath)
                fontPaint.color = config.clockColor(isDoze, screenOff, regionDark)

                val displayTime = formatDisplayTime(time)
                if (displayTime.isEmpty()) return@Canvas

                val (hours, minutes) = splitTimeLines(displayTime)
                if (hours.isEmpty()) return@Canvas

                val cellWidth = fontLayout.cellWidth
                val hoursBaselineY = -fontLayout.visualTopOffset
                val minutesBaselineY = hoursBaselineY + fontLayout.visualHeight + mode.lineSpacing * scale

                fun drawCenteredLine(digits: String, baselineY: Float, weightStart: Int) {
                    if (digits.isEmpty()) return
                    val lineWidth = cellWidth * digits.length
                    var cellX = (size.width - lineWidth) / 2f
                    for (i in digits.indices) {
                        fontPaint.typeface = getCachedTypeface(
                            fontWeights.getOrElse(weightStart + i) { mode.lsFontWeight },
                            mode.fontPath,
                        )
                        val glyphW = fontPaint.measureText(digits[i].toString())
                        val glyphX = cellX + (cellWidth - glyphW) / 2f
                        drawFontDigit(nativeCanvas, digits[i], glyphX, baselineY, mode)
                        cellX += cellWidth
                    }
                }

                drawCenteredLine(hours, hoursBaselineY, 0)
                drawCenteredLine(minutes, minutesBaselineY, hours.length)
            }

            if (hasInfo) {
                Spacer(modifier = Modifier.height(16.dp))
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
    private fun LargeAnalogContent(config: BitmapFaceConfig) {
        val (time, date, isDoze, screenOff, regionDark) = rememberClockState()
        val display = viewModel.rememberResolvedDisplay()
        val hasInfo = display !is DateDisplay.Hidden

        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Box(modifier = Modifier.fillMaxSize().analogDrawModifier(config, time, isDoze, screenOff, regionDark))

            val tintColor = androidColorToComposeColor(
                config.clockColor(isDoze, screenOff, regionDark)
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (hasInfo) {
                    EnhancedDateArea(
                        textColor = tintColor,
                        textSize = 16.sp,
                        iconSize = 18.dp,
                        rowArrangement = Arrangement.Center,
                    )
                }
            }
        }
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

    private fun analogFaceHeightPx(config: BitmapFaceConfig, scale: Float = 1f): Float {
        val fallbackHeight = context.resources.getDimension(R.dimen.clock_height)
        val tickRes = config.tickResIds.firstOrNull() ?: return fallbackHeight * scaleRatio * scale
        return (ContextCompat.getDrawable(context, tickRes)?.intrinsicHeight?.toFloat()
            ?: fallbackHeight) * scaleRatio * scale
    }

    private fun Modifier.analogDrawModifier(
        config: BitmapFaceConfig,
        time: String,
        isDoze: Boolean,
        screenOff: Boolean,
        regionDark: Boolean,
    ): Modifier = drawWithContent {
        drawContent()
        state.clockColorOverrideState.value
        if (time.isEmpty() || !TextUtils.isDigitsOnly(time)) return@drawWithContent

        val canvas = drawContext.canvas.nativeCanvas
        val clockSz = min(size.width, size.height)
        val cx = size.width / 2f
        val cy = size.height / 2f
        val color = config.clockColor(isDoze, screenOff, regionDark)
        val highlightColor = ContextCompat.getColor(context, R.color.clock_dot_color)
        val bitmaps = createBitmaps(context, config.tickResIds)
        val tick = bitmaps.getOrNull(0)
        val tickLight = bitmaps.getOrNull(1)

        val baseScale = context.scaleRatio * sizeScale
        val tickNaturalW = (tick?.width ?: 0) * baseScale
        val fitScale = fitToWidth(tickNaturalW)
        val scale = baseScale * fitScale
        val dotSize = context.scaledDimen(R.dimen.dot_size) * sizeScale * fitScale
        val handSize = context.scaledDimen(R.dimen.clock_hand_size) * sizeScale * fitScale

        if (tick != null && tickLight != null) {
            tickPaint.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
            val tw = tick.width * scale
            val left = cx - tw / 2f
            val top = cy - tick.height * scale / 2f
            val matrix = Matrix().apply {
                postScale(scale, scale)
                postTranslate(left, top)
            }
            if (!isDoze && !screenOff) {
                canvas.drawBitmap(tickLight, matrix, tickPaint)
            }
            canvas.drawBitmap(tick, matrix, tickPaint)
        }

        dotPaint.color = color
        dotPaint.xfermode = null
        val outerR = dotSize * 1.5f / 2f
        canvas.drawOval(
            RectF(cx - outerR, cy - outerR, cx + outerR, cy + outerR),
            dotPaint,
        )

        try {
            val seconds = time.takeLast(2).toInt()
            val minutes = time.dropLast(2).takeLast(2).toInt()
            val hours = time.dropLast(4).toInt()
            val hourAngle = (hours + minutes / 60f) * 5
            val minuteAngle = minutes + seconds / 60f

            drawClockHand(canvas, handPaint, hourAngle, handSize * 2, 0.22f, 0.38f, color, cx, cy, clockSz)
            drawClockHand(canvas, handPaint, minuteAngle, handSize, 0.22f, 0.42f, color, cx, cy, clockSz)
            drawClockHand(canvas, handPaint, seconds.toFloat(), handSize / 2, 0.19f, 0.42f, highlightColor, cx, cy, clockSz)
        } catch (_: NumberFormatException) {}

        if (!isDoze && !screenOff) {
            dotPaint.color = highlightColor
            dotPaint.xfermode = null
        } else {
            dotPaint.color = android.graphics.Color.TRANSPARENT
            dotPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        }
        val innerR = dotSize / 2f
        canvas.drawOval(
            RectF(cx - innerR, cy - innerR, cx + innerR, cy + innerR),
            dotPaint,
        )
    }

    private fun formatDisplayTime(time: String): String {
        if (time.length < 3) return time
        if (!DateFormat.is24HourFormat(context)) {
            val hour = time.substring(0, 2).toIntOrNull() ?: return time
            if (hour < 10) return time.drop(1)
        }
        return time
    }

    private fun drawFontDigit(
        canvas: AndroidCanvas,
        digit: Char,
        x: Float,
        baselineY: Float,
        mode: RenderMode.FontDigit,
    ) {
        val digitText = digit.toString()
        if (!shouldNormalizeDigitHeight(mode, digit)) {
            canvas.drawText(digitText, x, baselineY, fontPaint)
            return
        }

        val digitBounds = Rect()
        fontPaint.getTextBounds(digitText, 0, 1, digitBounds)
        val digitHeight = digitBounds.height().toFloat()
        val visualBounds = getFontDigitVisualBounds(fontPaint)
        if (digitHeight <= 0f || visualBounds.height <= digitHeight) {
            canvas.drawText(digitText, x, baselineY, fontPaint)
            return
        }

        val targetCenterY = baselineY + (visualBounds.top + visualBounds.bottom) / 2f
        val digitCenterFromBaseline = (digitBounds.top + digitBounds.bottom) / 2f
        val drawBaselineY = targetCenterY - digitCenterFromBaseline
        val scaleY = visualBounds.height / digitHeight

        canvas.save()
        canvas.scale(1f, scaleY, x + fontPaint.measureText(digitText) / 2f, targetCenterY)
        canvas.drawText(digitText, x, drawBaselineY, fontPaint)
        canvas.restore()
    }

    private fun shouldNormalizeDigitHeight(mode: RenderMode.FontDigit, digit: Char): Boolean =
        digit in mode.normalizedDigits

    private fun getFontDigitVisualBounds(paint: Paint): FontDigitVisualBounds {
        val bounds = Rect()
        var topMin = Int.MAX_VALUE
        var bottomMax = Int.MIN_VALUE
        for (digit in '0'..'9') {
            paint.getTextBounds(digit.toString(), 0, 1, bounds)
            if (bounds.top < topMin) topMin = bounds.top
            if (bounds.bottom > bottomMax) bottomMax = bounds.bottom
        }
        return FontDigitVisualBounds(topMin.toFloat(), bottomMax.toFloat())
    }

    override fun onDozeAmountChanged(linear: Float, eased: Float) {
        super.onDozeAmountChanged(linear, eased)
        val mode = fontDigitMode ?: return
        dozingAmountChanging = true
        if (!isWakingUp) handler?.removeCallbacks(forceResetRunnable)

        val target = if (linear == 0f) mode.lsFontWeight
            else if (linear == 1f) mode.aodFontWeight
            else null

        if (target != null) {
            for (i in 0 until DIGIT_COUNT) {
                val anim = digitAnimators[i]
                if (anim != null && anim.isRunning) continue
                if (fontWeights[i] != target) {
                    animateDigitTo(i, target, SETTLE_ANIM_MS)
                }
            }
            dozingAmountChanging = false
            return
        }

        if (!isWakingUp && !isKeyguardVisibleState) return

        val hasDigitRects = digitRects.any { it != null }
        if (!hasDigitRects) {
            val weight = if (isWakingUp) {
                (mode.aodFontWeight + (mode.lsFontWeight - mode.aodFontWeight) * (1f - linear)).toInt()
            } else {
                (mode.lsFontWeight - (mode.lsFontWeight - mode.aodFontWeight) * linear).toInt()
            }
            for (i in 0 until DIGIT_COUNT) {
                fontWeights[i] = weight
            }
            weightVersion.intValue++
            return
        }

        val targetWeight = if (isWakingUp) mode.lsFontWeight else mode.aodFontWeight
        val radius = maxRadius * (1f - linear)
        for (i in 0 until DIGIT_COUNT) {
            val rect = digitRects[i] ?: continue
            val reached = if (isWakingUp) {
                circleIntersectsRect(tapPos.x.toFloat(), tapPos.y.toFloat(), radius, rect)
            } else {
                circleNotFullyContainsRect(tapPos.x.toFloat(), tapPos.y.toFloat(), radius, rect)
            }
            if (reached && !digitAnimStarted[i]) {
                animateDigitTo(i, targetWeight, RIPPLE_ANIM_MS)
                digitAnimStarted[i] = true
            }
        }
    }

    override fun onStartedWakingUp() {
        super.onStartedWakingUp()
        if (fontDigitMode == null) return
        isWakingUp = true
        dozingAmountChanging = false
        handler?.postDelayed(forceResetRunnable, FORCE_RESET_DELAY_MS)
    }

    override fun onStartedGoingToSleep(isKeyguardVisible: Boolean) {
        val mode = fontDigitMode ?: return
        isWakingUp = false
        dozingAmountChanging = false
        isKeyguardVisibleState = isKeyguardVisible
        if (!isKeyguardVisible) {
            for (i in 0 until DIGIT_COUNT) {
                animateDigitTo(i, mode.aodFontWeight, SETTLE_ANIM_MS)
            }
        }
    }

    override fun onWakefulnessStateChanged(isWakingUp: Boolean, tapPosition: Point?) {
        if (fontDigitMode == null) return
        this.isWakingUp = isWakingUp
        val pos = tapPosition ?: Point(screenWidth / 2, screenHeight / 2)
        digitAnimStarted.fill(false)
        cancelDigitAnimators()
        maxRadius = maxOf(
            pos.x, screenWidth - pos.x,
            pos.y, screenHeight - pos.y
        )
        tapPos = pos
    }

    override fun onFidgetAnimation() {
        animateWeightPulse()
    }

    private fun animateWeightPulse() {
        val mode = fontDigitMode ?: return
        fidgetWeightAnimator?.cancel()

        val squeeze = ValueAnimator.ofInt(mode.lsFontWeight, FIDGET_THIN_WEIGHT).apply {
            duration = FIDGET_WEIGHT_DURATION
            interpolator = FIDGET_INTERPOLATOR
            addUpdateListener { anim ->
                val w = anim.animatedValue as Int
                for (i in 0 until DIGIT_COUNT) fontWeights[i] = w
                weightVersion.intValue++
            }
        }
        val restore = ValueAnimator.ofInt(FIDGET_THIN_WEIGHT, mode.lsFontWeight).apply {
            duration = FIDGET_WEIGHT_DURATION
            interpolator = FIDGET_INTERPOLATOR
            addUpdateListener { anim ->
                val w = anim.animatedValue as Int
                for (i in 0 until DIGIT_COUNT) fontWeights[i] = w
                weightVersion.intValue++
            }
        }

        fidgetWeightAnimator = AnimatorSet().apply {
            playSequentially(squeeze, restore)
            start()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        fidgetWeightAnimator?.cancel()
        handler?.removeCallbacks(forceResetRunnable)
        cancelDigitAnimators()
    }

    override fun getTag(): String =
        if (isLargeClock) "BitmapDigitComposeLargeClockView" else "BitmapDigitComposeClockView"

    companion object {
        private const val MAX_TABLET_SCALE = 1.2f
        private const val DATE_ROW_DP = 24f
        private const val DIGIT_COUNT = 4
        private const val WEIGHT_SNAP = 10
        private const val RIPPLE_ANIM_MS = 500L
        private const val SETTLE_ANIM_MS = 300L
        private const val FORCE_ANIM_MS = 350L
        private const val FORCE_RESET_DELAY_MS = 800L
        private const val FIDGET_THIN_WEIGHT = 300
        private const val FIDGET_WEIGHT_DURATION = 250L
        private val RIPPLE_INTERPOLATOR = PathInterpolator(0.6f, 0f, 0.2f, 1f)
        private val FIDGET_INTERPOLATOR = PathInterpolator(0.26873f, 0f, 0.45042f, 1f)
    }
}
