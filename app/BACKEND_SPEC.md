# Техническое задание: Backend для Activity Tracker (FastAPI)

Дата: 2026-02-25
Основание: `IMPLEMENTATION_PLAN_WATCH.md`, `PROGRESS.md`, исходный код Wear OS приложения

---

## 1. Общее описание системы

### 1.1. Назначение backend

Backend принимает зашифрованные «пакеты смены» от смарт-часов Samsung Galaxy Watch 8 (Wear OS), расшифровывает их, валидирует, сохраняет сырые данные сенсоров и предоставляет API для управления устройствами, сменами и аналитикой.

### 1.2. Контекст

Wear OS приложение (Kotlin) уже реализовано:
- **Итерация 1** — сбор данных с 8 сенсоров (акселерометр, гироскоп, барометр, магнитометр, пульс, BLE, wear on/off, батарея), запись в Room (SQLite)
- **Итерация 2** — сборка JSON-пакета смены, шифрование AES-256-GCM + RSA-OAEP (envelope encryption), очередь пакетов
- **Итерация 3** — Retrofit-клиент, NetworkUploader с mock-отправкой, WorkManager для фоновой синхронизации

**Часы ожидают от сервера:**
1. Эндпоинт приёма зашифрованных пакетов (`POST /api/v1/watch/packets`)
2. Эндпоинт проверки статуса пакета (`GET /api/v1/watch/packets/{packet_id}`)
3. Эндпоинт heartbeat (`POST /api/v1/watch/heartbeat`)
4. Механизм регистрации устройств и выдачи токенов
5. Публичный RSA-ключ для шифрования на стороне часов

### 1.3. Стек технологий

- **Фреймворк:** FastAPI (Python 3.11+)
- **БД:** PostgreSQL 16+
- **ORM:** SQLAlchemy 2.0+ (async)
- **Миграции:** Alembic
- **Очередь задач:** Celery + Redis (или TaskIQ)
- **Кэш:** Redis
- **Аутентификация:** JWT (access/refresh tokens), device tokens
- **Криптография:** `cryptography` (Python), RSA-OAEP + AES-256-GCM
- **Контейнеризация:** Docker + docker-compose
- **Документация API:** OpenAPI/Swagger (встроено в FastAPI)
- **Тесты:** pytest + pytest-asyncio + httpx

---

## 2. Архитектура

### 2.1. Общая диаграмма

```
[Watch] --HTTPS--> [FastAPI] --> [PostgreSQL]
                       |
                       +--> [Redis] (кэш, очереди)
                       |
                       +--> [Celery Worker] (расшифровка, парсинг, аналитика)
```

### 2.2. Слои приложения

