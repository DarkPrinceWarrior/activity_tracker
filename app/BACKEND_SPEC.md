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
│   │   ├── packets.py      # Приём и статус пакетов (раздел 4.2)
│   │   ├── gateway.py      # GATEWAY-режим (раздел 23)
│   │   ├── devices.py      # Управление устройствами (раздел 4.4)
│   │   ├── shifts.py       # Смены и данные (раздел 4.5)
│   │   ├── auth.py         # Аутентификация: устройства (4.1) + web (20.4)
│   │   ├── heartbeat.py    # Heartbeat устройств (раздел 4.3)
│   │   ├── analytics.py    # Аналитика: базовая (4.6) + расширенная (17.4)
│   │   ├── zones.py        # Зоны, маршруты (раздел 16.3)
│   │   ├── employees.py    # Справочник сотрудников (раздел 19.7)
│   │   ├── companies.py    # Компании и бригады (раздел 19.7)
│   │   ├── bindings.py     # Привязки часов (раздел 19.5)
│   │   ├── anomalies.py    # Аномалии (раздел 18.5)
│   │   ├── users.py        # Управление пользователями (раздел 20.4)
│   │   ├── audit.py        # Журнал аудита (раздел 20.4)
│   │   ├── admin.py        # Административные эндпоинты (раздел 4.7)
│   │   └── keys.py         # Управление криптоключами (раздел 4.7.5-4.7.6)
│   └── deps.py             # Зависимости (DI), RBAC middleware (раздел 20.3)
├── core/
│   ├── config.py           # Настройки (Pydantic Settings)
│   ├── security.py         # JWT, хеширование, криптография
│   ├── rbac.py             # require_role, require_scope, anonymize (раздел 20.3.2)
│   └── exceptions.py       # Кастомные исключения
├── models/                 # SQLAlchemy модели
│   ├── device.py           # devices, device_tokens, device_bindings
│   ├── packet.py           # packets, idempotency_keys
│   ├── shift.py            # shifts
│   ├── sensor_data.py      # 9 таблиц сенсорных данных
│   ├── zone.py             # sites, zones, zone_beacons, zone_visits, unknown_beacons
│   ├── activity.py         # activity_intervals, classification_configs
│   ├── metrics.py          # shift_metrics, reaction_times
│   ├── anomaly.py          # anomalies
│   ├── employee.py         # employees, companies, brigades
│   ├── user.py             # users, audit_log
│   ├── downtime.py         # downtime_reasons_catalog, downtime_assignments
│   ├── quality.py          # data_quality_flags
│   └── crypto_key.py       # crypto_keys
├── schemas/                # Pydantic схемы (request/response)
│   ├── packet.py
│   ├── device.py
│   ├── shift.py
│   ├── auth.py
│   ├── analytics.py
│   ├── zone.py
│   ├── activity.py
│   ├── anomaly.py
│   └── employee.py
├── services/               # Бизнес-логика
│   ├── packet_service.py
│   ├── crypto_service.py
│   ├── device_service.py
│   ├── shift_service.py
│   ├── zone_service.py
│   ├── classification_service.py
│   ├── metrics_service.py
│   ├── anomaly_service.py
│   └── analytics_service.py
├── tasks/                  # Celery задачи (раздел 6, 15-18, 21-22, 24)
│   ├── decrypt_task.py     # decrypt_and_parse_packet
│   ├── quality_task.py     # check_data_quality
│   ├── zone_task.py        # build_zone_visits
│   ├── classify_task.py    # classify_activity
│   ├── metrics_task.py     # calculate_shift_metrics
│   ├── anomaly_task.py     # detect_anomalies
│   └── cleanup_task.py     # cleanup_idempotency_keys, cleanup_old_data
├── db/
│   ├── session.py          # Async session factory
│   └── base.py             # Base model
├── migrations/             # Alembic
│   └── versions/
├── tests/
│   ├── test_packets.py
│   ├── test_crypto.py
│   ├── test_devices.py
│   ├── test_classification.py
│   ├── test_metrics.py
│   ├── test_anomalies.py
│   ├── test_rbac.py
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

> **Дополнительные поля** (serial_number, total_bindings_count, total_on_site_hours, charge_status и др.) **описаны в разделе 19.6.**

| Поле | Тип | Описание |
|------|-----|----------|
| `id` | UUID, PK | Внутренний ID |
| `device_id` | VARCHAR(64), UNIQUE, NOT NULL | ID устройства (выдаётся сервером при регистрации) |
| `device_secret_hash` | VARCHAR(256), NOT NULL | Хеш device_secret (bcrypt/argon2) |
| `model` | VARCHAR(64) | Модель часов (e.g. "Galaxy Watch 8") |
| `firmware` | VARCHAR(64) | Версия прошивки |
| `app_version` | VARCHAR(32) | Версия приложения на часах |
| `employee_id` | UUID, FK → employees.id, NULLABLE | Привязка к сотруднику |
| `site_id` | VARCHAR(64), FK → sites.id, NULLABLE | Текущий объект/площадка |
| `status` | ENUM('active','revoked','suspended') | Статус устройства |
| `last_heartbeat_at` | TIMESTAMPTZ, NULLABLE | Последний heartbeat |
| `last_sync_at` | TIMESTAMPTZ, NULLABLE | Последняя синхронизация |
| `timezone` | VARCHAR(64), DEFAULT 'UTC' | Часовой пояс устройства |
| `created_at` | TIMESTAMPTZ, NOT NULL | Дата регистрации |
| `updated_at` | TIMESTAMPTZ, NOT NULL | Дата обновления |
| `is_deleted` | BOOLEAN, DEFAULT false | Мягкое удаление |

### 3.2. Таблица `employees` — Сотрудники

> **Полная расширенная схема таблицы `employees` (согласно ТЗ, таблица 3) описана в разделе 19.1.**
> Включает: `company_id`, `pass_number` (RFID), `personnel_number` (табельный), `brigade_id`, `site_id`, `consent_pd_file` (ФЗ-152) и другие поля.

Краткая версия ключевых полей:

| Поле | Тип | Описание |
|------|-----|----------|
| `id` | UUID, PK | ID сотрудника |
| `full_name` | VARCHAR(256), NOT NULL | ФИО |
| `company_id` | UUID, FK → companies.id, NULLABLE | Компания/подрядчик |
| `position` | VARCHAR(128), NULLABLE | Должность |
| `personnel_number` | VARCHAR(64), NULLABLE, UNIQUE | Табельный номер |
| `pass_number` | VARCHAR(64), NULLABLE | Номер пропуска (RFID) |
| `brigade_id` | UUID, FK → brigades.id, NULLABLE | Бригада |
| `site_id` | VARCHAR(64), FK → sites.id, NULLABLE | Площадка |
| `status` | VARCHAR(16), DEFAULT 'active' | 'active', 'inactive', 'archived' |
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
| `status` | ENUM('accepted','decrypting','parsing','processing','processed','error') | Статус обработки |
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
| `uploaded_from` | VARCHAR(16), DEFAULT 'direct' | Источник: 'direct' (часы) или 'gateway' (шлюз), см. раздел 23 |
| `operator_id` | UUID, FK → users.id, NULLABLE | Оператор, загрузивший пакет (только для gateway) |
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
| `site_id` | VARCHAR(64), FK → sites.id, NULLABLE | Объект/площадка |
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

#### 3.6.9. `downtime_reasons` — Причины простоя (сырые события из пакета)

> Это **сырые данные** из расшифрованного пакета. Справочник причин — в `downtime_reasons_catalog` (раздел 19.4). Привязка к интервалам В1 — в `downtime_assignments` (раздел 22).

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

> **Доступ:** web-токен, роли `admin`, `operator` (своя площадка), `manager` (read-only). См. RBAC в разделе 20.

#### 4.4.1. `GET /api/v1/devices` — Список устройств

**Доступ:** admin, operator, manager (read-only).

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

**Доступ:** admin, operator, manager.

**Response 200:** Полная информация об устройстве + статистика последних пакетов.

#### 4.4.3. `PATCH /api/v1/devices/{device_id}` — Обновление устройства

**Доступ:** admin.

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

**Доступ:** admin, operator (своя площадка).

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
3. Создать запись в `device_bindings` (см. раздел 19.5).
4. Записать в `audit_log` (см. раздел 20.2.2).

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

### 4.6. Аналитика (базовая)

> **Расширенная аналитика с классификацией A1-В4, выработкой (%), временем реакции и V1% описана в разделе 17.**

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
- `RETENTION_DAYS_AUDIT_LOG` = 180 (журнал аудита, согласно ТЗ, п. 7)

### 6.4. Конвейер аналитических задач (после decrypt_and_parse_packet)

> **⚠️ Этот раздел расширен. Полная схема конвейера — в разделе 24.**

**Порядок запуска после `decrypt_and_parse_packet`:**
1. `check_data_quality` → флаги качества (раздел 21)
2. `build_zone_visits` → BLE → зоны, маршрут (раздел 16)
3. `classify_activity` → A1/A2/B1/B2/V1/V2/V3/V4 (раздел 15)
4. `calculate_shift_metrics` → выработка, V1%, время реакции (раздел 17)
5. `detect_anomalies` → 7 типов проверок (раздел 18)

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
RETENTION_DAYS_AUDIT_LOG=180

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

