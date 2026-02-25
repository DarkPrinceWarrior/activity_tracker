package com.example.activity_tracker.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.activity_tracker.ActivityTrackerApp
import com.example.activity_tracker.R
import com.example.activity_tracker.battery.BatteryDataAggregator
import com.example.activity_tracker.battery.BatteryTracker
import com.example.activity_tracker.ble.BleDataAggregator
import com.example.activity_tracker.ble.BleScanner
import com.example.activity_tracker.heartrate.HeartRateCollector
import com.example.activity_tracker.heartrate.HeartRateDataAggregator
import com.example.activity_tracker.sensor.SensorCollector
import com.example.activity_tracker.sensor.SensorDataAggregator
import com.example.activity_tracker.wear.WearDataAggregator
import com.example.activity_tracker.wear.WearStateTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Foreground Service для непрерывного сбора данных с сенсоров, BLE и wear-событий
 * Согласно секции 23.1 и Итерации 1 плана
 */
class CollectorService : Service() {

    private var serviceJob = SupervisorJob()
    private var serviceScope = CoroutineScope(serviceJob + Dispatchers.Default)

    private lateinit var sensorCollector: SensorCollector
    private lateinit var sensorAggregator: SensorDataAggregator

    private lateinit var bleScanner: BleScanner
    private lateinit var bleAggregator: BleDataAggregator

    private lateinit var wearStateTracker: WearStateTracker
    private lateinit var wearAggregator: WearDataAggregator

    private lateinit var heartRateCollector: HeartRateCollector
    private lateinit var heartRateAggregator: HeartRateDataAggregator

    private lateinit var batteryTracker: BatteryTracker
    private lateinit var batteryAggregator: BatteryDataAggregator

    private val collectionJobs = mutableListOf<Job>()

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "CollectorService created")

        val app = application as ActivityTrackerApp
        val repository = app.samplesRepository

        // Инициализация компонентов
        sensorCollector = SensorCollector(this)
        sensorAggregator = SensorDataAggregator(repository)

        bleScanner = BleScanner(this)
        bleAggregator = BleDataAggregator(repository)

        wearStateTracker = WearStateTracker(this)
        wearAggregator = WearDataAggregator(repository)

        heartRateCollector = HeartRateCollector(this)
        heartRateAggregator = HeartRateDataAggregator(repository)

        batteryTracker = BatteryTracker(this)
        batteryAggregator = BatteryDataAggregator(repository)

        // Создание и показ foreground notification
        startForeground(NOTIFICATION_ID, createNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "CollectorService started")

        when (intent?.action) {
            ACTION_START_COLLECTION -> startDataCollection()
            ACTION_STOP_COLLECTION -> stopDataCollection()
            else -> startDataCollection()
        }

        return START_STICKY
    }

    private fun startDataCollection() {
        Log.d(TAG, "Starting data collection")

        // Пересоздаём scope если предыдущий был отменён
        if (serviceJob.isCancelled) {
            serviceJob = SupervisorJob()
            serviceScope = CoroutineScope(serviceJob + Dispatchers.Default)
        }

        // Запуск сбора с акселерометра
        collectionJobs.add(
            serviceScope.launch {
                sensorAggregator.collectAndStore(
                    sensorCollector.collectAccelerometer(),
                    serviceScope
                )
            }
        )

        // Запуск сбора с гироскопа
        collectionJobs.add(
            serviceScope.launch {
                sensorAggregator.collectAndStore(
                    sensorCollector.collectGyroscope(),
                    serviceScope
                )
            }
        )

        // Запуск сбора с барометра (опциональный)
        collectionJobs.add(
            serviceScope.launch {
                sensorAggregator.collectAndStore(
                    sensorCollector.collectBarometer(),
                    serviceScope
                )
            }
        )

        // Запуск сбора с магнитометра (опциональный)
        collectionJobs.add(
            serviceScope.launch {
                sensorAggregator.collectAndStore(
                    sensorCollector.collectMagnetometer(),
                    serviceScope
                )
            }
        )

        // Запуск BLE-сканирования
        collectionJobs.add(
            serviceScope.launch {
                bleAggregator.collectAndStore(
                    bleScanner.scanBeacons(),
                    serviceScope
                )
            }
        )

        // Запуск отслеживания ношения часов
        collectionJobs.add(
            serviceScope.launch {
                wearAggregator.collectAndStore(
                    wearStateTracker.trackWearState(),
                    serviceScope
                )
            }
        )

        // Запуск сбора пульса (опциональный)
        collectionJobs.add(
            serviceScope.launch {
                heartRateAggregator.collectAndStore(
                    heartRateCollector.collectHeartRate(),
                    serviceScope
                )
            }
        )

        // Запуск отслеживания батареи
        collectionJobs.add(
            serviceScope.launch {
                batteryAggregator.collectAndStore(
                    batteryTracker.trackBattery(),
                    serviceScope
                )
            }
        )

        Log.d(TAG, "All collectors started (8 parallel streams)")
    }

    private fun stopDataCollection() {
        Log.d(TAG, "Stopping data collection")
        // Отменяем ВСЕ корутины включая вложенные job'ы агрегаторов
        serviceJob.cancel()
        // Ждём завершения корутин, чтобы awaitClose успел отменить ресиверы
        runBlocking {
            withTimeoutOrNull(3000L) {
                collectionJobs.joinAll()
            }
        }
        collectionJobs.clear()
        Log.d(TAG, "All coroutines cancelled")
        stopSelf()
    }

    override fun onDestroy() {
        Log.d(TAG, "CollectorService destroyed")
        serviceJob.cancel()
        collectionJobs.clear()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotification(): Notification {
        createNotificationChannel()

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Activity Tracker")
            .setContentText("Сбор данных активности")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Activity Collection",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Непрерывный сбор данных с сенсоров"
        }

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "CollectorService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "activity_collection"

        const val ACTION_START_COLLECTION = "com.example.activity_tracker.START_COLLECTION"
        const val ACTION_STOP_COLLECTION = "com.example.activity_tracker.STOP_COLLECTION"

        fun start(context: Context) {
            val intent = Intent(context, CollectorService::class.java).apply {
                action = ACTION_START_COLLECTION
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, CollectorService::class.java).apply {
                action = ACTION_STOP_COLLECTION
            }
            context.startService(intent)
        }
    }
}