```
app/
├── api/                    # Роутеры FastAPI
│   ├── v1/
│   │   ├── packets.py      # Приём и статус пакетов
│   │   ├── devices.py      # Регистрация и управление устройствами
│   │   ├── shifts.py       # Смены и данные
│   │   ├── auth.py         # Аутентификация устройств
│   │   ├── heartbeat.py    # Heartbeat устройств
│   │   ├── analytics.py    # Аналитика и отчёты
│   │   ├── admin.py        # Административные эндпоинты
│   │   └── keys.py         # Управление криптоключами
│   └── deps.py             # Зависимости (DI)
├── core/
│   ├── config.py           # Настройки (Pydantic Settings)
│   ├── security.py         # JWT, хеширование, криптография
│   └── exceptions.py       # Кастомные исключения
├── models/                 # SQLAlchemy модели
│   ├── device.py
│   ├── packet.py
│   ├── shift.py
│   ├── sensor_data.py
│   ├── idempotency.py
│   └── crypto_key.py
├── schemas/                # Pydantic схемы (request/response)
│   ├── packet.py
│   ├── device.py
│   ├── shift.py
│   ├── auth.py
│   └── analytics.py
├── services/               # Бизнес-логика
│   ├── packet_service.py
│   ├── crypto_service.py
│   ├── device_service.py
│   ├── shift_service.py
│   └── analytics_service.py
├── tasks/                  # Celery задачи
│   ├── decrypt_task.py
│   ├── parse_task.py
│   └── analytics_task.py
├── db/
│   ├── session.py          # Async session factory
│   └── base.py             # Base model
├── migrations/             # Alembic
│   └── versions/
├── tests/
│   ├── test_packets.py
│   ├── test_crypto.py
│   ├── test_devices.py
│   └── conftest.py
├── main.py                 # Точка входа FastAPI
├── celery_app.py           # Конфигурация Celery
├── Dockerfile
├── docker-compose.yml
├── requirements.txt
├── alembic.ini
└── .env.example

---

## 3. Модель данных (PostgreSQL)

### 3.1. Таблица `devices` — Зарегистрированные устройства

| Поле | Тип | Описание |
|------|-----|----------|
| `id` | UUID, PK | Внутренний ID |
| `device_id` | VARCHAR(64), UNIQUE, NOT NULL | ID устройства (выдаётся сервером при регистрации) |
| `device_secret_hash` | VARCHAR(256), NOT NULL | Хеш device_secret (bcrypt/argon2) |
| `model` | VARCHAR(64) | Модель часов (e.g. "Galaxy Watch 8") |
| `firmware` | VARCHAR(64) | Версия прошивки |
| `app_version` | VARCHAR(32) | Версия приложения на часах |
| `employee_id` | UUID, FK → employees.id, NULLABLE | Привязка к сотруднику |
| `site_id` | VARCHAR(64), NULLABLE | Текущий объект/площадка |
| `status` | ENUM('active','revoked','suspended') | Статус устройства |
| `last_heartbeat_at` | TIMESTAMPTZ, NULLABLE | Последний heartbeat |
| `last_sync_at` | TIMESTAMPTZ, NULLABLE | Последняя синхронизация |
| `timezone` | VARCHAR(64), DEFAULT 'UTC' | Часовой пояс устройства |
| `created_at` | TIMESTAMPTZ, NOT NULL | Дата регистрации |
| `updated_at` | TIMESTAMPTZ, NOT NULL | Дата обновления |
| `is_deleted` | BOOLEAN, DEFAULT false | Мягкое удаление |

### 3.2. Таблица `employees` — Сотрудники

| Поле | Тип | Описание |
|------|-----|----------|
| `id` | UUID, PK | ID сотрудника |
| `external_id` | VARCHAR(128), UNIQUE | Внешний ID (из HR-системы) |
| `full_name` | VARCHAR(256) | ФИО |
| `position` | VARCHAR(128), NULLABLE | Должность |
| `department` | VARCHAR(128), NULLABLE | Подразделение |
| `status` | ENUM('active','inactive') | Статус |
| `created_at` | TIMESTAMPTZ | Дата создания |
| `updated_at` | TIMESTAMPTZ | Дата обновления |
| `is_deleted` | BOOLEAN, DEFAULT false | Мягкое удаление |

### 3.3. Таблица `packets` — Принятые пакеты смены

| Поле | Тип | Описание |
|------|-----|----------|
| `id` | UUID, PK | Внутренний ID |
| `packet_id` | VARCHAR(64), UNIQUE, NOT NULL | UUID пакета с часов |
| `device_id` | VARCHAR(64), FK → devices.device_id | ID устройства-отправителя |
| `shift_start_ts` | BIGINT, NOT NULL | Начало смены (Unix ms) |
| `shift_end_ts` | BIGINT, NOT NULL | Конец смены (Unix ms) |
| `schema_version` | INTEGER, NOT NULL | Версия схемы пакета |
| `status` | ENUM('accepted','decrypting','parsing','processed','error') | Статус обработки |
| `payload_enc` | TEXT, NOT NULL | Зашифрованный payload (Base64) |
| `payload_key_enc` | TEXT, NOT NULL | Зашифрованный AES-ключ (Base64) |
| `iv` | VARCHAR(64), NOT NULL | IV для AES-GCM (Base64) |
| `payload_hash` | VARCHAR(128), NOT NULL | SHA-256 от plaintext (для проверки) |
| `payload_size_bytes` | INTEGER | Размер payload в байтах |
| `decrypted_payload` | JSONB, NULLABLE | Расшифрованный JSON (после обработки) |
| `error_message` | TEXT, NULLABLE | Описание ошибки |
| `processing_started_at` | TIMESTAMPTZ, NULLABLE | Начало обработки |
| `processing_finished_at` | TIMESTAMPTZ, NULLABLE | Конец обработки |
| `received_at` | TIMESTAMPTZ, NOT NULL | Время приёма |
| `created_at` | TIMESTAMPTZ | Дата создания |
| `updated_at` | TIMESTAMPTZ | Дата обновления |

**Индексы:**
- `idx_packets_device_id` ON `packets(device_id)`
- `idx_packets_status` ON `packets(status)`
- `idx_packets_shift_ts` ON `packets(shift_start_ts, shift_end_ts)`
- `idx_packets_received_at` ON `packets(received_at)`

### 3.4. Таблица `idempotency_keys` — Ключи идемпотентности

| Поле | Тип | Описание |
|------|-----|----------|
| `id` | UUID, PK | ID |
| `idempotency_key` | VARCHAR(128), UNIQUE, NOT NULL | Ключ (= packet_id) |
| `response_status` | INTEGER, NOT NULL | HTTP статус ответа |
| `response_body` | JSONB, NOT NULL | Тело ответа |
| `created_at` | TIMESTAMPTZ, NOT NULL | Дата создания |
| `expires_at` | TIMESTAMPTZ, NOT NULL | Дата истечения (created_at + 30 дней) |

**Индексы:**
- `idx_idempotency_expires` ON `idempotency_keys(expires_at)` — для периодической очистки

### 3.5. Таблица `shifts` — Смены (агрегированные)

| Поле | Тип | Описание |
|------|-----|----------|
| `id` | UUID, PK | ID смены |
| `packet_id` | VARCHAR(64), FK → packets.packet_id | Связь с пакетом |
| `device_id` | VARCHAR(64), FK → devices.device_id | Устройство |
| `employee_id` | UUID, FK → employees.id, NULLABLE | Сотрудник |
| `site_id` | VARCHAR(64), NULLABLE | Объект |
| `start_ts_ms` | BIGINT, NOT NULL | Начало смены (Unix ms UTC) |
| `end_ts_ms` | BIGINT, NOT NULL | Конец смены (Unix ms UTC) |
| `duration_minutes` | INTEGER | Длительность в минутах |
| `schema_version` | INTEGER | Версия схемы |
| `device_model` | VARCHAR(64) | Модель часов |
| `device_fw` | VARCHAR(64) | Прошивка |
| `app_version` | VARCHAR(32) | Версия приложения |
| `timezone` | VARCHAR(64) | Часовой пояс |
| `server_time_offset_ms` | BIGINT, DEFAULT 0 | Смещение серверного времени |
| `created_at` | TIMESTAMPTZ | Дата создания |
| `updated_at` | TIMESTAMPTZ | Дата обновления |

**Индексы:**
- `idx_shifts_device_ts` ON `shifts(device_id, start_ts_ms)`
- `idx_shifts_employee` ON `shifts(employee_id)`

### 3.6. Таблицы сенсорных данных (после расшифровки пакета)

Все таблицы ниже заполняются **после расшифровки и парсинга** пакета. Данные извлекаются из `decrypted_payload → samples`.

#### 3.6.1. `sensor_samples_accel` — Акселерометр

| Поле | Тип | Описание |
|------|-----|----------|
| `id` | BIGSERIAL, PK | ID |
| `shift_id` | UUID, FK → shifts.id | Смена |
| `packet_id` | VARCHAR(64), FK → packets.packet_id | Пакет |
| `device_id` | VARCHAR(64) | Устройство |
| `ts_ms` | BIGINT, NOT NULL | Метка времени (Unix ms UTC) |
| `x` | REAL, NOT NULL | Ось X (м/с²) |
| `y` | REAL, NOT NULL | Ось Y (м/с²) |
| `z` | REAL, NOT NULL | Ось Z (м/с²) |
| `quality` | REAL | Качество показания (0..1) |

#### 3.6.2. `sensor_samples_gyro` — Гироскоп

| Поле | Тип | Описание |
|------|-----|----------|
| `id` | BIGSERIAL, PK | ID |
| `shift_id` | UUID, FK → shifts.id | Смена |
| `packet_id` | VARCHAR(64) | Пакет |
| `device_id` | VARCHAR(64) | Устройство |
| `ts_ms` | BIGINT, NOT NULL | Метка времени (Unix ms UTC) |
| `x` | REAL, NOT NULL | Ось X (рад/с) |
| `y` | REAL, NOT NULL | Ось Y (рад/с) |
| `z` | REAL, NOT NULL | Ось Z (рад/с) |
| `quality` | REAL | Качество (0..1) |

#### 3.6.3. `sensor_samples_baro` — Барометр

| Поле | Тип | Описание |
|------|-----|----------|
| `id` | BIGSERIAL, PK | ID |
| `shift_id` | UUID, FK → shifts.id | Смена |
| `packet_id` | VARCHAR(64) | Пакет |
| `device_id` | VARCHAR(64) | Устройство |
| `ts_ms` | BIGINT, NOT NULL | Метка времени |
| `hpa` | REAL, NOT NULL | Давление (гПа) |

#### 3.6.4. `sensor_samples_mag` — Магнитометр

| Поле | Тип | Описание |
|------|-----|----------|
| `id` | BIGSERIAL, PK | ID |
| `shift_id` | UUID, FK → shifts.id | Смена |
| `packet_id` | VARCHAR(64) | Пакет |
| `device_id` | VARCHAR(64) | Устройство |
| `ts_ms` | BIGINT, NOT NULL | Метка времени |
| `x` | REAL, NOT NULL | Ось X (µT) |
| `y` | REAL, NOT NULL | Ось Y (µT) |
| `z` | REAL, NOT NULL | Ось Z (µT) |

#### 3.6.5. `heart_rate_samples` — Пульс

| Поле | Тип | Описание |
|------|-----|----------|
| `id` | BIGSERIAL, PK | ID |
| `shift_id` | UUID, FK → shifts.id | Смена |
| `packet_id` | VARCHAR(64) | Пакет |
| `device_id` | VARCHAR(64) | Устройство |
| `ts_ms` | BIGINT, NOT NULL | Метка времени |
| `bpm` | INTEGER, NOT NULL | Удары в минуту |
| `confidence` | REAL | Уверенность (0..1) |

#### 3.6.6. `ble_events` — BLE-метки (iBeacon)

| Поле | Тип | Описание |
|------|-----|----------|
| `id` | BIGSERIAL, PK | ID |
| `shift_id` | UUID, FK → shifts.id | Смена |
| `packet_id` | VARCHAR(64) | Пакет |
| `device_id` | VARCHAR(64) | Устройство |
| `ts_ms` | BIGINT, NOT NULL | Метка времени |
| `beacon_id` | VARCHAR(128), NOT NULL | ID BLE-маяка |
| `rssi` | INTEGER, NULLABLE | Уровень сигнала (dBm) |

#### 3.6.7. `wear_events` — События ношения часов

| Поле | Тип | Описание |
|------|-----|----------|
| `id` | BIGSERIAL, PK | ID |
| `shift_id` | UUID, FK → shifts.id | Смена |
| `packet_id` | VARCHAR(64) | Пакет |
| `device_id` | VARCHAR(64) | Устройство |
| `ts_ms` | BIGINT, NOT NULL | Метка времени |
| `state` | VARCHAR(16), NOT NULL | Состояние: 'on' или 'off' |

#### 3.6.8. `battery_events` — События батареи

| Поле | Тип | Описание |
|------|-----|----------|
| `id` | BIGSERIAL, PK | ID |
| `shift_id` | UUID, FK → shifts.id | Смена |
| `packet_id` | VARCHAR(64) | Пакет |
| `device_id` | VARCHAR(64) | Устройство |
| `ts_ms` | BIGINT, NOT NULL | Метка времени |
| `level` | REAL, NOT NULL | Уровень заряда (0.0 .. 1.0) |

#### 3.6.9. `downtime_reasons` — Причины простоя

| Поле | Тип | Описание |
|------|-----|----------|
| `id` | BIGSERIAL, PK | ID |
| `shift_id` | UUID, FK → shifts.id | Смена |
| `packet_id` | VARCHAR(64) | Пакет |
| `device_id` | VARCHAR(64) | Устройство |
| `ts_ms` | BIGINT, NOT NULL | Метка времени |
| `reason_id` | VARCHAR(64), NOT NULL | Код причины простоя |
| `zone_id` | VARCHAR(64), NULLABLE | ID зоны |

**Общие индексы для всех таблиц сенсорных данных:**
- `idx_{table}_shift_id` ON `{table}(shift_id)`
- `idx_{table}_ts_ms` ON `{table}(ts_ms)`
- `idx_{table}_device_ts` ON `{table}(device_id, ts_ms)`

### 3.7. Таблица `crypto_keys` — Серверные ключи шифрования

| Поле | Тип | Описание |
|------|-----|----------|
| `id` | UUID, PK | ID |
| `key_name` | VARCHAR(64), UNIQUE | Имя ключа (e.g. "rsa_primary") |
| `public_key_pem` | TEXT, NOT NULL | Публичный ключ (PEM) — отдаётся часам |
| `private_key_pem_enc` | TEXT, NOT NULL | Приватный ключ (PEM), зашифрованный мастер-ключом |
| `algorithm` | VARCHAR(32), DEFAULT 'RSA-OAEP-SHA256' | Алгоритм |
| `key_size_bits` | INTEGER, DEFAULT 2048 | Размер ключа |
| `status` | ENUM('active','rotated','revoked') | Статус |
| `activated_at` | TIMESTAMPTZ | Дата активации |
| `rotated_at` | TIMESTAMPTZ, NULLABLE | Дата ротации |
| `created_at` | TIMESTAMPTZ | Дата создания |

### 3.8. Таблица `device_tokens` — JWT-токены устройств

| Поле | Тип | Описание |
|------|-----|----------|
| `id` | UUID, PK | ID |
| `device_id` | VARCHAR(64), FK → devices.device_id | Устройство |
| `access_token_jti` | VARCHAR(128), UNIQUE | JTI access-токена |
| `refresh_token_hash` | VARCHAR(256) | Хеш refresh-токена |
| `expires_at` | TIMESTAMPTZ, NOT NULL | Срок действия access-токена |
| `refresh_expires_at` | TIMESTAMPTZ, NOT NULL | Срок действия refresh-токена |
| `is_revoked` | BOOLEAN, DEFAULT false | Отозван ли |
| `created_at` | TIMESTAMPTZ | Дата создания |

### 3.9. Таблица `packet_processing_log` — Лог обработки пакетов

| Поле | Тип | Описание |
|------|-----|----------|
| `id` | BIGSERIAL, PK | ID |
| `packet_id` | VARCHAR(64), FK → packets.packet_id | Пакет |
| `step` | VARCHAR(32) | Этап: 'received','decrypted','parsed','stored','error' |
| `status` | VARCHAR(16) | 'ok' или 'error' |
| `message` | TEXT, NULLABLE | Сообщение / ошибка |
| `duration_ms` | INTEGER, NULLABLE | Длительность этапа (мс) |
| `created_at` | TIMESTAMPTZ | Время события |

---

## 4. API эндпоинты (детальная спецификация)

### 4.1. Аутентификация устройств

#### 4.1.1. `POST /api/v1/auth/device/register` — Регистрация нового устройства

Вызывается один раз при первичной настройке часов. Возвращает `device_id` и `device_secret`.

**Доступ:** Требует admin-токен или одноразовый registration_code.

**Request Body:**
```json
{
  "model": "Galaxy Watch 8",
  "firmware": "One UI 8 Watch",
  "app_version": "1.0.0",
  "timezone": "Europe/Moscow",
  "registration_code": "ABC123"
}
```

**Response 201 Created:**
```json
{
  "device_id": "dev_a1b2c3d4e5f6",
  "device_secret": "sec_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
  "server_public_key_pem": "-----BEGIN PUBLIC KEY-----\nMIIBIjAN...\n-----END PUBLIC KEY-----",
  "server_time": "2026-02-25T14:30:00.000Z"
}
```

**Response 400:** Невалидный registration_code.
**Response 409:** Устройство уже зарегистрировано.

**Логика:**
1. Проверить `registration_code` (одноразовый, из таблицы `registration_codes`).
2. Сгенерировать уникальный `device_id` (префикс `dev_` + random).
3. Сгенерировать `device_secret` (256 бит, hex).
4. Сохранить хеш `device_secret` в таблицу `devices` (argon2).
5. Пометить `registration_code` как использованный.
6. Вернуть `device_id`, `device_secret` и публичный RSA-ключ сервера.

#### 4.1.2. `POST /api/v1/auth/device/token` — Получение access_token

Вызывается часами для получения/обновления JWT access_token.

**Request Body:**
```json
{
  "device_id": "dev_a1b2c3d4e5f6",
  "device_secret": "sec_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"
}
```

**Response 200 OK:**
```json
{
  "access_token": "eyJhbGciOiJIUzI1NiIs...",
  "token_type": "Bearer",
  "expires_in": 86400,
  "refresh_token": "ref_yyyyyyyyyyyyyyyy",
  "server_time": "2026-02-25T14:30:00.000Z"
}
```

**Response 401:** Неверный device_secret.
**Response 403:** Устройство отозвано/заблокировано (status != 'active').

**Логика:**
1. Найти устройство по `device_id`.
2. Проверить `device_secret` против хеша (argon2 verify).
3. Проверить `status == 'active'`.
4. Сгенерировать JWT access_token (payload: `device_id`, `jti`, `exp`).
5. Сгенерировать refresh_token.
6. Сохранить в `device_tokens`.
7. Вернуть токены.

**JWT payload:**
```json
{
  "sub": "dev_a1b2c3d4e5f6",
  "jti": "unique-token-id",
  "type": "device",
  "iat": 1700000000,
  "exp": 1700086400
}
```

**Настройки токенов:**
- `access_token` TTL: 24 часа (настраиваемо)
- `refresh_token` TTL: 30 дней

#### 4.1.3. `POST /api/v1/auth/device/refresh` — Обновление access_token

**Request Body:**
```json
{
  "device_id": "dev_a1b2c3d4e5f6",
  "refresh_token": "ref_yyyyyyyyyyyyyyyy"
}
```

**Response 200:** Новая пара access_token + refresh_token.
**Response 401:** Невалидный/истёкший refresh_token.

#### 4.1.4. `POST /api/v1/auth/device/revoke` — Отзыв устройства

**Доступ:** Только admin.

**Request Body:**
```json
{
  "device_id": "dev_a1b2c3d4e5f6",
  "reason": "lost"
}
```

**Response 200:** `{ "status": "revoked" }`

**Логика:**
1. Установить `devices.status = 'revoked'`.
2. Отозвать все активные токены (`device_tokens.is_revoked = true`).
3. Часы получат 403 при следующей попытке отправки.

### 4.2. Пакеты смены (КЛЮЧЕВОЙ МОДУЛЬ)

#### 4.2.1. `POST /api/v1/watch/packets` — Приём зашифрованного пакета смены

**Это главный эндпоинт, ради которого существует backend.** Часы отправляют сюда зашифрованный пакет данных за смену.

**Доступ:** `Authorization: Bearer <access_token>` (device token).

**Заголовки:**
- `Authorization: Bearer <token>` — **обязательный**
- `Idempotency-Key: <uuid>` — **обязательный**, должен совпадать с `packet_id`
- `Content-Type: application/json`
- `Content-Encoding: gzip` — опциональный

**Request Body (соответствует `UploadRequest` из Kotlin-клиента):**
```json
{
  "packet_id": "550e8400-e29b-41d4-a716-446655440000",
  "device_id": "dev_a1b2c3d4e5f6",
  "shift_start_ts": 1700000000000,
  "shift_end_ts": 1700043200000,
  "schema_version": 1,
  "payload_enc": "Base64-encoded-AES-256-GCM-encrypted-payload...",
  "payload_key_enc": "Base64-encoded-RSA-OAEP-encrypted-AES-key...",
  "iv": "Base64-encoded-12-byte-IV...",
  "payload_hash": "sha256-hex-of-original-plaintext-payload"
}
```

**Response 202 Accepted (соответствует `UploadResponse` из Kotlin-клиента):**
```json
{
  "packet_id": "550e8400-e29b-41d4-a716-446655440000",
  "status": "accepted",
  "server_time": "2026-02-25T14:30:00.000Z"
}
```

**Response 409 Conflict (идемпотентный повтор):**
```json
{
  "packet_id": "550e8400-e29b-41d4-a716-446655440000",
  "status": "accepted",
  "server_time": "2026-02-25T14:30:00.000Z",
  "message": "Packet already accepted"
}
```

**Response 400 Bad Request:**
```json
{
  "detail": "Validation error",
  "errors": [
    { "field": "payload_enc", "message": "Field is required" }
  ]
}
```

**Response 401 Unauthorized:** Невалидный/истёкший token.
**Response 403 Forbidden:** Устройство отозвано.
**Response 422 Unprocessable Entity:** Несоответствие schema_version.

**Детальная логика обработки (пошагово):**

```
1. Валидация JWT access_token
   ├── Проверить подпись, exp, jti
   ├── Проверить что device_id из токена == device_id из тела
   └── При ошибке → 401

