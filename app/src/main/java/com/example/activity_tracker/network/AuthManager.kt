package com.example.activity_tracker.network

import android.content.Context
import android.os.Build
import android.util.Log
import com.example.activity_tracker.crypto.DeviceCredentialsStore
import com.example.activity_tracker.network.model.DeviceRefreshRequest
import com.example.activity_tracker.network.model.DeviceRegisterRequest
import com.example.activity_tracker.network.model.DeviceTokenRequest
import com.example.activity_tracker.network.model.HeartbeatRequest
import com.example.activity_tracker.network.model.HeartbeatResponse
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.TimeZone

/**
 * Оркестрирует аутентификацию устройства (часов):
 * - QR-flow: показывает QR → поллит статус → получает credentials
 * - Legacy-flow: регистрация по одноразовому коду
 * - Получение access/refresh токенов
 * - Автоматическое обновление при истечении
 * - Heartbeat
 *
 * QR-flow жизненный цикл:
 * 1. generateDeviceId() → device_id для QR
 * 2. pollRegistrationStatus(device_id) → device_secret (от мобилки через бэкенд)
 * 3. authenticate() → access_token + refresh_token
 * 4. getAccessToken() → валидный токен (с auto-refresh)
 * 5. sendHeartbeat() → служебный сигнал
 */
class AuthManager(
    private val context: Context,
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

    // ─── QR-flow: Поллинг регистрации ───

    /**
     * Поллит бэкенд для проверки, зарегистрировано ли устройство мобилкой.
     * Вызывается периодически, пока мобилка не завершит регистрацию.
     *
     * @param deviceId ID устройства (из QR-кода)
     * @return Result.success(true) если зарегистрировано и credentials сохранены,
     *         Result.success(false) если ещё не зарегистрировано,
     *         Result.failure() при ошибке сети
     */
    suspend fun pollRegistrationStatus(deviceId: String): Result<Boolean> {
        return try {
            val response = authService.getRegistrationStatus(deviceId)

            when {
                response.isSuccessful -> {
                    val body = response.body()!!
                    if (body.registered && body.device_secret != null) {
                        // Мобилка зарегистрировала → сохраняем credentials
                        credentialsStore.saveRegistration(
                            deviceId = deviceId,
                            deviceSecret = body.device_secret,
                            serverPublicKeyPem = body.server_public_key_pem ?: ""
                        )
                        Log.d(TAG, "Device registered via mobile: $deviceId")
                        Result.success(true)
                    } else if (body.registered) {
                        // Зарегистрировано, но secret уже выдан ранее
                        // Проверяем — может, у нас уже есть credentials
                        if (credentialsStore.isRegistered) {
                            Result.success(true)
                        } else {
                            Log.w(TAG, "Device registered but secret already claimed")
                            Result.failure(AuthException("Секрет устройства уже был получен", 0))
                        }
                    } else {
                        // Ещё не зарегистрировано — продолжаем поллить
                        Result.success(false)
                    }
                }
                response.code() == 404 -> {
                    // Устройство не найдено — ещё не зарегистрировано
                    Result.success(false)
                }
                else -> {
                    Result.failure(
                        AuthException("Ошибка поллинга: ${response.code()}", response.code())
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Poll registration status failed", e)
            Result.failure(e)
        }
    }

    // ─── Legacy: Регистрация по коду ───

    /**
     * Регистрирует устройство на бэкенде по одноразовому коду (legacy-flow).
     * Вызывается один раз при первом запуске.
     *
     * @param registrationCode код от администратора
     * @return true если успешно
     */
    suspend fun register(registrationCode: String): Result<String> {
        Log.d(TAG, "Registering device with code: ${registrationCode.take(4)}...")

        return try {
            val request = DeviceRegisterRequest(
                registration_code = registrationCode,
                model = Build.MODEL ?: "Unknown Watch",
                firmware = Build.VERSION.RELEASE ?: "Unknown",
                app_version = getAppVersion(),
                timezone = TimeZone.getDefault().id
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
        pendingPackets: Int,
        appVersion: String = "1.0.0"
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
                pending_packets = pendingPackets,
                app_version = appVersion
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
                Result.failure(AuthException("Устройство не зарегистрировано. Покажите QR-код оператору.", 0))
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

    private fun getAppVersion(): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0.0"
    } catch (_: Exception) {
        "1.0.0"
    }
}