> **⚠️ Этот раздел заменён обновлённым планом в разделе 25.**
> Раздел 25 включает дополнительные итерации B4-B7 для справочников, RBAC, классификации A1-В4, аналитики, антифрода и GATEWAY-режима.
> **Общая оценка: 9-14 недель (2-3.5 месяца), 7 итераций (B1-B7).**

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
4. ~~**Классификация активности** — A/B/C классы обрабатываются на backend?~~ → ✅ **Решено в разделе 15** (Rule-Based + опциональный ML)
5. **Экспорт данных** — какие форматы кроме JSON/CSV (Excel, Parquet)?
6. **Retention policy** — подтвердить сроки хранения (90/365 дней).
7. **SLA** — uptime требования (99.9%?), допустимое время недоступности.
8. **Масштабирование** — ожидаемое количество устройств (10/100/1000+)?
9. **Резервное копирование** — политика бэкапов PostgreSQL.
10. ~~**GATEWAY-режим** — реализовывать ли эндпоинт для мобильного шлюза?~~ → ✅ **Решено в разделе 23** (`POST /api/v1/gateway/packets`)
11. **Калибровка порогов классификации** — нужны ли реальные данные с площадки для подбора порогов (accel_energy_high, gyro_energy_high и др.) в разделе 15?
12. **Матрица расстояний зон** — нужна ли для проверки IMPOSSIBLE_MOVE (раздел 18)? Или достаточно фиксированного min_travel_time_sec?
13. **ML-модель** — планируется ли фаза 2 с обучением модели (раздел 15.2.2)? Если да, кто размечает данные?

---

## 15. Классификация активности A1–В4

### 15.1. Классы и подклассы (согласно ТЗ, таблица 5)

| Класс | Подкласс | Код | Описание | Выработка % |
|-------|----------|-----|----------|-------------|
| **А. Продуктивная** | Высокоинтенсивная | `A1` | Устойчивый повторяющийся паттерн высокой частоты движения (работа инструментом, вибрация) | 100% |
| | Среднеинтенсивная | `A2` | Умеренное движение, фиксация в рабочей зоне >15 мин, без простоя (вязка арматуры, монтаж) | 90–100% |
| **Б. Нейтральная** | Перемещение | `B1` | Движение 4–6 км/ч между рабочими BLE-зонами, без высокоинтенсивных паттернов | 50% |
| | Надзор/контроль | `B2` | Фиксация в зоне, низкая активность, передвижение на месте (ИТР, мастера) | 50–70% |
| **В. Непродуктивная** | Скрытый простой | `V1` | Отсутствие движения, вне зоны отдыха, в рабочее время | 0% |
| | Официальный отдых | `V2` | Отсутствие движения, в зоне "Обед"/"Отдых" | 0% |
| | Нарушение режима | `V3` | Снятие часов или покидание объекта | −100% |
| | Активность вне зоны | `V4` | Паттерны работы вне зоны строительной площадки | 0% |

### 15.2. Алгоритм классификации

#### 15.2.1. Подход: Feature Engineering + Rule-Based + (опционально) ML

**Этап 1 — Извлечение признаков (Feature Extraction):**

Сырые данные разбиваются на **временные окна** (window) фиксированной длительности.

**Настраиваемые параметры:**
```python
class ClassificationConfig(BaseModel):
    window_size_sec: int = 10          # Длина окна (секунды)
    window_overlap_sec: int = 5        # Перекрытие окон
    min_interval_sec: int = 60         # Минимальная длительность интервала одного класса
    smoothing_window: int = 3          # Медианный фильтр для сглаживания
    
    # Пороги акселерометра
    accel_energy_high: float = 50.0    # Порог A1 (высокая энергия)
    accel_energy_medium: float = 15.0  # Порог A2 (средняя энергия)
    accel_energy_low: float = 2.0      # Порог покоя
    
    # Пороги гироскопа
    gyro_energy_high: float = 10.0     # Повторяющиеся вращения (A1)
    gyro_energy_medium: float = 3.0    # Умеренные повороты (A2)
    
    # Пороги шагов (из акселерометра)
    step_frequency_min: float = 1.5    # Мин. частота шагов (Гц) для B1
    step_frequency_max: float = 2.5    # Макс. частота шагов для B1
    walking_speed_min_kmh: float = 3.0 # Мин. скорость ходьбы
    walking_speed_max_kmh: float = 7.0 # Макс. скорость ходьбы
    
    # Пороги пульса (вспомогательные)
    hr_rest_max: int = 80              # Пульс покоя
    hr_work_min: int = 100             # Пульс при физической работе
    
    # Контекст зон
    idle_in_work_zone_min_sec: int = 300  # V1: простой в рабочей зоне > 5 мин
```

**Признаки (features) на каждое окно:**

```python
class WindowFeatures(BaseModel):
    """Признаки, извлекаемые из одного временного окна"""
    window_start_ms: int
    window_end_ms: int
    
    # Акселерометр
    accel_energy: float        # Сумма квадратов амплитуд (signal energy)
    accel_std_x: float         # Стандартное отклонение по X
    accel_std_y: float
    accel_std_z: float
    accel_magnitude_mean: float  # Средний модуль вектора
    accel_magnitude_std: float   # СКО модуля
    accel_peak_frequency: float  # Доминирующая частота (FFT)
    accel_spectral_entropy: float  # Энтропия спектра
    
    # Гироскоп
    gyro_energy: float
    gyro_std_x: float
    gyro_std_y: float
    gyro_std_z: float
    gyro_peak_frequency: float
    
    # Барометр
    baro_delta_hpa: float      # Изменение давления за окно
    baro_trend: str            # 'up' / 'down' / 'stable'
    
    # Магнитометр
    mag_std: float             # СКО модуля (индикатор близости к оборудованию)
    
    # Пульс
    hr_mean: Optional[int]
    hr_std: Optional[float]
    
    # Wear
    is_on_wrist: bool
    
    # BLE контекст
    current_zone_id: Optional[str]
    current_zone_type: Optional[str]  # 'work' / 'rest' / 'transit' / 'storage'
    is_on_site: bool           # Есть ли хотя бы одна BLE-метка площадки
```

**Этап 2 — Классификация (Rule-Based):**

```
Для каждого окна:

1. Проверка wear:
   └── is_on_wrist == false → V3 (нарушение)

2. Проверка нахождения на площадке:
   └── is_on_site == false AND accel_energy > low → V4 (вне зоны)

3. Проверка зоны отдыха:
   └── zone_type == 'rest' AND accel_energy < low → V2 (официальный отдых)

4. Проверка высокоинтенсивной работы:
   ├── accel_energy > high
   ├── AND gyro_energy > gyro_high
   ├── AND accel_peak_frequency в диапазоне вибрации (5-25 Гц)
   ├── AND zone_type == 'work'
   └── → A1

5. Проверка среднеинтенсивной работы:
   ├── accel_energy > medium
   ├── AND gyro_energy > gyro_medium
   ├── AND zone_type == 'work'
   └── → A2

6. Проверка перемещения:
   ├── step_frequency в [1.5, 2.5] Гц
   ├── AND accel_magnitude ритмичный (шаговый паттерн)
   └── → B1

7. Проверка надзора:
   ├── accel_energy в [low, medium]
   ├── AND зона рабочая
   ├── AND есть микродвижения (не полный покой)
   └── → B2

8. Скрытый простой (по умолчанию):
   ├── accel_energy < low
   ├── AND zone_type == 'work'
   ├── AND длительность > idle_in_work_zone_min_sec
   └── → V1

9. Иначе → B2 (неопределённая низкая активность)
```

**Этап 3 — Постобработка:**

1. Медианный фильтр (smoothing_window) для удаления кратковременных "всплесков".
2. Объединение смежных окон одного класса в **интервалы**.
3. Удаление интервалов короче `min_interval_sec` (поглощение соседними).
4. Запись интервалов в БД.

#### 15.2.2. Опциональный ML-подход (фаза 2)

Для повышения точности можно заменить правила на ML-модель:

```python
# Варианты моделей:
# 1. Random Forest / XGBoost на WindowFeatures → class
# 2. 1D-CNN на сырых данных окна → class
# 3. LSTM на последовательности окон → class

# Обучение:
# - Разметить 50-100 смен вручную (A1/A2/B1/B2/V1/V2/V3/V4)
# - Обучить модель
# - Валидация: 80/20 split по сменам (не по окнам!)
# - Метрики: accuracy, F1 per class, confusion matrix

# Интеграция:
# - Модель хранится как артефакт (pickle/ONNX)
# - Версионируется в crypto_keys или отдельной таблице
# - Celery task вызывает predict() вместо rule-based
```

### 15.3. Таблицы БД

#### 15.3.1. Таблица `activity_intervals`

