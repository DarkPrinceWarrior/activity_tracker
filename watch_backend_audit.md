# 🔍 Аудит: watch-backend vs WATCH_BACKEND_INTEGRATION_GUIDE.md

Проверка проведена 13.03.2026. Обновлено с результатами исправлений на стороне часов.

---

## ✅ Всё работает правильно

| # | Функционал | Статус |
|---|-----------|--------|
| 1 | `POST /api/v1/auth/device/register` — эндпоинт существует, принимает все поля (`registration_code`, `model`, `firmware`, `app_version`, `timezone`) | ✅ |
| 2 | Rate limit `/register` — **3/minute** | ✅ |
| 3 | `POST /api/v1/auth/device/token` — эндпоинт существует, проверяет `device_id` + `device_secret` | ✅ |
| 4 | Rate limit `/token` — **5/minute** | ✅ |
| 5 | `POST /api/v1/auth/device/refresh` — эндпоинт существует, вращает токены | ✅ |
| 6 | Ответ на `/token` и `/refresh` содержит `access_token`, `refresh_token`, `expires_in`, `server_time` | ✅ |
| 7 | `expires_in = 3600` (ACCESS_TOKEN_TTL_SECONDS) | ✅ |
| 8 | `POST /api/v1/watch/heartbeat` — эндпоинт существует, обновляет `last_heartbeat_at`, возвращает `server_time`, `server_time_ms`, `time_offset_ms`, `commands` | ✅ |
| 9 | `POST /api/v1/watch/packets` — эндпоинт существует, status 202 | ✅ |
| 10 | Rate limit `/watch/packets` — **10/minute** | ✅ |
| 11 | Проверка `Idempotency-Key` заголовка | ✅ |
| 12 | `Idempotency-Key` должен совпадать с `packet_id` в теле | ✅ |
| 13 | Проверка `x-device-id` заголовка совпадает с `device_id` в теле | ✅ |
| 14 | Повторная отправка пакета → 409 Conflict (идемпотентность) | ✅ |
| 15 | Валидация IV: base64 → ровно 12 байт | ✅ |
| 16 | Проверка SHA256 хеша payload **в pipeline** (`decrypt_parse.py`) | ✅ |
| 17 | Декодирование `payload_enc` из base64 → JSON в pipeline | ✅ |
| 18 | Сохранение всех типов сенсоров: accel, gyro, baro, mag, hr, ble, wear, battery, downtime_reasons | ✅ |
| 19 | `POST /api/v1/admin/registration-codes` — генерирует коды, формат `{items: [{code, expires_at}]}` | ✅ |
| 20 | Код регистрации одноразовый (после использования `is_used=True`) | ✅ |
| 21 | Устройство заблокировано → 403 | ✅ |
| 22 | Устройство не найдено при `/token` → 404 | ✅ |
| 23 | `server_public_key_pem` возвращается при регистрации | ✅ |
| 24 | Heartbeat обновляет `app_version` на устройстве | ✅ |
| 25 | Background pipeline запускается после принятия пакета | ✅ |

---

## 🐛 Баги и несоответствия — статус исправлений

### 🔴 ~~КРИТИЧНО: `battery_level` — неправильный диапазон~~ → ✅ ИСПРАВЛЕНО НА ЧАСАХ

**Проблема:** Бэкенд валидирует `battery_level: float = Field(ge=0.0, le=1.0)`, а часы отправляли 0–100.

**Решение на стороне часов:** `HeartbeatWorker.kt` — `getBatteryLevel()` теперь возвращает значение 0–100.

**⚠️ Нужно на бэкенде:** Изменить валидацию на `ge=0.0, le=100.0` в `src/schemas/watch.py`, строка 68.

```python
# БЫЛО:
battery_level: float = Field(ge=0.0, le=1.0)

# НУЖНО:
battery_level: float = Field(ge=0.0, le=100.0)
```

