package com.example.activity_tracker.presentation.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.activity_tracker.ActivityTrackerApp
import com.example.activity_tracker.network.AuthManager
import com.example.activity_tracker.network.HeartbeatWorker
import com.example.activity_tracker.network.UploadWorker
import com.example.activity_tracker.packet.PacketPipeline
import com.example.activity_tracker.service.CollectorService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * ViewModel для управления состоянием UI:
 * - Регистрация устройства
 * - Сбор данных
 * - Статус пакетов
 */
class StatusViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as ActivityTrackerApp
    private val repository = app.samplesRepository
    private val authManager = app.authManager

    // ─── Код регистрации (для разработки вшит, в проде — от оператора) ───
    val registrationCode = "A5A15D74E0D11F33"

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
        // При запуске: если уже зарегистрированы — проверяем токен
        if (app.credentialsStore.isRegistered) {
            viewModelScope.launch {
                val result = authManager.ensureAuthenticated()
                if (result.isFailure) {
                    Log.w(TAG, "Auto-auth failed: ${result.exceptionOrNull()?.message}")
                }
            }
        }
    }

    // ─── Регистрация ───

    fun registerDevice() {
        viewModelScope.launch {
            _isAuthLoading.value = true
            _authError.value = null

            Log.d(TAG, "Registering device with code: ${registrationCode.take(4)}...")

            // Шаг 1: Регистрация
            val registerResult = authManager.register(registrationCode)

            if (registerResult.isFailure) {
                _authError.value = registerResult.exceptionOrNull()?.message ?: "Ошибка регистрации"
                _isAuthLoading.value = false
                return@launch
            }

            val deviceId = registerResult.getOrNull()
            Log.d(TAG, "Device registered: $deviceId")

            // Шаг 2: Получение токенов
            val authResult = authManager.authenticate()

            if (authResult.isFailure) {
                _authError.value = authResult.exceptionOrNull()?.message ?: "Ошибка аутентификации"
                _isAuthLoading.value = false
                return@launch
            }

            Log.d(TAG, "Device authenticated successfully")
            _isRegistered.value = true
            _isAuthLoading.value = false

            // Запускаем heartbeat
            HeartbeatWorker.schedule(getApplication())
        }
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
    }
}

