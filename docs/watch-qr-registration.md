# Watch QR Registration — Реализация на стороне часов

## Обзор

Заменили ручную регистрацию часов (хардкод `registrationCode`) на автоматическую через QR-код.

### Было (старый flow)
1. В коде захардкожен `registrationCode = "A5A15D74E0D11F33"`
2. На экране часов показывался усечённый код и кнопка «Зарегистрировать»
3. Часы сами отправляли `POST /auth/device/register` с этим кодом

### Стало (новый flow)
1. Часы генерируют уникальный `device_id` из Android ID устройства (формат `WT-XXXXXXXX`)
2. На экране отображается QR-код с JSON-данными устройства
3. Оператор сканирует QR мобильным приложением
4. Мобилка регистрирует часы на бэкенде и привязывает к сотруднику
5. Часы поллят бэкенд каждые 3 сек → получают `device_secret` → автоматически переходят к работе

---

## Формат QR-кода

QR-код содержит JSON:

```json
{
  "device_id": "WT-0EC22895",
  "model": "sdk_gwear_x86_64",
  "firmware": "Wear OS 36",
  "app_version": "1.0.0"
}
```

| Поле | Описание | Пример |
|------|----------|--------|
| `device_id` | `WT-` + первые 8 символов `ANDROID_ID` (uppercase) | `WT-0EC22895` |
| `model` | `Build.MODEL` устройства | `Galaxy Watch6` |
| `firmware` | `Wear OS` + `Build.VERSION.SDK_INT` | `Wear OS 36` |
| `app_version` | Версия приложения | `1.0.0` |

---

## Поллинг регистрации

После отображения QR-кода часы начинают поллить endpoint:

```
GET /api/v1/auth/device/{device_id}/registration-status
```

**Интервал:** каждые 3 секунды

**Ответ (не зарегистрирован):**
```json
{ "registered": false }
```

**Ответ (зарегистрирован):**
```json
{
  "registered": true,
  "device_secret": "...",
  "server_public_key_pem": "-----BEGIN PUBLIC KEY-----\n...",
  "server_time": "2026-03-17T14:50:00Z"
}
```

При получении `registered: true` + `device_secret`:
1. Credentials сохраняются в `DeviceCredentialsStore`
2. Вызывается `authenticate()` для получения access/refresh токенов
3. Запускается `HeartbeatWorker`
4. UI переключается на `StatusScreen`

> **Важно:** `device_secret` выдаётся бэкендом одноразово — при повторном запросе он не возвращается.

---

## Изменённые файлы

### Новые файлы

| Файл | Описание |
|------|----------|
| `app/.../util/QrCodeGenerator.kt` | Генерация QR bitmap через ZXing |

### Изменённые файлы

| Файл | Изменения |
|------|-----------|
| `gradle/libs.versions.toml` | Добавлен `zxing = "3.5.3"` и `zxing-core` library |
| `app/build.gradle.kts` | Добавлен `implementation(libs.zxing.core)` |
| `app/.../network/model/AuthModels.kt` | Добавлен `RegistrationStatusResponse` |
| `app/.../network/WatchAuthService.kt` | Добавлен `getRegistrationStatus()` (GET) |
| `app/.../network/AuthManager.kt` | Добавлен `pollRegistrationStatus(deviceId)`, сохранён legacy `register()` |
| `app/.../viewmodel/StatusViewModel.kt` | Убран `registrationCode`, добавлен QR payload + поллинг |
| `app/.../ui/RegistrationScreen.kt` | Полная переработка: QR-код + статус ожидания |
| `app/.../presentation/MainActivity.kt` | Обновлён вызов `RegistrationScreen(viewModel)` |

---

## Зависимости от бэкенда

Для полной работы flow бэкенд должен реализовать два endpoint'а:

### 1. `POST /api/v1/auth/device/register-via-mobile`

Вызывается мобильным приложением оператора после сканирования QR.

**Request:**
```json
{
  "device_id": "WT-0EC22895",
  "employee_id": "uuid",
  "site_id": "site-1",
  "model": "sdk_gwear_x86_64",
  "firmware": "Wear OS 36",
  "app_version": "1.0.0"
}
```

**Response:**
```json
{
  "device_id": "WT-0EC22895",
  "status": "registered",
  "binding_id": "uuid"
}
```

### 2. `GET /api/v1/auth/device/{device_id}/registration-status`

Поллится часами каждые 3 сек. Без авторизации.

**Response (до регистрации):**
```json
{ "registered": false }
```

**Response (после регистрации, одноразово с secret):**
```json
{
  "registered": true,
  "device_secret": "generated-secret",
  "server_public_key_pem": "-----BEGIN PUBLIC KEY-----\n...",
  "server_time": "2026-03-17T14:50:00Z"
}
```

---

## Тестирование

### Проверено
- ✅ QR-код генерируется и отображается на эмуляторе Wear OS
- ✅ QR содержит корректный JSON с device_id, model, firmware
- ✅ device_id стабильный для одного устройства (привязан к ANDROID_ID)

### Требует бэкенд
- ⬜ Поллинг получает `registered: true` и credentials
- ⬜ Автоматический переход на StatusScreen после регистрации
- ⬜ Получение токенов после сохранения credentials
