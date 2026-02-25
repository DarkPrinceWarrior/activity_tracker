package com.example.activity_tracker.crypto

import android.util.Base64
import android.util.Log
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Шифрование пакета смены согласно секции 9.2 плана:
 * 1. Генерируем случайный data_key (AES-256-GCM) на каждый пакет
 * 2. Шифруем контент пакета data_key
 * 3. Шифруем data_key публичным ключом сервера (RSA/OAEP)
 * 4. Передаем payload_enc + payload_key_enc + IV
 */
class CryptoManager {

    /**
     * Результат шифрования пакета
     */
    data class EncryptedPacket(
        val payloadEncBase64: String,
        val payloadKeyEncBase64: String,
        val ivBase64: String,
        val payloadHashSha256: String
    )

    /**
     * Шифрует plaintext JSON пакета
     * @param plaintextJson JSON-строка пакета
     * @return EncryptedPacket с зашифрованными данными в Base64
     */
    fun encrypt(plaintextJson: String): EncryptedPacket {
        val plaintextBytes = plaintextJson.toByteArray(Charsets.UTF_8)

        // 1. Генерация случайного AES-256 ключа для этого пакета
        val dataKey = generateAesKey()

        // 2. Шифрование контента AES-256-GCM
        val iv = generateIv()
        val encryptedPayload = encryptAesGcm(plaintextBytes, dataKey, iv)

        // 3. Шифрование data_key публичным ключом сервера (RSA-OAEP)
        val encryptedDataKey = encryptDataKey(dataKey)

        // 4. SHA-256 от исходных данных для проверки целостности на сервере
        val payloadHash = sha256Hex(plaintextBytes)

        Log.d(TAG, "Packet encrypted: payload=${encryptedPayload.size}B, hash=$payloadHash")

        return EncryptedPacket(
            payloadEncBase64 = Base64.encodeToString(encryptedPayload, Base64.NO_WRAP),
            payloadKeyEncBase64 = Base64.encodeToString(encryptedDataKey, Base64.NO_WRAP),
            ivBase64 = Base64.encodeToString(iv, Base64.NO_WRAP),
            payloadHashSha256 = payloadHash
        )
    }

    private fun generateAesKey(): SecretKey {
        val keyGen = KeyGenerator.getInstance(AES_ALGORITHM)
        keyGen.init(AES_KEY_SIZE, SecureRandom())
        return keyGen.generateKey()
    }

    private fun generateIv(): ByteArray {
        val iv = ByteArray(GCM_IV_LENGTH)
        SecureRandom().nextBytes(iv)
        return iv
    }

    private fun encryptAesGcm(
        data: ByteArray,
        key: SecretKey,
        iv: ByteArray
    ): ByteArray {
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        val parameterSpec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.ENCRYPT_MODE, key, parameterSpec)
        return cipher.doFinal(data)
    }

    private fun encryptDataKey(dataKey: SecretKey): ByteArray {
        val serverPublicKeyBytes = getServerPublicKeyBytes()

        return if (serverPublicKeyBytes != null) {
            try {
                val keyFactory = KeyFactory.getInstance(RSA_ALGORITHM)
                val keySpec = X509EncodedKeySpec(serverPublicKeyBytes)
                val publicKey = keyFactory.generatePublic(keySpec)

                val cipher = Cipher.getInstance(RSA_OAEP_TRANSFORMATION)
                cipher.init(Cipher.ENCRYPT_MODE, publicKey)
                cipher.doFinal(dataKey.encoded)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to encrypt data_key with server public key", e)
                // Fallback: возвращаем key_enc как заглушку
                "PLACEHOLDER_KEY_ENC".toByteArray()
            }
        } else {
            // TODO: Заменить заглушку на реальный публичный ключ сервера
            Log.w(TAG, "Server public key not configured — using placeholder")
            "PLACEHOLDER_KEY_ENC_NO_SERVER_KEY".toByteArray()
        }
    }

    private fun sha256Hex(data: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(data)
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Возвращает публичный ключ сервера в DER-формате (X.509).
     * TODO: Заменить null на реальный ключ после получения от бэкенда.
     * Способы передачи ключа:
     *   1. Вшить как Base64-константу после получения от сервера
     *   2. Загрузить при первой регистрации устройства с сервера
     *   3. Передать через шлюз при старте смены (GATEWAY-режим)
     */
    private fun getServerPublicKeyBytes(): ByteArray? {
        // TODO: вставить Base64-encoded DER публичный ключ сервера
        // Пример когда ключ будет получен:
        // val base64Key = "MIIBIjANBgkqhki..."
        // return Base64.decode(base64Key, Base64.DEFAULT)
        return null
    }

    companion object {
        private const val TAG = "CryptoManager"

        private const val AES_ALGORITHM = "AES"
        private const val AES_KEY_SIZE = 256
        private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_LENGTH_BITS = 128

        private const val RSA_ALGORITHM = "RSA"
        private const val RSA_OAEP_TRANSFORMATION = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding"
    }
}
