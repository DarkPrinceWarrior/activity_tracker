package com.example.activity_tracker.packet

import android.content.Context
import android.util.Log
import com.example.activity_tracker.crypto.CryptoManager
import com.example.activity_tracker.data.local.entity.PacketQueueEntity
import com.example.activity_tracker.data.repository.SamplesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Оркестрирует сборку пакета смены: PacketBuilder → CryptoManager → PacketQueue
 *
 * @param serverPublicKeyPem PEM-ключ сервера для шифрования (из DeviceCredentialsStore)
 */
class PacketPipeline(
    private val context: Context,
    private val repository: SamplesRepository,
    serverPublicKeyPem: String? = null
) {

    private val packetBuilder = PacketBuilder(context, repository)
    private val cryptoManager = CryptoManager(serverPublicKeyPem)

    /**
     * Результат успешной сборки пакета
     */
    data class PipelineResult(
        val packetId: String,
        val payloadSizeBytes: Int,
        val payloadPath: String
    )

    /**
     * Полный цикл: выборка данных → JSON → шифрование → сохранение → очередь
     * @param startTs начало смены (Unix ms)
     * @param endTs конец смены (Unix ms)
     * @param seq порядковый номер пакета
     * @return PipelineResult или null при ошибке
     */
    suspend fun buildAndEnqueue(
        startTs: Long,
        endTs: Long,
        seq: Int = 0
    ): PipelineResult? = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Pipeline started for shift [$startTs, $endTs]")

            // 1. Сборка пакета из Room
            val packet = packetBuilder.build(startTs, endTs, seq)
            val plaintextJson = packetBuilder.toJson(packet)
            val plainSizeBytes = plaintextJson.toByteArray().size
            Log.d(TAG, "Packet built: id=${packet.packet_id}, size=${plainSizeBytes}B")

            // 2. Шифрование
            val encrypted = cryptoManager.encrypt(plaintextJson)
            Log.d(TAG, "Packet encrypted: hash=${encrypted.payloadHashSha256.take(16)}...")

            // 3. Сохранение зашифрованного payload на диск
            val payloadPath = savePayloadToDisk(
                packetId = packet.packet_id,
                encrypted = encrypted
            )
            Log.d(TAG, "Payload saved to: $payloadPath")

            // 4. Добавление в очередь отправки
            val queueEntry = PacketQueueEntity(
                packet_id = packet.packet_id,
                created_ts_ms = System.currentTimeMillis(),
                shift_start_ts_ms = startTs,
                shift_end_ts_ms = endTs,
                status = STATUS_PENDING,
                attempt = 0,
                last_error = null,
                payload_path = payloadPath
            )
            repository.enqueuePacket(queueEntry)
            Log.d(TAG, "Packet enqueued: id=${packet.packet_id}")

            PipelineResult(
                packetId = packet.packet_id,
                payloadSizeBytes = plainSizeBytes,
                payloadPath = payloadPath
            )
        } catch (e: Exception) {
            Log.e(TAG, "Pipeline failed", e)
            null
        }
    }

    /**
     * Сохраняет зашифрованный payload в файл в папке приложения
     * Формат файла: JSON с полями payloadEnc, payloadKeyEnc, iv, hash
     */
    private fun savePayloadToDisk(
        packetId: String,
        encrypted: CryptoManager.EncryptedPacket
    ): String {
        val dir = File(context.filesDir, PACKETS_DIR)
        if (!dir.exists()) dir.mkdirs()

        val file = File(dir, "$packetId.enc")
        val content = buildString {
            appendLine("{")
            appendLine("  \"payload_enc\": \"${encrypted.payloadEncBase64}\",")
            appendLine("  \"payload_key_enc\": \"${encrypted.payloadKeyEncBase64}\",")
            appendLine("  \"iv\": \"${encrypted.ivBase64}\",")
            appendLine("  \"payload_hash\": \"${encrypted.payloadHashSha256}\",")
            appendLine("  \"payload_size_bytes\": ${encrypted.payloadSizeBytes}")
            appendLine("}")
        }
        file.writeText(content, Charsets.UTF_8)
        return file.absolutePath
    }

    /**
     * Читает сохраненный payload из файла
     */
    fun readPayloadFromDisk(path: String): String? {
        return try {
            File(path).readText(Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read payload from $path", e)
            null
        }
    }

    /**
     * Удаляет файл payload после успешной отправки
     */
    fun deletePayload(path: String) {
        try {
            File(path).delete()
            Log.d(TAG, "Payload deleted: $path")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete payload: $path", e)
        }
    }

    companion object {
        private const val TAG = "PacketPipeline"
        private const val PACKETS_DIR = "packets"

        const val STATUS_PENDING = "pending"
        const val STATUS_UPLOADING = "uploading"
        const val STATUS_UPLOADED = "uploaded"
        const val STATUS_ERROR = "error"
    }
}
