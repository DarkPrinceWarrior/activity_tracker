# 🔗 Гайд: Интеграция Wear OS часов с Backend

> **Дата:** 13.03.2026  
> **Backend:** FastAPI (watch-backend), HTTP  
> **Часы:** Wear OS (Kotlin, Jetpack Compose)  
> **Текущий адрес:** `http://10.228.38.66:8000/api/v1/`

---

## 📑 Оглавление

1. [Общая архитектура](#1-общая-архитектура)
2. [Подготовка к работе](#2-подготовка-к-работе)
3. [Регистрация устройства](#3-регистрация-устройства)
4. [Аутентификация (токены)](#4-аутентификация-токены)
5. [Heartbeat (сигнал жизни)](#5-heartbeat-сигнал-жизни)
6. [Отправка пакета данных](#6-отправка-пакета-данных)
7. [Формат payload (внутри payload_enc)](#7-формат-payload-внутри-payload_enc)
8. [MVP-режим шифрования](#8-mvp-режим-шифрования)
9. [Обработка ошибок и retry](#9-обработка-ошибок-и-retry)
10. [Полная последовательность запросов](#10-полная-последовательность-запросов)
11. [Проверка на бэкенде](#11-проверка-на-бэкенде)
12. [Файлы в проекте](#12-файлы-в-проекте)
13. [FAQ / Частые проблемы](#13-faq--частые-проблемы)

---

## 1. Общая архитектура

```
┌──────────────────┐          HTTP (JSON)          ┌──────────────────┐
│   Wear OS часы   │ ─────────────────────────────▶ │   FastAPI Backend │
│  (эмулятор/реал) │                                │  :8000/api/v1/   │
└──────────────────┘                                └──────────────────┘
        │                                                    │
        │  Данные идут НАПРЯМУЮ                             │
        │  (без мобильного телефона)                        ▼
        │                                            ┌────────────┐
        │                                            │ PostgreSQL │
        │                                            └────────────┘
        │
        ▼
  ┌──────────────────┐
  │  Мобилка (опц.)  │ ← получает данные ОТ бэкенда
  │  (отдельное приложение) │    через свои API
  └──────────────────┘
```

**Ключевой момент:** Часы отправляют данные **напрямую** на бэкенд по Wi-Fi. Мобильное приложение — это отдельный клиент, который читает данные с бэкенда.

---

## 2. Подготовка к работе

### 2.1. Получить registration_code

Перед тем как часы смогут зарегистрироваться, нужно сгенерировать одноразовый код на бэкенде:

```bash
# 1. Залогиниться как admin
curl -X POST http://10.228.38.66:8000/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email": "admin@company.com", "password": "adminpass"}'
```

Ответ:
```json
{
  "access_token": "eyJ...",
  "token_type": "bearer"
}
```

```bash
# 2. Сгенерировать код регистрации
curl -X POST http://10.228.38.66:8000/api/v1/admin/registration-codes \
  -H "Authorization: Bearer eyJ..." \
  -H "Content-Type: application/json"
```

Ответ:
```json
{
  "items": [
    {
      "code": "B15AF3DD37D6E1D5",
      "expires_at": "2026-04-12T13:20:54.597058Z"
    }
  ]
}
```

### 2.2. Вшить код в приложение часов

В файле `StatusViewModel.kt`:
```kotlin
val registrationCode = "B15AF3DD37D6E1D5"
```

> **Важно:** Код одноразовый. После использования нужно генерировать новый.

### 2.3. Настроить URL бэкенда

В файле `NetworkClient.kt`:
```kotlin
const val BASE_URL = "http://10.228.38.66:8000/api/v1/"
```

### 2.4. Разрешить HTTP (для разработки)

В `AndroidManifest.xml`:
```xml
<application
    android:usesCleartextTraffic="true"
    ...>
```

---

## 3. Регистрация устройства

**Endpoint:** `POST /api/v1/auth/device/register`  
**Вызывается:** один раз, при первом запуске приложения  
**Rate limit:** 3 запроса/мин

### Запрос:

```json
POST /api/v1/auth/device/register
Content-Type: application/json

{
  "registration_code": "B15AF3DD37D6E1D5",
  "model": "Galaxy Watch 8",
  "firmware": "Wear OS 5",
  "app_version": "1.0.0",
  "timezone": "Europe/Moscow"
}
```

### Ответ (201 Created):

```json
{
  "device_id": "device-bdb08d2479e6bb16",
  "device_secret": "a1b2c3d4e5f6...",
  "server_public_key_pem": "-----BEGIN PUBLIC KEY-----\nMIIBIjAN...\n-----END PUBLIC KEY-----",
  "server_time": "2026-03-13T13:52:57.123456Z"
}
```

### Что сохраняется на часах:

| Поле | Хранилище | Назначение |
|------|-----------|------------|
| `device_id` | EncryptedSharedPreferences | Идентификатор устройства на сервере |
| `device_secret` | EncryptedSharedPreferences | Секрет для получения токенов |
| `server_public_key_pem` | EncryptedSharedPreferences | RSA-ключ сервера (для шифрования в проде) |

### Kotlin-код (AuthManager):

```kotlin
val response = authService.register(
    DeviceRegisterRequest(
        registration_code = registrationCode,
        model = "Galaxy Watch 8",
        firmware = "Wear OS 5",
        app_version = "1.0.0",
        timezone = "Europe/Moscow"
    )
)
// Сохраняем credentials
credentialsStore.saveRegistration(
    deviceId = response.device_id,
    deviceSecret = response.device_secret,
    serverPublicKeyPem = response.server_public_key_pem
)
```

### Ошибки:

| Код | Причина |
|-----|---------|
| 404 | Код не найден |
| 409 | Код уже использован |
| 410 | Код истёк |
| 429 | Rate limit |

---

## 4. Аутентификация (токены)

### 4.1. Получение токенов

**Endpoint:** `POST /api/v1/auth/device/token`  
**Вызывается:** после регистрации и при каждом запуске приложения  
**Rate limit:** 5 запросов/мин

```json
POST /api/v1/auth/device/token
Content-Type: application/json

{
  "device_id": "device-bdb08d2479e6bb16",
  "device_secret": "a1b2c3d4e5f6..."
}
```

Ответ:
```json
{
  "access_token": "kgZik_wlX7mnoxk...",
  "refresh_token": "ref_token_here...",
  "expires_in": 3600,
  "server_time": "2026-03-13T13:53:00.000Z"
}
```

### 4.2. Обновление access_token

**Endpoint:** `POST /api/v1/auth/device/refresh`  
**Вызывается:** автоматически, когда access_token истекает (за 1 мин до expiry)

```json
POST /api/v1/auth/device/refresh
Content-Type: application/json

{
  "device_id": "device-bdb08d2479e6bb16",
  "refresh_token": "ref_token_here..."
}
```

Ответ: такой же как у `/token`.

### Логика на часах:

```
if (tokenExpiresAt > now) → используем текущий access_token
else → вызываем /auth/device/refresh
    if (refresh failed) → вызываем /auth/device/token с device_secret
```

---

## 5. Heartbeat (сигнал жизни)

**Endpoint:** `POST /api/v1/watch/heartbeat`  
**Вызывается:** каждые 15 минут (через WorkManager)  
**Требует:** `Authorization: Bearer <access_token>`

### Запрос:

```json
POST /api/v1/watch/heartbeat
Content-Type: application/json

{
  "device_id": "device-bdb08d2479e6bb16",
  "device_time_ms": 1773410094597,
  "battery_level": 85.0,
  "is_collecting": true,
  "pending_packets": 0,
  "app_version": "1.0.0"
}
```

### Ответ:

```json
{
  "server_time": "2026-03-13T14:00:00.000Z",
  "server_time_ms": 1773410400000,
  "time_offset_ms": -305403,
  "commands": []
}
```

### Что бэкенд видит по heartbeat:

- Устройство «живо»
- Уровень батареи
- Идёт ли сбор данных
- Сколько пакетов ожидают отправки
- Разницу времени (для синхронизации)

---

## 6. Отправка пакета данных

**Endpoint:** `POST /api/v1/watch/packets`  
**Вызывается:** после остановки сбора данных (нажатие "Остановить")  
**Требует:** Bearer token + обязательные заголовки

### Заголовки:

```
Authorization: Bearer <access_token>
Idempotency-Key: <packet_id>          ← ОБЯЗАН совпадать с packet_id в теле!
x-device-id: <device_id>              ← для дополнительной валидации
Content-Type: application/json
```

### Запрос (тело):

```json
{
  "packet_id": "bd68e381-5403-4060-b55a-b141d6ca89b6",
  "device_id": "device-bdb08d2479e6bb16",
  "shift_start_ts": 1773410094597,
  "shift_end_ts": 1773410952342,
  "schema_version": 1,
  "payload_enc": "eyJkZXZpY2UiOnsi...",
  "payload_key_enc": "bXZwLW5vLWVuY3J5cHRpb24=",
  "iv": "dG9rUkFiZlFN...",
  "payload_hash": "52d367c09d9bf034e629575a3be1c7e6...",
  "payload_size_bytes": 140000
}
```

### Описание полей:

| Поле | Тип | Описание |
|------|-----|----------|
| `packet_id` | string (UUID) | Уникальный ID пакета |
| `device_id` | string | ID устройства с сервера |
| `shift_start_ts` | long | Начало смены, Unix ms |
| `shift_end_ts` | long | Конец смены, Unix ms |
| `schema_version` | int | Версия схемы (сейчас `1`) |
| `payload_enc` | string | **base64(JSON-байты)** — закодированные данные |
| `payload_key_enc` | string | base64 ключа шифрования (MVP: не используется) |
| `iv` | string | base64 от 12 байт IV (MVP: случайные байты) |
| `payload_hash` | string | **sha256(JSON-байты)** в hex |
| `payload_size_bytes` | int | Размер оригинальных JSON-байт |

### Ответ (202 Accepted):

```json
{
  "packet_id": "bd68e381-5403-4060-b55a-b141d6ca89b6",
  "status": "accepted",
  "received_at": "2026-03-13T14:09:34.123Z",
  "server_time": "2026-03-13T14:09:34.123Z"
}
```

### Ответ при дубликате (409 Conflict):

```json
{
  "packet_id": "bd68e381-5403-4060-b55a-b141d6ca89b6",
  "status": "accepted"
}
```

> **Часы считают 409 = успех** (пакет уже принят ранее).

### Ошибки:

| Код | Причина | Retry? |
|-----|---------|--------|
| 202 | Принят ✅ | — |
| 400 | Невалидные данные (hash mismatch, iv length и т.д.) | ❌ |
| 401 | Токен истёк → часы делают refresh и retry | ✅ |
| 403 | Устройство заблокировано | ❌ |
| 404 | Устройство не найдено | ❌ |
| 409 | Дубликат (считается успехом) | — |
| 422 | Ошибка валидации | ❌ |
| 5xx | Ошибка сервера | ✅ |

---

## 7. Формат payload (внутри payload_enc)

После `base64_decode(payload_enc)` получается JSON следующей структуры:

```json
{
  "schema_version": 1,
  "packet_id": "bd68e381-5403-4060-b55a-b141d6ca89b6",
  "device": {
    "device_id": "e3012260-3685-4e55-acc5-42b2a9b081f5",
    "model": "unknown sdk_gwear_x86_64",
    "fw": "16",
    "app_version": "1.0",
    "tz": "GMT"
  },
  "shift": {
    "start_ts_ms": 1773410094597,
    "end_ts_ms": 1773410952342
  },
  "time_sync": {
    "server_time_offset_ms": 0,
    "server_time_ms": 0
  },
  "samples": {
    "accel": [
      {
        "ts_ms": 1773410915097,
        "x": 0.0,
        "y": 9.776321,
        "z": 0.812345,
        "quality": 1.0
      }
      // ... ~25 замеров/сек
    ],
    "gyro": [
      {
        "ts_ms": 1773410915097,
        "x": 0.001,
        "y": -0.002,
        "z": 0.003,
        "quality": 1.0
      }
    ],
    "baro": [
      {
        "ts_ms": 1773410915500,
        "hpa": 1013.25
      }
    ],
    "mag": [
      {
        "ts_ms": 1773410915500,
        "x": 25.3,
        "y": -12.1,
        "z": 48.7
      }
    ],
    "hr": [
      {
        "ts_ms": 1773410920000,
        "bpm": 72,
        "confidence": 1.0
      }
    ],
    "ble": [
      {
        "ts_ms": 1773410916000,
        "beacon_id": "AA:BB:CC:DD:EE:FF",
        "rssi": -65
      }
    ],
    "wear": [
      {
        "ts_ms": 1773410094600,
        "state": "ON_WRIST"
      }
    ],
    "battery": [
      {
        "ts_ms": 1773410094610,
        "level": 95.0
      }
    ],
    "downtime_reasons": [
      {
        "ts_ms": 1773410500000,
        "reason_id": "lunch",
        "zone_id": "zone-1"
      }
    ]
  },
  "meta": {
    "created_ts_ms": 1773410952342,
    "seq": 0,
    "upload_attempt": 0
  }
}
```

### Описание сенсорных данных:

| Поле | Частота | Описание |
|------|---------|----------|
| `accel` | ~25 Hz | Акселерометр (x, y, z в m/s²) |
| `gyro` | ~25 Hz | Гироскоп (x, y, z в rad/s) |
| `baro` | ~1 Hz | Барометр (hPa) |
| `mag` | ~10 Hz | Магнитометр (x, y, z в μT) |
| `hr` | ~1 Hz | Пульс (bpm) |
| `ble` | по событию | BLE-маяки (MAC + RSSI) |
| `wear` | по событию | Состояние: ON_WRIST / OFF_WRIST |
| `battery` | по событию | Уровень батареи (0-100) |
| `downtime_reason` | по событию | Причина простоя |

---

## 8. MVP-режим шифрования

> **ВАЖНО ДЛЯ БЭКЕНДА:** В текущей MVP-версии данные **НЕ шифруются**.

### Алгоритм на часах:

```
jsonBytes = plaintextJson.toByteArray(UTF-8)
payload_enc = base64_encode(jsonBytes)       ← НЕ зашифровано!
payload_hash = sha256(jsonBytes).hex()       ← хеш от ТЕХ ЖЕ байт
payload_size_bytes = jsonBytes.size
iv = base64(random_12_bytes)                 ← валидный, но не используется
payload_key_enc = base64("mvp-no-encryption") ← заглушка
```

### Проверка хеша на бэкенде:

```python
import base64, hashlib

payload_bytes = base64.b64decode(packet["payload_enc"])
expected_hash = hashlib.sha256(payload_bytes).hexdigest()

assert packet["payload_hash"] == expected_hash  # ✅ Должно совпадать
```

### Получение JSON payload на бэкенде:

```python
import base64, json

payload_bytes = base64.b64decode(packet["payload_enc"])
payload_json = json.loads(payload_bytes.decode("utf-8"))

# Теперь payload_json — это dict с samples, device, meta и т.д.
print(payload_json["samples"]["accel"])  # список замеров акселерометра
```

### Частые ошибки:

| Ошибка | Причина |
|--------|---------|
| `payload_hash mismatch` | Хеш считается НЕ от тех байт, что в payload_enc |
| `Invalid iv length` | IV должен быть base64 от ровно 12 байт |

---

## 9. Обработка ошибок и retry

### Стратегия на часах:

| HTTP-код | Действие | Retry? |
|----------|----------|--------|
| 202 | Успех → удаляем пакет | — |
| 400 | Ошибка данных → помечаем error | ❌ |
| 401 | Refresh token → retry отправку | ✅ |
| 403 | Устройство заблокировано | ❌ |
| 409 | Дубликат = успех | — |
| 5xx | Сервер упал → backoff retry | ✅ |

### Backoff-интервалы:

```
Попытка 1: через 1 мин
Попытка 2: через 2 мин
Попытка 3: через 5 мин
Попытка 4: через 10 мин
Попытка 5: через 30 мин
Попытка 6+: через 60 мин
```

### Идемпотентность:

Заголовок `Idempotency-Key: <packet_id>` гарантирует, что повторная отправка того же пакета не создаст дубликат. Бэкенд вернёт `409 Conflict`, часы считают это успехом.

---

## 10. Полная последовательность запросов

```
┌─ ПЕРВЫЙ ЗАПУСК ────────────────────────────────────────────┐
│                                                            │
│  1. POST /auth/device/register                             │
│     ← device_id, device_secret, server_public_key_pem     │
│                                                            │
│  2. POST /auth/device/token                                │
│     ← access_token, refresh_token, expires_in              │
│                                                            │
└────────────────────────────────────────────────────────────┘

┌─ ПОСЛЕДУЮЩИЕ ЗАПУСКИ ──────────────────────────────────────┐
│                                                            │
│  1. POST /auth/device/token (или /refresh)                 │
│     ← access_token, refresh_token                         │
│                                                            │
└────────────────────────────────────────────────────────────┘

┌─ СБОР ДАННЫХ (Запустить → Остановить) ────────────────────┐
│                                                            │
│  [каждые 15 мин]                                          │
│  POST /watch/heartbeat                                     │
│     → battery, is_collecting, pending_packets              │
│                                                            │
│  [при нажатии "Остановить"]                                │
│  POST /watch/packets                                       │
│     → packet_id, payload_enc, payload_hash, ...            │
│     ← 202 Accepted                                         │
│                                                            │
└────────────────────────────────────────────────────────────┘

┌─ ФОНОВЫЙ РЕЖИМ ───────────────────────────────────────────┐
│                                                            │
│  [каждые 15 мин]                                          │
│  POST /watch/heartbeat                                     │
│                                                            │
│  [если есть неотправленные пакеты]                         │
│  POST /watch/packets (retry с backoff)                     │
│                                                            │
└────────────────────────────────────────────────────────────┘
```

---

## 11. Проверка на бэкенде

### 11.1. Логи uvicorn

В терминале сервера должны появляться:
```
INFO: POST /api/v1/auth/device/register → 200
INFO: POST /api/v1/auth/device/token → 200
INFO: POST /api/v1/watch/heartbeat → 200
INFO: POST /api/v1/watch/packets → 202
```

### 11.2. Через curl

```bash
# Токен админа
ADMIN_TOKEN=$(curl -s -X POST http://10.228.38.66:8000/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@company.com","password":"adminpass"}' \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['access_token'])")

# Список пакетов
curl -s http://10.228.38.66:8000/api/v1/admin/packets \
  -H "Authorization: Bearer $ADMIN_TOKEN" | python3 -m json.tool
```

### 11.3. Через БД (PostgreSQL)

```sql
-- Зарегистрированные устройства
SELECT device_id, model, status, last_heartbeat_at
FROM devices
ORDER BY created_at DESC;

-- Принятые пакеты
SELECT packet_id, device_id, status, payload_hash,
       payload_size_bytes, received_at
FROM packets
ORDER BY received_at DESC;

-- Декодировать payload (если нужно)
SELECT packet_id,
       convert_from(decode(payload_enc, 'base64'), 'UTF8')::jsonb
FROM packets
WHERE packet_id = 'bd68e381-...';
```

---

## 12. Файлы в проекте

### Часы (Wear OS) — ключевые файлы:

```
app/src/main/java/com/example/activity_tracker/
├── network/
│   ├── NetworkClient.kt          ← Retrofit клиент, BASE_URL
│   ├── WatchAuthService.kt       ← Retrofit: register, token, refresh, heartbeat
│   ├── WatchApiService.kt        ← Retrofit: uploadPacket, getPacketStatus
│   ├── AuthManager.kt            ← Логика auth: регистрация, токены, refresh
│   ├── NetworkUploader.kt        ← Отправка пакета + retry + 401-handling
│   ├── UploadWorker.kt           ← WorkManager: фоновая отправка
│   ├── HeartbeatWorker.kt        ← WorkManager: периодический heartbeat
│   └── model/
│       ├── AuthModels.kt         ← DTO: register, token, heartbeat
│       └── UploadRequest.kt      ← DTO: packet upload request/response
├── crypto/
│   ├── CryptoManager.kt         ← MVP: base64 + sha256 (без AES)
│   └── DeviceCredentialsStore.kt ← EncryptedSharedPreferences
├── packet/
│   ├── PacketBuilder.kt          ← Собирает JSON из Room-данных
│   ├── PacketPipeline.kt         ← Pipeline: build → encode → save → queue
│   └── model/
│       └── ShiftPacket.kt        ← Структура JSON-пакета
├── presentation/
│   ├── viewmodel/
│   │   └── StatusViewModel.kt    ← Управление UI + registration code
│   ├── ui/
│   │   ├── StatusScreen.kt       ← Экран: старт/стоп сбора
│   │   └── RegistrationScreen.kt ← Экран: код регистрации
│   └── MainActivity.kt          ← Навигация: регистрация → сбор
├── service/
│   └── CollectorService.kt      ← ForegroundService для сбора с датчиков
└── ActivityTrackerApp.kt         ← Application: init AuthManager, HeartbeatWorker
```

---

## 13. FAQ / Частые проблемы

### ❓ Ошибка `Invalid iv length: expected 12 bytes after base64 decode`

IV должен быть base64 от ровно 12 байт. Убедитесь, что `iv` в запросе — корректная строка base64, которая при декодировании даёт 12 байт.

### ❓ Ошибка `Payload integrity check failed`

Хеш `payload_hash` должен быть `sha256` от **тех же самых байт**, которые закодированы в `payload_enc`:

```python
# Правильно:
raw_bytes = json_string.encode("utf-8")
payload_enc = base64.b64encode(raw_bytes).decode()
payload_hash = hashlib.sha256(raw_bytes).hexdigest()

# НЕПРАВИЛЬНО:
payload_hash = hashlib.sha256(payload_enc.encode()).hexdigest()  # ← хеш от base64-строки!
```

### ❓ Ошибка 401 при отправке пакета

Токен истёк. Часы автоматически делают refresh через `/auth/device/refresh` и повторяют запрос. Если refresh тоже не работает — получают новый токен через `/auth/device/token`.

### ❓ Ошибка 400 помечается как `shouldRetry = false`

400 означает невалидные данные (неверный формат, хеш, IV и т.д.). Пакет помечается как `error` и **не отправляется повторно**, т.к. данные не изменятся.

### ❓ Как сбросить регистрацию часов?

```bash
adb shell pm clear com.example.activity_tracker
```

После этого нужно сгенерировать **новый** registration code и пересобрать приложение.

### ❓ Часы отправляют незашифрованные данные?

Да, в MVP-режиме `payload_enc` — это просто base64-кодированный JSON (без AES-шифрования). Метод `encryptFull()` в `CryptoManager.kt` уже реализован для продакшена (AES-256-GCM + RSA-OAEP), но пока не используется.

### ❓ Сколько данных в одном пакете?

Зависит от длительности смены и частоты датчиков:
- ~25 сек сбора → ~7 КБ (50 замеров accel + 50 gyro + wear + battery)
- ~1 мин → ~30 КБ
- ~8 часов → ~50-200 МБ (с акселерометром на 25 Hz)

### ❓ ConnectException к 10.0.2.2:5601

Это **не наша ошибка**. Это Google Wear OS пытается подключиться к Companion-устройству (телефону). Можно игнорировать — часы работают напрямую через Wi-Fi.

---

## Контакты

- **Backend:** watch-backend репозиторий (GitLab)
- **Часы:** activity_tracker (Android Studio)
- **API docs:** `API_REFERENCE.md`, `MOBILE_GUIDE.md`, `MOBILE_APP_SPEC.md`