| Поле | Тип | Описание |
|------|-----|----------|
| `id` | BIGSERIAL, PK | ID |
| `shift_id` | UUID, FK → shifts.id | Смена |
| `packet_id` | VARCHAR(64), FK → packets.packet_id | Пакет |
| `device_id` | VARCHAR(64), FK → devices.device_id | Устройство |
| `employee_id` | UUID, FK → employees.id, NULLABLE | Сотрудник |
| `activity_class` | VARCHAR(2), NOT NULL | Код: 'A1','A2','B1','B2','V1','V2','V3','V4' |
| `start_ts_ms` | BIGINT, NOT NULL | Начало интервала (Unix ms) |
| `end_ts_ms` | BIGINT, NOT NULL | Конец интервала (Unix ms) |
| `duration_sec` | INTEGER, NOT NULL | Длительность (секунды) |
| `zone_id` | VARCHAR(64), FK → zones.id, NULLABLE | Зона (если определена) |
| `confidence` | FLOAT, DEFAULT 1.0 | Уверенность классификации (0.0–1.0) |
| `features_json` | JSONB, NULLABLE | Средние признаки интервала (для отладки) |
| `created_at` | TIMESTAMPTZ | Время создания |

**Индексы:** `(shift_id)`, `(device_id, start_ts_ms)`, `(activity_class)`, `(employee_id, start_ts_ms)`

#### 15.3.2. Таблица `classification_configs`

| Поле | Тип | Описание |
|------|-----|----------|
| `id` | SERIAL, PK | ID |
| `name` | VARCHAR(64), UNIQUE | Название конфигурации |
| `config_json` | JSONB, NOT NULL | ClassificationConfig как JSON |
| `is_active` | BOOLEAN, DEFAULT false | Активная конфигурация |
| `version` | INTEGER, DEFAULT 1 | Версия |
| `created_at` | TIMESTAMPTZ | Время создания |
| `updated_at` | TIMESTAMPTZ | Время обновления |

### 15.4. Celery задача `classify_activity`

**Запуск:** Автоматически после `decrypt_and_parse_packet` (статус 'processed').

**Алгоритм:**
```
1. Загрузить расшифрованные данные смены из БД
   ├── accel, gyro, baro, mag, hr, ble, wear
   └── Загрузить активную ClassificationConfig

2. Извлечь BLE-контекст
   ├── Определить текущую зону для каждого момента времени
   └── (используя zone_visits, см. раздел 16)

3. Разбить на окна (window_size_sec, overlap)
   └── Для каждого окна извлечь WindowFeatures

4. Классифицировать каждое окно
   ├── Rule-based или ML predict()
   └── Результат: массив (window_start, window_end, class, confidence)

5. Постобработка
   ├── Медианный фильтр
   ├── Объединить смежные окна одного класса → интервалы
   └── Удалить короткие интервалы (< min_interval_sec)

6. Сохранить в activity_intervals
   └── Batch INSERT

7. Запустить calculate_shift_metrics (раздел 17)
```

### 15.5. API эндпоинты

#### `GET /api/v1/shifts/{shift_id}/activity` — Интервалы активности смены

**Response 200:**
```json
{
  "shift_id": "uuid",
  "total_intervals": 48,
  "intervals": [
    {
      "activity_class": "A2",
      "start_ts_ms": 1700000000000,
      "end_ts_ms": 1700003600000,
      "duration_sec": 3600,
      "zone_id": "zone_work_01",
      "zone_name": "КУГ-2 (секция 1)",
      "confidence": 0.92
    }
  ],
  "summary": {
    "A1_sec": 7200,
    "A2_sec": 14400,
    "B1_sec": 3600,
    "B2_sec": 1800,
    "V1_sec": 900,
    "V2_sec": 3600,
    "V3_sec": 0,
    "V4_sec": 0,
    "total_sec": 31500
  }
}
```

#### `GET /api/v1/admin/classification/config` — Текущая конфигурация

#### `PUT /api/v1/admin/classification/config` — Обновление конфигурации

**Request Body:** `ClassificationConfig` JSON.
При обновлении создаётся новая версия. Старая сохраняется.

---

## 16. Геолокация и маршрут по BLE

### 16.1. Модель зон

Каждая BLE-метка (beacon) привязана к **зоне** на площадке. Зона имеет тип, определяющий интерпретацию активности.

#### 16.1.1. Таблица `sites` (площадки/объекты)

| Поле | Тип | Описание |
|------|-----|----------|
| `id` | VARCHAR(64), PK | ID площадки |
| `name` | VARCHAR(256), NOT NULL | Название площадки |
| `address` | TEXT, NULLABLE | Адрес |
| `timezone` | VARCHAR(64), DEFAULT 'Europe/Moscow' | Часовой пояс |
| `status` | VARCHAR(16), DEFAULT 'active' | 'active', 'archived' |
| `created_at` | TIMESTAMPTZ | Время создания |
| `updated_at` | TIMESTAMPTZ | Время обновления |

#### 16.1.2. Таблица `zones` (зоны площадки)

| Поле | Тип | Описание |
|------|-----|----------|
| `id` | VARCHAR(64), PK | ID зоны |
| `site_id` | VARCHAR(64), FK → sites.id, NOT NULL | Площадка |
| `name` | VARCHAR(256), NOT NULL | Название ("КУГ-2 секция 1", "Бытовка №3") |
| `zone_type` | VARCHAR(32), NOT NULL | Тип: 'work', 'rest', 'transit', 'storage', 'entry', 'other' |
| `productivity_percent` | INTEGER, DEFAULT 100 | Процент продуктивности зоны (100% для рабочей, 0% для отдыха) |
| `lat` | FLOAT, NULLABLE | Широта (для карты) |
| `lon` | FLOAT, NULLABLE | Долгота (для карты) |
| `floor` | INTEGER, NULLABLE | Этаж |
| `status` | VARCHAR(16), DEFAULT 'active' | 'active', 'archived' |
| `created_at` | TIMESTAMPTZ | |
| `updated_at` | TIMESTAMPTZ | |

**Индекс:** `(site_id, status)`

#### 16.1.3. Таблица `zone_beacons` (привязка BLE-меток к зонам)

| Поле | Тип | Описание |
|------|-----|----------|
| `id` | SERIAL, PK | ID |
| `zone_id` | VARCHAR(64), FK → zones.id, NOT NULL | Зона |
| `beacon_id` | VARCHAR(128), NOT NULL, UNIQUE | ID BLE-метки (из BleScanner на часах) |
| `lat` | FLOAT, NULLABLE | Координаты метки |
| `lon` | FLOAT, NULLABLE | |
| `status` | VARCHAR(16), DEFAULT 'active' | 'active', 'removed' |
| `created_at` | TIMESTAMPTZ | |

**Индекс:** `(beacon_id)` — для быстрого поиска зоны по beacon_id

#### 16.1.4. Таблица `zone_visits` (посещения зон)

| Поле | Тип | Описание |
|------|-----|----------|
| `id` | BIGSERIAL, PK | ID |
| `shift_id` | UUID, FK → shifts.id | Смена |
| `device_id` | VARCHAR(64), FK → devices.device_id | Устройство |
| `employee_id` | UUID, FK → employees.id, NULLABLE | Сотрудник |
| `zone_id` | VARCHAR(64), FK → zones.id | Зона |
| `enter_ts_ms` | BIGINT, NOT NULL | Время входа в зону (Unix ms) |
| `exit_ts_ms` | BIGINT, NULLABLE | Время выхода из зоны (NULL = ещё в зоне) |
| `duration_sec` | INTEGER, NULLABLE | Длительность нахождения (секунды) |
| `avg_rssi` | INTEGER, NULLABLE | Средний уровень сигнала |
| `created_at` | TIMESTAMPTZ | |

**Индексы:** `(shift_id)`, `(device_id, enter_ts_ms)`, `(zone_id, enter_ts_ms)`

#### 16.1.5. Таблица `unknown_beacons` (неизвестные метки)

| Поле | Тип | Описание |
|------|-----|----------|
| `id` | BIGSERIAL, PK | ID |
| `beacon_id` | VARCHAR(128), NOT NULL | Неизвестный beacon_id |
| `device_id` | VARCHAR(64) | Какое устройство обнаружило |
| `first_seen_ts_ms` | BIGINT | Первое обнаружение |
| `last_seen_ts_ms` | BIGINT | Последнее обнаружение |
| `count` | INTEGER, DEFAULT 1 | Количество обнаружений |
| `status` | VARCHAR(16), DEFAULT 'new' | 'new', 'identified', 'ignored' |
| `created_at` | TIMESTAMPTZ | |

### 16.2. Алгоритм формирования маршрута

**Запуск:** Celery задача `build_zone_visits`, после `decrypt_and_parse_packet`.

```
Вход: ble_events из расшифрованного пакета (отсортированы по ts_ms)

Параметры (настраиваемые):
  MIN_ZONE_DURATION_SEC = 30    # Минимальное время в зоне (сглаживание "дребезга")
  MAX_GAP_SEC = 300             # Макс. разрыв без BLE-событий (зона не теряется)
  RSSI_THRESHOLD = -85          # Мин. уровень сигнала для считывания

Алгоритм:

1. Для каждого ble_event:
   ├── Найти zone_id по beacon_id в zone_beacons
   ├── Если не найден → записать в unknown_beacons, пропустить
   └── Если RSSI < RSSI_THRESHOLD → пропустить (слабый сигнал)

2. Формирование интервалов:
   ├── current_zone = None
   ├── Для каждого события (по времени):
   │   ├── Если zone_id == current_zone → продлить текущий интервал
   │   ├── Если zone_id != current_zone:
   │   │   ├── Если прошло < MIN_ZONE_DURATION_SEC → игнорировать (дребезг)
   │   │   └── Иначе → закрыть текущий интервал, открыть новый
   │   └── Если gap > MAX_GAP_SEC → закрыть текущий (выход из зоны)
   └── Закрыть последний интервал

3. Постобработка:
   ├── Удалить интервалы < MIN_ZONE_DURATION_SEC
   ├── Объединить смежные интервалы одной зоны (если разрыв < MAX_GAP_SEC)
   └── Рассчитать duration_sec для каждого интервала

4. Сохранить в zone_visits (Batch INSERT)

5. Сформировать маршрут:
   └── route = [zone_visits отсортированные по enter_ts_ms]
```

