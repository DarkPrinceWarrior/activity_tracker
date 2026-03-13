package com.example.activity_tracker

import android.app.Application
import com.example.activity_tracker.crypto.DeviceCredentialsStore
import com.example.activity_tracker.data.local.AppDatabase
import com.example.activity_tracker.data.local.DatabaseProvider
import com.example.activity_tracker.data.repository.SamplesRepository
import com.example.activity_tracker.data.repository.SamplesRepositoryImpl
import com.example.activity_tracker.network.AuthManager
import com.example.activity_tracker.network.HeartbeatWorker

class ActivityTrackerApp : Application() {

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

        // Запускаем периодический heartbeat (если устройство зарегистрировано)
        if (credentialsStore.isRegistered) {
            HeartbeatWorker.schedule(this)
        }
    }

    companion object {
        lateinit var instance: ActivityTrackerApp
            private set
    }
}

