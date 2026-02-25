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
 * Retrofit-интерфейс для API сервера согласно секции 9.3 плана
 */
interface WatchApiService {

    /**
     * POST /api/v1/watch/packets
     * Отправка зашифрованного пакета смены
     * Ожидаем: 202 Accepted, 409 Conflict (идемпотентный повтор)
     */
    @POST("api/v1/watch/packets")
    suspend fun uploadPacket(
        @Header("Authorization") authorization: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body request: UploadRequest
    ): Response<UploadResponse>

    /**
     * GET /api/v1/watch/packets/{packet_id}
     * Проверка статуса пакета: accepted | processing | processed | error
     */
    @GET("api/v1/watch/packets/{packet_id}")
    suspend fun getPacketStatus(
        @Header("Authorization") authorization: String,
        @Path("packet_id") packetId: String
    ): Response<PacketStatusResponse>
}
