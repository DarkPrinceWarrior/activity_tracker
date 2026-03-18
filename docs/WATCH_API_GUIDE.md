# Watch Backend — Руководство по API для разработчика Samsung Galaxy Watch 8

> **Base URL**: `https://<server>/api/v1`
> **Формат данных**: JSON (`Content-Type: application/json`)
> **Даты**: ISO 8601 (`2026-03-17T09:00:00Z`), timestamp-поля — миллисекунды Unix
> **Платформа часов**: Samsung Galaxy Watch 8, Wear OS 5, Kotlin

---

## Что делают часы в этой системе

Часы — это **сенсорное устройство**, которое:
1. Собирает данные с датчиков (акселерометр, гироскоп, барометр, пульсометр, BLE-маяки и т.д.)
2. Шифрует собранные данные (AES-GCM + RSA-OAEP)
3. Отправляет зашифрованные пакеты на сервер (`POST /watch/packets`)
4. Периодически шлёт heartbeat (`POST /watch/heartbeat`)

Часы **НЕ управляют** привязками, справочниками, аналитикой — это задача мобильного приложения оператора или веб-панели.

---

## Оглавление

1. [Два режима работы часов](#1-два-режима-работы-часов)
2. [Аутентификация устройства](#2-аутентификация-устройства)
3. [Heartbeat — пульс устройства](#3-heartbeat--пульс-устройства)
4. [Шифрование и подготовка пакета](#4-шифрование-и-подготовка-пакета)
5. [Отправка пакета данных](#5-отправка-пакета-данных)
6. [Проверка статуса пакета](#6-проверка-статуса-пакета)
7. [Echo — проверка отправленных данных](#7-echo--проверка-отправленных-данных)
8. [Формат Payload (сенсорные данные)](#8-формат-payload-сенсорные-данные)
9. [Перечисления (Enums)](#9-перечисления-enums)
10. [Rate Limiting](#10-rate-limiting)
11. [Общий формат ошибок](#11-общий-формат-ошибок)
12. [Полные flow для часов](#12-полные-flow-для-часов)
13. [Kotlin-рекомендации для Wear OS](#13-kotlin-рекомендации-для-wear-os)

---

## 1. Два режима работы часов

| Режим | Описание | Как часы попадают в систему |
|-------|----------|---------------------------|
| **Автономный (Direct)** | Часы самостоятельно регистрируются по одноразовому коду и отправляют данные напрямую на сервер через Wi-Fi/LTE | `POST /auth/device/register` |
| **Через шлюз (Gateway)** | Оператор регистрирует часы через мобильное приложение, данные передаются через телефон по Bluetooth | Часы вызывают `GET /auth/device/{id}/registration-status` для получения секрета |

> **Для разработчика часов**: в обоих режимах API отправки данных одинаковый (`POST /watch/packets`). Разница только в регистрации.

---

## 2. Аутентификация устройства

**Prefix**: `/api/v1/auth/device`

Часы используют **Device Token** — отдельный JWT, не связанный с пользователем.

### Жизненный цикл токенов

```
Регистрация → Получение секрета → Получение токенов → Работа → Обновление токенов
     ↓                                                              ↓
  (один раз)                                              (по refresh_token)
```

---

### `POST /auth/device/register`
Регистрация часов по одноразовому коду (для автономного режима). **Rate limit**: 3/мин.

**Request:**
```json
{
  "registration_code": "ABC123",
  "device_id": "WATCH-001",
  "model": "Samsung Galaxy Watch 8",
  "firmware": "1.2.0",
  "app_version": "2.0.1",
  "employee_id": "uuid | null",
  "site_id": "SITE-01 | null",
  "timezone": "Europe/Moscow"
}
```

| Поле | Тип | Обязательное | Описание |
|------|-----|:---:|----------|
| `registration_code` | `string` | ✅ | Одноразовый код от администратора |
| `device_id` | `string` (1-64) | ✅ | Уникальный ID часов (рекомендуется формат `WATCH-XXX`) |
| `model` | `string` (max 64) | ❌ | Модель устройства |
| `firmware` | `string` (max 64) | ❌ | Версия прошивки Wear OS |
| `app_version` | `string` (max 32) | ❌ | Версия приложения на часах |
| `employee_id` | `UUID` | ❌ | ID сотрудника (если известен) |
| `site_id` | `string` (1-64) | ❌ | ID площадки |
| `timezone` | `string` | ❌ | Часовой пояс (IANA) |

**Response** `200`:
```json
{
  "device_id": "WATCH-001",
  "device_secret": "generated_secret_string",
  "server_public_key_pem": "-----BEGIN PUBLIC KEY-----\n...",
  "server_time": "2026-03-17T09:00:00Z"
}
```

> ⚠️ **КРИТИЧНО**: `device_secret` выдаётся **ОДИН РАЗ**. Сохраните его в **EncryptedSharedPreferences** или **Android Keystore** на часах! При утере — устройство нужно перерегистрировать.

> ⚠️ **КРИТИЧНО**: `server_public_key_pem` — публичный RSA-ключ сервера. Он нужен для шифрования каждого пакета. Тоже сохраните!

---

### `GET /auth/device/{device_id}/registration-status`
Проверить статус регистрации и **получить секрет** (одноразово). Используется в режиме Gateway, когда оператор зарегистрировал часы через мобильное приложение.

**Response** `200` (первый вызов — секрет доступен):
```json
{
  "registered": true,
  "device_secret": "generated_secret_string",
  "server_public_key_pem": "-----BEGIN PUBLIC KEY-----\n...",
  "server_time": "2026-03-17T09:00:00Z"
}
```

**Response** `200` (повторный вызов — секрет уже выдан):
```json
{
  "registered": true,
  "device_secret": null,
  "server_public_key_pem": null,
  "server_time": null
}
```

**Response** `200` (устройство не найдено):
```json
{
  "registered": false
}
```

> ⚠️ `device_secret` и `server_public_key_pem` возвращаются **только при первом вызове**. После чтения секрет стирается на сервере.

---

### `POST /auth/device/token`
Получить access + refresh токены. **Rate limit**: 5/мин.

**Request:**
```json
{
  "device_id": "WATCH-001",
  "device_secret": "saved_secret"
}
```

**Response** `200`:
```json
{
  "access_token": "jwt_access_token",
  "refresh_token": "jwt_refresh_token",
  "expires_in": 3600,
  "server_time": "2026-03-17T09:00:00Z"
}
```

> Используйте `expires_in` для планирования refresh. Рекомендуется обновлять за **5 минут** до истечения.

---

### `POST /auth/device/refresh`
Обновить пару токенов по refresh-токену.

**Request:**
```json
{
  "device_id": "WATCH-001",
  "refresh_token": "current_refresh_token"
}
```

**Response** `200`: аналогично `DeviceTokenResponse`.

---

### `POST /auth/device/revoke`
Отозвать токены устройства (при деактивации часов).

**Request:**
```json
{
  "device_id": "WATCH-001"
}
```

**Response** `200`:
```json
{
  "status": "revoked"
}
```

---

## 3. Heartbeat — пульс устройства

### `POST /watch/heartbeat`
Часы периодически сообщают серверу о своём состоянии. **Рекомендуется: каждые 1-5 минут** во время активной работы.

**Что делает сервер:**
- Обновляет `last_heartbeat_at` и `last_sync_at` устройства
- Обновляет `battery_level` и `app_version`
- Возвращает точное серверное время для синхронизации часов
- Проверяет, что устройство активно

**Request:**

| Поле | Тип | Обязательное | Валидация | Описание |
|------|-----|:---:|-----------|----------|
| `device_id` | `string` | ✅ | 1-64 символа | ID часов |
| `device_time_ms` | `int` | ✅ | — | Текущее время на часах (Unix ms) |
| `battery_level` | `float` | ✅ | 0.0 – 100.0 | Уровень заряда (%) |
| `is_collecting` | `bool` | ✅ | — | Идёт ли сейчас сбор данных |
| `pending_packets` | `int` | ✅ | ≥ 0 | Кол-во пакетов в очереди на отправку |
| `app_version` | `string` | ✅ | 1-64 символа | Версия приложения на часах |

```json
{
  "device_id": "WATCH-001",
  "device_time_ms": 1710662400000,
  "battery_level": 85.5,
  "is_collecting": true,
  "pending_packets": 3,
  "app_version": "2.0.1"
}
```

**Response** `200`:

| Поле | Тип | Описание |
|------|-----|----------|
| `server_time` | `datetime` | Серверное время (ISO 8601) |
| `server_time_ms` | `int` | Серверное время (Unix ms) |
| `time_offset_ms` | `int` | Разница: `server_time_ms - device_time_ms`. Используйте для коррекции timestamps |
| `commands` | `list[dict]` | Команды от сервера (зарезервировано, сейчас `[]`) |

```json
{
  "server_time": "2026-03-17T10:00:00Z",
  "server_time_ms": 1710662400000,
  "time_offset_ms": 150,
  "commands": []
}
```

**Ошибки:**
| Код | Причина |
|-----|---------|
| `403` | Устройство не активно (`suspended` / `revoked`) |
| `404` | `device_id` не найден |
| `409` | Внутренняя ошибка при обновлении |
| `422` | Невалидные данные (например, `battery_level > 100`) |

> **Совет для Galaxy Watch 8**: Используйте `time_offset_ms` для коррекции timestamps в пакетах данных. Сохраняйте последний `time_offset_ms` в памяти и применяйте ко всем `ts_ms` перед отправкой.

---

## 4. Шифрование и подготовка пакета

### Шаг 1: Сформировать payload (JSON)

Payload — это JSON-объект со всеми сенсорными данными за смену. Подробная структура — в [разделе 8](#8-формат-payload-сенсорные-данные).

### Шаг 2: Шифрование

```
1. payload_raw = JSON.stringify(payload).toByteArray(UTF-8)
2. payload_hash = SHA-256(payload_raw).toHexString()       // 64 hex символа
3. aes_key = generateAESKey(256 бит)                       // случайный ключ
4. iv = generateIV(12 байт)                                // случайный IV (GCM)
5. payload_encrypted = AES-GCM.encrypt(payload_raw, aes_key, iv)
6. aes_key_encrypted = RSA-OAEP-SHA256.encrypt(aes_key, server_public_key)
7. payload_enc = Base64.encode(payload_encrypted)
8. payload_key_enc = Base64.encode(aes_key_encrypted)
9. iv = Base64.encode(iv)                                  // 12 байт → 16 символов base64
```

> `server_public_key` — публичный ключ, полученный при регистрации.

### Kotlin-реализация шифрования для Wear OS

```kotlin
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

data class EncryptedPacket(
    val payloadEnc: String,
    val payloadKeyEnc: String,
    val iv: String,
    val payloadHash: String,
    val payloadSizeBytes: Int
)

fun encryptPayload(payloadJson: String, serverPublicKeyPem: String): EncryptedPacket {
    val payloadBytes = payloadJson.toByteArray(Charsets.UTF_8)

    // 1. SHA-256 хеш
    val digest = MessageDigest.getInstance("SHA-256")
    val hash = digest.digest(payloadBytes).joinToString("") { "%02x".format(it) }

    // 2. Генерация AES-256 ключа
    val keyGen = KeyGenerator.getInstance("AES")
    keyGen.init(256, SecureRandom())
    val aesKey = keyGen.generateKey()

    // 3. Генерация IV (12 байт для GCM)
    val ivBytes = ByteArray(12)
    SecureRandom().nextBytes(ivBytes)

    // 4. AES-GCM шифрование payload
    val aesCipher = Cipher.getInstance("AES/GCM/NoPadding")
    aesCipher.init(Cipher.ENCRYPT_MODE, aesKey, GCMParameterSpec(128, ivBytes))
    val encryptedPayload = aesCipher.doFinal(payloadBytes)

    // 5. RSA-OAEP шифрование AES-ключа
    val publicKeyPem = serverPublicKeyPem
        .replace("-----BEGIN PUBLIC KEY-----", "")
        .replace("-----END PUBLIC KEY-----", "")
        .replace("\n", "")
    val publicKeyBytes = Base64.decode(publicKeyPem, Base64.DEFAULT)
    val publicKey = KeyFactory.getInstance("RSA")
        .generatePublic(X509EncodedKeySpec(publicKeyBytes))
    val rsaCipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
    rsaCipher.init(Cipher.ENCRYPT_MODE, publicKey)
    val encryptedKey = rsaCipher.doFinal(aesKey.encoded)

    return EncryptedPacket(
        payloadEnc = Base64.encodeToString(encryptedPayload, Base64.NO_WRAP),
        payloadKeyEnc = Base64.encodeToString(encryptedKey, Base64.NO_WRAP),
        iv = Base64.encodeToString(ivBytes, Base64.NO_WRAP),
        payloadHash = hash,
        payloadSizeBytes = payloadBytes.size
    )
}
```

### Шаг 3: Валидация на сервере

Сервер при получении проверяет:
- `payload_enc` — валидный base64
- `payload_key_enc` — валидный base64
- `iv` — валидный base64, **ровно 12 байт** после декодирования
- `payload_hash` — строго **64 hex-символа** (SHA-256)
- `shift_start_ts < shift_end_ts`
- `packet_id` — валидный UUID
- `device_id` — устройство существует и активно

---

## 5. Отправка пакета данных

### 🔑 Idempotency-Key

**Обязательный HTTP-заголовок** для предотвращения дублирования пакетов.

- `Idempotency-Key` **ДОЛЖЕН совпадать с `packet_id`** в теле запроса
- `packet_id` — **валидный UUID** (v4 рекомендуется)
- Сервер хранит ключ **24 часа**
- При повторной отправке — кэшированный ответ (без повторной обработки)

```kotlin
val packetId = UUID.randomUUID().toString()
// Idempotency-Key = packet_id!
request.addHeader("Idempotency-Key", packetId)
```

### `POST /watch/packets`
Отправить пакет данных. **Rate limit**: 10/мин. **Авторизация**: Device Token.

**Headers:**
| Заголовок | Обязательный | Описание |
|-----------|:---:|----------|
| `Authorization` | ✅ | `Bearer <device_access_token>` |
| `Idempotency-Key` | ✅ | UUID пакета, **совпадает с `packet_id`** |
| `x-device-id` | ❌ | Проверка совпадения с `device_id` в body |
| `Content-Type` | ✅ | `application/json` |

**Request:**
```json
{
  "packet_id": "550e8400-e29b-41d4-a716-446655440000",
  "device_id": "WATCH-001",
  "shift_start_ts": 1710658800,
  "shift_end_ts": 1710702000,
  "schema_version": 1,
  "payload_enc": "base64(AES-GCM-encrypted-JSON)",
  "payload_key_enc": "base64(RSA-OAEP-encrypted-AES-key)",
  "iv": "base64(12-byte-IV)",
  "payload_hash": "sha256hex(original-JSON-bytes)",
  "payload_size_bytes": 45000
}
```

| Поле | Тип | Обязательное | Описание |
|------|-----|:---:|----------|
| `packet_id` | `string` | ✅ | UUID v4, длина 36-64 |
| `device_id` | `string` | ✅ | ID часов (1-64 символа) |
| `shift_start_ts` | `int` | ✅ | Начало смены, Unix timestamp (сек или мс) |
| `shift_end_ts` | `int` | ✅ | Конец смены, > `shift_start_ts` |
| `schema_version` | `int` | ✅ | Версия формата данных (≥ 1) |
| `payload_enc` | `string` | ✅ | Base64 зашифрованный payload |
| `payload_key_enc` | `string` | ✅ | Base64 AES-ключ, зашифрованный RSA |
| `iv` | `string` | ✅ | Base64 IV (12 байт в сыром виде) |
| `payload_hash` | `string` | ✅ | SHA-256 хеш исходного payload, 64 hex |
| `payload_size_bytes` | `int\|null` | ❌ | Размер исходного payload в байтах |

**Response** `202`:
```json
{
  "packet_id": "550e8400-e29b-41d4-a716-446655440000",
  "status": "accepted",
  "received_at": "2026-03-17T09:00:00Z",
  "server_time": "2026-03-17T09:00:00Z"
}
```

> ⚠️ **`202 Accepted` ≠ успешная обработка!** Пакет обрабатывается **асинхронно**:
> `accepted` → `decrypting` → `parsing` → `processing` → `processed` (или `error`)

**Ошибки:**
| Код | Причина |
|-----|---------|
| `400` | `Idempotency-Key` отсутствует / не совпадает с `packet_id`, невалидный base64/iv/hash, `shift_start_ts >= shift_end_ts` |
| `403` | Устройство не активно (`suspended` / `revoked`) |
| `404` | Устройство не найдено |
| `409` | Пакет с таким `packet_id` уже существует |
| `429` | Превышен rate-limit (10/мин) |

---

## 6. Проверка статуса пакета

### `GET /watch/packets/{packet_id}`
Узнать статус обработки. **Авторизация**: Device Token. **Header**: `x-device-id` — обязательный.

**Response** `200`:
```json
{
  "packet_id": "550e8400...",
  "status": "processed"
}
```

> **Рекомендация**: polling с интервалом **5 секунд**, максимум **60 секунд**. Если статус не `processed`/`error` за минуту — логируйте предупреждение.

---

## 7. Echo — проверка отправленных данных

### `GET /watch/packets/{packet_id}/echo`
Вернуть отправленные данные обратно (для отладки). **Header**: `x-device-id` — обязательный.

**Response** `200`:
```json
{
  "packet_id": "...",
  "device_id": "WATCH-001",
  "shift_start_ts": 1710658800,
  "shift_end_ts": 1710702000,
  "schema_version": 1,
  "payload_enc": "...",
  "payload_key_enc": "...",
  "iv": "...",
  "payload_hash": "...",
  "payload_size_bytes": 45000,
  "status": "processed",
  "uploaded_from": "direct",
  "received_at": "2026-03-17T09:00:00Z"
}
```

---

## 8. Формат Payload (сенсорные данные)

Payload — JSON-объект, который шифруется перед отправкой.

> Бэкенд поддерживает и `snake_case`, и `camelCase` (`ts_ms` / `tsMs`, `beacon_id` / `beaconId`).

```json
{
  "schema_version": 1,
  "device": {
    "model": "Samsung Galaxy Watch 8",
    "firmware": "1.2.0",
    "app_version": "2.0.1",
    "timezone": "Europe/Moscow"
  },
  "shift": {
    "start_ts_ms": 1710658800000,
    "end_ts_ms": 1710702000000
  },
  "time_sync": {
    "server_time_offset_ms": 150
  },
  "samples": {
    "accel": [
      { "ts_ms": 1710658801000, "x": 0.12, "y": -9.78, "z": 0.03, "quality": 1.0 }
    ],
    "gyro": [
      { "ts_ms": 1710658801000, "x": 0.01, "y": -0.02, "z": 0.005, "quality": 1.0 }
    ],
    "baro": [
      { "ts_ms": 1710658860000, "hpa": 1013.25 }
    ],
    "mag": [
      { "ts_ms": 1710658801000, "x": 25.5, "y": -12.3, "z": 40.1 }
    ],
    "heart_rate": [
      { "ts_ms": 1710658860000, "bpm": 72, "confidence": 0.95 }
    ],
    "ble_events": [
      { "ts_ms": 1710659000000, "beacon_id": "AA:BB:CC:DD:EE:FF", "rssi": -65 }
    ],
    "wear_events": [
      { "ts_ms": 1710658800000, "state": "on_wrist" }
    ],
    "battery_events": [
      { "ts_ms": 1710658800000, "level": 85.5 }
    ],
    "downtime_reasons": [
      { "ts_ms": 1710670000000, "reason_id": "WAIT_MAT", "zone_id": "uuid" }
    ]
  }
}
```

### Описание сенсоров

| Сенсор | Поля | Samsung API | Частота |
|--------|------|-------------|---------|
| `accel` | `ts_ms, x, y, z, quality` | `Sensor.TYPE_ACCELEROMETER` | 1 Hz рекомендуется |
| `gyro` | `ts_ms, x, y, z, quality` | `Sensor.TYPE_GYROSCOPE` | 1 Hz |
| `baro` | `ts_ms, hpa` | `Sensor.TYPE_PRESSURE` | 1/мин |
| `mag` | `ts_ms, x, y, z` | `Sensor.TYPE_MAGNETIC_FIELD` | По необходимости |
| `heart_rate` | `ts_ms, bpm, confidence` | `Sensor.TYPE_HEART_RATE` | 1/мин |
| `ble_events` | `ts_ms, beacon_id, rssi` | `BluetoothLeScanner` | По обнаружению |
| `wear_events` | `ts_ms, state` | `Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT` | По событию |
| `battery_events` | `ts_ms, level` | `BatteryManager` | 1/30 мин |
| `downtime_reasons` | `ts_ms, reason_id, zone_id` | UI (ввод пользователя) | По событию |

### Wear events — возможные состояния

| `state` | Описание |
|---------|----------|
| `on_wrist` | Часы на руке |
| `off_wrist` | Часы сняты |

---

## 9. Перечисления (Enums)

```kotlin
enum class DeviceStatus(val value: String) {
    ACTIVE("active"),
    REVOKED("revoked"),
    SUSPENDED("suspended")
}

enum class PacketStatus(val value: String) {
    ACCEPTED("accepted"),
    DECRYPTING("decrypting"),
    PARSING("parsing"),
    PROCESSING("processing"),
    PROCESSED("processed"),
    ERROR("error")
}
```

---

## 10. Rate Limiting

| Эндпоинт | Лимит |
|----------|-------|
| `POST /auth/device/register` | 3/мин |
| `POST /auth/device/token` | 5/мин |
| `POST /watch/packets` | 10/мин |

При превышении: `429 Too Many Requests`. Реализуйте **exponential backoff** с jitter.

---

## 11. Общий формат ошибок

```json
{
  "detail": "Описание ошибки"
}
```

Для ошибок валидации (422):
```json
{
  "detail": [
    {
      "loc": ["body", "device_id"],
      "msg": "field required",
      "type": "value_error.missing"
    }
  ]
}
```

| HTTP-код | Значение |
|----------|----------|
| `400` | Невалидные данные запроса |
| `401` | Не авторизован (токен отсутствует/невалиден/x-device-id отсутствует) |
| `403` | Устройство suspended/revoked |
| `404` | Ресурс не найден |
| `409` | Конфликт (дубликат пакета, устройство уже зарегистрировано) |
| `422` | Ошибка валидации Pydantic |
| `429` | Превышен rate-limit |
| `500` | Внутренняя ошибка сервера |

---

## 12. Полные flow для часов

### Flow 1: Автономные часы (Direct Upload) — основной

```
┌──────────────────────────────────────────────────────────────────┐
│                    ОДНОРАЗОВАЯ НАСТРОЙКА                         │
├──────────────────────────────────────────────────────────────────┤
│                                                                  │
│  1. Регистрация по коду                                          │
│     POST /auth/device/register                                   │
│     → Сохранить: device_secret + server_public_key_pem           │
│                                                                  │
│  2. Получение токенов                                            │
│     POST /auth/device/token                                      │
│     → Сохранить: access_token + refresh_token                    │
│                                                                  │
└──────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────┐
│                    РАБОЧИЙ ЦИКЛ (каждая смена)                   │
├──────────────────────────────────────────────────────────────────┤
│                                                                  │
│  3. Периодический heartbeat (каждые 1-5 мин)                     │
│     POST /watch/heartbeat                                        │
│     → Сохранить time_offset_ms для коррекции timestamps          │
│                                                                  │
│  4. Сбор сенсорных данных в буфер                                │
│     (акселерометр, гироскоп, пульс, BLE и т.д.)                 │
│                                                                  │
│  5. Формирование + шифрование payload                            │
│     AES-GCM + RSA-OAEP                                          │
│                                                                  │
│  6. Отправка пакета                                              │
│     POST /watch/packets                                          │
│     Headers: Idempotency-Key = packet_id                         │
│                                                                  │
│  7. Проверка статуса (polling, 5 сек × 12 раз)                   │
│     GET /watch/packets/{packet_id}                               │
│     → status = "processed" → ОК                                  │
│     → status = "error" → логировать, пересоздать пакет           │
│                                                                  │
│  8. Обновление токенов (по необходимости)                        │
│     POST /auth/device/refresh                                    │
│                                                                  │
└──────────────────────────────────────────────────────────────────┘
```

### Flow 2: Часы через шлюз (Gateway)

```
┌──────────────────────────────────────────────────────────────────┐
│                    ОДНОРАЗОВАЯ НАСТРОЙКА                         │
├──────────────────────────────────────────────────────────────────┤
│                                                                  │
│  1. Оператор регистрирует часы через мобильное приложение        │
│     (мобильное вызывает POST /auth/device/register-via-mobile)   │
│                                                                  │
│  2. Часы запрашивают секрет                                      │
│     GET /auth/device/{device_id}/registration-status             │
│     → Сохранить: device_secret + server_public_key_pem           │
│                                                                  │
│  3. Получение токенов                                            │
│     POST /auth/device/token                                      │
│                                                                  │
└──────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────┐
│           РЕЖИМ ШЛЮЗА (данные через Bluetooth → телефон)          │
├──────────────────────────────────────────────────────────────────┤
│                                                                  │
│  4. Часы собирают данные и передают их по BLE на телефон          │
│     (реализация BLE-протокола — на стороне часов)                │
│                                                                  │
│  5. Телефон шифрует и отправляет через                            │
│     POST /gateway/packets (с binding_id!)                        │
│                                                                  │
│  Часы также могут слать heartbeat напрямую:                       │
│     POST /watch/heartbeat                                        │
│                                                                  │
└──────────────────────────────────────────────────────────────────┘
```

---

## 13. Kotlin-рекомендации для Wear OS

### HTTP-клиент: Ktor (рекомендуется для Wear OS)

```kotlin
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*

val httpClient = HttpClient(OkHttp) {
    install(ContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        })
    }
    defaultRequest {
        url("https://<server>/api/v1/")
        header("Content-Type", "application/json")
    }
}
```

### Token Manager

```kotlin
class DeviceTokenManager(private val context: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        context, "watch_tokens",
        MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    var accessToken: String?
        get() = prefs.getString("access_token", null)
        set(value) = prefs.edit().putString("access_token", value).apply()

    var refreshToken: String?
        get() = prefs.getString("refresh_token", null)
        set(value) = prefs.edit().putString("refresh_token", value).apply()

    var deviceSecret: String?
        get() = prefs.getString("device_secret", null)
        set(value) = prefs.edit().putString("device_secret", value).apply()

    var serverPublicKey: String?
        get() = prefs.getString("server_public_key", null)
        set(value) = prefs.edit().putString("server_public_key", value).apply()

    var expiresAt: Long
        get() = prefs.getLong("expires_at", 0)
        set(value) = prefs.edit().putLong("expires_at", value).apply()

    fun isTokenExpired(): Boolean =
        System.currentTimeMillis() > expiresAt - 5 * 60 * 1000 // за 5 мин до истечения
}
```

### Retry с exponential backoff

```kotlin
suspend fun <T> retryWithBackoff(
    maxRetries: Int = 3,
    initialDelayMs: Long = 1000,
    maxDelayMs: Long = 30000,
    block: suspend () -> T
): T {
    var currentDelay = initialDelayMs
    repeat(maxRetries - 1) { attempt ->
        try {
            return block()
        } catch (e: Exception) {
            if (e is HttpException && e.code() in listOf(400, 401, 403, 404, 409)) {
                throw e // не ретраим клиентские ошибки
            }
        }
        val jitter = (0..currentDelay / 2).random()
        delay(currentDelay + jitter)
        currentDelay = (currentDelay * 2).coerceAtMost(maxDelayMs)
    }
    return block() // последняя попытка
}
```

### WorkManager для фоновой отправки

```kotlin
class PacketUploadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val packetDao = AppDatabase.getInstance(applicationContext).packetDao()
        val pendingPackets = packetDao.getPendingPackets()

        for (packet in pendingPackets) {
            try {
                val response = api.submitPacket(
                    idempotencyKey = packet.packetId,
                    deviceId = packet.deviceId,
                    body = packet.toRequest()
                )
                packetDao.markSent(packet.packetId, response.status)
            } catch (e: HttpException) {
                if (e.code() == 409) {
                    // Дубликат — пакет уже на сервере, помечаем как отправленный
                    packetDao.markSent(packet.packetId, "duplicate")
                } else if (e.code() == 429) {
                    return Result.retry() // rate-limit → повторить позже
                } else {
                    throw e
                }
            }
        }
        return Result.success()
    }
}

// Запуск:
val uploadWork = PeriodicWorkRequestBuilder<PacketUploadWorker>(
    15, TimeUnit.MINUTES
).setConstraints(
    Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()
).build()

WorkManager.getInstance(context)
    .enqueueUniquePeriodicWork("packet_upload", ExistingPeriodicWorkPolicy.KEEP, uploadWork)
```

### Особенности Galaxy Watch 8

| Аспект | Рекомендация |
|--------|-------------|
| **Батарея** | Используйте `SensorManager.SENSOR_DELAY_NORMAL`, минимизируйте частоту сбора |
| **Сеть** | Wi-Fi может быть недоступен — используйте `WorkManager` с `NetworkType.CONNECTED` |
| **Хранение** | Буферизуйте данные в Room DB до отправки, часы имеют ограниченную память |
| **Фон** | Используйте `ForegroundService` с Notification для длительного сбора |
| **Энкрипция секретов** | Только `EncryptedSharedPreferences` или `Android Keystore` |
| **BLE-сканирование** | `ScanSettings.SCAN_MODE_LOW_POWER` для экономии батареи |
| **Heartbeat** | Используйте `AlarmManager` с `ELAPSED_REALTIME` для надёжного интервала |

---

## Что хранить на часах

| Данные | Где хранить | Когда сохранять |
|--------|-------------|----------------|
| `device_id` | EncryptedSharedPreferences | После регистрации |
| `device_secret` | EncryptedSharedPreferences | После регистрации (ОДИН РАЗ!) |
| `server_public_key_pem` | EncryptedSharedPreferences | После регистрации |
| `access_token` | EncryptedSharedPreferences | После получения/обновления токенов |
| `refresh_token` | EncryptedSharedPreferences | После получения/обновления токенов |
| `time_offset_ms` | SharedPreferences | После каждого heartbeat |
| Сенсорные данные | Room DB | Непрерывно во время смены |
| Неотправленные пакеты | Room DB | До успешной отправки |