### 16.3. API эндпоинты

#### `GET /api/v1/shifts/{shift_id}/zones` — Посещения зон за смену

**Response 200:**
```json
{
  "shift_id": "uuid",
  "total_visits": 12,
  "total_zones": 5,
  "visits": [
    {
      "zone_id": "zone_01",
      "zone_name": "КУГ-2 (секция 1)",
      "zone_type": "work",
      "enter_ts_ms": 1700000000000,
      "exit_ts_ms": 1700003600000,
      "duration_sec": 3600,
      "avg_rssi": -65
    }
  ],
  "summary_by_zone": [
    {
      "zone_id": "zone_01",
      "zone_name": "КУГ-2 (секция 1)",
      "zone_type": "work",
      "total_duration_sec": 18000,
      "visit_count": 4
    }
  ]
}
```

#### `GET /api/v1/shifts/{shift_id}/route` — Маршрут перемещений

**Response 200:**
```json
{
  "shift_id": "uuid",
  "route": [
    { "zone_id": "zone_entry", "zone_name": "Проходная", "enter_ts_ms": 1700000000000, "exit_ts_ms": 1700000300000 },
    { "zone_id": "zone_01", "zone_name": "КУГ-2 (секция 1)", "enter_ts_ms": 1700000300000, "exit_ts_ms": 1700003600000 },
    { "zone_id": "zone_rest", "zone_name": "Бытовка №3", "enter_ts_ms": 1700003600000, "exit_ts_ms": 1700007200000 }
  ]
}
```

#### CRUD для зон и меток

- `GET /api/v1/sites` — Список площадок
- `POST /api/v1/sites` — Создать площадку
- `GET /api/v1/sites/{site_id}/zones` — Зоны площадки
- `POST /api/v1/sites/{site_id}/zones` — Создать зону
- `PUT /api/v1/zones/{zone_id}` — Обновить зону
- `POST /api/v1/zones/{zone_id}/beacons` — Привязать BLE-метку к зоне
- `DELETE /api/v1/zones/{zone_id}/beacons/{beacon_id}` — Отвязать метку
- `GET /api/v1/admin/unknown-beacons` — Список неизвестных меток

---

## 17. Расчёт выработки и метрик

### 17.1. Формулы (согласно ТЗ, п. 5.3.4.4)

#### 17.1.1. Выработка (%)

Интегральный показатель эффективного рабочего времени:

```
Выработка(%) = (
    Time(A1) × 1.00 +
    Time(A2) × 0.95 +
    Time(B1) × 0.50 +
    Time(B2) × 0.60
) / Time(на площадке, исключая V2) × 100%
```

**Примечания:**
- V2 (официальный отдых) исключается из знаменателя
- V3 (нарушение) штрафует: −100% за этот интервал
- Коэффициенты A2 (0.95), B2 (0.60) — настраиваемые

#### 17.1.2. Коэффициент скрытого простоя В1 (%)

```
V1(%) = Time(V1 в рабочих зонах) / Time(нахождения в рабочих зонах) × 100%
```

Где "рабочая зона" — зоны с `zone_type = 'work'`.

#### 17.1.3. Среднее время реакции

Интервал от момента входа сотрудника в рабочую BLE-зону до начала продуктивной деятельности (A1 или A2).

```
reaction_time = первый A1/A2.start_ts_ms − zone_visit.enter_ts_ms
```

**Расчёт:**
- По каждому входу в рабочую зону (событийно)
- Среднее / медиана по смене
- Агрегаты по группам (бригада/подрядчик/площадка)

**Исключения (п. 5.3.4.4):**
- Если A1/A2 не наступили после входа в зону → "нет продуктивной активности"
- Правило: считать до выхода из зоны (настраиваемо)

### 17.2. Таблицы БД

#### 17.2.1. Таблица `shift_metrics` (метрики по смене)

| Поле | Тип | Описание |
|------|-----|----------|
| `id` | BIGSERIAL, PK | ID |
| `shift_id` | UUID, FK → shifts.id, UNIQUE | Смена |
| `device_id` | VARCHAR(64), FK → devices.device_id | Устройство |
| `employee_id` | UUID, FK → employees.id, NULLABLE | Сотрудник |
| `site_id` | VARCHAR(64), FK → sites.id, NULLABLE | Площадка |
| `shift_duration_sec` | INTEGER | Общая длительность смены |
| `on_site_duration_sec` | INTEGER | Время на площадке |
| `a1_duration_sec` | INTEGER, DEFAULT 0 | Время A1 |
| `a2_duration_sec` | INTEGER, DEFAULT 0 | Время A2 |
| `b1_duration_sec` | INTEGER, DEFAULT 0 | Время B1 |
| `b2_duration_sec` | INTEGER, DEFAULT 0 | Время B2 |
| `v1_duration_sec` | INTEGER, DEFAULT 0 | Время V1 |
| `v2_duration_sec` | INTEGER, DEFAULT 0 | Время V2 |
| `v3_duration_sec` | INTEGER, DEFAULT 0 | Время V3 |
| `v4_duration_sec` | INTEGER, DEFAULT 0 | Время V4 |
| `productivity_percent` | FLOAT | Выработка (%) |
| `v1_percent` | FLOAT | Коэффициент скрытого простоя (%) |
| `avg_reaction_time_sec` | FLOAT, NULLABLE | Среднее время реакции (сек) |
| `median_reaction_time_sec` | FLOAT, NULLABLE | Медиана времени реакции (сек) |
| `zones_visited` | INTEGER, DEFAULT 0 | Количество посещённых зон |
| `work_zones_visited` | INTEGER, DEFAULT 0 | Количество рабочих зон |
| `avg_hr_bpm` | INTEGER, NULLABLE | Средний пульс за смену |
| `wear_compliance_percent` | FLOAT, DEFAULT 100.0 | % времени с часами на руке |
| `anomalies_count` | INTEGER, DEFAULT 0 | Количество аномалий |
| `data_quality_score` | FLOAT, DEFAULT 1.0 | Качество данных (0.0–1.0) |
| `created_at` | TIMESTAMPTZ | |
| `updated_at` | TIMESTAMPTZ | |

**Индексы:** `(employee_id, shift_id)`, `(site_id, created_at)`, `(device_id)`

#### 17.2.2. Таблица `reaction_times` (время реакции — детально)

| Поле | Тип | Описание |
|------|-----|----------|
| `id` | BIGSERIAL, PK | ID |
| `shift_id` | UUID, FK → shifts.id | Смена |
| `employee_id` | UUID, FK → employees.id, NULLABLE | Сотрудник |
| `zone_id` | VARCHAR(64), FK → zones.id | Рабочая зона |
| `zone_enter_ts_ms` | BIGINT | Вход в зону |
| `activity_start_ts_ms` | BIGINT, NULLABLE | Начало A1/A2 (NULL = не начал) |
| `reaction_time_sec` | INTEGER, NULLABLE | Время реакции (сек), NULL = не начал |
| `has_productive_activity` | BOOLEAN, DEFAULT false | Была ли A1/A2 в этом визите |
| `created_at` | TIMESTAMPTZ | |

**Индексы:** `(shift_id)`, `(employee_id, zone_id)`

### 17.3. Celery задача `calculate_shift_metrics`

**Запуск:** После `classify_activity` (раздел 15) и `build_zone_visits` (раздел 16).

```
1. Загрузить activity_intervals и zone_visits для смены

2. Рассчитать длительности по классам:
   ├── Суммировать duration_sec по каждому activity_class
   └── a1_sec, a2_sec, b1_sec, b2_sec, v1_sec, v2_sec, v3_sec, v4_sec

3. Рассчитать выработку:
   ├── productive = a1*1.00 + a2*0.95 + b1*0.50 + b2*0.60
   ├── total_work = on_site - v2 (исключить отдых)
   └── productivity_percent = productive / total_work * 100

4. Рассчитать V1%:
   ├── v1_in_work_zones = SUM(V1 duration WHERE zone_type='work')
   ├── time_in_work_zones = SUM(zone_visit duration WHERE zone_type='work')
   └── v1_percent = v1_in_work_zones / time_in_work_zones * 100

5. Рассчитать время реакции:
   ├── Для каждого zone_visit WHERE zone_type='work':
   │   ├── Найти первый A1/A2 interval после enter_ts_ms
   │   ├── reaction = A1.start_ts_ms - zone_visit.enter_ts_ms
   │   └── Записать в reaction_times
   ├── avg_reaction = AVG(reaction_time_sec)
   └── median_reaction = MEDIAN(reaction_time_sec)

6. Рассчитать wear compliance:
   └── wear_percent = SUM(wear='on' duration) / shift_duration * 100

7. Сохранить в shift_metrics (INSERT or UPDATE)

8. Запустить detect_anomalies (раздел 18)
```

