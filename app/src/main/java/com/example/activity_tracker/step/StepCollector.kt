package com.example.activity_tracker.step

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import com.example.activity_tracker.step.model.StepReading
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

class StepCollector(context: Context) {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val stepCounterSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
    private val stepDetectorSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)

    init {
        Log.d(TAG, "Step counter available: ${stepCounterSensor != null}")
        Log.d(TAG, "Step detector available: ${stepDetectorSensor != null}")
    }

    fun collectSteps(): Flow<StepReading> = callbackFlow {
        val sensor = stepCounterSensor ?: stepDetectorSensor
        if (sensor == null) {
            Log.w(TAG, "No step sensor available")
            close()
            return@callbackFlow
        }

        var detectorCount = 0L

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val totalSteps = when (event.sensor.type) {
                    Sensor.TYPE_STEP_COUNTER -> event.values.firstOrNull()?.toLong() ?: return
                    Sensor.TYPE_STEP_DETECTOR -> {
                        detectorCount += event.values.size.toLong()
                        detectorCount
                    }
                    else -> return
                }

                trySend(
                    StepReading(
                        timestamp = System.currentTimeMillis(),
                        totalSteps = totalSteps
                    )
                )
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        val registered = sensorManager.registerListener(
            listener,
            sensor,
            SensorManager.SENSOR_DELAY_NORMAL
        )

        if (!registered) {
            Log.e(TAG, "Failed to register step listener")
            close()
            return@callbackFlow
        }

        awaitClose {
            sensorManager.unregisterListener(listener)
            Log.d(TAG, "Step collection stopped")
        }
    }

    companion object {
        private const val TAG = "StepCollector"
    }
}
