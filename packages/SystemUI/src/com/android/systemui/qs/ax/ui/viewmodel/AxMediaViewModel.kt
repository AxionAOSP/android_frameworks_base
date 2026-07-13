/*
 * Copyright 2025-2026 AxionOS
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

package com.android.systemui.qs.ax.ui.viewmodel

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import com.android.app.tracing.coroutines.launchTraced as launch
import com.android.internal.jank.Cuj
import com.android.systemui.animation.Expandable
import com.android.systemui.classifier.Classifier
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.media.controls.domain.pipeline.interactor.MediaCarouselInteractor
import com.android.systemui.media.remedia.domain.interactor.MediaInteractor
import com.android.systemui.media.remedia.domain.model.MediaActionModel
import com.android.systemui.media.remedia.domain.model.MediaOutputDeviceModel
import com.android.systemui.media.remedia.domain.model.MediaSessionModel
import com.android.systemui.media.remedia.ui.viewmodel.MediaFalsingSystem
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.qs.ax.data.repository.AxMediaHistoryRepository
import com.android.systemui.qs.ax.ui.model.AxMediaSurface
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope

class AxMediaViewModel
@Inject
constructor(
    private val interactor: MediaInteractor,
    private val falsingSystem: MediaFalsingSystem,
    private val mediaCarouselInteractor: MediaCarouselInteractor,
    private val mediaHistoryRepository: AxMediaHistoryRepository,
    private val activityStarter: ActivityStarter,
    @Application private val applicationScope: CoroutineScope,
    @Main private val mainDispatcher: CoroutineDispatcher,
) {
    private var scrubbingSessionKey: Any? by mutableStateOf(null)
    private var gutsSessionKey: Any? by mutableStateOf(null)
    private var scrubProgress by mutableFloatStateOf(0f)
    private var dismissedSessions by
        mutableStateOf(emptyMap<AxMediaSurface, Set<AxMediaDismissToken>>())
    private val sessions by derivedStateOf { interactor.sessions }

    private val activeSessions by derivedStateOf { sessions.filter { it.isDisplayable() } }

    val currentSession by derivedStateOf {
        val selected = sessions.getOrNull(interactor.currentCarouselIndex)
        selected?.takeIf { it.isDisplayable() } ?: activeSessions.firstOrNull()
    }

    val showOnLockscreen = mediaCarouselInteractor.allowMediaOnLockscreen
    val lastMediaPackage = mediaHistoryRepository.lastMediaPackage

    init {
        mediaHistoryRepository.startListening()
    }

    fun synchronizeSession(sessionKey: Any?) {
        if (scrubbingSessionKey != null && scrubbingSessionKey != sessionKey) {
            scrubbingSessionKey = null
        }
        if (gutsSessionKey != null && gutsSessionKey != sessionKey) {
            gutsSessionKey = null
        }
    }

    fun visibleSessions(surface: AxMediaSurface): List<MediaSessionModel> {
        return activeSessions.filter { it.isVisibleOn(surface) }
    }

    fun hasVisibleSessions(surface: AxMediaSurface): Boolean = visibleSessions(surface).isNotEmpty()

    fun sessionForKey(sessionKey: Any): MediaSessionModel? {
        return sessions.firstOrNull { it.key == sessionKey }
    }

    fun isSessionVisible(sessionKey: Any, surface: AxMediaSurface): Boolean {
        return sessionForKey(sessionKey)?.isVisibleOn(surface) == true
    }

    fun hasVisibleGuts(): Boolean {
        val sessionKey = gutsSessionKey ?: return false
        return sessions.any { it.key == sessionKey && it.isDisplayable() }
    }

    fun isGutsVisible(session: MediaSessionModel): Boolean = gutsSessionKey == session.key

    fun showGuts(session: MediaSessionModel) {
        gutsSessionKey = session.key
    }

    fun closeGuts() {
        gutsSessionKey = null
    }

    fun cancelGuts() {
        falsingSystem.runIfNotFalseTap(FalsingManager.LOW_PENALTY, ::closeGuts)
    }

    fun isSwipeFalseTouch(): Boolean =
        falsingSystem.isFalseTouch(Classifier.MEDIA_CAROUSEL_SWIPE)

    fun dismissBySwipe(surface: AxMediaSurface) {
        if (!surface.dismissible) return
        dismissedSessions =
            dismissedSessions +
                (surface to activeSessions.mapTo(mutableSetOf()) { it.dismissToken() })
        closeGuts()
    }

    fun dismissFromSurface(session: MediaSessionModel, surface: AxMediaSurface) {
        if (!surface.dismissible) return
        falsingSystem.runIfNotFalseTap(FalsingManager.LOW_PENALTY) {
            val dismissed = dismissedSessions[surface].orEmpty() + session.dismissToken()
            dismissedSessions = dismissedSessions + (surface to dismissed)
            closeGuts()
        }
    }

    fun openSettings() {
        falsingSystem.runIfNotFalseTap(FalsingManager.LOW_PENALTY) {
            interactor.openMediaSettings()
        }
    }

    fun progress(session: MediaSessionModel): Float {
        if (scrubbingSessionKey == session.key) return scrubProgress
        if (session.durationMs <= 0L) return 0f
        return (session.positionMs.toFloat() / session.durationMs).coerceIn(0f, 1f)
    }

    fun onScrubChange(session: MediaSessionModel, progress: Float) {
        scrubbingSessionKey = session.key
        scrubProgress = progress.coerceIn(0f, 1f)
    }

    fun onScrubFinished(session: MediaSessionModel, dragDelta: Offset) {
        if (
            scrubbingSessionKey == session.key &&
                dragDelta.isHorizontal() &&
                !falsingSystem.isFalseTouch(Classifier.MEDIA_SEEKBAR)
        ) {
            interactor.seek(session.key, (scrubProgress * session.durationMs).roundToLong())
        }
        scrubbingSessionKey = null
    }

    fun openSession(session: MediaSessionModel, expandable: Expandable) {
        falsingSystem.runIfNotFalseTap(FalsingManager.LOW_PENALTY) { session.onClick(expandable) }
    }

    fun openLastMediaApp(expandable: Expandable) {
        falsingSystem.runIfNotFalseTap(FalsingManager.LOW_PENALTY) {
            applicationScope.launch(context = mainDispatcher) {
                val target = mediaHistoryRepository.getLaunchTarget() ?: return@launch
                activityStarter.postStartActivityDismissingKeyguard(
                    target.intent,
                    0,
                    expandable.activityTransitionController(
                        Cuj.CUJ_SHADE_APP_LAUNCH_FROM_MEDIA_PLAYER
                    ),
                    null,
                    target.userHandle,
                )
            }
        }
    }

    fun openOutput(device: MediaOutputDeviceModel, expandable: Expandable) {
        falsingSystem.runIfNotFalseTap(FalsingManager.MODERATE_PENALTY) {
            device.onClick(expandable)
        }
    }

    fun runAction(action: MediaActionModel.Action) {
        falsingSystem.runIfNotFalseTap(FalsingManager.MODERATE_PENALTY) { action.onClick?.invoke() }
    }

    private fun Offset.isHorizontal(): Boolean = abs(x) >= abs(y)

    private fun MediaSessionModel.isDisplayable(): Boolean = isActive && title.isNotBlank()

    private fun MediaSessionModel.isVisibleOn(surface: AxMediaSurface): Boolean {
        return isDisplayable() &&
            (!surface.dismissible || dismissToken() !in dismissedSessions[surface].orEmpty())
    }

    private fun MediaSessionModel.dismissToken(): AxMediaDismissToken {
        return AxMediaDismissToken(key, title, subtitle)
    }
}

private data class AxMediaDismissToken(
    val sessionKey: Any,
    val title: String,
    val subtitle: String,
)
