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
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.media.MediaMetadata
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

    private val context = context.applicationContext
    private val scrimController: ScrimController = Dependency.get(ScrimController::class.java)
    private val mediaSessionManager = MediaSessionManagerHelper.getInstance(context)
    private val coroutineScope = CoroutineScope(Dispatchers.Main + Job())

    private val _dozing = MutableStateFlow(false)
    private val _keyguard = MutableStateFlow(false)
    private val _mediaEvents = MutableSharedFlow<Unit>()
    private val _qsExpanded = MutableStateFlow(false)

    private val mediaScrim = FrameLayout(context).apply {
        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
    }

    private var mediaArtJob: Job? = null
    private var isAlbumArtVisible = false
    private val mediaFadeLevel = 40

    init {
        mediaSessionManager.addMediaMetadataListener(this)
        setupStateObservers()
    }

    private fun setupStateObservers() {
        coroutineScope.launch {
            merge(
                _dozing,
                _keyguard,
                _mediaEvents,
                _qsExpanded,
            ).collect { updateMediaVisibility() }
        }
        coroutineScope.launch {
            _keyguard.flatMapLatest { isKeyguard ->
                if (isKeyguard) flow {
                    while (currentCoroutineContext().isActive) {
                        emit(Unit)
                        kotlinx.coroutines.delay(1000)
                    }
                } else flow { }
            }.collect { updateMediaVisibility() }
        }
    }

    private suspend fun shouldShowMediaArt(): Boolean {
        val settingsEnabled = Settings.System.getInt(
            context.contentResolver,
            LS_MEDIA_ART_ENABLED,
            0
        ) == 1

        val isPortrait = context.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
        val scrimStateKeyguard = scrimController.state.toString() == "KEYGUARD"
        val mediaPlaying = mediaSessionManager.isMediaPlaying()

        return settingsEnabled && !_dozing.value 
            && isPortrait && scrimStateKeyguard 
            && mediaPlaying && !_qsExpanded.value
    }

    fun updateMediaVisibility() {
        coroutineScope.launch {
            if (shouldShowMediaArt()) updateMediaArt() else cleanupResources()
        }
    }

    private fun updateMediaArt() {
        mediaArtJob?.cancel()
        mediaArtJob = coroutineScope.launch {
            processMediaArtwork().let { drawable ->
                updateScrim(drawable)
            }
        }
        mediaScrim.visibility = View.VISIBLE
    }

    private suspend fun processMediaArtwork(): LayerDrawable {
        val metadata = mediaSessionManager.getMediaMetadata() ?: return LayerDrawable(arrayOf())
        val bitmap = withContext(Dispatchers.IO) {
            metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: return@withContext null
        } ?: return LayerDrawable(arrayOf())

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
        val metadata = mediaSessionManager.getMediaMetadata() ?: return
        recycleDrawable(mediaScrim.background)
        mediaScrim.background = drawable
    }

    private fun cleanupResources() {
        mediaArtJob?.cancel()
        recycleDrawable(mediaScrim.background)
        mediaScrim.background = null
        mediaScrim.visibility = View.GONE
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

    override fun onMediaMetadataChanged() {
        _mediaEvents.tryEmit(Unit)
    }

    override fun onPlaybackStateChanged() {
        _mediaEvents.tryEmit(Unit)
    }

    fun onDozingChanged(dozing: Boolean) {
        _dozing.value = dozing
    }

    fun setOnKeyguard(active: Boolean) {
        _keyguard.value = active
    }

    fun getMediaArtScrim() = mediaScrim

    fun setSubjectAlpha(alpha: Float) {
        mediaScrim.alpha = alpha
    }
    
    fun setQsExpansion(expanded: Boolean) {
        _qsExpanded.value = expanded
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
