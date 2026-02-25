package com.example.activity_tracker.network

import android.util.Log
import com.example.activity_tracker.data.local.entity.PacketQueueEntity
import com.example.activity_tracker.data.repository.SamplesRepository
import com.example.activity_tracker.network.model.UploadRequest
import com.example.activity_tracker.packet.PacketPipeline
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.io.File

/**
 * Отвечает за отправку одного пакета на сервер согласно секции 9.3 и 10 плана.
 * Реализует идемпотентность (Idempotency-Key = packet_id) и экспоненциальный backoff.
 */
class NetworkUploader(
    private val repository: SamplesRepository,
    private val apiService: WatchApiService = NetworkClient.watchApiService
) {

    /**
     * Результат попытки отправки
     */
    sealed class UploadResult {
        object Success : UploadResult()
        data class Failure(val error: String, val shouldRetry: Boolean) : UploadResult()
    }

    /**
     * Отправляет пакет на сервер.
     * Обновляет статус в packet_queue.
     * @return UploadResult
     */
    suspend fun upload(packet: PacketQueueEntity): UploadResult {
        Log.d(TAG, "Uploading packet: ${packet.packet_id}, attempt=${packet.attempt + 1}")

        // Обновляем статус → uploading
        repository.updatePacketStatus(
            packetId = packet.packet_id,
            status = PacketPipeline.STATUS_UPLOADING,
            attempt = packet.attempt + 1,
            error = null
        )

        return try {
            val payloadJson = readPayload(packet.payload_path)
                ?: return handleError(packet, "Payload file not found: ${packet.payload_path}", false)

            val request = buildRequest(packet, payloadJson)
            val deviceId = extractField(payloadJson, "payload_enc").take(8).ifEmpty { "unknown" }
            Log.d(TAG, "Sending packet to server (device=$deviceId)")

            // TODO: Заменить mock на реальный вызов после готовности сервера
            val result = mockUpload(request, packet)

            when {
                result.isSuccessful || result.code() == 409 -> {
                    Log.d(TAG, "Packet uploaded successfully: ${packet.packet_id}")
                    repository.updatePacketStatus(
                        packetId = packet.packet_id,
                        status = PacketPipeline.STATUS_UPLOADED,
                        attempt = packet.attempt + 1,
                        error = null
                    )
                    UploadResult.Success
                }
                result.code() in listOf(400, 422) -> {
                    val err = "Server validation error: ${result.code()}"
                    handleError(packet, err, shouldRetry = false)
                }
                result.code() in listOf(401, 403) -> {
                    val err = "Auth error: ${result.code()} — manual re-auth required"
                    handleError(packet, err, shouldRetry = false)
                }
                else -> {
                    val err = "Server error: ${result.code()}"
                    handleError(packet, err, shouldRetry = true)
                }
            }
        } catch (e: Exception) {
            val err = "Network error: ${e.message}"
            Log.e(TAG, err, e)
            handleError(packet, err, shouldRetry = true)
        }
    }

    /**
     * Mock-реализация отправки.
     * TODO: Заменить на реальный вызов apiService.uploadPacket() после готовности сервера:
     *
     * return apiService.uploadPacket(
     *     authorization = "Bearer $accessToken",
     *     idempotencyKey = packet.packet_id,
     *     request = request
     * )
     */
    private suspend fun mockUpload(
        request: UploadRequest,
        packet: PacketQueueEntity
    ): retrofit2.Response<com.example.activity_tracker.network.model.UploadResponse> {
        Log.d(TAG, "MOCK: Would send POST ${NetworkClient.BASE_URL}api/v1/watch/packets")
        Log.d(TAG, "MOCK: packet_id=${request.packet_id}")
        Log.d(TAG, "MOCK: shift=[${request.shift_start_ts}, ${request.shift_end_ts}]")
        Log.d(TAG, "MOCK: payload_hash=${request.payload_hash}")
        Log.d(TAG, "MOCK: Simulating 202 Accepted")

        // Симулируем небольшую задержку сети
        delay(500)

        val mockBody = com.example.activity_tracker.network.model.UploadResponse(
            packet_id = packet.packet_id,
            status = "accepted",
            server_time = System.currentTimeMillis().toString()
        )
        return retrofit2.Response.success(202, mockBody)
    }

    private fun buildRequest(
        packet: PacketQueueEntity,
        payloadJson: String
    ): UploadRequest {
        return UploadRequest(
            packet_id = packet.packet_id,
            device_id = extractField(payloadJson, "device_id").ifEmpty { "unknown" },
            shift_start_ts = packet.shift_start_ts_ms,
            shift_end_ts = packet.shift_end_ts_ms,
            payload_enc = extractField(payloadJson, "payload_enc"),
            payload_key_enc = extractField(payloadJson, "payload_key_enc"),
            iv = extractField(payloadJson, "iv"),
            payload_hash = extractField(payloadJson, "payload_hash")
        )
    }

    private fun readPayload(path: String): String? = try {
        File(path).readText(Charsets.UTF_8)
    } catch (e: Exception) {
        Log.e(TAG, "Failed to read payload from $path", e)
        null
    }

    private fun extractField(json: String, field: String): String = try {
        JSONObject(json).optString(field, "")
    } catch (e: Exception) {
        ""
    }

    private suspend fun handleError(
        packet: PacketQueueEntity,
        error: String,
        shouldRetry: Boolean
    ): UploadResult.Failure {
        Log.e(TAG, "Upload failed: $error (retry=$shouldRetry)")
        repository.updatePacketStatus(
            packetId = packet.packet_id,
            status = PacketPipeline.STATUS_ERROR,
            attempt = packet.attempt + 1,
            error = error
        )
        return UploadResult.Failure(error, shouldRetry)
    }

    companion object {
        private const val TAG = "NetworkUploader"

        /**
         * Экспоненциальный backoff согласно секции 10 плана: 1, 2, 5, 10, 30, 60 мин
         */
        val BACKOFF_DELAYS_MS = listOf(
            60_000L,      // 1 мин
            120_000L,     // 2 мин
            300_000L,     // 5 мин
            600_000L,     // 10 мин
            1_800_000L,   // 30 мин
            3_600_000L    // 60 мин
        )

        fun backoffDelay(attempt: Int): Long =
            BACKOFF_DELAYS_MS.getOrElse(attempt) { BACKOFF_DELAYS_MS.last() }
    }
}