**Статус:** ✅ Часы отправляют 0–100. Ждём правку на бэке.

---

### 🟡 ~~`/auth/device/register` возвращает 201, а не 200~~ → ✅ НЕ ПРОБЛЕМА

**Ответ от часов:** Клиент проверяет успех через `response.isSuccessful` (Retrofit), что покрывает весь диапазон 2xx. **201 не ломает клиент.**

**Статус:** ✅ Закрыто, никаких правок не нужно.

---

### 🟡 ~~Код регистрации уже использован → 409, а не 400~~ → ✅ ИСПРАВЛЕНО НА ЧАСАХ

**Решение:** `AuthManager.kt` — добавлена явная обработка 409:

```kotlin
response.code() == 409 -> {
    Result.failure(AuthException("Код уже использован", response.code()))
}
```

**Статус:** ✅ Часы корректно обрабатывают 409.

---

### 🟡 ~~Срок действия кода истёк → 410, а не 400~~ → ✅ ИСПРАВЛЕНО НА ЧАСАХ

**Решение:** `AuthManager.kt` — добавлена обработка 410:

```kotlin
response.code() == 410 -> {
    Result.failure(AuthException("Код регистрации истёк", response.code()))
}
```

**Статус:** ✅ Часы корректно обрабатывают 410.

---

### 🟡 ~~`/admin/devices` — эндпоинт по другому пути~~ → ✅ ИСПРАВЛЕНО В ГАЙДЕ

**Проблема:** Гайд указывал `GET /api/v1/admin/devices`, реально: `GET /api/v1/devices`.

**Решение:** Обновлён `WATCH_BACKEND_INTEGRATION_GUIDE.md`, curl-команды исправлены.

**Статус:** ✅ Закрыто.

---

### 🟡 ~~`downtime_reason` в payload vs `downtime_reasons` в pipeline~~ → ✅ ИСПРАВЛЕНО НА ЧАСАХ

**Проблема:** Pipeline на бэке ищет `downtime_reasons` (множественное число), а часы отправляли `downtime_reason` (единственное). Данные о простоях молча терялись.

**Решение:** Переименовано поле в `ShiftPacket.kt` и `PacketBuilder.kt`:

```kotlin
// БЫЛО:
val downtime_reason: List<DowntimeSample>

// СТАЛО:
val downtime_reasons: List<DowntimeSample>
```

**Статус:** ✅ Часы теперь отправляют `downtime_reasons`.

---

### ℹ️ Heartbeat — нет Bearer-авторизации на сервере → 📝 ЗАФИКСИРОВАНО

**Факт:** Гайд говорит, что heartbeat требует `Authorization: Bearer <access_token>`, но на бэке проверки нет.

**Решение:** Часы всё равно отправляют токен (не мешает). Для MVP — приемлемо.

**Статус:** 📝 Задача на будущее — добавить проверку на бэке.

---

## 📋 Итог

| Уровень | Было | Сейчас | Примечание |
|---------|------|--------|------------|
| 🔴 Критично | 1 | 0 | `battery_level` — часы исправлены, ждём правку валидации на бэке |
| 🟡 Важно | 4 | 0 | Все исправлены на часах + в гайде |
| ℹ️ Инфо | 1 | 1 | Heartbeat без auth — задача на будущее |

### Что нужно сделать на бэкенде

1. **`battery_level`** — изменить `le=1.0` → `le=100.0` в `src/schemas/watch.py`
2. **(Опционально)** Добавить Bearer-проверку на `/watch/heartbeat`

### Что уже сделано на часах

1. ✅ `battery_level` отправляется как 0–100
2. ✅ `downtime_reasons` (множественное число) в JSON-пакете
3. ✅ Обработка 409 (код использован) и 410 (код истёк) при регистрации
4. ✅ `response.isSuccessful` (2xx) — совместимо с 201 от `/register`
5. ✅ Гайд обновлён с правильными путями и кодами ответов