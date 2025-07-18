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
package com.android.systemui.pulse

import android.content.Context
import android.graphics.Color
import android.util.Log
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.media.MediaSessionManager
import com.android.systemui.util.ScrimUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.math.log10
import kotlin.math.roundToInt

data class PulseUiState(
    val isEnabled: Boolean = false,
    val isVisible: Boolean = false,
    val barHeights: FloatArray = floatArrayOf(),
    val barCount: Int = 32,
    val roundedBars: Boolean = true,
    val barColor: Int = Color.WHITE,
    val colorMode: PulseColorMode = PulseColorMode.LAVALAMP,
    val style: PulseStyle = PulseStyle.BARS,
    val smoothingFactor: Float = PulseConstants.BASE_SMOOTHING_FACTOR
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PulseUiState) return false
        return isEnabled == other.isEnabled &&
               isVisible == other.isVisible &&
               barHeights.contentEquals(other.barHeights) &&
               barCount == other.barCount &&
               roundedBars == other.roundedBars &&
               barColor == other.barColor &&
               colorMode == other.colorMode &&
               style == other.style &&
               smoothingFactor == other.smoothingFactor
    }

    override fun hashCode(): Int {
        var result = isEnabled.hashCode()
        result = 31 * result + isVisible.hashCode()
        result = 31 * result + barHeights.contentHashCode()
        result = 31 * result + barCount
        result = 31 * result + roundedBars.hashCode()
        result = 31 * result + barColor
        result = 31 * result + colorMode.hashCode()
        result = 31 * result + style.hashCode()
        result = 31 * result + smoothingFactor.hashCode()
        return result
    }
}

