package com.example.activity_tracker.presentation.viewmodel

import android.app.Application
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.activity_tracker.ActivityTrackerApp
import com.example.activity_tracker.network.BindingPoller
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
 * - Автоматический сбор данных (через BindingPoller — без кнопок start/stop)
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

    /** device_id для отображения на StatusScreen */
    val deviceId: StateFlow<String> = MutableStateFlow(
        app.credentialsStore.deviceId ?: ""
    )

    private val _isAuthLoading = MutableStateFlow(false)
    val isAuthLoading: StateFlow<Boolean> = _isAuthLoading.asStateFlow()

    private val _authError = MutableStateFlow<String?>(null)
    val authError: StateFlow<String?> = _authError.asStateFlow()

    // ─── Ready state (для splash screen) ───
    // Становится true после завершения init{} — MainActivity убирает splash
    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    // ─── Collection state ───
    private val _isCollecting = MutableStateFlow(false)
    val isCollecting: StateFlow<Boolean> = _isCollecting.asStateFlow()

    private val _shiftStartTs = MutableStateFlow<Long?>(null)

    // ─── BindingPoller state ───
    private var bindingPoller: BindingPoller? = null

    /** Текущее состояние поллера (для отображения в UI) */
    private val _pollerState = MutableStateFlow(BindingPoller.PollerState.SLEEPING)
    val pollerState: StateFlow<BindingPoller.PollerState> = _pollerState.asStateFlow()

    /** Текущий активный binding_id */
    private val _activeBindingId = MutableStateFlow<String?>(null)
    val activeBindingId: StateFlow<String?> = _activeBindingId.asStateFlow()

    /** Заряжаются ли часы */
    private val _isCharging = MutableStateFlow(false)
    val isChargingState: StateFlow<Boolean> = _isCharging.asStateFlow()

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
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            if (app.credentialsStore.isRegistered) {
                // Устройство уже зарегистрировано — проверяем токен в фоне
                val result = authManager.ensureAuthenticated()
                if (result.isFailure) {
                    Log.w(TAG, "Auto-auth failed: ${result.exceptionOrNull()?.message}")
                }
                // Запускаем BindingPoller
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    startBindingPoller()
                }
            } else {
                // Не зарегистрировано — генерируем QR-данные и начинаем поллинг
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    generateQrPayload()
                    startPolling()
                }
            }
            // Инициализация завершена — разрешаем скрыть splash
            _isReady.value = true
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
     *   "firmware": "Wear OS 34",
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
            put("app_version", getAppVersion())
        }.toString()

        _generatedDeviceId.value = deviceId
        _qrPayload.value = payload

        Log.d(TAG, "QR payload generated: $payload")
    }

    // ─── Registration Polling ───

    /**
     * Запускает поллинг бэкенда каждые 3 секунды.
     * Когда мобилка зарегистрирует устройство — сохраняет credentials
     * и переключает UI на StatusScreen + запускает BindingPoller.
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
                            // Запускаем BindingPoller после успешной регистрации
                            startBindingPoller()
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
        bindingPoller?.stop()
    }

    // ─── BindingPoller ───

    /**
     * Запускает BindingPoller для автоматического управления сбором данных.
     * Вызывается после успешной регистрации и аутентификации.
     */
    private fun startBindingPoller() {
        if (bindingPoller != null) {
            Log.d(TAG, "BindingPoller already running")
            return
        }

        val poller = BindingPoller(
            context = getApplication(),
            authManager = authManager,
            credentialsStore = app.credentialsStore,
            scope = viewModelScope
        )

        // Когда обнаружен активный binding → автостарт сбора
        poller.onBindingStarted = { bindingId ->
            Log.d(TAG, "Binding started: $bindingId → auto-starting collection")
            _activeBindingId.value = bindingId
            startCollectionAuto()
        }

        // Когда binding закрыт → автостоп сбора
        poller.onBindingStopped = { bindingId ->
            Log.d(TAG, "Binding stopped: $bindingId → auto-stopping collection")
            _activeBindingId.value = null
            stopCollectionAuto()
        }

        // Следим за состоянием поллера
        viewModelScope.launch {
            poller.state.collect { state ->
                _pollerState.value = state
            }
        }

        viewModelScope.launch {
            poller.isCharging.collect { charging ->
                _isCharging.value = charging
            }
        }

        poller.start()
        bindingPoller = poller
        Log.d(TAG, "BindingPoller started")
    }

    // ─── Сброс устройства ───

    /**
     * Полный сброс часов: очистка credentials, остановка сбора.
     * Часы возвращаются на экран QR-регистрации.
     * Используется при переезде часов на другую площадку.
     */
    fun resetDevice() {
        viewModelScope.launch {
            Log.w(TAG, "Device reset initiated")

            // 1. Останавливаем BindingPoller
            bindingPoller?.stop()
            bindingPoller = null

            // 2. Останавливаем сбор если работает
            if (_isCollecting.value) {
                CollectorService.stop(getApplication())
                _isCollecting.value = false
                _shiftStartTs.value = null
                _activeBindingId.value = null
            }

            // 3. Очищаем все credentials
            app.credentialsStore.clear()

            // 4. Возвращаем на QR-экран
            _isRegistered.value = false

            // 5. Генерируем новый QR и начинаем поллинг
            generateQrPayload()
            startPolling()

            Log.d(TAG, "Device reset complete — showing QR screen")
        }
    }

    // ─── Автоматический сбор данных ───

    /**
     * Автоматический старт сбора при обнаружении активного binding.
     * Вызывается из BindingPoller.onBindingStarted.
     */
    private fun startCollectionAuto() {
        viewModelScope.launch {
            if (_isCollecting.value) {
                Log.d(TAG, "Collection already active, ignoring duplicate start")
                return@launch
            }
            val startTs = System.currentTimeMillis()
            _shiftStartTs.value = startTs
            CollectorService.start(getApplication(), startTs)
            _isCollecting.value = true
            Log.d(TAG, "Collection auto-started at $startTs")
        }
    }

    /**
     * Автоматический стоп сбора при закрытии binding.
     * Вызывается из BindingPoller.onBindingStopped.
     */
    private fun stopCollectionAuto() {
        viewModelScope.launch {
            if (!_isCollecting.value) {
                Log.d(TAG, "Collection not active, ignoring stop")
                return@launch
            }
            val endTs = System.currentTimeMillis()
            CollectorService.stop(getApplication(), endTs)
            _isCollecting.value = false
            _shiftStartTs.value = null
            Log.d(TAG, "Collection auto-stopped at $endTs")
        }
    }

    companion object {
        private const val TAG = "StatusViewModel"
        private const val POLLING_INTERVAL_MS = 3000L
    }

    private fun getAppVersion(): String = try {
        getApplication<Application>().packageManager
            .getPackageInfo(getApplication<Application>().packageName, 0)
            .versionName ?: "1.0.0"
    } catch (_: Exception) {
        "1.0.0"
    }
}
