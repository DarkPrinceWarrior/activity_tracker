package com.example.activity_tracker.network.model

/**
 * Модели для аутентификации устройства (часов) на бэкенде.
 * Согласно API_REFERENCE.md секция 1 — "Аутентификация устройства".
 */

// ─── Регистрация устройства ───

data class DeviceRegisterRequest(
    val registration_code: String,
    val model: String = "Galaxy Watch 8",
    val firmware: String = "Wear OS 5",
    val app_version: String = "1.0.0",
    val timezone: String = "Europe/Moscow"
)

data class DeviceRegisterResponse(
    val device_id: String,
    val device_secret: String,
    val server_public_key_pem: String,
    val server_time: String
)

// ─── Получение токенов ───

data class DeviceTokenRequest(
    val device_id: String,
    val device_secret: String
)

data class DeviceTokenResponse(
    val access_token: String,
    val refresh_token: String,
    val expires_in: Int,
    val server_time: String
)

// ─── Обновление токена ───

data class DeviceRefreshRequest(
    val device_id: String,
    val refresh_token: String
)

// ─── Heartbeat ───

data class HeartbeatRequest(
    val device_id: String,
    val device_time_ms: Long,
    val battery_level: Float,
    val is_collecting: Boolean,
    val pending_packets: Int,
    val app_version: String = "1.0.0"
)

data class HeartbeatResponse(
    val server_time: String,
    val server_time_ms: Long,
    val time_offset_ms: Long,
    val commands: List<String> = emptyList()
)

// ─── Polling регистрации (QR-flow) ───

data class RegistrationStatusResponse(
    val registered: Boolean,
    val device_secret: String? = null,
    val server_public_key_pem: String? = null,
    val server_time: String? = null
)
