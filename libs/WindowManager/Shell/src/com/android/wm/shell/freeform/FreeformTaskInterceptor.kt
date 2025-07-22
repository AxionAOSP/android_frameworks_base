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
package com.android.wm.shell.freeform

import android.app.ActivityManager.RunningTaskInfo
import android.app.ActivityOptions
import android.app.WindowConfiguration
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Handler
import android.util.Log
import android.window.WindowContainerTransaction
import com.android.wm.shell.freeform.FreeformTaskRepository.isTaskAdded
import com.android.wm.shell.freeform.FreeformTaskRepository.isMinimizedTask
import com.android.wm.shell.ShellTaskOrganizer
import com.android.wm.shell.sysui.ShellController
import com.android.wm.shell.sysui.ShellInit
import com.android.wm.shell.sysui.ConfigurationChangeListener
import com.android.wm.shell.common.SyncTransactionQueue
import com.android.wm.shell.windowdecor.WindowDecorViewModel
import kotlin.math.*

class FreeformTaskInterceptor(
    private val shellInit: ShellInit,
    private val context: Context,
    private val shellTaskOrganizer: ShellTaskOrganizer,
    private val syncQueue: SyncTransactionQueue,
    private val mainHandler: Handler,
    private val windowDecorViewModel: WindowDecorViewModel,
    private val shellController: ShellController
) : FloatingIconManager.FloatingIconCallback, IFreeformTaskInterceptor, ConfigurationChangeListener {

    init {
        shellInit.addInitCallback(::onShellInit, this)
    }

    private val iconManager = FloatingIconManager(context, shellTaskOrganizer, this, mainHandler)
    private val freeformTaskInterceptorInitializer: IFreeformTaskInterceptorInitializer =
        IFreeformTaskInterceptorInitializer(windowDecorViewModel, this)

    val defaultBounds: Rect
        get() = FreeformDisplayRepository.defaultBounds

    fun onShellInit() {
        FreeformDisplayRepository.init(context)
        freeformTaskInterceptorInitializer.onInit()
        shellController.addConfigurationChangeListener(this)
    }

    fun onTaskAppeared(taskInfo: RunningTaskInfo?) = withTaskInfo(taskInfo) { info ->
        if (info.getWindowingMode() != FREEFORM) {
            if (info.taskId.isTaskAdded) {
                reset(info, "onTaskAppeared")
            }
        } else if (info.getWindowingMode() == FREEFORM) {
            FreeformTaskRepository.addTask(info.taskId)
            relayout(info)
        }
    }

    fun onFocusTaskChanged(taskInfo: RunningTaskInfo?) = withTaskInfo(taskInfo) { info ->
        logDebug("onFocusTaskChanged: ${info.taskId} updating window..")
        onTaskAppeared(info)
    }

    fun onTaskInfoChanged(taskInfo: RunningTaskInfo?) = withTaskInfo(taskInfo) { info ->
        if (!info.taskId.isTaskAdded) return
        if (info.getWindowingMode() != FREEFORM) {
            reset(info, "onTaskInfoChanged")
        }
    }

    fun onTaskVanished(taskInfo: RunningTaskInfo?) = withTaskInfo(taskInfo) { info ->
        if (!info.taskId.isTaskAdded) return
        reset(info, "onTaskVanished")
    }

    override fun onTaskMinimized(taskInfo: RunningTaskInfo?) = withTaskInfo(taskInfo) { info ->
        if (info.taskId.isMinimizedTask) return
        logDebug("onTaskMinimized: ${info.taskId} minimizing. Tracking for floating icon")
        FreeformTaskRepository.addMinimizedTask(info)
        iconManager.showFloatingIcon(info)
    }

    private fun applyDpi(taskInfo: RunningTaskInfo, dpi: Int) {
        val wct = WindowContainerTransaction().apply {
            setDensityDpi(taskInfo.token, dpi)
        }
        syncQueue.runInSync {
            shellTaskOrganizer.applyTransaction(wct)
        }
    }

    private fun cleanupTask(taskId: Int) {
        FreeformTaskRepository.cleanupTask(taskId)
        iconManager.removeFloatingIcon(taskId)
    }

    fun relayout(taskInfo: RunningTaskInfo?) = withTaskInfo(taskInfo) { info ->
        val taskId = info.taskId
        val taskBounds = info.configuration.windowConfiguration.bounds
        windowDecorViewModel.showResizeVeil(taskId, taskBounds)
        mainHandler.postDelayed({
            val newDpi = FreeformDisplayRepository.freeformDensity
            applyDpi(info, newDpi)
            syncQueue.runAfterSync {
                windowDecorViewModel.hideResizeVeil(taskId)
            }
        }, 100)
    }

    fun reset(taskInfo: RunningTaskInfo?, reason: String) = withTaskInfo(taskInfo) { info ->
        logDebug("$reason: Restoring DPI for task ${info.taskId} (no longer freeform)")
        applyDpi(info, 0)
        cleanupTask(info.taskId)
    }

    override fun onRequestRelaunch(taskInfo: RunningTaskInfo) {
        try {
            val intent = Intent().apply {
                component = taskInfo.topActivity
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
            }
            val bounds = FreeformTaskRepository.getMinimizedTaskBounds(taskInfo.taskId)
                ?: FreeformDisplayRepository.defaultBounds
            val activityOptions = ActivityOptions.makeBasic().apply {
                setLaunchWindowingMode(FREEFORM)
                setLaunchBounds(bounds)
            }
            context.startActivity(intent, activityOptions.toBundle())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to relaunch task", e)
        } finally {
            cleanupTask(taskInfo.taskId)
        }
    }

    override fun onRequestDismiss(taskInfo: RunningTaskInfo) {
        try {
            val wct = WindowContainerTransaction().apply {
                setAlwaysOnTop(taskInfo.token, false)
                setDensityDpi(taskInfo.token, 0)
            }
            shellTaskOrganizer.applyTransaction(wct)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to dismiss task", e)
        } finally {
            cleanupTask(taskInfo.taskId)
        }
    }
    
    override fun onConfigurationChanged(newConfiguration: Configuration) {
        logDebug("Configuration changed: ${newConfiguration}")
        iconManager.onConfigurationChanged()
        FreeformDisplayRepository.onConfigurationChanged(newConfiguration)
    }

    private fun logDebug(message: String) {
        if (DEBUG) Log.d(TAG, message)
    }

    private inline fun withTaskInfo(taskInfo: RunningTaskInfo?, block: (RunningTaskInfo) -> Unit) {
        taskInfo?.let(block)
    }

    companion object {
        private const val TAG = "FreeformTaskInterceptor"
        private const val DEBUG = true
        private const val FREEFORM = WindowConfiguration.WINDOWING_MODE_FREEFORM
    }
}
