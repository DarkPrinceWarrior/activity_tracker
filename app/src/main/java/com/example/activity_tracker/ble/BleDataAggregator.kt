package com.example.activity_tracker.ble

import android.util.Log
import com.example.activity_tracker.ble.model.BleBeacon
import com.example.activity_tracker.data.local.entity.BleEventEntity
import com.example.activity_tracker.data.repository.SamplesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Агрегатор BLE-событий для буферизации и записи в БД
 */
class BleDataAggregator(
    private val repository: SamplesRepository,
    private val bufferSize: Int = 50
) {
    private val bleBuffer = mutableListOf<BleEventEntity>()
    private val mutex = Mutex()

    /**
     * Подписывается на Flow BLE-событий и сохраняет в БД пакетами
     */
    fun collectAndStore(
        bleFlow: Flow<BleBeacon>,
        scope: CoroutineScope
    ) {
        scope.launch {
            bleFlow
                .buffer(capacity = 100)
                .catch { e ->
                    Log.e(TAG, "Error collecting BLE beacons", e)
                }
                .collect { beacon ->
                    bufferBeacon(beacon)
                }
        }
    }

    private suspend fun bufferBeacon(beacon: BleBeacon) {
        mutex.withLock {
            bleBuffer.add(
                BleEventEntity(
                    ts_ms = beacon.timestamp,
                    beacon_id = beacon.beaconId,
                    rssi = beacon.rssi
                )
            )

            if (bleBuffer.size >= bufferSize) {
                flushBuffer()
            }
        }
    }

    private suspend fun flushBuffer() {
        if (bleBuffer.isEmpty()) return
        val toSave = bleBuffer.toList()
        bleBuffer.clear()
        try {
            repository.saveBle(toSave)
            Log.d(TAG, "Saved ${toSave.size} BLE events")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving BLE events", e)
            bleBuffer.addAll(toSave) // Возврат в буфер при ошибке
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
        private const val TAG = "BleDataAggregator"
    }
}
