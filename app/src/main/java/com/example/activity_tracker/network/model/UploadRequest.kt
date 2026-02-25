package com.example.activity_tracker.network.model

/**
 * Тело запроса POST /api/v1/watch/packets (секция 9.3 плана)
 */
data class UploadRequest(
    val packet_id: String,
    val device_id: String,
    val shift_start_ts: Long,
    val shift_end_ts: Long,
    val schema_version: Int = 1,
    val payload_enc: String,
    val payload_key_enc: String,
    val iv: String,
    val payload_hash: String
)

/**
 * Ответ сервера 202 Accepted (секция 9.4 плана)
 */
data class UploadResponse(
    val packet_id: String,
    val status: String,
    val server_time: String? = null
)

/**
 * Ответ GET /api/v1/watch/packets/{packet_id}
 */
data class PacketStatusResponse(
    val packet_id: String,
    val status: String
)
