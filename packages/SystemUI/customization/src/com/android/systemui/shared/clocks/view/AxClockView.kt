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
import android.icu.util.TimeZone
import android.text.format.DateFormat
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
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
import com.android.systemui.shared.clocks.ClockSettingsRepository
import com.android.systemui.shared.clocks.extensions.*
import java.util.Locale
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
    var onFidgetTapListener: ((Float, Float) -> Unit)? = null

    private val depthController = ClockDepthController(this)
    var depthEffectEnabled: Boolean
        get() = depthController.enabled
        set(value) { depthController.enabled = value }

    protected open val clockHeightBase: Int get() = context.scaledDimenInt(R.dimen.clock_height)
    val clockPaddingTop get() = context.scaledDimen(R.dimen.clock_padding_top)
    val clockPaddingStart get() = context.scaledDimen(R.dimen.clock_padding_start)
    val clockDateTextSize get() = context.scaledDimen(R.dimen.clock_date_text_size)
    val clockDateMarginTop get() = context.scaledDimen(R.dimen.clock_date_margin_top)
    val scaleRatio get() = context.scaleRatio
    val sizeScale get() = when {
        isPreviewMode -> 1f
        isLargeClock -> 1f
        else -> ClockSettingsRepository.sizeScale.value
    }
    val iconSize get() = context.scaledDimenInt(R.dimen.clock_icon_secondary_size)

    protected val config: ClockConfigs.ClockStyleConfig?
        get() {
            val className = this::class.simpleName ?: return null
            return ClockConfigs.resolveConfig(className, isLargeClock, state.alignmentState.value)
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

    internal open val useGlitchInteraction: Boolean = false

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
    open fun onChargeAnimation() {}
    open fun onPulsingChanged(doze: Boolean) {}
    open fun onScreenOff(screenOff: Boolean) { interactor.onScreenOff(screenOff) }
    open fun onRegionDarknessChanged(regionDark: Boolean) { interactor.onRegionDarknessChanged(regionDark) }
    open fun onFontSettingChanged() { interactor.onFontSettingChanged() }
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
        }
    }

    fun refreshDate() { interactor.refreshDate() }

    open fun animateFidgetTap(x: Float, y: Float) = animateFidgetTapDefault(x, y)

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
            ClockSettingsRepository.sizeScale.collect { requestLayout() }
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
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val newLocale = newConfig.locale
        if (newLocale != interactor.locale) {
            uiScope?.launch { refreshFormat(DateFormat.is24HourFormat(context), newLocale) }
        }
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
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        pivotX = w / 2f
        pivotY = h / 2f
    }

    protected open fun getContentBounds(): RectF? = null

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
        if (isLargeClock || isPreviewMode) return Modifier
        val scaleValue by ClockSettingsRepository.sizeScale.collectAsState()
        if (scaleValue == 1f) return Modifier
        return Modifier.graphicsLayer {
            scaleX = scaleValue
            scaleY = scaleValue
        }
    }

    protected val fidgetTapModifier: Modifier
        @Composable get() = fidgetTapModifierFor(null)

    @Composable
    protected fun fidgetTapModifierFor(boundsProvider: (() -> Rect?)?): Modifier {
        return Modifier.pointerInput(touchEnabled) {
            if (!touchEnabled) return@pointerInput
            detectTapGestures { offset ->
                if (state.isDoze) return@detectTapGestures
                val bounds = boundsProvider?.invoke()
                if (bounds != null && !bounds.contains(offset)) return@detectTapGestures
                onFidgetTapListener?.invoke(offset.x, offset.y)
            }
        }
    }

    @Composable
    protected fun DigitArea(
        modifier: Modifier = Modifier,
        content: @Composable () -> Unit,
    ) {
        Box(modifier = modifier.then(fidgetTapModifier)) {
            content()
        }
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
    }
}
