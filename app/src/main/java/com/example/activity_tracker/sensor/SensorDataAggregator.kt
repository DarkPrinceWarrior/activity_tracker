package com.example.activity_tracker.sensor

import android.util.Log
import com.example.activity_tracker.data.local.entity.BaroEntity
import com.example.activity_tracker.data.local.entity.MagEntity
import com.example.activity_tracker.data.local.entity.SensorSampleEntity
import com.example.activity_tracker.data.repository.SamplesRepository
import com.example.activity_tracker.sensor.model.SensorSample
import com.example.activity_tracker.sensor.model.SensorType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Агрегатор данных сенсоров: буферизует и сохраняет в БД пакетами
 */
class SensorDataAggregator(
    private val repository: SamplesRepository,
    private val bufferSize: Int = 100
) {
    private val sensorBuffer = mutableListOf<SensorSampleEntity>()
    private val baroBuffer = mutableListOf<BaroEntity>()
    private val magBuffer = mutableListOf<MagEntity>()
    private val mutex = Mutex()

    /**
     * Подписывается на Flow сенсоров и сохраняет данные
     */
    suspend fun collectAndStore(
        sensorFlow: Flow<SensorSample>
    ) {
        sensorFlow
            .buffer(capacity = 200)
            .catch { e ->
                if (e is CancellationException) throw e
                Log.e(TAG, "Error collecting sensor data", e)
            }
            .collect { sample ->
                bufferSample(sample)
            }
    }

    private suspend fun bufferSample(sample: SensorSample) {
        mutex.withLock {
            when (sample.type) {
                SensorType.ACCELEROMETER, SensorType.GYROSCOPE -> {
                    sensorBuffer.add(
                        SensorSampleEntity(
                            type = sample.type.typeName,
                            ts_ms = sample.timestamp,
                            x = sample.x,
                            y = sample.y,
                            z = sample.z,
                            quality = sample.quality
                        )
                    )
                    if (sensorBuffer.size >= bufferSize) {
                        flushSensorBuffer()
                    }
                }
                SensorType.BAROMETER -> {
                    baroBuffer.add(
                        BaroEntity(
                            ts_ms = sample.timestamp,
                            hpa = sample.x ?: 0f
                        )
                    )
                    if (baroBuffer.size >= bufferSize / 10) { // Барометр реже
                        flushBaroBuffer()
                    }
                }
                SensorType.MAGNETOMETER -> {
                    magBuffer.add(
                        MagEntity(
                            ts_ms = sample.timestamp,
                            x = sample.x ?: 0f,
                            y = sample.y ?: 0f,
                            z = sample.z ?: 0f
                        )
                    )
                    if (magBuffer.size >= bufferSize) {
                        flushMagBuffer()
                    }
                }
            }
        }
    }

    private suspend fun flushSensorBuffer() {
        if (sensorBuffer.isEmpty()) return
        val toSave = sensorBuffer.toList()
        sensorBuffer.clear()
        try {
            repository.saveSensor(toSave)
            Log.d(TAG, "Saved ${toSave.size} sensor samples")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving sensor samples", e)
            sensorBuffer.addAll(toSave) // Возврат в буфер при ошибке
        }
    }

    private suspend fun flushBaroBuffer() {
        if (baroBuffer.isEmpty()) return
        val toSave = baroBuffer.toList()
        baroBuffer.clear()
        try {
            repository.saveBaro(toSave)
            Log.d(TAG, "Saved ${toSave.size} barometer samples")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving barometer samples", e)
            baroBuffer.addAll(toSave)
        }
    }

    private suspend fun flushMagBuffer() {
        if (magBuffer.isEmpty()) return
        val toSave = magBuffer.toList()
        magBuffer.clear()
        try {
            repository.saveMag(toSave)
            Log.d(TAG, "Saved ${toSave.size} magnetometer samples")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving magnetometer samples", e)
            magBuffer.addAll(toSave)
        }
    }

    /**
     * Принудительный сброс всех буферов в БД
     */
    suspend fun flushAll() {
        mutex.withLock {
            flushSensorBuffer()
            flushBaroBuffer()
            flushMagBuffer()
        }
    }

    companion object {
        private const val TAG = "SensorDataAggregator"
    }
}