2. Проверка статуса устройства
   ├── SELECT status FROM devices WHERE device_id = :device_id
   └── Если status != 'active' → 403

3. Проверка идемпотентности
   ├── Сравнить Idempotency-Key == packet_id (ОБЯЗАТЕЛЬНО)
   ├── SELECT FROM idempotency_keys WHERE idempotency_key = :key
   ├── Если найден → вернуть сохранённый ответ (200/202)
   └── Если не найден → продолжить

4. Валидация тела запроса
   ├── Все обязательные поля присутствуют
   ├── packet_id — валидный UUID
   ├── shift_start_ts < shift_end_ts
   ├── schema_version поддерживается (список в конфиге)
   ├── payload_enc — валидный Base64
   ├── payload_key_enc — валидный Base64
   ├── iv — валидный Base64 (12 байт после decode)
   ├── payload_hash — 64 hex-символа (SHA-256)
   └── При ошибке → 400/422

5. Сохранение пакета
   ├── INSERT INTO packets (status='accepted', received_at=now())
   ├── INSERT INTO packet_processing_log (step='received', status='ok')
   └── INSERT INTO idempotency_keys (expires_at = now() + 30 дней)

6. Постановка в очередь обработки
   ├── Отправить Celery task: decrypt_and_parse_packet(packet_id)
   └── Задача выполняется асинхронно

7. Ответ клиенту
   ├── 202 Accepted
   ├── { packet_id, status: "accepted", server_time }
   └── Часы обновляют server_time_offset
