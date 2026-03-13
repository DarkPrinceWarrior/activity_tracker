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
 * Шифрование пакета смены:
 * 1. Генерируем случайный data_key (AES-256-GCM) на каждый пакет
 * 2. Шифруем контент пакета data_key
 * 3. Шифруем data_key публичным ключом сервера (RSA/OAEP)
 * 4. Передаем payload_enc + payload_key_enc + IV
 *
 * @param serverPublicKeyPem PEM-строка публичного RSA-ключа сервера
 *        (получена при регистрации устройства из DeviceCredentialsStore)
 */
class CryptoManager(
    private val serverPublicKeyPem: String? = null
) {

    /**
     * Результат подготовки пакета для отправки
     */
    data class EncryptedPacket(
        val payloadEncBase64: String,
        val payloadKeyEncBase64: String,
        val ivBase64: String,
        val payloadHashSha256: String,
        val payloadSizeBytes: Int
    )

    /**
     * MVP-режим: НЕ шифруем, только base64-кодируем JSON.
     * Сервер в текущей MVP-версии НЕ расшифровывает данные.
     * Он декодирует base64 и проверяет SHA-256 от результата.
     *
     * payload_enc  = base64(jsonBytes)
     * payload_hash = sha256(jsonBytes)
     */
    fun encrypt(plaintextJson: String): EncryptedPacket {
        val jsonBytes = plaintextJson.toByteArray(Charsets.UTF_8)

        // payload_enc = base64(jsonBytes) — БЕЗ шифрования для MVP
        val payloadEncBase64 = Base64.encodeToString(jsonBytes, Base64.NO_WRAP)

        // payload_hash = sha256(jsonBytes) — хеш от тех же самых байтов
        val payloadHash = sha256Hex(jsonBytes)

        Log.d(TAG, "Packet prepared (MVP mode): " +
                "jsonBytes=${jsonBytes.size}B, base64=${payloadEncBase64.length} chars, hash=$payloadHash")

        // Генерируем валидный 12-байтовый IV (сервер проверяет длину после base64-decode)
        val iv = generateIv()  // 12 random bytes
        val ivBase64 = Base64.encodeToString(iv, Base64.NO_WRAP)

        // Dummy payload_key_enc (сервер не расшифровывает в MVP, но поле обязательное)
        val dummyKeyEnc = Base64.encodeToString("mvp-no-encryption".toByteArray(), Base64.NO_WRAP)

        return EncryptedPacket(
            payloadEncBase64 = payloadEncBase64,
            payloadKeyEncBase64 = dummyKeyEnc,
            ivBase64 = ivBase64,
            payloadHashSha256 = payloadHash,
            payloadSizeBytes = jsonBytes.size
        )
    }

    /**
     * Полное шифрование (для прода):
     * AES-256-GCM для данных + RSA-OAEP для ключа
     */
    fun encryptFull(plaintextJson: String): EncryptedPacket {
        val plaintextBytes = plaintextJson.toByteArray(Charsets.UTF_8)

        val dataKey = generateAesKey()
        val iv = generateIv()
        val encryptedPayload = encryptAesGcm(plaintextBytes, dataKey, iv)
        val encryptedDataKey = encryptDataKey(dataKey)
        val payloadHash = sha256Hex(plaintextBytes)

        Log.d(TAG, "Packet encrypted (FULL): payload=${encryptedPayload.size}B, hash=$payloadHash")

        return EncryptedPacket(
            payloadEncBase64 = Base64.encodeToString(encryptedPayload, Base64.NO_WRAP),
            payloadKeyEncBase64 = Base64.encodeToString(encryptedDataKey, Base64.NO_WRAP),
            ivBase64 = Base64.encodeToString(iv, Base64.NO_WRAP),
            payloadHashSha256 = payloadHash,
            payloadSizeBytes = plaintextBytes.size
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
                // Пробуем RSA, потом EC
                val publicKey = try {
                    val keyFactory = KeyFactory.getInstance(RSA_ALGORITHM)
                    val keySpec = X509EncodedKeySpec(serverPublicKeyBytes)
                    keyFactory.generatePublic(keySpec)
                } catch (e: Exception) {
                    Log.w(TAG, "RSA parse failed, trying EC: ${e.message}")
                    try {
                        val keyFactory = KeyFactory.getInstance("EC")
                        val keySpec = X509EncodedKeySpec(serverPublicKeyBytes)
                        keyFactory.generatePublic(keySpec)
                    } catch (e2: Exception) {
                        Log.e(TAG, "EC parse also failed: ${e2.message}")
                        throw e  // бросаем оригинальную ошибку
                    }
                }

                Log.d(TAG, "Server public key parsed: algorithm=${publicKey.algorithm}, format=${publicKey.format}")

                if (publicKey.algorithm == "RSA") {
                    val cipher = Cipher.getInstance(RSA_OAEP_TRANSFORMATION)
                    cipher.init(Cipher.ENCRYPT_MODE, publicKey)
                    cipher.doFinal(dataKey.encoded)
                } else {
                    // Для EC/других ключей — пока заглушка, т.к. EC не поддерживает прямое шифрование
                    Log.w(TAG, "Server key is ${publicKey.algorithm}, not RSA — key encryption not supported yet")
                    "UNSUPPORTED_KEY_TYPE_${publicKey.algorithm}".toByteArray()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to encrypt data_key with server public key", e)
                "PLACEHOLDER_KEY_ENC".toByteArray()
            }
        } else {
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
     * Парсит PEM-строку публичного ключа сервера → DER (X.509) bytes.
     * Поддерживает форматы:
     *   -----BEGIN PUBLIC KEY----- (SPKI / X.509)
     *   -----BEGIN RSA PUBLIC KEY----- (PKCS#1)
     */
    private fun getServerPublicKeyBytes(): ByteArray? {
        if (serverPublicKeyPem.isNullOrBlank()) return null

        // Debug: логируем первые/последние символы для диагностики
        Log.d(TAG, "PEM key length=${serverPublicKeyPem!!.length}, " +
                "starts with: '${serverPublicKeyPem!!.take(40)}...'")

        return try {
            val base64Content = serverPublicKeyPem!!
                // Убираем все варианты PEM-заголовков
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replace("-----BEGIN RSA PUBLIC KEY-----", "")
                .replace("-----END RSA PUBLIC KEY-----", "")
                // Убираем все варианты переносов строк
                .replace("\\n", "")   // литеральная строка \n из JSON
                .replace("\n", "")    // реальный newline
                .replace("\r", "")    // carriage return
                .replace(" ", "")     // пробелы
                .replace("\t", "")    // табы
                .trim()

            Log.d(TAG, "Base64 after cleanup, length=${base64Content.length}, " +
                    "starts with: '${base64Content.take(20)}...'")

            if (base64Content.isEmpty()) {
                Log.w(TAG, "Server public key PEM is empty after stripping headers")
                return null
            }

            val decoded = Base64.decode(base64Content, Base64.DEFAULT)
            Log.d(TAG, "Decoded key bytes: ${decoded.size} bytes")
            decoded
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse server public key PEM", e)
            null
        }
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
