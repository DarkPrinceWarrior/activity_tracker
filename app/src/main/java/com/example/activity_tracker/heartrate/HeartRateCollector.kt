package com.example.activity_tracker.heartrate

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import com.example.activity_tracker.heartrate.model.HeartRateSample
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Сборщик данных пульса (Heart Rate)
 * Частота сбора: 0.2-1 Гц (каждые 1-5 секунд) согласно секции 19 плана
 */
class HeartRateCollector(context: Context) {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val heartRateSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE)

    init {
        logSensorAvailability()
    }

    /**
     * Создает Flow для сбора данных пульса
     */
    fun collectHeartRate(): Flow<HeartRateSample> = callbackFlow {
        val sensor = heartRateSensor
        if (sensor == null) {
            Log.w(TAG, "Heart rate sensor not available (optional sensor)")
            close()
            return@callbackFlow
        }

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val bpm = event.values[0].toInt()

                // Фильтруем нереалистичные значения
                if (bpm <= 0 || bpm > 250) {
                    Log.w(TAG, "Invalid heart rate value: $bpm")
                    return
                }

                // Определяем уверенность на основе точности сенсора
                val confidence = when (event.accuracy) {
                    SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> 1.0f
                    SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> 0.85f
                    SensorManager.SENSOR_STATUS_ACCURACY_LOW -> 0.6f
                    else -> 0.3f
                }

                val sample = HeartRateSample(
                    timestamp = System.currentTimeMillis(),
                    bpm = bpm,
                    confidence = confidence
                )

                trySend(sample)
                Log.d(TAG, "Heart rate: $bpm bpm (confidence: $confidence)")
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                val accuracyStr = when (accuracy) {
                    SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> "HIGH"
                    SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> "MEDIUM"
                    SensorManager.SENSOR_STATUS_ACCURACY_LOW -> "LOW"
                    else -> "UNRELIABLE"
                }
                Log.d(TAG, "Heart rate sensor accuracy changed: $accuracyStr")
            }
        }

        // Используем SENSOR_DELAY_NORMAL для ~1 Гц частоты
        // Это соответствует рекомендации 0.2-1 Гц из плана
        val registered = sensorManager.registerListener(
            listener,
            sensor,
            SensorManager.SENSOR_DELAY_NORMAL
        )

        if (!registered) {
            Log.e(TAG, "Failed to register heart rate listener")
            close()
            return@callbackFlow
        }

        Log.d(TAG, "Heart rate collection started")

        awaitClose {
            sensorManager.unregisterListener(listener)
            Log.d(TAG, "Heart rate collection stopped")
        }
    }

    /**
     * Проверяет доступность датчика пульса
     */
    fun isSensorAvailable(): Boolean = heartRateSensor != null

    private fun logSensorAvailability() {
        Log.d(TAG, "=== Heart Rate Sensor ===")
        Log.d(TAG, "Heart rate sensor available: ${heartRateSensor != null}")
        if (heartRateSensor != null) {
            Log.d(TAG, "Sensor name: ${heartRateSensor.name}")
            Log.d(TAG, "Sensor vendor: ${heartRateSensor.vendor}")
            Log.d(TAG, "Max range: ${heartRateSensor.maximumRange}")
        }
    }

    companion object {
        private const val TAG = "HeartRateCollector"
    }
}
