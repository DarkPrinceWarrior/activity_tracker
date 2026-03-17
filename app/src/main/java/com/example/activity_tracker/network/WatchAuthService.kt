package com.example.activity_tracker.network

import com.example.activity_tracker.network.model.DeviceRefreshRequest
import com.example.activity_tracker.network.model.DeviceRegisterRequest
import com.example.activity_tracker.network.model.DeviceRegisterResponse
import com.example.activity_tracker.network.model.DeviceTokenRequest
import com.example.activity_tracker.network.model.DeviceTokenResponse
import com.example.activity_tracker.network.model.HeartbeatRequest
import com.example.activity_tracker.network.model.HeartbeatResponse
import com.example.activity_tracker.network.model.RegistrationStatusResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * Retrofit-интерфейс для Auth API устройства.
 * Согласно API_REFERENCE.md секция 1 и MOBILE_GUIDE.md.
 *
 * Порядок вызовов (QR-flow):
 * 1. Часы генерируют device_id, показывают QR
 * 2. getRegistrationStatus() — поллинг, ждём пока мобилка зарегистрирует
 * 3. getToken() — получение access + refresh токенов
 * 4. refreshToken() — обновление access-токена при истечении
 * 5. heartbeat() — периодический сигнал жизни (каждые 5 мин)
 *
 * Legacy-flow (через registration_code):
 * 1. register() — первый запуск, по одноразовому коду
 * 2-5. аналогично
 */
interface WatchAuthService {

    /**
     * POST /auth/device/register
     * Регистрация устройства по одноразовому коду (legacy).
     * Rate limit: 3 запроса в минуту.
     */
    @POST("auth/device/register")
    suspend fun register(
        @Body request: DeviceRegisterRequest
    ): Response<DeviceRegisterResponse>

    /**
     * GET /auth/device/{device_id}/registration-status
     * Поллинг статуса регистрации (QR-flow).
     * Часы вызывают каждые 3 сек, пока мобилка не завершит регистрацию.
     * При registered=true ответ содержит device_secret (одноразово).
     */
    @GET("auth/device/{device_id}/registration-status")
    suspend fun getRegistrationStatus(
        @Path("device_id") deviceId: String
    ): Response<RegistrationStatusResponse>

    /**
     * POST /auth/device/token
     * Получение access и refresh токенов.
     * Rate limit: 5 запросов в минуту.
     */
    @POST("auth/device/token")
    suspend fun getToken(
        @Body request: DeviceTokenRequest
    ): Response<DeviceTokenResponse>

    /**
     * POST /auth/device/refresh
     * Обновление access-токена по refresh-токену.
     */
    @POST("auth/device/refresh")
    suspend fun refreshToken(
        @Body request: DeviceRefreshRequest
    ): Response<DeviceTokenResponse>

    /**
     * POST /watch/heartbeat
     * Периодический сигнал жизни от устройства.
     * Обновляет last_heartbeat_at и синхронизирует время.
     */
    @POST("watch/heartbeat")
    suspend fun heartbeat(
        @Header("Authorization") authorization: String,
        @Body request: HeartbeatRequest
    ): Response<HeartbeatResponse>
}
