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
package com.android.systemui.media

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import com.android.internal.graphics.ColorUtils
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.util.ScrimUtils
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.android.systemui.utils.coroutines.flow.conflatedCallbackFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

const val MEDIA_ART_STYLE_BLUR = 0
const val MEDIA_ART_STYLE_CONCEPT = 1

data class MediaArtUiState(
    val isEnabled: Boolean = false,
    val isVisible: Boolean = false,
    val isDozing: Boolean = false,
    val artworkDrawable: Drawable? = null,
    val fadeAlpha: Float = 0f,
    val blurLevel: Int = 0,
    val artStyle: Int = MEDIA_ART_STYLE_BLUR
)

@SysUISingleton
class MediaArtInteractor @Inject constructor(
    @Application private val scope: CoroutineScope,
    private val repository: MediaArtSettingsRepository,
) {

    private val _uiState = MutableStateFlow(MediaArtUiState())
    val uiState: StateFlow<MediaArtUiState> = _uiState.asStateFlow()

    private var artworkDrawable: Drawable? = null
    private var featureEnabled = false
    private var animationJob: Job? = null
    private var bouncerShowingOrKeyguardDismissing = false

    private val mediaDataFlow: Flow<MediaDataEvent> = conflatedCallbackFlow {
        val listener = object : MediaSessionManager.MediaDataListener {
            override fun onAlbumArtChanged(drawable: Drawable) {
                trySend(MediaDataEvent.AlbumArtChanged(drawable))
            }
            override fun onPlaybackStateChanged(state: Int) {
                trySend(MediaDataEvent.PlaybackStateChanged(state))
            }
        }
        MediaSessionManager.get().addListener(listener)
        awaitClose { MediaSessionManager.get().removeListener(listener) }
    }

    private val scrimEventFlow: Flow<ScrimEvent> = conflatedCallbackFlow {
        val listener = object : ScrimUtils.ScrimEventListener {
            override fun onKeyguardShowingChanged(showing: Boolean) {
                trySend(ScrimEvent.KeyguardShowing(showing))
            }
            override fun onPrimaryBouncerShowingChanged(showing: Boolean) {
                trySend(ScrimEvent.BouncerShowing(showing))
            }
            override fun onKeyguardGoingAwayChanged(goingAway: Boolean) {
                trySend(ScrimEvent.KeyguardGoingAway(goingAway))
            }
            override fun onKeyguardFadingAwayChanged(fadingAway: Boolean) {
                trySend(ScrimEvent.KeyguardFadingAway(fadingAway))
            }
            override fun onDozingChanged() {
                trySend(ScrimEvent.DozingChanged)
            }
            override fun onExpandedFractionChanged(expandedFraction: Float) {
                trySend(ScrimEvent.ExpandedFractionChanged(expandedFraction))
            }
            override fun onBarStateChanged(state: Int) {
                trySend(ScrimEvent.BarStateChanged(state))
            }
            override fun onQsVisibilityChanged(visible: Boolean) {
                trySend(ScrimEvent.QsVisibilityChanged(visible))
            }
            override fun onScreenTurnedOff() {
                trySend(ScrimEvent.ScreenTurnedOff)
            }
            override fun onStartedWakingUp() {
                trySend(ScrimEvent.StartedWakingUp)
            }
        }
        ScrimUtils.get().addListener(listener)
        awaitClose { ScrimUtils.get().removeListener(listener) }
    }

    init {
        observeSettings()
        observeMediaData()
        observeScrimEvents()
    }

    private fun observeSettings() {
        scope.launch {
            repository.settingsFlow.collect { settings ->
                featureEnabled = settings.isEnabled
                _uiState.update {
                    it.copy(
                        isEnabled = settings.isEnabled,
                        blurLevel = settings.blurLevel,
                        artStyle = settings.artStyle
                    )
                }
                updateVisibility()
            }
        }
    }

    private fun observeMediaData() {
        scope.launch {
            mediaDataFlow.collect { event ->
                when (event) {
                    is MediaDataEvent.AlbumArtChanged -> {
                        artworkDrawable = event.drawable
                        if (_uiState.value.isVisible) {
                            _uiState.update { it.copy(artworkDrawable = processArtwork()) }
                        }
                        updateVisibility()
                    }
                    is MediaDataEvent.PlaybackStateChanged -> {
                        updateVisibility()
                    }
                }
            }
        }
    }

    private fun observeScrimEvents() {
        scope.launch {
            scrimEventFlow.collect { event ->
                when (event) {
                    is ScrimEvent.KeyguardShowing -> {
                        if (!event.showing) {
                            bouncerShowingOrKeyguardDismissing = false
                            hideMediaArt()
                        } else {
                            updateVisibility()
                        }
                    }
                    is ScrimEvent.BouncerShowing -> {
                        bouncerShowingOrKeyguardDismissing = event.showing
                        if (event.showing) hideMediaArt() else updateVisibility()
                    }
                    is ScrimEvent.KeyguardGoingAway -> {
                        bouncerShowingOrKeyguardDismissing = event.goingAway
                        if (event.goingAway) hideMediaArt()
                    }
                    is ScrimEvent.KeyguardFadingAway -> {
                        bouncerShowingOrKeyguardDismissing = event.fadingAway
                        if (event.fadingAway) hideMediaArt()
                    }
                    ScrimEvent.ScreenTurnedOff -> {
                        if (!ScrimUtils.get().isDozing()) hideMediaArt()
                    }
                    ScrimEvent.DozingChanged -> {
                        _uiState.update { it.copy(isDozing = ScrimUtils.get().isDozing()) }
                        updateVisibility()
                    }
                    is ScrimEvent.ExpandedFractionChanged,
                    is ScrimEvent.BarStateChanged,
                    is ScrimEvent.QsVisibilityChanged -> {
                        updateVisibility()
                    }
                    ScrimEvent.StartedWakingUp -> {
                        _uiState.update { it.copy(isDozing = false) }
                        updateVisibility()
                    }
                }
            }
        }
    }

    private fun shouldShowMediaArt(): Boolean {
        if (!ScrimUtils.get().isKeyguardShowing()) return false
        return featureEnabled &&
               MediaSessionManager.get().isMediaPlaying &&
               ScrimUtils.get().isPanelFullyCollapsed() &&
               !bouncerShowingOrKeyguardDismissing
    }

    private fun updateVisibility() {
        val shouldShow = shouldShowMediaArt()
        val currentState = _uiState.value

        when {
            shouldShow && !currentState.isVisible -> showMediaArt()
            !shouldShow && currentState.isVisible -> hideMediaArt()
        }
    }

    private fun showMediaArt() {
        animationJob?.cancel()
        _uiState.update {
            it.copy(
                isVisible = true,
                artworkDrawable = processArtwork(),
                fadeAlpha = 0f
            )
        }
        animationJob = scope.launch {
            animateFade(targetAlpha = 1f, duration = 300)
        }
    }

    private fun hideMediaArt() {
        if (!_uiState.value.isVisible) return
        animationJob?.cancel()
        animationJob = scope.launch {
            animateFade(targetAlpha = 0f, duration = 100)
            _uiState.update {
                it.copy(
                    isVisible = false,
                    artworkDrawable = null,
                    fadeAlpha = 0f
                )
            }
        }
    }

    private suspend fun animateFade(targetAlpha: Float, duration: Long) {
        val startAlpha = _uiState.value.fadeAlpha
        val startTime = System.currentTimeMillis()

        while (true) {
            val elapsed = System.currentTimeMillis() - startTime
            val progress = (elapsed.toFloat() / duration).coerceIn(0f, 1f)
            val currentAlpha = startAlpha + (targetAlpha - startAlpha) * progress

            _uiState.update { it.copy(fadeAlpha = currentAlpha) }

            if (progress >= 1f) break
            delay(16)
        }
    }

    private fun processArtwork(): Drawable? {
        val drawable = artworkDrawable ?: return null

        val fadeColor = ColorUtils.blendARGB(Color.TRANSPARENT, Color.BLACK, 0.4f)
        val fadeOverlay = ColorDrawable(fadeColor).apply {
            setBounds(0, 0, drawable.intrinsicWidth, drawable.intrinsicHeight)
        }

        return LayerDrawable(arrayOf(drawable, fadeOverlay)).apply {
            setBounds(0, 0, drawable.intrinsicWidth, drawable.intrinsicHeight)
        }
    }

    private sealed class MediaDataEvent {
        data class AlbumArtChanged(val drawable: Drawable) : MediaDataEvent()
        data class PlaybackStateChanged(val state: Int) : MediaDataEvent()
    }

    private sealed class ScrimEvent {
        data class KeyguardShowing(val showing: Boolean) : ScrimEvent()
        data class BouncerShowing(val showing: Boolean) : ScrimEvent()
        data class KeyguardGoingAway(val goingAway: Boolean) : ScrimEvent()
        data class KeyguardFadingAway(val fadingAway: Boolean) : ScrimEvent()
        data object DozingChanged : ScrimEvent()
        data class ExpandedFractionChanged(val fraction: Float) : ScrimEvent()
        data class BarStateChanged(val state: Int) : ScrimEvent()
        data class QsVisibilityChanged(val visible: Boolean) : ScrimEvent()
        data object ScreenTurnedOff : ScrimEvent()
        data object StartedWakingUp : ScrimEvent()
    }
}
