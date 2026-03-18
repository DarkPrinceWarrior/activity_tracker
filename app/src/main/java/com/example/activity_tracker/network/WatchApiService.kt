package com.example.activity_tracker.network

import com.example.activity_tracker.network.model.PacketStatusResponse
import com.example.activity_tracker.network.model.UploadRequest
import com.example.activity_tracker.network.model.UploadResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * Retrofit-интерфейс для Watch API — отправка пакетов и проверка статусов.
 * Согласно API_REFERENCE.md секция 3 — "Watch API".
 */
interface WatchApiService {

    /**
     * POST /watch/packets
     * Отправка зашифрованного пакета смены.
     *
     * Заголовки:
     * - Authorization: Bearer <access_token>
     * - Idempotency-Key: <packet_id> (ОБЯЗАН совпадать с packet_id в теле)
     * - x-device-id: <device_id> (для доп. валидации)
     *
     * Ответы: 202 Accepted, 409 Conflict (дубликат — считаем успехом)
     */
    @POST("watch/packets")
    suspend fun uploadPacket(
        @Header("Authorization") authorization: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Header("x-device-id") deviceId: String,
        @Body request: UploadRequest
    ): Response<UploadResponse>

    /**
     * GET /watch/packets/{packet_id}
     * Проверка статуса пакета: accepted | decrypting | parsing | processing | processed | error
     *
     * Заголовки:
     * - Authorization: Bearer <device_access_token>
     * - x-device-id: обязателен
     */
    @GET("watch/packets/{packet_id}")
    suspend fun getPacketStatus(
        @Header("Authorization") authorization: String,
        @Header("x-device-id") deviceId: String,
        @Path("packet_id") packetId: String
    ): Response<PacketStatusResponse>

    /**
     * GET /watch/packets/{packet_id}/echo
     * Отладочный endpoint — возвращает расшифрованные данные пакета.
     * Полезно для проверки что бэкенд корректно расшифровал и распарсил payload.
     */
    @GET("watch/packets/{packet_id}/echo")
    suspend fun getPacketEcho(
        @Header("Authorization") authorization: String,
        @Header("x-device-id") deviceId: String,
        @Path("packet_id") packetId: String
    ): Response<okhttp3.ResponseBody>
}

