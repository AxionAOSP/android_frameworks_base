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

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.util.Log
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.routines.model.Trigger
import javax.inject.Inject

@SysUISingleton
class LocationTriggerMonitor @Inject constructor(
    @Application private val context: Context,
    private val locationManager: LocationManager,
) {

    private var callback: ((trigger: Trigger) -> Unit)? = null
    private var started = false
    private val registeredGeofences = mutableMapOf<String, PendingIntent>()

    private val geofenceReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != ACTION_GEOFENCE_TRIGGER) return
            val entering = intent.getBooleanExtra(LocationManager.KEY_PROXIMITY_ENTERING, false)
            val lat = intent.getDoubleExtra(EXTRA_LATITUDE, 0.0)
            val lng = intent.getDoubleExtra(EXTRA_LONGITUDE, 0.0)
            val radius = intent.getFloatExtra(EXTRA_RADIUS, 0f)
            Log.d(TAG, "Geofence triggered: entering=$entering, lat=$lat, lng=$lng")
            callback?.invoke(Trigger.Location(lat, lng, radius, entering))
        }
    }

    fun setCallback(cb: (trigger: Trigger) -> Unit) {
        callback = cb
    }

    fun start() {
        if (started) return
        started = true
        context.registerReceiver(
            geofenceReceiver,
            IntentFilter(ACTION_GEOFENCE_TRIGGER),
            Context.RECEIVER_NOT_EXPORTED,
        )
        Log.d(TAG, "Location trigger monitor started")
    }

    fun stop() {
        if (!started) return
        started = false
        removeAllGeofences()
        context.unregisterReceiver(geofenceReceiver)
        Log.d(TAG, "Location trigger monitor stopped")
    }

    fun registerGeofence(
        routineId: String,
        triggerIndex: Int,
        trigger: Trigger.Location,
    ) {
        val key = "$routineId:$triggerIndex"
        removeGeofence(key)

        val intent = Intent(ACTION_GEOFENCE_TRIGGER).apply {
            setPackage(context.packageName)
            putExtra(EXTRA_LATITUDE, trigger.latitude)
            putExtra(EXTRA_LONGITUDE, trigger.longitude)
            putExtra(EXTRA_RADIUS, trigger.radiusMeters)
        }
        val pi = PendingIntent.getBroadcast(
            context,
            key.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )

        locationManager.addProximityAlert(
            trigger.latitude,
            trigger.longitude,
            trigger.radiusMeters,
            GEOFENCE_EXPIRATION_NONE,
            pi,
        )
        registeredGeofences[key] = pi
        Log.d(TAG, "Registered geofence: key=$key, lat=${trigger.latitude}, lng=${trigger.longitude}, radius=${trigger.radiusMeters}m")
    }

    fun removeRoutineGeofences(routineId: String) {
        val keysToRemove = registeredGeofences.keys.filter { it.startsWith("$routineId:") }
        keysToRemove.forEach { removeGeofence(it) }
    }

    fun removeAllGeofences() {
        registeredGeofences.keys.toList().forEach { removeGeofence(it) }
    }

    private fun removeGeofence(key: String) {
        registeredGeofences.remove(key)?.let { pi ->
            locationManager.removeProximityAlert(pi)
            pi.cancel()
        }
    }

    companion object {
        private const val TAG = "RoutinesLocationTrigger"
        private const val ACTION_GEOFENCE_TRIGGER =
            "com.android.systemui.routines.ACTION_GEOFENCE_TRIGGER"
        private const val EXTRA_LATITUDE = "latitude"
        private const val EXTRA_LONGITUDE = "longitude"
        private const val EXTRA_RADIUS = "radius"
        private const val GEOFENCE_EXPIRATION_NONE = -1L
    }
}
