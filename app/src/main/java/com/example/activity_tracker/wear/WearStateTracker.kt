package com.example.activity_tracker.wear

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import com.example.activity_tracker.wear.model.WearState
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Отслеживание состояния ношения часов (on-wrist/off-wrist)
 * Использует датчик LOW_LATENCY_OFFBODY_DETECT для определения контакта с кожей
 * Согласно секции 4 и 23 плана
 */
class WearStateTracker(context: Context) {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    // Пробуем оба типа датчиков для совместимости
    private val offBodySensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT)
        ?: sensorManager.getDefaultSensor(34) // TYPE_LOW_LATENCY_OFFBODY_DETECT = 34

    init {
        logSensorAvailability()
    }

    /**
     * Создает Flow для отслеживания состояния ношения часов
     */
    fun trackWearState(): Flow<WearState> = callbackFlow {
        val sensor = offBodySensor

        if (sensor == null) {
            Log.w(TAG, "Off-body sensor not available, using fallback method")
            // Отправляем начальное состояние ON_WRIST как предположение
            trySend(WearState(System.currentTimeMillis(), WearState.State.ON_WRIST))
            close()
            return@callbackFlow
        }

        val listener = object : SensorEventListener {
            private var lastState: WearState.State? = null

            override fun onSensorChanged(event: SensorEvent) {
                // Значение 0 = off body (сняты), 1 = on body (надеты)
                val isOnBody = event.values[0] == 1f
                val newState = if (isOnBody) {
                    WearState.State.ON_WRIST
                } else {
                    WearState.State.OFF_WRIST
                }

                // Отправляем только при изменении состояния
                if (newState != lastState) {
                    lastState = newState
                    val wearState = WearState(
                        timestamp = System.currentTimeMillis(),
                        state = newState
                    )
                    trySend(wearState)
                    Log.d(TAG, "Wear state changed: $newState")
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                Log.d(TAG, "Off-body sensor accuracy changed: $accuracy")
            }
        }

        val registered = sensorManager.registerListener(
            listener,
            sensor,
            SensorManager.SENSOR_DELAY_NORMAL
        )

        if (!registered) {
            Log.e(TAG, "Failed to register off-body sensor listener")
            close()
            return@callbackFlow
        }

        Log.d(TAG, "Wear state tracking started")

        // Отправляем начальное состояние (предполагаем ON_WRIST)
        trySend(WearState(System.currentTimeMillis(), WearState.State.ON_WRIST))

        awaitClose {
            sensorManager.unregisterListener(listener)
            Log.d(TAG, "Wear state tracking stopped")
        }
    }

    /**
     * Проверяет доступность датчика контакта с телом
     */
    fun isSensorAvailable(): Boolean = offBodySensor != null

    private fun logSensorAvailability() {
        Log.d(TAG, "=== Wear State Sensor ===")
        Log.d(TAG, "Off-body sensor available: ${offBodySensor != null}")
        if (offBodySensor != null) {
            Log.d(TAG, "Sensor name: ${offBodySensor.name}")
            Log.d(TAG, "Sensor vendor: ${offBodySensor.vendor}")
        }
    }

    companion object {
        private const val TAG = "WearStateTracker"
    }
}
