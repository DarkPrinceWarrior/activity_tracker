package com.example.activity_tracker

import android.app.Application
import com.example.activity_tracker.data.local.AppDatabase
import com.example.activity_tracker.data.local.DatabaseProvider
import com.example.activity_tracker.data.repository.SamplesRepository
import com.example.activity_tracker.data.repository.SamplesRepositoryImpl

class ActivityTrackerApp : Application() {

    val database: AppDatabase by lazy {
        DatabaseProvider.getDatabase(this)
    }

    val samplesRepository: SamplesRepository by lazy {
        SamplesRepositoryImpl(database)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: ActivityTrackerApp
            private set
    }
}
