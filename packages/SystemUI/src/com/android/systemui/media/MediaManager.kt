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
import java.util.concurrent.TimeUnit

class MediaManager private constructor() : NotificationListener.NotificationHandler {

    interface Callback {
        fun onQLPlaybackStateChanged(play: Boolean) {}
        fun onQLMetadataChanged(track: String, artist: String) {}
        fun onNowPlayingUpdate(nowPlayingText: String) {}
    }

    companion object {
        private const val TAG = "MediaManager"

        @Volatile
        private var INSTANCE: MediaManager? = null
        private lateinit var appContext: Context

        fun init(context: Context) {
            appContext = context.applicationContext
            get()
        }

        fun get(): MediaManager =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: MediaManager().also { INSTANCE = it }
            }
    }

    val mediaSessionManager: MediaSessionManager
        get() = MediaSessionManager.get()

    private val handler = Handler(Looper.getMainLooper())
    private var isActive = false

    private val callbacks = WeakListenerManager<Callback>().apply {
        setLifecycleCallbacks(
            onActive = {
                if (isQuicklookNPEnabled()) {
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

    private var mediaListener: MediaSessionManager.MediaDataListener? = null
    private var isMediaListening = false
    private var mediaWakeLock: SettableWakeLock? = null

    private var lastNowPlayingText: String = ""
    private var lastDetectedNowPlayingText: String = ""
    private val nowPlayingCache = mutableListOf<String>()

    private val nowPlayingTimeoutRunnable = Runnable {
        clearNowPlayingData()
    }

    fun addHandler(listener: NotificationListener) {
        if (!isPixelDevice()) return
        listener.addNotificationHandler(this)
    }

    fun addCallback(callback: Callback) {
        callbacks.addListener(callback)
        if (isQuicklookNPEnabled()) {
            handler.removeCallbacks(nowPlayingTimeoutRunnable)
            callback.onQLPlaybackStateChanged(mediaSessionManager.isMediaPlaying)
            callback.onQLMetadataChanged(mediaSessionManager.trackTitle, mediaSessionManager.artist)
            if (isPixelDevice()) {
                callback.onNowPlayingUpdate("")
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

        mediaWakeLock = SettableWakeLock(
            WakeLock.createPartial(appContext, null, "nowplaying"), "nowplaying"
        )

        mediaListener = object : MediaSessionManager.MediaDataListener {
            override fun onPlaybackStateChanged(state: Int) {
                notifyPlaybackState(mediaSessionManager.isMediaPlaying)
            }

            override fun onMetadataChanged(track: String, artist: String) {
                notifyMetadata(track, artist)
            }
        }

        mediaSessionManager.addListener(mediaListener!!)
        isMediaListening = true
    }

    private fun stopMediaListening() {
        if (!isMediaListening) return
        mediaListener?.let {
            mediaSessionManager.removeListener(it)
        }
        mediaListener = null
        isMediaListening = false
        mediaWakeLock?.setAcquired(false)
    }

    private fun pushCurrentMediaState() {
        notifyPlaybackState(mediaSessionManager.isMediaPlaying)
        notifyMetadata(mediaSessionManager.trackTitle, mediaSessionManager.artist)
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
                mediaWakeLock?.setAcquired(true)
                handler.postDelayed({
                    mediaWakeLock?.setAcquired(false)
                }, 2000L)
            }
            cb.onNowPlayingUpdate(nowPlayingText)
            sendDozePulse()
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

    private fun isQuicklookNPEnabled(): Boolean {
        return Settings.Secure.getIntForUser(
            appContext.contentResolver,
            "nt_quicklook_np",
            1,
            UserHandle.USER_CURRENT
        ) == 1
    }

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap) {
        if (!isPixelDevice() || !isQuicklookNPEnabled() || !isActive || mediaSessionManager.isMediaPlaying) return
        if (sbn.packageName != "com.google.android.as") return

        val extras = sbn.notification?.extras ?: return
        val text = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: return

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
        if (!isQuicklookNPEnabled()) return
        val intent = Intent("com.android.systemui.doze.pulse")
        appContext.sendBroadcastAsUser(intent, UserHandle.of(UserHandle.USER_CURRENT))
    }

    private fun isPixelDevice(): Boolean {
        return Build.MANUFACTURER.equals("Google", ignoreCase = true)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap) {}
    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap, reason: Int) {}
    override fun onNotificationsInitialized() {}
    override fun onNotificationRankingUpdate(rankingMap: RankingMap) {}
}
