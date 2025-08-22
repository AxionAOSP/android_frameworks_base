/*
 * Copyright (C) 2025 AxionOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */
package com.android.systemui.media

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.os.*
import android.provider.Settings
import android.service.notification.NotificationListenerService.RankingMap
import android.service.notification.StatusBarNotification
import com.android.systemui.statusbar.NotificationListener
import com.android.systemui.util.WeakListenerManager
import com.android.systemui.util.wakelock.*
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.util.ScrimUtils
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@SysUISingleton
class MediaManager @Inject constructor(
    private val context: Context,
    private val listener: NotificationListener
) : NotificationListener.NotificationHandler,
    MediaSessionManager.MediaDataListener {

    interface Callback {
        fun onQLPlaybackStateChanged(play: Boolean) {}
        fun onQLMetadataChanged(track: String, artist: String) {}
        fun onNowPlayingUpdate(nowPlayingText: String) {}
    }

    private val handler = Handler(Looper.getMainLooper())
    private var isActive = false

    val mediaSessionManager: MediaSessionManager
        get() = MediaSessionManager.get()

    private val isPixelDevice: Boolean
        get() = Build.MANUFACTURER.equals("Google", ignoreCase = true)

    private val isDozing: Boolean
        get() = ScrimUtils.get().isDozing()

    private val isQuicklookNPEnabled: Boolean
        get() = Settings.Secure.getIntForUser(
            context.contentResolver,
            "nt_quicklook_np",
            1,
            UserHandle.USER_CURRENT
        ) == 1

    private val callbacks = WeakListenerManager<Callback>().apply {
        setLifecycleCallbacks(
            onActive = {
                if (isQuicklookNPEnabled) {
                    startMediaListening()
                }
                isActive = true
            },
            onInactive = {
                stopMediaListening()
                isActive = false
            }
        )
    }

    private var isMediaListening = false
    private val mediaWakeLock = SettableWakeLock(
        WakeLock.createPartial(context, null, "mm"),
        "mm"
    )
    private var lastNowPlayingText: String = ""
    private var lastDetectedNowPlayingText: String = ""
    private val nowPlayingCache = mutableListOf<String>()

    private val nowPlayingTimeoutRunnable = Runnable {
        clearNowPlayingData()
    }

    init {
        if (isPixelDevice) {
            listener.addNotificationHandler(this)
        }
    }

    fun addCallback(callback: Callback) {
        callbacks.addListener(callback)
        if (!isQuicklookNPEnabled) return
        handler.removeCallbacks(nowPlayingTimeoutRunnable)
        callback.onQLPlaybackStateChanged(mediaSessionManager.isMediaPlaying)
        callback.onQLMetadataChanged(mediaSessionManager.trackTitle, mediaSessionManager.artist)
        callback.onNowPlayingUpdate("")
        if (callbacks.size() == 1) {
            if (isPixelDevice) {
                listener.addNotificationHandler(this)
            }
            if (!isMediaListening) {
                startMediaListening()
            }
        }
    }

    fun removeCallback(callback: Callback) {
        callbacks.removeListener(callback)
        if (callbacks.isEmpty()) {
            stopMediaListening()
        }
    }

    private fun startMediaListening() {
        if (isMediaListening) return
        mediaSessionManager.addListener(this)
        isMediaListening = true
    }

    private fun stopMediaListening() {
        if (!isMediaListening) return
        if (isPixelDevice) {
            listener.removeNotificationHandler(this)
        }
        mediaSessionManager.removeListener(this)
        isMediaListening = false
        mediaWakeLock.setAcquired(false)
    }

    private fun maybeRunWithWakeLock(
        delayMs: Long = 2000L,
        block: () -> Unit
    ) {
        if (isDozing) {
            mediaWakeLock.setAcquired(true)
            handler.postDelayed({
                block()
                sendDozePulse()
                mediaWakeLock.setAcquired(false)
            }, delayMs)
        } else {
            block()
        }
    }

    private fun notifyPlaybackState(play: Boolean) {
        callbacks.notify { it.onQLPlaybackStateChanged(play) }
    }

    private fun notifyMetadata(track: String, artist: String) {
        callbacks.notify { it.onQLMetadataChanged(track, artist) }
    }

    private fun notifyNowPlayingUpdate(nowPlayingText: String) {
        if (lastNowPlayingText == nowPlayingText) return
        lastNowPlayingText = nowPlayingText

        callbacks.notify { cb ->
            if (nowPlayingText.isNotEmpty()) {
                maybeRunWithWakeLock {
                    cb.onNowPlayingUpdate(nowPlayingText)
                }
            }
        }

        handler.removeCallbacks(nowPlayingTimeoutRunnable)
        if (nowPlayingText.isNotEmpty()) {
            handler.postDelayed(nowPlayingTimeoutRunnable, TimeUnit.MINUTES.toMillis(2))
        }
    }

    private fun clearNowPlayingData() {
        handler.removeCallbacks(nowPlayingTimeoutRunnable)
        if (lastNowPlayingText.isEmpty()) return
        lastNowPlayingText = ""
        callbacks.notify { it.onNowPlayingUpdate("") }
    }

    private fun clearMediaState() {
        notifyPlaybackState(false)
        notifyMetadata("", "")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap) {
        if (!isPixelDevice || !isQuicklookNPEnabled || !isActive || mediaSessionManager.isMediaPlaying) return
        if (sbn.packageName != "com.google.android.as") return

        val text = sbn.notification?.extras
            ?.getCharSequence(Notification.EXTRA_TITLE)
            ?.toString()
            ?: return

        synchronized(nowPlayingCache) {
            if (text != lastDetectedNowPlayingText) {
                nowPlayingCache.clear()
                nowPlayingCache.add(text)
                lastDetectedNowPlayingText = text
                notifyNowPlayingUpdate(text)
            }
        }
    }

    private fun sendDozePulse() {
        if (!isQuicklookNPEnabled || !isDozing) return
        val intent = Intent("com.android.systemui.doze.pulse")
        context.sendBroadcastAsUser(intent, UserHandle.of(UserHandle.USER_CURRENT))
    }

    override fun onPlaybackStateChanged(state: Int) {
        notifyPlaybackState(mediaSessionManager.isMediaPlaying)
    }

    override fun onMetadataChanged(track: String, artist: String) {
        maybeRunWithWakeLock {
            notifyMetadata(track, artist)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap) {}
    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap, reason: Int) {}
    override fun onNotificationsInitialized() {}
    override fun onNotificationRankingUpdate(rankingMap: RankingMap) {}
}
