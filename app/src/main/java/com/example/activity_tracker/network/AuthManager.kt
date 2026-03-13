package com.example.activity_tracker.network

import android.util.Log
import com.example.activity_tracker.crypto.DeviceCredentialsStore
import com.example.activity_tracker.network.model.DeviceRefreshRequest
import com.example.activity_tracker.network.model.DeviceRegisterRequest
import com.example.activity_tracker.network.model.DeviceTokenRequest
import com.example.activity_tracker.network.model.HeartbeatRequest
import com.example.activity_tracker.network.model.HeartbeatResponse
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Оркестрирует аутентификацию устройства (часов):
 * - Регистрация по одноразовому коду (первый запуск)
 * - Получение access/refresh токенов
 * - Автоматическое обновление при истечении
 * - Heartbeat
 *
 * Жизненный цикл:
 * 1. register(code) → device_id + device_secret
 * 2. authenticate()  → access_token + refresh_token
 * 3. getAccessToken() → возвращает валидный токен (с auto-refresh)
 * 4. sendHeartbeat() → периодический сигнал
 */
class AuthManager(
    private val credentialsStore: DeviceCredentialsStore,
    private val authService: WatchAuthService = NetworkClient.watchAuthService
) {

    private val refreshMutex = Mutex()

    enum class AuthState {
        NOT_REGISTERED,   // Нет device_id — нужна регистрация
        REGISTERED,       // Есть device_id, но нет токенов
        AUTHENTICATED,    // Есть валидный access_token
        TOKEN_EXPIRED     // Токен истёк, нужен refresh
    }

    val currentState: AuthState
        get() = when {
            !credentialsStore.isRegistered -> AuthState.NOT_REGISTERED
            credentialsStore.hasValidToken -> AuthState.AUTHENTICATED
            credentialsStore.hasRefreshToken -> AuthState.TOKEN_EXPIRED
            else -> AuthState.REGISTERED
        }

    val isReady: Boolean
        get() = credentialsStore.hasValidToken

    val deviceId: String?
        get() = credentialsStore.deviceId

    // ─── Регистрация ───

    /**
     * Регистрирует устройство на бэкенде по одноразовому коду.
     * Вызывается один раз при первом запуске.
     *
     * @param registrationCode код от администратора
     * @return true если успешно
     */
    suspend fun register(registrationCode: String): Result<String> {
        Log.d(TAG, "Registering device with code: ${registrationCode.take(4)}...")

        return try {
            val request = DeviceRegisterRequest(
                registration_code = registrationCode
            )

            val response = authService.register(request)

            when {
                response.isSuccessful -> {
                    val body = response.body()!!
                    credentialsStore.saveRegistration(
                        deviceId = body.device_id,
                        deviceSecret = body.device_secret,
                        serverPublicKeyPem = body.server_public_key_pem
                    )
                    Log.d(TAG, "Device registered: ${body.device_id}")
                    Result.success(body.device_id)
                }
                response.code() == 404 -> {
                    Result.failure(AuthException("Код регистрации не найден", response.code()))
                }
                response.code() == 409 -> {
                    Result.failure(AuthException("Код уже использован", response.code()))
                }
                response.code() == 410 -> {
                    Result.failure(AuthException("Код регистрации истёк", response.code()))
                }
                else -> {
                    Result.failure(AuthException("Ошибка регистрации: ${response.code()}", response.code()))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Registration failed", e)
            Result.failure(e)
        }
    }

    // ─── Получение токенов ───

    /**
     * Получает access и refresh токены.
     * Вызывается после регистрации.
     */
    suspend fun authenticate(): Result<Unit> {
        val deviceId = credentialsStore.deviceId
        val deviceSecret = credentialsStore.deviceSecret

        if (deviceId == null || deviceSecret == null) {
            return Result.failure(AuthException("Устройство не зарегистрировано", 0))
        }

        Log.d(TAG, "Authenticating device: $deviceId")

        return try {
            val request = DeviceTokenRequest(
                device_id = deviceId,
                device_secret = deviceSecret
            )

            val response = authService.getToken(request)

            when {
                response.isSuccessful -> {
                    val body = response.body()!!
                    credentialsStore.saveTokens(
                        accessToken = body.access_token,
                        refreshToken = body.refresh_token,
                        expiresIn = body.expires_in
                    )
                    Log.d(TAG, "Authenticated, token expires in ${body.expires_in}s")
                    Result.success(Unit)
                }
                response.code() == 401 -> {
                    Result.failure(AuthException("Неверный device_secret", response.code()))
                }
                response.code() == 403 -> {
                    Result.failure(AuthException("Устройство заблокировано (REVOKED)", response.code()))
                }
                else -> {
                    Result.failure(AuthException("Ошибка аутентификации: ${response.code()}", response.code()))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Authentication failed", e)
            Result.failure(e)
        }
    }

    // ─── Refresh ───

    /**
     * Обновляет access-токен по refresh-токену.
     * Потокобезопасно (Mutex): при параллельных запросах обновление происходит один раз.
     */
    suspend fun refreshAccessToken(): Result<Unit> = refreshMutex.withLock {
        // Перепроверяем после захвата мьютекса (другой поток мог уже обновить)
        if (credentialsStore.hasValidToken) {
            return@withLock Result.success(Unit)
        }

        val deviceId = credentialsStore.deviceId
        val refreshToken = credentialsStore.refreshToken

        if (deviceId == null || refreshToken == null) {
            return@withLock Result.failure(AuthException("Нет данных для refresh", 0))
        }

        Log.d(TAG, "Refreshing token for device: $deviceId")

        return@withLock try {
            val request = DeviceRefreshRequest(
                device_id = deviceId,
                refresh_token = refreshToken
            )

            val response = authService.refreshToken(request)

            when {
                response.isSuccessful -> {
                    val body = response.body()!!
                    credentialsStore.saveTokens(
                        accessToken = body.access_token,
                        refreshToken = body.refresh_token,
                        expiresIn = body.expires_in
                    )
                    Log.d(TAG, "Token refreshed, expires in ${body.expires_in}s")
                    Result.success(Unit)
                }
                response.code() == 401 -> {
                    // Refresh-токен невалиден — нужна полная переаутентификация
                    Log.w(TAG, "Refresh token invalid, re-authenticating")
                    authenticate()
                }
                else -> {
                    Result.failure(AuthException("Ошибка refresh: ${response.code()}", response.code()))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Token refresh failed", e)
            Result.failure(e)
        }
    }

    // ─── Get valid token ───

    /**
     * Возвращает валидный access-токен.
     * Автоматически обновляет при необходимости.
     *
     * @throws AuthException если невозможно получить токен
     */
    suspend fun getAccessToken(): String {
        // Если токен валиден — возвращаем
        credentialsStore.accessToken?.let { token ->
            if (credentialsStore.hasValidToken) return token
        }

        // Пытаемся обновить
        val refreshResult = refreshAccessToken()
        if (refreshResult.isSuccess) {
            return credentialsStore.accessToken
                ?: throw AuthException("Token is null after refresh", 0)
        }

        throw refreshResult.exceptionOrNull() as? AuthException
            ?: AuthException("Не удалось получить токен", 0)
    }

    /**
     * Возвращает device_id или бросает исключение.
     */
    fun getDeviceIdOrThrow(): String {
        return credentialsStore.deviceId
            ?: throw AuthException("Устройство не зарегистрировано", 0)
    }

    // ─── Heartbeat ───

    /**
     * Отправляет heartbeat на сервер.
     * @param batteryLevel уровень заряда 0.0–1.0
     * @param isCollecting идёт ли сбор данных
     * @param pendingPackets количество неотправленных пакетов
     */
    suspend fun sendHeartbeat(
        batteryLevel: Float,
        isCollecting: Boolean,
        pendingPackets: Int
    ): Result<HeartbeatResponse> {
        val deviceId = credentialsStore.deviceId
            ?: return Result.failure(AuthException("Устройство не зарегистрировано", 0))

        return try {
            // Получаем access token (с auto-refresh)
            val accessToken = getAccessToken()

            val request = HeartbeatRequest(
                device_id = deviceId,
                device_time_ms = System.currentTimeMillis(),
                battery_level = batteryLevel,
                is_collecting = isCollecting,
                pending_packets = pendingPackets
            )

            val response = authService.heartbeat(
                authorization = "Bearer $accessToken",
                request = request
            )

            when {
                response.isSuccessful -> {
                    val body = response.body()!!
                    Log.d(TAG, "Heartbeat OK, time_offset=${body.time_offset_ms}ms")
                    Result.success(body)
                }
                response.code() == 401 -> {
                    // Токен протух — обновляем и пробуем ещё раз
                    Log.w(TAG, "Heartbeat 401, refreshing token...")
                    val refreshResult = refreshAccessToken()
                    if (refreshResult.isSuccess) {
                        val newToken = getAccessToken()
                        val retryResponse = authService.heartbeat(
                            authorization = "Bearer $newToken",
                            request = request
                        )
                        if (retryResponse.isSuccessful) {
                            Result.success(retryResponse.body()!!)
                        } else {
                            Result.failure(AuthException("Heartbeat retry failed: ${retryResponse.code()}", retryResponse.code()))
                        }
                    } else {
                        Result.failure(AuthException("Token refresh failed for heartbeat", 401))
                    }
                }
                response.code() == 403 -> {
                    Result.failure(AuthException("Устройство REVOKED", response.code()))
                }
                response.code() == 404 -> {
                    Result.failure(AuthException("Устройство не найдено на сервере", response.code()))
                }
                else -> {
                    Result.failure(AuthException("Heartbeat error: ${response.code()}", response.code()))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Heartbeat failed", e)
            Result.failure(e)
        }
    }

    // ─── Full init ───

    /**
     * Полная инициализация: если устройство зарегистрировано,
     * но нет токена — получает его автоматически.
     */
    suspend fun ensureAuthenticated(): Result<Unit> {
        return when (currentState) {
            AuthState.NOT_REGISTERED -> {
                Result.failure(AuthException("Устройство не зарегистрировано. Нужен registration_code.", 0))
            }
            AuthState.REGISTERED -> {
                authenticate()
            }
            AuthState.TOKEN_EXPIRED -> {
                refreshAccessToken()
            }
            AuthState.AUTHENTICATED -> {
                Result.success(Unit)
            }
        }
    }

    /**
     * Сбрасывает все credentials (для перерегистрации).
     */
    fun reset() {
        credentialsStore.clear()
        Log.d(TAG, "Auth state reset")
    }

    class AuthException(message: String, val httpCode: Int) : Exception(message)

    companion object {
        private const val TAG = "AuthManager"
    }
}
