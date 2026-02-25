package com.example.activity_tracker.network

import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Singleton-провайдер Retrofit-клиента для WatchApiService
 * TODO: Заменить BASE_URL на реальный адрес сервера после его готовности
 */
object NetworkClient {

    /**
     * TODO: Заменить на реальный URL сервера
     * Формат: "https://your-server.com/"
     */
    const val BASE_URL = "https://placeholder.example.com/"

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val retrofit = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    val watchApiService: WatchApiService = retrofit.create(WatchApiService::class.java)
}
