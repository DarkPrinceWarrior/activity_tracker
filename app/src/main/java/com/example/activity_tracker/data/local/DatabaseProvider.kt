package com.example.activity_tracker.data.local

import android.content.Context
import androidx.room.Room
import java.util.concurrent.Executors

object DatabaseProvider {
    @Volatile
    private var INSTANCE: AppDatabase? = null

    // Выделенный пул потоков для IO-операций Room.
    // Не используем Dispatchers.IO напрямую — Room требует java.util.concurrent.Executor.
    private val dbExecutor = Executors.newFixedThreadPool(4)

    fun getDatabase(context: Context): AppDatabase {
        return INSTANCE ?: synchronized(this) {
            // Двойная проверка после захвата блокировки
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "watch.db"
            )
                // Все запросы Room выполняются на выделенном IO-пуле,
                // а не на main thread → устраняет "Davey!" кадры
                .setQueryExecutor(dbExecutor)
                .setTransactionExecutor(dbExecutor)
                .fallbackToDestructiveMigration()
                .build()
                .also { INSTANCE = it }
        }
    }
}
