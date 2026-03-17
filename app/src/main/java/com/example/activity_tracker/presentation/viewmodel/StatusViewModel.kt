package com.example.activity_tracker.presentation.viewmodel

import android.app.Application
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.activity_tracker.ActivityTrackerApp
import com.example.activity_tracker.network.AuthManager
import com.example.activity_tracker.network.HeartbeatWorker
import com.example.activity_tracker.network.UploadWorker
import com.example.activity_tracker.packet.PacketPipeline
import com.example.activity_tracker.service.CollectorService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * ViewModel для управления состоянием UI:
 * - QR-регистрация: генерация device_id, QR-payload, поллинг
 * - Сбор данных
 * - Статус пакетов
 */
class StatusViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as ActivityTrackerApp
    private val repository = app.samplesRepository
    private val authManager = app.authManager

    // ─── QR Registration ───

    /** JSON-строка для QR-кода (содержит device_id, model, firmware) */
    private val _qrPayload = MutableStateFlow("")
    val qrPayload: StateFlow<String> = _qrPayload.asStateFlow()

    /** device_id, сгенерированный для этого устройства */
    private val _generatedDeviceId = MutableStateFlow("")
    val generatedDeviceId: StateFlow<String> = _generatedDeviceId.asStateFlow()

    /** Статус поллинга: true = идёт опрос бэкенда */
    private val _isPolling = MutableStateFlow(false)
    val isPolling: StateFlow<Boolean> = _isPolling.asStateFlow()

    private var pollingJob: Job? = null

    // ─── Auth state ───
    private val _isRegistered = MutableStateFlow(app.credentialsStore.isRegistered)
    val isRegistered: StateFlow<Boolean> = _isRegistered.asStateFlow()

    private val _isAuthLoading = MutableStateFlow(false)
    val isAuthLoading: StateFlow<Boolean> = _isAuthLoading.asStateFlow()

    private val _authError = MutableStateFlow<String?>(null)
    val authError: StateFlow<String?> = _authError.asStateFlow()

    // ─── Collection state ───
    private val _isCollecting = MutableStateFlow(false)
    val isCollecting: StateFlow<Boolean> = _isCollecting.asStateFlow()

    private val _shiftStartTs = MutableStateFlow<Long?>(null)

    val pendingPacketsCount: StateFlow<Int> = repository
        .observeQueue(PacketPipeline.STATUS_PENDING)
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val uploadedPacketsCount: StateFlow<Int> = repository
        .observeQueue(PacketPipeline.STATUS_UPLOADED)
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val errorPacketsCount: StateFlow<Int> = repository
        .observeQueue(PacketPipeline.STATUS_ERROR)
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    init {
        if (app.credentialsStore.isRegistered) {
            // Устройство уже зарегистрировано — проверяем токен
            viewModelScope.launch {
                val result = authManager.ensureAuthenticated()
                if (result.isFailure) {
                    Log.w(TAG, "Auto-auth failed: ${result.exceptionOrNull()?.message}")
                }
            }
        } else {
            // Не зарегистрировано — генерируем QR-данные и начинаем поллинг
            generateQrPayload()
            startPolling()
        }
    }

    // ─── QR Payload Generation ───

    /**
     * Генерирует уникальный device_id из Android ID + формирует JSON для QR-кода.
     *
     * Формат QR:
     * ```json
     * {
     *   "device_id": "WT-ABCD1234",
     *   "model": "Galaxy Watch6",
     *   "firmware": "Wear OS 5",
     *   "app_version": "1.0.0"
     * }
     * ```
     */
    private fun generateQrPayload() {
        val context = getApplication<Application>()

        // Генерируем device_id из Android ID (стабильный для устройства)
        @Suppress("HardwareIds")
        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        ) ?: "UNKNOWN"

        // Формируем device_id: WT-<первые 8 символов ANDROID_ID в верхнем регистре>
        val deviceId = "WT-${androidId.take(8).uppercase()}"

        val model = Build.MODEL ?: "Unknown Watch"
        val firmware = "Wear OS ${Build.VERSION.SDK_INT}"

        val payload = JSONObject().apply {
            put("device_id", deviceId)
            put("model", model)
            put("firmware", firmware)
            put("app_version", "1.0.0")
        }.toString()

        _generatedDeviceId.value = deviceId
        _qrPayload.value = payload

        Log.d(TAG, "QR payload generated: $payload")
    }

    // ─── Registration Polling ───

    /**
     * Запускает поллинг бэкенда каждые 3 секунды.
     * Когда мобилка зарегистрирует устройство — сохраняет credentials
     * и переключает UI на StatusScreen.
     */
    fun startPolling() {
        if (pollingJob?.isActive == true) return
        if (_isRegistered.value) return

        val deviceId = _generatedDeviceId.value
        if (deviceId.isBlank()) {
            Log.w(TAG, "Cannot start polling: deviceId is empty")
            return
        }

        _isPolling.value = true
        _authError.value = null

        pollingJob = viewModelScope.launch {
            Log.d(TAG, "Starting registration polling for: $deviceId")

            while (true) {
                val result = authManager.pollRegistrationStatus(deviceId)

                result.onSuccess { registered ->
                    if (registered) {
                        Log.d(TAG, "Device registered! Proceeding to authenticate...")
                        _isPolling.value = false

                        // Получаем токены
                        _isAuthLoading.value = true
                        val authResult = authManager.authenticate()

                        if (authResult.isSuccess) {
                            Log.d(TAG, "Device authenticated successfully")
                            _isRegistered.value = true
                            HeartbeatWorker.schedule(getApplication())
                        } else {
                            _authError.value = authResult.exceptionOrNull()?.message
                                ?: "Ошибка аутентификации"
                            Log.e(TAG, "Auth failed after registration: ${_authError.value}")
                        }
                        _isAuthLoading.value = false
                        return@launch
                    }
                }

                result.onFailure { error ->
                    // Логируем, но продолжаем поллить — может быть временная ошибка сети
                    Log.w(TAG, "Poll error (will retry): ${error.message}")
                }

                delay(POLLING_INTERVAL_MS)
            }
        }
    }

    /** Останавливает поллинг */
    fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
        _isPolling.value = false
        Log.d(TAG, "Polling stopped")
    }

    override fun onCleared() {
        super.onCleared()
        stopPolling()
    }

    // ─── Сбор данных ───

    fun startCollection() {
        viewModelScope.launch {
            _shiftStartTs.value = System.currentTimeMillis()
            CollectorService.start(getApplication())
            _isCollecting.value = true
        }
    }

    fun stopCollection() {
        viewModelScope.launch {
            val startTs = _shiftStartTs.value
            val endTs = System.currentTimeMillis()
            CollectorService.stop(getApplication())
            _isCollecting.value = false

            if (startTs != null) {
                val serverKey = app.credentialsStore.serverPublicKeyPem
                val pipeline = PacketPipeline(getApplication(), repository, serverKey)
                val result = pipeline.buildAndEnqueue(startTs, endTs)
                if (result != null) {
                    Log.d(TAG, "Packet created: ${result.packetId}, size=${result.payloadSizeBytes}B")
                    UploadWorker.schedule(getApplication())
                }
            }
        }
    }

    companion object {
        private const val TAG = "StatusViewModel"
        private const val POLLING_INTERVAL_MS = 3000L
    }
}
