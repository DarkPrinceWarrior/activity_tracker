package com.example.activity_tracker.wear

import android.util.Log
import com.example.activity_tracker.data.local.entity.WearEventEntity
import com.example.activity_tracker.data.repository.SamplesRepository
import com.example.activity_tracker.wear.model.WearState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch

/**
 * Агрегатор событий ношения часов для записи в БД
 */
class WearDataAggregator(
    private val repository: SamplesRepository
) {
    /**
     * Подписывается на Flow событий ношения и сохраняет в БД
     */
    suspend fun collectAndStore(
        wearStateFlow: Flow<WearState>
    ) {
        wearStateFlow
            .catch { e ->
                if (e is CancellationException) throw e
                Log.e(TAG, "Error collecting wear state", e)
            }
            .collect { wearState ->
                saveWearEvent(wearState)
            }
    }

    private suspend fun saveWearEvent(wearState: WearState) {
        try {
            val entity = WearEventEntity(
                ts_ms = wearState.timestamp,
                state = wearState.state.value
            )
            repository.saveWear(listOf(entity))
            Log.d(TAG, "Saved wear event: ${wearState.state}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error saving wear event", e)
        }
    }

    companion object {
        private const val TAG = "WearDataAggregator"
    }
}
