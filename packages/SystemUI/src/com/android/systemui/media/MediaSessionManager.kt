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

import android.content.Context
import android.graphics.drawable.Drawable
import android.media.session.PlaybackState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.toArgb
import com.android.app.tracing.coroutines.launchTraced as launch
import com.android.systemui.CoreStartable
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.media.remedia.domain.interactor.MediaInteractor
import com.android.systemui.media.remedia.domain.model.MediaSessionModel
import com.android.systemui.media.remedia.shared.model.MediaSessionState
import com.android.systemui.util.WeakListenerManager
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope

@SysUISingleton
class MediaSessionManager
@Inject
constructor(
    @Application private val context: Context,
    @Application private val applicationScope: CoroutineScope,
    private val mediaInteractor: MediaInteractor,
) : CoreStartable {

    interface MediaDataListener {
        fun onPlaybackStateChanged(state: Int) {}
        fun onAlbumArtChanged(drawable: Drawable?) {}
        fun onAppIconChanged(drawable: Drawable?) {}
        fun onMediaColorsChanged(color: Int?) {}
        fun onMetadataChanged(track: String, artist: String) {}
    }

    private val listenerManager = WeakListenerManager<MediaDataListener>()
    @Volatile private var currentSession = ResolvedMediaSession()

    val isMediaPlaying: Boolean
        get() = currentSession.playbackState == PlaybackState.STATE_PLAYING

    override fun start() {
        applicationScope.launch {
            snapshotFlow { mediaInteractor.currentSessionSnapshot() }.collect(::updateSession)
        }
    }

    fun addListener(listener: MediaDataListener) {
        listenerManager.addListener(listener)
        
        listenerManager.notifyOnBackground {
            if (it === listener) {
                val session = currentSession
                it.onPlaybackStateChanged(session.playbackState)
                it.onMetadataChanged(session.title, session.artist)
                it.onAlbumArtChanged(session.albumArt)
                it.onAppIconChanged(session.appIcon)
                it.onMediaColorsChanged(session.mediaColor)
            }
        }
    }

    fun removeListener(listener: MediaDataListener) = listenerManager.removeListener(listener)

    private fun updateSession(session: MediaSessionSnapshot?) {
        val previous = currentSession
        val updated = session?.resolve() ?: ResolvedMediaSession()
        if (previous == updated) return
        currentSession = updated

        if (previous.playbackState != updated.playbackState) {
            listenerManager.notifyOnBackground {
                it.onPlaybackStateChanged(updated.playbackState)
            }
        }
        if (previous.title != updated.title || previous.artist != updated.artist) {
            listenerManager.notifyOnBackground { it.onMetadataChanged(updated.title, updated.artist) }
        }
        if (previous.albumArt !== updated.albumArt) {
            listenerManager.notifyOnBackground { it.onAlbumArtChanged(updated.albumArt) }
        }
        if (previous.appIcon !== updated.appIcon) {
            listenerManager.notifyOnBackground { it.onAppIconChanged(updated.appIcon) }
        }
        if (previous.mediaColor != updated.mediaColor) {
            listenerManager.notifyOnBackground { it.onMediaColorsChanged(updated.mediaColor) }
        }
    }

    private fun MediaInteractor.currentSessionSnapshot(): MediaSessionSnapshot? {
        val currentSessions = sessions
        val currentSession = currentSessions.getOrNull(currentCarouselIndex)
        val session =
            currentSession?.takeIf { it.isDisplayable() }
                ?: currentSessions.firstOrNull { it.isDisplayable() }
                ?: return null
        return MediaSessionSnapshot(
            playbackState = session.state.toPlaybackState(),
            albumArt = session.background,
            appIcon = session.appIcon,
            mediaColor = session.colorScheme?.primary?.toArgb(),
            title = session.title,
            artist = session.subtitle,
        )
    }

    private fun MediaSessionModel.isDisplayable(): Boolean = isActive && title.isNotBlank()

    private fun MediaSessionState.toPlaybackState(): Int =
        when (this) {
            MediaSessionState.Playing -> PlaybackState.STATE_PLAYING
            MediaSessionState.Paused -> PlaybackState.STATE_PAUSED
            MediaSessionState.Buffering -> PlaybackState.STATE_BUFFERING
        }

    private fun Icon.toDrawable(): Drawable? =
        when (this) {
            is Icon.Loaded -> drawable
            is Icon.Resource -> context.getDrawable(resId)
        }

    private fun MediaSessionSnapshot.resolve() =
        ResolvedMediaSession(
            playbackState = playbackState,
            albumArt = albumArt?.toDrawable(),
            appIcon = appIcon.toDrawable(),
            mediaColor = mediaColor,
            title = title,
            artist = artist,
        )

    private data class MediaSessionSnapshot(
        val playbackState: Int,
        val albumArt: Icon?,
        val appIcon: Icon,
        val mediaColor: Int?,
        val title: String,
        val artist: String,
    )

    private data class ResolvedMediaSession(
        val playbackState: Int = PlaybackState.STATE_NONE,
        val albumArt: Drawable? = null,
        val appIcon: Drawable? = null,
        val mediaColor: Int? = null,
        val title: String = "",
        val artist: String = "",
    )
}
