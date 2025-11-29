/*
 * Copyright (C) 2025 Axion OS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.axion.volume.repository

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.media.AppVolume
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.content.pm.PackageManager
import android.content.pm.ApplicationInfo
import lineageos.providers.LineageSettings
import com.android.systemui.axion.volume.dagger.AxionVolumeScope
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*

interface AxionVolumeRepository {
    fun volumeFlow(streamType: Int): Flow<Float>
    fun muteFlow(streamType: Int): Flow<Boolean>
    val ringerModeFlow: Flow<Int>
    val availableStreamsFlow: Flow<List<Int>>
    val isLeftSideFlow: Flow<Boolean>
    val activeAppVolumes: Flow<List<AppVolume>>

    fun setVolume(streamType: Int, level: Float, flags: Int = 0)
    fun setMute(streamType: Int, muted: Boolean)
    fun setAppVolume(packageName: String, volume: Float)
    fun setAppMute(packageName: String, muted: Boolean)
    fun setRingerMode(mode: Int)
    fun getMaxVolume(streamType: Int): Int
    fun getMinVolume(streamType: Int): Int
    fun isStreamMuted(streamType: Int): Boolean
    fun getStreamVolume(streamType: Int): Int
}

@AxionVolumeScope
class AxionVolumeRepositoryImpl @Inject constructor(
    @Application private val context: Context,
    private val audioManager: AudioManager,
    private val broadcastDispatcher: BroadcastDispatcher,
    private val packageManager: PackageManager,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
) : AxionVolumeRepository {

    private val handler = Handler(Looper.getMainLooper())

    companion object {
        private const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"
        private const val STREAM_MUTE_CHANGED_ACTION = "android.media.STREAM_MUTE_CHANGED_ACTION"
        private const val EXTRA_VOLUME_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"

        val TRACKED_STREAMS = listOf(
            AudioManager.STREAM_MUSIC,
            AudioManager.STREAM_RING,
            AudioManager.STREAM_ALARM,
            AudioManager.STREAM_VOICE_CALL,
            AudioManager.STREAM_NOTIFICATION,
        )
    }

    private val appVolumeUpdateEvents = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    private val volumeChanges = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val streamType = intent.getIntExtra(EXTRA_VOLUME_STREAM_TYPE, -1)
                trySend(streamType)
            }
        }
        
        val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                trySend(-1)
            }
        }

        context.contentResolver.registerContentObserver(
            Settings.System.CONTENT_URI,
            true,
            observer
        )

        broadcastDispatcher.registerReceiver(
            receiver,
            IntentFilter(VOLUME_CHANGED_ACTION)
        )

        awaitClose {
            context.contentResolver.unregisterContentObserver(observer)
            broadcastDispatcher.unregisterReceiver(receiver)
        }
    }.shareIn(
        scope = CoroutineScope(backgroundDispatcher),
        started = SharingStarted.WhileSubscribed(),
        replay = 1
    )

    override fun volumeFlow(streamType: Int): Flow<Float> = volumeChanges
        .filter { it == -1 || it == streamType }
        .map { getNormalizedVolume(streamType) }
        .onStart { emit(getNormalizedVolume(streamType)) }
        .distinctUntilChanged()
        .flowOn(backgroundDispatcher)

    override fun muteFlow(streamType: Int): Flow<Boolean> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val changedStreamType = intent.getIntExtra(EXTRA_VOLUME_STREAM_TYPE, -1)
                if (changedStreamType == streamType || changedStreamType == -1) {
                    trySend(audioManager.isStreamMute(streamType))
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(STREAM_MUTE_CHANGED_ACTION)
            addAction(VOLUME_CHANGED_ACTION)
        }

        broadcastDispatcher.registerReceiver(receiver, filter)
        trySend(audioManager.isStreamMute(streamType))

        awaitClose {
            broadcastDispatcher.unregisterReceiver(receiver)
        }
    }
    .distinctUntilChanged()
    .flowOn(backgroundDispatcher)

    override val ringerModeFlow: Flow<Int> = broadcastFlow(
        actions = listOf(
            AudioManager.RINGER_MODE_CHANGED_ACTION,
            AudioManager.INTERNAL_RINGER_MODE_CHANGED_ACTION
        ),
        initialValue = { audioManager.ringerModeInternal },
        extractor = { intent ->
            intent.getIntExtra(AudioManager.EXTRA_RINGER_MODE, audioManager.ringerModeInternal)
        }
    )

    override val availableStreamsFlow: Flow<List<Int>> = callbackFlow {
        trySend(getAvailableStreams())
        awaitClose { }
    }.flowOn(backgroundDispatcher)

    override val isLeftSideFlow: Flow<Boolean> = settingsFlow(
        uri = LineageSettings.Secure.getUriFor(LineageSettings.Secure.VOLUME_PANEL_ON_LEFT),
        getValue = ::isLeftSide
    )
        
    override val activeAppVolumes: Flow<List<AppVolume>> = kotlinx.coroutines.flow.merge(
        callbackFlow {
            val observer = object : ContentObserver(handler) {
                override fun onChange(selfChange: Boolean) {
                    trySend(Unit)
                }
            }
    
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    trySend(Unit)
                }
            }
    
            context.contentResolver.registerContentObserver(
                Settings.System.getUriFor(Settings.System.SHOW_APP_VOLUME),
                false,
                observer
            )
    
            val filter = IntentFilter().apply {
                addAction(VOLUME_CHANGED_ACTION)
                addAction(STREAM_MUTE_CHANGED_ACTION)
            }
            broadcastDispatcher.registerReceiver(receiver, filter)
    
            trySend(Unit)
    
            awaitClose {
                context.contentResolver.unregisterContentObserver(observer)
                broadcastDispatcher.unregisterReceiver(receiver)
            }
        },
        appVolumeUpdateEvents
    )
    .map { getAppVolumes() }
    .flowOn(backgroundDispatcher)

    private fun <T> settingsFlow(uri: android.net.Uri, getValue: () -> T): Flow<T> = callbackFlow {
        val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                trySend(getValue())
            }
        }

        context.contentResolver.registerContentObserver(uri, false, observer)
        trySend(getValue())

        awaitClose {
            context.contentResolver.unregisterContentObserver(observer)
        }
    }
    .distinctUntilChanged()
    .flowOn(backgroundDispatcher)

    private fun <T> broadcastFlow(
        actions: List<String>,
        initialValue: () -> T,
        extractor: (Intent) -> T
    ): Flow<T> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                trySend(extractor(intent))
            }
        }

        val filter = IntentFilter().apply {
            actions.forEach { addAction(it) }
        }

        broadcastDispatcher.registerReceiver(receiver, filter)
        trySend(initialValue())

        awaitClose {
            broadcastDispatcher.unregisterReceiver(receiver)
        }
    }
    .distinctUntilChanged()
    .flowOn(backgroundDispatcher)

    private fun getAppVolumes(): List<AppVolume> {
        val showAppVolume = Settings.System.getInt(
            context.contentResolver,
            Settings.System.SHOW_APP_VOLUME,
            0
        ) == 1
        if (!showAppVolume) return emptyList()
        return audioManager.listAppVolumes()
            .filter { it.isActive }
            .filter {
                try {
                    val appInfo = packageManager.getApplicationInfo(it.packageName, 0)
                    (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) == 0
                } catch (e: Exception) {
                    false
                }
            }
    }

    private fun isLeftSide(): Boolean =
        LineageSettings.Secure.getInt(
            context.contentResolver,
            LineageSettings.Secure.VOLUME_PANEL_ON_LEFT,
            0
        ) != 0

    private fun getAvailableStreams(): List<Int> =
        TRACKED_STREAMS.filter { stream -> getMaxVolume(stream) > 0 }

    private fun getNormalizedVolume(streamType: Int): Float {
        val max = getMaxVolume(streamType)
        val min = getMinVolume(streamType)
        val current = audioManager.getStreamVolume(streamType)
        return if (max > min) {
            (current - min).toFloat() / (max - min)
        } else {
            0f
        }
    }

    override fun setVolume(streamType: Int, level: Float, flags: Int) {
        val max = getMaxVolume(streamType)
        val min = getMinVolume(streamType)
        val volume = (min + (level * (max - min))).toInt().coerceIn(min, max)
        audioManager.setStreamVolume(streamType, volume, flags)
    }

    override fun setMute(streamType: Int, muted: Boolean) {
        audioManager.adjustStreamVolume(
            streamType,
            if (muted) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE,
            0
        )
    }

    override fun setAppVolume(packageName: String, volume: Float) {
        audioManager.setAppVolume(packageName, volume)
        appVolumeUpdateEvents.tryEmit(Unit)
    }

    override fun setAppMute(packageName: String, muted: Boolean) {
        audioManager.setAppMute(packageName, muted)
        appVolumeUpdateEvents.tryEmit(Unit)
    }

    override fun setRingerMode(mode: Int) {
        audioManager.ringerModeInternal = mode
    }

    override fun getMaxVolume(streamType: Int): Int =
        audioManager.getStreamMaxVolume(streamType)

    override fun getMinVolume(streamType: Int): Int =
        audioManager.getStreamMinVolume(streamType)

    override fun isStreamMuted(streamType: Int): Boolean =
        audioManager.isStreamMute(streamType)

    override fun getStreamVolume(streamType: Int): Int =
        audioManager.getStreamVolume(streamType)
}
