/*
 * Copyright (C) 2025-2026 AxionOS
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

import android.media.audiofx.Visualizer
import com.android.systemui.dagger.SysUISingleton
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.sqrt

@SysUISingleton
class PulseAudioDataProcessor @Inject constructor(
    private val displayRepository: PulseDisplayRepository
) {
    companion object {
        private const val MIN_FREQ_HZ = 60f
        private const val MAX_FREQ_HZ = 16000f
        private const val DB_FLOOR = -40f
        private const val DB_CEIL = 0f
        private const val EMA_ATTACK_SPEED = 55f
        private const val EMA_DECAY_SPEED = 10f
        private const val MAX_BAR_HEIGHT = 500f
    }

    private var visualizer: Visualizer? = null
    private var isProcessing = false
    private var lastUpdateTime = 0L
    private var smoothedBands: FloatArray? = null
    private var bandBinRanges: Array<IntRange>? = null
    private var lastBarCount = 0

    private val _audioDataFlow = MutableSharedFlow<FloatArray>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val audioDataFlow: SharedFlow<FloatArray> = _audioDataFlow.asSharedFlow()

    fun startCapture() {
        if (isProcessing) return

        try {
            visualizer = Visualizer(0).apply {
                captureSize = Visualizer.getCaptureSizeRange()[1]
                setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(
                        visualizer: Visualizer?,
                        waveform: ByteArray?,
                        samplingRate: Int
                    ) {}

                    override fun onFftDataCapture(
                        visualizer: Visualizer?,
                        fft: ByteArray?,
                        samplingRate: Int
                    ) {
                        if (fft != null && fft.size >= 4) {
                            processFFTData(fft, samplingRate)
                        }
                    }
                }, Visualizer.getMaxCaptureRate(), false, true)

                enabled = true
            }

            isProcessing = true
        } catch (_: Exception) {
            cleanup()
        }
    }

    fun stopCapture() {
        if (!isProcessing) return

        try {
            visualizer?.apply {
                enabled = false
                setDataCaptureListener(null, 0, false, false)
                release()
            }
        } catch (_: Exception) {}
        visualizer = null
        isProcessing = false
        smoothedBands = null
        bandBinRanges = null
    }

    fun cleanup() {
        stopCapture()
    }

    fun isCapturing(): Boolean = isProcessing

    private fun processFFTData(fftBytes: ByteArray, samplingRate: Int) {
        val currentTime = System.currentTimeMillis()
        val throttleMs = displayRepository.displayState.value.throttleMs
        if (currentTime - lastUpdateTime < throttleMs) return
        val dt = if (lastUpdateTime == 0L) {
            1f / 60f
        } else {
            ((currentTime - lastUpdateTime) / 1000f).coerceIn(0.001f, 0.1f)
        }
        lastUpdateTime = currentTime

        val barCount = lastBarCount
        if (barCount <= 0) return

        val numBins = fftBytes.size / 2
        val sampleRate = samplingRate / 1000f
        val binHz = sampleRate / (numBins * 2)

        if (bandBinRanges == null || bandBinRanges!!.size != barCount) {
            bandBinRanges = buildLogBands(barCount, numBins, binHz)
        }

        val heights = FloatArray(barCount)
        val ranges = bandBinRanges!!

        for (i in 0 until barCount) {
            val range = ranges[i]
            var sumMag = 0f
            var count = 0

            for (bin in range) {
                if (bin < 1 || bin >= numBins) continue
                val real = fftBytes[bin * 2].toFloat()
                val imag = fftBytes[bin * 2 + 1].toFloat()
                sumMag += sqrt(real * real + imag * imag)
                count++
            }

            if (count == 0) continue

            val avgMag = sumMag / count
            val db = if (avgMag > 0f) 20f * log10(avgMag / 128f) else DB_FLOOR
            val normalized = ((db - DB_FLOOR) / (DB_CEIL - DB_FLOOR)).coerceIn(0f, 1f)
            heights[i] = normalized * MAX_BAR_HEIGHT
        }

        if (smoothedBands == null || smoothedBands!!.size != barCount) {
            smoothedBands = heights.copyOf()
        } else {
            val prev = smoothedBands!!
            for (i in 0 until barCount) {
                val speed = if (heights[i] > prev[i]) EMA_ATTACK_SPEED else EMA_DECAY_SPEED
                val alpha = (1f - exp(-speed * dt)).coerceIn(0.01f, 1f)
                prev[i] = prev[i] + alpha * (heights[i] - prev[i])
            }
        }

        _audioDataFlow.tryEmit(smoothedBands!!.copyOf())
    }

    fun setBarCount(count: Int) {
        if (count != lastBarCount) {
            lastBarCount = count
            bandBinRanges = null
            smoothedBands = null
        }
    }

    private fun buildLogBands(barCount: Int, numBins: Int, binHz: Float): Array<IntRange> {
        val minBin = (MIN_FREQ_HZ / binHz).toInt().coerceAtLeast(1)
        val maxBin = (MAX_FREQ_HZ / binHz).toInt().coerceAtMost(numBins - 1)

        val logMin = ln(minBin.toFloat())
        val logMax = ln(maxBin.toFloat())
        val logStep = (logMax - logMin) / barCount

        return Array(barCount) { i ->
            val lo = exp(logMin + i * logStep).toInt().coerceAtLeast(minBin)
            val hi = (exp(logMin + (i + 1) * logStep).toInt() - 1).coerceAtMost(maxBin)
            lo..hi.coerceAtLeast(lo)
        }
    }
}
