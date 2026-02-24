package com.android.systemui.axdynamicbar.data.source

import com.android.systemui.axdynamicbar.model.IslandEvent
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.screenrecord.data.model.ScreenRecordModel.Starting.Companion.toCountdownSeconds
import com.android.systemui.statusbar.chips.screenrecord.domain.interactor.ScreenRecordChipInteractor
import com.android.systemui.statusbar.chips.screenrecord.domain.model.ScreenRecordChipModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@SysUISingleton
class ScreenRecordIslandManager
@Inject
constructor(
    @Application private val applicationScope: CoroutineScope,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
    private val screenRecordChipInteractor: ScreenRecordChipInteractor,
) {
    private val _screenRecordEvent = MutableStateFlow<IslandEvent.ScreenRecording?>(null)
    val screenRecordEvent: StateFlow<IslandEvent.ScreenRecording?> =
        _screenRecordEvent.asStateFlow()

    @Volatile
    private var notificationStartTimeMs: Long = 0L

    private var listening = false
    private var listenerJob: Job? = null

    fun startListening() {
        if (listening) return
        listening = true
        listenerJob?.cancel()
        listenerJob =
            applicationScope.launch(backgroundDispatcher) {
                screenRecordChipInteractor.screenRecordState.collect { state ->
                    _screenRecordEvent.value =
                        when (state) {
                            is ScreenRecordChipModel.Recording -> {
                                val notifMs = notificationStartTimeMs
                                val existing = _screenRecordEvent.value
                                if (existing != null) {
                                    if (notifMs > 0L && existing.startTimeMs != notifMs) {
                                        existing.copy(startTimeMs = notifMs)
                                    } else existing
                                } else {
                                    IslandEvent.ScreenRecording(
                                        startTimeMs =
                                            if (notifMs > 0L) notifMs
                                            else System.currentTimeMillis(),
                                    )
                                }
                            }
                            is ScreenRecordChipModel.Starting ->
                                IslandEvent.ScreenRecording(
                                    countdownSeconds =
                                        state.millisUntilStarted.toCountdownSeconds(),
                                )
                            is ScreenRecordChipModel.DoingNothing -> null
                        }
                }
            }
    }

    fun stopListening() {
        if (!listening) return
        listening = false
        listenerJob?.cancel()
        listenerJob = null
        _screenRecordEvent.value = null
        notificationStartTimeMs = 0L
    }

    fun stopRecording() {
        applicationScope.launch { screenRecordChipInteractor.stopRecording() }
    }

    fun updateNotificationStartTime(timeMs: Long) {
        notificationStartTimeMs = timeMs
        _screenRecordEvent.update { existing ->
            if (existing != null && timeMs > 0L && existing.startTimeMs != timeMs) {
                existing.copy(startTimeMs = timeMs)
            } else existing
        }
    }
}