### 17.4. API эндпоинты

#### `GET /api/v1/shifts/{shift_id}/metrics` — Метрики смены

**Response 200:**
```json
{
  "shift_id": "uuid",
  "employee_name": "Иванов И.И.",
  "site_name": "Площадка №1",
  "shift_duration_sec": 43200,
  "on_site_duration_sec": 41400,
  "productivity_percent": 78.5,
  "v1_percent": 3.2,
  "avg_reaction_time_sec": 145,
  "median_reaction_time_sec": 120,
  "activity_breakdown": {
    "A1_sec": 7200, "A1_percent": 17.4,
    "A2_sec": 14400, "A2_percent": 34.8,
    "B1_sec": 3600, "B1_percent": 8.7,
    "B2_sec": 1800, "B2_percent": 4.3,
    "V1_sec": 900, "V1_percent": 2.2,
    "V2_sec": 3600, "V2_percent": 8.7,
    "V3_sec": 0, "V3_percent": 0.0,
    "V4_sec": 0, "V4_percent": 0.0
  },
  "wear_compliance_percent": 99.8,
  "zones_visited": 5,
  "avg_hr_bpm": 82,
  "anomalies_count": 0,
  "data_quality_score": 0.95
}
```

#### `GET /api/v1/analytics/productivity` — Сводная выработка

**Query:** `site_id`, `employee_id`, `company_id`, `brigade_id`, `date_from`, `date_to`, `page`, `page_size`

**Response 200:**
```json
{
  "period": { "from": "2026-02-01", "to": "2026-02-25" },
  "items": [
    {
      "employee_id": "uuid",
      "employee_name": "Иванов И.И.",
      "company": "ООО Строймонтаж",
      "total_shifts": 20,
      "total_hours_on_site": 240,
      "avg_productivity_percent": 76.3,
      "avg_v1_percent": 4.1,
      "avg_reaction_time_sec": 155,
      "anomalies_total": 1
    }
  ],
  "aggregates": {
    "avg_productivity_percent": 72.1,
    "avg_v1_percent": 5.8,
    "avg_reaction_time_sec": 180
  },
  "total": 42,
  "page": 1,
  "page_size": 50
}
```

#### `GET /api/v1/shifts/{shift_id}/reaction-times` — Детальное время реакции

**Response 200:**
```json
{
  "shift_id": "uuid",
  "reactions": [
    {
      "zone_id": "zone_01",
      "zone_name": "КУГ-2 (секция 1)",
      "zone_enter_ts_ms": 1700000300000,
      "activity_start_ts_ms": 1700000420000,
      "reaction_time_sec": 120,
      "has_productive_activity": true
    }
  ],
  "avg_sec": 145,
  "median_sec": 120
}
```

---

## 18. Антифрод и аномалии

### 18.1. Правила обнаружения (согласно ТЗ, п. 5.3.4.6)

| № | Тип аномалии | Код | Условие | Серьёзность |
|---|-------------|-----|---------|-------------|
| 1 | Активность при снятых часах | `WORK_OFF_WRIST` | activity_class ∈ {A1,A2} AND wear_state = 'off' | critical |
| 2 | Частые снятия/надевания | `FREQUENT_WEAR_TOGGLE` | Количество переключений wear on↔off > N за T минут | warning |
| 3 | Работа вне площадки | `WORK_OFF_SITE` | activity_class ∈ {A1,A2} AND is_on_site = false (нет BLE-меток) | warning |
| 4 | Невозможное перемещение | `IMPOSSIBLE_MOVE` | Переход zone_A → zone_B за < X секунд (при известном расстоянии) | critical |
| 5 | Длительное отсутствие данных | `DATA_GAP` | Разрыв в данных сенсоров > M минут (при wear = 'on') | info |
| 6 | Снятие в рабочее время | `OFF_WRIST_WORK_HOURS` | wear = 'off' во время активной смены > K минут | warning |
| 7 | Пульс = 0 при wear = on | `ZERO_HR_ON_WRIST` | hr.bpm = 0 при wear = 'on' длительно (возможен муляж) | critical |

### 18.2. Настраиваемые параметры

```python
class AnomalyConfig(BaseModel):
    # Частые снятия/надевания
    wear_toggle_max_count: int = 5       # Макс. переключений
    wear_toggle_window_min: int = 30     # За окно (минуты)
    
    # Невозможное перемещение
    min_travel_time_sec: int = 60        # Мин. время между зонами
    # (или матрица расстояний zones × zones)
    
    # Разрыв данных
    max_data_gap_min: int = 10           # Макс. допустимый разрыв (минуты)
    
    # Снятие в рабочее время
    max_off_wrist_min: int = 5           # Допустимое время без часов
    
    # Пульс
    zero_hr_max_duration_sec: int = 300  # Макс. время нулевого пульса
```

### 18.3. Таблица `anomalies`

| Поле | Тип | Описание |
|------|-----|----------|
| `id` | BIGSERIAL, PK | ID |
| `shift_id` | UUID, FK → shifts.id | Смена |
| `device_id` | VARCHAR(64), FK → devices.device_id | Устройство |
| `employee_id` | UUID, FK → employees.id, NULLABLE | Сотрудник |
| `anomaly_type` | VARCHAR(32), NOT NULL | Код: 'WORK_OFF_WRIST', 'FREQUENT_WEAR_TOGGLE', ... |
| `severity` | VARCHAR(16), NOT NULL | 'critical', 'warning', 'info' |
| `start_ts_ms` | BIGINT, NOT NULL | Начало аномалии |
| `end_ts_ms` | BIGINT, NULLABLE | Конец аномалии |
| `description` | TEXT | Описание ("Снятие часов в 14:32 в зоне КУГ-2") |
| `details_json` | JSONB, NULLABLE | Доп. данные (зоны, расстояния, количество) |
| `status` | VARCHAR(16), DEFAULT 'new' | 'new', 'confirmed', 'false_positive', 'under_review' |
| `resolved_by` | UUID, FK → users.id, NULLABLE | Кто разобрал |
| `resolved_at` | TIMESTAMPTZ, NULLABLE | Когда |
| `comment` | TEXT, NULLABLE | Комментарий при разборе |
| `created_at` | TIMESTAMPTZ | |

**Индексы:** `(shift_id)`, `(employee_id, created_at)`, `(anomaly_type, status)`, `(severity, status)`

### 18.4. Celery задача `detect_anomalies`

**Запуск:** После `calculate_shift_metrics` (раздел 17).

```
1. Загрузить данные смены:
   ├── wear_events, activity_intervals, zone_visits, hr_samples
   └── Загрузить AnomalyConfig

2. Проверка WORK_OFF_WRIST:
   ├── Для каждого activity_interval WHERE class ∈ {A1, A2}:
   │   └── Проверить wear_state в этот период
   └── Если wear = 'off' → создать аномалию (critical)

3. Проверка FREQUENT_WEAR_TOGGLE:
   ├── Скользящее окно (wear_toggle_window_min)
   ├── Подсчитать переключения on↔off
   └── Если count > wear_toggle_max_count → аномалия (warning)

4. Проверка WORK_OFF_SITE:
   ├── Для каждого activity_interval WHERE class ∈ {A1, A2}:
   │   └── Проверить наличие BLE-событий в этот период
   └── Если BLE пустой → аномалия (warning)

5. Проверка IMPOSSIBLE_MOVE:
   ├── Для каждой пары последовательных zone_visits:
   │   ├── delta = visit[i+1].enter_ts - visit[i].exit_ts
   │   └── Если delta < min_travel_time_sec → аномалия (critical)
   └── (Опционально: матрица расстояний между зонами)

6. Проверка DATA_GAP:
   ├── Найти разрывы в данных акселерометра > max_data_gap_min
   └── При wear = 'on' → аномалия (info)

7. Проверка OFF_WRIST_WORK_HOURS:
   ├── Суммировать wear='off' интервалы
   └── Если total > max_off_wrist_min → аномалия (warning)

8. Проверка ZERO_HR_ON_WRIST:
   ├── Найти интервалы hr.bpm = 0 при wear = 'on'
   └── Если длительность > zero_hr_max_duration_sec → аномалия (critical)

9. Batch INSERT в anomalies

10. UPDATE shift_metrics SET anomalies_count = COUNT(anomalies)
```

### 18.5. API эндпоинты

#### `GET /api/v1/shifts/{shift_id}/anomalies` — Аномалии смены

**Response 200:**
```json
{
  "shift_id": "uuid",
  "anomalies": [
    {
      "id": 1234,
      "anomaly_type": "OFF_WRIST_WORK_HOURS",
      "severity": "warning",
      "start_ts_ms": 1700010000000,
      "end_ts_ms": 1700010600000,
      "description": "Часы сняты на 10 мин в рабочее время (зона КУГ-2)",
      "status": "new"
    }
  ],
  "total": 1
}
```

