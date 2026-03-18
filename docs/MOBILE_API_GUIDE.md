# Watch Backend — Полное руководство по API для мобильного разработчика (Kotlin)

> **Base URL**: `https://<server>/api/v1`
> **Формат данных**: JSON (`Content-Type: application/json`)
> **Даты**: ISO 8601 (`2026-03-17T09:00:00Z`), timestamp-поля — миллисекунды Unix

---

## Что это за система и зачем она нужна

Watch Backend — это серверная часть системы мониторинга активности рабочих на промышленных объектах (стройка, завод, шахта и т.д.).

**Как это работает в реальной жизни:**
1. На объекте есть **часы** (wearable-устройства) с датчиками (акселерометр, гироскоп, барометр, пульсометр и т.д.)
2. Утром мастер/оператор **привязывает** часы к конкретному рабочему через **мобильное приложение** (Gateway App)
3. Рабочий носит часы — часы собирают сенсорные данные
4. Мобильное приложение принимает данные от часов по **Bluetooth** и **отправляет на сервер** через Gateway API
5. Сервер **расшифровывает** пакеты (AES-GCM + RSA-OAEP), **парсит** сенсорные данные и **классифицирует** активность
6. В конце смены мастер **закрывает привязку** → данные надёжно привязаны к конкретному сотруднику
7. Руководство видит **аналитику**: продуктивность, простои, аномалии, время реакции и т.д.

**Почему привязка (binding) — ключевая концепция:**
Часы — это **общее оборудование**. Сегодня Иванов носит W-001, завтра — Петров. Без привязки данные привязаны к **устройству**, а не к **человеку**. `binding_id` в каждом пакете гарантирует, что данные попадут к правильному сотруднику.

---

## Оглавление

