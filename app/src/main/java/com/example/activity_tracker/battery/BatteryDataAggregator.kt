package com.example.activity_tracker.battery

import android.util.Log
import com.example.activity_tracker.battery.model.BatterySample
import com.example.activity_tracker.data.local.entity.BatteryEntity
import com.example.activity_tracker.data.repository.SamplesRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch

/**
 * Агрегатор событий батареи для записи в БД
 * События батареи редкие, поэтому сохраняем сразу без буферизации
 */
class BatteryDataAggregator(
    private val repository: SamplesRepository
) {
    /**
     * Подписывается на Flow батареи и сохраняет в БД
     */
    suspend fun collectAndStore(
        batteryFlow: Flow<BatterySample>
    ) {
        batteryFlow
            .catch { e ->
                if (e is CancellationException) throw e
                Log.e(TAG, "Error collecting battery data", e)
            }
            .collect { sample ->
                saveBatterySample(sample)
            }
    }

    private suspend fun saveBatterySample(sample: BatterySample) {
        try {
            val entity = BatteryEntity(
                ts_ms = sample.timestamp,
                level = sample.level
            )
            repository.saveBattery(listOf(entity))
            Log.d(TAG, "Saved battery event: ${(sample.level * 100).toInt()}%")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving battery event", e)
        }
    }

    companion object {
        private const val TAG = "BatteryDataAggregator"
    }
}
