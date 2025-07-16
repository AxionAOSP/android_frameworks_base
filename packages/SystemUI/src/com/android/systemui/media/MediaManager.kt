package com.android.systemui.media

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import android.service.notification.NotificationListenerService.RankingMap
import android.service.notification.StatusBarNotification
import android.util.Log
import com.android.systemui.statusbar.NotificationListener
import com.android.systemui.util.WeakListenerManager
import com.android.systemui.util.wakelock.*
import java.lang.ref.WeakReference

class MediaManager private constructor() : NotificationListener.NotificationHandler {

    interface Callback {
        fun onQLPlaybackStateChanged(play: Boolean) {}
        fun onQLMetadataChanged(track: String, artist: String) {}
        fun onNowPlayingUpdate(nowPlayingText: String) {}
    }

    companion object {
        private const val DEBUG = false
        private const val TAG = "MediaManager"
        private val QUICKLOOK_URI: Uri =
            Settings.Secure.getUriFor("nt_quicklook_np")

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
                if (DEBUG) Log.d(TAG, "Callbacks activated")
                observeSettings()
                if (isQuicklookNPEnabled()) startMediaListening()
                isActive = true
            },
            onInactive = {
                if (DEBUG) Log.d(TAG, "Callbacks deactivated")
                stopMediaListening()
                unobserveSettings()
                isActive = false
            }
        )
    }

    private var settingsObserver: ContentObserver? = null
    private var isObservingSettings = false
    private var mediaListener: MediaSessionManager.MediaDataListener? = null
    private var isMediaListening = false
    private var mediaWakeLock: SettableWakeLock? = null

    private var lastNowPlayingText: String = ""
    private var lastDetectedNowPlayingText: String = ""
    private val nowPlayingCache = mutableListOf<String>()

    private val nowPlayingTimeoutRunnable = Runnable {
        if (DEBUG) Log.d(TAG, "NowPlaying timeout reached, clearing data")
        clearNowPlayingData()
    }

    fun addHandler(listener: NotificationListener) {
        if (DEBUG) Log.d(TAG, "Handler added")
        listener.addNotificationHandler(this)
    }

    fun addCallback(callback: Callback) {
        if (DEBUG) Log.d(TAG, "Callback added")
        callbacks.addListener(callback)
        if (isQuicklookNPEnabled()) {
            handler.removeCallbacks(nowPlayingTimeoutRunnable)
            callback.onQLPlaybackStateChanged(mediaSessionManager.isMediaPlaying)
            callback.onQLMetadataChanged(mediaSessionManager.trackTitle, mediaSessionManager.artist)
            callback.onNowPlayingUpdate("")
        }
    }

    fun removeCallback(callback: Callback) {
        if (DEBUG) Log.d(TAG, "Callback removed")
        callbacks.removeListener(callback)
        if (callbacks.isEmpty()) {
            stopMediaListening()
        }
    }

    private fun observeSettings() {
        if (settingsObserver != null) return
        val context = appContext ?: return

        if (DEBUG) Log.d(TAG, "observeSettings: Registering settings observer")

        settingsObserver = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                if (DEBUG) Log.d(TAG, "Settings changed: ${uri.toString()}")
                if (uri == QUICKLOOK_URI) {
                    if (isQuicklookNPEnabled()) {
                        if (!isMediaListening) {
                            if (DEBUG) Log.d(TAG, "QuickLook enabled, starting media listening")
                            startMediaListening()
                        }
                        pushCurrentMediaState()
                    } else {
                        if (DEBUG) Log.d(TAG, "QuickLook disabled, stopping media listening")
                        stopMediaListening()
                        clearNowPlayingData()
                        clearMediaState()
                    }
                }
            }
        }.also {
            context.contentResolver.registerContentObserver(
                QUICKLOOK_URI,
                false,
                it
            )
            isObservingSettings = true
        }
    }

    private fun unobserveSettings() {
        val context = appContext ?: return
        if (DEBUG) Log.d(TAG, "unobserveSettings: Unregistering settings observer")
        settingsObserver?.let {
            context.contentResolver.unregisterContentObserver(it)
            settingsObserver = null
            isObservingSettings = false
        }
        mediaWakeLock?.setAcquired(false)
    }

    private fun startMediaListening() {
        if (isMediaListening) return

        val context = appContext ?: return
        if (DEBUG) Log.d(TAG, "startMediaListening: Starting")

        mediaWakeLock = SettableWakeLock(
            WakeLock.createPartial(context, null, "nowplaying"), "nowplaying"
        )

        mediaListener = object : MediaSessionManager.MediaDataListener {
            override fun onPlaybackStateChanged(state: Int) {
                if (DEBUG) Log.d(TAG, "Playback state changed: $state")
                notifyPlaybackState(mediaSessionManager.isMediaPlaying)
            }

            override fun onMetadataChanged(track: String, artist: String) {
                if (DEBUG) Log.d(TAG, "Metadata changed: Track='$track', Artist='$artist'")
                notifyMetadata(track, artist)
            }
        }

        mediaSessionManager.addListener(mediaListener!!)
        isMediaListening = true
    }

    private fun stopMediaListening() {
        if (!isMediaListening) return
        if (DEBUG) Log.d(TAG, "stopMediaListening: Stopping")
        mediaListener?.let {
            mediaSessionManager.removeListener(it)
        }
        mediaListener = null
        isMediaListening = false
        mediaWakeLock?.setAcquired(false)
    }

    private fun pushCurrentMediaState() {
        if (DEBUG) Log.d(TAG, "pushCurrentMediaState")
        notifyPlaybackState(mediaSessionManager.isMediaPlaying)
        notifyMetadata(mediaSessionManager.trackTitle, mediaSessionManager.artist)
    }

    private fun notifyPlaybackState(play: Boolean) {
        if (DEBUG) Log.d(TAG, "notifyPlaybackState: $play")
        callbacks.notify { it.onQLPlaybackStateChanged(play) }
    }

    private fun notifyMetadata(track: String, artist: String) {
        if (DEBUG) Log.d(TAG, "notifyMetadata: '$track' by '$artist'")
        callbacks.notify { it.onQLMetadataChanged(track, artist) }
    }

    private fun notifyNowPlayingUpdate(nowPlayingText: String) {
        if (lastNowPlayingText == nowPlayingText) {
            if (DEBUG) Log.d(TAG, "notifyNowPlayingUpdate: Skipping duplicate '$nowPlayingText'")
            return
        }

        if (DEBUG) Log.d(TAG, "notifyNowPlayingUpdate: '$nowPlayingText'")
        lastNowPlayingText = nowPlayingText

        callbacks.notify { cb ->
            if (nowPlayingText.isNotEmpty()) {
                if (DEBUG) Log.d(TAG, "Acquiring wakelock for now playing")
                mediaWakeLock?.setAcquired(true)
                handler.postDelayed({
                    if (DEBUG) Log.d(TAG, "Releasing wakelock for now playing")
                    mediaWakeLock?.setAcquired(false)
                }, 2000L)
            }
            cb.onNowPlayingUpdate(nowPlayingText)
            sendDozePulse()
        }

        handler.removeCallbacks(nowPlayingTimeoutRunnable)
        if (nowPlayingText.isNotEmpty()) {
            if (DEBUG) Log.d(TAG, "Scheduling now playing clear in 2 minutes")
            handler.postDelayed(nowPlayingTimeoutRunnable, 120_000L)
        }
    }

    private fun clearNowPlayingData() {
        handler.removeCallbacks(nowPlayingTimeoutRunnable)

        if (lastNowPlayingText.isEmpty()) {
            if (DEBUG) Log.d(TAG, "clearNowPlayingData: Already empty")
            return
        }

        if (DEBUG) Log.d(TAG, "clearNowPlayingData: Hiding '$lastNowPlayingText'")
        lastNowPlayingText = ""
        callbacks.notify { it.onNowPlayingUpdate("") }
    }

    private fun clearMediaState() {
        if (DEBUG) Log.d(TAG, "clearMediaState")
        notifyPlaybackState(false)
        notifyMetadata("", "")
    }

    private fun isQuicklookNPEnabled(): Boolean {
        val context = appContext ?: return false
        val enabled = Settings.Secure.getIntForUser(
            context.contentResolver,
            "nt_quicklook_np",
            1,
            UserHandle.USER_CURRENT
        ) == 1
        if (DEBUG) Log.d(TAG, "QuickLook NP Enabled: $enabled")
        return enabled
    }

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap) {
        if (!isQuicklookNPEnabled() || !isActive) return
        if (sbn.packageName != "com.google.android.as") return

        val extras = sbn.notification?.extras ?: return
        val text = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: return

        if (DEBUG) Log.d(TAG, "onNotificationPosted: '$text' from ${sbn.packageName}")

        synchronized(nowPlayingCache) {
            if (text != lastDetectedNowPlayingText) {
                if (DEBUG) Log.d(TAG, "New now playing text detected")
                nowPlayingCache.clear()
                nowPlayingCache.add(text)
                lastDetectedNowPlayingText = text
                notifyNowPlayingUpdate(text)
            } else {
                if (DEBUG) Log.d(TAG, "Duplicate NowPlaying notification ignored: '$text'")
            }
        }
    }

    private fun sendDozePulse() {
        if (!isQuicklookNPEnabled()) return
        val context = appContext ?: return
        if (DEBUG) Log.d(TAG, "Sending doze pulse intent")
        val intent = Intent("com.android.systemui.doze.pulse")
        context.sendBroadcastAsUser(intent, UserHandle.of(UserHandle.USER_CURRENT))
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap) {}
    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap, reason: Int) {}
    override fun onNotificationsInitialized() {}
    override fun onNotificationRankingUpdate(rankingMap: RankingMap) {}
}
