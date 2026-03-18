# Привязка часов к сотруднику — Гайд для мобильного разработчика

> **Base URL**: `https://<server>/api/v1`
> **Формат**: JSON, `Content-Type: application/json`

---

## Зачем нужна привязка

Часы — это **общее оборудование**. Утром Иванов берёт часы W-001, вечером сдаёт, а утром их берёт Петров. Привязка (binding) — это запись о том, **кто именно** носил конкретные часы в конкретную смену.

Без привязки данные с часов привязаны к **устройству**, а не к **человеку**. Если часы перепривязали к другому рабочему, данные прошлой смены могут «уехать» не к тому сотруднику.

### Как сервер определяет сотрудника

При обработке пакета сервер определяет `employee_id` по приоритету:

```
1. packet.operator_id       → если передан явно (высший приоритет)
2. packet.binding_id        → ищет в device_bindings → берёт employee_id
3. device.employee_id       → текущая привязка устройства (fallback, ненадёжно)
```

Поэтому **всегда передавайте `binding_id`** — это единственный надёжный способ.

---

## Два способа привязки

### Способ 1: Ручная привязка через `/bindings/` (основной)

Оператор/мастер через мобильное приложение создаёт привязку перед началом смены, а затем закрывает после окончания.

### Способ 2: Автоматическая привязка через `register-via-mobile` (первая регистрация)

При **первой** регистрации нового устройства через мобильное приложение (`POST /api/v1/auth/device/register-via-mobile`) сервер автоматически:
1. Регистрирует устройство
2. Создаёт привязку к указанному сотруднику
3. Записывает событие в аудит-лог

> ⚠️ Этот способ рассчитан **только на первичную регистрацию**. Для последующих смен используйте ручную привязку через `/bindings/`.

---

## Какие роуты использовать

| Шаг | Метод | Роут | Описание |
|-----|-------|------|----------|
| 0 | `POST` | `/api/v1/auth/device/register-via-mobile` | Первичная регистрация + авто-привязка |
| 0b | `GET` | `/api/v1/auth/device/{device_id}/registration-status` | Проверить статус регистрации (secret) |
| 1 | `POST` | `/api/v1/bindings/` | Привязать часы к рабочему |
| 2 | `POST` | `/api/v1/gateway/packets` | Отправить пакет данных **с `binding_id`** |
| 3 | `PUT` | `/api/v1/bindings/{binding_id}/close` | Закрыть привязку (конец смены) |
| 4 | `GET` | `/api/v1/shifts?employee_id={uuid}` | Получить смены сотрудника |
| 5 | `GET` | `/api/v1/shifts/{id}/metrics` | Метрики смены |
| 6 | `GET` | `/api/v1/shifts/{id}/activity` | Классификация активности |
| 7 | `GET` | `/api/v1/bindings/?employee_id={uuid}` | История привязок |

> ⚠️ **Не используйте** `POST /api/v1/watch/packets` — этот роут для автономных часов и **не поддерживает `binding_id`**.

---

## Шаг 0: Первичная регистрация устройства (только один раз)

Если устройство **ещё не зарегистрировано** в системе, оператор/админ может зарегистрировать его через мобильное приложение.

> **Требует авторизацию**: User JWT (роль `operator` или `admin`).

### `POST /api/v1/auth/device/register-via-mobile`

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
| `device_id` | `string` (1-64) | ✅ | ID устройства (часов) |
| `employee_id` | `UUID` | ✅ | ID сотрудника |
| `site_id` | `string` (1-64) | ✅ | ID площадки |
| `model` | `string` (max 64) | ❌ | Модель устройства |
| `firmware` | `string` (max 64) | ❌ | Версия прошивки |
| `app_version` | `string` (max 32) | ❌ | Версия приложения |

### Ответ `201 Created`

```json
{
  "device_id": "WATCH-001",
  "status": "registered",
  "binding_id": null
}
```

### Ошибки

| Код | Причина |
|-----|---------|
| `401` | Не авторизован |
| `403` | Нет прав (не `operator`/`admin`) |
| `404` | Сотрудник не найден |
| `409` | Устройство уже зарегистрировано |

### Получение секрета: `GET /api/v1/auth/device/{device_id}/registration-status`

После регистрации через мобильное приложение часам нужно получить свой `device_secret` и `server_public_key_pem`. Этот эндпоинт **возвращает секрет только один раз** — при первом вызове.

