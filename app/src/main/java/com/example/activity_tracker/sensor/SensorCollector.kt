package com.example.activity_tracker.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import com.example.activity_tracker.sensor.model.SensorProfile
import com.example.activity_tracker.sensor.model.SensorSample
import com.example.activity_tracker.sensor.model.SensorType
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Сборщик данных с сенсоров (акселерометр, гироскоп, барометр, магнитометр)
 * Согласно секции 23 плана
 */
class SensorCollector(context: Context) {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private var currentProfile: SensorProfile = SensorProfile.NORMAL

    private val accelerometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val barometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)
    private val magnetometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    init {
        logAvailableSensors()
    }

    /**
     * Устанавливает профиль сбора (NORMAL, ECO, AGGRESSIVE)
     */
    fun setProfile(profile: SensorProfile) {
        currentProfile = profile
        Log.d(TAG, "Sensor profile changed to: ${profile.name}")
    }

    /**
     * Создает Flow для сбора данных с акселерометра
     */
    fun collectAccelerometer(): Flow<SensorSample> = callbackFlow {
        val sensor = accelerometer
        if (sensor == null) {
            Log.e(TAG, "Accelerometer not available")
            close()
            return@callbackFlow
        }

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val sample = SensorSample(
                    type = SensorType.ACCELEROMETER,
                    timestamp = System.currentTimeMillis(),
                    x = event.values[0],
                    y = event.values[1],
                    z = event.values[2],
                    quality = if (event.accuracy >= SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM) 1f else 0.5f
                )
                trySend(sample)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                Log.d(TAG, "Accelerometer accuracy changed: $accuracy")
            }
        }

        val registered = sensorManager.registerListener(
            listener,
            sensor,
            currentProfile.samplingPeriodUs
        )

        if (!registered) {
            Log.e(TAG, "Failed to register accelerometer listener")
            close()
            return@callbackFlow
        }

        Log.d(TAG, "Accelerometer started with profile: ${currentProfile.name}")

        awaitClose {
            sensorManager.unregisterListener(listener)
            Log.d(TAG, "Accelerometer stopped")
        }
    }

    /**
     * Создает Flow для сбора данных с гироскопа
     */
    fun collectGyroscope(): Flow<SensorSample> = callbackFlow {
        val sensor = gyroscope
        if (sensor == null) {
            Log.e(TAG, "Gyroscope not available")
            close()
            return@callbackFlow
        }

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val sample = SensorSample(
                    type = SensorType.GYROSCOPE,
                    timestamp = System.currentTimeMillis(),
                    x = event.values[0],
                    y = event.values[1],
                    z = event.values[2],
                    quality = if (event.accuracy >= SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM) 1f else 0.5f
                )
                trySend(sample)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                Log.d(TAG, "Gyroscope accuracy changed: $accuracy")
            }
        }

        val registered = sensorManager.registerListener(
            listener,
            sensor,
            currentProfile.samplingPeriodUs
        )

        if (!registered) {
            Log.e(TAG, "Failed to register gyroscope listener")
            close()
            return@callbackFlow
        }

        Log.d(TAG, "Gyroscope started with profile: ${currentProfile.name}")

        awaitClose {
            sensorManager.unregisterListener(listener)
            Log.d(TAG, "Gyroscope stopped")
        }
    }

    /**
     * Создает Flow для сбора данных с барометра
     */
    fun collectBarometer(): Flow<SensorSample> = callbackFlow {
        val sensor = barometer
        if (sensor == null) {
            Log.w(TAG, "Barometer not available (optional sensor)")
            close()
            return@callbackFlow
        }

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val sample = SensorSample(
                    type = SensorType.BAROMETER,
                    timestamp = System.currentTimeMillis(),
                    x = event.values[0], // hPa
                    quality = 1f
                )
                trySend(sample)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        val registered = sensorManager.registerListener(
            listener,
            sensor,
            1_000_000 // 1 Гц для барометра
        )

        if (!registered) {
            Log.e(TAG, "Failed to register barometer listener")
            close()
            return@callbackFlow
        }

        Log.d(TAG, "Barometer started")

        awaitClose {
            sensorManager.unregisterListener(listener)
            Log.d(TAG, "Barometer stopped")
        }
    }

    /**
     * Создает Flow для сбора данных с магнитометра
     */
    fun collectMagnetometer(): Flow<SensorSample> = callbackFlow {
        val sensor = magnetometer
        if (sensor == null) {
            Log.w(TAG, "Magnetometer not available (optional sensor)")
            close()
            return@callbackFlow
        }

        // В ECO режиме магнитометр отключен
        if (currentProfile == SensorProfile.ECO) {
            Log.d(TAG, "Magnetometer disabled in ECO mode")
            close()
            return@callbackFlow
        }

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val sample = SensorSample(
                    type = SensorType.MAGNETOMETER,
                    timestamp = System.currentTimeMillis(),
                    x = event.values[0],
                    y = event.values[1],
                    z = event.values[2],
                    quality = if (event.accuracy >= SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM) 1f else 0.5f
                )
                trySend(sample)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                Log.d(TAG, "Magnetometer accuracy changed: $accuracy")
            }
        }

        val registered = sensorManager.registerListener(
            listener,
            sensor,
            100_000 // 10 Гц для магнитометра
        )

        if (!registered) {
            Log.e(TAG, "Failed to register magnetometer listener")
            close()
            return@callbackFlow
        }

        Log.d(TAG, "Magnetometer started")

        awaitClose {
            sensorManager.unregisterListener(listener)
            Log.d(TAG, "Magnetometer stopped")
        }
    }

    private fun logAvailableSensors() {
        Log.d(TAG, "=== Available Sensors ===")
        Log.d(TAG, "Accelerometer: ${accelerometer != null}")
        Log.d(TAG, "Gyroscope: ${gyroscope != null}")
        Log.d(TAG, "Barometer: ${barometer != null}")
        Log.d(TAG, "Magnetometer: ${magnetometer != null}")
    }

    companion object {
        private const val TAG = "SensorCollector"
    }
}
