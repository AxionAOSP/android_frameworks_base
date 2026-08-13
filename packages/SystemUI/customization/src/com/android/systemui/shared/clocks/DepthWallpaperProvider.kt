/*
 * Copyright (C) 2026 AxionOS Project
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

package com.android.systemui.shared.clocks

import android.app.WallpaperManager
import android.content.ContentResolver
import android.content.Context
import android.database.ContentObserver
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Base64
import android.util.Log
import com.android.axion.util.DisplayUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger

object DepthWallpaperProvider {

    private const val TAG = "DepthWallpaperProvider"
    private const val SETTING_DEPTH_MASK = "ax_depth_subject_mask"
    private const val SETTING_DEPTH_ENABLED = "ax_depth_clock_enabled"
    private const val SETTING_DISABLE_ZOOM = "pref_disable_wallpaper_zoom"
    private const val EFFECTS_PACKAGE = "com.android.axion.wallpapereffects"
    private const val MAGIC_PORTRAIT_SERVICE = "MagicPortraitService"
    private const val PATH_VERSION = 0x01

    @Volatile
    var isEnabled: Boolean = false
        private set

    private var wallpaperZoomActive = false
    private var wallpaperZoomDisabled = false

    private val listeners = mutableMapOf<DepthMaskListener, Context>()
    private val handler = Handler(Looper.getMainLooper())
    private val refreshGeneration = AtomicInteger()
    private var registered = false
    private var contentResolver: ContentResolver? = null
    private var wallpaperManager: WallpaperManager? = null

    interface DepthMaskListener {
        fun onDepthDataChanged(path: Path?, pathAspect: Float)

        fun onWallpaperZoomActiveChanged(active: Boolean)

        fun onWallpaperZoomDisabledChanged(disabled: Boolean)
    }

    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            refreshAsync()
            refreshZoomDisabled()
        }
    }

    fun init(context: Context) {
        if (registered) return
        registered = true
        val stableContext = context.applicationContext ?: context
        contentResolver = stableContext.contentResolver
        wallpaperManager = WallpaperManager.getInstance(stableContext)

        DisplayUtils.DisplayLayout.values().forEach {
            stableContext.contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(it.getSettingName(SETTING_DEPTH_MASK)),
                false,
                observer,
            )
        }
        stableContext.contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(SETTING_DEPTH_ENABLED),
            false, observer
        )
        stableContext.contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(SETTING_DISABLE_ZOOM),
            false, observer
        )

        refreshAsync()
        refreshZoomDisabled()
    }

    fun addListener(context: Context, listener: DepthMaskListener) {
        listeners[listener] = DisplayUtils.createStableDisplayContext(context)
        listener.onDepthDataChanged(null, 1f)
        listener.onWallpaperZoomDisabledChanged(wallpaperZoomDisabled)
        listener.onWallpaperZoomActiveChanged(wallpaperZoomActive)
        refreshAsync()
    }

    fun removeListener(listener: DepthMaskListener) {
        listeners.remove(listener)
    }

    fun updateListenerContext(context: Context, listener: DepthMaskListener) {
        if (!listeners.containsKey(listener)) return
        listeners[listener] = DisplayUtils.createStableDisplayContext(context)
        refreshAsync()
    }

    fun setWallpaperZoomActive(active: Boolean) {
        if (wallpaperZoomActive == active) return

        wallpaperZoomActive = active
        for (listener in listeners.keys.toList()) {
            listener.onWallpaperZoomActiveChanged(active)
        }
    }

    private fun refreshAsync() {
        val cr = contentResolver ?: return
        val generation = refreshGeneration.incrementAndGet()
        val registrations = listeners.toMap()
        Thread {
            try {
                val liveInfo = wallpaperManager?.wallpaperInfo
                val enabled =
                    Settings.Secure.getInt(cr, SETTING_DEPTH_ENABLED, 0) == 1
                        && (
                            liveInfo == null ||
                                hasLockWallpaper() ||
                                liveInfo.component.packageName == EFFECTS_PACKAGE &&
                                    liveInfo.component.className.endsWith(MAGIC_PORTRAIT_SERVICE)
                            )
                val paths =
                    if (enabled) {
                        registrations.values
                            .map {
                                DisplayUtils.getCurrentDisplayLayout(it)
                                    .getSettingName(SETTING_DEPTH_MASK)
                            }
                            .distinct()
                            .associateWith { setting ->
                                Settings.Secure.getString(cr, setting)?.let(::decodePath)
                            }
                    } else {
                        emptyMap()
                    }

                handler.post {
                    if (refreshGeneration.get() != generation) return@post
                    isEnabled = enabled
                    registrations.forEach { (listener, context) ->
                        if (listeners[listener] !== context) return@forEach
                        val result =
                            paths[
                                DisplayUtils.getCurrentDisplayLayout(context)
                                    .getSettingName(SETTING_DEPTH_MASK)
                            ]
                        listener.onDepthDataChanged(result?.first, result?.second ?: 1f)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to refresh depth data", e)
                handler.post {
                    if (refreshGeneration.get() != generation) return@post
                    registrations.keys.forEach { listener ->
                        if (listeners.containsKey(listener)) {
                            listener.onDepthDataChanged(null, 1f)
                        }
                    }
                }
            }
        }.start()
    }

    private fun refreshZoomDisabled() {
        val cr = contentResolver ?: return
        Thread {
            try {
                val disabled = Settings.Secure.getInt(cr, SETTING_DISABLE_ZOOM, 0) == 1
                if (wallpaperZoomDisabled == disabled) return@Thread
                wallpaperZoomDisabled = disabled
                handler.post {
                    for (listener in listeners.keys.toList()) {
                        listener.onWallpaperZoomDisabledChanged(disabled)
                    }
                    if (disabled && wallpaperZoomActive) {
                        wallpaperZoomActive = false
                        for (listener in listeners.keys.toList()) {
                            listener.onWallpaperZoomActiveChanged(false)
                        }
                    }
                }
            } catch (_: Exception) {}
        }.start()
    }

    private fun decodePath(base64Str: String): Pair<Path, Float>? {
        return try {
            val bytes = Base64.decode(base64Str, Base64.NO_WRAP)
            if (bytes.size < 7) return null

            if (bytes[0].toInt() and 0xFF != PATH_VERSION) {
                Log.w(TAG, "Unknown path format version: ${bytes[0]}")
                return null
            }

            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            buf.get()

            val extractW = buf.short.toInt() and 0xFFFF
            val extractH = buf.short.toInt() and 0xFFFF
            val numContours = buf.short.toInt() and 0xFFFF

            if (extractW <= 0 || extractH <= 0 || numContours <= 0) return null

            val path = Path()
            path.fillType = Path.FillType.WINDING

            for (c in 0 until numContours) {
                if (buf.remaining() < 2) break
                val numPoints = buf.short.toInt() and 0xFFFF
                if (numPoints < 3 || buf.remaining() < numPoints * 4) continue

                for (p in 0 until numPoints) {
                    val x = (buf.short.toInt() and 0xFFFF).toFloat()
                    val y = (buf.short.toInt() and 0xFFFF).toFloat()
                    if (p == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
            }

            Pair(path, extractW.toFloat() / extractH)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode depth path", e)
            null
        }
    }

    private fun hasLockWallpaper(): Boolean {
        return try {
            wallpaperManager?.getWallpaperFile(WallpaperManager.FLAG_LOCK)?.use { true } == true
        } catch (_: Exception) {
            false
        }
    }

}
