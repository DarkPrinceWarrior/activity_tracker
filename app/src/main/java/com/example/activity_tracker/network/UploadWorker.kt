package com.example.activity_tracker.network

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.activity_tracker.ActivityTrackerApp
import com.example.activity_tracker.packet.PacketPipeline

/**
 * WorkManager Worker для фоновой отправки пакетов из очереди.
 * Запускается при наличии сети. Обрабатывает все pending-пакеты.
 * Согласно секции 10 и 15 плана (WorkManager для гарантированной доставки).
 */
class UploadWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        Log.d(TAG, "UploadWorker started")

        val repository = (context.applicationContext as ActivityTrackerApp).samplesRepository
        val uploader = NetworkUploader(repository)

        val pendingPackets = repository.getPendingPackets()
        Log.d(TAG, "Found ${pendingPackets.size} pending packets")

        if (pendingPackets.isEmpty()) {
            return Result.success()
        }

        var anyFailed = false

        for (packet in pendingPackets) {
            Log.d(TAG, "Processing packet: ${packet.packet_id} (attempt=${packet.attempt})")

            val result = uploader.upload(packet)

            when (result) {
                is NetworkUploader.UploadResult.Success -> {
                    Log.d(TAG, "Packet uploaded: ${packet.packet_id}")
                }
                is NetworkUploader.UploadResult.Failure -> {
                    Log.e(TAG, "Packet failed: ${packet.packet_id} — ${result.error}")
                    if (result.shouldRetry) {
                        anyFailed = true
                    }
                }
            }
        }

        return if (anyFailed) {
            Log.d(TAG, "Some packets failed, scheduling retry")
            Result.retry()
        } else {
            Log.d(TAG, "All packets processed")
            Result.success()
        }
    }

    companion object {
        private const val TAG = "UploadWorker"
        private const val WORK_NAME = "upload_packets"

        /**
         * Планирует разовую фоновую отправку пакетов при наличии сети.
         * Идемпотентен: повторный вызов заменяет предыдущую задачу.
         */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<UploadWorker>()
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )

            Log.d(TAG, "Upload work scheduled")
        }
    }
}
