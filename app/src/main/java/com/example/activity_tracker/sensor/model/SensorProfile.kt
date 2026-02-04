package com.example.activity_tracker.sensor.model

import android.hardware.SensorManager

/**
 * Профили сбора сенсоров согласно секции 17 плана
 */
enum class SensorProfile(
    val samplingPeriodUs: Int,
    val description: String
) {
    /**
     * Нормальный режим: 25-50 Гц
     * Используется во время рабочей смены при нормальном заряде
     */
    NORMAL(
        samplingPeriodUs = 40_000, // 25 Гц
        description = "Normal mode: 25 Hz for motion sensors"
    ),

    /**
     * Экономный режим: 12-25 Гц
     * Используется при заряде < 20% или отсутствии сети > 2 часов
     */
    ECO(
        samplingPeriodUs = 80_000, // 12.5 Гц
        description = "Eco mode: 12.5 Hz, magnetometer disabled"
    ),

    /**
     * Агрессивный режим: 50-100 Гц
     * Используется кратковременно для калибровки при зарядке
     */
    AGGRESSIVE(
        samplingPeriodUs = 20_000, // 50 Гц
        description = "Aggressive mode: 50 Hz for short calibration periods"
    );

    companion object {
        /**
         * Выбор профиля на основе условий
         */
        fun selectProfile(
            batteryLevel: Float,
            isCharging: Boolean,
            noNetworkHours: Int
        ): SensorProfile {
            return when {
                isCharging -> AGGRESSIVE
                batteryLevel < 0.20f -> ECO
                noNetworkHours > 2 -> ECO
                else -> NORMAL
            }
        }
    }
}
