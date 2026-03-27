package com.example.activity_tracker.network

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import com.example.activity_tracker.crypto.DeviceCredentialsStore
import com.example.activity_tracker.network.model.BindingResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Адаптивный polling привязок (bindings) с сервера.
 *
 * Часы автоматически определяют наличие активной привязки
 * и управляют сбором данных без участия рабочего.
 *
 * Интервалы polling'а:
 * - На зарядке, нет binding → POLLING OFF (спим)
 * - Не на зарядке, нет binding → 5 сек (ожидание привязки)
 * - Сбор активен → 30 сек (проверяем закрытие binding)
 * - Только что остановлен → 5 сек на 3 мин (возможна передача)
 */
class BindingPoller(
    private val context: Context,
    private val authManager: AuthManager,
    private val credentialsStore: DeviceCredentialsStore,
    private val bindingApi: BindingApiService = NetworkClient.bindingApiService,
    private val scope: CoroutineScope
) {

    // ─── Состояние ───

    enum class PollerState {
        SLEEPING,           // На зарядке, без binding → не поллим
        WAITING_FOR_BINDING, // Не на зарядке, ждём привязку → 5 сек
        COLLECTING,         // Активный binding → 30 сек
        JUST_STOPPED        // Только остановлен → 5 сек на 3 мин
    }

    private val _state = MutableStateFlow(PollerState.SLEEPING)
    val state: StateFlow<PollerState> = _state.asStateFlow()

    /** Текущий активный binding_id (null если нет привязки) */
    private val _activeBindingId = MutableStateFlow<String?>(null)
    val activeBindingId: StateFlow<String?> = _activeBindingId.asStateFlow()

    /** Текущее состояние зарядки */
    private val _isCharging = MutableStateFlow(false)
    val isCharging: StateFlow<Boolean> = _isCharging.asStateFlow()

    /** Ошибка последнего poll'а (для отладки) */
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    /** Callback при обнаружении нового binding */
    var onBindingStarted: ((bindingId: String) -> Unit)? = null

    /** Callback при закрытии binding */
    var onBindingStopped: ((bindingId: String) -> Unit)? = null

    private var pollingJob: Job? = null
    private var justStoppedJob: Job? = null
    private var chargingReceiver: BroadcastReceiver? = null

    // ─── Lifecycle ───

    /**
     * Запускает поллер. Начинает отслеживать зарядку и polling binding'ов.
     */
    fun start() {
        registerChargingReceiver()
        updateChargingState()
        updatePollerState()
        Log.d(TAG, "BindingPoller started, charging=${_isCharging.value}")
    }

    /**
     * Полностью останавливает поллер.
     */
    fun stop() {
        pollingJob?.cancel()
        pollingJob = null
        justStoppedJob?.cancel()
        justStoppedJob = null
        unregisterChargingReceiver()
        Log.d(TAG, "BindingPoller stopped")
    }

    // ─── Отслеживание зарядки ───

    private fun registerChargingReceiver() {
        if (chargingReceiver != null) return

        chargingReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val wasCharging = _isCharging.value
                updateChargingState()
                if (wasCharging != _isCharging.value) {
                    Log.d(TAG, "Charging changed: ${_isCharging.value}")
                    updatePollerState()
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        context.registerReceiver(chargingReceiver, filter)
    }

    private fun unregisterChargingReceiver() {
        chargingReceiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Charging receiver already unregistered")
            }
            chargingReceiver = null
        }
    }

    private fun updateChargingState() {
        val batteryStatus = context.registerReceiver(
            null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
        val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        _isCharging.value = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
    }

    // ─── Управление polling state ───

    private fun updatePollerState() {
        val newState = when {
            _activeBindingId.value != null -> PollerState.COLLECTING
            _state.value == PollerState.JUST_STOPPED -> PollerState.JUST_STOPPED
            _isCharging.value -> PollerState.SLEEPING
            else -> PollerState.WAITING_FOR_BINDING
        }

        if (newState != _state.value) {
            Log.d(TAG, "State: ${_state.value} → $newState")
            _state.value = newState
        }

        restartPolling()
    }

    private fun restartPolling() {
        pollingJob?.cancel()

        val interval = when (_state.value) {
            PollerState.SLEEPING -> {
                Log.d(TAG, "Sleeping (charging) — polling OFF")
                return // Не поллим
            }
            PollerState.WAITING_FOR_BINDING -> INTERVAL_WAITING_MS
            PollerState.COLLECTING -> INTERVAL_COLLECTING_MS
            PollerState.JUST_STOPPED -> INTERVAL_WAITING_MS
        }

        pollingJob = scope.launch {
            Log.d(TAG, "Polling started with interval ${interval}ms")
            while (true) {
                pollBindingStatus()
                delay(interval)
            }
        }
    }

    // ─── Polling ───

    private suspend fun pollBindingStatus() {
        val deviceId = credentialsStore.deviceId
        if (deviceId.isNullOrBlank()) {
            Log.w(TAG, "Cannot poll: deviceId is null")
            return
        }

        try {
            val accessToken = authManager.getAccessToken()
            val response = bindingApi.getBindings(
                authorization = "Bearer $accessToken",
                deviceId = deviceId,
                status = "active",
                pageSize = 1
            )

            if (response.isSuccessful) {
                val body = response.body()
                val activeBinding = body?.items?.firstOrNull {
                    it.status == "active"
                }
                _lastError.value = null
                handleBindingResult(activeBinding)
            } else if (response.code() == 401) {
                // Токен протух — обновляем
                Log.w(TAG, "Poll got 401, refreshing token...")
                authManager.refreshAccessToken()
            } else {
                _lastError.value = "HTTP ${response.code()}"
                Log.w(TAG, "Poll error: HTTP ${response.code()}")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _lastError.value = e.message
            Log.w(TAG, "Poll failed: ${e.message}")
        }
    }

    private fun handleBindingResult(activeBinding: BindingResponse?) {
        val currentBindingId = _activeBindingId.value

        when {
            // Новый binding обнаружен (не было → стало)
            activeBinding != null && currentBindingId == null -> {
                Log.d(TAG, "Active binding found: ${activeBinding.id}")
                _activeBindingId.value = activeBinding.id
                justStoppedJob?.cancel()
                _state.value = PollerState.COLLECTING
                restartPolling()
                onBindingStarted?.invoke(activeBinding.id)
            }

            // Binding закрыт / удалён (было → не стало)
            activeBinding == null && currentBindingId != null -> {
                Log.d(TAG, "Binding closed: $currentBindingId")
                val closedId = currentBindingId
                _activeBindingId.value = null
                enterJustStoppedState()
                onBindingStopped?.invoke(closedId)
            }

            // Тот же binding — ничего не делаем (idempotency)
            activeBinding != null && activeBinding.id == currentBindingId -> {
                // OK, binding всё ещё активен
            }

            // Другой binding (переход без закрытия? маловероятно)
            activeBinding != null && activeBinding.id != currentBindingId -> {
                Log.w(TAG, "Binding changed: $currentBindingId → ${activeBinding.id}")
                val oldId = currentBindingId!!
                _activeBindingId.value = activeBinding.id
                onBindingStopped?.invoke(oldId)
                onBindingStarted?.invoke(activeBinding.id)
            }

            // Нет binding'а, и не было — продолжаем ждать
            else -> { /* no-op */ }
        }
    }

    /**
     * Переходит в состояние JUST_STOPPED на 3 минуты.
     * Если за это время новый binding не обнаружен — переходит
     * в WAITING или SLEEPING в зависимости от зарядки.
     */
    private fun enterJustStoppedState() {
        _state.value = PollerState.JUST_STOPPED
        restartPolling()

        justStoppedJob?.cancel()
        justStoppedJob = scope.launch {
            delay(JUST_STOPPED_DURATION_MS)
            if (_activeBindingId.value == null) {
                Log.d(TAG, "JUST_STOPPED timeout → checking charging state")
                updatePollerState()
            }
        }
    }

    /**
     * Вызывается при смене зарядки /  сбросе устройства.
     * Принудительно пересчитывает состояние.
     */
    fun forceUpdateState() {
        updateChargingState()
        updatePollerState()
    }

    companion object {
        private const val TAG = "BindingPoller"

        /** Интервал в режиме ожидания привязки (5 сек) */
        private const val INTERVAL_WAITING_MS = 5_000L

        /** Интервал при активном сборе (30 сек) */
        private const val INTERVAL_COLLECTING_MS = 30_000L

        /** Длительность состояния JUST_STOPPED (3 мин) */
        private const val JUST_STOPPED_DURATION_MS = 3 * 60 * 1000L
    }
}
