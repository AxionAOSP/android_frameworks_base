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
package com.android.systemui.doze

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.SystemClock
import android.os.UserHandle
import android.util.Log
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.keyguard.KeyguardUpdateMonitorCallback
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.statusbar.pipeline.battery.domain.interactor.BatteryInteractor
import com.android.systemui.util.settings.SecureSettings
import com.android.systemui.util.settings.SettingsProxyExt.observerFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Calendar
import javax.inject.Inject

@SysUISingleton
class AodScheduleController @Inject constructor(
    private val context: Context,
    @Main private val handler: Handler,
    private val keyguardUpdateMonitor: KeyguardUpdateMonitor,
    private val secureSettings: SecureSettings,
    private val batteryInteractor: BatteryInteractor,
    @Application private val scope: CoroutineScope
) : CoreStartable {
    companion object {
        private const val TAG = "AodScheduleController"
        
        const val AOD_SCHEDULE_MODE = "aod_schedule_mode"
        const val AOD_SCHEDULE_START_TIME = "aod_schedule_start_time"
        const val AOD_SCHEDULE_END_TIME = "aod_schedule_end_time"
        
        const val MODE_DISABLED = 0
        const val MODE_ALWAYS = 1
        const val MODE_CHARGE_ONLY = 2
        const val MODE_SCHEDULED = 3
        const val MODE_SCHEDULED_CHARGE = 4
        
        private const val DEFAULT_START_TIME = "2300"
        private const val DEFAULT_END_TIME = "0700"
        private const val DATE_FORMAT = "HHmm"
        
        private const val ACTION_ENTER_SCHEDULE = "com.android.systemui.aod.schedule.enter"
        private const val ACTION_EXIT_SCHEDULE = "com.android.systemui.aod.schedule.exit"
    }
    
    private val alarmManager: AlarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val dateTimeFormatter = DateTimeFormatter.ofPattern(DATE_FORMAT)
    
    private var scheduleMode = MODE_DISABLED
    private var startTime: String = DEFAULT_START_TIME
    private var endTime: String = DEFAULT_END_TIME
    private var currentChargingState = false
    
    private var isWithinScheduleWindow = true
    
    private var enterSchedulePendingIntent: PendingIntent? = null
    private var exitSchedulePendingIntent: PendingIntent? = null

    private val scheduleReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_ENTER_SCHEDULE -> {
                    updateScheduleState(currentChargingState)
                    notifyScheduleChange()
                    rescheduleAlarms()
                }
                ACTION_EXIT_SCHEDULE -> {
                    updateScheduleState(currentChargingState)
                    notifyScheduleChange()
                    rescheduleAlarms()
                }
            }
        }
    }
    
    private val keyguardCallback = object : KeyguardUpdateMonitorCallback() {
        override fun onUserSwitchComplete(newUserId: Int) {
            scope.launch {
                loadSettings()
                batteryInteractor.isCharging.collect { isCharging ->
                    currentChargingState = isCharging
                    updateScheduleState(isCharging)
                    rescheduleAlarms()
                    notifyScheduleChange()
                }
            }
        }
    }
    
    override fun start() {
        scope.launch {
            combine(
                secureSettings.observerFlow(
                    AOD_SCHEDULE_MODE,
                    AOD_SCHEDULE_START_TIME,
                    AOD_SCHEDULE_END_TIME
                ).onStart { emit(Unit) },
                batteryInteractor.isCharging
            ) { _, isCharging ->
                currentChargingState = isCharging
                loadSettings()
                updateScheduleState(isCharging)
                rescheduleAlarms()
                notifyScheduleChange()
            }.collect { }
        }
        
        registerReceiver()
        keyguardUpdateMonitor.registerCallback(keyguardCallback)
    }
    
    private fun loadSettings() {
        scheduleMode = secureSettings.getIntForUser(
            AOD_SCHEDULE_MODE, MODE_ALWAYS, UserHandle.USER_CURRENT
        )
        
        startTime = secureSettings.getStringForUser(
            AOD_SCHEDULE_START_TIME, UserHandle.USER_CURRENT
        ) ?: DEFAULT_START_TIME
        
        endTime = secureSettings.getStringForUser(
            AOD_SCHEDULE_END_TIME, UserHandle.USER_CURRENT
        ) ?: DEFAULT_END_TIME
    }
    
    private fun updateScheduleState(isCharging: Boolean = false) {
        isWithinScheduleWindow = when (scheduleMode) {
            MODE_DISABLED -> false
            MODE_ALWAYS -> true
            MODE_CHARGE_ONLY -> isCharging
            MODE_SCHEDULED -> isWithinSchedule()
            MODE_SCHEDULED_CHARGE -> isCharging || isWithinSchedule()
            else -> false
        }
    }
    
    private fun registerReceiver() {
        val filter = IntentFilter().apply {
            addAction(ACTION_ENTER_SCHEDULE)
            addAction(ACTION_EXIT_SCHEDULE)
        }
        context.registerReceiver(scheduleReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
    }
    
    fun shouldShowAod(): Boolean {
        return isWithinScheduleWindow
    }
    
    private fun isWithinSchedule(): Boolean {
        try {
            val now = LocalTime.now()
            val start = LocalTime.parse(startTime, dateTimeFormatter)
            val end = LocalTime.parse(endTime, dateTimeFormatter)
            
            val result = if (start.isBefore(end)) {
                !now.isBefore(start) && now.isBefore(end)
            } else {
                !now.isBefore(start) || now.isBefore(end)
            }
            return result
        } catch (e: Exception) {
            return true
        }
    }
    
    private fun rescheduleAlarms() {
        cancelAlarms()
        
        if (scheduleMode != MODE_SCHEDULED && scheduleMode != MODE_SCHEDULED_CHARGE) {
            return
        }
        
        try {
            val calendar = Calendar.getInstance()
            val currentHour = calendar.get(Calendar.HOUR_OF_DAY)
            val currentMinute = calendar.get(Calendar.MINUTE)
            val currentSecond = calendar.get(Calendar.SECOND)
            
            val startHour = startTime.substring(0, 2).toInt()
            val startMinute = startTime.substring(2, 4).toInt()
            val endHour = endTime.substring(0, 2).toInt()
            val endMinute = endTime.substring(2, 4).toInt()
            
            val enterDelayMs: Long
            val exitDelayMs: Long
            
            if (isWithinScheduleWindow) {
                exitDelayMs = calculateDelayToTime(currentHour, currentMinute, currentSecond, endHour, endMinute)
                enterDelayMs = exitDelayMs + calculateDurationBetweenTimes(endHour, endMinute, startHour, startMinute)
            } else {
                enterDelayMs = calculateDelayToTime(currentHour, currentMinute, currentSecond, startHour, startMinute)
                exitDelayMs = enterDelayMs + calculateDurationBetweenTimes(startHour, startMinute, endHour, endMinute)
            }
            
            if (enterDelayMs > 0) {
                scheduleEnterAlarm(enterDelayMs)
            }
            if (exitDelayMs > 0) {
                scheduleExitAlarm(exitDelayMs)
            }
        } catch (e: Exception) {
            Log.e(TAG, "rescheduleAlarms error", e)
        }
    }
    
    private fun calculateDelayToTime(currentHour: Int, currentMinute: Int, currentSecond: Int, 
                                     targetHour: Int, targetMinute: Int): Long {
        var hourDiff = targetHour - currentHour
        if (hourDiff < 0 || (hourDiff == 0 && targetMinute <= currentMinute)) {
            hourDiff += 24
        }
        val minuteDiff = targetMinute - currentMinute
        val secondDiff = -currentSecond
        
        return ((hourDiff * 3600L + minuteDiff * 60L + secondDiff) * 1000L).coerceAtLeast(1000L)
    }
    
    private fun calculateDurationBetweenTimes(fromHour: Int, fromMinute: Int, toHour: Int, toMinute: Int): Long {
        var hourDiff = toHour - fromHour
        if (hourDiff <= 0) {
            hourDiff += 24
        }
        val minuteDiff = toMinute - fromMinute
        return (hourDiff * 3600L + minuteDiff * 60L) * 1000L
    }
    
    private fun scheduleEnterAlarm(delayMs: Long) {
        if (enterSchedulePendingIntent == null) {
            val intent = Intent(ACTION_ENTER_SCHEDULE).setPackage(context.packageName)
            enterSchedulePendingIntent = PendingIntent.getBroadcast(
                context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }
        val triggerTime = SystemClock.elapsedRealtime() + delayMs
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            triggerTime,
            enterSchedulePendingIntent!!
        )
        Log.d(TAG, "Scheduled ENTER alarm: delay=${delayMs}ms, triggerAt=$triggerTime")
    }
    
    private fun scheduleExitAlarm(delayMs: Long) {
        if (exitSchedulePendingIntent == null) {
            val intent = Intent(ACTION_EXIT_SCHEDULE).setPackage(context.packageName)
            exitSchedulePendingIntent = PendingIntent.getBroadcast(
                context, 1, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }
        val triggerTime = SystemClock.elapsedRealtime() + delayMs
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            triggerTime,
            exitSchedulePendingIntent!!
        )
        Log.d(TAG, "Scheduled EXIT alarm: delay=${delayMs}ms, triggerAt=$triggerTime")
    }
    
    private fun cancelAlarms() {
        enterSchedulePendingIntent?.let { alarmManager.cancel(it) }
        exitSchedulePendingIntent?.let { alarmManager.cancel(it) }
    }
    
    private val callbacks = java.util.concurrent.CopyOnWriteArrayList<Runnable>()
    
    fun addCallback(cb: Runnable) {
        callbacks.add(cb)
    }

    fun removeCallback(cb: Runnable) {
        callbacks.remove(cb)
    }
    
    private fun notifyScheduleChange() {
        callbacks.forEach { it.run() }
    }
    
    fun isScheduleMode(): Boolean = scheduleMode != MODE_DISABLED && scheduleMode != MODE_ALWAYS
    
    fun getStartTime(): String = startTime
    
    fun getEndTime(): String = endTime
}
