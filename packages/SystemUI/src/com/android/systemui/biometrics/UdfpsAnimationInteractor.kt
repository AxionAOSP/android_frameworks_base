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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Resources
import android.app.WallpaperManager
import android.graphics.Bitmap
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
import kotlinx.coroutines.withContext
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

data class UdfpsShaderConfig(
    val activeDurationSec: Float = 0.45f,
    val cycleDurationMs: Long = 800L,
    val maxRadiusDp: Float = 106f,
    val peakAmplitude: Float = 40f,
    val startRipplesDp: Float = 13f,
    val endRipplesDp: Float = 30f,
    val chromaticOffsetDp: Float = 2f,
)

data class UdfpsAnimationUiState(
    val isVisible: Boolean = false,
    val sensorType: Int = FingerprintSensorProperties.TYPE_UDFPS_OPTICAL,
    val animationOffsetY: Int = 0,
    val animationSize: Int = 0,
    val themeSeq: Int = 0,
    val activeOverlayPackage: String = "",
    val animType: String = "lottie",
    val isShaderMode: Boolean = false,
    val preloadedDrawable: Drawable? = null,
    val preloadedWallpaper: Bitmap? = null,
    val preloadedShader: WaterRippleShader? = null,
    val shaderConfig: UdfpsShaderConfig = UdfpsShaderConfig(),
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
    private var cachedShaderPkg: String? = null
    private var cachedShader: WaterRippleShader? = null
    private var cachedShaderConfig: UdfpsShaderConfig = UdfpsShaderConfig()
    private var cachedWallpaperId: Int = -1
    private var cachedWallpaperBitmap: Bitmap? = null

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
        runCatching {
            context.registerReceiver(
                object : BroadcastReceiver() {
                    override fun onReceive(ctx: Context?, intent: Intent?) {
                        if (_uiState.value.isShaderMode) {
                            refreshWallpaperOnlyAsync()
                        }
                    }
                },
                IntentFilter(Intent.ACTION_WALLPAPER_CHANGED),
                Context.RECEIVER_EXPORTED
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

    private fun resolveOverlayString(res: Resources?, pkg: String, name: String, fallbackResId: Int): String? {
        if (res != null && pkg.isNotEmpty()) {
            val id = res.getIdentifier(name, "string", pkg)
            if (id != 0) {
                runCatching { return res.getString(id) }
            }
        }
        return runCatching { context.resources.getString(fallbackResId) }.getOrNull()
    }

    private fun resolveOverlayInt(res: Resources?, pkg: String, name: String, fallbackResId: Int, defaultVal: Int): Int {
        if (res != null && pkg.isNotEmpty()) {
            val id = res.getIdentifier(name, "integer", pkg)
            if (id != 0) {
                runCatching { return res.getInteger(id) }
            }
        }
        return runCatching { context.resources.getInteger(fallbackResId) }.getOrDefault(defaultVal)
    }

    private fun loadWallpaperCached(): Bitmap? {
        return runCatching {
            val wm = WallpaperManager.getInstance(context) ?: return cachedWallpaperBitmap
            val lockId = wm.getWallpaperId(WallpaperManager.FLAG_LOCK)
            val flag = if (lockId > 0) WallpaperManager.FLAG_LOCK else WallpaperManager.FLAG_SYSTEM
            val wpId = if (lockId > 0) lockId else wm.getWallpaperId(WallpaperManager.FLAG_SYSTEM)
            val existing = cachedWallpaperBitmap
            if (wpId == cachedWallpaperId && existing != null && !existing.isRecycled) {
                existing
            } else {
                val raw = wm.getBitmapAsUser(UserHandle.USER_CURRENT, false, flag, true)
                if (raw != null && !raw.isRecycled && raw.width > 0 && raw.height > 0) {
                    val tw = (raw.width * 0.5f).toInt().coerceAtLeast(1)
                    val th = (raw.height * 0.5f).toInt().coerceAtLeast(1)
                    val scaled = Bitmap.createScaledBitmap(raw, tw, th, true)
                    if (scaled !== raw && !raw.isRecycled) {
                        raw.recycle()
                    }
                    wm.forgetLoadedWallpaper()
                    val old = cachedWallpaperBitmap
                    cachedWallpaperBitmap = scaled
                    cachedWallpaperId = wpId
                    if (old != null && old !== scaled && !old.isRecycled) {
                        old.recycle()
                    }
                    scaled
                } else existing
            }
        }.getOrDefault(cachedWallpaperBitmap)
    }

    private fun refreshWallpaperOnlyAsync() {
        preloadJob?.cancel()
        preloadJob = scope.launch(Dispatchers.IO) {
            val wallpaper = loadWallpaperCached()
            val dm = context.resources.displayMetrics
            cachedShader?.setBackgroundBitmap(wallpaper, dm.widthPixels, dm.heightPixels)
            _uiState.update { it.copy(preloadedWallpaper = wallpaper) }
        }
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
            val shaderMode = type == MODE_WATER_RIPPLE || type == MODE_SHADER ||
                pkg.contains("udfps_animation.water") || pkg.contains("udfps_animation.shader")
            val needsDrawable = shaderMode || type == MODE_DRAWABLE

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

            val wallpaper: Bitmap?
            val shader: WaterRippleShader?
            val sConfig: UdfpsShaderConfig
            if (shaderMode) {
                wallpaper = loadWallpaperCached()
                if (!forceReloadDrawable && pkg == cachedShaderPkg && cachedShader != null) {
                    shader = cachedShader
                    sConfig = cachedShaderConfig
                } else {
                    val customAgsl = resolveOverlayString(
                        overlayRes, pkg, "config_udfps_custom_agsl_shader", R.string.config_udfps_custom_agsl_shader
                    )
                    shader = runCatching { WaterRippleShader(customAgsl) }.getOrElse { WaterRippleShader() }
                    sConfig = UdfpsShaderConfig(
                        activeDurationSec = (
                            resolveOverlayInt(overlayRes, pkg, "config_udfps_shader_duration_ms", R.integer.config_udfps_shader_duration_ms, 450) / 1000f
                        ).coerceAtLeast(0.1f),
                        cycleDurationMs = resolveOverlayInt(
                            overlayRes, pkg, "config_udfps_shader_cycle_ms", R.integer.config_udfps_shader_cycle_ms, 800
                        ).toLong().coerceAtLeast(200L),
                        maxRadiusDp = resolveOverlayInt(
                            overlayRes, pkg, "config_udfps_shader_max_radius_dp", R.integer.config_udfps_shader_max_radius_dp, 106
                        ).toFloat(),
                        peakAmplitude = resolveOverlayInt(
                            overlayRes, pkg, "config_udfps_shader_peak_amplitude", R.integer.config_udfps_shader_peak_amplitude, 40
                        ).toFloat(),
                        startRipplesDp = resolveOverlayInt(
                            overlayRes, pkg, "config_udfps_shader_start_ripples_dp", R.integer.config_udfps_shader_start_ripples_dp, 13
                        ).toFloat(),
                        endRipplesDp = resolveOverlayInt(
                            overlayRes, pkg, "config_udfps_shader_end_ripples_dp", R.integer.config_udfps_shader_end_ripples_dp, 30
                        ).toFloat(),
                        chromaticOffsetDp = resolveOverlayInt(
                            overlayRes, pkg, "config_udfps_shader_chromatic_offset_dp", R.integer.config_udfps_shader_chromatic_offset_dp, 2
                        ).toFloat(),
                    )
                    val dm = context.resources.displayMetrics
                    shader.setBackgroundBitmap(wallpaper, dm.widthPixels, dm.heightPixels)
                    cachedShaderPkg = pkg
                    cachedShader = shader
                    cachedShaderConfig = sConfig
                }
            } else {
                wallpaper = null
                shader = null
                sConfig = UdfpsShaderConfig()
                cachedShaderPkg = null
                cachedShader = null
            }

            _uiState.update {
                it.copy(
                    themeSeq = if (forceReloadDrawable) it.themeSeq + 1 else it.themeSeq,
                    activeOverlayPackage = pkg,
                    animType = type,
                    isShaderMode = shaderMode,
                    preloadedDrawable = drawable,
                    preloadedWallpaper = wallpaper,
                    preloadedShader = shader,
                    shaderConfig = sConfig
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
        if (state.isShaderMode) {
            return (360f * context.resources.displayMetrics.density).toInt()
        }
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
        private const val MODE_WATER_RIPPLE = "water_ripple"
        private const val MODE_SHADER = "shader"
        private const val MIN_PLAY_DURATION_MS = 500L
    }
}
