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
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import lineageos.providers.LineageSettings
import com.android.systemui.axion.volume.domain.model.AxionAppVolumeModel
import com.android.systemui.axion.volume.dagger.AxionVolumeScope
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.plugins.VolumeDialogController
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
    val activeAppVolumes: Flow<List<AxionAppVolumeModel>>
    val activeAppPackageName: Flow<String?>
    fun setActiveApp(packageName: String?)
    val activeStreamFlow: Flow<Int>
    val inCallFlow: Flow<Boolean>

    fun setVolume(streamType: Int, level: Float, flags: Int = 0)
    fun setMute(streamType: Int, muted: Boolean)
    fun setAppVolume(packageName: String, volume: Float)
    fun setAppMute(packageName: String, muted: Boolean)
    fun setRingerMode(mode: Int)
    fun setActiveStream(streamType: Int)
    fun setExpanded(expanded: Boolean)
    fun userActivity()
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
    private val controller: VolumeDialogController,
) : AxionVolumeRepository {

    private val handler = Handler(Looper.getMainLooper())

    companion object {
        private const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"
        private const val STREAM_MUTE_CHANGED_ACTION = "android.media.STREAM_MUTE_CHANGED_ACTION"

        val TRACKED_STREAMS = listOf(
            AudioManager.STREAM_MUSIC,
            AudioManager.STREAM_RING,
            AudioManager.STREAM_ALARM,
            AudioManager.STREAM_VOICE_CALL,
            AudioManager.STREAM_NOTIFICATION,
        )
    }

    private val appVolumeUpdateEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    private val controllerState: StateFlow<VolumeDialogController.State?> = callbackFlow<VolumeDialogController.State?> {
        val callback = object : VolumeDialogController.Callbacks {
            override fun onShowRequested(reason: Int, keyguardLocked: Boolean, lockTaskModeState: Int) {}
            override fun onDismissRequested(reason: Int) {}
            override fun onScreenOff() {}
            override fun onShowSafetyWarning(flags: Int) {}
            override fun onAccessibilityModeChanged(showA11yStream: Boolean?) {}
            override fun onCaptionEnabledStateChanged(isEnabled: Boolean, checkBeforeSwitch: Boolean) {}
            override fun onCaptionComponentStateChanged(isComponentEnabled: Boolean?, fromTooltip: Boolean) {}
            override fun onShowCsdWarning(csdWarning: Int, durationMs: Int) {}
            override fun onLayoutDirectionChanged(layoutDirection: Int) {}
            override fun onConfigurationChanged() {}
            override fun onShowVibrateHint() {}
            override fun onShowSilentHint() {}
            override fun onVolumeChangedFromKey() {}

            override fun onStateChanged(state: VolumeDialogController.State) {
                trySend(state)
            }
        }
        
        controller.addCallback(callback, handler)
        controller.getState()
        
        awaitClose {
            controller.removeCallback(callback)
        }
    }.stateIn(
        scope = CoroutineScope(backgroundDispatcher),
        started = SharingStarted.Eagerly,
        initialValue = null
    )

    override fun volumeFlow(streamType: Int): Flow<Float> = controllerState
        .filterNotNull()
        .map { state ->
            val ss = state.states.get(streamType)
            if (ss != null) {
                val max = ss.levelMax
                val min = ss.levelMin
                val current = ss.level
                if (max > min) {
                    (current - min).toFloat() / (max - min)
                } else {
                    0f
                }
            } else {
                0f
            }
        }
        .distinctUntilChanged()
        .flowOn(backgroundDispatcher)

    override fun muteFlow(streamType: Int): Flow<Boolean> = controllerState
        .filterNotNull()
        .map { state ->
             state.states.get(streamType)?.muted == true
        }
        .distinctUntilChanged()
        .flowOn(backgroundDispatcher)

    override val ringerModeFlow: Flow<Int> = controllerState
        .filterNotNull()
        .map { it.ringerModeInternal }
        .distinctUntilChanged()
        .flowOn(backgroundDispatcher)

    override val inCallFlow: Flow<Boolean> = callbackFlow {
        val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        
        val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
            override fun onCallStateChanged(state: Int) {
                trySend(state != TelephonyManager.CALL_STATE_IDLE)
            }
        }
        
        telephonyManager.registerTelephonyCallback(context.mainExecutor, callback)
        trySend(telephonyManager.callState != TelephonyManager.CALL_STATE_IDLE)
        
        awaitClose {
            telephonyManager.unregisterTelephonyCallback(callback)
        }
    }
    .distinctUntilChanged()
    .flowOn(backgroundDispatcher)

    override val availableStreamsFlow: Flow<List<Int>> = combine(
        controllerState.filterNotNull(),
        inCallFlow
    ) { state, inCall ->
        getAvailableStreams(state, inCall)
    }
    .distinctUntilChanged()
    .flowOn(backgroundDispatcher)

    override val isLeftSideFlow: Flow<Boolean> = settingsFlow(
        uri = LineageSettings.Secure.getUriFor(LineageSettings.Secure.VOLUME_PANEL_ON_LEFT),
        getValue = ::isLeftSide
    )
    
    override val activeStreamFlow: Flow<Int> = controllerState
        .filterNotNull()
        .map { it.activeStream }
        .distinctUntilChanged()
        .flowOn(backgroundDispatcher)
        
    private val isExpanded = MutableStateFlow(false)

    override fun setExpanded(expanded: Boolean) {
        isExpanded.value = expanded
    }

    private val _activeAppPackageName = MutableStateFlow<String?>(null)
    override val activeAppPackageName: Flow<String?> = _activeAppPackageName.asStateFlow()

    override fun setActiveApp(packageName: String?) {
        _activeAppPackageName.value = packageName
    }

    override val activeAppVolumes: Flow<List<AxionAppVolumeModel>> = isExpanded
        .flatMapLatest { expanded ->
            if (expanded) {
                flow {
                    while (currentCoroutineContext().isActive) {
                        emit(getAppVolumes())
                        delay(1000)
                    }
                }
            } else {
                flowOf(emptyList())
            }
        }
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

    private fun getAppVolumes(): List<AxionAppVolumeModel> {
        val showAppVolume = Settings.System.getInt(
            context.contentResolver,
            Settings.System.SHOW_APP_VOLUME,
            0
        ) == 1
        if (!showAppVolume) return emptyList()
        return audioManager.listAppVolumes()
            .filter { it.isActive }
            .mapNotNull { appVolume ->
                try {
                    val appInfo = packageManager.getApplicationInfo(appVolume.packageName, 0)
                    if ((appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0) return@mapNotNull null
                    
                    val label = packageManager.getApplicationLabel(appInfo).toString()
                    AxionAppVolumeModel(
                        packageName = appVolume.packageName,
                        label = label,
                        volume = appVolume.volume,
                        isMuted = appVolume.isMuted,
                        isActive = appVolume.isActive
                    )
                } catch (e: Exception) {
                    null
                }
            }
    }

    private fun isLeftSide(): Boolean =
        LineageSettings.Secure.getInt(
            context.contentResolver,
            LineageSettings.Secure.VOLUME_PANEL_ON_LEFT,
            0
        ) != 0

    private fun getAvailableStreams(state: VolumeDialogController.State, inCall: Boolean): List<Int> {
        val logicStreams = buildList {
            add(AudioManager.STREAM_MUSIC)
            if (inCall) {
                add(AudioManager.STREAM_VOICE_CALL)
            }
            add(AudioManager.STREAM_RING)
            add(AudioManager.STREAM_NOTIFICATION)
            add(AudioManager.STREAM_ALARM)
        }
        
        val streams = mutableListOf<Int>()
        
        val excludedActiveStreams = setOf(
            AudioManager.STREAM_ACCESSIBILITY,
            AudioManager.STREAM_VOICE_CALL
        )
        
        if (state.activeStream != -1 && state.activeStream !in excludedActiveStreams) {
            streams.add(state.activeStream)
        }
        
        for (stream in logicStreams) {
             if (!streams.contains(stream) && (state.states.get(stream)?.levelMax ?: 0) > 0) {
                 streams.add(stream)
             }
        }
        
        return streams
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

    override fun setActiveStream(streamType: Int) {
        controller.setActiveStream(streamType, false)
    }

    override fun userActivity() {
        controller.userActivity()
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
