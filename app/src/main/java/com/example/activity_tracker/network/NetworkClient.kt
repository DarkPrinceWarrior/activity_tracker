package com.example.activity_tracker.network

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
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

    private val loggingInterceptor = HttpLoggingInterceptor { message ->
        Log.d("OkHttp", message)
    }.apply {
        level = HttpLoggingInterceptor.Level.BODY
    }

    private val okHttpClient = OkHttpClient.Builder()
        .addInterceptor(loggingInterceptor)
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
