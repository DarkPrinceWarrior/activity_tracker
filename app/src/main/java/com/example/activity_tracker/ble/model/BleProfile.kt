package com.example.activity_tracker.ble.model

/**
 * Профили BLE-сканирования согласно секции 17 плана
 * Периодическое сканирование окнами для экономии батареи
 */
enum class BleProfile(
    val scanWindowMs: Long,
    val scanIntervalMs: Long,
    val description: String
) {
    /**
     * Нормальный режим: окно 10 сек каждые 60 сек
     */
    NORMAL(
        scanWindowMs = 10_000,
        scanIntervalMs = 60_000,
        description = "Normal: 10s scan every 60s"
    ),

    /**
     * Экономный режим: окно 5 сек каждые 120 сек
     */
    ECO(
        scanWindowMs = 5_000,
        scanIntervalMs = 120_000,
        description = "Eco: 5s scan every 120s"
    ),

    /**
     * Агрессивный режим: окно 10 сек каждые 30 сек
     */
    AGGRESSIVE(
        scanWindowMs = 10_000,
        scanIntervalMs = 30_000,
        description = "Aggressive: 10s scan every 30s"
    );

    companion object {
        /**
         * Выбор профиля на основе условий (синхронизован с SensorProfile)
         */
        fun selectProfile(
            batteryLevel: Float,
            isCharging: Boolean,
            noNetworkHours: Int
        ): BleProfile {
            return when {
                isCharging -> AGGRESSIVE
                batteryLevel < 0.20f -> ECO
                noNetworkHours > 2 -> ECO
                else -> NORMAL
            }
        }
    }
}
