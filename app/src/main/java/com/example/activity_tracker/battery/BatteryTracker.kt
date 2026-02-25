package com.example.activity_tracker.battery

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import com.example.activity_tracker.battery.model.BatterySample
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Отслеживание уровня заряда батареи
 * Используется для записи в БД и автоматического переключения профилей сбора
 * Согласно секции 8 и 17 плана
 */
class BatteryTracker(private val context: Context) {

    /**
     * Создает Flow для отслеживания уровня батареи
     */
    fun trackBattery(): Flow<BatterySample> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_BATTERY_CHANGED -> {
                        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)

                        if (level >= 0 && scale > 0) {
                            val batteryLevel = level / scale.toFloat()
                            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                                    status == BatteryManager.BATTERY_STATUS_FULL

                            val sample = BatterySample(
                                timestamp = System.currentTimeMillis(),
                                level = batteryLevel,
                                isCharging = isCharging
                            )

                            trySend(sample)
                            Log.d(TAG, "Battery: ${(batteryLevel * 100).toInt()}%, charging: $isCharging")
                        }
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
        }

        context.registerReceiver(receiver, filter)
        Log.d(TAG, "Battery tracking started")

        // Отправляем начальное состояние батареи
        val batteryStatus = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        batteryStatus?.let { intent ->
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)

            if (level >= 0 && scale > 0) {
                val batteryLevel = level / scale.toFloat()
                val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL

                val sample = BatterySample(
                    timestamp = System.currentTimeMillis(),
                    level = batteryLevel,
                    isCharging = isCharging
                )

                trySend(sample)
                Log.d(TAG, "Initial battery: ${(batteryLevel * 100).toInt()}%, charging: $isCharging")
            }
        }

        awaitClose {
            try {
                context.unregisterReceiver(receiver)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Receiver already unregistered", e)
            }
            Log.d(TAG, "Battery tracking stopped")
        }
    }

    /**
     * Получает текущий уровень заряда батареи синхронно
     */
    fun getCurrentBatteryLevel(): Float {
        val batteryStatus = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return batteryStatus?.let { intent ->
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level >= 0 && scale > 0) {
                level / scale.toFloat()
            } else {
                1.0f // По умолчанию предполагаем полный заряд
            }
        } ?: 1.0f
    }

    /**
     * Проверяет, заряжается ли устройство
     */
    fun isCharging(): Boolean {
        val batteryStatus = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return batteryStatus?.let { intent ->
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL
        } ?: false
    }

    companion object {
        private const val TAG = "BatteryTracker"
    }
}