@SysUISingleton
class PulseInteractor @Inject constructor(
    @Application private val context: Context,
    @Application private val scope: CoroutineScope,
    private val settingsRepository: PulseSettingsRepository,
    private val displayRepository: PulseDisplayRepository,
    private val audioProcessor: PulseAudioDataProcessor
) : MediaSessionManager.MediaDataListener,
    ScrimUtils.ScrimEventListener {

    companion object {
        private const val TAG = "PulseInteractor"
    }

    private val _uiState = MutableStateFlow(PulseUiState())
    val uiState: StateFlow<PulseUiState> = _uiState.asStateFlow()

    private val accentColor: Int
        get() = context.getColor(android.R.color.system_accent1_100)

    private val pulsing: Boolean
        get() = ScrimUtils.get().isPulsing()

    private val keyguardShowing: Boolean
        get() = ScrimUtils.get().isKeyguardShowing()

    private val panelFullyCollapsed: Boolean
        get() = ScrimUtils.get().isPanelFullyCollapsed()

    private val screenAwake: Boolean
        get() = ScrimUtils.get().isAwake()

    private val mediaPlaying: Boolean
        get() = MediaSessionManager.get().isMediaPlaying

    private var bouncerShowingOrKeyguardDismissing = false

    private val shouldShowPulse: Boolean
        get() = _uiState.value.isEnabled && mediaPlaying && (keyguardShowing || pulsing) && 
                panelFullyCollapsed && !bouncerShowingOrKeyguardDismissing && screenAwake

    private val processingScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var fftAverage: Array<FFTAverage>? = null
    private var pulseRunning = false

    init {
        Log.d(TAG, "init: keyguard=$keyguardShowing, collapsed=$panelFullyCollapsed, " +
            "media=$mediaPlaying, awake=$screenAwake")

        ScrimUtils.get().addListener(this)
        MediaSessionManager.get().addListener(this)
        
        observeSettings()
        observeDisplayState()
        observeAudioData()
    }

    private fun observeAudioData() {
        scope.launch {
            audioProcessor.audioDataFlow.collect { data ->
                if (pulseRunning && data.isDataValid) {
                    processingScope.launch {
                        processFFT(data.fftBytes!!)
                    }
                }
            }
        }
    }

    private fun observeSettings() {
        scope.launch {
            settingsRepository.settingsFlow.collect { settings ->
                Log.d(TAG, "settings updated: enabled=${settings.isEnabled}")
                _uiState.update { current ->
                    current.copy(
                        isEnabled = settings.isEnabled,
                        barCount = settings.barCount,
                        roundedBars = settings.roundedBars,
                        colorMode = settings.colorMode,
                        style = settings.style,
                        barColor = when (settings.colorMode) {
                            PulseColorMode.ACCENT -> accentColor
                            else -> current.barColor
                        }
                    )
                }
                updatePulseState()
            }
        }
    }

    private fun observeDisplayState() {
        scope.launch {
            displayRepository.displayState.collect { displayState ->
                _uiState.update { it.copy(smoothingFactor = displayState.smoothingFactor) }
            }
        }
    }

    private fun updatePulseState() {
        val shouldShow = shouldShowPulse

        Log.d(TAG, "updatePulseState: shouldShow=$shouldShow, pulseRunning=$pulseRunning, " +
            "enabled=${_uiState.value.isEnabled}, media=$mediaPlaying, keyguard=$keyguardShowing, " +
            "collapsed=$panelFullyCollapsed, bouncer=$bouncerShowingOrKeyguardDismissing, awake=$screenAwake")
        
        if (shouldShow != pulseRunning) {
            pulseRunning = shouldShow
            if (shouldShow) {
                Log.d(TAG, "starting pulse")
                audioProcessor.startCapture()
            } else {
                Log.d(TAG, "stopping pulse")
                audioProcessor.stopCapture()
            }
            _uiState.update { it.copy(isVisible = shouldShow) }
        }
    }

    private fun stopPulseImmediate(reason: String) {
        if (!pulseRunning) return
        Log.d(TAG, "stopPulseImmediate: $reason")
        pulseRunning = false
        audioProcessor.stopCapture()
        _uiState.update { it.copy(isVisible = false) }
    }

    private suspend fun processFFT(data: ByteArray) {
        val barCount = _uiState.value.barCount
        if (fftAverage == null || fftAverage!!.size != barCount) {
            fftAverage = Array(barCount) { FFTAverage() }
        }

        val heights = FloatArray(barCount)
        for (i in 0 until barCount) {
            val realIndex = i * 2 + 2
            val imagIndex = i * 2 + 3
            if (realIndex >= data.size || imagIndex >= data.size) continue
            val rfk = data[realIndex].toInt()
            val ifk = data[imagIndex].toInt()
            val magnitude = (rfk * rfk + ifk * ifk).toFloat()
            var dbValue = if (magnitude > 0) (10 * log10(magnitude.toDouble())).toInt() else 0
            dbValue = fftAverage!![i].average(dbValue)
            heights[i] = dbValue * PulseConstants.FFT_FUDGE_FACTOR.toFloat()
        }

        withContext(Dispatchers.Main.immediate) {
            _uiState.update { it.copy(barHeights = heights) }
        }
    }

    override fun onPlaybackStateChanged(state: Int) {
        updatePulseState()
    }

    override fun onMediaColorsChanged(color: Int) {
        if (_uiState.value.colorMode == PulseColorMode.ALBUM) {
            _uiState.update { it.copy(barColor = color) }
        }
    }

    override fun onKeyguardShowingChanged(showing: Boolean) {
        Log.d(TAG, "onKeyguardShowingChanged: $showing")
        if (!showing) {
            bouncerShowingOrKeyguardDismissing = false
            stopPulseImmediate("keyguard hidden")
        } else {
            updatePulseState()
        }
    }

    override fun onPrimaryBouncerShowingChanged(showing: Boolean) {
        Log.d(TAG, "onPrimaryBouncerShowingChanged: $showing")
        bouncerShowingOrKeyguardDismissing = showing
        updatePulseState()
    }

    override fun onKeyguardFadingAwayChanged(fadingAway: Boolean) {
        Log.d(TAG, "onKeyguardFadingAwayChanged: $fadingAway")
        bouncerShowingOrKeyguardDismissing = fadingAway
        if (fadingAway) stopPulseImmediate("keyguard fading away")
    }

    override fun onKeyguardGoingAwayChanged(goingAway: Boolean) {
        Log.d(TAG, "onKeyguardGoingAwayChanged: $goingAway")
        bouncerShowingOrKeyguardDismissing = goingAway
        if (goingAway) stopPulseImmediate("keyguard going away")
    }

    override fun onExpandedFractionChanged(expandedFraction: Float) {
        updatePulseState()
    }

    override fun onQsVisibilityChanged(visible: Boolean) {
        Log.d(TAG, "onQsVisibilityChanged: $visible")
        updatePulseState()
    }

    override fun onScreenTurnedOff() {
        Log.d(TAG, "onScreenTurnedOff")
        stopPulseImmediate("screen off")
    }

    override fun onStartedWakingUp() {
        Log.d(TAG, "onStartedWakingUp")
        updatePulseState()
    }

    override fun setPulsing(pulsing: Boolean) {
        Log.d(TAG, "setPulsing: $pulsing")
        updatePulseState()
    }

    private class FFTAverage {
        private val window = ArrayDeque<Float>(PulseConstants.FFT_WINDOW_LENGTH)
        private var average = 0f

        fun average(db: Int): Int {
            if (window.size >= PulseConstants.FFT_WINDOW_LENGTH) {
                val removed = window.removeFirst()
                average -= removed
            }

            val newVal = db / PulseConstants.FFT_WINDOW_LENGTH.toFloat()
            average += newVal
            window.addLast(newVal)

            return average.roundToInt()
        }
    }
}