#### `GET /api/v1/anomalies` — Список всех аномалий (Admin)

**Query:** `site_id`, `employee_id`, `severity`, `status`, `anomaly_type`, `date_from`, `date_to`, `page`, `page_size`

#### `PATCH /api/v1/anomalies/{anomaly_id}` — Разбор аномалии

**Request Body:**
```json
{
  "status": "confirmed",
  "comment": "Подтверждено: сотрудник снимал часы для мытья рук"
}
```

#### `GET /api/v1/admin/anomaly-config` — Текущая конфигурация

#### `PUT /api/v1/admin/anomaly-config` — Обновление конфигурации

---

## 19. Расширенные справочники

### 19.1. Расширенная таблица `employees` (согласно ТЗ, таблица 3)

| Поле | Тип | Описание |
|------|-----|----------|
| `id` | UUID, PK | ID сотрудника в системе |
| `full_name` | VARCHAR(256), NOT NULL | ФИО |
| `company_id` | UUID, FK → companies.id, NULLABLE | Компания/подрядчик |
| `position` | VARCHAR(128), NULLABLE | Должность |
| `pass_number` | VARCHAR(64), NULLABLE | Номер пропуска (RFID) |
| `personnel_number` | VARCHAR(64), NULLABLE, UNIQUE | Табельный номер |
| `brigade_id` | UUID, FK → brigades.id, NULLABLE | Бригада |
| `site_id` | VARCHAR(64), FK → sites.id, NULLABLE | Площадка по умолчанию |
| `consent_pd_file` | VARCHAR(512), NULLABLE | Ссылка на скан согласия ПДн (ФЗ-152) |
| `consent_pd_date` | DATE, NULLABLE | Дата согласия |
| `status` | VARCHAR(16), DEFAULT 'active' | 'active', 'inactive', 'archived' |
| `created_at` | TIMESTAMPTZ | |
| `updated_at` | TIMESTAMPTZ | |
| `is_deleted` | BOOLEAN, DEFAULT false | Мягкое удаление |

### 19.2. Таблица `companies` (подрядчики/компании)

| Поле | Тип | Описание |
|------|-----|----------|
| `id` | UUID, PK | ID |
| `name` | VARCHAR(256), NOT NULL | Название ("ООО Строймонтажинжиниринг") |
| `inn` | VARCHAR(12), NULLABLE | ИНН |
| `status` | VARCHAR(16), DEFAULT 'active' | 'active', 'archived' |
| `created_at` | TIMESTAMPTZ | |
| `updated_at` | TIMESTAMPTZ | |

### 19.3. Таблица `brigades` (бригады)

| Поле | Тип | Описание |
|------|-----|----------|
| `id` | UUID, PK | ID |
| `name` | VARCHAR(128), NOT NULL | Название бригады |
| `company_id` | UUID, FK → companies.id | Компания |
| `site_id` | VARCHAR(64), FK → sites.id, NULLABLE | Площадка |
| `foreman_id` | UUID, FK → employees.id, NULLABLE | Бригадир |
| `status` | VARCHAR(16), DEFAULT 'active' | |
| `created_at` | TIMESTAMPTZ | |

### 19.4. Таблица `downtime_reasons_catalog` (справочник причин простоя)

| Поле | Тип | Описание |
|------|-----|----------|
| `id` | VARCHAR(32), PK | Код причины (reason_id) |
| `name` | VARCHAR(256), NOT NULL | "Жду инструмент", "Жду материал", "Нет задания" и т.д. |
| `category` | VARCHAR(64), NULLABLE | Категория: 'material', 'equipment', 'task', 'other' |
| `is_active` | BOOLEAN, DEFAULT true | Активна ли причина |
| `sort_order` | INTEGER, DEFAULT 0 | Порядок в списке на часах |
| `created_at` | TIMESTAMPTZ | |

### 19.5. Таблица `device_bindings` (история привязок часы↔сотрудник)

| Поле | Тип | Описание |
|------|-----|----------|
| `id` | BIGSERIAL, PK | ID |
| `device_id` | VARCHAR(64), FK → devices.device_id | Часы |
| `employee_id` | UUID, FK → employees.id | Сотрудник |
| `site_id` | VARCHAR(64), FK → sites.id | Площадка |
| `shift_date` | DATE, NOT NULL | Дата смены |
| `shift_type` | VARCHAR(16), DEFAULT 'day' | 'day', 'night' |
| `bound_at` | TIMESTAMPTZ, NOT NULL | Время выдачи |
| `bound_by` | UUID, FK → users.id, NULLABLE | Кто выдал (оператор) |
| `unbound_at` | TIMESTAMPTZ, NULLABLE | Время возврата |
| `unbound_by` | UUID, FK → users.id, NULLABLE | Кто принял |
| `status` | VARCHAR(16), DEFAULT 'active' | 'active', 'closed', 'lost' |

**Индексы:** `(device_id, status)`, `(employee_id, shift_date)`, `(site_id, shift_date)`

### 19.6. Расширенная таблица `devices` (согласно ТЗ, таблица 4)

Дополнительные поля к существующей таблице `devices` (раздел 3):

| Поле | Тип | Описание |
|------|-----|----------|
| `serial_number` | VARCHAR(64), NULLABLE | Серийный номер |
| `total_bindings_count` | INTEGER, DEFAULT 0 | Количество людей, носивших часы |
| `total_on_site_hours` | FLOAT, DEFAULT 0 | Общее время на площадке (часы) |
| `last_bound_employee_id` | UUID, FK → employees.id, NULLABLE | Кому выдавались последний раз |
| `last_bound_at` | TIMESTAMPTZ, NULLABLE | Дата/время последней выдачи |
| `charge_status` | VARCHAR(16), DEFAULT 'unknown' | 'charged', 'low', 'charging', 'unknown' |

### 19.7. API эндпоинты справочников

#### Сотрудники
- `GET /api/v1/employees` — Список (query: company_id, brigade_id, site_id, status, search, page)
- `POST /api/v1/employees` — Создать
- `GET /api/v1/employees/{id}` — Детали
- `PUT /api/v1/employees/{id}` — Обновить
- `DELETE /api/v1/employees/{id}` — Мягкое удаление (is_deleted=true)
- `POST /api/v1/employees/import` — Импорт из CSV/XLSX

#### Компании
- `GET /api/v1/companies` — Список
- `POST /api/v1/companies` — Создать
- `PUT /api/v1/companies/{id}` — Обновить

#### Бригады
- `GET /api/v1/brigades` — Список (query: company_id, site_id)
- `POST /api/v1/brigades` — Создать
- `PUT /api/v1/brigades/{id}` — Обновить

#### Причины простоя
- `GET /api/v1/downtime-reasons` — Список (для часов и админки)
- `POST /api/v1/downtime-reasons` — Создать
- `PUT /api/v1/downtime-reasons/{id}` — Обновить

#### Привязки часов
- `POST /api/v1/bindings` — Создать привязку (выдача часов)
- `PUT /api/v1/bindings/{id}/close` — Закрыть привязку (возврат часов)
- `GET /api/v1/bindings` — История привязок (query: device_id, employee_id, date, site_id)

---

## 20. RBAC и web-пользователи

### 20.1. Ролевая модель (согласно ТЗ, раздел 3)

| Роль | Код | Область доступа | Ключевые права |
|------|-----|-----------------|----------------|
| Администратор | `admin` | Глобально (все площадки) | Полный доступ: пользователи, справочники, данные, аналитика, аудит |
| Оператор площадки | `operator` | Своя площадка | Выдача/возврат часов, выгрузка данных, журнал операций |
| Руководитель | `manager` | Своя площадка/объект/компания | Дашборды, детализация, отчёты, разбор аномалий |
| Аналитик | `analyst` | Своя площадка/объект/компания | Read-only, агрегаты **без персональной детализации** |

### 20.2. Таблицы БД

#### 20.2.1. Таблица `users`

| Поле | Тип | Описание |
|------|-----|----------|
| `id` | UUID, PK | ID пользователя |
| `email` | VARCHAR(256), NOT NULL, UNIQUE | Логин (e-mail) |
| `password_hash` | VARCHAR(256), NOT NULL | Хеш пароля (argon2) |
| `full_name` | VARCHAR(256), NOT NULL | ФИО |
| `phone` | VARCHAR(32), NULLABLE | Телефон |
| `role` | VARCHAR(16), NOT NULL | 'admin', 'operator', 'manager', 'analyst' |
| `scope_type` | VARCHAR(16), NULLABLE | Тип области: 'site', 'company', 'brigade', 'global' |
| `scope_ids` | JSONB, DEFAULT '[]' | ID площадок/компаний/бригад (массив) |
| `status` | VARCHAR(16), DEFAULT 'active' | 'active', 'blocked' |
| `last_login_at` | TIMESTAMPTZ, NULLABLE | Последний вход |
| `password_changed_at` | TIMESTAMPTZ, NULLABLE | Когда менял пароль |
| `created_at` | TIMESTAMPTZ | |
| `updated_at` | TIMESTAMPTZ | |
| `is_deleted` | BOOLEAN, DEFAULT false | |

**Индексы:** `(email)`, `(role, status)`

#### 20.2.2. Таблица `audit_log` (журнал аудита)

