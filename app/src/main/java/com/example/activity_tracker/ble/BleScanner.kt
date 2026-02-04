package com.example.activity_tracker.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.util.Log
import com.example.activity_tracker.ble.model.BleBeacon
import com.example.activity_tracker.ble.model.BleProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Сканер BLE-меток с периодическим сканированием окнами
 * Согласно секции 5, 17 и 23 плана
 */
class BleScanner(context: Context) {

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private val bleScanner: BluetoothLeScanner? = bluetoothAdapter?.bluetoothLeScanner

    private var currentProfile: BleProfile = BleProfile.NORMAL
    private var scanJob: Job? = null

    init {
        logBluetoothAvailability()
    }

    /**
     * Устанавливает профиль сканирования (NORMAL, ECO, AGGRESSIVE)
     */
    fun setProfile(profile: BleProfile) {
        currentProfile = profile
        Log.d(TAG, "BLE profile changed to: ${profile.name} (${profile.description})")
    }

    /**
     * Создает Flow для периодического сканирования BLE-меток
     */
    @SuppressLint("MissingPermission")
    fun scanBeacons(scope: CoroutineScope): Flow<BleBeacon> = callbackFlow {
        val scanner = bleScanner
        if (scanner == null) {
            Log.e(TAG, "BLE scanner not available")
            close()
            return@callbackFlow
        }

        if (bluetoothAdapter?.isEnabled != true) {
            Log.e(TAG, "Bluetooth is disabled")
            close()
            return@callbackFlow
        }

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val beacon = BleBeacon(
                    timestamp = System.currentTimeMillis(),
                    beaconId = result.device.address,
                    rssi = result.rssi
                )
                trySend(beacon)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { result ->
                    val beacon = BleBeacon(
                        timestamp = System.currentTimeMillis(),
                        beaconId = result.device.address,
                        rssi = result.rssi
                    )
                    trySend(beacon)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "BLE scan failed with error code: $errorCode")
            }
        }

        // Периодическое сканирование окнами
        scanJob = scope.launch {
            while (isActive) {
                try {
                    // Начало окна сканирования
                    Log.d(TAG, "Starting BLE scan window (${currentProfile.scanWindowMs}ms)")
                    scanner.startScan(null, scanSettings, callback)

                    // Ждем окно сканирования
                    delay(currentProfile.scanWindowMs)

                    // Остановка сканирования
                    scanner.stopScan(callback)
                    Log.d(TAG, "BLE scan window completed")

                    // Ждем до следующего окна
                    val pauseDuration = currentProfile.scanIntervalMs - currentProfile.scanWindowMs
                    if (pauseDuration > 0) {
                        Log.d(TAG, "BLE scan paused for ${pauseDuration}ms")
                        delay(pauseDuration)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error during BLE scan", e)
                    delay(5000) // Пауза перед повторной попыткой
                }
            }
        }

        awaitClose {
            try {
                scanner.stopScan(callback)
                scanJob?.cancel()
                Log.d(TAG, "BLE scanner stopped")
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping BLE scan", e)
            }
        }
    }

    /**
     * Проверяет доступность Bluetooth и BLE
     */
    fun isAvailable(): Boolean {
        return bluetoothAdapter != null && bleScanner != null
    }

    /**
     * Проверяет, включен ли Bluetooth
     */
    fun isBluetoothEnabled(): Boolean {
        return bluetoothAdapter?.isEnabled == true
    }

    private fun logBluetoothAvailability() {
        Log.d(TAG, "=== Bluetooth Availability ===")
        Log.d(TAG, "Bluetooth adapter: ${bluetoothAdapter != null}")
        Log.d(TAG, "BLE scanner: ${bleScanner != null}")
        Log.d(TAG, "Bluetooth enabled: ${isBluetoothEnabled()}")
    }

    companion object {
        private const val TAG = "BleScanner"
    }
}
