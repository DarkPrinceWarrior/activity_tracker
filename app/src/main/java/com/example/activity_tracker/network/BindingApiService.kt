package com.example.activity_tracker.network

import com.example.activity_tracker.network.model.BindingListResponse
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Query

/**
 * Retrofit-интерфейс для polling привязок.
 * Часы используют GET /bindings/?device_id=... для определения
 * активной привязки и автоматического управления сбором данных.
 */
interface BindingApiService {

    /**
     * GET /bindings/?device_id=...&status=active&page_size=1
     * Проверяет наличие активной привязки для данного устройства.
     *
     * Заголовки:
     * - Authorization: Bearer <access_token>
     *
     * Часы запрашивают page_size=1, status=active для минимального трафика.
     */
    @GET("bindings/")
    suspend fun getBindings(
        @Header("Authorization") authorization: String,
        @Query("device_id") deviceId: String,
        @Query("status") status: String? = null,
        @Query("page") page: Int = 1,
        @Query("page_size") pageSize: Int = 1
    ): Response<BindingListResponse>
}