1. [Архитектура и общие сведения](#1-архитектура-и-общие-сведения)
2. [Аутентификация и авторизация](#2-аутентификация-и-авторизация)
3. [Auth — Пользователи](#3-auth--пользователи)
4. [Auth — Устройства](#4-auth--устройства)
5. [Auth — Регистрация устройства через мобильное](#5-auth--регистрация-устройства-через-мобильное)
6. [Устройства (Devices)](#6-устройства-devices)
7. [Сотрудники (Employees)](#7-сотрудники-employees)
8. [Смены (Shifts)](#8-смены-shifts)
9. [Watch — Отправка данных с часов](#9-watch--отправка-данных-с-часов)
10. [Gateway — Отправка через шлюз](#10-gateway--отправка-через-шлюз)
11. [Пакеты (Packets)](#11-пакеты-packets)
12. [Привязки (Bindings)](#12-привязки-bindings)
13. [Справочники: Компании, Бригады, Объекты](#13-справочники-компании-бригады-объекты)
14. [Зоны и Маяки (Zones & Beacons)](#14-зоны-и-маяки-zones--beacons)
15. [Аналитика (Analytics)](#15-аналитика-analytics)
16. [Аномалии (Anomalies)](#16-аномалии-anomalies)
17. [Причины простоев (Downtime Reasons)](#17-причины-простоев-downtime-reasons)
18. [Аудит-лог (Audit Log)](#18-аудит-лог-audit-log)
19. [Администрирование (Admin)](#19-администрирование-admin)
20. [Crypto Keys](#20-crypto-keys)
21. [Device Tokens](#21-device-tokens)
22. [Idempotency Keys](#22-idempotency-keys)
23. [Sensor Samples](#23-sensor-samples)
24. [Packet Processing Logs](#24-packet-processing-logs)
25. [Перечисления (Enums)](#25-перечисления-enums)
26. [Пагинация и фильтрация](#26-пагинация-и-фильтрация)
27. [Rate Limiting](#27-rate-limiting)
28. [Типичные flow для мобильного приложения](#28-типичные-flow-для-мобильного-приложения)
29. [Привязка часов к сотруднику — полный lifecycle](#29-привязка-часов-к-сотруднику--полный-lifecycle)

---

## 1. Архитектура и общие сведения

Бэкенд построен на **FastAPI** (Python, async). Структура:

```
src/
├── api/v1/          # Роуты (endpoints)
├── api/middleware/   # Middleware (обработка исключений, логирование)
├── schemas/         # Pydantic-схемы (Request/Response модели)
├── services/        # Бизнес-логика
│   └── pipeline/    # Асинхронный pipeline обработки пакетов
├── repositories/    # Работа с БД (паттерн Repository)
├── db/models/       # SQLAlchemy-модели
│   └── migrations/  # Alembic-миграции
├── utils/           # Авторизация (JWT), зависимости (DI)
├── limiter.py       # Rate limiting (SlowAPI)
└── logging_config.py # Настройка логирования (structlog)
```

### Ключевые технологии
- **FastAPI** — async REST API
- **SQLAlchemy 2.0** + **Advanced-Alchemy** — ORM и Repository pattern
- **SlowAPI** — rate limiting для критичных эндпоинтов
- **structlog** — структурированное логирование
- **Alembic** — миграции БД

### Pipeline обработки пакетов

Когда мобильное приложение отправляет пакет (`POST /gateway/packets` или `POST /watch/packets`), сервер **не обрабатывает его синхронно**. Вместо этого:

1. Пакет сохраняется в БД со статусом `accepted`
2. Запускается **background task** (асинхронный pipeline)
3. Pipeline проходит этапы: `decrypting` → `parsing` → `processing` → `processed` (или `error`)
4. На каждом этапе создаётся запись в `packet_processing_logs` для аудита

Это значит, что ответ `202 Accepted` **не гарантирует** успешную обработку — только приём. Для проверки используйте `GET /watch/packets/{packet_id}`.

### Общий формат ошибок

```json
{
  "detail": "Описание ошибки"
}
```

Для ошибок валидации Pydantic (422) формат другой:
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
| `400` | Невалидные данные запроса (Idempotency-Key, device_id mismatch и т.д.) |
| `401` | Не авторизован (токен отсутствует/невалиден/x-device-id отсутствует) |
| `403` | Нет прав доступа (устройство suspended/revoked, роль не подходит) |
| `404` | Ресурс не найден |
| `409` | Конфликт (дубликат пакета, активная привязка уже есть, устройство уже зарег.) |
| `422` | Ошибка валидации Pydantic (неправильные типы/значения полей) |
| `429` | Превышен rate-limit |
| `500` | Внутренняя ошибка сервера |

---

## 2. Аутентификация и авторизация

### Два типа авторизации

| Тип | Заголовок | Для кого |
|-----|-----------|----------|
| **User JWT** | `Authorization: Bearer <access_token>` | Веб/моб приложение оператора/админа |
| **Device Token** | `Authorization: Bearer <device_access_token>` | Часы (устройство) |

### User JWT
- Получение: `POST /api/v1/auth/login`
- Передача: заголовок `Authorization: Bearer <token>`
- Роли: `admin`, `operator`

### Device Token
- Регистрация устройства: `POST /api/v1/auth/device/register`
- Получение токенов: `POST /api/v1/auth/device/token`
- Обновление: `POST /api/v1/auth/device/refresh`
- Передача: заголовок `Authorization: Bearer <access_token>`

---

## 3. Auth — Пользователи

**Prefix**: `/api/v1/auth`

### `POST /auth/login`
Авторизация пользователя. Возвращает JWT-токен.

**Request:**
```json
{
  "email": "admin@example.com",
  "password": "secret123"
}
```

**Response** `200`:
```json
{
  "access_token": "eyJhbGciOiJIUzI1NiIs...",
  "token_type": "Bearer"
}
```

---

### `GET /auth/me`
Получить данные текущего пользователя. **Требует**: User JWT.

**Response** `200`:
```json
{
  "id": "uuid",
  "email": "admin@example.com",
  "full_name": "Иванов Иван",
  "role": "admin",
  "status": "active"
}
```

---

### `POST /auth/users`
Создать нового пользователя. **Требует**: роль `admin`.

**Request:**
```json
{
  "email": "user@example.com",
  "password": "password123",
  "full_name": "Петров Пётр",
  "role": "operator"
}
```

**Response** `201`: Аналогично `MeResponse`.

---

## 4. Auth — Устройства

**Prefix**: `/api/v1/auth/device`

### `POST /auth/device/register`
Регистрация нового устройства по одноразовому коду. **Rate limit**: 3/мин.

> **Когда использовать**: Этот эндпоинт предназначен для **автономных часов**, которые самостоятельно регистрируются в системе по одноразовому коду, полученному от администратора. Для регистрации через мобильное приложение оператора используйте [`POST /auth/device/register-via-mobile`](#5-auth--регистрация-устройства-через-мобильное).

**Request:**
```json
{
  "registration_code": "ABC123",
  "device_id": "WATCH-001",
  "model": "WatchPro X1",
  "firmware": "1.2.0",
  "app_version": "2.0.1",
  "employee_id": "uuid | null",
  "site_id": "SITE-01 | null",
  "timezone": "Europe/Moscow"
}
```

**Response** `200`:
```json
{
  "device_id": "WATCH-001",
  "device_secret": "generated_secret_string",
  "server_public_key_pem": "-----BEGIN PUBLIC KEY-----\n...",
  "server_time": "2026-03-17T09:00:00Z"
}
```

> ⚠️ **Важно**: `device_secret` выдаётся один раз. Сохраните его в SecureStorage на устройстве!

---

### `POST /auth/device/token`
Получить access + refresh токены по `device_id` и `device_secret`. **Rate limit**: 5/мин.

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

**Response** `200`: Аналогично `DeviceTokenResponse`.

---

### `POST /auth/device/revoke`
Отозвать токены устройства.

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

## 5. Auth — Регистрация устройства через мобильное

**Prefix**: `/api/v1/auth/device`

> **Зачем это нужно**: Когда оператор находится на объекте с новыми часами, он может зарегистрировать устройство **прямо через мобильное приложение**, без участия администратора и без одноразового кода. Сервер автоматически создаёт устройство, привязку к сотруднику и запись в аудит-лог.

### `POST /auth/device/register-via-mobile`
Регистрация нового устройства через мобильное приложение оператора. **Требует**: User JWT (роль `operator` или `admin`).

В отличие от `POST /auth/device/register`, этот эндпоинт:
- Не требует одноразового кода (registration_code)
- Автоматически создаёт **привязку** (binding) к указанному сотруднику
- Автоматически записывает **аудит-лог**
- Возвращает статус `registered`

**Request:**
```json
{
  "device_id": "WATCH-001",
  "employee_id": "550e8400-e29b-41d4-a716-446655440000",
  "site_id": "SITE-01",
  "model": "WatchPro X1",
  "firmware": "1.2.0",
  "app_version": "2.0.1"
}
```

| Поле | Тип | Обязательное | Описание |
|------|-----|:---:|----------|
| `device_id` | `string` (1-64) | ✅ | ID устройства |
| `employee_id` | `UUID` | ✅ | ID сотрудника (проверяется на существование и `is_deleted`) |
| `site_id` | `string` (1-64) | ✅ | ID площадки |
| `model` | `string` (max 64) | ❌ | Модель устройства |
| `firmware` | `string` (max 64) | ❌ | Версия прошивки |
| `app_version` | `string` (max 32) | ❌ | Версия приложения на часах |

**Response** `201`:
```json
{
  "device_id": "WATCH-001",
  "status": "registered",
  "binding_id": null
}
```

**Ошибки:**
| Код | Причина |
|-----|---------|
| `401` | Не авторизован |
| `403` | Нет прав (не `operator`/`admin`) |
| `404` | Сотрудник не найден или удалён |
| `409` | Устройство с таким `device_id` уже зарегистрировано |

---

### `GET /auth/device/{device_id}/registration-status`
Проверить статус регистрации устройства и **получить секрет** (одноразово).

> ⚠️ **Важно**: `device_secret` и `server_public_key_pem` возвращаются **только при первом вызове** этого эндпоинта после регистрации. После чтения секрет стирается на сервере (`device_secret_plain = null`).

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

**Response** `200` (устройство не найдено или не активно):
```json
{
  "registered": false
}
```

---

## 6. Устройства (Devices)

**Prefix**: `/api/v1/devices`

### `GET /devices`
Список устройств с пагинацией.

**Query params:**
| Параметр | Тип | Описание |
|----------|-----|----------|
| `status` | `active\|revoked\|suspended` | Фильтр по статусу |
| `employee_id` | `UUID` | Фильтр по сотруднику |
| `page` | `int` (≥1) | Страница, default=1 |
| `page_size` | `int` (1..100) | Размер, default=20 |

**Response** `200`:
```json
{
  "items": [
    {
      "device_id": "WATCH-001",
      "model": "WatchPro X1",
      "firmware": "1.2.0",
      "app_version": "2.0.1",
      "employee_id": "uuid | null",
      "site_id": "SITE-01 | null",
      "status": "active",
      "timezone": "Europe/Moscow",
      "last_heartbeat_at": "2026-03-17T08:55:00Z | null",
      "last_sync_at": "2026-03-17T08:50:00Z | null",
      "created_at": "2026-03-01T10:00:00Z",
      "updated_at": "2026-03-17T08:55:00Z"
    }
  ],
  "total": 42,
  "page": 1,
  "page_size": 20
}
```

---

### `GET /devices/{device_id}`
Детальная информация об устройстве (с данными последнего пакета).

**Response** `200`: `DeviceListItem` + поля:
```json
{
  "...все поля DeviceListItem...",
  "last_packet_id": "pkt_abc123 | null",
  "last_packet_status": "processed | null",
  "last_packet_received_at": "2026-03-17T08:50:00Z | null"
}
```

---

### `GET /devices/{device_id}/full`
Полные данные: информация + все пакеты + все сенсорные потоки.

**Response** `200`:
```json
{
  "...все поля DeviceDetailItem...",
  "serial_number": "SN-123 | null",
  "battery_level": 85.5,
  "charge_status": "charging | unknown",
  "total_bindings_count": 15,
  "total_on_site_hours": 120.5,
  "last_bound_at": "2026-03-17T07:00:00Z | null",
  "packets": [
    {
      "packet_id": "pkt_abc",
      "status": "processed",
      "received_at": "...",
      "shift_start_ts": 1710658800000,
      "shift_end_ts": 1710702000000,
      "schema_version": 1,
      "payload_size_bytes": 45000,
      "uploaded_from": "direct"
    }
  ],
  "sensors": {
    "accel": [...],
    "gyro": [...],
    "baro": [...],
    "mag": [...],
    "heart_rate": [...],
    "ble": [...],
    "wear": [...],
    "battery": [...],
    "downtime": [...]
  }
}
```

---

### `PATCH /devices/{device_id}`
Обновить данные устройства (partial update).

**Request:**
```json
{
  "model": "WatchPro X2",
  "firmware": "1.3.0",
  "app_version": "2.1.0",
  "employee_id": "uuid | null",
  "site_id": "SITE-02 | null",
  "status": "active | suspended",
  "timezone": "Europe/Moscow"
}
```
Все поля опциональны. **Response** `200`: `DeviceDetailItem`.

---

### `POST /devices/{device_id}/bind`
Привязать устройство к сотруднику и объекту.

**Request:**
```json
{
  "employee_id": "uuid",
  "site_id": "SITE-01"
}
```

**Response** `200`:
```json
{
  "device_id": "WATCH-001",
  "binding_id": "uuid",
  "employee_id": "uuid",
  "site_id": "SITE-01",
  "shift_date": "2026-03-17",
  "bound_at": "2026-03-17T07:00:00Z"
}
```

---

## 7. Сотрудники (Employees)

**Prefix**: `/api/v1/employees`

### `GET /employees/`
Список сотрудников с пагинацией, сортировкой и поиском.

**Query params:**
| Параметр | Тип | Описание |
|----------|-----|----------|
| `limit` | `int` (1..100) | Кол-во записей, default=20 |
| `offset` | `int` (≥0) | Смещение, default=0 |
| `sort` | `string` | Сортировка, напр. `full_name` или `-full_name` (desc) |
| `q` | `string` | Поиск по ФИО (contains) |
| `status` | `string` | Фильтр по статусу |
| `company_id` | `UUID` | Фильтр по компании |
| `brigade_id` | `UUID` | Фильтр по бригаде |
| `site_id` | `string` | Фильтр по объекту |

**Response** `200`: `list[EmployeeResponse]`
```json
[
  {
    "uuid": "uuid",
    "full_name": "Иванов Иван Иванович",
    "company_id": "uuid | null",
    "position": "Монтажник | null",
    "personnel_number": "T-001 | null",
    "pass_number": "P-001 | null",
    "brigade_id": "uuid | null",
    "site_id": "SITE-01 | null",
    "consent_pd_file": "url_to_file | null",
    "consent_pd_date": "2026-01-15 | null",
    "status": "active",
    "created_at": "2026-01-01T00:00:00Z",
    "updated_at": "2026-03-17T08:00:00Z"
  }
]
```

---

### `GET /employees/{employee_uuid}`
Получить сотрудника по UUID. **Response** `200`: `EmployeeResponse`.

---

### `POST /employees/`
Создать сотрудника.

**Request:**
```json
{
  "full_name": "Петров Пётр Петрович",
  "company_id": "uuid | null",
  "position": "Сварщик",
  "personnel_number": "T-002",
  "pass_number": "P-002",
  "brigade_id": "uuid | null",
  "site_id": "SITE-01",
  "consent_pd_file": null,
  "consent_pd_date": null,
  "status": "active"
}
```

**Response** `201`: `EmployeeResponse`.

---

### `PUT /employees/{employee_uuid}`
Полное обновление сотрудника. **Request**: все поля опциональны. **Response** `200`: `EmployeeResponse`.

---

### `DELETE /employees/{employee_uuid}`
Удалить сотрудника. **Response** `204`: пустое тело.

---

### `POST /employees/import`
Массовый импорт из файла (CSV/Excel). **Content-Type**: `multipart/form-data`.

**Request**: `file` — загружаемый файл.

**Response** `200`:
```json
{
  "total": 100,
  "created": 85,
  "updated": 10,
  "errors": [
    { "row": 5, "error": "Дубликат табельного номера" }
  ]
}
```

---

## 8. Смены (Shifts)

**Prefix**: `/api/v1/shifts`

### `GET /shifts`
Список смен с пагинацией и фильтрами.

**Query params:**
| Параметр | Тип | Описание |
|----------|-----|----------|
| `device_id` | `string` | Фильтр по устройству |
| `employee_id` | `UUID` | Фильтр по сотруднику |
| `date_from` | `datetime` | Начало периода (ISO 8601) |
| `date_to` | `datetime` | Конец периода |
| `page` | `int` (≥1) | Страница, default=1 |
| `page_size` | `int` (1..100) | Размер, default=20 |

**Response** `200`:
```json
{
  "items": [
    {
      "id": "uuid",
      "employee_name": "Иванов И.И. | null",
      "packet_id": "pkt_abc",
      "device_id": "WATCH-001",
      "employee_id": "uuid | null",
      "site_id": "SITE-01 | null",
      "start_ts_ms": 1710658800000,
      "end_ts_ms": 1710702000000,
      "duration_minutes": 720,
      "schema_version": 1,
      "device_model": "WatchPro X1",
      "device_fw": "1.2.0",
      "app_version": "2.0.1",
      "timezone": "Europe/Moscow",
      "server_time_offset_ms": 150,
      "status": "processed",
      "samples_count": {
        "accel": 43200, "gyro": 43200, "baro": 720,
        "mag": 0, "hr": 720, "ble": 500,
        "wear": 50, "battery": 24, "downtime": 3
      },
      "created_at": "...",
      "updated_at": "..."
    }
  ],
  "total": 150,
  "page": 1,
  "page_size": 20
}
```

---

### `GET /shifts/{shift_id}`
Детали одной смены. **Response** `200`: `ShiftListItem`.

---

### `GET /shifts/{shift_id}/data/{data_type}`
Получить сенсорные данные смены.

**Path params:**
- `data_type` — тип данных: `accel`, `gyro`, `baro`, `mag`, `heart_rate`, `ble`, `wear`, `battery`, `downtime`

**Query params:**
| Параметр | Тип | Описание |
|----------|-----|----------|
| `from_ts` | `int` | Начало диапазона (ms) |
| `to_ts` | `int` | Конец диапазона (ms) |
| `limit` | `int` (1..100000) | Кол-во записей, default=10000 |
| `offset` | `int` (≥0) | Смещение, default=0 |
| `format` | `string` | Формат вывода (опционально, `csv`) |

**Response** `200`:
```json
{
  "items": [
    {
      "uuid": "uuid",
      "stream": "accel",
      "shift_id": "uuid",
      "packet_id": "pkt_abc",
      "device_id": "WATCH-001",
      "ts_ms": 1710658801000,
      "payload": { "x": 0.12, "y": -9.78, "z": 0.03 },
      "created_at": "..."
    }
  ],
  "total": 43200,
  "page": 1,
  "page_size": 10000
}
```

При `format=csv` вернёт CSV-файл.

---

### `GET /shifts/{shift_id}/zones`
Зоны, посещённые за смену.

**Response** `200`:
```json
{
  "shift_id": "uuid",
  "total_visits": 15,
  "total_zones": 5,
  "visits": [
    {
      "zone_id": "uuid",
      "zone_name": "Цех №1",
      "zone_type": "work",
      "enter_ts_ms": 1710659000000,
      "exit_ts_ms": 1710662600000,
      "duration_sec": 3600,
      "avg_rssi": -65
    }
  ],
  "summary_by_zone": [
    {
      "zone_id": "uuid",
      "zone_name": "Цех №1",
      "zone_type": "work",
      "total_duration_sec": 14400,
      "visit_count": 5
    }
  ]
}
```

---

### `GET /shifts/{shift_id}/route`
Маршрут перемещения за смену (хронологический порядок).

**Response** `200`:
```json
{
  "shift_id": "uuid",
  "route": [
    { "zone_id": "uuid", "zone_name": "Проходная", "enter_ts_ms": ..., "exit_ts_ms": ... },
    { "zone_id": "uuid", "zone_name": "Цех №1", "enter_ts_ms": ..., "exit_ts_ms": ... }
  ]
}
```

---

### `GET /shifts/{shift_id}/activity`
Классификация активности за смену.

**Классы активности:**
| Класс | Описание |
|-------|----------|
| `A1` | Активная работа (высокая интенсивность) |
| `A2` | Работа средней интенсивности |
| `B1` | Ходьба / перемещение |
| `B2` | Медленное перемещение |
| `V1` | Простой в рабочей зоне |
| `V2` | Простой в нерабочей зоне |
| `V3` | Перерыв |
| `V4` | Неизвестно / нет данных |

**Response** `200`:
```json
{
  "shift_id": "uuid",
  "total_intervals": 48,
  "intervals": [
    {
      "interval_id": "uuid",
      "activity_class": "A1",
      "start_ts_ms": 1710659000000,
      "end_ts_ms": 1710660800000,
      "duration_sec": 1800,
      "zone_id": "uuid | null",
      "zone_name": "Цех №1 | null",
      "confidence": 0.92
    }
  ],
  "summary": {
    "A1_sec": 7200, "A2_sec": 3600,
    "B1_sec": 1800, "B2_sec": 900,
    "V1_sec": 5400, "V2_sec": 1200,
    "V3_sec": 3600, "V4_sec": 300,
    "total_sec": 24000
  }
}
```

---

### `GET /shifts/{shift_id}/metrics`
Агрегированные метрики смены.

**Response** `200`:
```json
{
  "shift_id": "uuid",
  "employee_name": "Иванов И.И.",
  "site_name": "Объект А",
  "shift_duration_sec": 28800,
  "on_site_duration_sec": 27000,
  "productivity_percent": 75.5,
  "v1_percent": 18.7,
  "avg_reaction_time_sec": 45.2,
  "median_reaction_time_sec": 38.0,
  "activity_breakdown": {
    "A1_sec": 7200, "A1_percent": 25.0,
    "A2_sec": 3600, "A2_percent": 12.5,
    "B1_sec": 1800, "B1_percent": 6.25,
    "B2_sec": 900,  "B2_percent": 3.125,
    "V1_sec": 5400, "V1_percent": 18.75,
    "V2_sec": 1200, "V2_percent": 4.17,
    "V3_sec": 3600, "V3_percent": 12.5,
    "V4_sec": 300,  "V4_percent": 1.04
  },
  "wear_compliance_percent": 95.0,
  "zones_visited": 5,
  "avg_hr_bpm": 82,
  "anomalies_count": 2,
  "data_quality_score": 0.97
}
```

---

### `GET /shifts/{shift_id}/reaction-times`
Время реакции: за сколько начал работать после входа в зону.

**Response** `200`:
```json
{
  "shift_id": "uuid",
  "reactions": [
    {
      "zone_id": "uuid",
      "zone_name": "Цех №1",
      "zone_enter_ts_ms": 1710659000000,
      "activity_start_ts_ms": 1710659045000,
      "reaction_time_sec": 45,
      "has_productive_activity": true
    }
  ],
  "avg_sec": 42.5,
  "median_sec": 38.0
}
```

---

### `GET /shifts/{shift_id}/downtimes`
Простои смены с причинами.

**Response** `200`:
```json
{
  "shift_id": "uuid",
  "downtimes": [
    {
      "interval_id": "uuid",
      "start_ts_ms": 1710670000000,
      "end_ts_ms": 1710673600000,
      "duration_sec": 3600,
      "zone_id": "uuid | null",
      "zone_name": "Склад | null",
      "reason_id": "uuid",
      "reason_name": "Ожидание материалов",
      "source": "auto | manual",
      "assigned_by": "uuid | null"
    }
  ],
  "total_downtime_sec": 5400,
  "reasons_summary": [
    { "reason_id": "uuid", "reason_name": "Ожидание материалов", "total_sec": 3600, "count": 2 }
  ]
}
```

---

### `POST /shifts/{shift_id}/downtimes/{interval_id}/assign`
Назначить причину простоя интервалу.

**Request:**
```json
{
  "reason_id": "reason_uuid_or_code",
  "comment": "Ожидали поставку | null",
  "assigned_by": "uuid | null"
}
```

**Response** `201`: `ShiftDowntimeAssignResponse`.

---

### `PUT /shifts/{shift_id}/downtimes/{interval_id}/assign`
Обновить причину простоя. **Request/Response** аналогично POST. **Response** `200`.

---

## 9. Watch — Отправка данных с часов

**Prefix**: `/api/v1/watch`

### 🔐 Шифрование и подготовка данных пакета

Прежде чем отправить пакет, мобильное приложение/часы **должны подготовить данные** следующим образом:

#### Шаг 1: Сформировать payload (JSON с сенсорными данными)

Payload — это JSON-объект с данными смены. Структура после расшифровки:

```json
{
  "schema_version": 1,
  "device": {
    "model": "WatchPro X1",
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

> **Допустимые имена полей**: бэкенд поддерживает и `snake_case`, и `camelCase` (например, `ts_ms` / `tsMs`, `start_ts_ms` / `startTsMs`, `beacon_id` / `beaconId`).

#### Шаг 2: Шифрование

```
1. payload_raw = JSON.stringify(payload).toByteArray(UTF-8)
2. payload_hash = SHA-256(payload_raw).toHexString()   // 64 hex символа
3. aes_key = generateAESKey(256 бит)                   // случайный ключ
4. iv = generateIV(12 байт)                            // случайный вектор инициализации (GCM)
5. payload_encrypted = AES-GCM.encrypt(payload_raw, aes_key, iv)
6. aes_key_encrypted = RSA-OAEP-SHA256.encrypt(aes_key, server_public_key)
7. payload_enc = Base64.encode(payload_encrypted)
8. payload_key_enc = Base64.encode(aes_key_encrypted)
9. iv = Base64.encode(iv)                              // ровно 12 байт до base64
```

> ⚠️ **server_public_key** — публичный ключ сервера, полученный при регистрации устройства (`POST /auth/device/register` → `server_public_key_pem`). Также доступен через `GET /admin/crypto/public-key`.

#### Шаг 3: Валидация на сервере

Сервер при получении пакета проверяет:
- `payload_enc` — валидный base64
- `payload_key_enc` — валидный base64
- `iv` — валидный base64, **ровно 12 байт** после декодирования
- `payload_hash` — строго **64 hex-символа** (SHA-256)
- `shift_start_ts < shift_end_ts`
- `packet_id` — валидный UUID
- `device_id` — устройство существует и имеет статус `active`

### 🔑 Idempotency-Key

**Обязательный HTTP-заголовок** для предотвращения дублирования пакетов.

**Правила:**
- `Idempotency-Key` **ДОЛЖЕН совпадать с `packet_id`** в теле запроса
- `packet_id` должен быть **валидным UUID** (v4 рекомендуется)
- Сервер хранит Idempotency-Key **24 часа** после первого принятия
- При повторной отправке с тем же ключом сервер вернёт **кэшированный ответ** (без повторной обработки)
- Если пакет с таким `packet_id` уже существует — ответ `409 Conflict`

```kotlin
// Kotlin: генерация packet_id и Idempotency-Key
val packetId = UUID.randomUUID().toString()
// Idempotency-Key = packet_id !
request.addHeader("Idempotency-Key", packetId)
```

### `POST /watch/packets`
Отправить пакет данных с часов. **Rate limit**: 10/мин.

**Headers (все обязательные для надёжной работы):**
| Заголовок | Обязательный | Описание |
|-----------|:---:|----------|
| `Idempotency-Key` | ✅ | UUID пакета, **должен совпадать с `packet_id`** |
| `x-device-id` | ❌ | Если передан — проверяется совпадение с `device_id` в body |
| `Content-Type` | ✅ | `application/json` |

**Request — обязательные поля:**
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
| `packet_id` | `string` | ✅ | UUID v4, длина 36-64. Уникальный ID пакета |
| `device_id` | `string` | ✅ | ID устройства (1-64 символа) |
| `shift_start_ts` | `int` | ✅ | Начало смены, Unix timestamp (сек или мс) |
| `shift_end_ts` | `int` | ✅ | Конец смены, должен быть > `shift_start_ts` |
| `schema_version` | `int` | ✅ | Версия формата данных (≥ 1) |
| `payload_enc` | `string` | ✅ | Base64-закодированный зашифрованный payload |
| `payload_key_enc` | `string` | ✅ | Base64-закодированный AES-ключ, зашифрованный RSA |
| `iv` | `string` | ✅ | Base64-закодированный IV (12 байт в сыром виде) |
| `payload_hash` | `string` | ✅ | SHA-256 хеш **исходного** (незашифрованного) payload, 64 hex символа |
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

**Возможные ошибки:**
| Код | Причина |
|-----|---------|
| `400` | Отсутствует `Idempotency-Key`, не совпадает с `packet_id`, невалидный base64/iv/hash, `shift_start_ts >= shift_end_ts` |
| `403` | Устройство не активно (`suspended` / `revoked`) |
| `404` | Устройство с указанным `device_id` не найдено |
| `409` | Пакет с таким `packet_id` уже существует |
| `429` | Превышен rate-limit (10/мин) |

> **Важно**: пакет обрабатывается **асинхронно** в background task. Pipeline обработки:
> `accepted` → `decrypting` → `parsing` → `processing` → `processed` (или `error`)

---

### `GET /watch/packets/{packet_id}`
Узнать статус пакета. **Header**: `x-device-id` — обязательный.

**Response** `200`:
```json
{
  "packet_id": "550e8400...",
  "status": "processed"
}
```

---

### `GET /watch/packets/{packet_id}/echo`
Echo: вернуть отправленные данные обратно. **Header**: `x-device-id` — обязательный.

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

### `POST /watch/heartbeat`
Heartbeat — часы/приложение периодически сообщают серверу о своём состоянии. Рекомендуется вызывать **каждые 1-5 минут** во время активной работы.

**Что делает сервер при получении:**
- Обновляет `last_heartbeat_at` и `last_sync_at` устройства
- Обновляет `battery_level` и `app_version` в записи устройства
- Возвращает точное серверное время для синхронизации часов
- Проверяет, что устройство существует и активно (`status = active`)

**Request — все поля обязательные:**

| Поле | Тип | Обязательное | Валидация | Описание |
|------|-----|:---:|-----------|----------|
| `device_id` | `string` | ✅ | 1-64 символа | ID зарегистрированного устройства |
| `device_time_ms` | `int` | ✅ | — | Текущее время на часах (Unix ms) |
| `battery_level` | `float` | ✅ | 0.0 – 100.0 | Уровень заряда батареи (%) |
| `is_collecting` | `bool` | ✅ | — | Идёт ли сейчас сбор данных |
| `pending_packets` | `int` | ✅ | ≥ 0 | Кол-во пакетов, ожидающих отправку |
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
| `time_offset_ms` | `int` | Разница: `server_time_ms - device_time_ms`. Используйте для коррекции timestamps в пакетах |
| `commands` | `list[dict]` | Команды от сервера (зарезервировано, сейчас всегда `[]`) |

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

---

## 10. Gateway — Отправка через шлюз

**Prefix**: `/api/v1/gateway`

### `POST /gateway/packets`
Отправка пакета через мобильное приложение-шлюз (телефон оператора).

> Шифрование payload и правила Idempotency-Key **идентичны** секции [Watch → Шифрование](#-шифрование-и-подготовка-данных-пакета) и [Watch → Idempotency-Key](#-idempotency-key).

**Headers:**
- `Idempotency-Key` — **обязательный**, **должен совпадать с `packet_id`** в body
- `x-device-id` — опциональный (проверка совпадения с `device_id` в body)

**Request** (расширение `WatchPacketSubmitRequest`):
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
  "operator_id": "uuid | null",
  "site_id": "SITE-01 | null",
  "binding_id": "binding_uuid | null",
  "gateway_device_info": {
    "model": "Samsung Galaxy S24 | null",
    "os_version": "Android 15 | null",
    "app_version": "1.0.0 | null"
  }
}
```

**Response** `202`: `WatchPacketSubmitResponse`.

---

## 11. Пакеты (Packets)

**Prefix**: `/api/v1/packets`

CRUD для пакетов данных. Используется внутренними сервисами и для администрирования.

### `GET /packets/`
Список пакетов. **Query**: `limit`, `offset`, `sort`, `status` (enum PacketStatus).

### `GET /packets/{packet_id}`
Получить пакет по ID.

### `POST /packets/`
Создать пакет (внутренний). **Response** `201`.

### `PATCH /packets/{packet_id}/status`
Обновить статус пакета: `{ "status": "processed", "error_message": null }`.

### `DELETE /packets/{packet_id}`
Удалить пакет. **Response** `204`.

**PacketResponse:**
```json
{
  "uuid": "uuid",
  "packet_id": "pkt_abc",
  "device_id": "WATCH-001",
  "shift_start_ts": 1710658800,
  "shift_end_ts": 1710702000,
  "schema_version": 1,
  "status": "processed",
  "uploaded_from": "direct",
  "payload_size_bytes": 45000,
  "error_message": null,
  "received_at": "...",
  "updated_at": "..."
}
```

---

## 12. Привязки (Bindings)

**Prefix**: `/api/v1/bindings`

Привязка устройства к сотруднику на конкретную смену.

### `POST /bindings/`
Создать привязку.

**Request:**
```json
{
  "device_id": "WATCH-001",
  "employee_id": "uuid",
  "site_id": "SITE-01",
  "shift_date": "2026-03-17",
  "shift_type": "day",
  "bound_by": "uuid | null"
}
```

**Response** `201`: `BindingResponse`.

---

### `PUT /bindings/{binding_id}/close`
Закрыть привязку.

**Request:**
```json
{
  "unbound_by": "uuid | null"
}
```

**Response** `200`: `BindingResponse`.

---

### `GET /bindings/`
Список привязок.

**Query params:** `page`, `page_size`, `device_id`, `employee_id`, `shift_date` (YYYY-MM-DD), `site_id`.

**BindingResponse:**
```json
{
  "id": "uuid",
  "device_id": "WATCH-001",
  "employee_id": "uuid",
  "site_id": "SITE-01",
  "shift_date": "2026-03-17",
  "shift_type": "day",
  "bound_at": "2026-03-17T07:00:00Z",
  "bound_by": "uuid | null",
  "unbound_at": "null | datetime",
  "unbound_by": "uuid | null",
  "status": "active | closed",
  "created_at": "...",
  "updated_at": "..."
}
```

---

## 13. Справочники: Компании, Бригады, Объекты

### Компании — `/api/v1/companies`

| Метод | URL | Описание |
|-------|-----|----------|
| `GET /` | Список | Query: `limit`, `offset`, `sort`, `q` (поиск) |
| `GET /{uuid}` | Получить | |
| `POST /` | Создать | `{ "name": "ООО Строй", "inn": "1234567890", "status": "active" }` |
| `PUT /{uuid}` | Обновить | Все поля опциональны |
| `DELETE /{uuid}` | Удалить | Response `204` |

**CompanyResponse:** `{ uuid, name, inn, status, created_at, updated_at }`

---

### Бригады — `/api/v1/brigades`

| Метод | URL | Описание |
|-------|-----|----------|
| `GET /` | Список | Query: `limit`, `offset`, `sort`, `q`, `company_id`, `site_uuid` |
| `GET /{uuid}` | Получить | |
| `POST /` | Создать | `{ "name": "Бригада №1", "company_id": "uuid", "site_id": "SITE-01", "foreman_id": "uuid", "status": "active" }` |
| `PUT /{uuid}` | Обновить | |
| `DELETE /{uuid}` | Удалить | Response `204` |

**BrigadeResponse:** `{ uuid, name, company_id, site_id, foreman_id, status, created_at }`

---

### Объекты (Sites) — `/api/v1/sites`

| Метод | URL | Описание |
|-------|-----|----------|
| `GET /` | Список | Query: `limit`, `offset`, `sort`, `q` |
| `GET /{site_uuid}` | Получить | `site_uuid` — строковый ID |
| `POST /` | Создать | `{ "site_id": "SITE-01", "name": "Объект А", "address": "...", "timezone": "Europe/Moscow", "status": "active" }` |
| `PATCH /{site_uuid}` | Обновить | |
| `DELETE /{site_uuid}` | Удалить | Response `204` |

**SiteResponse:** `{ uuid, site_id, name, address, timezone, status, created_at, updated_at }`

---

## 14. Зоны и Маяки (Zones & Beacons)

### Зоны объекта — `/api/v1/sites/{site_id}/zones`

### `GET /sites/{site_id}/zones`
Список зон объекта. **Query**: `page`, `page_size` (max 200).

### `POST /sites/{site_id}/zones`
Создать зону.

```json
{
  "name": "Цех №1",
  "zone_type": "work | rest | transit | storage | office",
  "productivity_percent": 100,
  "lat": 55.7558,
  "lon": 37.6173,
  "floor": 1,
  "status": "active"
}
```

**ZoneResponse:** `{ uuid, site_id, name, zone_type, productivity_percent, lat, lon, floor, status, created_at, updated_at }`

---

### Управление зонами — `/api/v1/zones`

### `PUT /zones/{zone_id}`
Обновить зону. Все поля опциональны.

### `POST /zones/{zone_id}/beacons`
Добавить маяк к зоне.

```json
{
  "beacon_id": "BLE-MAC-ADDRESS",
  "lat": 55.7558,
  "lon": 37.6173,
  "status": "active"
}
```

**Response** `201`: `ZoneBeaconResponse { uuid, zone_id, beacon_id, lat, lon, status, created_at, updated_at }`

### `DELETE /zones/{zone_id}/beacons/{beacon_id}`
Удалить маяк. **Response** `204`.

---

### Неизвестные маяки — `/api/v1/admin/unknown-beacons`

### `GET /admin/unknown-beacons`
Список обнаруженных, но не привязанных к зонам маяков.

**Query**: `page`, `page_size`, `status`, `device_id`.

```json
{
  "items": [
    {
      "uuid": "uuid", "beacon_id": "XX:XX:XX:XX",
      "device_id": "WATCH-001",
      "first_seen_ts_ms": ..., "last_seen_ts_ms": ...,
      "count": 150, "status": "new",
      "created_at": "...", "updated_at": "..."
    }
  ],
  "total": 5, "page": 1, "page_size": 50
}
```

---

## 15. Аналитика (Analytics)

**Prefix**: `/api/v1/analytics`

### `GET /analytics/device/{device_id}/summary`
Сводка по устройству.

```json
{
  "device_id": "WATCH-001",
  "status": "active",
  "total_packets": 150, "accepted_packets": 145,
  "processed_packets": 140, "error_packets": 5,
  "total_shifts": 60, "samples_count": 2500000,
  "last_heartbeat_at": "...", "last_sync_at": "..."
}
```

---

### `GET /analytics/employee/{employee_id}/summary`
Сводка по сотруднику.

```json
{
  "employee_id": "uuid",
  "total_devices": 2, "total_shifts": 45,
  "total_packets": 90, "samples_count": 1800000
}
```

---

### `GET /analytics/zones`
Сводка по зонам (BLE-события).

```json
[
  { "beacon_id": "XX:XX:XX", "events": 1500 }
]
```

---

### `GET /analytics/productivity`
Отчёт по продуктивности.

**Query params:**
| Параметр | Тип | Описание |
|----------|-----|----------|
| `site_id` | `string` | Фильтр по объекту |
| `employee_id` | `UUID` | Фильтр по сотруднику |
| `company_id` | `UUID` | Фильтр по компании |
| `brigade_id` | `UUID` | Фильтр по бригаде |
| `date_from` | `date` (YYYY-MM-DD) | Начало периода |
| `date_to` | `date` | Конец периода |
| `page` | `int` | Страница, default=1 |
| `page_size` | `int` (1..200) | Размер, default=50 |

**Response** `200`:
```json
{
  "period": { "date_from": "2026-03-01", "date_to": "2026-03-17" },
  "items": [
    {
      "employee_id": "uuid",
      "employee_name": "Иванов И.И.",
      "company": "ООО Строй",
      "total_shifts": 15,
      "total_hours_on_site": 120.0,
      "avg_productivity_percent": 78.5,
      "avg_v1_percent": 15.2,
      "avg_reaction_time_sec": 40.0,
      "anomalies_total": 3
    }
  ],
  "aggregates": {
    "avg_productivity_percent": 72.0,
    "avg_v1_percent": 18.5,
    "avg_reaction_time_sec": 45.0
  },
  "total": 50, "page": 1, "page_size": 50
}
```

---

## 16. Аномалии (Anomalies)

### `GET /shifts/{shift_id}/anomalies`
Аномалии конкретной смены.

```json
{
  "shift_id": "uuid",
  "anomalies": [ "...AnomalyItem..." ],
  "total": 3
}
```

### `GET /anomalies`
Список всех аномалий с фильтрами.

**Query**: `site_id`, `employee_id`, `severity`, `status`, `anomaly_type`, `date_from`, `date_to`, `page`, `page_size`.

### `PATCH /anomalies/{anomaly_id}`
Обновить аномалию (изменить статус, добавить комментарий).

**Request:** `{ "status": "resolved", "comment": "Ложное срабатывание", "resolved_by": "uuid" }`

**AnomalyItem:**
```json
{
  "id": "uuid", "shift_id": "uuid", "device_id": "WATCH-001",
  "employee_id": "uuid | null",
  "anomaly_type": "wear_toggle | data_gap | impossible_travel | off_wrist | zero_hr",
  "severity": "low | medium | high | critical",
  "start_ts_ms": ..., "end_ts_ms": ...,
  "description": "Частое снятие/надевание часов",
  "status": "open | acknowledged | resolved | false_positive",
  "details_json": { "toggle_count": 8 },
  "comment": null, "resolved_by": null, "resolved_at": null,
  "created_at": "...", "updated_at": "..."
}
```

---

## 17. Причины простоев (Downtime Reasons)

**Prefix**: `/api/v1/downtime-reasons`

### `GET /downtime-reasons/`
Справочник причин простоев. **Query**: `page` (default=1), `page_size` (default=100, max 500), `is_active`.

### `POST /downtime-reasons/`
Создать: `{ "code": "WAIT_MAT", "name": "Ожидание материалов", "category": "logistics", "is_active": true, "sort_order": 1 }`

### `PUT /downtime-reasons/{reason_id}`
Обновить: `{ "name": "...", "category": "...", "is_active": false, "sort_order": 2 }`

**DowntimeReasonResponse:** `{ uuid, code, name, category, is_active, sort_order, created_at, updated_at }`

---

## 18. Аудит-лог (Audit Log)

**Prefix**: `/api/v1/audit-log`

### `GET /audit-log/`
Журнал действий пользователей.

**Query**: `user_id`, `action`, `entity_type`, `date_from`, `date_to`, `page`, `page_size`.

```json
{
  "items": [
    {
      "id": "uuid", "user_id": "uuid | null",
      "action": "create | update | delete | login",
      "entity_type": "device | employee | shift | ...",
      "entity_id": "...", "details_json": {...},
      "ip_address": "192.168.1.1", "user_agent": "...",
      "created_at": "..."
    }
  ],
  "total": 500, "page": 1, "page_size": 50
}
```

### `GET /audit-log/export`
Экспорт в CSV. Те же query-параметры. **Response**: `text/csv`.

---

## 19. Администрирование (Admin)

**Prefix**: `/api/v1/admin` — **все роуты требуют роль `admin`**.

| Метод | URL | Описание |
|-------|-----|----------|
| `GET /admin/devices` | Список устройств (admin view) | Query: `status`, `page`, `page_size` |
| `GET /admin/packets` | Список пакетов | Query: `status`, `device_id`, `from_ts`, `to_ts`, `page`, `page_size` |
| `GET /admin/packets/{id}/log` | Лог обработки пакета | Шаги pipeline |
| `POST /admin/packets/{id}/reprocess` | Перезапустить обработку | Response `202` |
| `GET /admin/stats` | Системная статистика | `{ active_devices, revoked_devices, total_packets, accepted_packets, processing_packets, error_packets }` |
| `POST /admin/crypto/rotate-key` | Ротация крипто-ключа | Возвращает новый публичный ключ |
| `GET /admin/crypto/public-key` | Текущий публичный ключ | `{ public_key_pem: "..." }` |
| `POST /admin/registration-codes` | Генерация рег. кодов | Query: `count` (1..200), `expires_in_days` (1..365, default=30) |
| `GET /admin/classification/config` | Конфиг классификации | Параметры алгоритма |
| `PUT /admin/classification/config` | Обновить конфиг | |
| `GET /admin/anomaly-config` | Конфиг аномалий | Пороги детекции |
| `PUT /admin/anomaly-config` | Обновить конфиг | |

---

## 20. Crypto Keys

**Prefix**: `/api/v1/crypto-keys` — CRUD для криптографических ключей.

| Метод | URL | Response |
|-------|-----|----------|
| `GET /` | Список ключей | Query: `limit`, `offset`, `sort`, `status` (active/rotated/revoked) |
| `GET /{uuid}` | Получить ключ | |
| `POST /` | Создать ключ | `201` |
| `PATCH /{uuid}` | Обновить | |
| `DELETE /{uuid}` | Удалить | `204` |

---

## 21. Device Tokens

**Prefix**: `/api/v1/device-tokens` — CRUD для JWT-токенов устройств.

| Метод | URL | Query |
|-------|-----|-------|
| `GET /` | Список | `limit`, `offset`, `sort`, `device_id` |
| `GET /{uuid}` | Получить | |
| `POST /` | Создать | `201` |
| `PATCH /{uuid}` | Обновить | |
| `DELETE /{uuid}` | Удалить | `204` |

---

## 22. Idempotency Keys

**Prefix**: `/api/v1/idempotency-keys` — хранение ключей идемпотентности.

Стандартный CRUD: `GET /`, `GET /{uuid}`, `POST /`, `PATCH /{uuid}`, `DELETE /{uuid}`.

---

## 23. Sensor Samples

**Prefix**: `/api/v1/sensor-samples` — CRUD для единичных сенсорных записей.

| Метод | URL | Описание |
|-------|-----|----------|
| `GET /{stream}` | Список по потоку | Query: `limit`, `offset`, `sort`, `device_id`. Stream: accel/gyro/baro и т.д. |
| `GET /{stream}/{uuid}` | Конкретная запись | |
| `POST /{stream}` | Создать | `{ shift_id, packet_id, device_id, ts_ms, payload }` |
| `PATCH /{stream}/{uuid}` | Обновить | |
| `DELETE /{stream}/{uuid}` | Удалить | `204` |

---

## 24. Packet Processing Logs

**Prefix**: `/api/v1/packet-processing-logs` — логи обработки пакетов.

Стандартный CRUD: `GET /`, `GET /{uuid}`, `POST /`, `PATCH /{uuid}`, `DELETE /{uuid}`.

Query: `limit`, `offset`, `sort`, `packet_id`.

---

## 25. Перечисления (Enums)

### DeviceStatus
```kotlin
enum class DeviceStatus(val value: String) {
    ACTIVE("active"),
    REVOKED("revoked"),
    SUSPENDED("suspended")
}
```

### PacketStatus
```kotlin
enum class PacketStatus(val value: String) {
    ACCEPTED("accepted"),
    DECRYPTING("decrypting"),
    PARSING("parsing"),
    PROCESSING("processing"),
    PROCESSED("processed"),
    ERROR("error")
}
```

### UploadedFrom
```kotlin
enum class UploadedFrom(val value: String) {
    DIRECT("direct"),   // С часов напрямую
    GATEWAY("gateway")  // Через мобильный шлюз
}
```

### CryptoKeyStatus
```kotlin
enum class CryptoKeyStatus(val value: String) {
    ACTIVE("active"),
    ROTATED("rotated"),
    REVOKED("revoked")
}
```

---

## 26. Пагинация и фильтрация

### Два стиля пагинации

**Стиль 1 — page/page_size** (shifts, devices, bindings, zones, anomalies, analytics):
```
GET /shifts?page=2&page_size=20
→ { items: [...], total: 150, page: 2, page_size: 20 }
```

**Стиль 2 — limit/offset** (employees, packets, companies, brigades, sites, crypto-keys, tokens, samples):
```
GET /employees/?limit=20&offset=40
→ [ ...list of items... ]
```

### Сортировка
```
GET /employees/?sort=full_name      # ASC
GET /employees/?sort=-full_name     # DESC
```

### Поиск
```
GET /employees/?q=Иванов            # Поиск по ФИО (contains)
GET /companies/?q=строй             # Поиск по названию
```

---

## 27. Rate Limiting

| Эндпоинт | Лимит |
|----------|-------|
| `POST /auth/device/register` | 3 запроса/мин |
| `POST /auth/device/token` | 5 запросов/мин |
| `POST /watch/packets` | 10 запросов/мин |

При превышении ответ: `429 Too Many Requests`.

---

## 28. Типичные flow для мобильного приложения

### Flow 1: Приложение-шлюз (Gateway App)

```
0. [Одноразово] Регистрация нового устройства через мобильное
   POST /auth/device/register-via-mobile
   → { device_id, status: "registered" }
   Затем часы вызывают:
   GET /auth/device/{device_id}/registration-status
   → { device_secret, server_public_key_pem } (одноразово)

1. Оператор логинится
   POST /auth/login → получает access_token

2. Загрузка справочников
   GET /employees/?limit=100
   GET /sites/
   GET /companies/

3. Создание привязки (привязать часы к работнику)
   POST /bindings/
   → { binding_id, device_id, employee_id, site_id }

4. Приём данных от часов (по Bluetooth) и отправка на сервер
   POST /gateway/packets
   Headers: Idempotency-Key: <uuid>
   Body: { ...encrypted packet + operator_id, site_id, binding_id, gateway_device_info }

5. Проверка статуса пакета (polling)
   GET /watch/packets/{packet_id}
   Headers: x-device-id: WATCH-001

6. Просмотр смен и аналитики
   GET /shifts?employee_id=...&date_from=...
   GET /shifts/{id}/metrics
   GET /shifts/{id}/activity
   GET /shifts/{id}/zones
   GET /shifts/{id}/reaction-times
   GET /shifts/{id}/downtimes
   GET /analytics/productivity?site_id=...

7. Закрытие привязки
   PUT /bindings/{binding_id}/close
```

### Flow 2: Автономные часы (Direct Upload)

```
1. Одноразовая регистрация
   POST /auth/device/register → device_id + device_secret + server_public_key_pem

2. Получение токенов
   POST /auth/device/token → access_token + refresh_token

3. Периодические heartbeat
   POST /watch/heartbeat

4. Отправка данных смены
   POST /watch/packets
   Headers: Idempotency-Key: <uuid>

5. Обновление токенов
   POST /auth/device/refresh
```

### Flow 3: Административная панель (Admin)

```
1. Логин admin
   POST /auth/login

2. Мониторинг
   GET /admin/stats
   GET /admin/devices
   GET /admin/packets

3. Управление
   POST /admin/registration-codes
   POST /admin/crypto/rotate-key
   GET /admin/packets/{id}/log
   POST /admin/packets/{id}/reprocess

4. Справочники
   CRUD: /companies, /brigades, /sites, /employees
   CRUD: /zones, /zones/{id}/beacons, /downtime-reasons

5. Аналитика
   GET /analytics/productivity
   GET /shifts/{id}/metrics
   GET /anomalies
```

---

## 29. Привязка часов к сотруднику — полный lifecycle

> **Это критически важный раздел.** Если `binding_id` не передаётся при загрузке пакетов, данные смены могут быть записаны на **неправильного** сотрудника.

### Проблема

Часы передаются от одного рабочего к другому между сменами. Когда часы W-001 были у **Иванова**, а потом перешли к **Петрову**, пакет данных за смену Иванова может прийти на сервер **после** того, как часы уже привязаны к Петрову. Без `binding_id` сервер не сможет определить, что данные принадлежат Иванову.

### Решение: binding_id

При загрузке каждого пакета приложение **обязано** передавать `binding_id` — ID привязки, которая была активна **в момент сбора данных**.

Сервер резолвит `employee_id` в следующем порядке приоритета:
1. `operator_id` из пакета (если передан явно)
2. **`binding_id`** → ищет в таблице `device_bindings` → берёт `employee_id` оттуда
3. `device.employee_id` — текущая привязка устройства (fallback, ненадёжно!)

### Полный flow (Kotlin-псевдокод)

#### Шаг 1: Создать привязку (начало смены)

```kotlin
// Мастер привязывает часы к рабочему
val binding = api.createBinding(
    BindingCreateRequest(
        deviceId = "WATCH-001",
        employeeId = employeeUUID,
        siteId = "SITE-01",
        shiftDate = LocalDate.now(),    // "2026-03-17"
        shiftType = "day",              // "day" или "night"
        boundBy = masterUUID            // UUID мастера (опционально)
    )
)

// ⚠️ СОХРАНИТЬ binding.id! Он нужен для всех пакетов этой смены
val activeBindingId: String = binding.id
```

**Запрос**: `POST /api/v1/bindings/`

**Ответ** `201`:
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "device_id": "WATCH-001",
  "employee_id": "uuid-рабочего",
  "site_id": "SITE-01",
  "shift_date": "2026-03-17",
  "shift_type": "day",
  "bound_at": "2026-03-17T07:00:00Z",
  "bound_by": "uuid-мастера",
  "unbound_at": null,
  "unbound_by": null,
  "status": "active",
  "created_at": "...",
  "updated_at": "..."
}
```

**Ошибки**:
| Код | Причина |
|-----|---------|
| `404` | Устройство или сотрудник не найден |
| `409` | У устройства уже есть активная привязка (сначала закройте её) |

---

#### Шаг 2: Отправлять пакеты с binding_id

```kotlin
// При отправке КАЖДОГО пакета — передавать binding_id
val response = api.submitGatewayPacket(
    idempotencyKey = packetId,
    body = GatewayPacketSubmitRequest(
        packetId = packetId,
        deviceId = "WATCH-001",
        shiftStartTs = shiftStartMs,
        shiftEndTs = shiftEndMs,
        schemaVersion = 1,
        payloadEnc = encryptedPayload,
        payloadKeyEnc = encryptedAesKey,
        iv = ivBase64,
        payloadHash = sha256Hex,
        payloadSizeBytes = payloadSize,
        operatorId = null,
        siteId = "SITE-01",
        bindingId = activeBindingId,  // ← КРИТИЧЕСКИ ВАЖНО!
        gatewayDeviceInfo = GatewayDeviceInfo(
            model = Build.MODEL,
            osVersion = "Android ${Build.VERSION.RELEASE}",
            appVersion = BuildConfig.VERSION_NAME
        )
    )
)
```

**Запрос**: `POST /api/v1/gateway/packets`

---

#### Шаг 3: Закрыть привязку (конец смены)

```kotlin
// Мастер отвязывает часы от рабочего
api.closeBinding(
    bindingId = activeBindingId,
    body = BindingCloseRequest(
        unboundBy = masterUUID  // UUID мастера (опционально)
    )
)
```

**Запрос**: `PUT /api/v1/bindings/{binding_id}/close`

**Ответ** `200`:
```json
{
  "id": "550e8400-...",
  "status": "closed",
  "unbound_at": "2026-03-17T19:00:00Z",
  "unbound_by": "uuid-мастера"
}
```

---

#### Шаг 4: Получить данные сотрудника за смену

```kotlin
// Все смены конкретного сотрудника
val shifts = api.listShifts(employeeId = employeeUUID)

// Метрики за конкретную смену
val metrics = api.getShiftMetrics(shiftId = shifts.items[0].id)

// Сенсорные данные (акселерометр)
val accelData = api.getShiftData(shiftId = shiftId, dataType = "accel")

// Аналитика по сотруднику
val summary = api.getEmployeeSummary(employeeId = employeeUUID)
```

| Запрос | Описание |
|--------|----------|
| `GET /shifts?employee_id={uuid}` | Все смены сотрудника |
| `GET /shifts/{id}/metrics` | Метрики смены |
| `GET /shifts/{id}/data/accel` | Акселерометр |
| `GET /shifts/{id}/data/heart_rate` | Пульс |
| `GET /shifts/{id}/activity` | Классификация активности |
| `GET /shifts/{id}/downtimes` | Простои |
| `GET /analytics/employee/{uuid}/summary` | Сводка по сотруднику |
| `GET /anomalies?employee_id={uuid}` | Аномалии |

---

#### Шаг 5: История привязок

```kotlin
// Все привязки сотрудника (какие часы он носил)
val bindings = api.listBindings(employeeId = employeeUUID)

// Все привязки конкретных часов (кто их носил)
val deviceBindings = api.listBindings(deviceId = "WATCH-001")
```

**Запрос**: `GET /api/v1/bindings/?employee_id={uuid}` или `?device_id=WATCH-001`

---

### Визуальная схема

```
┌──────────────┐     POST /bindings/        ┌─────────────────────┐
│  Мобильное   │ ─────────────────────────→  │  Создать привязку   │
│  приложение  │     ← binding_id           │  (status=active)    │
│              │                             └─────────────────────┘
│              │                                      │
│              │     POST /gateway/packets             ▼
│              │     + binding_id              ┌──────────────────┐
│              │ ──────────────────────────→   │  Загрузить пакет │
│              │                               │  (с binding_id)  │
│              │                               └──────────────────┘
│              │                                      │
│              │     PUT /bindings/{id}/close          ▼
│              │ ──────────────────────────→   ┌──────────────────┐
│              │                               │ Закрыть привязку │
│              │                               │ (status=closed)  │
│              │                               └──────────────────┘
│              │                                      │
│              │     GET /shifts?employee_id=          ▼
│              │ ──────────────────────────→   ┌──────────────────┐
│              │                               │ Данные сотрудника│
│              │     ← смены, метрики, ...     │ (не устройства!) │
└──────────────┘                               └──────────────────┘
```

### Что хранить на клиенте

| Данные | Когда сохранять | Зачем |
|--------|----------------|-------|
| `binding_id` | После `POST /bindings/` | Передавать в каждый пакет |
| `device_id` | После регистрации | Идентификация устройства |
| `employee_id` | При выборе рабочего | Фильтрация смен и аналитики |
| `site_id` | При выборе площадки | Контекст пакета |

> ⚠️ **binding_id нужно хранить до полной отправки всех пакетов данной смены**, даже если привязка уже закрыта! Пакеты могут отправляться с задержкой (нет интернета), и `binding_id` гарантирует правильную атрибуцию данных.

---

## Kotlin-рекомендации

### HTTP-клиент: Ktor или Retrofit

```kotlin
// Retrofit пример
interface WatchApi {
    @POST("auth/login")
    suspend fun login(@Body request: LoginRequest): LoginResponse

    @POST("auth/device/register")
    suspend fun registerDevice(@Body request: DeviceRegisterRequest): DeviceRegisterResponse

    @POST("watch/packets")
    suspend fun submitPacket(
        @Header("Idempotency-Key") idempotencyKey: String,
        @Header("x-device-id") deviceId: String?,
        @Body request: WatchPacketSubmitRequest
    ): WatchPacketSubmitResponse

    @POST("watch/heartbeat")
    suspend fun heartbeat(@Body request: HeartbeatRequest): HeartbeatResponse

    @GET("shifts")
    suspend fun listShifts(
        @Query("device_id") deviceId: String? = null,
        @Query("employee_id") employeeId: String? = null,
        @Query("page") page: Int = 1,
        @Query("page_size") pageSize: Int = 20
    ): ShiftListResponse
}
```

### Interceptor для авторизации

```kotlin
class AuthInterceptor(private val tokenProvider: TokenProvider) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request().newBuilder()
            .addHeader("Authorization", "Bearer ${tokenProvider.getToken()}")
            .build()
        return chain.proceed(request)
    }
}
```

### Обработка ошибок

```kotlin
sealed class ApiResult<T> {
    data class Success<T>(val data: T) : ApiResult<T>()
    data class Error<T>(val code: Int, val message: String) : ApiResult<T>()
}

suspend fun <T> safeApiCall(call: suspend () -> T): ApiResult<T> {
    return try {
        ApiResult.Success(call())
    } catch (e: HttpException) {
        val errorBody = e.response()?.errorBody()?.string()
        val detail = JSONObject(errorBody ?: "{}").optString("detail", "Unknown error")
        ApiResult.Error(e.code(), detail)
    }
}
```