```

**Ограничения:**
- Максимальный размер тела запроса: **50 MB** (настраиваемо)
- Rate limit: **10 запросов/мин** на device_id
- Таймаут: **60 секунд**

#### 4.2.2. `GET /api/v1/watch/packets/{packet_id}` — Статус пакета

**Доступ:** `Authorization: Bearer <access_token>` (device token).

**Response 200 OK (соответствует `PacketStatusResponse` из Kotlin-клиента):**
```json
{
  "packet_id": "550e8400-e29b-41d4-a716-446655440000",
  "status": "processed"
}
```

**Возможные статусы:**
- `accepted` — пакет принят, ожидает обработки
- `decrypting` — идёт расшифровка
- `parsing` — идёт парсинг JSON
- `processed` — успешно обработан, данные сохранены
- `error` — ошибка обработки

**Логика:**
1. Валидация токена.
2. Проверить что `device_id` из токена владеет этим `packet_id`.
3. SELECT status FROM packets WHERE packet_id = :packet_id.
4. Если не найден → 404.

### 4.3. Heartbeat

#### 4.3.1. `POST /api/v1/watch/heartbeat` — Пульс устройства

Часы отправляют периодически (например, каждые 15 минут) для синхронизации времени и подтверждения "живости".

**Доступ:** `Authorization: Bearer <access_token>`.

**Request Body:**
```json
{
  "device_id": "dev_a1b2c3d4e5f6",
  "device_time_ms": 1700000000000,
  "battery_level": 0.72,
  "is_collecting": true,
  "pending_packets": 2,
  "app_version": "1.0.0"
}
```

**Response 200 OK:**
```json
{
  "server_time": "2026-02-25T14:30:00.000Z",
  "server_time_ms": 1700000001200,
  "time_offset_ms": 1200,
  "commands": []
}
```

**Поле `commands`** — массив команд от сервера (для будущих расширений):
```json
{
  "commands": [
    { "type": "update_ble_profile", "profile": "ECO" },
    { "type": "force_upload", "reason": "admin_request" },
    { "type": "update_server_key", "public_key_pem": "..." }
  ]
}
```

**Логика:**
1. Валидация токена.
2. UPDATE devices SET last_heartbeat_at = now() WHERE device_id = :id.
3. Вычислить time_offset_ms = server_time_ms - device_time_ms.
4. Вернуть server_time и commands (если есть pending-команды).

### 4.4. Управление устройствами

#### 4.4.1. `GET /api/v1/devices` — Список устройств

**Доступ:** Admin.

**Query параметры:**
- `status` — фильтр по статусу ('active', 'revoked', 'suspended')
- `employee_id` — фильтр по сотруднику
- `page`, `page_size` — пагинация (default: page=1, page_size=50)

**Response 200:**
```json
{
  "items": [
    {
      "device_id": "dev_a1b2c3d4e5f6",
      "model": "Galaxy Watch 8",
      "firmware": "One UI 8 Watch",
      "app_version": "1.0.0",
      "employee_id": "uuid-or-null",
      "employee_name": "Иванов И.И.",
      "status": "active",
      "last_heartbeat_at": "2026-02-25T14:00:00Z",
      "last_sync_at": "2026-02-25T13:45:00Z",
      "created_at": "2026-02-01T10:00:00Z"
    }
  ],
  "total": 42,
  "page": 1,
  "page_size": 50
}
```

#### 4.4.2. `GET /api/v1/devices/{device_id}` — Детали устройства

**Доступ:** Admin.

**Response 200:** Полная информация об устройстве + статистика последних пакетов.

#### 4.4.3. `PATCH /api/v1/devices/{device_id}` — Обновление устройства

**Доступ:** Admin.

**Request Body (все поля опциональны):**
```json
{
  "employee_id": "uuid",
  "site_id": "site_01",
  "status": "suspended"
}
```

**Response 200:** Обновлённое устройство.

#### 4.4.4. `POST /api/v1/devices/{device_id}/bind` — Привязка к сотруднику

**Доступ:** Admin.

**Request Body:**
```json
{
  "employee_id": "uuid",
  "site_id": "site_01"
}
```

**Логика:**
1. Проверить что сотрудник существует и активен.
2. Обновить devices.employee_id и devices.site_id.
3. Записать лог привязки.

### 4.5. Смены и данные

#### 4.5.1. `GET /api/v1/shifts` — Список смен

**Доступ:** Admin.

**Query параметры:**
- `device_id` — по устройству
- `employee_id` — по сотруднику
- `date_from`, `date_to` — диапазон дат (ISO 8601)
- `page`, `page_size` — пагинация

**Response 200:**
```json
{
  "items": [
    {
      "id": "uuid",
      "device_id": "dev_a1b2c3d4e5f6",
      "employee_name": "Иванов И.И.",
      "start_ts_ms": 1700000000000,
      "end_ts_ms": 1700043200000,
      "duration_minutes": 720,
      "status": "processed",
      "samples_count": {
        "accel": 864000,
        "gyro": 864000,
        "baro": 43200,
        "mag": 432000,
        "hr": 43200,
        "ble": 720,
        "wear": 2,
        "battery": 48
      }
    }
  ],
  "total": 150,
  "page": 1,
  "page_size": 50
}
```

#### 4.5.2. `GET /api/v1/shifts/{shift_id}` — Детали смены

**Response 200:** Полная информация о смене + метаданные пакета.

#### 4.5.3. `GET /api/v1/shifts/{shift_id}/data/{data_type}` — Сырые данные смены

**Доступ:** Admin.

**Параметры пути:**
- `data_type`: `accel`, `gyro`, `baro`, `mag`, `hr`, `ble`, `wear`, `battery`, `downtime`

**Query параметры:**
- `from_ts`, `to_ts` — временной диапазон (фильтрация внутри смены)
- `limit` — максимальное количество записей (default: 10000, max: 100000)
- `offset` — смещение для пагинации
- `format` — `json` (default) или `csv`

**Response 200 (пример для hr):**
```json
{
  "shift_id": "uuid",
  "data_type": "hr",
  "count": 43200,
  "returned": 10000,
  "data": [
    { "ts_ms": 1700000000100, "bpm": 72, "confidence": 0.85 },
    { "ts_ms": 1700000001100, "bpm": 73, "confidence": 0.90 }
  ]
}
```

### 4.6. Аналитика

#### 4.6.1. `GET /api/v1/analytics/device/{device_id}/summary` — Сводка по устройству

**Query:** `date_from`, `date_to`

**Response 200:**
```json
{
  "device_id": "dev_a1b2c3d4e5f6",
  "period": { "from": "2026-02-01", "to": "2026-02-25" },
  "total_shifts": 24,
  "total_hours": 288,
  "avg_shift_duration_min": 720,
  "avg_heart_rate_bpm": 78,
  "total_ble_zones_visited": 15,
  "wear_compliance_percent": 98.5,
  "battery_avg_start": 0.95,
  "battery_avg_end": 0.32,
  "packets_with_errors": 0
}
```

#### 4.6.2. `GET /api/v1/analytics/employee/{employee_id}/summary` — Сводка по сотруднику

Аналогично 4.6.1, но агрегация по employee_id.

#### 4.6.3. `GET /api/v1/analytics/zones` — Аналитика по BLE-зонам

**Query:** `date_from`, `date_to`, `site_id`

**Response 200:**
```json
{
  "zones": [
    {
      "beacon_id": "zone_A1",
      "total_visits": 450,
      "unique_devices": 12,
      "avg_duration_min": 35,
      "peak_hour": 10
    }
  ]
}
```

### 4.7. Административные эндпоинты

#### 4.7.1. `GET /api/v1/admin/packets` — Лог пакетов

**Query:** `status`, `device_id`, `date_from`, `date_to`, `page`, `page_size`

**Response 200:** Список пакетов с деталями обработки.

#### 4.7.2. `GET /api/v1/admin/packets/{packet_id}/log` — Лог обработки пакета

**Response 200:**
```json
{
  "packet_id": "uuid",
  "logs": [
    { "step": "received", "status": "ok", "duration_ms": 5, "created_at": "..." },
    { "step": "decrypted", "status": "ok", "duration_ms": 120, "created_at": "..." },
    { "step": "parsed", "status": "ok", "duration_ms": 350, "created_at": "..." },
    { "step": "stored", "status": "ok", "duration_ms": 1500, "created_at": "..." }
  ]
}
```

#### 4.7.3. `POST /api/v1/admin/packets/{packet_id}/reprocess` — Повторная обработка

Перезапускает Celery task для расшифровки и парсинга пакета.

#### 4.7.4. `GET /api/v1/admin/stats` — Общая статистика системы

**Response 200:**
```json
{
  "active_devices": 42,
  "total_packets_today": 84,
  "packets_pending": 3,
  "packets_error": 1,
  "total_shifts": 1250,
  "storage_used_gb": 15.3,
  "uptime_hours": 720
}
```

#### 4.7.5. `POST /api/v1/admin/crypto/rotate-key` — Ротация RSA-ключа

**Логика:**
1. Генерация новой пары RSA-ключей (2048+ бит).
2. Сохранение в `crypto_keys` (status='active').
3. Предыдущий ключ → status='rotated' (не удаляется для расшифровки старых пакетов).
4. Новый публичный ключ отдаётся часам через heartbeat commands или при следующей регистрации.

#### 4.7.6. `GET /api/v1/admin/crypto/public-key` — Текущий публичный ключ

**Response 200:**
```json
{
  "key_name": "rsa_primary",
  "public_key_pem": "-----BEGIN PUBLIC KEY-----\n...",
  "algorithm": "RSA-OAEP-SHA256",
  "activated_at": "2026-02-01T00:00:00Z"
}
```

#### 4.7.7. `POST /api/v1/admin/registration-codes` — Генерация кодов регистрации

**Request Body:**
```json
{
  "count": 10,
  "expires_in_hours": 72
}
```

**Response 201:**
```json
{
  "codes": ["ABC123", "DEF456", "..."],
  "expires_at": "2026-02-28T14:30:00Z"
}
```

---

## 5. Криптография на сервере (расшифровка пакетов)

### 5.1. Схема шифрования (соответствует CryptoManager.kt на часах)

Часы используют **envelope encryption**:
1. Генерируют случайный AES-256 ключ (`data_key`) на каждый пакет.
2. Шифруют JSON-payload ключом `data_key` алгоритмом **AES-256-GCM** (IV = 12 байт, тег = 128 бит).
3. Шифруют `data_key` публичным RSA-ключом сервера алгоритмом **RSA/ECB/OAEPWithSHA-256AndMGF1Padding**.
4. Вычисляют SHA-256 хеш от исходного plaintext.
5. Передают: `payload_enc` (Base64), `payload_key_enc` (Base64), `iv` (Base64), `payload_hash` (hex).

### 5.2. Алгоритм расшифровки на сервере (Python)

```python
from cryptography.hazmat.primitives.asymmetric import padding
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
import base64
import hashlib

