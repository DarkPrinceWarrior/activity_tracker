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
 * Отвечает за отправку одного пакета на реальный сервер.
 * Реализует:
 * - Аутентификацию через AuthManager (Bearer token)
 * - Идемпотентность (Idempotency-Key = packet_id)
 * - Экспоненциальный backoff при ошибках
 * - Auto-refresh токена при 401
 */
class NetworkUploader(
    private val repository: SamplesRepository,
    private val authManager: AuthManager,
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

            // Получаем access token (с auto-refresh если нужно)
            val accessToken = try {
                authManager.getAccessToken()
            } catch (e: AuthManager.AuthException) {
                return handleError(packet, "Auth error: ${e.message}", shouldRetry = true)
            }

            val deviceId = authManager.getDeviceIdOrThrow()

            Log.d(TAG, "Sending packet to server: ${packet.packet_id}")

            // Реальный вызов API
            val result = apiService.uploadPacket(
                authorization = "Bearer $accessToken",
                idempotencyKey = packet.packet_id,
                deviceId = deviceId,
                request = request
            )

            when {
                result.isSuccessful || result.code() == 409 -> {
                    Log.d(TAG, "Packet accepted by server: ${packet.packet_id} (code=${result.code()})")

                    // Polling статуса пакета (асинхронная обработка на бэкенде)
                    val finalStatus = pollPacketStatus(
                        packetId = packet.packet_id,
                        deviceId = deviceId,
                        accessToken = accessToken
                    )
                    Log.d(TAG, "Packet final status: ${packet.packet_id} → $finalStatus")

                    if (finalStatus == "error") {
                        handleError(packet, "Server processing error", shouldRetry = true)
                    } else {
                        repository.updatePacketStatus(
                            packetId = packet.packet_id,
                            status = PacketPipeline.STATUS_UPLOADED,
                            attempt = packet.attempt + 1,
                            error = null
                        )
                        UploadResult.Success
                    }
                }
                result.code() == 401 -> {
                    // Токен протух — обновляем и пробуем ещё раз
                    Log.w(TAG, "Got 401, refreshing token and retrying...")
                    val refreshResult = authManager.refreshAccessToken()
                    if (refreshResult.isSuccess) {
                        // Retry с новым токеном
                        val newToken = authManager.getAccessToken()
                        val retryResult = apiService.uploadPacket(
                            authorization = "Bearer $newToken",
                            idempotencyKey = packet.packet_id,
                            deviceId = deviceId,
                            request = request
                        )
                        if (retryResult.isSuccessful || retryResult.code() == 409) {
                            repository.updatePacketStatus(
                                packetId = packet.packet_id,
                                status = PacketPipeline.STATUS_UPLOADED,
                                attempt = packet.attempt + 1,
                                error = null
                            )
                            UploadResult.Success
                        } else {
                            handleError(packet, "Retry failed: ${retryResult.code()}", shouldRetry = true)
                        }
                    } else {
                        handleError(packet, "Token refresh failed", shouldRetry = true)
                    }
                }
                result.code() in listOf(400, 422) -> {
                    val err = "Server validation error: ${result.code()}"
                    handleError(packet, err, shouldRetry = false)
                }
                result.code() == 403 -> {
                    val err = "Device REVOKED or forbidden: ${result.code()}"
                    handleError(packet, err, shouldRetry = false)
                }
                result.code() == 404 -> {
                    val err = "Device not found on server: ${result.code()}"
                    handleError(packet, err, shouldRetry = false)
                }
                else -> {
                    val err = "Server error: ${result.code()}"
                    handleError(packet, err, shouldRetry = true)
                }
            }
        } catch (e: AuthManager.AuthException) {
            val err = "Auth error: ${e.message}"
            Log.e(TAG, err, e)
            handleError(packet, err, shouldRetry = true)
        } catch (e: Exception) {
            val err = "Network error: ${e.message}"
            Log.e(TAG, err, e)
            handleError(packet, err, shouldRetry = true)
        }
    }

    private fun buildRequest(
        packet: PacketQueueEntity,
        payloadJson: String
    ): UploadRequest {
        // payload_size_bytes из сохранённого файла (размер оригинальных jsonBytes)
        val payloadSizeBytes = try {
            JSONObject(payloadJson).optInt("payload_size_bytes", 0)
        } catch (e: Exception) { 0 }

        return UploadRequest(
            packet_id = packet.packet_id,
            device_id = extractField(payloadJson, "device_id").ifEmpty {
                authManager.deviceId ?: "unknown"
            },
            shift_start_ts = packet.shift_start_ts_ms,
            shift_end_ts = packet.shift_end_ts_ms,
            payload_enc = extractField(payloadJson, "payload_enc"),
            payload_key_enc = extractField(payloadJson, "payload_key_enc"),
            iv = extractField(payloadJson, "iv"),
            payload_hash = extractField(payloadJson, "payload_hash"),
            payload_size_bytes = payloadSizeBytes.takeIf { it > 0 }
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

    /**
     * Polling статуса пакета после 202 Accepted.
     * Бэкенд обрабатывает пакет асинхронно: accepted → decrypting → parsing → processing → processed|error
     * Поллим каждые 5 секунд, максимум 12 раз (60 секунд).
     *
     * @return финальный статус: "processed", "error" или "timeout"
     */
    private suspend fun pollPacketStatus(
        packetId: String,
        deviceId: String,
        accessToken: String
    ): String {
        repeat(POLL_MAX_ATTEMPTS) { attempt ->
            delay(POLL_INTERVAL_MS)
            try {
                val response = apiService.getPacketStatus(
                    authorization = "Bearer $accessToken",
                    deviceId = deviceId,
                    packetId = packetId
                )
                val status = response.body()?.status
                Log.d(TAG, "Poll #${attempt + 1}: packet=$packetId, status=$status")

                when (status) {
                    "processed" -> return "processed"
                    "error" -> return "error"
                    // accepted, decrypting, parsing, processing — продолжаем polling
                }
            } catch (e: Exception) {
                Log.w(TAG, "Poll #${attempt + 1} failed: ${e.message}")
            }
        }
        Log.w(TAG, "Polling timeout for packet $packetId after ${POLL_MAX_ATTEMPTS * POLL_INTERVAL_MS / 1000}s")
        return "timeout" // Пакет принят, но обработка ещё не завершилась
    }

    companion object {
        private const val TAG = "NetworkUploader"
        private const val POLL_INTERVAL_MS = 5000L  // 5 секунд между запросами
        private const val POLL_MAX_ATTEMPTS = 12    // 12 × 5с = 60с максимум

        /**
         * Экспоненциальный backoff: 1, 2, 5, 10, 30, 60 мин
         */
        val BACKOFF_DELAYS_MS = listOf(
            60_000L,      // 1 мин
            120_000L,     // 2 мин
            300_000L,     // 5 мин
            600_000L,     // 10 мин
            1_800_000L,   // 30 мин
            3_600_000L    // 60 мин
        )

        /**
         * Exponential backoff с jitter для 429/server errors.
         * Jitter (0–25% от base) предотвращает thundering herd.
         */
        fun backoffDelay(attempt: Int): Long {
            val base = BACKOFF_DELAYS_MS.getOrElse(attempt) { BACKOFF_DELAYS_MS.last() }
            val jitter = (0..base / 4).random()
            return base + jitter
        }
    }
}

