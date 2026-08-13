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

import android.content.Context
import android.content.res.Configuration
import android.graphics.*
import android.graphics.Color as AndroidColor
import android.icu.util.TimeZone
import android.text.format.DateFormat
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.systemui.customization.R
import com.android.systemui.log.core.MessageBuffer
import com.android.systemui.plugins.keyguard.data.model.AlarmData
import com.android.systemui.plugins.keyguard.ui.clocks.*
import com.android.systemui.shared.clocks.ClockConfigs
import com.android.systemui.shared.clocks.ClockEditScaleGeometry
import com.android.systemui.shared.clocks.ClockSettingsRepository
import com.android.systemui.shared.clocks.extensions.*
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.*

abstract class AxClockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    defStyleRes: Int = 0
) : ViewGroup(context, attrs, defStyleAttr, defStyleRes) {

    val state = AxClockState()
    val quickLook = QuickLookController(this)
    val interactor = AxClockInteractor(context, state, quickLook)
    val viewModel = AxClockViewModel(state, quickLook)
    internal val host = AxClockHost(this)

    private var uiScope: CoroutineScope? = null

    var isLargeClock = false

    var isPreviewMode = false
        set(value) {
            field = value
            if (value) {
                touchEnabled = false
                depthEffectEnabled = false
                animAlpha = 1f
            }
        }

    var animAlpha: Float = 1f
        set(value) {
            if (isPreviewMode && value != 1f) return
            field = value
            alpha = value
        }

    var touchEnabled: Boolean = true
    var previewSizeScaleOverride: Float? by mutableStateOf(null)
    var previewAlignmentFrozen = false
        set(value) {
            if (field == value) return
            field = value
            if (!value) updatePreviewAlignment()
        }
    private val previewHorizontalOffsetDpOverrideState = mutableStateOf<Float?>(null)
    var previewHorizontalOffsetDpOverride: Float?
        get() = previewHorizontalOffsetDpOverrideState.value
        set(value) {
            previewHorizontalOffsetDpOverrideState.value = value
            if (!previewAlignmentFrozen) updatePreviewAlignment()
        }

    private fun updatePreviewAlignment() {
        state.alignmentState.value = ClockSettingsRepository.horizontalPositionAlignment(
            previewHorizontalOffsetDpOverride ?: ClockSettingsRepository.horizontalOffsetDp.value,
        )
    }

    var renderedClockEditGeometryVersion by mutableIntStateOf(0)
        private set

    private var renderedClockBaseWidthDp by mutableStateOf(0f)
    private var renderedClockBaseHeightDp by mutableStateOf(0f)
    private var renderedClockCenterYFraction by mutableStateOf(0.5f)
    private var renderedClockBoundsDirty = true
    private var renderedClockCapturePosted = false
    private var renderedClockBitmap: Bitmap? = null
    private var renderedClockPixels = IntArray(0)

    private val depthController = ClockDepthController(this)
    var depthEffectEnabled: Boolean
        get() = depthController.enabled
        set(value) { depthController.enabled = value }
    var depthSourceBoundsProvider: (() -> RectF?)?
        get() = depthController.sourceBoundsProvider
        set(value) { depthController.sourceBoundsProvider = value }
    var depthSourceScale: Float
        get() = depthController.sourceScale
        set(value) { depthController.sourceScale = value }

    protected open val clockHeightBase: Int get() = context.scaledDimenInt(R.dimen.clock_height)
    val clockPaddingTop get() = context.scaledDimen(R.dimen.clock_padding_top)
    val clockPaddingStart get() = context.scaledDimen(R.dimen.clock_padding_start)
    val clockDateTextSize get() = context.scaledDimen(R.dimen.clock_date_text_size)
    val clockDateMarginTop get() = context.scaledDimen(R.dimen.clock_date_margin_top)
    val scaleRatio get() = context.scaleRatio
    val sizeScale: Float get() = clockScaleState(ClockSettingsRepository.sizeScale.value).value
    val iconSize get() = context.scaledDimenInt(R.dimen.clock_icon_secondary_size)

    protected val config: ClockConfigs.ClockStyleConfig?
        get() {
            val className = this::class.simpleName ?: return null
            return ClockConfigs.resolveConfig(
                className,
                isLargeClock,
                state.alignmentState.value,
            )
        }

    val isLeftAligned: Boolean get() = config?.align == ClockConfigs.Align.LEFT
    val isRightAligned: Boolean get() = config?.align == ClockConfigs.Align.RIGHT
    val isSideAligned: Boolean get() = isLeftAligned || isRightAligned

    val clockHeight: Int
        get() {
            val resHeight = config?.customHeightRes?.let { context.scaledDimenInt(it) } ?: clockHeightBase
            val bottomPad = if (!isLargeClock) {
                (SMALL_CLOCK_BOTTOM_PAD_DP * context.resources.displayMetrics.density).toInt()
            } else 0
            return ((resHeight + dateHeight) * sizeScale).toInt() + bottomPad
        }

    val dateMarginTop: Int
        get() {
            val cfg = config ?: return 0
            if (!cfg.visible) return 0
            return (cfg.customDateMarginTop?.let { context.scaledDimen(it) } ?: clockDateMarginTop).toInt()
        }

    val dateHeight: Int
        get() {
            val cfg = config ?: return 0
            if (!cfg.visible) return 0
            return when (cfg.position) {
                ClockConfigs.Position.ABOVE -> (clockDateTextSize + dateMarginTop + clockPaddingTop).toInt()
                ClockConfigs.Position.BELOW -> clockDateTextSize.toInt()
                else -> 0
            }
        }

    var isDoze: Boolean
        get() = state.isDoze
        set(value) { state.isDoze = value }
    var isScreenOff: Boolean
        get() = state.isScreenOff
        set(value) { state.isScreenOff = value }
    var isRegionDark: Boolean
        get() = state.isRegionDark
        set(value) { state.isRegionDark = value }
    val dateStr: String get() = state.dateStr

    init {
        host.attach { Content() }
    }

    abstract override fun getTag(): String

    @Composable
    protected abstract fun Content()

    open val animationSpec: AxClockAnimationSpec = AxClockAnimationSpecs.Default

    open fun onAlarmDataChanged(data: AlarmData) { interactor.onAlarmDataChanged(data) }
    open fun onClockDataChanged(data: ClockData) { interactor.onClockDataChanged(data) }
    open fun onDateChanged() {}
    open fun onThemeChanged(isDarkTheme: Boolean) {}
    open fun onPlaybackStateChanged(playing: Boolean) { interactor.onPlaybackStateChanged(playing) }
    open fun onMetadataChanged(track: String, artist: String, packageName: String) { interactor.onMetadataChanged(track, artist, packageName) }
    open fun onNowPlayingUpdate(npText: String) { interactor.onNowPlayingUpdate(npText) }
    open fun onClockLayoutChanged(isCentered: Boolean, isLargeClockVisible: Boolean) {}
    fun onDepthEffectVisibilityChanged(visible: Boolean) { depthController.setDepthVisible(visible) }
    fun setMessageBuffer(buffer: MessageBuffer) {}
    open fun onDozeChanged(doze: Boolean) { interactor.onDozeChanged(doze) }
    open fun onFidgetAnimation() {}
    open fun onChargeAnimation() {}
    open fun onPulsingChanged(doze: Boolean) {}
    open fun onScreenOff(screenOff: Boolean) { interactor.onScreenOff(screenOff) }
    open fun onRegionDarknessChanged(regionDark: Boolean) { interactor.onRegionDarknessChanged(regionDark) }
    open fun onFontSettingChanged(fontSizePx: Float = 0f) {
        interactor.onFontSettingChanged()
        onDisplayMetricsChanged()
    }
    open fun onTimeZoneChanged(timeZone: TimeZone) { interactor.onTimeZoneChanged(timeZone) }

    open fun onDozeAmountChanged(linear: Float, eased: Float) {
        if (isPreviewMode) return
        state.dozeAmountFlow.value = eased
    }

    open fun onStartedWakingUp() {
        interactor.onStartedWakingUp()
        uiScope?.launch {
            delay(1250)
            interactor.refreshTime()
        }
    }

    open fun onStartedGoingToSleep(isKeyguardVisible: Boolean) {}
    open fun onWakefulnessStateChanged(isWakingUp: Boolean, tapPosition: Point?) {}

    open fun refreshFormat(use24: Boolean, newLocale: Locale = interactor.locale) {
        interactor.needsSeconds = (this as? BitmapDigitComposeClockView)?.faceStyle?.needsPerSecondTick == true
        interactor.useStandardFormat = this is OldQuickLookClockView
        interactor.refreshFormat(use24, newLocale)
    }

    open fun refreshTime() {
        if (interactor.refreshTime()) {
            contentDescription = interactor.talkBackContent
            renderedClockBoundsDirty = true
            requestLayout()
        }
    }

    fun refreshDate() {
        interactor.refreshDate()
        renderedClockBoundsDirty = true
        requestLayout()
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        if (!touchEnabled) return false
        return super.dispatchTouchEvent(ev)
    }

    override fun draw(canvas: Canvas) {
        if (!depthController.shouldApplyDepth()) {
            super.draw(canvas)
            return
        }
        depthController.drawWithDepth(canvas) { super.draw(it) }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Log.d(tag, "onAttachedToWindow")
        ClockSettingsRepository.init(context)
        uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        (parent as? ViewGroup)?.let {
            it.clipChildren = false
            it.clipToPadding = false
        }
        depthController.onAttached()
        uiScope?.launch {
            ClockSettingsRepository.clockEditGeometryVersion.collect {
                renderedClockBoundsDirty = true
                requestLayout()
            }
        }
        refreshTime()
        state.timeState.value = interactor.timeStr
        state.dateBelowState.value = ClockSettingsRepository.isDateBelow.value
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        Log.d(tag, "onDetachedFromWindow")
        uiScope?.cancel()
        depthController.onDetached()
        renderedClockBitmap?.recycle()
        renderedClockBitmap = null
        renderedClockPixels = IntArray(0)
        renderedClockBoundsDirty = true
        renderedClockCapturePosted = false
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        ClockSettingsRepository.init(context)
        depthController.onConfigurationChanged()
        val newLocale = newConfig.locale
        if (newLocale != interactor.locale) {
            uiScope?.launch { refreshFormat(DateFormat.is24HourFormat(context), newLocale) }
        }
    }

    protected open fun onDisplayMetricsChanged() {
        state.configurationVersion.intValue++
        renderedClockBoundsDirty = true
        host.view.requestLayout()
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = getDefaultSize(suggestedMinimumWidth, widthMeasureSpec)
        val cv = host.view
        val mode = MeasureSpec.getMode(heightMeasureSpec)
        val maxH = MeasureSpec.getSize(heightMeasureSpec)

        cv.measure(
            MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
        )
        val naturalH = cv.measuredHeight
        val floor = if (isLargeClock) 0 else clockHeight
        val finalH = when (mode) {
            MeasureSpec.EXACTLY -> maxOf(naturalH, maxH, floor)
            else -> maxOf(naturalH, floor)
        }
        setMeasuredDimension(w, finalH)
        if (w > 0 && finalH > 0 && finalH != naturalH) {
            cv.measure(
                MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(finalH, MeasureSpec.EXACTLY),
            )
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val cv = host.view
        if (!cv.isAttachedToWindow) return
        if (!isLargeClock && (isPreviewMode)) {
            cv.measure(
                MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
            )
        }
        cv.layout(0, 0, width, height)
        scheduleRenderedClockBoundsCapture()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        pivotX = w / 2f
        pivotY = h / 2f
    }

    protected open fun getContentBounds(): RectF? = null

    open fun getClockEditScaleGeometry(
        availableWidthDp: Float,
        requestedScale: Float,
    ): ClockEditScaleGeometry =
        ClockEditScaleGeometry.default(
            availableWidthDp = availableWidthDp,
            requestedScale = requestedScale,
            scaleRange = ClockSettingsRepository.sizeScaleRange,
        )

    fun resolveClockEditScaleGeometry(
        availableWidthDp: Float,
        requestedScale: Float,
    ): ClockEditScaleGeometry {
        val baseWidthDp = renderedClockBaseWidthDp
        val baseHeightDp = renderedClockBaseHeightDp
        if (baseWidthDp <= 0f || baseHeightDp <= 0f) {
            return getClockEditScaleGeometry(availableWidthDp, requestedScale)
        }
        val range = ClockSettingsRepository.sizeScaleRange
        val fixedPaddingDp = CLOCK_EDIT_RENDERED_PADDING_DP * 2f
        val maxScale = if (availableWidthDp > fixedPaddingDp) {
            ((availableWidthDp - fixedPaddingDp) / baseWidthDp).coerceIn(range.min, range.max)
        } else {
            range.min
        }
        val scale = requestedScale.coerceIn(range.min, maxScale)
        return ClockEditScaleGeometry(
            scaleRange = range.copy(max = maxScale),
            requestedScale = scale,
            frameWidthDp = baseWidthDp * scale + fixedPaddingDp,
            minFrameWidthDp = 0f,
            resizeDpPerScale = baseWidthDp,
            frameHeightDp = baseHeightDp * scale + fixedPaddingDp,
            resizeHeightDpPerScale = baseHeightDp,
            frameCenterYFraction = renderedClockCenterYFraction,
        )
    }

    internal fun resolveHorizontalTranslationDp(
        offsetDp: Float,
        alignment: String,
        requestedScale: Float,
    ): Float {
        val density = context.resources.displayMetrics.density
        val availableWidthDp =
            if (width > 0) width / density
            else context.resources.configuration.screenWidthDp.toFloat()
        val frameWidthDp = resolveClockEditScaleGeometry(availableWidthDp, requestedScale)
            .frameWidthIn(availableWidthDp)
        val freeWidthDp = (availableWidthDp - frameWidthDp).coerceAtLeast(0f)
        val centeredStartDp = freeWidthDp / 2f
        val alignedStartDp = when (alignment) {
            ClockSettingsRepository.ALIGNMENT_LEFT -> 0f
            ClockSettingsRepository.ALIGNMENT_RIGHT -> freeWidthDp
            else -> centeredStartDp
        }
        val centeredOffsetDp = offsetDp.coerceIn(-centeredStartDp, centeredStartDp)
        return centeredOffsetDp + centeredStartDp - alignedStartDp
    }

    private fun scheduleRenderedClockBoundsCapture() {
        if (isLargeClock || width <= 0 || height <= 0 || renderedClockCapturePosted) return
        if (!isPreviewMode && ClockSettingsRepository.horizontalOffsetDp.value == 0f) return
        if (!renderedClockBoundsDirty && renderedClockBaseWidthDp > 0f) return
        renderedClockCapturePosted = true
        postOnAnimation {
            renderedClockCapturePosted = false
            if (!isAttachedToWindow) return@postOnAnimation
            captureRenderedClockBounds()
        }
    }

    private fun captureRenderedClockBounds() {
        val contentView = host.view
        val bitmapWidth = contentView.width
        val bitmapHeight = contentView.height
        if (bitmapWidth <= 0 || bitmapHeight <= 0) return
        val scale = sizeScale.coerceAtLeast(0.01f)

        val bitmap = renderedClockBitmap
            ?.takeIf { it.width == bitmapWidth && it.height == bitmapHeight }
            ?: Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888).also {
                renderedClockBitmap?.recycle()
                renderedClockBitmap = it
            }
        bitmap.eraseColor(AndroidColor.TRANSPARENT)
        contentView.draw(Canvas(bitmap))

        val pixelCount = bitmapWidth * bitmapHeight
        if (renderedClockPixels.size < pixelCount) {
            renderedClockPixels = IntArray(pixelCount)
        }
        bitmap.getPixels(renderedClockPixels, 0, bitmapWidth, 0, 0, bitmapWidth, bitmapHeight)

        var left = bitmapWidth
        var top = bitmapHeight
        var right = -1
        var bottom = -1
        for (index in 0 until pixelCount) {
            if ((renderedClockPixels[index] ushr 24) <= CLOCK_EDIT_ALPHA_THRESHOLD) continue
            val x = index % bitmapWidth
            val y = index / bitmapWidth
            if (x < left) left = x
            if (x > right) right = x
            if (y < top) top = y
            if (y > bottom) bottom = y
        }

        renderedClockBoundsDirty = false
        if (right < left || bottom < top) return

        val density = context.resources.displayMetrics.density
        val baseWidthDp = (right - left + 1) / density / scale
        val baseHeightDp = (bottom - top + 1) / density / scale
        val centerYFraction = (top + bottom + 1f) / (bitmapHeight * 2f)
        if (
            abs(renderedClockBaseWidthDp - baseWidthDp) <
                CLOCK_EDIT_BOUNDS_EPSILON_DP &&
                abs(renderedClockBaseHeightDp - baseHeightDp) <
                    CLOCK_EDIT_BOUNDS_EPSILON_DP &&
                abs(renderedClockCenterYFraction - centerYFraction) <
                    CLOCK_EDIT_CENTER_EPSILON
        ) {
            return
        }
        renderedClockBaseWidthDp = baseWidthDp
        renderedClockBaseHeightDp = baseHeightDp
        renderedClockCenterYFraction = centerYFraction
        renderedClockEditGeometryVersion++
    }

    open fun setupPreview() {
        interactor.setupPreview {
            isPreviewMode = true
            isDoze = false
            isScreenOff = false
            isRegionDark = false
        }
    }

    @Composable
    protected fun rememberClockState(): ClockUiState = viewModel.rememberClockState()

    @Composable
    protected fun tintColor(isDoze: Boolean, screenOff: Boolean, regionDark: Boolean): Color =
        viewModel.tintColor(isDoze, screenOff, regionDark)

    @Composable
    protected fun inverseSizeScaleModifier(): Modifier = Modifier

    @Composable
    protected fun digitScaleModifier(): Modifier {
        val scaleState = rememberClockScaleState()
        if (!scaleState.appliesToContent || scaleState.value == 1f) return Modifier
        return Modifier.graphicsLayer {
            scaleX = scaleState.value
            scaleY = scaleState.value
        }
    }

    @Composable
    protected fun rememberClockScaleState(): ClockScaleState {
        val repositoryScale by ClockSettingsRepository.sizeScale.collectAsState()
        return clockScaleState(repositoryScale)
    }

    @Composable
    protected fun rememberSmallClockSizeScale(): Float = rememberClockScaleState().value

    private fun clockScaleState(repositoryScale: Float): ClockScaleState {
        return ClockScaleState(
            isLargeClock = isLargeClock,
            isPreviewMode = isPreviewMode,
            repositoryScale = repositoryScale,
            previewOverride = previewSizeScaleOverride,
            range = ClockSettingsRepository.sizeScaleRange,
        )
    }

    @Composable
    protected fun EnhancedDateArea(
        modifier: Modifier = Modifier,
        textColor: Color = tintColor(state.dozeFlow.value, state.screenOffFlow.value, state.regionDarkFlow.value)
            .copy(alpha = if (state.dozeFlow.value) 0.6f else 0.8f),
        textSize: TextUnit = 18.sp,
        fontFamily: FontFamily = remember(state.fontVersion.intValue) { resolveDateFontFamily() },
        fontWeight: FontWeight = FontWeight.Medium,
        letterSpacing: TextUnit = 0.sp,
        iconSize: Dp = 16.dp,
        uppercase: Boolean = false,
        rowArrangement: Arrangement.Horizontal = when {
            isLeftAligned -> Arrangement.Start
            isRightAligned -> Arrangement.End
            else -> Arrangement.Center
        },
    ) {
        val display = viewModel.rememberResolvedDisplay()
        val inverseModifier = inverseSizeScaleModifier()
        QuickLookDateArea(
            modifier = modifier.then(inverseModifier),
            display = display,
            dateStr = state.dateStr,
            sizeScale = 1f,
            textColor = textColor,
            textSize = textSize,
            fontFamily = fontFamily,
            fontWeight = fontWeight,
            letterSpacing = letterSpacing,
            iconSize = iconSize,
            uppercase = uppercase,
            rowArrangement = rowArrangement,
        )
    }

    companion object {
        const val DEBUG = false
        private const val CLOCK_EDIT_ALPHA_THRESHOLD = 8
        private const val CLOCK_EDIT_BOUNDS_EPSILON_DP = 0.5f
        private const val CLOCK_EDIT_CENTER_EPSILON = 0.001f
        private const val CLOCK_EDIT_RENDERED_PADDING_DP = 12f
    }
}
