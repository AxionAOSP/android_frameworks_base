/*
 * Copyright (C) 2025 AxionOS Project
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
package com.android.wm.shell.windowdecor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.UserHandle
import android.util.Log
import android.view.SurfaceControl
import com.android.launcher3.icons.BaseIconFactory
import com.android.launcher3.icons.IconProvider
import com.android.wm.shell.ShellTaskOrganizer
import com.android.wm.shell.common.DisplayController
import com.android.wm.shell.windowdecor.common.WindowDecorTaskResourceLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainCoroutineDispatcher
import java.util.function.Supplier


interface VeilWindowDecoration {
    fun showResizeVeil(bounds: Rect)
    fun updateResizeVeil(bounds: Rect)
    fun hideResizeVeil()
}

class VeiledResizer(
    private val context: Context,
    private val displayController: DisplayController,
    private val taskResourceLoader: WindowDecorTaskResourceLoader,
    private val mainDispatcher: MainCoroutineDispatcher,
    private val bgScope: CoroutineScope,
) {

    companion object {
        private const val TAG = "VeiledResizer"
    }

    private var resizeVeilBitmap: Bitmap? = null
    private var resizeVeil: ResizeVeil? = null
    private var taskSurface: SurfaceControl? = null
    private var taskInfo: android.app.ActivityManager.RunningTaskInfo? = null
    private var surfaceControlTransactionSupplier: Supplier<SurfaceControl.Transaction>? = null

    fun prepare(
        taskInfo: android.app.ActivityManager.RunningTaskInfo,
        taskSurface: SurfaceControl,
        surfaceControlTransactionSupplier: Supplier<SurfaceControl.Transaction>
    ) {
        this.taskInfo = taskInfo
        this.taskSurface = taskSurface
        this.surfaceControlTransactionSupplier = surfaceControlTransactionSupplier
        taskResourceLoader.onWindowDecorCreated(taskInfo)
        loadAppInfoIcon()
    }

    private fun loadAppInfoIcon() {
        val packageName = taskInfo?.realActivity?.packageName ?: return
        try {
            val iconProvider = IconProvider(context)
            val drawable: Drawable = iconProvider.getIcon(
                context.packageManager.getActivityInfo(taskInfo!!.baseActivity!!, 0)
            )
            val iconSize = context.resources.getDimensionPixelSize(
                com.android.wm.shell.R.dimen.desktop_mode_resize_veil_icon_size
            )
            val iconFactory = BaseIconFactory(context, context.resources.displayMetrics.densityDpi, iconSize)
            resizeVeilBitmap = iconFactory.createScaledBitmap(drawable, BaseIconFactory.MODE_DEFAULT)
        } catch (e: Exception) {
            Log.w(TAG, "Unable to load icon for package: $packageName", e)
        }
    }

    fun createResizeVeil() {
        if (resizeVeil == null && taskSurface != null && taskInfo != null && resizeVeilBitmap != null) {
            resizeVeil = ResizeVeil(
                context = context,
                displayController = displayController,
                taskResourceLoader = taskResourceLoader,
                mainDispatcher = mainDispatcher,
                bgScope = bgScope,
                parentSurface = taskSurface!!,
                surfaceControlTransactionSupplier = surfaceControlTransactionSupplier!!,
                taskInfo = taskInfo!!
            )
        }
    }

    fun showResizeVeil(bounds: Rect) {
        resizeVeil?.showVeil(taskSurface!!, bounds, taskInfo!!)
    }

    fun updateResizeVeil(bounds: Rect) {
        resizeVeil?.updateResizeVeil(bounds)
    }

    fun hideResizeVeil() {
        resizeVeil?.hideVeil()
    }

    fun disposeResizeVeil() {
        resizeVeil?.dispose()
        resizeVeil = null
    }
}
