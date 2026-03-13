package com.example.activity_tracker.network.model

/**
 * Тело запроса POST /api/v1/watch/packets
 * Согласно API_REFERENCE.md секция 3.
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
    val payload_hash: String,
    val payload_size_bytes: Int? = null
)

/**
 * Ответ сервера 202 Accepted
 */
data class UploadResponse(
    val packet_id: String,
    val status: String,
    val received_at: String? = null,
    val server_time: String? = null
)

/**
 * Ответ GET /api/v1/watch/packets/{packet_id}
 */
data class PacketStatusResponse(
    val packet_id: String,
    val status: String
)