def decrypt_packet(
    payload_enc_b64: str,
    payload_key_enc_b64: str,
    iv_b64: str,
    payload_hash_expected: str,
    private_key_pem: bytes
) -> dict:
    """
    Расшифровка пакета смены.
    Соответствует CryptoManager.encrypt() на часах.
    """
    # 1. Декодируем Base64
    payload_enc = base64.b64decode(payload_enc_b64)
    payload_key_enc = base64.b64decode(payload_key_enc_b64)
    iv = base64.b64decode(iv_b64)

    # 2. Расшифровываем AES-ключ приватным RSA-ключом
    private_key = serialization.load_pem_private_key(
        private_key_pem, password=None
    )
    data_key = private_key.decrypt(
        payload_key_enc,
        padding.OAEP(
            mgf=padding.MGF1(algorithm=hashes.SHA256()),
            algorithm=hashes.SHA256(),
            label=None
        )
    )

    # 3. Расшифровываем payload AES-256-GCM
    aesgcm = AESGCM(data_key)
    plaintext = aesgcm.decrypt(iv, payload_enc, None)

    # 4. Проверяем SHA-256 хеш
    actual_hash = hashlib.sha256(plaintext).hexdigest()
    if actual_hash != payload_hash_expected:
        raise ValueError(
            f"Hash mismatch: expected={payload_hash_expected}, "
            f"actual={actual_hash}"
        )

    # 5. Парсим JSON
    import json
    return json.loads(plaintext.decode("utf-8"))
```

### 5.3. Управление RSA-ключами

**Генерация ключевой пары (при первом запуске или ротации):**
```python
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.hazmat.primitives import serialization

private_key = rsa.generate_private_key(
    public_exponent=65537,
    key_size=2048  # или 4096 для повышенной безопасности
)

# Приватный ключ → шифруем мастер-паролем и сохраняем в БД
private_pem = private_key.private_bytes(
    encoding=serialization.Encoding.PEM,
    format=serialization.PrivateFormat.PKCS8,
    encryption_algorithm=serialization.BestAvailableEncryption(
        MASTER_KEY.encode()
    )
)

# Публичный ключ → отдаём часам
public_pem = private_key.public_key().public_bytes(
    encoding=serialization.Encoding.PEM,
    format=serialization.PublicFormat.SubjectPublicKeyInfo
)
```

**Часы получают публичный ключ:**
- При регистрации устройства (response `POST /api/v1/auth/device/register`)
- Через heartbeat command `update_server_key`
- Через `GET /api/v1/admin/crypto/public-key`

**Хранение приватного ключа:**
- Приватный ключ шифруется `MASTER_KEY` из переменной окружения.
- `MASTER_KEY` никогда не хранится в БД или коде.
- При ротации старый ключ сохраняется (status='rotated') для расшифровки ранее принятых пакетов.

### 5.4. Совместимость с Kotlin CryptoManager

| Параметр | Часы (Kotlin) | Сервер (Python) |
|----------|---------------|-----------------|
| AES алгоритм | `AES/GCM/NoPadding` | `AESGCM` (cryptography) |
| AES ключ | 256 бит | 256 бит |
| IV | 12 байт, `SecureRandom` | 12 байт из `iv_b64` |
| GCM тег | 128 бит (включён в ciphertext) | 128 бит (включён в ciphertext) |
| RSA padding | `RSA/ECB/OAEPWithSHA-256AndMGF1Padding` | `OAEP(MGF1(SHA256), SHA256)` |
| Hash | `SHA-256`, hex | `sha256().hexdigest()` |
| Base64 | `Base64.NO_WRAP` | `base64.b64decode()` |

**ВАЖНО:** Java AES-GCM при `doFinal()` возвращает `ciphertext + auth_tag` конкатенированно. Python `AESGCM.decrypt()` ожидает тот же формат. Совместимость обеспечена.

---

## 6. Фоновые задачи (Celery)

### 6.1. `decrypt_and_parse_packet` — Расшифровка и парсинг пакета

**Запуск:** Автоматически после `POST /api/v1/watch/packets` (202 Accepted).

**Алгоритм:**

```
1. Загрузить пакет из БД
   ├── SELECT * FROM packets WHERE packet_id = :id AND status = 'accepted'
   └── Если не найден или status != 'accepted' → завершить

2. Обновить статус → 'decrypting'
   └── log(step='decrypting', status='ok')

3. Расшифровать payload
   ├── Получить активный приватный RSA-ключ из crypto_keys
   ├── Вызвать decrypt_packet()
   ├── При ошибке → status='error', log ошибку
   └── log(step='decrypted', status='ok', duration_ms=...)

4. Обновить статус → 'parsing'
   └── Валидировать JSON по схеме ShiftPacket (schema_version)

5. Извлечь и сохранить данные
   ├── Создать запись в shifts
   ├── Batch INSERT в sensor_samples_accel (все accel из samples)
   ├── Batch INSERT в sensor_samples_gyro
   ├── Batch INSERT в sensor_samples_baro
   ├── Batch INSERT в sensor_samples_mag
   ├── Batch INSERT в heart_rate_samples
   ├── Batch INSERT в ble_events
   ├── Batch INSERT в wear_events
   ├── Batch INSERT в battery_events
   ├── Batch INSERT в downtime_reasons
   ├── Сохранить decrypted_payload как JSONB в packets
   └── log(step='stored', status='ok', duration_ms=...)

6. Обновить статус → 'processed'
   ├── UPDATE packets SET status = 'processed'
   ├── UPDATE devices SET last_sync_at = now()
   └── log(step='parsed', status='ok')
```

**Обработка ошибок:**
- При любой ошибке: `status = 'error'`, `error_message = str(e)`.
- Ошибки логируются в `packet_processing_log`.
- Celery retry: до 3 попыток с backoff 30с, 60с, 120с.
- При перманентной ошибке (невалидный hash, битые данные) — без retry.

**Оптимизация batch INSERT:**
- Для больших пакетов (24ч смена, 25 Гц accel = ~2.16M записей) использовать:
  - `COPY` (PostgreSQL) через `psycopg` для максимальной скорости
  - Или chunked INSERT по 10000 записей за транзакцию
  - Или `executemany()` с prepared statements

### 6.2. `cleanup_idempotency_keys` — Очистка устаревших ключей

**Запуск:** Периодически (Celery Beat), раз в сутки.

**Логика:**
```sql
DELETE FROM idempotency_keys WHERE expires_at < NOW();
```

### 6.3. `cleanup_old_data` — Очистка старых данных

**Запуск:** Периодически (Celery Beat), раз в неделю.

**Настройки:**
- `RETENTION_DAYS_RAW_DATA` = 90 (сырые сенсорные данные)
- `RETENTION_DAYS_PACKETS` = 365 (пакеты)
- `RETENTION_DAYS_SHIFTS` = 365 (смены)

### 6.4. `generate_shift_analytics` — Генерация аналитики

**Запуск:** После успешного `decrypt_and_parse_packet`.

**Логика:**
1. Подсчёт агрегатов по смене (средний пульс, время ношения, зоны).
2. Обновление сводной статистики.

### 6.5. Celery Beat расписание

```python
CELERY_BEAT_SCHEDULE = {
    "cleanup-idempotency-keys": {
        "task": "tasks.cleanup_idempotency_keys",
        "schedule": crontab(hour=3, minute=0),  # 03:00 ежедневно
    },
    "cleanup-old-data": {
        "task": "tasks.cleanup_old_data",
        "schedule": crontab(hour=4, minute=0, day_of_week=0),  # 04:00 по воскресеньям
    },
    "check-device-health": {
        "task": "tasks.check_device_health",
        "schedule": crontab(minute="*/15"),  # каждые 15 минут
    },
}
```

---

## 7. Pydantic-схемы (request/response)

### 7.1. Схемы пакетов

```python
from pydantic import BaseModel, Field, field_validator
from typing import Optional
from datetime import datetime
import re

class UploadPacketRequest(BaseModel):
    """Соответствует UploadRequest из Kotlin-клиента"""
    packet_id: str = Field(..., description="UUID пакета")
    device_id: str = Field(..., description="ID устройства")
    shift_start_ts: int = Field(..., description="Начало смены (Unix ms)")
    shift_end_ts: int = Field(..., description="Конец смены (Unix ms)")
    schema_version: int = Field(default=1)
    payload_enc: str = Field(..., description="Base64 AES-GCM encrypted payload")
    payload_key_enc: str = Field(..., description="Base64 RSA-OAEP encrypted AES key")
    iv: str = Field(..., description="Base64 IV (12 bytes)")
    payload_hash: str = Field(..., description="SHA-256 hex of plaintext")

    @field_validator("payload_hash")
    @classmethod
    def validate_hash(cls, v):
        if not re.match(r"^[a-f0-9]{64}$", v):
            raise ValueError("payload_hash must be 64 hex chars (SHA-256)")
        return v

    @field_validator("shift_end_ts")
    @classmethod
    def validate_shift_range(cls, v, info):
        if "shift_start_ts" in info.data and v <= info.data["shift_start_ts"]:
            raise ValueError("shift_end_ts must be > shift_start_ts")
        return v

class UploadPacketResponse(BaseModel):
    """Соответствует UploadResponse из Kotlin-клиента"""
    packet_id: str
    status: str  # "accepted"
    server_time: str  # ISO 8601
    message: Optional[str] = None

class PacketStatusResponse(BaseModel):
    """Соответствует PacketStatusResponse из Kotlin-клиента"""
    packet_id: str
    status: str  # accepted | decrypting | parsing | processed | error