| Поле | Тип | Описание |
|------|-----|----------|
| `id` | BIGSERIAL, PK | ID |
| `user_id` | UUID, FK → users.id | Кто |
| `action` | VARCHAR(64), NOT NULL | Действие: 'login', 'create_employee', 'bind_device', 'export_report', ... |
| `entity_type` | VARCHAR(32), NULLABLE | Тип объекта: 'employee', 'device', 'zone', 'anomaly', ... |
| `entity_id` | VARCHAR(128), NULLABLE | ID объекта |
| `details_json` | JSONB, NULLABLE | Дополнительные данные (что изменилось) |
| `ip_address` | VARCHAR(45), NULLABLE | IP-адрес |
| `user_agent` | VARCHAR(512), NULLABLE | User-Agent |
| `created_at` | TIMESTAMPTZ, NOT NULL | Когда |

**Индексы:** `(user_id, created_at)`, `(action, created_at)`, `(entity_type, entity_id)`

**Retention:** ≥ 180 дней (согласно ТЗ, п. 7).

### 20.3. Авторизация и проверка доступа

#### 20.3.1. JWT для web-пользователей

```python
# JWT payload для web-пользователей (отдельно от device-токенов)
{
    "sub": "user-uuid",
    "email": "ivanov@example.com",
    "role": "manager",
    "scope_type": "site",
    "scope_ids": ["site_01", "site_02"],
    "type": "web",
    "iat": 1700000000,
    "exp": 1700028800
}
```

**Настройки:**
- `access_token` TTL: 8 часов
- `refresh_token` TTL: 30 дней
- Блокировка после 5 неудачных попыток входа (на 15 минут)

#### 20.3.2. Middleware проверки доступа

```python
# Декоратор/dependency для эндпоинтов
def require_role(*roles: str):
    """Проверяет роль пользователя"""

def require_scope(entity_site_id: str):
    """Проверяет что entity_site_id входит в scope_ids пользователя"""

def anonymize_for_analyst(data: dict):
    """Убирает ФИО и персональные данные для роли analyst"""
```

**Правила:**
- `admin` — доступ ко всему
- `operator` — только свои площадки, только привязки/выгрузки
- `manager` — свои площадки, полная детализация
- `analyst` — свои площадки, **без ФИО/табельных** (обезличенно, ФЗ-152)

### 20.4. API эндпоинты

#### Аутентификация web-пользователей
- `POST /api/v1/auth/login` — Вход (email + password → tokens)
- `POST /api/v1/auth/refresh` — Обновление токена
- `POST /api/v1/auth/logout` — Выход (отзыв токена)
- `GET /api/v1/auth/me` — Профиль текущего пользователя
- `PUT /api/v1/auth/password` — Смена пароля

#### Управление пользователями (только admin)
- `GET /api/v1/users` — Список пользователей
- `POST /api/v1/users` — Создать (отправляет пароль на email)
- `PUT /api/v1/users/{id}` — Обновить роль/scope/статус
- `POST /api/v1/users/{id}/block` — Заблокировать
- `POST /api/v1/users/{id}/reset-password` — Сброс пароля

#### Журнал аудита (admin + manager read-only)
- `GET /api/v1/audit-log` — Лента действий (query: user_id, action, entity_type, date_from, date_to, page)
- `GET /api/v1/audit-log/export` — Экспорт (CSV)

---

## 21. Флаги качества данных

### 21.1. Типы флагов

| Код | Описание | Условие | Влияние на метрики |
|-----|----------|---------|-------------------|
| `NO_BLE` | Нет BLE-данных в пакете | ble_events пустой | Геолокация недоступна, зоны не определены |
| `NO_WEAR` | Нет данных о ношении | wear_events пустой | Невозможно определить V3 |
| `NO_HR` | Нет данных пульса | hr_samples пустой | Пульс не учитывается |
| `INCOMPLETE_PACKET` | Неполный пакет | shift_end - shift_start < ожидаемой длительности | Метрики неточны |
| `TIME_GAPS` | Разрывы по времени | Разрывы > 10 мин в данных сенсоров | Возможны пропуски интервалов |
| `LOW_SAMPLE_RATE` | Низкая частота данных | Частота < 50% от ожидаемой (25 Гц для accel) | Классификация менее точна |
| `SENSOR_FAULT` | Сбой сенсора | Все значения = 0 или NaN длительное время | Данные ненадёжны |
| `CLOCK_DRIFT` | Рассогласование часов | time_sync.server_time_offset_ms > 60000 (1 мин) | Временные метки неточны |

### 21.2. Таблица `data_quality_flags`

| Поле | Тип | Описание |
|------|-----|----------|
| `id` | BIGSERIAL, PK | ID |
| `packet_id` | VARCHAR(64), FK → packets.packet_id | Пакет |
| `shift_id` | UUID, FK → shifts.id | Смена |
| `flag_type` | VARCHAR(32), NOT NULL | Код флага |
| `severity` | VARCHAR(16), NOT NULL | 'critical', 'warning', 'info' |
| `description` | TEXT | Описание ("Нет BLE-данных: геолокация недоступна") |
| `details_json` | JSONB, NULLABLE | Детали (ожидаемое vs фактическое количество) |
| `created_at` | TIMESTAMPTZ | |

**Индексы:** `(packet_id)`, `(shift_id)`, `(flag_type)`

### 21.3. Celery задача `check_data_quality`

**Запуск:** После `decrypt_and_parse_packet`, перед `classify_activity`.

```
1. Проверить наличие каждого типа данных:
   ├── ble_events пуст? → NO_BLE
   ├── wear_events пуст? → NO_WEAR
   └── hr_samples пуст? → NO_HR

2. Проверить полноту пакета:
   ├── actual_duration = shift_end - shift_start
   ├── expected_duration = из конфигурации смены
   └── actual < expected * 0.8 → INCOMPLETE_PACKET

3. Проверить разрывы:
   ├── Найти gaps > 10 мин в accel данных
   └── gaps > 0 → TIME_GAPS (указать количество и суммарную длительность)

4. Проверить частоту:
   ├── actual_rate = count(accel) / duration_sec
   ├── expected_rate = 25 Гц
   └── actual < expected * 0.5 → LOW_SAMPLE_RATE

5. Проверить clock drift:
   └── abs(server_time_offset_ms) > 60000 → CLOCK_DRIFT

6. Рассчитать data_quality_score:
   ├── score = 1.0
   ├── Для каждого critical флага: score -= 0.3
   ├── Для каждого warning флага: score -= 0.1
   ├── score = max(0.0, score)
   └── UPDATE shift_metrics SET data_quality_score = score

7. Batch INSERT в data_quality_flags
```

### 21.4. API

Флаги отображаются в ответах:
- `GET /api/v1/shifts/{shift_id}` — поле `quality_flags: [...]`
- `GET /api/v1/shifts/{shift_id}/metrics` — поле `data_quality_score`
- `GET /api/v1/admin/packets/{packet_id}/log` — поле `quality_flags`

---

## 22. Причины простоя — серверная привязка к интервалам В1

### 22.1. Логика (согласно ТЗ, п. 5.3.4.5)

Причины простоя привязываются к интервалам `activity_class = 'V1'` двумя способами:

1. **С часов** — сотрудник выбирает причину на экране часов (данные в `downtime_reasons` пакета)
2. **С web** — руководитель/оператор указывает причину при разборе смены

### 22.2. Таблица `downtime_assignments` (привязка причин к интервалам)

| Поле | Тип | Описание |
|------|-----|----------|
| `id` | BIGSERIAL, PK | ID |
| `activity_interval_id` | BIGINT, FK → activity_intervals.id | Интервал V1 |
| `shift_id` | UUID, FK → shifts.id | Смена |
| `reason_id` | VARCHAR(32), FK → downtime_reasons_catalog.id | Код причины |
| `source` | VARCHAR(16), NOT NULL | 'watch' (с часов) или 'web' (руководителем) |
| `assigned_by` | UUID, FK → users.id, NULLABLE | Кто назначил (для source='web') |
| `zone_id` | VARCHAR(64), FK → zones.id, NULLABLE | В какой зоне |
| `comment` | TEXT, NULLABLE | Комментарий |
| `created_at` | TIMESTAMPTZ | |
| `updated_at` | TIMESTAMPTZ | |

**Индексы:** `(shift_id)`, `(reason_id)`, `(activity_interval_id)`

### 22.3. Автоматическая привязка причин с часов

При обработке пакета (`decrypt_and_parse_packet`), после классификации:

```
1. Для каждого downtime_reason из расшифрованного пакета:
   ├── Найти activity_interval WHERE class='V1'
   │   AND start_ts_ms <= reason.ts_ms <= end_ts_ms
   ├── Если найден → INSERT downtime_assignments (source='watch')
   └── Если не найден → логировать warning (причина вне интервала V1)
```

### 22.4. API

- `GET /api/v1/shifts/{shift_id}/downtimes` — Список простоев с причинами
- `POST /api/v1/shifts/{shift_id}/downtimes/{interval_id}/assign` — Назначить причину (web)
- `PUT /api/v1/shifts/{shift_id}/downtimes/{interval_id}/assign` — Изменить причину

