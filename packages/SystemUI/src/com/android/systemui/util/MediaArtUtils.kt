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
package com.android.systemui.util

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.content.res.Configuration
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.media.MediaMetadata
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout

import androidx.core.content.getSystemService

import com.android.internal.graphics.ColorUtils
import com.android.systemui.Dependency
import com.android.systemui.statusbar.phone.ScrimController

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.math.*

class MediaArtUtils private constructor(context: Context) : MediaSessionManagerHelper.MediaMetadataListener {

    private enum class MediaScrimState {
        STATE_SCRIM_SHOWING,
        STATE_SCRIM_HIDDEN
    }

    private val context = context.applicationContext
    private val coroutineScope = CoroutineScope(Dispatchers.Main + Job())

    private val _dozing = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val _keyguard = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val _mediaEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val _qsExpanded = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    
    private var currentMediaScrimState = MediaScrimState.STATE_SCRIM_HIDDEN
    
    private var listening = false
    private var featureEnabled = false

    private var stateObserversJob: Job? = null
    private val settingsObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            updateSettings()
        }
    }

    private val mediaScrim = FrameLayout(context).apply {
        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
    }

    private var mediaArtJob: Job? = null
    private var isAlbumArtVisible = false
    private val mediaFadeLevel = 40
    private var dismissingKeyguard = false

    init {
        MSMHProxy.INSTANCE(context).addMediaMetadataListener(this)
        
        context.contentResolver.registerContentObserver(
            Settings.System.getUriFor(LS_MEDIA_ART_ENABLED),
            false,
            settingsObserver
        )

        updateSettings()
    }

    private fun updateSettings() {
        featureEnabled = Settings.System.getIntForUser(
            context.contentResolver,
            LS_MEDIA_ART_ENABLED,
            0,
            UserHandle.USER_CURRENT
        ) == 1

        if (featureEnabled) {
            registerStateObservers()
        } else {
            unregisterStateObservers()
        }
    }

    private fun registerStateObservers() {
        if (listening) return

        listening = true

        stateObserversJob = coroutineScope.launch {
            merge(
                _dozing,
                _keyguard,
                _mediaEvents,
                _qsExpanded,
            )
            .debounce(100)
            .collect { updateMediaVisibility() }
        }
    }

    private fun unregisterStateObservers() {
        if (!listening) return
        stateObserversJob?.cancel()
        stateObserversJob = null
        listening = false
    }

    private suspend fun shouldShowMediaArt(): Boolean {
        val scrimUtils = ScrimUtils.getInstance(context)
        val isPortrait = context.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
        val isKeyguard = scrimUtils.isKeyguardShowing()
        val isDozing = scrimUtils.isDozing()
        val isPanelFullyCollapsed = scrimUtils.isPanelFullyCollapsed()
        val isMediaPlaying = MSMHProxy.INSTANCE(context).isMediaPlaying()

        val shouldShow = featureEnabled && !isDozing &&
            isPortrait && isKeyguard &&
            isMediaPlaying && isPanelFullyCollapsed

        return shouldShow
    }

    fun updateMediaVisibility() {
        coroutineScope.launch {
            val shouldShow = shouldShowMediaArt()

            val newState = if (shouldShow) MediaScrimState.STATE_SCRIM_SHOWING else MediaScrimState.STATE_SCRIM_HIDDEN
            if (newState == currentMediaScrimState) {
                return@launch
            }

            currentMediaScrimState = newState

            if (shouldShow) {
                showMediaArt()
            } else {
                cleanupResources()
            }
        }
    }

    private fun showMediaArt() {
        if (dismissingKeyguard) return
        updateMediaArt()
        mediaScrim.apply {
            alpha = 0f
            visibility = View.VISIBLE
            animate()
                .alpha(1f)
                .setDuration(300)
                .setListener(null)
                .start()
        }
    }
    
    private fun updateMediaArt() {
        mediaArtJob?.cancel()
        mediaArtJob = coroutineScope.launch {
            processMediaArtwork().let { drawable ->
                updateScrim(drawable)
            }
        }
    }

    private suspend fun processMediaArtwork(): LayerDrawable {
        val metadata = MSMHProxy.INSTANCE(context).getMediaMetadata()
        if (metadata == null) {
            return LayerDrawable(arrayOf())
        }

        val bitmap = withContext(Dispatchers.IO) {
            MSMHProxy.INSTANCE(context).getMediaBitmap()
        }

        if (bitmap == null) return LayerDrawable(arrayOf())

        val processedBitmap = withContext(Dispatchers.Default) {
            getResizedBitmap(bitmap)
        }

        val fadeColor = ColorUtils.blendARGB(
            Color.TRANSPARENT,
            Color.BLACK,
            mediaFadeLevel / 100f
        )

        return LayerDrawable(arrayOf(
            BitmapDrawable(context.resources, processedBitmap),
            ColorDrawable(fadeColor)
        ))
    }

    private fun updateScrim(drawable: LayerDrawable) {
        val metadata = MSMHProxy.INSTANCE(context).getMediaMetadata() ?: return
        recycleDrawable(mediaScrim.background)
        mediaScrim.background = drawable
    }

    fun cleanupResources() {
        dismissingKeyguard = true
        mediaArtJob?.cancel()
        mediaScrim.animate()
            .alpha(0f)
            .setDuration(100)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    recycleDrawable(mediaScrim.background)
                    mediaScrim.background = null
                    mediaScrim.visibility = View.GONE
                    currentMediaScrimState = MediaScrimState.STATE_SCRIM_HIDDEN
                    mediaScrim.postDelayed({
                        dismissingKeyguard = false
                    }, 100)
                }
            })
            .start()
    }

    private fun recycleDrawable(drawable: Drawable?) {
        when (drawable) {
            is BitmapDrawable -> drawable.bitmap?.recycle()
            is LayerDrawable -> (0 until drawable.numberOfLayers).forEach {
                recycleDrawable(drawable.getDrawable(it))
            }
        }
    }

    private fun getResizedBitmap(source: Bitmap): Bitmap {
        val metrics = context.getSystemService<WindowManager>()!!.currentWindowMetrics
        val bounds = metrics.bounds
        val scaleFactor = maxOf(
            bounds.width().toFloat() / source.width,
            bounds.height().toFloat() / source.height
        )
        
        val scaledBitmap = Bitmap.createScaledBitmap(
            source,
            (source.width * scaleFactor).roundToInt(),
            (source.height * scaleFactor).roundToInt(),
            true
        )

        return Bitmap.createBitmap(
            scaledBitmap,
            maxOf((scaledBitmap.width - bounds.width()) / 2, 0),
            maxOf((scaledBitmap.height - bounds.height()) / 2, 0),
            min(bounds.width(), scaledBitmap.width),
            min(bounds.height(), scaledBitmap.height)
        )
    }
    
    override fun onAlbumArtChanged() {
        coroutineScope.launch {
            _mediaEvents.tryEmit(Unit)
            if (currentMediaScrimState == MediaScrimState.STATE_SCRIM_SHOWING) {
                updateMediaArt()
            }
        }
    }

    fun onDozingChanged() {
        _dozing.tryEmit(Unit)
    }

    fun onKeyguardShowingChanged() {
        _keyguard.tryEmit(Unit)
    }

    fun getMediaArtScrim() = mediaScrim

    fun setSubjectAlpha(alpha: Float) {
        mediaScrim.alpha = alpha
    }

    fun setQsExpansion() {
        _qsExpanded.tryEmit(Unit)
    }

    companion object {
        private const val LS_MEDIA_ART_ENABLED = "ls_media_art_enabled"
        
        @Volatile private var instance: MediaArtUtils? = null

        fun getInstance(context: Context): MediaArtUtils =
            instance ?: synchronized(this) {
                instance ?: MediaArtUtils(context).also { instance = it }
            }
    }
}
