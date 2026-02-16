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
import java.nio.ByteBuffer
import java.nio.ByteOrder

object DepthWallpaperProvider {

    private const val TAG = "DepthWallpaperProvider"
    private const val SETTING_DEPTH_MASK = "ax_depth_subject_mask"
    private const val SETTING_DEPTH_ENABLED = "ax_depth_clock_enabled"
    private const val PATH_VERSION = 0x01

    @Volatile
    var subjectPath: Path? = null
        private set

    @Volatile
    var pathAspect: Float = 1f
        private set

    @Volatile
    var isEnabled: Boolean = false
        private set

    private val listeners = mutableSetOf<DepthMaskListener>()
    private val handler = Handler(Looper.getMainLooper())
    private var registered = false
    private var contentResolver: ContentResolver? = null
    private var wallpaperManager: WallpaperManager? = null

    interface DepthMaskListener {
        fun onDepthDataChanged(path: Path?, pathAspect: Float)
    }

    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            refreshAsync()
        }
    }

    fun init(context: Context) {
        if (registered) return
        registered = true
        contentResolver = context.contentResolver
        wallpaperManager = WallpaperManager.getInstance(context)

        context.contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(SETTING_DEPTH_MASK),
            false, observer
        )
        context.contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(SETTING_DEPTH_ENABLED),
            false, observer
        )

        refreshAsync()
    }

    fun addListener(listener: DepthMaskListener) {
        listeners.add(listener)
        listener.onDepthDataChanged(
            if (isEnabled) subjectPath else null,
            pathAspect
        )
        if (subjectPath == null) {
            refreshAsync()
        }
    }

    fun removeListener(listener: DepthMaskListener) {
        listeners.remove(listener)
    }

    private fun refreshAsync() {
        val cr = contentResolver ?: return
        Thread {
            try {
                val isLiveWallpaper = wallpaperManager?.wallpaperInfo != null
                val enabled = !isLiveWallpaper &&
                    Settings.Secure.getInt(cr, SETTING_DEPTH_ENABLED, 0) == 1
                val maskStr = if (enabled) {
                    Settings.Secure.getString(cr, SETTING_DEPTH_MASK)
                } else null

                val pathResult = maskStr?.let { decodePath(it) }

                isEnabled = enabled
                subjectPath = pathResult?.first
                pathAspect = pathResult?.second ?: 1f

                handler.post {
                    notifyListeners(if (enabled) pathResult?.first else null)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to refresh depth data", e)
                handler.post { notifyListeners(null) }
            }
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

    private fun notifyListeners(path: Path?) {
        val aspect = pathAspect
        for (listener in listeners) {
            listener.onDepthDataChanged(path, aspect)
        }
    }
}
