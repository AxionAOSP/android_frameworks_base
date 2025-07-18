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

import android.media.audiofx.Visualizer
import com.android.systemui.dagger.SysUISingleton
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject

@SysUISingleton
class PulseAudioDataProcessor @Inject constructor(
    private val displayRepository: PulseDisplayRepository
) {
    private var visualizer: Visualizer? = null
    private var isProcessing = false
    
    private val pulseData = PulseFFTData()
    private var lastUpdateTime = 0L
    
    private val _audioDataFlow = MutableSharedFlow<PulseData>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val audioDataFlow: SharedFlow<PulseData> = _audioDataFlow.asSharedFlow()
    
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
                        if (fft != null && fft.isNotEmpty()) {
                            processFFTData(fft)
                        }
                    }
                }, Visualizer.getMaxCaptureRate() / 2, false, true)
                
                enabled = true
            }
            
            isProcessing = true
        } catch (e: Exception) {
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
            visualizer = null
            isProcessing = false
            pulseData.reset()
        } catch (e: Exception) {
        }
    }

    fun cleanup() {
        stopCapture()
    }

    private fun processFFTData(fftBytes: ByteArray) {
        val currentTime = System.currentTimeMillis()
        val throttleMs = displayRepository.displayState.value.throttleMs
        if (currentTime - lastUpdateTime < throttleMs) {
            return
        }
        lastUpdateTime = currentTime
        pulseData.updateFFTData(fftBytes)
        _audioDataFlow.tryEmit(pulseData)
    }

    fun isCapturing(): Boolean = isProcessing
}
