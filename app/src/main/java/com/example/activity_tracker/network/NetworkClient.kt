package com.example.activity_tracker.network

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Singleton-провайдер Retrofit-клиента для Watch API и Auth API.
 * Бэкенд: FastAPI (Python) — watch-backend.
 */
object NetworkClient {

    /**
     * Адрес бэкенда в локальной сети.
     * Для прода заменить на HTTPS-адрес.
     */
    const val BASE_URL = "http://10.0.2.2:8000/api/v1/"

    private val loggingInterceptor = HttpLoggingInterceptor { message ->
        Log.d("OkHttp", message)
    }.apply {
        level = HttpLoggingInterceptor.Level.BODY
    }

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .addInterceptor(loggingInterceptor)
        .build()

    private val retrofit = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    val watchApiService: WatchApiService = retrofit.create(WatchApiService::class.java)
    val watchAuthService: WatchAuthService = retrofit.create(WatchAuthService::class.java)
    val bindingApiService: BindingApiService = retrofit.create(BindingApiService::class.java)
}
