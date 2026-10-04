package com.android.systemui.axdynamicbar.data.source

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon as DrawableIcon
import android.os.Handler
import android.util.Log
import com.android.systemui.axdynamicbar.model.IslandEvent
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.media.MediaSessionManager
import com.android.systemui.media.ResolvedMediaSession
import com.android.systemui.media.dialog.MediaOutputDialogManager
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@SysUISingleton
class MediaIslandManager
@Inject
constructor(
    @Application private val context: Context,
    @Application private val applicationScope: CoroutineScope,
    @Main private val mainHandler: Handler,
    private val mediaOutputDialogManager: MediaOutputDialogManager,
    private val mediaSessionManager: MediaSessionManager,
) {
    companion object {
        private const val TAG = "MediaIslandManager"
        private const val DEFAULT_OUTPUT_DEVICE = "Speaker"
        private const val PAUSE_DISMISS_TIMEOUT_MS = 5000L
    }

    private val _mediaEvent = MutableStateFlow<IslandEvent.Media?>(null)
    val mediaEvent: StateFlow<IslandEvent.Media?> = _mediaEvent.asStateFlow()

    private val isListening = AtomicBoolean(false)
    private val sessionJob = AtomicReference<Job?>(null)
    private val sessionLostListener = AtomicReference<(() -> Unit)?>(null)

    val activeMediaPackage: String?
        get() = mediaSessionManager.activeSession.value?.packageName

    fun setOnMediaSessionLost(callback: () -> Unit) {
        sessionLostListener.set(callback)
    }

    fun startListening() {
        if (!isListening.compareAndSet(false, true)) return
        val job = applicationScope.launch {
            mediaSessionManager.activeSession.collectLatest { session ->
                if (session == null || session.isResumption) {
                    _mediaEvent.value = null
                    sessionLostListener.get()?.invoke()
                    return@collectLatest
                }
                val media = mapSessionToIslandMedia(session)
                _mediaEvent.value = media
                if (!session.isPlaying) {
                    delay(PAUSE_DISMISS_TIMEOUT_MS)
                    _mediaEvent.value = null
                }
            }
        }
        sessionJob.set(job)
    }

    fun stopListening() {
        if (!isListening.compareAndSet(true, false)) return
        sessionJob.getAndSet(null)?.cancel()
        _mediaEvent.value = null
    }

    fun clear() {
        _mediaEvent.value = null
    }

    private fun mapSessionToIslandMedia(session: ResolvedMediaSession?): IslandEvent.Media? {
        if (session == null || session.isResumption) return null
        if (session.track.isEmpty() && session.artist.isEmpty()) return null

        val outputDevice = session.outputDevice?.name?.toString() ?: DEFAULT_OUTPUT_DEVICE

        val customActions = session.customActions.take(2).mapNotNull { ca ->
            val label = ca.name?.toString()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val action = ca.action?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val icon = resolveCustomActionIcon(session.packageName, ca.icon)
            IslandEvent.MediaCustomAction(label = label, action = action, icon = icon)
        }

        return IslandEvent.Media(
            track = session.track,
            artist = session.artist,
            isPlaying = session.isPlaying,
            albumArt = session.albumArt,
            outputDeviceName = outputDevice,
            customActions = customActions,
            appIcon = session.appIcon,
            packageName = session.packageName,
            mediaColor = session.mediaColor ?: 0,
        )
    }

    private fun resolveCustomActionIcon(packageName: String, iconResId: Int) =
        try {
            if (iconResId != 0 && packageName.isNotEmpty()) {
                DrawableIcon.createWithResource(packageName, iconResId).loadDrawable(context)
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }

    fun togglePlayPause() {
        mediaSessionManager.togglePlayPause()
    }

    fun skipNext() {
        mediaSessionManager.skipNext()
    }

    fun skipPrev() {
        mediaSessionManager.skipPrev()
    }

    fun sendCustomAction(action: String) {
        mediaSessionManager.sendCustomAction(action)
    }

    fun getMediaAppIntent(): Intent? =
        mediaSessionManager.getMediaAppIntent()

    fun openMediaApp() {
        mediaSessionManager.openMediaApp()
    }

    fun openMediaOutputSwitcher() {
        val session = mediaSessionManager.activeSession.value ?: return
        val deviceIntent = session.outputDevice?.intent
        if (deviceIntent != null) {
            try {
                deviceIntent.send()
                return
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send output device intent", e)
            }
        }
        val pkg = session.packageName.ifEmpty { return }
        mainHandler.post {
            try {
                mediaOutputDialogManager.createAndShow(packageName = pkg, aboveStatusBar = true)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to open media output switcher", e)
            }
        }
    }
}
