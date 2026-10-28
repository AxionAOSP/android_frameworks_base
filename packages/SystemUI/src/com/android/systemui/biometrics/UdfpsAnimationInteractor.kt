/*
 * Copyright (C) 2025 AxionOS
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
package com.android.systemui.biometrics

import android.content.Context
import android.graphics.drawable.AnimationDrawable
import android.graphics.drawable.Drawable
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.hardware.fingerprint.FingerprintSensorProperties
import android.os.SystemClock
import android.os.UserHandle
import android.provider.Settings
import com.android.systemui.biometrics.domain.interactor.UdfpsOverlayInteractor
import com.android.systemui.biometrics.shared.model.UdfpsOverlayParams
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryUdfpsInteractor
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.res.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import javax.inject.Inject

data class UdfpsAnimationUiState(
    val isVisible: Boolean = false,
    val sensorType: Int = FingerprintSensorProperties.TYPE_UDFPS_OPTICAL,
    val animationOffsetY: Int = 0,
    val animationSize: Int = 0,
    val themeSeq: Int = 0,
    val activeOverlayPackage: String = "",
    val animType: String = "lottie",
    val preloadedDrawable: Drawable? = null,
)

@SysUISingleton
class UdfpsAnimationInteractor @Inject constructor(
    @Application private val scope: CoroutineScope,
    private val context: Context,
    private val deviceEntryUdfpsInteractor: DeviceEntryUdfpsInteractor,
    private val udfpsOverlayInteractor: UdfpsOverlayInteractor,
) {
    private val _uiState = MutableStateFlow(UdfpsAnimationUiState())
    val uiState: StateFlow<UdfpsAnimationUiState> = _uiState.asStateFlow()
    private var lastOverlayParams = UdfpsOverlayParams()
    private var lastShownTimeMs = 0L
    private var hideJob: Job? = null
    private var preloadJob: Job? = null
    private var cachedDrawablePkg: String? = null
    private var cachedDrawable: Drawable? = null

    init {
        refreshResourcesAsync(forceReloadDrawable = true)
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                onThemeChanged()
            }
        }
        runCatching {
            context.contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.THEME_CUSTOMIZATION_OVERLAY_PACKAGES),
                false,
                observer,
                UserHandle.USER_ALL
            )
        }

        combine(
            udfpsOverlayInteractor.isFingerDown,
            deviceEntryUdfpsInteractor.isListeningForUdfps,
        ) { isFingerDown, isListening ->
            isFingerDown && isListening
        }.onEach { updateVisibility(it) }
            .launchIn(scope)

        udfpsOverlayInteractor.udfpsOverlayParams
            .onEach { updatePosition(it) }
            .launchIn(scope)
    }

    private fun resolveActiveUdfpsOverlayPackage(): String {
        val json = Settings.Secure.getStringForUser(
            context.contentResolver,
            Settings.Secure.THEME_CUSTOMIZATION_OVERLAY_PACKAGES,
            UserHandle.USER_CURRENT
        ) ?: return ""
        val key = "\"android.theme.customization.udfps_animation\""
        val idx = json.indexOf(key)
        if (idx < 0) return ""
        val colon = json.indexOf(':', idx + key.length)
        if (colon < 0) return ""
        val q1 = json.indexOf('"', colon + 1)
        if (q1 < 0) return ""
        val q2 = json.indexOf('"', q1 + 1)
        if (q2 < 0) return ""
        return json.substring(q1 + 1, q2)
    }

    fun onThemeChanged() {
        refreshResourcesAsync(forceReloadDrawable = true)
    }

    private fun refreshResourcesAsync(forceReloadDrawable: Boolean) {
        preloadJob?.cancel()
        preloadJob = scope.launch(Dispatchers.IO) {
            val pkg = resolveActiveUdfpsOverlayPackage()
            val overlayRes = if (pkg.isNotEmpty()) {
                runCatching { context.packageManager.getResourcesForApplication(pkg) }.getOrNull()
            } else null
            val type = if (overlayRes != null && pkg.isNotEmpty()) {
                val id = overlayRes.getIdentifier("config_udfps_animation_type", "string", pkg)
                if (id != 0) runCatching { overlayRes.getString(id) }.getOrDefault(MODE_DRAWABLE) else MODE_DRAWABLE
            } else {
                context.resources.getString(R.string.config_udfps_animation_type)
            }
            val needsDrawable = type == MODE_DRAWABLE

            val drawable: Drawable? = if (needsDrawable) {
                if (!forceReloadDrawable && pkg == cachedDrawablePkg && cachedDrawable != null) {
                    cachedDrawable
                } else {
                    runCatching {
                        val dr = if (overlayRes != null && pkg.isNotEmpty()) {
                            val resId = overlayRes.getIdentifier("udfps_animation", "drawable", pkg)
                            if (resId != 0) overlayRes.getDrawable(resId, null)
                            else context.getDrawable(R.drawable.udfps_animation)
                        } else {
                            context.getDrawable(R.drawable.udfps_animation)
                        }
                        (dr as? AnimationDrawable)?.let { animDrawable ->
                            for (i in 0 until animDrawable.numberOfFrames) {
                                animDrawable.getFrame(i)
                            }
                        }
                        cachedDrawablePkg = pkg
                        cachedDrawable = dr
                        dr
                    }.getOrNull()
                }
            } else {
                cachedDrawablePkg = null
                cachedDrawable = null
                null
            }

            _uiState.update {
                it.copy(
                    themeSeq = if (forceReloadDrawable) it.themeSeq + 1 else it.themeSeq,
                    activeOverlayPackage = pkg,
                    animType = type,
                    preloadedDrawable = drawable
                )
            }
            updatePosition(lastOverlayParams)
        }
    }

    private fun updateVisibility(visible: Boolean) {
        if (visible) {
            hideJob?.cancel()
            hideJob = null
            lastShownTimeMs = SystemClock.uptimeMillis()
            if (!_uiState.value.isVisible) {
                _uiState.update { it.copy(isVisible = true) }
            }
        } else if (_uiState.value.isVisible && hideJob?.isActive != true) {
            val elapsed = SystemClock.uptimeMillis() - lastShownTimeMs
            val remaining = (MIN_PLAY_DURATION_MS - elapsed).coerceAtLeast(0L)
            if (remaining <= 0L) {
                _uiState.update { it.copy(isVisible = false) }
            } else {
                hideJob = scope.launch {
                    delay(remaining)
                    _uiState.update { it.copy(isVisible = false) }
                    hideJob = null
                }
            }
        }
    }

    private fun currentAnimationSize(): Int {
        val state = _uiState.value
        val sizeRes =
            if (state.animType == MODE_DRAWABLE) {
                R.dimen.udfps_animation_drawable_size
            } else {
                R.dimen.udfps_animation_size
            }
        return context.resources.getDimensionPixelSize(sizeRes)
    }

    private fun updatePosition(params: UdfpsOverlayParams) {
        lastOverlayParams = params
        val animationSize = currentAnimationSize()

        if (params.sensorBounds.isEmpty) {
            _uiState.update { it.copy(animationSize = animationSize) }
            return
        }

        val animationOffset = context.resources.getDimensionPixelSize(
            R.dimen.udfps_animation_offset
        ) * params.scaleFactor

        val offsetY = params.sensorBounds.top - (animationSize / 2) + animationOffset.toInt()

        _uiState.update {
            it.copy(
                animationOffsetY = offsetY,
                animationSize = animationSize,
                sensorType = params.sensorType,
            )
        }
    }

    companion object {
        private const val MODE_DRAWABLE = "drawable"
        private const val MIN_PLAY_DURATION_MS = 500L
    }
}