```

### 7.2. Схемы аутентификации

```python
class DeviceRegisterRequest(BaseModel):
    model: str = Field(..., max_length=64)
    firmware: str = Field(..., max_length=64)
    app_version: str = Field(..., max_length=32)
    timezone: str = Field(default="UTC", max_length=64)
    registration_code: str = Field(..., max_length=32)

class DeviceRegisterResponse(BaseModel):
    device_id: str
    device_secret: str
    server_public_key_pem: str
    server_time: str

class DeviceTokenRequest(BaseModel):
    device_id: str
    device_secret: str

class DeviceTokenResponse(BaseModel):
    access_token: str
    token_type: str = "Bearer"
    expires_in: int  # секунды
    refresh_token: str
    server_time: str

class DeviceRefreshRequest(BaseModel):
    device_id: str
    refresh_token: str
```

### 7.3. Схемы heartbeat

```python
class HeartbeatRequest(BaseModel):
    device_id: str
    device_time_ms: int
    battery_level: float = Field(..., ge=0.0, le=1.0)
    is_collecting: bool = False
    pending_packets: int = Field(default=0, ge=0)
    app_version: Optional[str] = None

class HeartbeatCommand(BaseModel):
    type: str  # update_ble_profile, force_upload, update_server_key
    payload: dict = Field(default_factory=dict)

class HeartbeatResponse(BaseModel):
    server_time: str
    server_time_ms: int
    time_offset_ms: int
    commands: list[HeartbeatCommand] = Field(default_factory=list)
```

### 7.4. Схема расшифрованного пакета (валидация JSON)

```python
class AccelSampleSchema(BaseModel):
    ts_ms: int
    x: float
    y: float
    z: float
    quality: float = 1.0

class GyroSampleSchema(BaseModel):
    ts_ms: int
    x: float
    y: float
    z: float
    quality: float = 1.0

class BaroSampleSchema(BaseModel):
    ts_ms: int
    hpa: float

class MagSampleSchema(BaseModel):
    ts_ms: int
    x: float
    y: float
    z: float

class HrSampleSchema(BaseModel):
    ts_ms: int
    bpm: int = Field(..., ge=0, le=300)
    confidence: float = Field(default=1.0, ge=0.0, le=1.0)

class BleSampleSchema(BaseModel):
    ts_ms: int
    beacon_id: str
    rssi: Optional[int] = None

class WearSampleSchema(BaseModel):
    ts_ms: int
    state: str  # "on" | "off"

class BatterySampleSchema(BaseModel):
    ts_ms: int
    level: float = Field(..., ge=0.0, le=1.0)

class DowntimeSampleSchema(BaseModel):
    ts_ms: int
    reason_id: str
    zone_id: Optional[str] = None

class DeviceInfoSchema(BaseModel):
    device_id: str
    model: str
    fw: str
    app_version: str
    tz: str

class ShiftPeriodSchema(BaseModel):
    start_ts_ms: int
    end_ts_ms: int

class TimeSyncSchema(BaseModel):
    server_time_offset_ms: int = 0
    server_time_ms: int = 0

class ShiftSamplesSchema(BaseModel):
    accel: list[AccelSampleSchema] = Field(default_factory=list)
    gyro: list[GyroSampleSchema] = Field(default_factory=list)
    baro: list[BaroSampleSchema] = Field(default_factory=list)
    mag: list[MagSampleSchema] = Field(default_factory=list)
    hr: list[HrSampleSchema] = Field(default_factory=list)
    ble: list[BleSampleSchema] = Field(default_factory=list)
    wear: list[WearSampleSchema] = Field(default_factory=list)
    battery: list[BatterySampleSchema] = Field(default_factory=list)
    downtime_reason: list[DowntimeSampleSchema] = Field(default_factory=list)

class PacketMetaSchema(BaseModel):
    created_ts_ms: int
    seq: int
    upload_attempt: int = 0

class ShiftPacketSchema(BaseModel):
    """
    Валидация расшифрованного JSON пакета.
    Соответствует ShiftPacket из Kotlin packet/model/ShiftPacket.kt
    """
    schema_version: int = 1
    packet_id: str
    device: DeviceInfoSchema
    shift: ShiftPeriodSchema
    time_sync: TimeSyncSchema
    samples: ShiftSamplesSchema
    meta: PacketMetaSchema
```

---

## 8. Конфигурация и переменные окружения

### 8.1. Файл `.env.example`

```env
# === Приложение ===
APP_NAME=activity-tracker-backend
APP_ENV=production
APP_DEBUG=false
APP_HOST=0.0.0.0
APP_PORT=8000
APP_WORKERS=4
LOG_LEVEL=info

# === База данных ===
DATABASE_URL=postgresql+asyncpg://user:password@localhost:5432/activity_tracker
DATABASE_POOL_SIZE=20
DATABASE_MAX_OVERFLOW=10

# === Redis ===
REDIS_URL=redis://localhost:6379/0
CELERY_BROKER_URL=redis://localhost:6379/1
CELERY_RESULT_BACKEND=redis://localhost:6379/2

# === JWT ===
JWT_SECRET_KEY=your-256-bit-secret-key-here
JWT_ALGORITHM=HS256
JWT_ACCESS_TOKEN_EXPIRE_SECONDS=86400
JWT_REFRESH_TOKEN_EXPIRE_DAYS=30

# === Криптография ===
MASTER_KEY=your-master-key-for-encrypting-private-keys
RSA_KEY_SIZE=2048

# === Лимиты ===
MAX_UPLOAD_SIZE_MB=50
RATE_LIMIT_PER_DEVICE=10/minute
IDEMPOTENCY_KEY_TTL_DAYS=30

# === Ретенция данных ===
RETENTION_DAYS_RAW_DATA=90
RETENTION_DAYS_PACKETS=365
RETENTION_DAYS_SHIFTS=365

# === CORS ===
CORS_ORIGINS=["http://localhost:3000"]

# === Admin ===
ADMIN_API_KEY=your-admin-api-key
```

### 8.2. Pydantic Settings

```python
from pydantic_settings import BaseSettings

class Settings(BaseSettings):
    app_name: str = "activity-tracker-backend"
    app_env: str = "production"
    app_debug: bool = False
    app_host: str = "0.0.0.0"
    app_port: int = 8000
    app_workers: int = 4
    log_level: str = "info"

    database_url: str
    database_pool_size: int = 20
    database_max_overflow: int = 10

    redis_url: str = "redis://localhost:6379/0"
    celery_broker_url: str = "redis://localhost:6379/1"
    celery_result_backend: str = "redis://localhost:6379/2"

    jwt_secret_key: str
    jwt_algorithm: str = "HS256"
    jwt_access_token_expire_seconds: int = 86400
    jwt_refresh_token_expire_days: int = 30

    master_key: str
    rsa_key_size: int = 2048

    max_upload_size_mb: int = 50
    rate_limit_per_device: str = "10/minute"
    idempotency_key_ttl_days: int = 30

    retention_days_raw_data: int = 90
    retention_days_packets: int = 365
    retention_days_shifts: int = 365

    cors_origins: list[str] = ["http://localhost:3000"]
    admin_api_key: str = ""

    supported_schema_versions: list[int] = [1]

    class Config:
        env_file = ".env"
        env_file_encoding = "utf-8"
```

---

## 9. Docker и инфраструктура

### 9.1. Dockerfile

```dockerfile
FROM python:3.11-slim

WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends \
    libpq-dev gcc && \
    rm -rf /var/lib/apt/lists/*

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY . .

EXPOSE 8000

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000", "--workers", "4"]
```

### 9.2. docker-compose.yml

```yaml
version: "3.9"

services:
  api:
    build: .
    ports:
      - "8000:8000"
    env_file: .env
    depends_on:
      db:
        condition: service_healthy
      redis:
        condition: service_started
    volumes:
      - ./app:/app/app
    restart: unless-stopped

  celery-worker:
    build: .
    command: celery -A app.celery_app worker --loglevel=info --concurrency=4
    env_file: .env
    depends_on:
      db:
        condition: service_healthy
      redis:
        condition: service_started
    restart: unless-stopped

  celery-beat:
    build: .
    command: celery -A app.celery_app beat --loglevel=info
    env_file: .env
    depends_on:
      redis:
        condition: service_started
    restart: unless-stopped

  db:
    image: postgres:16-alpine
    environment:
      POSTGRES_DB: activity_tracker
      POSTGRES_USER: user
      POSTGRES_PASSWORD: password
    ports:
      - "5432:5432"
    volumes:
      - pgdata:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U user -d activity_tracker"]
      interval: 5s
      timeout: 5s
      retries: 5
    restart: unless-stopped

  redis:
    image: redis:7-alpine
    ports:
      - "6379:6379"
    volumes:
      - redisdata:/data
    restart: unless-stopped

volumes:
  pgdata:
  redisdata:
```

### 9.3. requirements.txt

```
# FastAPI
fastapi==0.109.2
uvicorn[standard]==0.27.1
python-multipart==0.0.9

# Database
sqlalchemy[asyncio]==2.0.27
asyncpg==0.29.0
alembic==1.13.1
psycopg2-binary==2.9.9

# Redis & Celery
redis==5.0.1
celery[redis]==5.3.6

# Auth & Security
python-jose[cryptography]==3.3.0
passlib[argon2]==1.7.4
cryptography==42.0.4

# Validation & Serialization
pydantic==2.6.1
pydantic-settings==2.1.0
email-validator==2.1.0

# HTTP client (для тестов)
httpx==0.27.0

# Logging
structlog==24.1.0

# Rate limiting
slowapi==0.1.9

# Testing
pytest==8.0.1
pytest-asyncio==0.23.5
pytest-cov==4.1.0
factory-boy==3.3.0

# Utils
python-dotenv==1.0.1
```

---

## 10. Тестирование

### 10.1. Обязательные тесты (Definition of Done)

#### 10.1.1. Крипто-тесты (критические)

```python
# tests/test_crypto.py

class TestCryptoService:
    """
    Проверяют совместимость с CryptoManager.kt на часах
    """

    def test_decrypt_aes_gcm_known_vector(self):
        """Расшифровка известного AES-256-GCM вектора"""

    def test_decrypt_rsa_oaep_known_vector(self):
        """Расшифровка AES-ключа, зашифрованного RSA-OAEP"""

    def test_full_envelope_decrypt(self):
        """Полный цикл: RSA → AES-ключ → AES-GCM → plaintext"""

    def test_hash_verification_success(self):
        """SHA-256 хеш совпадает"""

    def test_hash_verification_failure(self):
        """SHA-256 хеш не совпадает → ValueError"""

    def test_decrypt_with_rotated_key(self):
        """Расшифровка старым ключом после ротации"""

    def test_kotlin_generated_payload(self):
        """
        ВАЖНЕЙШИЙ ТЕСТ: расшифровка payload, реально
        сгенерированного CryptoManager.kt на часах.
        Зафиксировать тестовый вектор от Kotlin-приложения.
        """
```

#### 10.1.2. Тесты приёма пакетов

```python
# tests/test_packets.py

class TestUploadPacket:

    async def test_upload_success_202(self, client, auth_headers):
        """Успешная отправка → 202 Accepted"""

    async def test_upload_idempotent_409(self, client, auth_headers):
        """Повторная отправка → 409 Conflict с тем же ответом"""

    async def test_upload_invalid_hash_400(self, client, auth_headers):
        """Невалидный payload_hash → 400"""

    async def test_upload_expired_token_401(self, client):
        """Истёкший токен → 401"""

    async def test_upload_revoked_device_403(self, client):
        """Отозванное устройство → 403"""

    async def test_upload_wrong_device_id_401(self, client, auth_headers):
        """device_id в теле != device_id в токене → 401"""

    async def test_upload_unsupported_schema_422(self, client, auth_headers):
        """schema_version=99 → 422"""

    async def test_upload_missing_fields_400(self, client, auth_headers):
        """Отсутствие обязательного поля → 400"""

    async def test_upload_triggers_celery_task(self, client, auth_headers):
        """После 202 → Celery task поставлен в очередь"""

    async def test_packet_status_processed(self, client, auth_headers):
        """GET /packets/{id} после обработки → status=processed"""
```

#### 10.1.3. Тесты аутентификации

```python
# tests/test_auth.py

class TestDeviceAuth:

    async def test_register_device_success(self, client):
        """Регистрация → device_id + device_secret + public_key"""

    async def test_register_invalid_code_400(self, client):
        """Невалидный registration_code → 400"""

    async def test_get_token_success(self, client):
        """device_id + device_secret → access_token"""

    async def test_get_token_wrong_secret_401(self, client):
        """Неверный device_secret → 401"""

    async def test_refresh_token_success(self, client):
        """refresh_token → новая пара токенов"""

    async def test_revoked_device_403(self, client):
        """Отозванное устройство → 403 при получении токена"""
```

#### 10.1.4. Тесты Celery задач

```python
# tests/test_tasks.py

class TestDecryptAndParseTask:

    async def test_decrypt_parse_store_success(self):
        """Полный цикл: accepted → decrypting → parsing → processed"""

    async def test_decrypt_invalid_key_error(self):
        """Невалидный RSA-ключ → status=error"""

    async def test_decrypt_hash_mismatch_error(self):
        """Несовпадение хеша → status=error, без retry"""

    async def test_parse_invalid_json_error(self):
        """Невалидный JSON → status=error"""

    async def test_batch_insert_large_dataset(self):
        """24ч смена, 25 Гц accel — ~2M записей сохраняются"""

    async def test_retry_on_db_error(self):
        """Ошибка БД → retry (до 3 раз)"""
```

### 10.2. Интеграционные тесты

```python
# tests/test_integration.py

class TestEndToEnd:

    async def test_full_flow(self):
        """
        1. Зарегистрировать устройство
        2. Получить access_token
        3. Отправить зашифрованный пакет
        4. Дождаться обработки (poll status)
        5. Проверить данные в БД
        """

    async def test_idempotent_retry_flow(self):
        """
        1. Отправить пакет → 202
        2. Повторить отправку → 409 с тем же ответом
        3. Проверить что данные не дублируются
        """

    async def test_offline_retry_flow(self):
        """
        Симулирует сценарий часов:
        1. Отправить пакет → 5xx (сервер временно недоступен)
        2. Повторить → 202
        """
```

### 10.3. conftest.py (фикстуры)

```python
import pytest
from httpx import AsyncClient, ASGITransport
from app.main import app
from app.db.session import get_session

@pytest.fixture
async def client():
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        yield c

@pytest.fixture
async def registered_device(client):
    """Зарегистрированное устройство с токеном"""
    # register → token → return headers

@pytest.fixture
def auth_headers(registered_device):
    return {"Authorization": f"Bearer {registered_device['access_token']}"}

@pytest.fixture
def sample_upload_request():
    """Валидный UploadRequest с реально зашифрованным payload"""
```

---

## 11. Маппинг Kotlin-клиент ↔ FastAPI backend

Таблица соответствий между классами/полями на часах и на сервере.

### 11.1. Сетевые модели

| Kotlin (часы) | Python (сервер) | Файл на часах |
|----------------|-----------------|---------------|
| `UploadRequest` | `UploadPacketRequest` | `network/model/UploadRequest.kt` |
| `UploadResponse` | `UploadPacketResponse` | `network/model/UploadRequest.kt` |
| `PacketStatusResponse` | `PacketStatusResponse` | `network/model/UploadRequest.kt` |
| `WatchApiService.uploadPacket()` | `POST /api/v1/watch/packets` | `network/WatchApiService.kt` |
| `WatchApiService.getPacketStatus()` | `GET /api/v1/watch/packets/{id}` | `network/WatchApiService.kt` |

### 11.2. Модели пакета (расшифрованный JSON)

| Kotlin (часы) | Python (сервер) | Описание |
|----------------|-----------------|----------|
| `ShiftPacket` | `ShiftPacketSchema` | Корневая структура |
| `DeviceInfo` | `DeviceInfoSchema` | Информация об устройстве |
| `ShiftPeriod` | `ShiftPeriodSchema` | Период смены |
| `TimeSync` | `TimeSyncSchema` | Синхронизация времени |
| `ShiftSamples` | `ShiftSamplesSchema` | Контейнер всех данных |
| `AccelSample` | `AccelSampleSchema` | Акселерометр |
| `GyroSample` | `GyroSampleSchema` | Гироскоп |
| `BaroSample` | `BaroSampleSchema` | Барометр |
| `MagSample` | `MagSampleSchema` | Магнитометр |
| `HrSample` | `HrSampleSchema` | Пульс |
| `BleSample` | `BleSampleSchema` | BLE-метки |
| `WearSample` | `WearSampleSchema` | Ношение часов |
| `BatterySample` | `BatterySampleSchema` | Батарея |
| `DowntimeSample` | `DowntimeSampleSchema` | Причины простоя |
| `PacketMeta` | `PacketMetaSchema` | Метаданные пакета |

### 11.3. Криптография

| Kotlin (часы) | Python (сервер) | Описание |
|----------------|-----------------|----------|
| `CryptoManager.encrypt()` | `crypto_service.decrypt_packet()` | Шифрование / расшифровка |
| `CryptoManager.generateAesKey()` | `AESGCM(data_key)` | AES-256 ключ |
| `CryptoManager.encryptAesGcm()` | `aesgcm.decrypt(iv, ct, None)` | AES-256-GCM |
| `CryptoManager.encryptDataKey()` | `private_key.decrypt(OAEP)` | RSA-OAEP |
| `CryptoManager.sha256Hex()` | `hashlib.sha256().hexdigest()` | SHA-256 проверка |
| `EncryptedPacket.payloadEncBase64` | `payload_enc` (Base64) | Зашифрованные данные |
| `EncryptedPacket.payloadKeyEncBase64` | `payload_key_enc` (Base64) | Зашифрованный AES-ключ |
| `EncryptedPacket.ivBase64` | `iv` (Base64) | Вектор инициализации |
| `EncryptedPacket.payloadHashSha256` | `payload_hash` (hex) | Хеш для проверки |

### 11.4. Статусы пакетов

| Kotlin (часы) — `PacketPipeline` | Python (сервер) — `packets.status` | Описание |
|-----------------------------------|------------------------------------|----------|
| `STATUS_PENDING = "pending"` | `accepted` | Пакет принят |
| `STATUS_UPLOADING = "uploading"` | — (только на часах) | Идёт отправка |
| `STATUS_UPLOADED = "uploaded"` | `accepted` → `processed` | Отправлен / обработан |
| `STATUS_ERROR = "error"` | `error` | Ошибка |
| — | `decrypting` | Идёт расшифровка (на сервере) |
| — | `parsing` | Идёт парсинг JSON (на сервере) |

### 11.5. Что менять на часах после готовности backend

Файлы, которые нужно обновить в Kotlin-приложении:

1. **`NetworkClient.kt`** — заменить `BASE_URL` на реальный URL сервера
2. **`NetworkUploader.kt`** — заменить `mockUpload()` на `apiService.uploadPacket()`
3. **`CryptoManager.kt`** — вставить реальный публичный RSA-ключ в `getServerPublicKeyBytes()`
4. **Добавить** экран регистрации устройства (вызов `POST /api/v1/auth/device/register`)
5. **Добавить** логику получения и обновления `access_token` (вызов `POST /api/v1/auth/device/token`)
6. **Добавить** периодический heartbeat (вызов `POST /api/v1/watch/heartbeat`)

---

## 12. План итераций реализации backend

### Итерация B1 — Ядро: приём пакетов (1-2 недели)

**Цель:** Часы могут отправлять пакеты на сервер и получать 202 Accepted.

- [ ] Инициализация проекта FastAPI + Docker + PostgreSQL + Redis
- [ ] Alembic миграции для таблиц: `devices`, `packets`, `idempotency_keys`, `packet_processing_log`
- [ ] Pydantic-схемы: `UploadPacketRequest`, `UploadPacketResponse`, `PacketStatusResponse`
- [ ] `POST /api/v1/watch/packets` — приём, валидация, сохранение, идемпотентность
- [ ] `GET /api/v1/watch/packets/{packet_id}` — проверка статуса
- [ ] Middleware: CORS, request logging, error handling
- [ ] Тесты: приём пакетов, идемпотентность, валидация
- [ ] docker-compose запуск (api + db + redis)

**Критерий готовности:** curl/httpx могут отправить пакет и получить 202. Повтор → 409.

### Итерация B2 — Аутентификация устройств (1 неделя)

**Цель:** Устройства регистрируются и получают JWT-токены.

- [ ] Таблицы: `employees`, `device_tokens`, `registration_codes`
- [ ] `POST /api/v1/auth/device/register` — регистрация + выдача device_secret
- [ ] `POST /api/v1/auth/device/token` — выдача JWT access_token
- [ ] `POST /api/v1/auth/device/refresh` — обновление токена
- [ ] JWT dependency для FastAPI (проверка токена в каждом запросе)
- [ ] Проверка `device_id` из токена vs `device_id` из тела запроса
- [ ] `POST /api/v1/auth/device/revoke` — отзыв устройства
- [ ] Тесты аутентификации

**Критерий готовности:** Часы регистрируются, получают токен, отправляют пакет с `Authorization: Bearer`.

### Итерация B3 — Криптография и обработка (1-2 недели)

**Цель:** Сервер расшифровывает пакеты и сохраняет сырые данные.

- [ ] Таблицы сенсорных данных (9 таблиц), таблица `shifts`, таблица `crypto_keys`
- [ ] `crypto_service.py` — генерация RSA-ключей, расшифровка пакетов
- [ ] Celery task `decrypt_and_parse_packet`
- [ ] Batch INSERT сенсорных данных в PostgreSQL
- [ ] `GET /api/v1/admin/crypto/public-key` — отдача публичного ключа
- [ ] `POST /api/v1/admin/crypto/rotate-key` — ротация ключей
- [ ] Крипто-тесты (совместимость с Kotlin CryptoManager)
- [ ] Интеграционный тест: шифрование на Python → расшифровка → проверка

**Критерий готовности:** Зашифрованный пакет от часов расшифровывается, данные в БД. Тест с реальным вектором от Kotlin-приложения проходит.

### Итерация B4 — Heartbeat и управление (1 неделя)

**Цель:** Мониторинг устройств и административные функции.

- [ ] `POST /api/v1/watch/heartbeat` — пульс устройства + синхронизация времени
- [ ] `GET /api/v1/devices` — список устройств с фильтрами и пагинацией
- [ ] `GET /api/v1/devices/{device_id}` — детали
- [ ] `PATCH /api/v1/devices/{device_id}` — обновление
- [ ] `POST /api/v1/devices/{device_id}/bind` — привязка к сотруднику
- [ ] CRUD для сотрудников (employees)
- [ ] Celery Beat: очистка idempotency_keys, проверка здоровья устройств

**Критерий готовности:** Администратор видит список устройств, их статусы, может управлять привязками.

### Итерация B5 — Смены, данные и аналитика (1-2 недели)

**Цель:** Доступ к сменам, сырым данным и базовая аналитика.

- [ ] `GET /api/v1/shifts` — список смен с фильтрами
- [ ] `GET /api/v1/shifts/{shift_id}` — детали
- [ ] `GET /api/v1/shifts/{shift_id}/data/{type}` — сырые данные с пагинацией
- [ ] `GET /api/v1/analytics/device/{id}/summary` — сводка по устройству
- [ ] `GET /api/v1/analytics/employee/{id}/summary` — сводка по сотруднику
- [ ] `GET /api/v1/analytics/zones` — аналитика BLE-зон
- [ ] `GET /api/v1/admin/stats` — общая статистика
- [ ] `GET /api/v1/admin/packets` — лог пакетов
- [ ] `POST /api/v1/admin/packets/{id}/reprocess` — повторная обработка
- [ ] CSV экспорт для сырых данных

**Критерий готовности:** Полное API для администратора и аналитической системы.

### Итерация B6 — Безопасность, нагрузка и продакшн (1 неделя)

**Цель:** Подготовка к production-развёртыванию.

- [ ] Rate limiting (slowapi) — 10 req/min на device_id
- [ ] Проверка размера payload (max 50 MB)
- [ ] HTTPS (TLS 1.2+), опционально certificate pinning
- [ ] Structured logging (structlog)
- [ ] Health check endpoint (`GET /health`)
- [ ] Нагрузочное тестирование: 100 устройств × 2 пакета/день
- [ ] Очистка старых данных (retention policy)
- [ ] Документация Swagger/OpenAPI финализация
- [ ] CI/CD pipeline (GitHub Actions / GitLab CI)

---

## 13. Финальный чек-лист (Definition of Done для всего backend)

### 13.1. Функциональные требования

- [ ] Устройства регистрируются и получают device_id + device_secret + публичный RSA-ключ
- [ ] Устройства аутентифицируются через JWT (access_token + refresh_token)
- [ ] Зашифрованные пакеты принимаются (202 Accepted) и ставятся в очередь
- [ ] Идемпотентность: повторная отправка → 409 с тем же ответом (TTL ≥ 30 дней)
- [ ] Пакеты расшифровываются (RSA-OAEP + AES-256-GCM)
- [ ] SHA-256 хеш plaintext проверяется на сервере
- [ ] Сырые данные 9 типов сохраняются в отдельные таблицы
- [ ] Статус пакета доступен через GET endpoint
- [ ] Heartbeat обновляет last_heartbeat_at и возвращает server_time
- [ ] Устройства привязываются к сотрудникам
- [ ] Смены доступны через API с фильтрами
- [ ] Сырые данные доступны с пагинацией
- [ ] Базовая аналитика работает
- [ ] Ротация RSA-ключей без потери расшифровки старых пакетов

### 13.2. Нефункциональные требования

- [ ] Время приёма пакета (202 Accepted) ≤ 500 мс
- [ ] Время расшифровки + парсинга ≤ 30 с для 12-часовой смены
- [ ] Batch INSERT 2M+ записей за < 60 с
- [ ] Rate limiting: 10 req/min на устройство
- [ ] Payload до 50 MB
- [ ] Идемпотентность 30+ дней
- [ ] Retention policy: сырые данные 90 дней, пакеты/смены 365 дней
- [ ] Zero дубликатов при повторных отправках
- [ ] Корректная обработка 401/403 (часы прекращают ретраи)
- [ ] Корректная обработка 5xx (часы повторяют с backoff)
- [ ] HTTPS обязателен в production
- [ ] Логирование всех приёмов пакетов с device_id, размером, статусом

### 13.3. Совместимость с Kotlin-клиентом

- [ ] `UploadRequest` поля 1:1 совпадают с `UploadPacketRequest`
- [ ] `UploadResponse` поля 1:1 совпадают с `UploadPacketResponse`
- [ ] `PacketStatusResponse` поля 1:1 совпадают
- [ ] HTTP коды ответов совпадают с обработкой в `NetworkUploader.kt`
- [ ] `Idempotency-Key` header обрабатывается корректно
- [ ] `Authorization: Bearer` header обрабатывается корректно
- [ ] Расшифрованный JSON валидируется по схеме `ShiftPacket`
- [ ] Base64 (NO_WRAP) корректно декодируется на Python
- [ ] AES-GCM (ciphertext+tag) совместимость Java ↔ Python подтверждена тестом

---

## 14. Открытые вопросы для согласования

1. **Admin UI** — нужен ли web-интерфейс (React/Vue) или достаточно REST API + Swagger?
2. **Webhook/Push** — нужны ли уведомления о новых пакетах (WebSocket, SSE)?
3. **Multi-tenancy** — одна компания или несколько (site_id достаточно)?
4. **Классификация активности** — A/B/C классы обрабатываются на backend? Если да, нужна ML-модель.
5. **Экспорт данных** — какие форматы кроме JSON/CSV (Excel, Parquet)?
6. **Retention policy** — подтвердить сроки хранения (90/365 дней).
7. **SLA** — uptime требования (99.9%?), допустимое время недоступности.
8. **Масштабирование** — ожидаемое количество устройств (10/100/1000+)?
9. **Резервное копирование** — политика бэкапов PostgreSQL.
10. **GATEWAY-режим** — реализовывать ли эндпоинт для мобильного шлюза (`POST /api/v1/gateway/packets`)?
