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

package com.android.systemui.routines.domain

import android.content.Context
import android.os.Handler
import android.util.Log
import android.widget.Toast
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.res.R
import com.android.systemui.routines.data.RoutinesRepository
import com.android.systemui.routines.domain.action.ActionExecutor
import com.android.systemui.routines.domain.condition.ConditionEvaluator
import com.android.systemui.routines.domain.trigger.EventTriggerMonitor
import com.android.systemui.routines.domain.trigger.LocationTriggerMonitor
import com.android.systemui.routines.domain.trigger.TimeTriggerScheduler
import com.android.systemui.routines.domain.trigger.TriggerEvaluator
import com.android.systemui.routines.model.Routine
import com.android.systemui.routines.model.Trigger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

@SysUISingleton
class RoutinesInteractor @Inject constructor(
    @Application private val context: Context,
    @Application private val scope: CoroutineScope,
    @Main private val mainHandler: Handler,
    private val repository: RoutinesRepository,
    private val settings: RoutinesSettings,
    private val timeTriggerScheduler: TimeTriggerScheduler,
    private val eventTriggerMonitor: EventTriggerMonitor,
    private val locationTriggerMonitor: LocationTriggerMonitor,
    private val triggerEvaluator: TriggerEvaluator,
    private val conditionEvaluator: ConditionEvaluator,
    private val actionExecutor: ActionExecutor,
) {

    private var monitorJob: Job? = null
    private val executionGuard = ConcurrentHashMap<String, Long>()

    fun init() {
        timeTriggerScheduler.setCallback { routineId, _ ->
            scope.launch { onTimeTriggerFired(routineId) }
        }

        eventTriggerMonitor.setCallback { trigger ->
            scope.launch { onEventTriggerFired(trigger) }
        }

        locationTriggerMonitor.setCallback { trigger ->
            scope.launch { onEventTriggerFired(trigger) }
        }

        monitorJob = scope.launch {
            combine(
                settings.isEnabled,
                repository.routinesFlow,
            ) { enabled, routines -> enabled to routines }
                .collectLatest { (enabled, routines) ->
                    repository.updateCache(routines)
                    if (enabled) {
                        startMonitoring(routines)
                    } else {
                        stopMonitoring()
                    }
                }
        }
    }

    fun destroy() {
        monitorJob?.cancel()
        monitorJob = null
        stopMonitoring()
        timeTriggerScheduler.setCallback { _, _ -> }
        eventTriggerMonitor.setCallback { }
        locationTriggerMonitor.setCallback { }
    }

    private fun startMonitoring(routines: List<Routine>) {
        timeTriggerScheduler.cancelAll()
        locationTriggerMonitor.removeAllGeofences()
        timeTriggerScheduler.start()
        locationTriggerMonitor.start()

        val activeRoutines = routines.filter { it.enabled }
        val activeIds = mutableSetOf<String>()

        val requiredGroups = mutableSetOf<EventTriggerMonitor.ListenerGroup>()
        activeRoutines.forEach { routine ->
            registerTriggers(routine)
            activeIds.add(routine.id)
            routine.triggers.forEach { trigger ->
                EventTriggerMonitor.triggerToGroup(trigger)?.let { requiredGroups.add(it) }
            }
        }

        eventTriggerMonitor.updateListeners(requiredGroups)
        executionGuard.keys.removeAll { it !in activeIds }
        Log.d(TAG, "Monitoring started for ${activeIds.size} routines, " +
            "${requiredGroups.size} listener groups")
    }

    private fun stopMonitoring() {
        timeTriggerScheduler.stop()
        eventTriggerMonitor.stop()
        locationTriggerMonitor.stop()
        executionGuard.clear()
        Log.d(TAG, "Monitoring stopped")
    }

    private fun registerTriggers(routine: Routine) {
        routine.triggers.forEachIndexed { index, trigger ->
            when (trigger) {
                is Trigger.TimeOfDay ->
                    timeTriggerScheduler.scheduleTimeOfDay(routine.id, index, trigger)
                is Trigger.Interval ->
                    timeTriggerScheduler.scheduleInterval(routine.id, index, trigger)
                is Trigger.Location ->
                    locationTriggerMonitor.registerGeofence(routine.id, index, trigger)
                else -> Unit
            }
        }
    }

    private suspend fun onTimeTriggerFired(routineId: String) {
        val routine = repository.getRoutine(routineId) ?: return
        if (!routine.enabled) return
        if (!settings.isEnabled.value) return

        if (conditionEvaluator.evaluateAll(routine.conditions)) {
            executeRoutine(routine)
        }

        routine.triggers.forEachIndexed { index, trigger ->
            when (trigger) {
                is Trigger.TimeOfDay ->
                    timeTriggerScheduler.rescheduleTimeOfDay(routine.id, index, trigger)
                is Trigger.Interval ->
                    timeTriggerScheduler.rescheduleInterval(routine.id, index, trigger)
                else -> Unit
            }
        }
    }

    private suspend fun onEventTriggerFired(event: Trigger) {
        val routines = repository.routines.value
        if (!settings.isEnabled.value) return

        val matching = triggerEvaluator.findMatchingRoutines(routines, event)
        for (routine in matching) {
            if (conditionEvaluator.evaluateAll(routine.conditions)) {
                executeRoutine(routine)
            }
        }
    }

    private suspend fun executeRoutine(routine: Routine) {
        if (!checkExecutionGuard(routine.id)) return
        Log.d(TAG, "Executing routine: ${routine.name} (${routine.id})")
        mainHandler.post {
            Toast.makeText(
                context,
                context.getString(R.string.ax_routines_toast_executing, routine.name),
                Toast.LENGTH_SHORT,
            ).show()
        }
        runCatching {
            actionExecutor.executeActions(routine.actions, routine.name)
            repository.markTriggered(routine.id)
        }.onFailure { e ->
            Log.e(TAG, "Failed to execute routine: ${routine.name}", e)
        }
    }

    private fun checkExecutionGuard(routineId: String): Boolean {
        val now = System.currentTimeMillis()
        val lastExecution = executionGuard[routineId] ?: 0L
        if (now - lastExecution < MIN_EXECUTION_INTERVAL_MS) {
            Log.d(TAG, "Skipping routine $routineId: too soon since last execution")
            return false
        }
        executionGuard[routineId] = now
        return true
    }

    companion object {
        private const val TAG = "RoutinesInteractor"
        private const val MIN_EXECUTION_INTERVAL_MS = 5_000L
    }
}
