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
import com.example.activity_tracker.network.UploadWorker
import com.example.activity_tracker.packet.PacketPipeline
import com.example.activity_tracker.sensor.SensorCollector
import com.example.activity_tracker.sensor.SensorDataAggregator
import com.example.activity_tracker.step.StepCollector
import com.example.activity_tracker.step.StepDataAggregator
import com.example.activity_tracker.wear.WearDataAggregator
import com.example.activity_tracker.wear.WearStateTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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

    private lateinit var stepCollector: StepCollector
    private lateinit var stepAggregator: StepDataAggregator

    private val collectionJobs = mutableListOf<Job>()
    private var packetJob: Job? = null

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

        stepCollector = StepCollector(this)
        stepAggregator = StepDataAggregator(repository)

        // Создание и показ foreground notification
        startForeground(NOTIFICATION_ID, createNotification())
    }

    private fun startDataCollection(startTs: Long?) {
        Log.d(TAG, "Starting data collection")

        // Пересоздаём scope если предыдущий был отменён
        if (serviceJob.isCancelled) {
            serviceJob = SupervisorJob()
            serviceScope = CoroutineScope(serviceJob + Dispatchers.Default)
        }

        if (collectionJobs.isNotEmpty()) {
            Log.d(TAG, "Collection already active, ignoring duplicate start")
            return
        }

        initializeShiftState(startTs ?: System.currentTimeMillis())

        // Запуск сбора с акселерометра
        collectionJobs.add(
            serviceScope.launch {
                sensorAggregator.collectAndStore(
                    sensorCollector.collectAccelerometer()
                )
            }
        )

        // Запуск сбора с гироскопа
        collectionJobs.add(
            serviceScope.launch {
                sensorAggregator.collectAndStore(
                    sensorCollector.collectGyroscope()
                )
            }
        )

        // Запуск сбора с барометра (опциональный)
        collectionJobs.add(
            serviceScope.launch {
                sensorAggregator.collectAndStore(
                    sensorCollector.collectBarometer()
                )
            }
        )

        // Запуск сбора с магнитометра (опциональный)
        collectionJobs.add(
            serviceScope.launch {
                sensorAggregator.collectAndStore(
                    sensorCollector.collectMagnetometer()
                )
            }
        )

        // Запуск BLE-сканирования
        collectionJobs.add(
            serviceScope.launch {
                bleAggregator.collectAndStore(
                    bleScanner.scanBeacons()
                )
            }
        )

        // Запуск отслеживания ношения часов
        collectionJobs.add(
            serviceScope.launch {
                wearAggregator.collectAndStore(
                    wearStateTracker.trackWearState()
                )
            }
        )

        // Запуск сбора пульса (опциональный)
        collectionJobs.add(
            serviceScope.launch {
                heartRateAggregator.collectAndStore(
                    heartRateCollector.collectHeartRate()
                )
            }
        )

        // Запуск отслеживания батареи
        collectionJobs.add(
            serviceScope.launch {
                batteryAggregator.collectAndStore(
                    batteryTracker.trackBattery()
                )
            }
        )

        collectionJobs.add(
            serviceScope.launch {
                stepAggregator.collectAndStore(
                    stepCollector.collectSteps()
                )
            }
        )

        packetJob = serviceScope.launch {
            while (true) {
                delay(PACKET_INTERVAL_MS)
                enqueuePacketWindow(
                    endTs = System.currentTimeMillis(),
                    reason = "periodic"
                )
            }
        }

        Log.d(TAG, "All collectors started (9 parallel streams)")
    }

    private fun stopDataCollection(stopTs: Long?) {
        Log.d(TAG, "Stopping data collection")
        val finalTs = stopTs ?: System.currentTimeMillis()
        packetJob?.cancel()
        packetJob = null
        // Отменяем каждый collection job напрямую
        collectionJobs.forEach { it.cancel() }
        // Ждём завершения корутин, чтобы awaitClose успел отменить ресиверы
        runBlocking {
            withTimeoutOrNull(5000L) {
                collectionJobs.joinAll()
            }
            flushAggregators()
            enqueuePacketWindow(
                endTs = finalTs,
                reason = "final"
            )
        }
        collectionJobs.clear()
        clearShiftState()
        Log.d(TAG, "All coroutines cancelled")
        stopSelf()
    }

    override fun onDestroy() {
        Log.d(TAG, "CollectorService destroyed")
        packetJob?.cancel()
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
            start(context, System.currentTimeMillis())
        }

        fun start(context: Context, shiftStartTs: Long) {
            val intent = Intent(context, CollectorService::class.java).apply {
                action = ACTION_START_COLLECTION
                putExtra(EXTRA_SHIFT_START_TS, shiftStartTs)
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            stop(context, System.currentTimeMillis())
        }

        fun stop(context: Context, shiftEndTs: Long) {
            val intent = Intent(context, CollectorService::class.java).apply {
                action = ACTION_STOP_COLLECTION
                putExtra(EXTRA_SHIFT_END_TS, shiftEndTs)
            }
            context.startService(intent)
        }

        private const val PREFS_NAME = "collection_session"
        private const val KEY_SHIFT_START_TS = "shift_start_ts"
        private const val KEY_LAST_PACKET_END_TS = "last_packet_end_ts"
        private const val KEY_PACKET_SEQ = "packet_seq"
        private const val KEY_ACTIVE = "active"
        private const val EXTRA_SHIFT_START_TS = "extra_shift_start_ts"
        private const val EXTRA_SHIFT_END_TS = "extra_shift_end_ts"
        private const val PACKET_INTERVAL_MS = 5 * 60 * 1000L
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "CollectorService started")

        when (intent?.action) {
            ACTION_START_COLLECTION -> startDataCollection(intent.getLongExtra(EXTRA_SHIFT_START_TS, System.currentTimeMillis()))
            ACTION_STOP_COLLECTION -> stopDataCollection(intent.getLongExtra(EXTRA_SHIFT_END_TS, System.currentTimeMillis()))
            else -> startDataCollection(null)
        }

        return START_STICKY
    }

    private fun prefs() = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun initializeShiftState(startTs: Long) {
        val prefs = prefs()
        if (prefs.getBoolean(KEY_ACTIVE, false)) {
            return
        }
        prefs.edit()
            .putBoolean(KEY_ACTIVE, true)
            .putLong(KEY_SHIFT_START_TS, startTs)
            .putLong(KEY_LAST_PACKET_END_TS, startTs)
            .putInt(KEY_PACKET_SEQ, 0)
            .apply()
    }

    private suspend fun flushAggregators() {
        sensorAggregator.flushAll()
        bleAggregator.flushAll()
        heartRateAggregator.flushAll()
        stepAggregator.flushAll()
    }

    private suspend fun enqueuePacketWindow(endTs: Long, reason: String) {
        val prefs = prefs()
        if (!prefs.getBoolean(KEY_ACTIVE, false)) return

        val startTs = prefs.getLong(KEY_LAST_PACKET_END_TS, endTs)
        if (endTs <= startTs) {
            Log.d(TAG, "Skipping $reason packet: empty interval [$startTs, $endTs]")
            return
        }

        flushAggregators()

        val app = application as ActivityTrackerApp
        val deviceId = app.credentialsStore.deviceId ?: return
        val serverKey = app.credentialsStore.serverPublicKeyPem
        val seq = prefs.getInt(KEY_PACKET_SEQ, 0)
        val pipeline = PacketPipeline(this, app.samplesRepository, deviceId, serverKey)
        val result = pipeline.buildAndEnqueue(startTs, endTs, seq)

        if (result != null) {
            prefs.edit()
                .putLong(KEY_LAST_PACKET_END_TS, endTs)
                .putInt(KEY_PACKET_SEQ, seq + 1)
                .apply()
            UploadWorker.schedule(this)
            Log.d(TAG, "Enqueued $reason packet ${result.packetId} for [$startTs, $endTs]")
        } else {
            Log.e(TAG, "Failed to enqueue $reason packet for [$startTs, $endTs]")
        }
    }

    private fun clearShiftState() {
        prefs().edit().clear().apply()
    }
}
