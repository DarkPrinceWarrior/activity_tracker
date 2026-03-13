# Watch Backend — API Reference

> Документация для мобильного разработчика.  
> Base URL: `https://<host>/api/v1`  
> Все тела запросов и ответов в формате **JSON** (`Content-Type: application/json`).  
> Все даты/время в формате **ISO 8601 UTC** (например `2024-01-15T12:00:00Z`).  
> Все идентификаторы сущностей — **UUID v4** (строка), кроме `device_id` и `site_id` (произвольная строка).

---

## Содержание

1. [Аутентификация устройства](#1-аутентификация-устройства)
2. [Аутентификация пользователей (веб/операторы)](#2-аутентификация-пользователей)
3. [Watch API — пакеты и heartbeat](#3-watch-api)
4. [Gateway API — приём пакетов через шлюз](#4-gateway-api)
5. [Смены (Shifts)](#5-смены-shifts)
6. [Устройства (Devices)](#6-устройства-devices)
7. [Привязки (Bindings)](#7-привязки-bindings)
8. [Сотрудники (Employees)](#8-сотрудники-employees)
9. [Справочники: Компании, Бригады, Участки](#9-справочники)
10. [Зоны (Zones)](#10-зоны-zones)
11. [Аналитика (Analytics)](#11-аналитика-analytics)
12. [Admin API](#12-admin-api)
13. [Причины простоев (Downtime Reasons)](#13-причины-простоев-downtime-reasons)
14. [Аномалии (Anomalies)](#14-аномалии-anomalies)
15. [Пакеты — внутренний API](#15-пакеты--внутренний-api-packets)
16. [Журнал аудита](#16-журнал-аудита-audit-log)
17. [Коды ошибок](#17-коды-ошибок)

---

## 1. Аутентификация устройства

### `POST /auth/device/register`

Регистрация нового устройства по одноразовому регистрационному коду.  
**Rate limit:** 3 запроса в минуту.

**Тело запроса:**

| Поле | Тип | Обязательно | Описание |
|---|---|---|---|
| `registration_code` | string | ✅ | Одноразовый код (выдаётся администратором) |
| `device_id` | string | ❌ | Желаемый ID устройства (≤64 символов). Если не передан — генерируется сервером |
| `model` | string | ❌ | Модель устройства, например `"Galaxy Watch 6"` |
| `firmware` | string | ❌ | Версия прошивки, например `"One UI 6"` |
| `app_version` | string | ❌ | Версия приложения, например `"1.0.0"` |
| `employee_id` | UUID | ❌ | UUID сотрудника для автоматической привязки |
| `site_id` | string | ❌ | ID участка |
| `timezone` | string | ❌ | Часовой пояс, по умолчанию `"UTC"` |

**Ответ `201 Created`:**

```json
{
  "device_id": "watch-abc123",
  "device_secret": "секретный_ключ_для_хранения_на_устройстве",
  "server_public_key_pem": "-----BEGIN PUBLIC KEY-----\n...",
  "server_time": "2024-01-15T12:00:00Z"
}
```

> ⚠️ `device_secret` — сохраните надёжно! Используется для получения токенов. Повторно не выдаётся.

**Ошибки:** `404` — код не найден, `409` — код уже использован.

---

### `POST /auth/device/token`

Получение access и refresh токенов для устройства.  
**Rate limit:** 5 запросов в минуту.

**Тело запроса:**

| Поле | Тип | Обязательно | Описание |
|---|---|---|---|
| `device_id` | string | ✅ | ID зарегистрированного устройства |
| `device_secret` | string | ✅ | Секрет, полученный при регистрации |

**Ответ `200 OK`:**

```json
{
  "access_token": "eyJ...",
  "refresh_token": "eyJ...",
  "expires_in": 3600,
  "server_time": "2024-01-15T12:00:00Z"
}
```

**Ошибки:** `401` — неверный секрет, `403` — устройство заблокировано (REVOKED), `404` — устройство не найдено.

---

### `POST /auth/device/refresh`

Обновление access-токена по refresh-токену.

**Тело запроса:**

| Поле | Тип | Обязательно | Описание |
|---|---|---|---|
| `device_id` | string | ✅ | ID устройства |
| `refresh_token` | string | ✅ | Refresh-токен |

**Ответ `200 OK`:** аналогичен `/token`.

**Ошибки:** `401` — невалидный или истёкший refresh_token.

---

### `POST /auth/device/revoke`

Отзыв (деактивация) устройства.

**Тело запроса:**

| Поле | Тип | Обязательно | Описание |
|---|---|---|---|
| `device_id` | string | ✅ | ID устройства |

**Ответ `200 OK`:**

```json
{ "status": "revoked" }
```

---

## 2. Аутентификация пользователей

### `POST /auth/login`

Вход оператора/администратора по email и паролю.

**Тело запроса:**

| Поле | Тип | Обязательно | Описание |
|---|---|---|---|
| `email` | string | ✅ | Email пользователя |
| `password` | string | ✅ | Пароль |

**Ответ `200 OK`:**

```json
{
  "access_token": "eyJ...",
  "token_type": "Bearer"
}
```

**Ошибки:** `401` — неверный пароль или email, `403` — пользователь заблокирован.

---

### `GET /auth/me`

Информация о текущем авторизованном пользователе.  
**Требует:** `Authorization: Bearer <access_token>`

**Ответ `200 OK`:**

```json
{
  "id": "uuid",
  "email": "admin@company.com",
  "full_name": "Иван Иванов",
  "role": "admin",
  "status": "active"
}
```

**Ошибки:** `401` — нет токена или токен невалиден.

---

### `POST /auth/users`

Создание нового пользователя. **Только для роли `admin`.**  
**Требует:** `Authorization: Bearer <access_token>`

**Тело запроса:**

| Поле | Тип | Обязательно | Описание |
|---|---|---|---|
| `email` | string | ✅ | Email нового пользователя |
| `password` | string | ✅ | Пароль |
| `full_name` | string | ✅ | Полное имя |
| `role` | string | ✅ | Роль: `"admin"` или `"operator"` |

**Ответ `201 Created`:** аналогичен `/auth/me`.

**Ошибки:** `400` — невалидная роль, `403` — нет прав.

---

## 3. Watch API

> Эти эндпоинты используются непосредственно **умными часами**.

### `POST /watch/packets`

Отправка пакета сенсорных данных с часов.  
**Rate limit:** 10 запросов в минуту с одного IP.

**Обязательный заголовок:**

```
Idempotency-Key: <packet_id>
```

> Значение `Idempotency-Key` **обязано совпадать** с `packet_id` в теле запроса!

**Необязательный заголовок:**
```
x-device-id: <device_id>
```
Если передан — должен совпадать с `device_id` в теле. Используется для дополнительной валидации.

**Тело запроса:**

| Поле | Тип | Обязательно | Описание |
|---|---|---|---|
| `packet_id` | string (UUID) | ✅ | Уникальный ID пакета (UUID v4) |
| `device_id` | string | ✅ | ID устройства-отправителя |
| `shift_start_ts` | integer | ✅ | Начало смены в миллисекундах Unix timestamp |
| `shift_end_ts` | integer | ✅ | Конец смены в мс. Должен быть > `shift_start_ts` |
| `schema_version` | integer | ✅ | Версия схемы данных (≥1) |
| `payload_enc` | string | ✅ | Зашифрованный payload, закодированный в Base64 |
| `payload_key_enc` | string | ✅ | Зашифрованный ключ AES, в Base64 |
| `iv` | string | ✅ | Вектор инициализации AES-GCM (ровно 12 байт → Base64, 16 символов) |
| `payload_hash` | string | ✅ | SHA-256 хеш исходного (незашифрованного) payload, 64 hex-символа |
| `payload_size_bytes` | integer | ❌ | Размер payload в байтах (≥0) |

**Ответ `202 Accepted`:**

```json
{
  "packet_id": "550e8400-e29b-41d4-a716-446655440000",
  "status": "accepted",
  "received_at": "2024-01-15T12:00:00Z",
  "server_time": "2024-01-15T12:00:00Z"
}
```

**Ошибки:**

| Код | Причина |
|---|---|
| `400` | Нет `Idempotency-Key`, `Idempotency-Key` ≠ `packet_id`, невалидный Base64/IV/hash, `start_ts >= end_ts` |
| `404` | Устройство не найдено |
| `403` | Устройство заблокировано (REVOKED) |
| `409` | Пакет с таким `packet_id` уже принят (дубликат) |
| `422` | `packet_id` не является валидным UUID |
| `429` | Превышен rate limit |

---

### `GET /watch/packets/{packet_id}`

Проверка статуса пакета.

**Обязательный заголовок:**
```
x-device-id: <device_id>
```

**Ответ `200 OK`:**

```json
{
  "packet_id": "550e8400-e29b-41d4-a716-446655440000",
  "status": "accepted"
}
```

Возможные значения `status`: `accepted`, `processing`, `processed`, `error`.

**Ошибки:** `401` — нет заголовка `x-device-id`, `404` — пакет не найден или принадлежит другому устройству.

---

### `GET /watch/packets/{packet_id}/echo`

Получение полного содержимого ранее отправленного пакета (для верификации).

**Обязательный заголовок:**
```
x-device-id: <device_id>
```

**Ответ `200 OK`:**

```json
{
  "packet_id": "...",
  "device_id": "...",
  "shift_start_ts": 1700000000000,
  "shift_end_ts": 1700003600000,
  "schema_version": 1,
  "payload_enc": "base64...",
  "payload_key_enc": "base64...",
  "iv": "base64...",
  "payload_hash": "sha256hex...",
  "payload_size_bytes": 1024,
  "status": "accepted",
  "uploaded_from": "direct",
  "received_at": "2024-01-15T12:00:00Z"
}
```

**Ошибки:** `401` — нет `x-device-id`, `404` — пакет не найден или чужой.

---

### `POST /watch/heartbeat`

Периодический сигнал жизни от устройства. Обновляет `last_heartbeat_at` и позволяет синхронизировать время.

**Тело запроса:**

| Поле | Тип | Обязательно | Описание |
|---|---|---|---|
| `device_id` | string | ✅ | ID устройства |
| `device_time_ms` | integer | ✅ | Текущее время на устройстве в мс (Unix timestamp) |
| `battery_level` | float | ✅ | Уровень заряда батареи: `0.0` — `1.0` |
| `is_collecting` | boolean | ✅ | Идёт ли сбор данных в данный момент |
| `pending_packets` | integer | ✅ | Количество пакетов, ожидающих отправки (≥0) |
| `app_version` | string | ✅ | Версия приложения на устройстве |

**Ответ `200 OK`:**

```json
{
  "server_time": "2024-01-15T12:00:00Z",
  "server_time_ms": 1705320000000,
  "time_offset_ms": 150,
  "commands": []
}
```

> `time_offset_ms = server_time_ms - device_time_ms` — используйте для коррекции часов на устройстве.  
> `commands` — зарезервировано для серверных команд (пока всегда пустой массив).

**Ошибки:** `403` — устройство REVOKED, `404` — устройство не найдено.

---

## 4. Gateway API

> Используется шлюзовым Android-устройством (планшет/телефон), которое собирает данные с часов по BLE и проксирует их на сервер.

### `POST /gateway/packets`

Приём пакета через шлюз. Аналогичен `/watch/packets`, но с дополнительными полями.

**Заголовки:** аналогичны `/watch/packets` (`Idempotency-Key` обязателен).

**Тело запроса:** все поля из `/watch/packets`, плюс:

| Поле | Тип | Обязательно | Описание |
|---|---|---|---|
| `operator_id` | UUID | ❌ | UUID оператора-отправителя (должен существовать в БД) |
| `site_id` | string | ❌ | ID участка (должен существовать в БД) |
| `binding_id` | string | ❌ | ID привязки (произвольная строка, ≤64 символов) |
| `gateway_device_info` | object | ❌ | Информация о шлюзовом устройстве (см. ниже) |

**`gateway_device_info` объект:**

| Поле | Тип | Описание |
|---|---|---|
| `model` | string | Модель шлюза, например `"Zebra TC52"` |
| `os_version` | string | Версия ОС шлюза |
| `app_version` | string | Версия приложения-шлюза |

**Ответ `202 Accepted`:** аналогичен `/watch/packets`.

> В БД пакет сохраняется с `uploaded_from = "gateway"` (а не `"direct"`).

**Ошибки:** те же, что у `/watch/packets`.

---

## 5. Смены (Shifts)

### `GET /shifts`

Список смен с фильтрацией и пагинацией.

**Query-параметры:**

| Параметр | Тип | Описание |
|---|---|---|
| `device_id` | string | Фильтр по устройству |
| `employee_id` | UUID | Фильтр по сотруднику |
| `date_from` | datetime | Смены начиная с этой даты |
| `date_to` | datetime | Смены заканчивающиеся до этой даты |
| `page` | integer | Номер страницы (по умолчанию `1`) |
| `page_size` | integer | Размер страницы (1–100, по умолчанию `20`) |

**Ответ `200 OK`:**

```json
{
  "items": [
    {
      "id": "uuid",
      "packet_id": "uuid",
      "device_id": "device-001",
      "employee_id": "uuid",
      "employee_name": "Иван Иванов",
      "site_id": "site-001",
      "start_ts_ms": 1700000000000,
      "end_ts_ms": 1700003600000,
      "duration_minutes": 60,
      "schema_version": 1,
      "device_model": "Galaxy Watch 6",
      "device_fw": "One UI 6",
      "app_version": "1.0.0",
      "timezone": "UTC",
      "status": "processed",
      "samples_count": { "accel": 3600, "gyro": 3600, "hr": 120, "ble": 45, ... },
      "created_at": "2024-01-15T12:00:00Z"
    }
  ],
  "total": 150,
  "page": 1,
  "page_size": 20
}
```

---

### `GET /shifts/{shift_id}`

Детальная информация о смене.

**Ответ `200 OK`:** один объект смены (аналогично элементу из списка).

**Ошибки:** `404` — смена не найдена.

---

### `GET /shifts/{shift_id}/data/{data_type}`

Сенсорные данные смены по типу потока.

**`data_type`** — один из: `accel`, `gyro`, `baro`, `mag`, `hr`, `ble`, `wear`, `battery`, `downtime`.

**Query-параметры:**

| Параметр | Тип | Описание |
|---|---|---|
| `from_ts` | integer | Начало диапазона (мс) |
| `to_ts` | integer | Конец диапазона (мс) |
| `limit` | integer | Лимит записей (1–100000, по умолчанию `10000`) |
| `offset` | integer | Смещение (по умолчанию `0`) |
| `format` | string | `"csv"` — вернуть данные в CSV-формате вместо JSON |

**Ответ `200 OK`:**

```json
{
  "items": [
    {
      "uuid": "uuid",
      "stream": "accel",
      "shift_id": "uuid",
      "packet_id": "uuid",
      "device_id": "device-001",
      "ts_ms": 1700000001000,
      "payload": { "x": 0.12, "y": -0.03, "z": 9.81, "quality": 95 }
    }
  ],
  "total": 3600,
  "page": 1,
  "page_size": 10000
}
```

---

### `GET /shifts/{shift_id}/zones`

Посещения зон в рамках смены.

**Ответ `200 OK`:**

```json
{
  "shift_id": "uuid",
  "total_visits": 5,
  "total_zones": 3,
  "visits": [
    {
      "zone_id": "uuid", "zone_name": "Зона А", "zone_type": "work",
      "enter_ts_ms": 1700000000000, "exit_ts_ms": 1700001800000,
      "duration_sec": 1800, "avg_rssi": -65.5
    }
  ],
  "summary_by_zone": [
    { "zone_id": "uuid", "zone_name": "Зона А", "zone_type": "work",
      "total_duration_sec": 3600, "visit_count": 2 }
  ]
}
```

---

### `GET /shifts/{shift_id}/route`

Маршрут перемещения по зонам в хронологическом порядке.

**Ответ `200 OK`:**

```json
{
  "shift_id": "uuid",
  "route": [
    { "zone_id": "uuid", "zone_name": "Вход", "enter_ts_ms": 1700000000000, "exit_ts_ms": 1700000300000 },
    { "zone_id": "uuid", "zone_name": "Цех 1", "enter_ts_ms": 1700000300000, "exit_ts_ms": 1700003600000 }
  ]
}
```

---

### `GET /shifts/{shift_id}/activity`

Интервалы активности с классификацией действий.

**Ответ `200 OK`:**

```json
{
  "shift_id": "uuid",
  "total_intervals": 48,
  "intervals": [
    {
      "interval_id": "uuid",
      "activity_class": "V1",
      "start_ts_ms": 1700000000000,
      "end_ts_ms": 1700000120000,
      "duration_sec": 120,
      "zone_id": "uuid",
      "zone_name": "Цех 1",
      "confidence": 0.95
    }
  ],
  "summary": {
    "A1_sec": 600, "A2_sec": 300, "B1_sec": 1200, "B2_sec": 600,
    "V1_sec": 900, "V2_sec": 0, "V3_sec": 0, "V4_sec": 0, "total_sec": 3600
  }
}
```

> Классы активности: `A1`, `A2` — активная работа; `B1`, `B2` — умеренная; `V1`–`V4` — простой/нагрузка.

---

### `GET /shifts/{shift_id}/metrics`

Агрегированные метрики смены (производительность, ЧСС, аномалии и т.д.).

**Ответ `200 OK`:**

```json
{
  "shift_id": "uuid",
  "employee_name": "Иван Иванов",
  "site_name": "Завод №1",
  "shift_duration_sec": 28800,
  "on_site_duration_sec": 25200,
  "productivity_percent": 78.5,
  "v1_percent": 15.2,
  "avg_reaction_time_sec": 12.3,
  "median_reaction_time_sec": 10.5,
  "wear_compliance_percent": 95.0,
  "zones_visited": 4,
  "avg_hr_bpm": 82,
  "anomalies_count": 3,
  "data_quality_score": 0.97,
  "activity_breakdown": {
    "A1_sec": 7200, "A1_percent": 25.0,
    "V1_sec": 4320, "V1_percent": 15.0
  }
}
```

---

### `GET /shifts/{shift_id}/reaction-times`

Времена реакции сотрудника в зонах.

**Ответ `200 OK`:**

```json
{
  "shift_id": "uuid",
  "reactions": [
    {
      "zone_id": "uuid", "zone_name": "Цех 1",
      "zone_enter_ts_ms": 1700000000000,
      "activity_start_ts_ms": 1700000012000,
      "reaction_time_sec": 12,
      "has_productive_activity": true
    }
  ],
  "avg_sec": 12.3,
  "median_sec": 10.5
}
```

---

### `GET /shifts/{shift_id}/downtimes`

Простои смены с назначенными причинами.

**Ответ `200 OK`:**

```json
{
  "shift_id": "uuid",
  "downtimes": [
    {
      "interval_id": "uuid",
      "start_ts_ms": 1700001000000,
      "end_ts_ms": 1700001600000,
      "duration_sec": 600,
      "zone_id": "uuid",
      "zone_name": "Склад",
      "reason_id": "uuid",
      "reason_name": "Ожидание материала",
      "source": "manual",
      "assigned_by": "uuid"
    }
  ],
  "total_downtime_sec": 600,
  "reasons_summary": [
    { "reason_id": "uuid", "reason_name": "Ожидание материала", "total_sec": 600, "count": 1 }
  ]
}
```

---

### `POST /shifts/{shift_id}/downtimes/{interval_id}/assign`

Назначить причину простоя для интервала активности.

**Тело запроса:**

| Поле | Тип | Обязательно | Описание |
|---|---|---|---|
| `reason_id` | string | ✅ | UUID или код причины простоя из каталога |

**Ответ `201 Created`:**

```json
{
  "interval_id": "uuid",
  "reason_id": "uuid",
  "reason_name": "Ожидание материала",
  "source": "manual",
  "assigned_by": "uuid",
  "created_at": "2024-01-15T14:00:00Z"
}
```

**Ошибки:** `404` — смена или интервал не найдены.

---

### `PUT /shifts/{shift_id}/downtimes/{interval_id}/assign`

Изменить уже назначенную причину простоя. Тело — то же, что у POST.

**Ответ `200 OK`:**

```json
{
  "interval_id": "uuid",
  "reason_id": "uuid",
  "reason_name": "Плановый перерыв",
  "source": "manual",
  "assigned_by": "uuid",
  "created_at": "2024-01-15T14:00:00Z"
}
```

**Ошибки:** `404` — смена, интервал или назначение не найденю.

---

## 6. Устройства (Devices)

### `GET /devices`

Список устройств с фильтрацией.

**Query-параметры:**

| Параметр | Тип | Описание |
|---|---|---|
| `status` | string | Фильтр: `active`, `revoked` |
| `employee_id` | UUID | Фильтр по привязанному сотруднику |
| `page` | integer | По умолчанию `1` |
| `page_size` | integer | 1–100, по умолчанию `20` |

**Ответ `200 OK`:**

```json
{
  "items": [
    {
      "device_id": "device-001",
      "model": "Galaxy Watch 6",
      "firmware": "One UI 6",
      "app_version": "1.0.0",
      "employee_id": "uuid",
      "site_id": "site-001",
      "status": "active",
      "timezone": "UTC",
      "last_heartbeat_at": "2024-01-15T11:55:00Z",
      "last_sync_at": "2024-01-15T11:55:00Z",
      "created_at": "2024-01-10T09:00:00Z",
      "updated_at": "2024-01-15T11:55:00Z"
    }
  ],
  "total": 42,
  "page": 1,
  "page_size": 20
}
```

---

### `GET /devices/{device_id}`

Детальная информация об устройстве.

**Ответ `200 OK`:**

```json
{
  "device_id": "device-001",
  "model": "Galaxy Watch 6",
  "firmware": "One UI 6",
  "app_version": "1.0.0",
  "employee_id": "uuid",
  "site_id": "site-001",
  "status": "active",
  "timezone": "UTC",
  "last_heartbeat_at": "2024-01-15T11:55:00Z",
  "last_sync_at": "2024-01-15T11:55:00Z",
  "last_packet_id": "uuid",
  "last_packet_status": "processed",
  "last_packet_received_at": "2024-01-15T10:00:00Z",
  "created_at": "2024-01-10T09:00:00Z",
  "updated_at": "2024-01-15T11:55:00Z"
}
```

---

### `PATCH /devices/{device_id}`

Обновление метаданных устройства.

**Тело запроса (все поля опциональны):**

| Поле | Тип | Описание |
|---|---|---|
| `model` | string | Модель |
| `firmware` | string | Прошивка |
| `app_version` | string | Версия приложения |
| `employee_id` | UUID | Привязать к сотруднику |
| `site_id` | string | ID участка |
| `status` | string | `active` или `revoked` |
| `timezone` | string | Часовой пояс |

**Ответ `200 OK`:** полный объект устройства (аналогично `GET /devices/{device_id}`).

```json
{
  "device_id": "device-001",
  "model": "Galaxy Watch 6",
  "firmware": "One UI 6",
  "app_version": "2.0.0",
  "employee_id": "uuid",
  "site_id": "site-001",
  "status": "active",
  "timezone": "UTC",
  "last_heartbeat_at": "2024-01-15T11:55:00Z",
  "last_sync_at": "2024-01-15T11:55:00Z",
  "last_packet_id": "uuid",
  "last_packet_status": "processed",
  "last_packet_received_at": "2024-01-15T10:00:00Z",
  "created_at": "2024-01-10T09:00:00Z",
  "updated_at": "2024-01-15T12:00:00Z"
}
```

**Ошибки:** `404` — устройство не найдено.

---

### `POST /devices/{device_id}/bind`

Привязка устройства к сотруднику и участку.

**Тело запроса:**

| Поле | Тип | Обязательно | Описание |
|---|---|---|---|
| `employee_id` | UUID | ✅ | ID сотрудника |
| `site_id` | string | ✅ | ID участка |

**Ответ `200 OK`:**

```json
{
  "device_id": "device-001",
  "binding_id": "uuid",
  "employee_id": "uuid",
  "site_id": "site-001",
  "shift_date": "2024-01-15",
  "bound_at": "2024-01-15T08:00:00Z"
}
```

---

## 7. Привязки (Bindings)

Привязка связывает устройство, сотрудника, участок и дату смены.

### `POST /bindings/`

Создать привязку.

**Тело запроса:**

| Поле | Тип | Обязательно | Описание |
|---|---|---|---|
| `device_id` | string | ✅ | ID устройства |
| `employee_id` | UUID | ✅ | ID сотрудника |
| `site_id` | string | ✅ | ID участка |
| `shift_date` | date | ✅ | Дата смены (`"2024-01-15"`) |
| `shift_type` | string | ❌ | Тип смены: `"day"` (по умолчанию), `"night"` и др. |
| `bound_by` | UUID | ❌ | UUID оператора, создавшего привязку |

**Ответ `201 Created`:**

```json
{
  "id": "uuid",
  "device_id": "device-001",
  "employee_id": "uuid",
  "site_id": "site-001",
  "shift_date": "2024-01-15",
  "shift_type": "day",
  "bound_at": "2024-01-15T08:00:00Z",
  "bound_by": "uuid",
  "unbound_at": null,
  "unbound_by": null,
  "status": "active",
  "created_at": "2024-01-15T08:00:00Z",
  "updated_at": "2024-01-15T08:00:00Z"
}
```

**Ошибки:** `404` — устройство или сотрудник не найдены, `409` — активная привязка уже существует.

---

### `GET /bindings/`

Список привязок.

**Query-параметры:** `device_id`, `employee_id`, `shift_date`, `site_id`, `page`, `page_size`.

**Ответ `200 OK`:**

```json
{
  "items": [
    {
      "id": "uuid",
      "device_id": "device-001",
      "employee_id": "uuid",
      "site_id": "site-001",
      "shift_date": "2024-01-15",
      "shift_type": "day",
      "bound_at": "2024-01-15T08:00:00Z",
      "bound_by": "uuid",
      "unbound_at": null,
      "unbound_by": null,
      "status": "active",
      "created_at": "2024-01-15T08:00:00Z",
      "updated_at": "2024-01-15T08:00:00Z"
    }
  ],
  "total": 10,
  "page": 1,
  "page_size": 20
}
```

---

### `PUT /bindings/{binding_id}/close`

Закрыть (завершить) привязку.

**Тело запроса:**

| Поле | Тип | Описание |
|---|---|---|
| `unbound_by` | UUID | UUID оператора, закрывшего привязку |

**Ответ `200 OK`:** объект привязки с заполненными полями `unbound_at` и `status: "closed"`.

```json
{
  "id": "uuid",
  "device_id": "device-001",
  "employee_id": "uuid",
  "site_id": "site-001",
  "shift_date": "2024-01-15",
  "shift_type": "day",
  "bound_at": "2024-01-15T08:00:00Z",
  "bound_by": "uuid",
  "unbound_at": "2024-01-15T20:00:00Z",
  "unbound_by": "uuid",
  "status": "closed",
  "created_at": "2024-01-15T08:00:00Z",
  "updated_at": "2024-01-15T20:00:00Z"
}
```

**Ошибки:** `404` — привязка не найдена.

---

## 8. Сотрудники (Employees)

### `GET /employees/`

Список сотрудников.

**Query-параметры:**

| Параметр | Тип | Описание |
|---|---|---|
| `status` | string | Фильтр по статусу |
| `company_id` | UUID | Фильтр по компании |
| `brigade_id` | UUID | Фильтр по бригаде |
| `site_id` | string | Фильтр по участку |
| `limit` / `offset` | integer | Пагинация |

**Ответ `200 OK`:** массив объектов сотрудника:

```json
[
  {
    "uuid": "uuid",
    "full_name": "Иван Иванов",
    "company_id": "uuid",
    "position": "Оператор",
    "personnel_number": "12345",
    "pass_number": "A-001",
    "brigade_id": "uuid",
    "site_id": "site-001",
    "consent_pd_file": null,
    "consent_pd_date": null,
    "status": "active",
    "created_at": "2024-01-10T09:00:00Z",
    "updated_at": "2024-01-15T12:00:00Z"
  }
]
```

---

### `GET /employees/{employee_uuid}`

Получить сотрудника по UUID.

**Ответ `200 OK`:**

```json
{
  "uuid": "uuid",
  "full_name": "Иван Иванов",
  "company_id": "uuid",
  "position": "Оператор",
  "personnel_number": "12345",
  "pass_number": "A-001",
  "brigade_id": "uuid",
  "site_id": "site-001",
  "consent_pd_file": null,
  "consent_pd_date": null,
  "status": "active",
  "created_at": "2024-01-10T09:00:00Z",
  "updated_at": "2024-01-15T12:00:00Z"
}
```

**Ошибки:** `404` — сотрудник не найден.

---

### `POST /employees/`

Создать сотрудника.

**Тело запроса:**

| Поле | Тип | Обязательно | Описание |
|---|---|---|---|
| `full_name` | string | ✅ | ФИО (1–256 символов) |
| `company_id` | UUID | ❌ | ID компании |
| `position` | string | ❌ | Должность |
| `personnel_number` | string | ❌ | Табельный номер |
| `pass_number` | string | ❌ | Номер пропуска |
| `brigade_id` | UUID | ❌ | ID бригады |
| `site_id` | string | ❌ | ID участка |
| `consent_pd_file` | string | ❌ | Ссылка на файл согласия |
| `consent_pd_date` | date | ❌ | Дата подписания согласия |
| `status` | string | ❌ | По умолчанию `"active"` |

**Ответ `201 Created`:** объект сотрудника (см. `GET /employees/{uuid}`).

---

### `PUT /employees/{employee_uuid}`

Обновить сотрудника. Тело аналогично созданию, все поля опциональны.

**Ответ `200 OK`:** объект сотрудника (см. `GET /employees/{uuid}`).

**Ошибки:** `404` — сотрудник не найден.

---

### `DELETE /employees/{employee_uuid}`

Удалить сотрудника. **Ответ `204 No Content`.**

---

### `POST /employees/import`

Массовый импорт сотрудников из файла (CSV/Excel).

**Тип запроса:** `multipart/form-data`  
**Поле:** `file` — файл для загрузки.

**Ответ `200 OK`:**

```json
{
  "total": 50,
  "created": 45,
  "updated": 5,
  "errors": [
    { "row": 12, "error": "Не указано ФИО" }
  ]
}
```

---

## 9. Справочники

### Компании (`/companies/`)

| Метод | Путь | Описание |
|---|---|---|
| `GET` | `/companies/` | Список компаний |
| `GET` | `/companies/{uuid}` | Получить компанию |
| `POST` | `/companies/` | Создать компанию |
| `PUT` | `/companies/{uuid}` | Обновить компанию |
| `DELETE` | `/companies/{uuid}` | Удалить (`204`) |

**Поля создания/обновления:**

| Поле | Тип | Описание |
|---|---|---|
| `name` | string | Название (1–256 символов) |
| `inn` | string | ИНН (до 12 символов) |
| `status` | string | `"active"` по умолчанию |

**Ответ `200/201`:**

```json
{
  "uuid": "uuid",
  "name": "ООО Завод"",
  "inn": "1234567890",
  "status": "active",
  "created_at": "2024-01-01T00:00:00Z",
  "updated_at": "2024-01-15T12:00:00Z"
}
```

---

### Бригады (`/brigades/`)

| Метод | Путь | Описание |
|---|---|---|
| `GET` | `/brigades/` | Список (фильтры: `company_id`, `site_uuid`) |
| `GET` | `/brigades/{uuid}` | Получить бригаду |
| `POST` | `/brigades/` | Создать |
| `PUT` | `/brigades/{uuid}` | Обновить |
| `DELETE` | `/brigades/{uuid}` | Удалить (`204`) |

**Поля:**

| Поле | Тип | Обязательно | Описание |
|---|---|---|---|
| `name` | string | ✅ | Название бригады |
| `company_id` | UUID | ✅ | ID компании |
| `site_id` | string | ❌ | ID участка |
| `foreman_id` | UUID | ❌ | UUID бригадира |
| `status` | string | ❌ | По умолчанию `"active"` |

**Ответ `200/201`:**

```json
{
  "uuid": "uuid",
  "name": "Бригада №1",
  "company_id": "uuid",
  "site_id": "site-001",
  "foreman_id": "uuid",
  "status": "active",
  "created_at": "2024-01-01T00:00:00Z"
}
```

---

### Участки (`/sites/`)

| Метод | Путь | Описание |
|---|---|---|
| `GET` | `/sites/` | Список участков |
| `GET` | `/sites/{site_id}` | Получить участок |
| `POST` | `/sites/` | Создать |
| `PATCH` | `/sites/{site_id}` | Обновить |
| `DELETE` | `/sites/{site_id}` | Удалить (`204`) |

**Поля создания:**

| Поле | Тип | Обязательно | Описание |
|---|---|---|---|
| `site_id` | string | ✅ | Уникальный ID участка (бизнес-ключ) |
| `name` | string | ✅ | Название |
| `address` | string | ❌ | Адрес |
| `timezone` | string | ❌ | По умолчанию `"Europe/Moscow"` |
| `status` | string | ❌ | По умолчанию `"active"` |

**Ответ `200/201`:**

```json
{
  "uuid": "uuid",
  "site_id": "site-001",
  "name": "Завод №1",
  "address": "г. Москва, ул. Промышленная, 1",
  "timezone": "Europe/Moscow",
  "status": "active",
  "created_at": "2024-01-01T00:00:00Z",
  "updated_at": "2024-01-15T12:00:00Z"
}
```

---

## 10. Зоны (Zones)

### `GET /sites/{site_id}/zones`

Список зон участка.

**Query-параметры:** `page`, `page_size` (до 200, по умолчанию 50).

**Ответ `200 OK`:**

```json
{
  "items": [
    {
      "uuid": "uuid",
      "site_id": "site-001",
      "name": "Цех №1",
      "zone_type": "work",
      "productivity_percent": 100,
      "lat": 55.7558,
      "lon": 37.6173,
      "floor": 1,
      "status": "active",
      "created_at": "2024-01-01T00:00:00Z",
      "updated_at": "2024-01-01T00:00:00Z"
    }
  ],
  "total": 5,
  "page": 1,
  "page_size": 50
}
```

---

### `POST /sites/{site_id}/zones`

Создать зону на участке.

**Тело запроса:**

| Поле | Тип | Обязательно | Описание |
|---|---|---|---|
| `name` | string | ✅ | Название зоны |
| `zone_type` | string | ✅ | Тип: `"work"`, `"rest"`, `"transit"` и т.д. |
| `productivity_percent` | integer | ❌ | Коэффициент продуктивности 0–100 (по умолчанию 100) |
| `lat` | float | ❌ | Широта |
| `lon` | float | ❌ | Долгота |
| `floor` | integer | ❌ | Этаж |
| `status` | string | ❌ | По умолчанию `"active"` |

**Ответ `201 Created`:**

```json
{
  "uuid": "uuid",
  "site_id": "site-001",
  "name": "Цех №1",
  "zone_type": "work",
  "productivity_percent": 100,
  "lat": 55.7558,
  "lon": 37.6173,
  "floor": 1,
  "status": "active",
  "created_at": "2024-01-15T08:00:00Z",
  "updated_at": "2024-01-15T08:00:00Z"
}
```

---

### `PUT /zones/{zone_id}`

Обновить зону. Все поля опциональны (аналогично созданию).

**Ответ `200 OK`:** объект зоны (аналогично `POST /sites/{site_id}/zones`).

**Ошибки:** `404` — зона не найдена.

---

### `POST /zones/{zone_id}/beacons`

Добавить BLE-маяк к зоне.

**Тело запроса:**

| Поле | Тип | Обязательно | Описание |
|---|---|---|---|
| `beacon_id` | string | ✅ | MAC-адрес или UUID маяка (до 128 символов) |
| `lat` | float | ❌ | Широта маяка |
| `lon` | float | ❌ | Долгота |
| `status` | string | ❌ | По умолчанию `"active"` |

**Ответ `201 Created`:**

```json
{
  "uuid": "uuid",
  "zone_id": "uuid",
  "beacon_id": "AA:BB:CC:DD:EE:FF",
  "lat": 55.7558,
  "lon": 37.6173,
  "status": "active",
  "created_at": "2024-01-15T08:00:00Z",
  "updated_at": "2024-01-15T08:00:00Z"
}
```

**Ошибки:** `404` — зона не найдена.

---

### `DELETE /zones/{zone_id}/beacons/{beacon_id}`

Удалить маяк из зоны. **Ответ `204 No Content`.**

---

### `GET /admin/unknown-beacons`

Список BLE-маяков, обнаруженных устройствами, но не привязанных ни к одной зоне.

**Query-параметры:** `status`, `device_id`, `page`, `page_size`.

**Ответ `200 OK`:**

```json
{
  "items": [
    {
      "uuid": "uuid",
      "beacon_id": "AA:BB:CC:DD:EE:FF",
      "device_id": "device-001",
      "first_seen_ts_ms": 1700000000000,
      "last_seen_ts_ms": 1700003600000,
      "count": 42,
      "status": "new",
      "created_at": "2024-01-15T08:00:00Z",
      "updated_at": "2024-01-15T12:00:00Z"
    }
  ],
  "total": 5,
  "page": 1,
  "page_size": 50
}
```

---

## 11. Аналитика (Analytics)

### `GET /analytics/device/{device_id}/summary`

Сводная статистика по устройству.

**Ответ `200 OK`:**

```json
{
  "device_id": "device-001",
  "status": "active",
  "total_packets": 150,
  "accepted_packets": 148,
  "processed_packets": 146,
  "error_packets": 2,
  "total_shifts": 30,
  "samples_count": 1500000,
  "last_heartbeat_at": "2024-01-15T11:55:00Z",
  "last_sync_at": "2024-01-15T11:55:00Z"
}
```

**Ошибки:** `404` — устройство не найдено.

---

### `GET /analytics/employee/{employee_id}/summary`

Сводная статистика по сотруднику.

**Ответ `200 OK`:**

```json
{
  "employee_id": "uuid",
  "total_devices": 2,
  "total_shifts": 45,
  "total_packets": 135,
  "samples_count": 2000000
}
```

---

### `GET /analytics/zones`

Агрегированная статистика по всем зонам (количество BLE-событий по beacon_id).

**Ответ `200 OK`:** массив `[{ "beacon_id": "AA:BB:CC", "events": 842 }]`

---

### `GET /analytics/productivity`

Продуктивность сотрудников за период.

**Query-параметры:**

| Параметр | Тип | Описание |
|---|---|---|
| `site_id` | string | Фильтр по участку |
| `employee_id` | UUID | Фильтр по сотруднику |
| `company_id` | UUID | Фильтр по компании |
| `brigade_id` | UUID | Фильтр по бригаде |
| `date_from` | date | Начало периода (`"2024-01-01"`) |
| `date_to` | date | Конец периода |
| `page` | integer | По умолчанию `1` |
| `page_size` | integer | 1–200, по умолчанию `50` |

**Ответ `200 OK`:**

```json
{
  "period": { "date_from": "2024-01-01", "date_to": "2024-01-31" },
  "items": [
    {
      "employee_id": "uuid",
      "employee_name": "Иван Иванов",
      "company": "ООО Завод",
      "total_shifts": 22,
      "total_hours_on_site": 176.5,
      "avg_productivity_percent": 81.2,
      "avg_v1_percent": 12.3,
      "avg_reaction_time_sec": 11.5,
      "anomalies_total": 7
    }
  ],
  "aggregates": {
    "avg_productivity_percent": 78.5,
    "avg_v1_percent": 14.1,
    "avg_reaction_time_sec": 13.2
  },
  "total": 25,
  "page": 1,
  "page_size": 50
}
```

---

## 12. Admin API

> 🔐 Все эндпоинты требуют `Authorization: Bearer <token>` с ролью **`admin`**.

### `GET /admin/stats`

Системная статистика.

**Ответ `200 OK`:**

```json
{
  "active_devices": 42,
  "revoked_devices": 3,
  "total_packets": 12500,
  "accepted_packets": 100,
  "processing_packets": 5,
  "error_packets": 12
}
```

---

### `POST /admin/registration-codes`

Генерация регистрационных кодов для устройств.

**Query-параметры:**

| Параметр | Тип | Описание |
|---|---|---|
| `count` | integer | Количество кодов (1–200, по умолчанию `1`) |
| `expires_in_days` | integer | Срок действия 1–365 дней (по умолчанию `30`) |

**Ответ `201 Created`:**

```json
{
  "items": [
    { "code": "A1B2C3D4E5F6", "expires_at": "2024-02-14T12:00:00Z" }
  ]
}
```

---

### `GET /admin/packets`

Список всех пакетов с фильтрацией.

**Query-параметры:** `status` (`accepted`/`processing`/`processed`/`error`), `device_id`, `from_ts`, `to_ts`, `page`, `page_size`.

**Ответ `200 OK`:**

```json
{
  "items": [
    {
      "uuid": "uuid",
      "packet_id": "550e8400-e29b-41d4-a716-446655440000",
      "device_id": "device-001",
      "status": "processed",
      "received_at": "2024-01-15T12:00:00Z",
      "created_at": "2024-01-15T12:00:00Z",
      "updated_at": "2024-01-15T12:05:00Z"
    }
  ],
  "total": 150,
  "page": 1,
  "page_size": 20
}
```

---

### `GET /admin/packets/{packet_id}/log`

Лог обработки пакета (шаги pipeline).

**Ответ `200 OK`:** массив шагов:

```json
[
  {
    "uuid": "uuid",
    "packet_id": "uuid",
    "step": "received",
    "status": "ok",
    "message": "Packet accepted",
    "duration_ms": 5,
    "created_at": "2024-01-15T12:00:00Z"
  }
]
```

Шаги: `received` → `decrypt_parse` → `classify` → `metrics` → ...

---

### `POST /admin/packets/{packet_id}/reprocess`

Поставить пакет в очередь на повторную обработку (например, после ошибки).

**Ответ `202 Accepted`:**

```json
{ "status": "queued" }
```

**Ошибки:** `404` — пакет не найден.

---

### `GET /admin/crypto/public-key`

Получить текущий публичный ключ сервера (RSA-OAEP-SHA256) для шифрования payload.

**Ответ `200 OK`:**

```json
{ "public_key_pem": "-----BEGIN PUBLIC KEY-----\n..." }
```

---

### `POST /admin/crypto/rotate-key`

Ротация крипто-ключа. Старый ключ помечается как `rotated`, создаётся новый.

**Ответ `200 OK`:**

```json
{
  "key_name": "rotated-1705320000",
  "public_key_pem": "-----BEGIN PUBLIC KEY-----\n...",
  "algorithm": "RSA-OAEP-SHA256",
  "key_size_bits": 2048
}
```

---

## 13. Причины простоев (Downtime Reasons)

Справочник причин простоев — используется при назначении причины интервалу смены.

### `GET /downtime-reasons/`

Список причин простоев.

**Query-параметры:**

| Параметр | Тип | Описание |
|---|---|---|
| `is_active` | boolean | Фильтр по активности (`true`/`false`) |
| `page` | integer | По умолчанию `1` |
| `page_size` | integer | 1–500, по умолчанию `100` |

**Ответ `200 OK`:**

```json
{
  "items": [
    {
      "uuid": "uuid",
      "code": "WAIT_MATERIAL",
      "name": "Ожидание материала",
      "category": "supply",
      "is_active": true,
      "sort_order": 10,
      "created_at": "2024-01-01T00:00:00Z",
      "updated_at": "2024-01-01T00:00:00Z"
    }
  ],
  "total": 15,
  "page": 1,
  "page_size": 100
}
```

---

### `POST /downtime-reasons/`

Создать причину простоя.

**Тело запроса:**

| Поле | Тип | Обязательно | Описание |
|---|---|---|---|
| `code` | string | ✅ | Уникальный код (1–32 символа), например `"WAIT_MATERIAL"` |
| `name` | string | ✅ | Название причины (до 256 символов) |
| `category` | string | ❌ | Категория, например `"supply"`, `"equipment"`, `"personnel"` |
| `is_active` | boolean | ❌ | По умолчанию `true` |
| `sort_order` | integer | ❌ | Порядок сортировки, по умолчанию `0` |

**Ответ `201 Created`:**

```json
{
  "uuid": "uuid",
  "code": "EQUIPMENT_FAIL",
  "name": "Поломка оборудования",
  "category": "equipment",
  "is_active": true,
  "sort_order": 20,
  "created_at": "2024-01-15T08:00:00Z",
  "updated_at": "2024-01-15T08:00:00Z"
}
```

---

### `PUT /downtime-reasons/{reason_id}`

Обновить причину простоя. Все поля опциональны.

**Тело запроса:**

| Поле | Тип | Описание |
|---|---|---|
| `name` | string | Новое название |
| `category` | string | Категория |
| `is_active` | boolean | Активна ли причина |
| `sort_order` | integer | Порядок сортировки |

**Ответ `200 OK`:** объект причины простоя (аналогично `POST`).

**Ошибки:** `404` — причина не найдена.

---

## 14. Аномалии (Anomalies)

### `GET /shifts/{shift_id}/anomalies`

Аномалии конкретной смены.

**Ответ `200 OK`:**

```json
{
  "shift_id": "uuid",
  "total": 3,
  "anomalies": [
    {
      "id": "uuid",
      "shift_id": "uuid",
      "device_id": "device-001",
      "employee_id": "uuid",
      "anomaly_type": "off_wrist",
      "severity": "medium",
      "start_ts_ms": 1700001000000,
      "end_ts_ms": 1700001300000,
      "description": "Устройство снято с руки на 5 минут",
      "status": "open",
      "details_json": { "duration_sec": 300 },
      "comment": null,
      "resolved_by": null,
      "resolved_at": null,
      "created_at": "2024-01-15T12:05:00Z",
      "updated_at": "2024-01-15T12:05:00Z"
    }
  ]
}
```

> Типы аномалий (`anomaly_type`): `off_wrist`, `data_gap`, `zero_hr`, `wear_toggle`, `fast_zone_switch`.  
> Серьёзность (`severity`): `low`, `medium`, `high`.  
> Статусы (`status`): `open`, `resolved`, `ignored`.

---

### `GET /anomalies`

Список всех аномалий по всем сменам с фильтрацией.

**Query-параметры:**

| Параметр | Тип | Описание |
|---|---|---|
| `site_id` | string | Фильтр по участку |
| `employee_id` | UUID | Фильтр по сотруднику |
| `severity` | string | Фильтр: `low`, `medium`, `high` |
| `status` | string | Фильтр: `open`, `resolved`, `ignored` |
| `anomaly_type` | string | Тип аномалии |
| `date_from` | datetime | Начало периода |
| `date_to` | datetime | Конец периода |
| `page` | integer | По умолчанию `1` |
| `page_size` | integer | 1–200, по умолчанию `50` |

**Ответ `200 OK`:**

```json
{
  "items": [ { "id": "uuid", "shift_id": "uuid", "anomaly_type": "off_wrist", "severity": "medium", "status": "open", "..." : "..." } ],
  "total": 42,
  "page": 1,
  "page_size": 50
}
```

---

### `PATCH /anomalies/{anomaly_id}`

Обновить статус аномалии (закрыть, проигнорировать, добавить комментарий).

**Тело запроса:**

| Поле | Тип | Описание |
|---|---|---|
| `status` | string | Новый статус: `resolved` или `ignored` |
| `comment` | string | Комментарий оператора |
| `resolved_by` | UUID | UUID пользователя, закрывшего аномалию |

**Ответ `200 OK`:** полный объект аномалии (аналогично элементу из `GET /anomalies`).

**Ошибки:** `404` — аномалия не найдена.

---

## 15. Пакеты — внутренний API (`/packets`)

> Служебные эндпоинты для CRUD пакетов. Предназначены для внутреннего использования (дашборд/администратор).

### `GET /packets/`

Список пакетов.

**Query-параметры:** `status` (`accepted`/`processing`/`processed`/`error`), `limit`, `offset`, `order_by`.

**Ответ `200 OK`:** массив объектов пакета:

```json
[
  {
    "uuid": "uuid",
    "packet_id": "550e8400-e29b-41d4-a716-446655440000",
    "device_id": "device-001",
    "shift_start_ts": 1700000000000,
    "shift_end_ts": 1700003600000,
    "schema_version": 1,
    "status": "processed",
    "uploaded_from": "direct",
    "payload_size_bytes": 2048,
    "error_message": null,
    "received_at": "2024-01-15T12:00:00Z",
    "updated_at": "2024-01-15T12:05:00Z"
  }
]
```

---

### `GET /packets/{packet_uuid}`

Детали пакета по его UUID (первичный ключ в БД, не `packet_id`).

**Ответ `200 OK`:** один объект пакета (аналогично элементу из списка).

**Ошибки:** `404` — пакет не найден.

---

### `POST /packets/`

Создать пакет напрямую (минуя Watch/Gateway API).

**Тело запроса:**

| Поле | Тип | Обязательно | Описание |
|---|---|---|---|
| `packet_id` | string | ✅ | Уникальный ID пакета |
| `device_id` | string | ✅ | ID устройства |
| `shift_start_ts` | integer | ✅ | Начало смены (мс) |
| `shift_end_ts` | integer | ✅ | Конец смены (мс) |
| `schema_version` | integer | ✅ | Версия схемы (≥1) |
| `payload_enc` | string | ✅ | Зашифрованный payload (Base64) |
| `payload_key_enc` | string | ✅ | Зашифрованный ключ (Base64) |
| `iv` | string | ✅ | Вектор инициализации (Base64) |
| `payload_hash` | string | ✅ | SHA-256 хеш (64 hex-символа) |
| `payload_size_bytes` | integer | ❌ | Размер payload в байтах |
| `uploaded_from` | string | ❌ | `"direct"` (по умолчанию) или `"gateway"` |

**Ответ `201 Created`:** объект пакета.

---

### `PATCH /packets/{packet_uuid}/status`

Обновить статус пакета вручную.

**Тело запроса:**

| Поле | Тип | Обязательно | Описание |
|---|---|---|---|
| `status` | string | ✅ | Новый статус: `accepted`, `processing`, `processed`, `error` |
| `error_message` | string | ❌ | Сообщение об ошибке |

**Ответ `200 OK`:** обновлённый объект пакета.

---

### `DELETE /packets/{packet_uuid}`

Удалить пакет. **Ответ `204 No Content`.**

---

## 16. Журнал аудита (`/audit-log`)

Журнал действий пользователей в системе.

### `GET /audit-log/`

Список записей журнала аудита.

**Query-параметры:**

| Параметр | Тип | Описание |
|---|---|---|
| `user_id` | UUID | Фильтр по пользователю |
| `action` | string | Фильтр по действию, например `"create"`, `"update"`, `"delete"` |
| `entity_type` | string | Фильтр по типу сущности, например `"device"`, `"employee"` |
| `date_from` | datetime | Начало периода |
| `date_to` | datetime | Конец периода |
| `page` | integer | По умолчанию `1` |
| `page_size` | integer | 1–200, по умолчанию `50` |

**Ответ `200 OK`:**

```json
{
  "items": [
    {
      "id": "uuid",
      "user_id": "uuid",
      "user_email": "admin@company.com",
      "action": "update",
      "entity_type": "device",
      "entity_id": "device-001",
      "details": { "status": { "old": "active", "new": "revoked" } },
      "created_at": "2024-01-15T14:30:00Z"
    }
  ],
  "total": 250,
  "page": 1,
  "page_size": 50
}
```

---

### `GET /audit-log/export`

Экспортировать журнал аудита в **CSV-файл**. Параметры фильтрации — те же, что у `GET /audit-log/`.

**Ответ `200 OK`:** файл с заголовком `Content-Type: text/csv`.

---

## 17. Коды ошибок

| Код | Значение |
|---|---|
| `400` | Ошибка валидации данных запроса |
| `401` | Не авторизован (нет токена или токен невалиден) |
| `403` | Нет прав (устройство REVOKED или недостаточная роль) |
| `404` | Ресурс не найден |
| `409` | Конфликт (дублирование, уже существует) |
| `422` | Ошибка типов данных (например, не UUID там, где ожидался UUID) |
| `429` | Превышен rate limit (подождите и повторите) |
| `500` | Внутренняя ошибка сервера |

**Формат ошибки:**

```json
{ "detail": "Описание ошибки" }
```

---

*Документация сгенерирована на основе исходного кода. Последнее обновление: март 2024.*