```json
{
  "registered": true,
  "device_secret": "generated_secret_string",
  "server_public_key_pem": "-----BEGIN PUBLIC KEY-----\n...",
  "server_time": "2026-03-17T09:00:00Z"
}
```

> ⚠️ **Важно**: `device_secret` выдаётся **однократно**. После первого запроса поле `device_secret_plain` обнуляется на сервере. Сохраните секрет в SecureStorage!

---

## Шаг 1: Создать привязку (начало смены)

Мастер/оператор привязывает часы к рабочему через мобильное приложение.

### Запрос

```
POST /api/v1/bindings/
```

```json
{
  "device_id": "WATCH-001",
  "employee_id": "550e8400-e29b-41d4-a716-446655440000",
  "site_id": "SITE-01",
  "shift_date": "2026-03-17",
  "shift_type": "day",
  "bound_by": "uuid-мастера"
}
```

| Поле | Тип | Обязательное | Описание |
|------|-----|:---:|----------|
| `device_id` | `string` (max 64) | ✅ | ID устройства (часов) |
| `employee_id` | `UUID` | ✅ | ID сотрудника |
| `site_id` | `string` (max 64) | ✅ | ID площадки/объекта |
| `shift_date` | `date` (YYYY-MM-DD) | ✅ | Дата смены |
| `shift_type` | `string` (max 16) | ❌ | Тип смены: `"day"` (default) или `"night"` |
| `bound_by` | `UUID` | ❌ | Кто создал привязку |

### Что делает сервер

1. Проверяет, что устройство с `device_id` **существует** в БД
2. Проверяет, что сотрудник с `employee_id` **существует** и **не удалён** (`is_deleted = false`)
3. Проверяет, что у этого устройства **нет активной привязки** (status = `active`)
4. Создаёт запись `DeviceBinding` со статусом `active`
5. Обновляет устройство: `last_bound_employee_id`, `last_bound_at`, инкрементирует `total_bindings_count`

### Ответ `201 Created`

```json
{
  "id": "a1b2c3d4-5678-90ab-cdef-1234567890ab",
  "device_id": "WATCH-001",
  "employee_id": "550e8400-e29b-41d4-a716-446655440000",
  "site_id": "SITE-01",
  "shift_date": "2026-03-17",
  "shift_type": "day",
  "bound_at": "2026-03-17T07:00:00Z",
  "bound_by": "uuid-мастера",
  "unbound_at": null,
  "unbound_by": null,
  "status": "active",
  "created_at": "2026-03-17T07:00:00Z",
  "updated_at": "2026-03-17T07:00:00Z"
}
```

### Ошибки

| Код | Причина |
|-----|---------|
| `404` | Устройство (`device_id`) не найдено, или сотрудник (`employee_id`) не найден/удалён |
| `409` | У устройства уже есть активная привязка — сначала закройте её |

### Kotlin

```kotlin
val binding = api.createBinding(
    BindingCreateRequest(
        deviceId = "WATCH-001",
        employeeId = employeeUUID,
        siteId = "SITE-01",
        shiftDate = LocalDate.now(),
        shiftType = "day",
        boundBy = masterUUID
    )
)

// ⚠️ СОХРАНИТЬ! Нужен для всех пакетов этой смены
val activeBindingId: String = binding.id
```

---

## Шаг 2: Отправлять пакеты с binding_id

Каждый пакет данных отправляется через **gateway** роут с указанием `binding_id`.

