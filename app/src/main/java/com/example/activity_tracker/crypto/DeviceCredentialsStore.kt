package com.example.activity_tracker.crypto

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Безопасное хранилище учётных данных устройства (часов).
 *
 * Хранит:
 * - device_id, device_secret (после регистрации)
 * - access_token, refresh_token (после получения токенов)
 * - server_public_key_pem (RSA-ключ сервера для шифрования)
 * - token_expires_at (время истечения access-токена)
 *
 * Использует EncryptedSharedPreferences для надёжного хранения.
 * Если шифрование недоступно — fallback на обычные SharedPreferences (только для dev).
 */
class DeviceCredentialsStore(context: Context) {

    private val prefs: SharedPreferences = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            PREFS_FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        Log.w(TAG, "EncryptedSharedPreferences unavailable, using fallback", e)
        context.getSharedPreferences(PREFS_FILE_NAME, Context.MODE_PRIVATE)
    }

    // ─── Device ID & Secret ───

    var deviceId: String?
        get() = prefs.getString(KEY_DEVICE_ID, null)
        set(value) = prefs.edit().putString(KEY_DEVICE_ID, value).apply()

    var deviceSecret: String?
        get() = prefs.getString(KEY_DEVICE_SECRET, null)
        set(value) = prefs.edit().putString(KEY_DEVICE_SECRET, value).apply()

    // ─── Tokens ───

    var accessToken: String?
        get() = prefs.getString(KEY_ACCESS_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_ACCESS_TOKEN, value).apply()

    var refreshToken: String?
        get() = prefs.getString(KEY_REFRESH_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_REFRESH_TOKEN, value).apply()

    var tokenExpiresAt: Long
        get() = prefs.getLong(KEY_TOKEN_EXPIRES_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_TOKEN_EXPIRES_AT, value).apply()

    // ─── Server Public Key ───

    var serverPublicKeyPem: String?
        get() = prefs.getString(KEY_SERVER_PUBLIC_KEY, null)
        set(value) = prefs.edit().putString(KEY_SERVER_PUBLIC_KEY, value).apply()

    // ─── State checks ───

    val isRegistered: Boolean
        get() = !deviceId.isNullOrBlank() && !deviceSecret.isNullOrBlank()

    val hasValidToken: Boolean
        get() = !accessToken.isNullOrBlank() &&
                tokenExpiresAt > System.currentTimeMillis()

    val hasRefreshToken: Boolean
        get() = !refreshToken.isNullOrBlank()

    /**
     * Сохраняет данные регистрации устройства.
     */
    fun saveRegistration(
        deviceId: String,
        deviceSecret: String,
        serverPublicKeyPem: String
    ) {
        this.deviceId = deviceId
        this.deviceSecret = deviceSecret
        this.serverPublicKeyPem = serverPublicKeyPem
        Log.d(TAG, "Registration saved: device_id=$deviceId")
    }

    /**
     * Сохраняет токены доступа.
     * @param expiresIn время жизни токена в секундах
     */
    fun saveTokens(accessToken: String, refreshToken: String, expiresIn: Int) {
        this.accessToken = accessToken
        this.refreshToken = refreshToken
        this.tokenExpiresAt = System.currentTimeMillis() + (expiresIn * 1000L) - 60_000L
        Log.d(TAG, "Tokens saved, expires in ${expiresIn}s")
    }

    /**
     * Очищает все данные (для сброса/перерегистрации).
     */
    fun clear() {
        prefs.edit().clear().apply()
        Log.d(TAG, "Credentials cleared")
    }

    companion object {
        private const val TAG = "DeviceCredentialsStore"
        private const val PREFS_FILE_NAME = "device_credentials"

        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_DEVICE_SECRET = "device_secret"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_TOKEN_EXPIRES_AT = "token_expires_at"
        private const val KEY_SERVER_PUBLIC_KEY = "server_public_key_pem"
    }
}
