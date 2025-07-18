/*
 * Copyright (C) 2025 The AxionAOSP Project
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
import com.android.systemui.media.MediaSessionManager
import com.android.systemui.util.ScrimUtils
import kotlinx.coroutines.*

class PulseViewController private constructor() :
    PulseAudioDataProcessor.DataListener,
    MediaSessionManager.MediaDataListener,
    ScrimUtils.ScrimEventListener {

    private var pulseView: PulseView? = null
    private var settingsRepository: PulseSettingsRepository? = null
    private var audioProcessor: PulseAudioDataProcessor? = null

    private val mainScope = MainScope()

    private fun initialize(context: Context) {
        settingsRepository = PulseSettingsRepository(context).apply {
            startObserving()
            setOnSettingsChangedListener { onSettingsChanged() }
        }

        audioProcessor = PulseAudioDataProcessor(context).apply {
            setDataListener(this@PulseViewController)
        }

        pulseView = PulseView(context).apply {
            initialize(settingsRepository!!)
        }

        ScrimUtils.get().addListener(this)
        MediaSessionManager.get().addListener(this)
        
        pulseView?.setVisibility(false)
    }

    fun getPulseView(): PulseView {
        return pulseView ?: throw IllegalStateException("PulseView not initialized")
    }

    private fun updatePulseState() {
        if (shouldShowPulse()) {
            if (!isRunning()) {
                start()
            }
        } else {
            if (isRunning()) {
                stop()
            }
        }
    }

    private fun start() {
        mainScope.launch {
            pulseView?.setVisibility(true)
            audioProcessor?.startCapture()
        }
    }

    private fun stop() {
        mainScope.launch {
            pulseView?.setVisibility(false)
            audioProcessor?.stopCapture()
        }
    }

    private fun onSettingsChanged() {
        mainScope.launch {
            updatePulseState()
        }
    }

    fun isRunning(): Boolean {
        return audioProcessor?.isCapturing() == true
    }

    fun shouldShowPulse(): Boolean {
        return settingsRepository?.isPulseEnabled() == true &&
            ScrimUtils.get().isKeyguardShowing() &&
            MediaSessionManager.get().isMediaPlaying
    }

    override fun onDataUpdate(data: PulseData) {
        if (settingsRepository?.isPulseEnabled() == true) {
            mainScope.launch {
                pulseView?.updateVisualizerData(data)
            }
        }
    }

    override fun onPlaybackStateChanged(state: Int) {
        mainScope.launch {
            updatePulseState()
        }
    }

    override fun onMediaColorsChanged(color: Int) {
        pulseView?.onMediaColorsChanged(color)
    }

    override fun onKeyguardShowingChanged(showing: Boolean) {
        mainScope.launch {
            updatePulseState()
        }
    }

    override fun onKeyguardFadingAwayChanged(fadingAway: Boolean) {
        stop()
    }

    override fun onKeyguardGoingAwayChanged(goingAway: Boolean) {
        stop()
    }
    
    override fun onScreenTurnedOff() {
        stop()
    }
    
    override fun onStartedWakingUp() {
        mainScope.launch {
            updatePulseState()
        }
    }

    fun destroy() {
        mainScope.cancel()
    }

    companion object {
        private const val TAG = "PulseViewController"
        private lateinit var instance: PulseViewController
        private var isInitialized = false

        @JvmStatic
        fun init(context: Context) {
            if (!isInitialized) {
                instance = PulseViewController()
                instance.initialize(context)
                isInitialized = true
            }
        }

        @JvmStatic
        fun get(): PulseViewController {
            if (!isInitialized) {
                throw IllegalStateException("PulseViewController not initialized. Call init(context) first.")
            }
            return instance
        }
    }
}
