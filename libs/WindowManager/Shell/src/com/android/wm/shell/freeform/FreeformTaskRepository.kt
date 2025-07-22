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
import android.graphics.Rect
import android.util.SparseBooleanArray
import java.util.concurrent.ConcurrentHashMap

object FreeformTaskRepository {

    private val _minimizedTasks = ConcurrentHashMap<Int, RunningTaskInfo>()
    private val _freeformTasks = SparseBooleanArray()

    val minimizedTasks: List<RunningTaskInfo>
        get() = _minimizedTasks.values.toList()

    val freeformTasks: List<Int>
        get() = synchronized(_freeformTasks) {
            List(_freeformTasks.size()) { index -> _freeformTasks.keyAt(index) }
        }

    val Int.isTaskAdded: Boolean
        get() = synchronized(_freeformTasks) {
            _freeformTasks.get(this, false)
        }

    val Int.isMinimizedTask: Boolean
        get() = _minimizedTasks.containsKey(this)

    fun addTask(taskId: Int): Boolean = synchronized(_freeformTasks) {
        if (!_freeformTasks.get(taskId, false)) {
            _freeformTasks.put(taskId, true)
            true
        } else {
            false
        }
    }

    fun removeTask(taskId: Int) = synchronized(_freeformTasks) {
        _freeformTasks.delete(taskId)
    }

    fun addMinimizedTask(taskInfo: RunningTaskInfo): Boolean {
        val taskId = taskInfo.taskId
        return _minimizedTasks.putIfAbsent(taskId, taskInfo) == null
    }

    fun removeMinimizedTask(taskId: Int) {
        _minimizedTasks.remove(taskId)
    }

    operator fun get(taskId: Int): RunningTaskInfo? = _minimizedTasks[taskId]

    fun getMinimizedTaskBounds(taskId: Int): Rect? {
        return _minimizedTasks[taskId]
            ?.configuration
            ?.windowConfiguration
            ?.bounds
            ?.takeIf { !it.isEmpty }
    }

    fun cleanupTask(taskId: Int) {
        removeTask(taskId)
        removeMinimizedTask(taskId)
    }
}
