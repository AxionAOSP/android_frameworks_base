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
package com.android.systemui.media

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.content.res.Configuration
import android.database.ContentObserver
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import com.android.internal.graphics.ColorUtils
import com.android.systemui.SystemUIApplication
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.util.ScrimUtils
import kotlinx.coroutines.*
import javax.inject.Inject

@SysUISingleton
class MediaViewController @Inject constructor(
    private val context: Context
) : MediaSessionManager.MediaDataListener, ScrimUtils.ScrimEventListener {

    private val coroutineScope = CoroutineScope(Dispatchers.Main + Job())
    
    private var isScrimVisible = false
    private var listening = false
    private var featureEnabled = false
    private var artworkDrawable: Drawable? = null
    private var bouncerShowingOrKeyguardDismissing = false
    private var dismissingKeyguard = false
    
    private val isMediaPlaying 
        get() = MediaSessionManager.get().isMediaPlaying
    private val isKeyguardShowing 
        get() = ScrimUtils.get().isKeyguardShowing()

    private val settingsObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            updateSettings()
        }
    }

    private val mediaScrim = ImageView(context).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        scaleType = ImageView.ScaleType.CENTER_CROP
    }

    private var mediaArtJob: Job? = null
    private var stateUpdateJob: Job? = null

    init {        
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

        if (featureEnabled && !listening) {
            ScrimUtils.get().addListener(this)
            listening = true
        } else if (!featureEnabled && listening) {
            MediaSessionManager.get().removeListener(this)
            ScrimUtils.get().removeListener(this)
            listening = false
        }
    }

    private fun shouldShowMediaArt(): Boolean {
        if (!isKeyguardShowing) return false
        
        val isPortrait = context.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
        
        return featureEnabled && 
               !ScrimUtils.get().isDozing() &&
               isPortrait && 
               isMediaPlaying && 
               ScrimUtils.get().isPanelFullyCollapsed() &&
               !bouncerShowingOrKeyguardDismissing
    }

    private fun showMediaArt() {
        if (dismissingKeyguard) return

        updateMediaArt()

        mediaScrim.apply {
            alpha = 0f
            visibility = View.VISIBLE
            isScrimVisible = true
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
            mediaScrim.setImageDrawable(processArtwork())
        }
    }

    private fun processArtwork(): LayerDrawable {
        val drawable = artworkDrawable ?: return LayerDrawable(arrayOf())

        val fadeColor = ColorUtils.blendARGB(Color.TRANSPARENT, Color.BLACK, 0.4f)
        val fadeOverlay = ColorDrawable(fadeColor).apply {
            setBounds(0, 0, drawable.intrinsicWidth, drawable.intrinsicHeight)
        }

        return LayerDrawable(arrayOf(drawable, fadeOverlay)).apply {
            setBounds(0, 0, drawable.intrinsicWidth, drawable.intrinsicHeight)
        }
    }

    fun cleanupResources() {
        if (dismissingKeyguard) return
        dismissingKeyguard = true
        mediaArtJob?.cancel()
        stateUpdateJob?.cancel()
        
        mediaScrim.animate()
            .alpha(0f)
            .setDuration(100)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    mediaScrim.setImageDrawable(null)
                    mediaScrim.visibility = View.GONE
                    isScrimVisible = false
                    dismissingKeyguard = false
                }
            })
            .start()
    }

    private fun scheduleStateUpdate() {
        if (!isKeyguardShowing) return
        
        stateUpdateJob?.cancel()
        stateUpdateJob = coroutineScope.launch {
            onMediaStateChanged()
        }
    }

    override fun onAlbumArtChanged(drawable: Drawable) {
        if (!isKeyguardShowing) return
        
        artworkDrawable = drawable
        scheduleStateUpdate()
        if (isScrimVisible) {
            updateMediaArt()
        }
    }
    
    override fun onPlaybackStateChanged(state: Int) {
        scheduleStateUpdate()
    }

    override fun onPrimaryBouncerShowingChanged(showing: Boolean) {
        bouncerShowingOrKeyguardDismissing = showing
        if (showing) cleanupResources()
    }
    
    override fun onKeyguardGoingAwayChanged(goingAway: Boolean) {
        bouncerShowingOrKeyguardDismissing = goingAway
        if (goingAway) cleanupResources()
    }
    
    override fun onKeyguardFadingAwayChanged(fadingAway: Boolean) {
        bouncerShowingOrKeyguardDismissing = fadingAway
        if (fadingAway) cleanupResources()
    }

    override fun onDozingChanged() {
        scheduleStateUpdate()
    }
    
    override fun onExpandedFractionChanged(expandedFraction: Float) {
        scheduleStateUpdate()
    }
    
    override fun onBarStateChanged(state: Int) {
        scheduleStateUpdate()
    }
    
    override fun onQsVisibilityChanged(visible: Boolean) {
        scheduleStateUpdate()
    }

    override fun onKeyguardShowingChanged(showing: Boolean) {
        if (showing) {
            dismissingKeyguard = false
            MediaSessionManager.get().addListener(this)
            scheduleStateUpdate()
        } else {
            cleanupResources()
            MediaSessionManager.get().removeListener(this)
        }
    }

    override fun onScreenTurnedOff() {
        cleanupResources()
    }
    
    override fun onStartedWakingUp() {
        scheduleStateUpdate()
    }

    private suspend fun onMediaStateChanged() {
        val shouldShow = shouldShowMediaArt()
        when {
            shouldShow && !isScrimVisible -> showMediaArt()
            !shouldShow && isScrimVisible -> cleanupResources()
        }
    }

    fun getMediaArtScrim() = mediaScrim

    companion object {
        private const val LS_MEDIA_ART_ENABLED = "ls_media_art_enabled"
        
        @JvmStatic
        fun get(context: Context): MediaViewController {
            val app = context.applicationContext as SystemUIApplication
            return app.sysUIComponent.mediaViewController()
        }
    }
}
