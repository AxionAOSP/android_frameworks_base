/*
 * Copyright (C) 2026 AxionOS
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
package com.android.systemui.mistouch.data.repository

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.mistouch.shared.model.MistouchSensorState
import javax.inject.Inject

private const val POCKET_SENSOR = Sensor.TYPE_PROXIMITY
private const val SENSOR_DELAY = SensorManager.SENSOR_DELAY_NORMAL
private const val GRAVITY_THRESHOLD = -0.7f
private const val MIN_INCLINATION = 75
private const val MAX_INCLINATION = 100

@SysUISingleton
class MistouchSensorRepository @Inject constructor(
    context: Context,
) {
    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val proximitySensor = sensorManager?.getDefaultSensor(POCKET_SENSOR, true)
    private val accelerometerSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private var onStateChanged: ((MistouchSensorState) -> Unit)? = null
    private var state = MistouchSensorState()

    val hasSensors: Boolean
        get() = proximitySensor != null || accelerometerSensor != null

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent?) {
            event ?: return

            if (event.sensor.type == POCKET_SENSOR) {
                updateState(state.copy(proximityNear = event.values[0] < 1.0f))
            } else if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
                updateAccelerometerState(event.values[0], event.values[1], event.values[2])
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    fun startListening(onStateChanged: (MistouchSensorState) -> Unit): Boolean {
        if (!hasSensors) return false
        if (this.onStateChanged != null) return true

        this.onStateChanged = onStateChanged
        proximitySensor?.let { sensorManager?.registerListener(sensorListener, it, SENSOR_DELAY) }
        accelerometerSensor?.let {
            sensorManager?.registerListener(sensorListener, it, SENSOR_DELAY)
        }
        return true
    }

    fun stopListening() {
        if (!hasSensors) return

        onStateChanged = null
        state = MistouchSensorState()
        sensorManager?.unregisterListener(sensorListener)
    }

    private fun updateAccelerometerState(x: Float, y: Float, z: Float) {
        val magnitude = Math.sqrt((x * x + y * y + z * z).toDouble())

        if (magnitude == 0.0) return

        val normalizedY = y / magnitude
        val normalizedZ = z / magnitude
        val inclination = Math.round(Math.toDegrees(Math.acos(normalizedZ))).toInt()
        val isGravityInPocket = normalizedY < GRAVITY_THRESHOLD
        val isInclinationInPocket = inclination in MIN_INCLINATION..MAX_INCLINATION

        updateState(
            state.copy(
                accelerometerInPocket = isGravityInPocket && isInclinationInPocket
            )
        )
    }

    private fun updateState(state: MistouchSensorState) {
        this.state = state
        onStateChanged?.invoke(state)
    }
}
