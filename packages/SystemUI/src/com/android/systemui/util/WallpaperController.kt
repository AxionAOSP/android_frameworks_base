/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.util

import android.app.WallpaperManager
import android.util.Log
import android.view.View
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.shared.clocks.DepthWallpaperProvider
import com.android.systemui.wallpapers.data.repository.WallpaperRepository
import javax.inject.Inject
import kotlin.math.max

private const val TAG = "WallpaperController"

/**
 * Controller for wallpaper-related logic.
 *
 * Note: New logic should be added to [WallpaperRepository], not this class.
 */
@SysUISingleton
class WallpaperController
@Inject
constructor(
    private val wallpaperManager: WallpaperManager,
    private val wallpaperRepository: WallpaperRepository,
) {

    var rootView: View? = null
        set(value) {
            field = value
            wallpaperRepository.rootView = value
            updateZoom()
        }

    private var notificationShadeZoomOut: Float = 0f
    private var unfoldTransitionZoomOut: Float = 0f
    private var screenOnZoomOut: Float = 0f
    private var launcherAnimationZoomOut: Float = 0f
    private var launcherDepthZoomOut: Float = 0f
    private var launcherZoomEnabled = true
    private var wallpaperZoomDisabled = false

    private val shouldUseDefaultUnfoldTransition: Boolean
        get() = wallpaperRepository.wallpaperInfo.value?.shouldUseDefaultUnfoldTransition() ?: true

    fun setNotificationShadeZoom(zoomOut: Float) {
        notificationShadeZoomOut = zoomOut
        updateZoom()
    }

    fun setUnfoldTransitionZoom(zoomOut: Float) {
        if (shouldUseDefaultUnfoldTransition) {
            unfoldTransitionZoomOut = zoomOut
            updateZoom()
        }
    }

    fun setScreenOnZoom(zoomOut: Float) {
        screenOnZoomOut = zoomOut
        updateZoom()
    }

    fun setLauncherAnimationZoom(zoomOut: Float) {
        launcherAnimationZoomOut = zoomOut.coerceIn(0f, 1f)
        updateZoom()
    }

    fun setLauncherDepthZoom(zoomOut: Float) {
        launcherDepthZoomOut = zoomOut.coerceIn(0f, 1f)
        updateZoom()
    }

    fun setLauncherZoomEnabled(enabled: Boolean) {
        if (launcherZoomEnabled == enabled) return

        launcherZoomEnabled = enabled
        updateZoom()
    }

    fun setWallpaperZoomDisabled(disabled: Boolean) {
        if (wallpaperZoomDisabled == disabled) return

        wallpaperZoomDisabled = disabled
        updateZoom()
    }

    private fun updateZoom() {
        val shadeZoomOut = max(notificationShadeZoomOut, unfoldTransitionZoomOut)
        val launcherZoomOut =
            if (launcherZoomEnabled) {
                max(launcherAnimationZoomOut, launcherDepthZoomOut)
            } else {
                0f
            }
        val zoomOut =
            if (wallpaperZoomDisabled) {
                1f
            } else {
                max(max(shadeZoomOut, launcherZoomOut), screenOnZoomOut)
            }
        val zoomActive = zoomOut > 0f
        if (zoomActive) {
            DepthWallpaperProvider.setWallpaperZoomActive(true)
        }
        if (setWallpaperZoom(zoomOut) && !zoomActive) {
            DepthWallpaperProvider.setWallpaperZoomActive(false)
        }
    }

    private fun setWallpaperZoom(zoomOut: Float): Boolean {
        val root = rootView ?: return false
        return try {
            if (root.isAttachedToWindow && root.windowToken != null) {
                wallpaperManager.setWallpaperZoomOut(root.windowToken, zoomOut)
                true
            } else {
                Log.i(TAG, "Won't set zoom. Window not attached $root")
                false
            }
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Can't set zoom. Window is gone: ${rootView?.windowToken}", e)
            false
        }
    }
}