**Response `GET /api/v1/shifts/{shift_id}/downtimes`:**
```json
{
  "shift_id": "uuid",
  "downtimes": [
    {
      "interval_id": 1234,
      "start_ts_ms": 1700010000000,
      "end_ts_ms": 1700010900000,
      "duration_sec": 900,
      "zone_id": "zone_01",
      "zone_name": "КУГ-2 (секция 1)",
      "reason_id": "wait_material",
      "reason_name": "Жду материал",
      "source": "watch",
      "assigned_by": null
    }
  ],
  "total_downtime_sec": 900,
  "reasons_summary": [
    { "reason_id": "wait_material", "reason_name": "Жду материал", "total_sec": 900, "count": 1 }
  ]
}
```

Аудит: история изменений причин записывается в `audit_log`.

---

## 23. GATEWAY-режим (приём от мобильного шлюза)

### 23.1. Назначение

Мобильное приложение оператора (шлюз) считывает данные с часов по Bluetooth и передаёт на сервер. В отличие от DIRECT-режима (часы → сервер), здесь промежуточным звеном выступает смартфон оператора.

### 23.2. `POST /api/v1/gateway/packets` — Приём пакета от шлюза

**Доступ:** `Authorization: Bearer <web_token>` (роль: operator).

**Request Body (расширенный UploadRequest):**
```json
{
  "packet_id": "uuid",
  "device_id": "dev_a1b2c3d4e5f6",
  "shift_start_ts": 1700000000000,
  "shift_end_ts": 1700043200000,
  "schema_version": 1,
  "payload_enc": "Base64...",
  "payload_key_enc": "Base64...",
  "iv": "Base64...",
  "payload_hash": "sha256hex...",
  
  "operator_id": "uuid",
  "site_id": "site_01",
  "employee_id": "uuid",
  "binding_id": 1234,
  "uploaded_from": "gateway",
  "gateway_device_info": {
    "model": "Samsung Galaxy Tab A9",
    "os_version": "Android 14",
    "app_version": "1.0.0"
  }
}
```

**Логика:**
1. Валидация оператора (JWT web-token, роль = operator).
2. Проверка что operator имеет доступ к site_id.
3. Проверка что device_id привязан к employee_id (через device_bindings).
4. Далее — аналогично `POST /api/v1/watch/packets` (идемпотентность, сохранение, Celery task).
5. Дополнительно: записать `operator_id` и `uploaded_from = 'gateway'` в packets.

**Response:** Аналогично 4.2.1 (202 Accepted / 409 Conflict).

### 23.3. Отличия от DIRECT-режима

| Аспект | DIRECT (часы → сервер) | GATEWAY (часы → шлюз → сервер) |
|--------|------------------------|-------------------------------|
| Аутентификация | Device JWT (device_id + device_secret) | Web JWT (operator email + password) |
| Знает employee_id | Нет (привязка на сервере по device_bindings) | Да (оператор указывает при выгрузке) |
| Канал | Wi-Fi/LTE с часов | BLE → смартфон → Wi-Fi/LTE |
| Оффлайн | Очередь на часах (WorkManager) | Очередь на смартфоне (локальная БД) |
| Поле uploaded_from | 'direct' | 'gateway' |

---

## 24. Обновлённый конвейер обработки данных (полная схема)

```
Часы → [DIRECT: POST /watch/packets] → Сервер
    или
Часы → BLE → Шлюз → [GATEWAY: POST /gateway/packets] → Сервер

Сервер:
  1. Приём пакета (202 Accepted)
     └── Сохранение в packets, idempotency_keys

  2. [Celery] decrypt_and_parse_packet
     ├── Расшифровка (RSA-OAEP + AES-256-GCM)
     ├── Проверка SHA-256 хеша
     ├── Парсинг JSON (ShiftPacketSchema)
     └── Batch INSERT сырых данных (9 таблиц)

  3. [Celery] check_data_quality
     └── Флаги качества → data_quality_flags

  4. [Celery] build_zone_visits
     ├── beacon_id → zone_id (через zone_beacons)
     ├── Формирование интервалов посещения зон
     ├── Сглаживание дребезга
     └── Маршрут перемещений → zone_visits

  5. [Celery] classify_activity
     ├── Feature extraction (окна 10 сек)
     ├── Классификация A1/A2/B1/B2/V1/V2/V3/V4
     ├── Постобработка (медианный фильтр, merge)
     └── → activity_intervals

  6. [Celery] calculate_shift_metrics
     ├── Выработка (%), V1%, время реакции
     ├── Привязка причин простоя (с часов → V1 интервалы)
     └── → shift_metrics, reaction_times, downtime_assignments

  7. [Celery] detect_anomalies
     ├── 7 типов проверок
     └── → anomalies

  Статус пакета: accepted → decrypting → parsing → processing → processed
```

---

## 25. Обновлённые итерации реализации

### Итерация B1 — Инфраструктура и приём пакетов (1-2 недели)

- [ ] Инициализация FastAPI + Docker + PostgreSQL + Redis
- [ ] Alembic миграции: `devices`, `packets`, `idempotency_keys`, `packet_processing_log`
- [ ] `POST /api/v1/watch/packets` — приём, валидация, идемпотентность (раздел 4.2)
- [ ] `GET /api/v1/watch/packets/{packet_id}` — статус
- [ ] Middleware: CORS, logging, error handling
- [ ] docker-compose (api + db + redis)
- [ ] Тесты приёма пакетов

**Критерий:** curl отправляет пакет → 202. Повтор → 409.

### Итерация B2 — Аутентификация устройств (1 неделя)

- [ ] Таблицы: `device_tokens`, `registration_codes`
- [ ] Регистрация, токены, refresh, revoke (раздел 4.1)
- [ ] JWT dependency для FastAPI
- [ ] Тесты аутентификации

**Критерий:** Часы регистрируются, получают токен, отправляют пакет с `Authorization: Bearer`.

### Итерация B3 — Криптография и парсинг (1-2 недели)

- [ ] Таблицы сенсорных данных (9 таблиц), `shifts`, `crypto_keys`
- [ ] `crypto_service.py` — RSA-ключи, расшифровка (раздел 5)
- [ ] Celery: `decrypt_and_parse_packet` (раздел 6.1)
- [ ] Batch INSERT сенсорных данных
- [ ] Крипто-тесты (совместимость с Kotlin CryptoManager)

**Критерий:** Зашифрованный пакет расшифровывается, данные в БД.

### Итерация B4 — Справочники и RBAC (1-2 недели)

- [ ] Таблицы: `sites`, `zones`, `zone_beacons`, `companies`, `brigades`, расширенная `employees`
- [ ] Таблицы: `users`, `audit_log`, `downtime_reasons_catalog`, `device_bindings`
- [ ] CRUD для справочников (зоны, сотрудники, компании, бригады, причины простоя)
- [ ] Привязки часов (device_bindings)
- [ ] RBAC: JWT web-пользователей, middleware require_role/require_scope
- [ ] Аутентификация web: login/refresh/logout/me/password
- [ ] Управление пользователями (admin)
- [ ] Журнал аудита
- [ ] Тесты RBAC + справочники

### Итерация B5 — Геолокация, классификация и метрики (2-3 недели)

- [ ] Таблицы: `zone_visits`, `unknown_beacons`, `activity_intervals`, `classification_configs`
- [ ] Таблицы: `shift_metrics`, `reaction_times`, `data_quality_flags`, `downtime_assignments`
- [ ] Celery: `check_data_quality`
- [ ] Celery: `build_zone_visits` (BLE → зоны, маршрут, дребезг)
- [ ] Celery: `classify_activity` (feature extraction + rule-based классификация)
- [ ] Celery: `calculate_shift_metrics` (выработка, V1%, время реакции)
- [ ] Celery: привязка причин простоя с часов к интервалам V1
- [ ] API: `/shifts/{id}/zones`, `/shifts/{id}/route`
- [ ] API: `/shifts/{id}/activity`, `/shifts/{id}/metrics`
- [ ] API: `/analytics/productivity`
- [ ] API: `/shifts/{id}/reaction-times`, `/shifts/{id}/downtimes`
- [ ] Тесты классификации и метрик

**Критерий готовности:** Пакет обрабатывается до конца: сырые данные → зоны → классы A1-V4 → выработка и метрики в БД.

### Итерация B6 — Антифрод и аномалии (1 неделя)

- [ ] Таблица `anomalies`
- [ ] Celery: `detect_anomalies` (7 типов проверок)
- [ ] API: `/shifts/{id}/anomalies`, `/anomalies`, `PATCH /anomalies/{id}`
- [ ] Конфигурация аномалий (AnomalyConfig)
- [ ] Тесты антифрод-правил

### Итерация B7 — Heartbeat, GATEWAY и продакшн (1-2 недели)

- [ ] Heartbeat (см. раздел 4.3)
- [ ] `POST /api/v1/gateway/packets` (GATEWAY-режим)
- [ ] Rate limiting, HTTPS, structured logging
- [ ] Health check, нагрузочные тесты
- [ ] Celery Beat расписание (очистки, проверка здоровья)
- [ ] CI/CD, Swagger финализация
- [ ] Интеграционные тесты (полный конвейер)

**Общая оценка: 9-14 недель (2-3.5 месяца)**
