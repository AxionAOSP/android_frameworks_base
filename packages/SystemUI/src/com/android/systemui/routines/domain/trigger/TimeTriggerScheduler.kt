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

package com.android.systemui.routines.domain.trigger

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.routines.model.Trigger
import java.util.Calendar
import javax.inject.Inject

@SysUISingleton
class TimeTriggerScheduler @Inject constructor(
    @Application private val context: Context,
) {

    private val alarmManager = context.getSystemService(AlarmManager::class.java)
    private val scheduledAlarms = mutableMapOf<String, PendingIntent>()
    private var callback: ((routineId: String, triggerIndex: Int) -> Unit)? = null
    private var receiverRegistered = false

    private val alarmReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != ACTION_ROUTINE_TRIGGER) return
            val routineId = intent.getStringExtra(EXTRA_ROUTINE_ID) ?: return
            val triggerIndex = intent.getIntExtra(EXTRA_TRIGGER_INDEX, -1)
            if (triggerIndex < 0) return
            Log.d(TAG, "Time trigger fired: routine=$routineId, trigger=$triggerIndex")
            callback?.invoke(routineId, triggerIndex)
        }
    }

    fun setCallback(cb: (routineId: String, triggerIndex: Int) -> Unit) {
        callback = cb
    }

    fun start() {
        if (!receiverRegistered) {
            context.registerReceiver(
                alarmReceiver,
                IntentFilter(ACTION_ROUTINE_TRIGGER),
                Context.RECEIVER_NOT_EXPORTED,
            )
            receiverRegistered = true
        }
    }

    fun stop() {
        cancelAll()
        if (receiverRegistered) {
            context.unregisterReceiver(alarmReceiver)
            receiverRegistered = false
        }
    }

    fun scheduleTimeOfDay(routineId: String, triggerIndex: Int, trigger: Trigger.TimeOfDay) {
        val key = buildKey(routineId, triggerIndex)
        cancelAlarm(key)

        val nextFireTime = computeNextFireTime(trigger)
        if (nextFireTime < 0) return

        val pi = createPendingIntent(routineId, triggerIndex, key)
        alarmManager?.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            nextFireTime,
            pi,
        )
        scheduledAlarms[key] = pi
        Log.d(TAG, "Scheduled time trigger: key=$key, fireAt=$nextFireTime")
    }

    fun scheduleInterval(routineId: String, triggerIndex: Int, trigger: Trigger.Interval) {
        val key = buildKey(routineId, triggerIndex)
        cancelAlarm(key)

        val intervalMs = trigger.intervalMinutes * 60_000L
        val nextFireTime = System.currentTimeMillis() + intervalMs

        val pi = createPendingIntent(routineId, triggerIndex, key)
        alarmManager?.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            nextFireTime,
            pi,
        )
        scheduledAlarms[key] = pi
    }

    fun rescheduleTimeOfDay(routineId: String, triggerIndex: Int, trigger: Trigger.TimeOfDay) {
        scheduleTimeOfDay(routineId, triggerIndex, trigger)
    }

    fun rescheduleInterval(routineId: String, triggerIndex: Int, trigger: Trigger.Interval) {
        scheduleInterval(routineId, triggerIndex, trigger)
    }

    fun cancelRoutine(routineId: String) {
        val keysToRemove = scheduledAlarms.keys.filter { it.startsWith("$routineId:") }
        keysToRemove.forEach { cancelAlarm(it) }
    }

    fun cancelAll() {
        scheduledAlarms.keys.toList().forEach { cancelAlarm(it) }
    }

    private fun cancelAlarm(key: String) {
        scheduledAlarms.remove(key)?.let { pi ->
            alarmManager?.cancel(pi)
            pi.cancel()
        }
    }

    private fun computeNextFireTime(trigger: Trigger.TimeOfDay): Long {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, trigger.hour)
            set(Calendar.MINUTE, trigger.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        if (cal.timeInMillis <= System.currentTimeMillis()) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }

        if (trigger.daysOfWeek.size < 7) {
            for (i in 0 until 7) {
                if (cal.get(Calendar.DAY_OF_WEEK) in trigger.daysOfWeek) break
                cal.add(Calendar.DAY_OF_YEAR, 1)
            }
        }

        return cal.timeInMillis
    }

    private fun createPendingIntent(
        routineId: String,
        triggerIndex: Int,
        key: String,
    ): PendingIntent {
        val intent = Intent(ACTION_ROUTINE_TRIGGER).apply {
            setPackage(context.packageName)
            putExtra(EXTRA_ROUTINE_ID, routineId)
            putExtra(EXTRA_TRIGGER_INDEX, triggerIndex)
        }
        return PendingIntent.getBroadcast(
            context,
            key.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun buildKey(routineId: String, triggerIndex: Int) = "$routineId:$triggerIndex"

    companion object {
        private const val TAG = "RoutinesTimeTrigger"
        const val ACTION_ROUTINE_TRIGGER = "com.android.systemui.routines.ACTION_TRIGGER"
        const val EXTRA_ROUTINE_ID = "routine_id"
        const val EXTRA_TRIGGER_INDEX = "trigger_index"
    }
}
