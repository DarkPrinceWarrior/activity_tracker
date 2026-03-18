package com.example.activity_tracker

import android.app.Application
import com.example.activity_tracker.crypto.DeviceCredentialsStore
import com.example.activity_tracker.data.local.AppDatabase
import com.example.activity_tracker.data.local.DatabaseProvider
import com.example.activity_tracker.data.repository.SamplesRepository
import com.example.activity_tracker.data.repository.SamplesRepositoryImpl
import com.example.activity_tracker.network.AuthManager
import com.example.activity_tracker.network.HeartbeatWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ActivityTrackerApp : Application() {

    // Scope, привязанный к жизни процесса приложения
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database: AppDatabase by lazy {
        DatabaseProvider.getDatabase(this)
    }

    val samplesRepository: SamplesRepository by lazy {
        SamplesRepositoryImpl(database)
    }

    val credentialsStore: DeviceCredentialsStore by lazy {
        DeviceCredentialsStore(this)
    }

    val authManager: AuthManager by lazy {
        AuthManager(credentialsStore)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Прогреваем тяжёлые компоненты в фоне, пока система рендерит splash.
        // Tink (EncryptedSharedPreferences) и Room инициализируются на IO-потоке,
        // а не на main thread → устраняет "Skipped N frames" при старте.
        appScope.launch(Dispatchers.IO) {
            // 1. Инициализируем DeviceCredentialsStore (Tink / EncryptedSharedPreferences)
            val registered = credentialsStore.isRegistered

            // 2. Открываем соединение с БД заранее (Room ленив — первый запрос открывает файл)
            database.openHelper.readableDatabase

            // 3. Планируем heartbeat уже в фоне, если уже зарегистрированы
            if (registered) {
                HeartbeatWorker.schedule(this@ActivityTrackerApp)
            }
        }
    }

    companion object {
        lateinit var instance: ActivityTrackerApp
            private set
    }
}
