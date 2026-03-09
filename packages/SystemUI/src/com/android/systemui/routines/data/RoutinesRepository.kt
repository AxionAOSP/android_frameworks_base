/*
 * Copyright (C) 2025-2026 AxionOS
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

package com.android.systemui.routines.data

import android.os.UserHandle
import android.util.Log
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.routines.model.Routine
import com.android.systemui.util.settings.SecureSettings
import com.android.systemui.util.settings.SettingsProxyExt.observerFlow
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext
import javax.inject.Inject

@SysUISingleton
class RoutinesRepository @Inject constructor(
    private val secureSettings: SecureSettings,
    @Background private val bgDispatcher: CoroutineDispatcher,
    private val serializer: RoutineSerializer,
) {

    private val _routines = MutableStateFlow<List<Routine>>(emptyList())
    val routines: StateFlow<List<Routine>> = _routines.asStateFlow()

    val routinesFlow: Flow<List<Routine>> =
        secureSettings.observerFlow(KEY_ROUTINES_DATA)
            .onStart { emit(Unit) }
            .map { readRoutines() }
            .flowOn(bgDispatcher)

    private fun readRoutines(): List<Routine> {
        val json = runCatching {
            secureSettings.getStringForUser(
                KEY_ROUTINES_DATA, UserHandle.USER_CURRENT,
            ) ?: ""
        }.getOrElse { e ->
            Log.e(TAG, "Failed to read routines from Settings", e)
            ""
        }
        return serializer.deserializeRoutines(json)
    }

    fun updateCache(routines: List<Routine>) {
        _routines.value = routines
    }

    suspend fun save() {
        withContext(bgDispatcher) {
            runCatching {
                val json = serializer.serializeRoutines(_routines.value)
                secureSettings.putStringForUser(
                    KEY_ROUTINES_DATA, json, UserHandle.USER_CURRENT,
                )
            }.onFailure { e ->
                Log.e(TAG, "Failed to save routines to Settings", e)
            }
        }
    }

    suspend fun addRoutine(routine: Routine) {
        _routines.value = _routines.value + routine
        save()
    }

    suspend fun updateRoutine(routine: Routine) {
        _routines.value = _routines.value.map {
            if (it.id == routine.id) routine else it
        }
        save()
    }

    suspend fun removeRoutine(routineId: String) {
        _routines.value = _routines.value.filter { it.id != routineId }
        save()
    }

    suspend fun getRoutine(routineId: String): Routine? =
        _routines.value.find { it.id == routineId }

    suspend fun setRoutineEnabled(routineId: String, enabled: Boolean) {
        _routines.value = _routines.value.map {
            if (it.id == routineId) it.copy(enabled = enabled) else it
        }
        save()
    }

    fun markTriggered(routineId: String, timestamp: Long = System.currentTimeMillis()) {
        _routines.value = _routines.value.map {
            if (it.id == routineId) it.copy(lastTriggeredAt = timestamp) else it
        }
    }

    companion object {
        private const val TAG = "RoutinesRepository"
        private const val KEY_ROUTINES_DATA = "ax_routines_data"
    }
}
