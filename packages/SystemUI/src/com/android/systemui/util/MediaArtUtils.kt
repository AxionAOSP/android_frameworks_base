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

import com.android.internal.graphics.ColorUtils

import com.android.systemui.Dependency
import com.android.systemui.statusbar.phone.ScrimController

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min
import kotlin.math.roundToInt

class MediaArtUtils private constructor(context: Context) : MediaSessionManagerHelper.MediaMetadataListener {

    private val mContext: Context = context.applicationContext
    private val mScrimController: ScrimController = Dependency.get(ScrimController::class.java)
    private val mMediaSessionManagerHelper: MediaSessionManagerHelper = MediaSessionManagerHelper.getInstance(mContext)
    private val coroutineScope = CoroutineScope(Dispatchers.Main + Job())
    private var mediaArtJob: Job? = null

    private val mLsMediaScrim: FrameLayout = object : FrameLayout(mContext) {
        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            this@MediaArtUtils.onDetachedFromWindow()
        }
    }

    private var mDozing = false
    private var mAlbumArtShowing = false
    private val mLsMediaFadeLevel = 40
    private var mPreviousMediaMetadata: MediaMetadata? = null

    init {
        mMediaSessionManagerHelper.addMediaMetadataListener(this)
        mLsMediaScrim.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
    }

    fun onDozingChanged(dozing: Boolean) {
        if (mDozing != dozing) {
            mDozing = dozing
            updateMediaArtVisibility()
        }
    }

    fun getMediaArtScrim(): FrameLayout = mLsMediaScrim

    private fun canShowLsMediaArt(): Boolean {
        val lsMediaEnabled = Settings.System.getInt(
            mContext.contentResolver,
            LS_MEDIA_ART_ENABLED,
            0
        ) == 1
        return lsMediaEnabled && !mDozing &&
                mContext.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE &&
                mScrimController.state.toString() == "KEYGUARD" &&
                mMediaSessionManagerHelper.isMediaPlaying()
    }

    fun updateMediaArtVisibility() {
        if (canShowLsMediaArt()) {
            showMediaArt()
        } else {
            hideMediaArt()
        }
    }

    private fun showMediaArt() {
        if (mAlbumArtShowing) return
        val metadata = mMediaSessionManagerHelper.getMediaMetadata() ?: return
        if (metadata == mPreviousMediaMetadata) return
        generateMediaArt(metadata)
        mLsMediaScrim.alpha = 0f
        mLsMediaScrim.visibility = View.VISIBLE
        mLsMediaScrim.animate().alpha(1f).duration = 300
        mAlbumArtShowing = true
    }

    fun hideMediaArt() {
        if (!mAlbumArtShowing) return
        cancelPendingTask()
        mLsMediaScrim.animate().cancel()
        mLsMediaScrim.animate()
            .alpha(0f)
            .setDuration(300)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    cleanupMediaArt()
                }

                override fun onAnimationCancel(animation: Animator) {
                    cleanupMediaArt()
                }
            }).start()
    }

    private fun cleanupMediaArt() {
        cancelPendingTask()
        mLsMediaScrim.visibility = View.GONE
        recycleBackgroundBitmaps(mLsMediaScrim.background)
        mLsMediaScrim.background = null
        mPreviousMediaMetadata = null
        mAlbumArtShowing = false
        mLsMediaScrim.animate().setListener(null)
    }

    private fun generateMediaArt(metadata: MediaMetadata) {
        val bitmap = getMediaBitmap(metadata) ?: return
        cancelPendingTask()
        mediaArtJob = coroutineScope.launch {
            val fadeColor = ColorUtils.blendARGB(
                Color.TRANSPARENT,
                Color.BLACK,
                mLsMediaFadeLevel / 100f
            )
            val layers = withContext(Dispatchers.Default) {
                arrayOf<Drawable>(
                    BitmapDrawable(mContext.resources, getResizedBitmap(bitmap)),
                    ColorDrawable(fadeColor)
                )
            }
            val layerDrawable = LayerDrawable(layers)
            updateScrimBackground(layerDrawable, metadata)
        }
    }

    private fun updateScrimBackground(newBackground: LayerDrawable, metadata: MediaMetadata) {
        if (!mLsMediaScrim.isAttachedToWindow) return
        recycleBackgroundBitmaps(mLsMediaScrim.background)
        mLsMediaScrim.background = newBackground
        mPreviousMediaMetadata = metadata
    }

    private fun recycleBackgroundBitmaps(background: Drawable?) {
        when (background) {
            is BitmapDrawable -> {
                val bitmap = background.bitmap
                if (bitmap != null && !bitmap.isRecycled) {
                    bitmap.recycle()
                }
            }
            is LayerDrawable -> {
                for (i in 0 until background.numberOfLayers) {
                    recycleBackgroundBitmaps(background.getDrawable(i))
                }
            }
        }
    }

    private fun getResizedBitmap(wallpaperBitmap: Bitmap): Bitmap {
        val windowManager = mContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val displayBounds = windowManager.currentWindowMetrics.bounds
        val ratioW = displayBounds.width().toFloat() / wallpaperBitmap.width
        val ratioH = displayBounds.height().toFloat() / wallpaperBitmap.height
        val scaleFactor = maxOf(ratioH, ratioW)
        val desiredWidth = (wallpaperBitmap.width * scaleFactor).roundToInt().coerceAtLeast(0)
        val desiredHeight = (wallpaperBitmap.height * scaleFactor).roundToInt().coerceAtLeast(0)
        val scaledWallpaperBitmap = Bitmap.createScaledBitmap(wallpaperBitmap, desiredWidth, desiredHeight, true)
        val xPixelShift = maxOf((desiredWidth - displayBounds.width()) / 2, 0)
        val yPixelShift = maxOf((desiredHeight - displayBounds.height()) / 2, 0)
        val cropWidth = min(displayBounds.width(), scaledWallpaperBitmap.width - xPixelShift)
        val cropHeight = min(displayBounds.height(), scaledWallpaperBitmap.height - yPixelShift)
        return Bitmap.createBitmap(
            scaledWallpaperBitmap,
            xPixelShift,
            yPixelShift,
            cropWidth,
            cropHeight
        )
    }
    
    private fun getMediaBitmap(metadata: MediaMetadata): Bitmap? {
        return metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
    }

    fun onDetachedFromWindow() {
        mPreviousMediaMetadata = null
        mMediaSessionManagerHelper.removeMediaMetadataListener(this)
        cancelPendingTask()
        coroutineScope.coroutineContext[Job]?.cancel()
    }

    private fun cancelPendingTask() {
        mediaArtJob?.cancel()
        mediaArtJob = null
    }

    fun setSubjectAlpha(subjectAlpha: Float) {
        mLsMediaScrim.alpha = subjectAlpha
    }

    override fun onMediaMetadataChanged() {
        cleanupMediaArt()
        updateMediaArtVisibility()
    }

    override fun onPlaybackStateChanged() {
        cleanupMediaArt()
        updateMediaArtVisibility()
    }

    companion object {
        private const val LS_MEDIA_ART_ENABLED = "ls_media_art_enabled"
        @Volatile
        private var instance: MediaArtUtils? = null

        fun getInstance(context: Context): MediaArtUtils {
            return instance ?: synchronized(MediaArtUtils::class.java) {
                instance ?: MediaArtUtils(context).also { instance = it }
            }
        }
    }
}
