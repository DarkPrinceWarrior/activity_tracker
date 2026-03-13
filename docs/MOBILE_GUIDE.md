# 📱 Руководство по интеграции мобильного приложения

> Пошаговый алгоритм работы с бэкендом + готовые моковые данные для каждого запроса.

---

## Оглавление

1. [Предварительная настройка (одноразово)](#1-предварительная-настройка)
2. [Регистрация устройства](#2-регистрация-устройства)
3. [Получение токенов устройства](#3-получение-токенов)
4. [Heartbeat (каждые N минут)](#4-heartbeat)
5. [Отправка пакета данных](#5-отправка-пакета)
6. [Проверка статуса пакета](#6-проверка-статуса)
7. [Обновление токена (refresh)](#7-обновление-токена)
8. [Отправка через Gateway (телефон-шлюз)](#8-gateway)

---

## Базовый URL

```
http://<SERVER_IP>:8000/api/v1
```

---

## 1. Предварительная настройка

### 1.1 Логин администратора

Нужно залогиниться как админ, чтобы сгенерировать коды регистрации.

```http
POST /api/v1/auth/login
Content-Type: application/json
```

**Моковые данные:**
```json
{
  "email": "admin@company.com",
  "password": "adminpass"
}
```

**Ответ:**
```json
{
  "access_token": "eyJhbGciOiJIUzI1NiIs...",
  "token_type": "Bearer"
}
```

> ⚠️ Первого админа нужно создать через миграцию или SQL-скрипт в БД.

---

### 1.2 Генерация кодов регистрации

Админ генерирует одноразовые коды для регистрации устройств.

```http
POST /api/v1/admin/registration-codes?count=5&expires_in_days=30
Authorization: Bearer <admin_token>
```

**Ответ:**
```json
{
  "items": [
    { "code": "ABC12345", "expires_at": "2026-04-12T00:00:00Z" },
    { "code": "DEF67890", "expires_at": "2026-04-12T00:00:00Z" }
  ]
}
```

> Запишите коды — каждый можно использовать только один раз.

---

## 2. Регистрация устройства

Первое, что делает устройство (часы) при первом запуске.

```http
POST /api/v1/auth/device/register
Content-Type: application/json
```

**Моковые данные:**
```json
{
  "registration_code": "ABC12345",
  "model": "Galaxy Watch 6",
  "firmware": "One UI 6.0",
  "app_version": "1.0.0",
  "timezone": "Europe/Moscow"
}
```

**Ответ:**
```json
{
  "device_id": "device-a1b2c3d4e5f6",
  "device_secret": "kL9mN2...(длинная строка)",
  "server_public_key_pem": "-----BEGIN PUBLIC KEY-----\n...",
  "server_time": "2026-03-12T14:30:00Z"
}
```

> ⚠️ **Сохраните `device_id` и `device_secret`** — они нужны для всех дальнейших запросов.

---

## 3. Получение токенов

После регистрации устройство запрашивает токены доступа.

```http
POST /api/v1/auth/device/token
Content-Type: application/json
```

**Моковые данные:**
```json
{
  "device_id": "device-a1b2c3d4e5f6",
  "device_secret": "kL9mN2...(из шага 2)"
}
```

**Ответ:**
```json
{
  "access_token": "q3W7xK9...(длинная строка)",
  "refresh_token": "rT5yU8...(длинная строка)",
  "expires_in": 3600,
  "server_time": "2026-03-12T14:31:00Z"
}
```

---

## 4. Heartbeat

Устройство шлёт heartbeat каждые 1–5 минут, чтобы сервер знал, что оно живо.

```http
POST /api/v1/watch/heartbeat
Content-Type: application/json
```

**Моковые данные:**
```json
{
  "device_id": "device-a1b2c3d4e5f6",
  "device_time_ms": 1710251400000,
  "battery_level": 0.85,
  "is_collecting": true,
  "pending_packets": 2,
  "app_version": "1.0.0"
}
```

| Поле | Описание |
|---|---|
| `device_time_ms` | Текущее время на часах в миллисекундах (Unix epoch × 1000) |
| `battery_level` | От 0.0 до 1.0 |
| `is_collecting` | Собирает ли данные сейчас |
| `pending_packets` | Сколько пакетов ещё не отправлено |

**Ответ:**
```json
{
  "server_time": "2026-03-12T14:35:00Z",
  "server_time_ms": 1710251700000,
  "time_offset_ms": -300000,
  "commands": []
}
```

---

## 5. Отправка пакета данных

Устройство отправляет накопленные данные (акселерометр, пульс и т.д.) пачками.

```http
POST /api/v1/watch/packets
Content-Type: application/json
Idempotency-Key: 550e8400-e29b-41d4-a716-446655440000
```

**Моковые данные:**
```json
{
  "packet_id": "550e8400-e29b-41d4-a716-446655440000",
  "device_id": "device-a1b2c3d4e5f6",
  "shift_start_ts": 1710230400000,
  "shift_end_ts": 1710259200000,
  "schema_version": 1,
  "payload_enc": "eyJzY2hlbWFfdmVyc2lvbiI6IDEsICJzYW1wbGVzIjoge319",
  "payload_key_enc": "ZHVtbXkta2V5",
  "iv": "AAAAAAAAAAAAAAAA",
  "payload_hash": "a948904f2f0f479b8f8564e9c624a8f5b9de6e1a35e4c1d7f2a2b6c3d4e5f6a7",
  "payload_size_bytes": 36
}
```

| Поле | Описание |
|---|---|
| `packet_id` | UUID v4, уникальный для каждого пакета |
| `Idempotency-Key` | Заголовок, **обязателен**, должен совпадать с `packet_id` |
| `shift_start_ts` / `shift_end_ts` | Начало/конец смены, ms |
| `payload_enc` | Base64-encoded зашифрованные данные |
| `iv` | Base64-encoded вектор инициализации (12 байт) |
| `payload_hash` | SHA-256 от расшифрованных данных (hex, 64 символа) |

**Ответ (202):**
```json
{
  "packet_id": "550e8400-e29b-41d4-a716-446655440000",
  "status": "accepted",
  "received_at": "2026-03-12T14:36:00Z",
  "server_time": "2026-03-12T14:36:00Z"
}
```

> Повторная отправка того же `packet_id` → **409 Conflict** (идемпотентность).

---

## 6. Проверка статуса пакета

Устройство может проверить, что с его пакетом.

```http
GET /api/v1/watch/packets/550e8400-e29b-41d4-a716-446655440000
x-device-id: device-a1b2c3d4e5f6
```

**Ответ:**
```json
{
  "packet_id": "550e8400-e29b-41d4-a716-446655440000",
  "status": "accepted"
}
```

Статусы: `accepted` → `processed` (или `error`)

---

## 7. Обновление токена

Когда `access_token` истечёт (через `expires_in` секунд), нужно обновить.

```http
POST /api/v1/auth/device/refresh
Content-Type: application/json
```

**Моковые данные:**
```json
{
  "device_id": "device-a1b2c3d4e5f6",
  "refresh_token": "rT5yU8...(из шага 3)"
}
```

**Ответ — новые токены:**
```json
{
  "access_token": "newAccessToken...",
  "refresh_token": "newRefreshToken...",
  "expires_in": 3600,
  "server_time": "2026-03-12T15:31:00Z"
}
```

> ⚠️ Старый refresh_token после использования **перестаёт работать**.

---

## 8. Gateway (отправка с телефона-шлюза)

Если часы отправляют данные не напрямую, а через телефон рядом.

```http
POST /api/v1/gateway/packets
Content-Type: application/json
Idempotency-Key: 660e8400-e29b-41d4-a716-446655440001
x-device-id: device-a1b2c3d4e5f6
```

**Моковые данные:**
```json
{
  "packet_id": "660e8400-e29b-41d4-a716-446655440001",
  "device_id": "device-a1b2c3d4e5f6",
  "shift_start_ts": 1710230400000,
  "shift_end_ts": 1710259200000,
  "schema_version": 1,
  "payload_enc": "eyJzY2hlbWFfdmVyc2lvbiI6IDEsICJzYW1wbGVzIjoge319",
  "payload_key_enc": "ZHVtbXkta2V5",
  "iv": "AAAAAAAAAAAAAAAA",
  "payload_hash": "a948904f2f0f479b8f8564e9c624a8f5b9de6e1a35e4c1d7f2a2b6c3d4e5f6a7",
  "payload_size_bytes": 36,
  "gateway_device_info": {
    "model": "Samsung Galaxy S24",
    "os_version": "Android 14",
    "app_version": "3.1.0"
  }
}
```

---

## Порядок вызовов — блок-схема

```
┌─── ПЕРВЫЙ ЗАПУСК ─────────────────────────────────┐
│                                                     │
│  1. POST /auth/device/register  (код от админа)    │
│         ↓ получаем device_id + device_secret       │
│  2. POST /auth/device/token                        │
│         ↓ получаем access_token + refresh_token    │
│                                                     │
└─────────────────────────────────────────────────────┘
           ↓
┌─── РАБОЧИЙ ЦИКЛ (повторяется) ────────────────────┐
│                                                     │
│  3. POST /watch/heartbeat      (каждые 1-5 мин)   │
│  4. POST /watch/packets        (когда есть данные) │
│  5. GET  /watch/packets/{id}   (проверка статуса)  │
│                                                     │
└─────────────────────────────────────────────────────┘
           ↓
┌─── ОБНОВЛЕНИЕ ТОКЕНА ─────────────────────────────┐
│                                                     │
│  6. POST /auth/device/refresh  (когда token истёк) │
│         ↓ получаем новые access + refresh          │
│     → возврат в РАБОЧИЙ ЦИКЛ                      │
│                                                     │
└─────────────────────────────────────────────────────┘
```

---

## Коды ошибок

| Код | Значение | Что делать |
|-----|----------|------------|
| 400 | Невалидные данные | Проверить формат запроса |
| 401 | Нет/невалидный токен | Обновить токен через refresh |
| 403 | Устройство отозвано | Перерегистрировать устройство |
| 404 | Не найдено | Проверить device_id / packet_id |
| 409 | Дубликат | Пакет уже отправлен, это нормально |
| 422 | Ошибка валидации | Проверить типы полей (UUID и т.д.) |
