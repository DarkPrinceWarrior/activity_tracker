package com.example.activity_tracker.heartrate

import android.util.Log
import com.example.activity_tracker.data.local.entity.HeartRateEntity
import com.example.activity_tracker.data.repository.SamplesRepository
import com.example.activity_tracker.heartrate.model.HeartRateSample
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Агрегатор данных пульса для буферизации и записи в БД
 */
class HeartRateDataAggregator(
    private val repository: SamplesRepository,
    private val bufferSize: Int = 20 // Буфер меньше, т.к. пульс редкий (0.2-1 Гц)
) {
    private val hrBuffer = mutableListOf<HeartRateEntity>()
    private val mutex = Mutex()

    /**
     * Подписывается на Flow пульса и сохраняет в БД пакетами
     */
    suspend fun collectAndStore(
        heartRateFlow: Flow<HeartRateSample>
    ) {
        heartRateFlow
            .buffer(capacity = 50)
            .catch { e ->
                if (e is CancellationException) throw e
                Log.e(TAG, "Error collecting heart rate", e)
            }
            .collect { sample ->
                bufferSample(sample)
            }
    }

    private suspend fun bufferSample(sample: HeartRateSample) {
        mutex.withLock {
            hrBuffer.add(
                HeartRateEntity(
                    ts_ms = sample.timestamp,
                    bpm = sample.bpm,
                    confidence = sample.confidence
                )
            )

            if (hrBuffer.size >= bufferSize) {
                flushBuffer()
            }
        }
    }

    private suspend fun flushBuffer() {
        if (hrBuffer.isEmpty()) return
        val toSave = hrBuffer.toList()
        hrBuffer.clear()
        try {
            repository.saveHeartRate(toSave)
            Log.d(TAG, "Saved ${toSave.size} heart rate samples")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error saving heart rate samples", e)
            hrBuffer.addAll(toSave) // Возврат в буфер при ошибке
        }
    }

    /**
     * Принудительный сброс буфера в БД
     */
    suspend fun flushAll() {
        mutex.withLock {
            flushBuffer()
        }
    }

    companion object {
        private const val TAG = "HeartRateDataAggregator"
    }
}
