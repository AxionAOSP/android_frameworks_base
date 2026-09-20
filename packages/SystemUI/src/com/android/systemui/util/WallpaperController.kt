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
import android.view.View
import androidx.annotation.VisibleForTesting
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.wallpapers.AxWallpaperZoomController
import com.android.systemui.wallpapers.WallpaperZoomOwner
import com.android.systemui.wallpapers.data.repository.WallpaperRepository
import javax.inject.Inject

/**
 * Controller for wallpaper-related logic.
 *
 * Note: New logic should be added to [WallpaperRepository], not this class.
 */
@SysUISingleton
class WallpaperController
@Inject
constructor(
    private val wallpaperRepository: WallpaperRepository,
    private val axWallpaperZoomController: AxWallpaperZoomController,
) {

    @VisibleForTesting
    constructor(
        wallpaperManager: WallpaperManager,
        wallpaperRepository: WallpaperRepository,
    ) : this(
        wallpaperRepository,
        AxWallpaperZoomController(wallpaperManager, wallpaperRepository, null)
    )

    var rootView: View? = null
        set(value) {
            field = value
            wallpaperRepository.rootView = value
            axWallpaperZoomController.onRootViewSet(value)
        }

    private val shouldUseDefaultUnfoldTransition: Boolean
        get() = wallpaperRepository.wallpaperInfo.value?.shouldUseDefaultUnfoldTransition() ?: true

    fun setNotificationShadeZoom(zoomOut: Float) {
        axWallpaperZoomController.setZoom(
            WallpaperZoomOwner.NOTIFICATION_SHADE,
            zoomOut,
            "notificationShade",
        )
    }

    fun setUnfoldTransitionZoom(zoomOut: Float) {
        if (shouldUseDefaultUnfoldTransition) {
            axWallpaperZoomController.setZoom(
                WallpaperZoomOwner.UNFOLD,
                zoomOut,
                "unfold",
            )
        }
    }

    fun setLauncherAnimationZoom(zoomOut: Float) {
        axWallpaperZoomController.setLauncherWallpaperZoom(zoomOut)
    }

    fun setWallpaperZoom(zoomOut: Float): Boolean {
        return axWallpaperZoomController.applyWallpaperZoom(zoomOut)
    }
}
