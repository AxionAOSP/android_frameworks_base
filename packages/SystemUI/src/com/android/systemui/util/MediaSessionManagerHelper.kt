/*
 * Copyright (C) 2023-2024 The risingOS Android Project
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
package com.android.systemui.util

import android.app.WallpaperColors
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionLegacyHelper
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.view.KeyEvent
import android.view.View
import com.android.internal.jank.InteractionJankMonitor
import com.android.systemui.Dependency
import com.android.systemui.animation.DialogCuj
import com.android.systemui.animation.DialogTransitionAnimator
import com.android.systemui.animation.Expandable
import com.android.systemui.monet.ColorScheme
import com.android.systemui.media.dialog.MediaOutputDialogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MediaSessionManagerHelper private constructor(private val context: Context) {

    interface MediaMetadataListener {
        fun onMediaMetadataChanged() {}
        fun onPlaybackStateChanged() {}
        fun onMediaColorsChanged() {}
    }

    private val _mediaMetadata = MutableStateFlow<MediaMetadata?>(null)
    val mediaMetadata: StateFlow<MediaMetadata?> = _mediaMetadata

    private val _playbackState = MutableStateFlow<PlaybackState?>(null)
    val playbackState: StateFlow<PlaybackState?> = _playbackState

    private val _mediaColors = MutableStateFlow(0)
    val mediaColors: StateFlow<Int> = _mediaColors

    private val _currentColorScheme = MutableStateFlow<ColorScheme?>(null)
    val currentColorScheme: StateFlow<ColorScheme?> = _currentColorScheme

    private val scope = CoroutineScope(Dispatchers.Main)
    private var collectJob: Job? = null

    private var lastSavedPackageName: String? = null
    private val mediaSessionManager: MediaSessionManager = context.getSystemService(MediaSessionManager::class.java)!!
    private val activityLauncherUtils = ActivityLauncherUtils(context)
    private var activeController: MediaController? = null
    private val listeners = mutableSetOf<MediaMetadataListener>()

    private val mediaControllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            _mediaMetadata.value = metadata
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            _playbackState.value = state
        }
    }

    private val tickerFlow = flow {
        while (true) {
            emit(Unit)
            delay(1000)
        }
    }.flowOn(Dispatchers.Default)

    init {
        lastSavedPackageName = Settings.System.getString(
            context.contentResolver,
            "media_session_last_package_name"
        )

        scope.launch {
            tickerFlow
                .map { fetchActiveController() }
                .distinctUntilChanged { old, new -> sameSessions(old, new) }
                .collect { controller ->
                    activeController?.unregisterCallback(mediaControllerCallback)
                    activeController = controller
                    controller?.registerCallback(mediaControllerCallback)
                    _mediaMetadata.value = controller?.metadata
                    _playbackState.value = controller?.playbackState
                    saveLastNonNullPackageName()
                }
        }

        scope.launch {
            tickerFlow.collect {
                updateMediaColors()
            }
        }
    }

    private suspend fun fetchActiveController(): MediaController? = withContext(Dispatchers.IO) {
        var localController: MediaController? = null
        val remoteSessions = mutableSetOf<String>()
        
        mediaSessionManager.getActiveSessions(null)
            .filter { controller ->
                controller.playbackState?.state == PlaybackState.STATE_PLAYING &&
                controller.playbackInfo != null
            }
            .forEach { controller ->
                when (controller.playbackInfo?.playbackType) {
                    MediaController.PlaybackInfo.PLAYBACK_TYPE_REMOTE -> {
                        remoteSessions.add(controller.packageName)
                        if (localController?.packageName == controller.packageName) {
                            localController = null
                        }
                    }
                    MediaController.PlaybackInfo.PLAYBACK_TYPE_LOCAL -> {
                        if (!remoteSessions.contains(controller.packageName)) {
                            localController = localController ?: controller
                        }
                    }
                }
            }
        localController
    }

    private suspend fun updateMediaColors() = withContext(Dispatchers.Default) {
        val bitmap = mediaMetadata.value?.run {
            getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: 
            getBitmap(MediaMetadata.METADATA_KEY_ART) ?: 
            getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        } ?: return@withContext

        val wallpaperColors = WallpaperColors.fromBitmap(bitmap) ?: return@withContext
        val config = context.resources.configuration
        val isDarkTheme = config.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        
        ColorScheme(wallpaperColors, isDarkTheme).let { scheme ->
            val newColor = if (isDarkTheme) scheme.accent1.s100 else scheme.accent1.s800
            if (_mediaColors.value != newColor) {
                _mediaColors.value = newColor
                _currentColorScheme.value = scheme
            }
        }
    }

    fun addMediaMetadataListener(listener: MediaMetadataListener) {
        listeners.add(listener)
        if (listeners.size == 1) {
            startCollecting()
        }
        listener.onMediaMetadataChanged()
        listener.onPlaybackStateChanged()
        listener.onMediaColorsChanged()
    }

    fun removeMediaMetadataListener(listener: MediaMetadataListener) {
        listeners.remove(listener)
        if (listeners.isEmpty()) {
            stopCollecting()
        }
    }

    private fun startCollecting() {
        collectJob = scope.launch {
            launch { mediaMetadata.collect { notifyListeners { onMediaMetadataChanged() } } }
            launch { playbackState.collect { notifyListeners { onPlaybackStateChanged() } } }
            launch { mediaColors.collect { notifyListeners { onMediaColorsChanged() } } }
        }
    }

    private fun stopCollecting() {
        collectJob?.cancel()
        collectJob = null
        activeController?.unregisterCallback(mediaControllerCallback)
        activeController = null
        _mediaMetadata.value = null
        _playbackState.value = null
        _mediaColors.value = 0
    }

    private fun notifyListeners(action: MediaMetadataListener.() -> Unit) {
        listeners.forEach { it.action() }
    }

    fun seekTo(time: Long) {
        activeController?.transportControls?.seekTo(time)
    }

    fun getTotalDuration() = mediaMetadata.value?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L

    private fun saveLastNonNullPackageName() {
        activeController?.packageName?.takeIf { it.isNotEmpty() }?.let { pkg ->
            if (pkg != lastSavedPackageName) {
                Settings.System.putString(
                    context.contentResolver,
                    "media_session_last_package_name",
                    pkg
                )
                lastSavedPackageName = pkg
            }
        }
    }

    fun getMediaBitmap(): Bitmap? = mediaMetadata.value?.let {
        it.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: 
        it.getBitmap(MediaMetadata.METADATA_KEY_ART) ?: 
        it.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
    }
    
    fun getMediaMetadata(): MediaMetadata? {
        return mediaMetadata.value
    }

    fun getMediaColor(): Int {
        return mediaColors.value
    }

    fun isMediaControllerAvailable() = activeController?.packageName?.isNotEmpty() ?: false

    fun isMediaPlaying() = playbackState.value?.state == PlaybackState.STATE_PLAYING

    fun getMediaControllerPlaybackState(): PlaybackState? {
        return activeController?.playbackState ?: null
    }

    private fun sameSessions(a: MediaController?, b: MediaController?): Boolean {
        if (a == b) return true
        if (a == null) return false
        return a.controlsSameSession(b)
    }

    private fun dispatchMediaKeyWithWakeLockToMediaSession(keycode: Int) {
        val helper = MediaSessionLegacyHelper.getHelper(context) ?: return
        var event = KeyEvent(
            SystemClock.uptimeMillis(),
            SystemClock.uptimeMillis(),
            KeyEvent.ACTION_DOWN,
            keycode,
            0
        )
        helper.sendMediaButtonEvent(event, true)
        event = KeyEvent.changeAction(event, KeyEvent.ACTION_UP)
        helper.sendMediaButtonEvent(event, true)
    }

    fun prevSong() {
        dispatchMediaKeyWithWakeLockToMediaSession(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
    }

    fun nextSong() {
        dispatchMediaKeyWithWakeLockToMediaSession(KeyEvent.KEYCODE_MEDIA_NEXT)
    }

    fun toggleMediaPlaybackState() {
        if (isMediaPlaying()) {
            dispatchMediaKeyWithWakeLockToMediaSession(KeyEvent.KEYCODE_MEDIA_PAUSE)
        } else {
            dispatchMediaKeyWithWakeLockToMediaSession(KeyEvent.KEYCODE_MEDIA_PLAY)
        }
    }
    
    fun showMediaDialog(view: View) {
        val packageName = lastSavedPackageName?.takeIf { it.isNotEmpty() } ?: return
        Dependency.get(MediaOutputDialogManager::class.java)
            .createAndShowWithController(
                packageName,
                true,
                Expandable.fromView(view).dialogController()
            )
    }

    fun launchMediaApp() {
        lastSavedPackageName?.takeIf { it.isNotEmpty() }?.let {
            activityLauncherUtils.launchMediaPlayerApp(it)
        }
    }
    
    private fun Expandable.dialogController(): DialogTransitionAnimator.Controller? {
        return dialogTransitionController(
            cuj =
                DialogCuj(
                    InteractionJankMonitor.CUJ_SHADE_DIALOG_OPEN,
                    MediaOutputDialogManager.INTERACTION_JANK_TAG
                )
        )
    }

    companion object {
        @Volatile
        private var instance: MediaSessionManagerHelper? = null

        fun getInstance(context: Context): MediaSessionManagerHelper =
            instance ?: synchronized(this) {
                instance ?: MediaSessionManagerHelper(context).also { instance = it }
            }
    }
}
