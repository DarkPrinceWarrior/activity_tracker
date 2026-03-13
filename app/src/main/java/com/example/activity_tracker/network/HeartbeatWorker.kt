package com.example.activity_tracker.network

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.activity_tracker.ActivityTrackerApp
import com.example.activity_tracker.crypto.DeviceCredentialsStore
import java.util.concurrent.TimeUnit

/**
 * Периодический WorkManager Worker для отправки heartbeat на сервер.
 * Запускается каждые 15 минут (минимум для WorkManager).
 *
 * Heartbeat передаёт:
 * - device_id, battery_level, is_collecting, pending_packets
 * - device_time_ms для синхронизации часов
 *
 * Сервер:
 * - Обновляет last_heartbeat_at
 * - Может вернуть commands (wipe, update и т.д.)
 */
class HeartbeatWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        Log.d(TAG, "HeartbeatWorker started")

        val credentialsStore = DeviceCredentialsStore(context.applicationContext)
        val authManager = AuthManager(credentialsStore)

        // Если устройство не зарегистрировано — нет смысла слать heartbeat
        if (!credentialsStore.isRegistered) {
            Log.w(TAG, "Device not registered, skipping heartbeat")
            return Result.success()
        }

        // Убеждаемся что есть токен
        val authResult = authManager.ensureAuthenticated()
        if (authResult.isFailure) {
            Log.w(TAG, "Auth failed, skipping heartbeat: ${authResult.exceptionOrNull()?.message}")
            return Result.retry()
        }

        val batteryLevel = getBatteryLevel()
        val isCollecting = isServiceRunning()
        val pendingPackets = getPendingPacketCount()

        val result = authManager.sendHeartbeat(
            batteryLevel = batteryLevel,
            isCollecting = isCollecting,
            pendingPackets = pendingPackets
        )

        return if (result.isSuccess) {
            val response = result.getOrNull()
            Log.d(TAG, "Heartbeat OK, time_offset=${response?.time_offset_ms}ms, commands=${response?.commands}")

            // TODO: Обработать commands от сервера (wipe, update, etc.)
            response?.commands?.forEach { cmd ->
                Log.d(TAG, "Server command: $cmd")
            }

            Result.success()
        } else {
            Log.e(TAG, "Heartbeat failed: ${result.exceptionOrNull()?.message}")
            Result.retry()
        }
    }

    private fun getBatteryLevel(): Float {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus: Intent? = context.registerReceiver(null, filter)
        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level >= 0 && scale > 0) level.toFloat() / scale.toFloat() else 0f
    }

    private fun isServiceRunning(): Boolean {
        // Проверяем, запущен ли CollectorService
        return try {
            val app = context.applicationContext as ActivityTrackerApp
            // Упрощённая проверка — по наличию pending packets
            true // В prod нужно проверять реальный флаг
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun getPendingPacketCount(): Int {
        return try {
            val app = context.applicationContext as ActivityTrackerApp
            app.samplesRepository.getPendingPackets().size
        } catch (e: Exception) {
            0
        }
    }

    companion object {
        private const val TAG = "HeartbeatWorker"
        private const val WORK_NAME = "heartbeat"

        /**
         * Запускает периодическую отправку heartbeat.
         * PeriodicWorkRequest: минимум 15 минут в WorkManager.
         */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<HeartbeatWorker>(
                15, TimeUnit.MINUTES // Минимум для WorkManager
            )
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )

            Log.d(TAG, "Heartbeat work scheduled (every 15 min)")
        }

        /**
         * Останавливает heartbeat.
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.d(TAG, "Heartbeat work cancelled")
        }
    }
}
