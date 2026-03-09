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

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.routines.model.Routine
import com.android.systemui.routines.model.Trigger
import javax.inject.Inject

@SysUISingleton
class TriggerEvaluator @Inject constructor() {

    fun matchesEvent(trigger: Trigger, event: Trigger): Boolean = when {
        trigger is Trigger.ChargingState && event is Trigger.ChargingState ->
            trigger.charging == event.charging

        trigger is Trigger.BatteryLevel && event is Trigger.BatteryLevel ->
            matchesBatteryLevel(trigger, event)

        trigger is Trigger.WifiState && event is Trigger.WifiState ->
            matchesWifiState(trigger, event)

        trigger is Trigger.BluetoothState && event is Trigger.BluetoothState ->
            matchesBluetoothState(trigger, event)

        trigger is Trigger.ScreenState && event is Trigger.ScreenState ->
            trigger.on == event.on

        trigger is Trigger.FeatureState && event is Trigger.FeatureState ->
            trigger.feature == event.feature && trigger.active == event.active

        trigger is Trigger.HeadphonesState && event is Trigger.HeadphonesState ->
            trigger.connected == event.connected

        trigger is Trigger.RingerMode && event is Trigger.RingerMode ->
            trigger.mode == event.mode

        trigger is Trigger.AppLaunch && event is Trigger.AppLaunch ->
            trigger.packageName == event.packageName

        trigger is Trigger.AppClose && event is Trigger.AppClose ->
            trigger.packageName == event.packageName

        trigger is Trigger.SensorPrivacyState && event is Trigger.SensorPrivacyState ->
            trigger.sensor == event.sensor && trigger.blocked == event.blocked

        trigger is Trigger.Location && event is Trigger.Location ->
            matchesLocation(trigger, event)

        else -> false
    }

    fun findMatchingRoutines(
        routines: List<Routine>,
        event: Trigger,
    ): List<Routine> = routines.filter { routine ->
        routine.enabled && routine.triggers.any { matchesEvent(it, event) }
    }

    private fun matchesBatteryLevel(trigger: Trigger.BatteryLevel, event: Trigger.BatteryLevel): Boolean {
        val eventLevel = event.threshold
        return when (trigger.direction) {
            Trigger.BatteryLevel.Direction.BELOW ->
                event.direction == Trigger.BatteryLevel.Direction.BELOW &&
                    eventLevel <= trigger.threshold
            Trigger.BatteryLevel.Direction.ABOVE ->
                event.direction == Trigger.BatteryLevel.Direction.ABOVE &&
                    eventLevel >= trigger.threshold
        }
    }

    private fun matchesWifiState(trigger: Trigger.WifiState, event: Trigger.WifiState): Boolean {
        if (trigger.connected != event.connected) return false
        if (trigger.ssid != null && trigger.ssid != event.ssid) return false
        return true
    }

    private fun matchesBluetoothState(
        trigger: Trigger.BluetoothState,
        event: Trigger.BluetoothState,
    ): Boolean {
        if (trigger.connected != event.connected) return false
        if (trigger.deviceAddress != null && trigger.deviceAddress != event.deviceAddress) return false
        return true
    }

    private fun matchesLocation(trigger: Trigger.Location, event: Trigger.Location): Boolean {
        if (trigger.entering != event.entering) return false
        val latMatch = Math.abs(trigger.latitude - event.latitude) < 0.0001
        val lngMatch = Math.abs(trigger.longitude - event.longitude) < 0.0001
        return latMatch && lngMatch
    }
}
