package com.example.activity_tracker.step

import android.util.Log
import com.example.activity_tracker.data.local.entity.StepCountEntity
import com.example.activity_tracker.data.repository.SamplesRepository
import com.example.activity_tracker.step.model.StepReading
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class StepDataAggregator(
    private val repository: SamplesRepository,
    private val bufferSize: Int = 20
) {
    private val stepBuffer = mutableListOf<StepCountEntity>()
    private val mutex = Mutex()

    suspend fun collectAndStore(stepFlow: Flow<StepReading>) {
        stepFlow
            .buffer(capacity = 50)
            .catch { e ->
                if (e is CancellationException) throw e
                Log.e(TAG, "Error collecting steps", e)
            }
            .collect { reading ->
                bufferSample(reading)
            }
    }

    private suspend fun bufferSample(reading: StepReading) {
        mutex.withLock {
            stepBuffer.add(
                StepCountEntity(
                    ts_ms = reading.timestamp,
                    total_steps = reading.totalSteps
                )
            )

            if (stepBuffer.size >= bufferSize) {
                flushBuffer()
            }
        }
    }

    private suspend fun flushBuffer() {
        if (stepBuffer.isEmpty()) return
        val toSave = stepBuffer.toList()
        stepBuffer.clear()
        try {
            repository.saveSteps(toSave)
            Log.d(TAG, "Saved ${toSave.size} step samples")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error saving step samples", e)
            stepBuffer.addAll(toSave)
        }
    }

    suspend fun flushAll() {
        mutex.withLock {
            flushBuffer()
        }
    }

    companion object {
        private const val TAG = "StepDataAggregator"
    }
}