> **Полный процесс шифрования** описан в [MOBILE_API_GUIDE.md](MOBILE_API_GUIDE.md#8-watch--отправка-данных-с-часов) — секции «Шифрование и подготовка данных пакета».

### Запрос

```
POST /api/v1/gateway/packets
Headers:
  Idempotency-Key: <packet_id>
  Content-Type: application/json
```

```json
{
  "packet_id": "uuid-пакета",
  "device_id": "WATCH-001",
  "shift_start_ts": 1710658800000,
  "shift_end_ts": 1710702000000,
  "schema_version": 1,
  "payload_enc": "base64(зашифрованные данные)",
  "payload_key_enc": "base64(зашифрованный AES-ключ)",
  "iv": "base64(12-байтный IV)",
  "payload_hash": "sha256hex(исходный JSON)",
  "payload_size_bytes": 45000,
  "operator_id": null,
  "site_id": "SITE-01",
  "binding_id": "a1b2c3d4-5678-90ab-cdef-1234567890ab",
  "gateway_device_info": {
    "model": "Samsung Galaxy S24",
    "os_version": "Android 15",
    "app_version": "1.0.0"
  }
}
```

| Поле | Тип | Обязательное | Описание |
|------|-----|:---:|----------|
| `packet_id` | `string` (36-64) | ✅ | UUID v4, уникальный ID пакета |
| `device_id` | `string` (1-64) | ✅ | ID устройства |
| `shift_start_ts` | `int` | ✅ | Начало смены, Unix timestamp (сек или мс) |
| `shift_end_ts` | `int` | ✅ | Конец смены, должен быть > `shift_start_ts` |
| `schema_version` | `int` (≥ 1) | ✅ | Версия формата данных |
| `payload_enc` | `string` (min 1) | ✅ | Base64-закодированный зашифрованный payload |
| `payload_key_enc` | `string` (min 1) | ✅ | Base64-закодированный AES-ключ, зашифрованный RSA |
| `iv` | `string` (1-64) | ✅ | Base64-закодированный IV (12 байт в сыром виде) |
| `payload_hash` | `string` (1-128) | ✅ | SHA-256 хеш исходного payload |
| `payload_size_bytes` | `int \| null` (≥ 0) | ❌ | Размер исходного payload в байтах |
| `binding_id` | `string` (max 64) | ⚠️ **Настоятельно рекомендуется** | ID привязки из шага 1 |
| `operator_id` | `UUID` | ❌ | ID оператора (опционально) |
| `site_id` | `string` (max 64) | ❌ | ID площадки |
| `gateway_device_info` | `object` | ❌ | Информация о телефоне-шлюзе |

**Поля `gateway_device_info`:**

| Поле | Тип | Описание |
|------|-----|----------|
| `model` | `string \| null` (max 128) | Модель телефона |
| `os_version` | `string \| null` (max 64) | Версия ОС |
| `app_version` | `string \| null` (max 64) | Версия приложения |

> ⚠️ **`binding_id` — ключевое поле!** Именно по нему сервер определяет, какому сотруднику принадлежат данные. Без него данные привяжутся к текущему владельцу часов, который может быть уже другим.

### Ответ `202 Accepted`

```json
{
  "packet_id": "uuid-пакета",
  "status": "accepted",
  "received_at": "2026-03-17T09:00:00Z",
  "server_time": "2026-03-17T09:00:00Z"
}
```

> **Важно**: пакет обрабатывается **асинхронно** в background task. Pipeline обработки:
> `accepted` → `decrypting` → `parsing` → `processing` → `processed` (или `error`)

### Что делает сервер при получении

1. Валидирует `Idempotency-Key` заголовок (обязательный, должен совпадать с `packet_id`)
2. Если передан `x-device-id` — проверяет совпадение с `device_id` в body
3. Ищет устройство по `device_id` → проверяет что оно `active`
4. Если `binding_id` указан → ищет в `device_bindings` → берёт `employee_id`
5. Создаёт запись о пакете со статусом `accepted`
6. Запускает асинхронный pipeline обработки (расшифровка → парсинг → сохранение сенсоров)

### Kotlin

```kotlin
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
        bindingId = activeBindingId,  // ← ИЗ ШАГА 1
        gatewayDeviceInfo = GatewayDeviceInfo(
            model = Build.MODEL,
            osVersion = "Android ${Build.VERSION.RELEASE}",
            appVersion = BuildConfig.VERSION_NAME
        )
    )
)
```

---

## Шаг 3: Закрыть привязку (конец смены)

Мастер/оператор отвязывает часы от рабочего.

### Запрос

```
PUT /api/v1/bindings/{binding_id}/close
```

```json
{
  "unbound_by": "uuid-мастера"
}
```

| Поле | Тип | Обязательное | Описание |
|------|-----|:---:|----------|
| `unbound_by` | `UUID` | ❌ | Кто отвязал |

### Что делает сервер

1. Ищет привязку по `binding_id`
2. Проверяет, что статус = `active` (нельзя закрыть уже закрытую)
3. Устанавливает `status = "closed"`, `unbound_at = текущее время`, `unbound_by = переданный UUID`

### Ответ `200 OK`

```json
{
  "id": "a1b2c3d4-...",
  "device_id": "WATCH-001",
  "employee_id": "550e8400-...",
  "site_id": "SITE-01",
  "shift_date": "2026-03-17",
  "shift_type": "day",
  "bound_at": "2026-03-17T07:00:00Z",
  "bound_by": "uuid-мастера",
  "unbound_at": "2026-03-17T19:00:00Z",
  "unbound_by": "uuid-мастера",
  "status": "closed",
  "created_at": "...",
  "updated_at": "..."
}
```

### Ошибки

| Код | Причина |
|-----|---------|
| `404` | Привязка не найдена |
| `409` | Привязка уже закрыта (`status != "active"`) |

### Kotlin

```kotlin
api.closeBinding(
    bindingId = activeBindingId,
    body = BindingCloseRequest(unboundBy = masterUUID)
)
```

---

## Шаг 4: Получить данные сотрудника

### Смены сотрудника

```
GET /api/v1/shifts?employee_id={uuid}&page=1&page_size=20
```

Дополнительные фильтры: `device_id`, `date_from`, `date_to`.

### Детали смены

```
GET /api/v1/shifts/{shift_id}
```

### Сенсорные данные за смену

```
GET /api/v1/shifts/{shift_id}/data/{data_type}
```

Типы: `accel`, `gyro`, `baro`, `mag`, `heart_rate`, `ble`, `wear`, `battery`, `downtime`.

Дополнительные фильтры: `from_ts`, `to_ts` (временной диапазон в ms), `limit` (1-100000, default=10000), `offset`, `format` (`csv` для экспорта).

### Метрики смены

```
GET /api/v1/shifts/{shift_id}/metrics
```

Возвращает: продуктивность, время на площадке, среднее/медианное время реакции, распределение по классам активности, wear compliance, среднее ЧСС, количество аномалий, data quality score.

### Активность за смену

```
GET /api/v1/shifts/{shift_id}/activity
```

Классификация активности (A1=активная работа, A2=средняя, B1=ходьба, B2=медленное перемещение, V1=простой в рабочей зоне, V2=простой в нерабочей зоне, V3=перерыв, V4=неизвестно).

### Зоны за смену

```
GET /api/v1/shifts/{shift_id}/zones
```

Зоны, посещённые за смену: детальные визиты и сводка по зонам.

### Маршрут за смену

```
GET /api/v1/shifts/{shift_id}/route
```

Хронологический маршрут перемещения между зонами.

### Время реакции

```
GET /api/v1/shifts/{shift_id}/reaction-times
```

За сколько начал работать после входа в зону.

### Простои и причины

```
GET /api/v1/shifts/{shift_id}/downtimes
```

Интервалы простоев с причинами и сводкой.

### Аномалии за смену

```
GET /api/v1/shifts/{shift_id}/anomalies
```

### Аномалии по сотруднику

```
GET /api/v1/anomalies?employee_id={uuid}
```

### Аналитика по сотруднику

```
GET /api/v1/analytics/employee/{employee_id}/summary
```

### Kotlin

```kotlin
// Все смены рабочего
val shifts = api.listShifts(employeeId = employeeUUID)

// Метрики за конкретную смену
val metrics = api.getShiftMetrics(shiftId = shifts.items[0].id)

// Акселерометр
val accel = api.getShiftData(shiftId = shiftId, dataType = "accel")

// Классификация активности
val activity = api.getShiftActivity(shiftId = shiftId)

// Зоны за смену
val zones = api.getShiftZones(shiftId = shiftId)

// Маршрут перемещения
val route = api.getShiftRoute(shiftId = shiftId)

// Время реакции
val reactions = api.getShiftReactionTimes(shiftId = shiftId)

// Простои
val downtimes = api.getShiftDowntimes(shiftId = shiftId)

// Аномалии смены
val shiftAnomalies = api.getShiftAnomalies(shiftId = shiftId)

// Сводка по сотруднику
val summary = api.getEmployeeSummary(employeeId = employeeUUID)
```

---

## Шаг 5: История привязок

### По сотруднику (какие часы он носил)

```
GET /api/v1/bindings/?employee_id={uuid}&page=1&page_size=20
```

### По устройству (кто носил эти часы)

```
GET /api/v1/bindings/?device_id=WATCH-001&page=1&page_size=20
```

### Дополнительные фильтры

| Параметр | Тип | Описание |
|----------|-----|----------|
| `shift_date` | `string` (YYYY-MM-DD) | Дата смены |
| `site_id` | `string` | Площадка |
| `page` | `int` (≥ 1) | Страница (default=1) |
| `page_size` | `int` (1-100) | Размер страницы (default=20) |

---

## Визуальная схема

```
┌────────────────────────┐
│  ПЕРВАЯ РЕГИСТРАЦИЯ    │
│  (один раз!)           │
└──────────┬─────────────┘
           │
           ▼
POST /auth/device/register-via-mobile
→ Создано: устройство + привязка + аудит-лог
→ GET /auth/device/{id}/registration-status
  → device_secret + server_public_key_pem (одноразово!)

           │
           ▼

┌──────────────┐                               ┌─────────────────────┐
│  Мобильное   │     POST /bindings/           │  Создать привязку   │
│  приложение  │ ────────────────────────────→  │  status=active      │
│              │     ← binding_id              │  employee=Иванов    │
│              │                                └─────────┬───────────┘
│              │                                          │
│              │     POST /gateway/packets                ▼
│              │     + binding_id               ┌─────────────────────┐
│              │ ────────────────────────────→  │  Загрузить пакет    │
│              │                                │  binding_id → Иванов│
│              │     Async background task:     │  Pipeline:          │
│              │     accepted → decrypting →    │  accepted → parsed  │
│              │     parsing → processing →     │  → processed        │
│              │     processed (или error)      └─────────┬───────────┘
│              │                                          │
│              │     PUT /bindings/{id}/close              ▼
│              │ ────────────────────────────→  ┌─────────────────────┐
│              │                                │  Закрыть привязку   │
│              │                                │  status=closed      │
│              │                                └─────────┬───────────┘
│              │                                          │
│              │     GET /shifts?employee_id=              ▼
│              │ ────────────────────────────→  ┌─────────────────────┐
│              │     ← смены, метрики          │  Данные СОТРУДНИКА  │
│              │                                │  (не устройства!)   │
└──────────────┘                                └─────────────────────┘
```

---

## Что хранить на клиенте

| Данные | Когда сохранять | Зачем |
|--------|----------------|-------|
| `binding_id` | После `POST /bindings/` | Передавать в **каждый** пакет |
| `device_id` | После регистрации | Идентификация устройства |
| `employee_id` | При выборе рабочего | Фильтрация данных |
| `site_id` | При выборе площадки | Контекст пакета |
| `device_secret` | После регистрации (одноразово!) | Получение JWT-токенов устройства |
| `server_public_key_pem` | После регистрации | Шифрование payload (RSA-OAEP-SHA256) |

---

## Важные моменты

1. **У устройства может быть только одна активная привязка.** Перед созданием новой — закройте предыдущую (сервер выдаст `409`).

2. **`binding_id` нужно хранить до отправки всех пакетов** данной смены, даже если привязка уже закрыта. Пакеты могут отправляться с задержкой (нет интернета).

3. **Не используйте `/watch/packets`** для мобильного приложения — этот роут не поддерживает `binding_id`, `operator_id`, `site_id`, `gateway_device_info`.

4. **Данные запрашивайте по `employee_id`**, а не по `device_id`. Устройство может быть у разных сотрудников, а `employee_id` гарантирует правильную выборку.

5. **`device_secret` выдаётся однократно**. При регистрации через `register-via-mobile` → `registration-status` — секрет отдаётся один раз и стирается на сервере.

6. **Пакеты обрабатываются асинхронно**. Статус `accepted` в ответе означает, что пакет принят, но ещё не обработан. Используйте `GET /watch/packets/{packet_id}` (с заголовком `x-device-id`) для проверки статуса.

7. **Сотрудник проверяется на удалённость**: при создании привязки сервер не только ищет сотрудника по `employee_id`, но и проверяет `is_deleted`. Удалённого сотрудника привязать нельзя.

---

## Retrofit-интерфейс

```kotlin
interface BindingApi {
    // --- Регистрация устройства через мобильное ---
    @POST("api/v1/auth/device/register-via-mobile")
    suspend fun registerDeviceViaMobile(
        @Body request: MobileRegisterRequest
    ): MobileRegisterResponse

    @GET("api/v1/auth/device/{deviceId}/registration-status")
    suspend fun getRegistrationStatus(
        @Path("deviceId") deviceId: String
    ): RegistrationStatusResponse

    // --- Привязки ---
    @POST("api/v1/bindings/")
    suspend fun createBinding(
        @Body request: BindingCreateRequest
    ): BindingResponse

    @PUT("api/v1/bindings/{bindingId}/close")
    suspend fun closeBinding(
        @Path("bindingId") bindingId: String,
        @Body request: BindingCloseRequest
    ): BindingResponse

    @GET("api/v1/bindings/")
    suspend fun listBindings(
        @Query("employee_id") employeeId: String? = null,
        @Query("device_id") deviceId: String? = null,
        @Query("shift_date") shiftDate: String? = null,
        @Query("site_id") siteId: String? = null,
        @Query("page") page: Int = 1,
        @Query("page_size") pageSize: Int = 20
    ): BindingListResponse

    // --- Отправка пакетов (Gateway) ---
    @POST("api/v1/gateway/packets")
    suspend fun submitPacket(
        @Header("Idempotency-Key") idempotencyKey: String,
        @Header("x-device-id") xDeviceId: String? = null,
        @Body request: GatewayPacketSubmitRequest
    ): WatchPacketSubmitResponse

    // --- Проверка статуса пакета ---
    @GET("api/v1/watch/packets/{packetId}")
    suspend fun getPacketStatus(
        @Path("packetId") packetId: String,
        @Header("x-device-id") xDeviceId: String
    ): PacketStatusResponse

    // --- Смены ---
    @GET("api/v1/shifts")
    suspend fun listShifts(
        @Query("employee_id") employeeId: String? = null,
        @Query("device_id") deviceId: String? = null,
        @Query("date_from") dateFrom: String? = null,
        @Query("date_to") dateTo: String? = null,
        @Query("page") page: Int = 1,
        @Query("page_size") pageSize: Int = 20
    ): ShiftListResponse

    @GET("api/v1/shifts/{shiftId}")
    suspend fun getShift(
        @Path("shiftId") shiftId: String
    ): ShiftListItem

    @GET("api/v1/shifts/{shiftId}/metrics")
    suspend fun getShiftMetrics(
        @Path("shiftId") shiftId: String
    ): ShiftMetricsResponse

    @GET("api/v1/shifts/{shiftId}/activity")
    suspend fun getShiftActivity(
        @Path("shiftId") shiftId: String
    ): ShiftActivityResponse

    @GET("api/v1/shifts/{shiftId}/zones")
    suspend fun getShiftZones(
        @Path("shiftId") shiftId: String
    ): ShiftZonesResponse

    @GET("api/v1/shifts/{shiftId}/route")
    suspend fun getShiftRoute(
        @Path("shiftId") shiftId: String
    ): ShiftRouteResponse

    @GET("api/v1/shifts/{shiftId}/reaction-times")
    suspend fun getShiftReactionTimes(
        @Path("shiftId") shiftId: String
    ): ShiftReactionTimesResponse

    @GET("api/v1/shifts/{shiftId}/downtimes")
    suspend fun getShiftDowntimes(
        @Path("shiftId") shiftId: String
    ): ShiftDowntimesResponse

    @GET("api/v1/shifts/{shiftId}/data/{dataType}")
    suspend fun getShiftData(
        @Path("shiftId") shiftId: String,
        @Path("dataType") dataType: String,
        @Query("from_ts") fromTs: Long? = null,
        @Query("to_ts") toTs: Long? = null,
        @Query("limit") limit: Int = 10000,
        @Query("offset") offset: Int = 0,
        @Query("format") format: String? = null
    ): ShiftDataResponse

    @GET("api/v1/shifts/{shiftId}/anomalies")
    suspend fun getShiftAnomalies(
        @Path("shiftId") shiftId: String
    ): ShiftAnomaliesResponse

    // --- Аналитика ---
    @GET("api/v1/analytics/employee/{employeeId}/summary")
    suspend fun getEmployeeSummary(
        @Path("employeeId") employeeId: String
    ): EmployeeSummaryResponse

    @GET("api/v1/anomalies")
    suspend fun listAnomalies(
        @Query("employee_id") employeeId: String? = null,
        @Query("page") page: Int = 1,
        @Query("page_size") pageSize: Int = 50
    ): AnomalyListResponse

    // --- Назначение причин простоев ---
    @POST("api/v1/shifts/{shiftId}/downtimes/{intervalId}/assign")
    suspend fun assignDowntimeReason(
        @Path("shiftId") shiftId: String,
        @Path("intervalId") intervalId: String,
        @Body request: ShiftDowntimeAssignRequest
    ): ShiftDowntimeAssignResponse

    @PUT("api/v1/shifts/{shiftId}/downtimes/{intervalId}/assign")
    suspend fun updateDowntimeReason(
        @Path("shiftId") shiftId: String,
        @Path("intervalId") intervalId: String,
        @Body request: ShiftDowntimeAssignRequest
    ): ShiftDowntimeAssignResponse
}
```
