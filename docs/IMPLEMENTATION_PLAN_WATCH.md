# План реализации приложения для смарт-часов (Samsung Galaxy Watch 8)

Дата: 2026-02-03  
Основание: `ТЗ_Смарт-часы_Вариант 1.md`

## 1. Контекст и цель
Приложение на смарт-часах предназначено для непрерывного сбора данных активности сотрудника и фиксации нахождения в BLE-зонах с последующей безопасной выгрузкой “пакета смены” в систему. Логика интерпретации/аналитики выполняется на сервере; на часах сохраняем “сырые” данные, события ношения, BLE-события и служебные метрики.

Поддерживаемые режимы синхронизации (конфигурируемые):
- `DIRECT` — отправка пакета смены напрямую с часов на сервер по HTTPS.
- `GATEWAY` — синхронизация через мобильное приложение оператора (шлюз) по Bluetooth в начале и конце смены. Само мобильное приложение **не разрабатывается в этом проекте**, но интерфейсы/протокол с часами фиксируются здесь.

## 1.1. Отклонения и расширения относительно ТЗ (Вариант 1)
Этот блок фиксирует различия относительно `ТЗ_Смарт-часы_Вариант 1.md`, чтобы у команды разработки не было противоречий между исходным ТЗ и фактической реализацией.

Подтвержденные расширения (не противоречат ТЗ, но выходят за его рамки):
- Режим `DIRECT`: прямой HTTPS‑аплоад с часов на сервер без мобильного шлюза. Это дополнительный режим; базовый сценарий ТЗ (`GATEWAY`) остается поддерживаемым.

Подтверждено: заказчик согласовал наличие **опционального** шлюза и **опциональной** прямой отправки.

## 2. Целевая платформа и актуальная информация
Целевая модель по запросу: Samsung Galaxy Watch 8 (и Watch 8 Classic). Samsung официально сообщает о Galaxy Watch 8 series и One UI 8 Watch, где интерфейс ориентирован на “glanceable” сценарии на маленьком экране, включая Multi-Info Tiles и Now Bar.citeturn1search2  
Требование ТЗ о совместимости с Watch4+ сохраняется: совместимость подтверждается матрицей сенсоров и стабильности BLE.
Примечание: в таблице 6 ТЗ упоминается “Galaxy Watch 7” — требуется подтверждение идентичности сенсоров/метрик для Watch 8.

## 3. Принципы Wear OS и UX-ограничения
Wear OS‑принципы требуют задач “за секунды”, glanceable поверхностей (tiles/complications) и устойчивой работы оффлайн.citeturn1search7  
Рекомендации Wear OS app quality включают актуальные требования по target SDK (Android 13/14) и обязательное тестирование на Wear OS 3+ устройствах/эмуляторах.citeturn2search5

## 4. Ограничения сенсоров и частоты
На Android 9+ фоновые приложения не получают события от continuous/on‑change сенсоров; сбор следует вести в foreground service.citeturn1search0  
Для motion‑сенсоров действует rate‑limit (до 200 Гц при `registerListener`, около 50 Гц в `SensorDirectChannel`); для более высоких частот требуется `HIGH_SAMPLING_RATE_SENSORS`.citeturn1search6

## 5. BLE‑сканирование
Непрерывное BLE‑сканирование быстро разряжает батарею, поэтому нужен ограниченный по времени скан и остановка после окна скана.citeturn2search6

## 6. Архитектура (слои и компоненты)
Архитектурный стиль: модульная структура внутри одного Wear OS приложения (packages) с четким разделением “сбор → хранение → упаковка → передача”.

Компоненты:
- `SensorCollector` – подписка на датчики (акселерометр, гироскоп, барометр, магнитометр, пульс), агрегация, временные метки.
- `WearStateTracker` – события wear/off‑wrist (контакт с кожей).
- `BleScanner` – периодический скан BLE‑меток, фиксация id + timestamp + (опц.) RSSI.
- `LocalStore` – надежное хранение “сырых” событий и служебных данных.
- `PacketBuilder` – сбор “пакета смены” по периоду, версия формата, контроль целостности.
- `Crypto` – шифрование данных на часах, подготовка к выгрузке.
- `TransportManager` – выбор режима `DIRECT`/`GATEWAY`.
- `NetworkUploader` – защищенная отправка “пакета смены” напрямую на сервер по HTTPS с очередью, ретраями и подтверждением (`DIRECT`).
- `GatewaySyncService` – BLE‑синхронизация с приложением оператора в начале/конце смены (`GATEWAY`).
- `UI` – минимальные экраны статуса, состояния смены, ошибок; опционально экран причины простоя.

## 7. Поток данных
Общий:
1. Старт смены: часы активируют сбор в foreground service.
2. Сбор сенсоров + BLE‑событий + wear‑событий с записью в `LocalStore`.
3. Конец смены: часы формируют “пакет смены” за период.

Режим `DIRECT`:
1. Отправка по HTTPS напрямую на сервер; ожидание подтверждения.
2. После подтверждения пакет помечается “выгружен”, хранится до политики очистки.
3. При отсутствии связи пакет остается в очереди на часах до успешной отправки.

Режим `GATEWAY`:
1. В начале смены часы синхронизируются с мобильным приложением оператора (получают контекст смены/сотрудника).
2. В конце смены мобильное приложение считывает “пакет смены” по BLE и отправляет на сервер.
3. Часы получают подтверждение успешной выгрузки через шлюз.

## 8. Модель данных (минимальный набор)
Сущности:
- `DeviceInfo`: device_id, model, firmware, timezone.
- `SensorSample`: type, ts, x/y/z, quality.
- `HeartRateSample`: ts, bpm, confidence.
- `BleEvent`: ts, beacon_id, rssi.
- `WearEvent`: ts, state (on/off‑wrist).
- `BatteryEvent`: ts, level.
- `DowntimeReason` (опц.): ts, reason_id, zone_id.

Формат “пакета смены”:
- версия формата;
- период (start_ts, end_ts);
- контрольная сумма;
- сериализованные списки событий.

## 9. Протокол API и формат передачи
Ниже фиксируем минимально достаточные протоколы для двух режимов: прямой (`DIRECT`) и через шлюз (`GATEWAY`).

### 9.1. Аутентификация и идентификация устройства (DIRECT)
Вариант по умолчанию (MVP):
1. `device_id` фиксируется на часах при первичной регистрации устройства (выдается сервером).
2. `device_secret` (или refresh‑token) хранится в защищенном хранилище (Android Keystore).
3. Каждая отправка содержит заголовок `Authorization: Bearer <access_token>`; `access_token` обновляется по `device_secret`.

Вариант усиленный (после MVP):
1. mTLS (сертификат устройства).
2. Серверная ротация ключей и отзыв устройств.

### 9.2. Шифрование пакета (требование: расшифровка только на сервере)
Используем гибридную схему (envelope):
1. На часах генерируется случайный `data_key` (AES‑256‑GCM) на каждый пакет.
2. Контент пакета шифруется `data_key`.
3. `data_key` шифруется публичным ключом сервера (RSA/ECDH) и передается вместе с пакетом.
4. Сервер расшифровывает `data_key`, затем контент.

### 9.3. Эндпоинты (DIRECT)
1. `POST /api/v1/watch/packets`
   - Заголовки:
     - `Authorization: Bearer <token>`
     - `Idempotency-Key: <uuid>`
     - `Content-Encoding: gzip` (опц.)
   - Тело (JSON или бинарный контейнер):
     - `packet_id` (uuid)
     - `device_id`
     - `shift_start_ts`, `shift_end_ts`
     - `schema_version`
     - `payload_enc` (base64 или бинарный блок)
     - `payload_key_enc` (base64)
     - `payload_hash` (sha256 от расшифрованных данных)
2. `GET /api/v1/watch/packets/{packet_id}`
   - Возвращает статус: `accepted | processing | processed | error`
3. `POST /api/v1/watch/heartbeat` (опц.)
   - Для контроля “живости” устройств и синхронизации времени.

### 9.4. Ответы сервера (DIRECT)
1. `202 Accepted`:
   - `{ "packet_id": "...", "status": "accepted", "server_time": "..." }`
2. `409 Conflict`:
   - Идемпотентный повтор (пакет уже принят).
3. `400/422`:
   - Ошибка валидации формата.
4. `401/403`:
   - Ошибка авторизации.
5. `5xx`:
   - Ошибка сервера, требуется повтор.

### 9.5. Идемпотентность (DIRECT)
1. `packet_id` и `Idempotency-Key` обязаны быть одинаковыми при повторной отправке.
2. Сервер хранит ключи идемпотентности минимум 30 дней.
3. При повторе сервер всегда возвращает один и тот же результат для этого ключа.

### 9.6. JSON‑схема пакета (plaintext внутри `payload_enc`)
Это структура после расшифровки `payload_enc` на сервере. Формат можно хранить в JSON (gzip) или CBOR/Protobuf. Для MVP — JSON.

```json
{
  "schema_version": 1,
  "packet_id": "uuid",
  "device": {
    "device_id": "string",
    "model": "string",
    "fw": "string",
    "app_version": "string",
    "tz": "Europe/Moscow"
  },
  "shift": {
    "start_ts_ms": 1700000000000,
    "end_ts_ms": 1700003600000
  },
  "time_sync": {
    "server_time_offset_ms": 1200,
    "server_time_ms": 1700000001200
  },
  "samples": {
    "accel": [
      { "ts_ms": 1700000000100, "x": 0.01, "y": 9.80, "z": 0.02, "quality": 1 }
    ],
    "gyro": [
      { "ts_ms": 1700000000100, "x": 0.001, "y": 0.002, "z": 0.003, "quality": 1 }
    ],
    "baro": [
      { "ts_ms": 1700000000100, "hpa": 1007.2 }
    ],
    "mag": [
      { "ts_ms": 1700000000100, "x": 12.3, "y": -5.2, "z": 44.1 }
    ],
    "hr": [
      { "ts_ms": 1700000000100, "bpm": 72, "confidence": 0.85 }
    ],
    "ble": [
      { "ts_ms": 1700000000150, "beacon_id": "abc123", "rssi": -68 }
    ],
    "wear": [
      { "ts_ms": 1700000000200, "state": "on" }
    ],
    "battery": [
      { "ts_ms": 1700000000300, "level": 0.72 }
    ],
    "downtime_reason": [
      { "ts_ms": 1700001000000, "reason_id": "wait_tools", "zone_id": "Z1" }
    ]
  },
  "meta": {
    "created_ts_ms": 1700003601000,
    "seq": 42,
    "upload_attempt": 3
  }
}
```

Типы и единицы:
- Все `*_ts_ms`: Unix epoch, миллисекунды, UTC.
- `accel`: м/с², `gyro`: рад/с, `baro`: hPa, `mag`: µT, `hr.bpm`: уд/мин.
- `quality`: 0..1 или 0/1 (настройка), фиксируется в контракте.

### 9.7. Протокол BLE‑синхронизации (GATEWAY, минимальный контракт)
Цель: обеспечить обмен “контекстом смены” в начале и “пакетом смены” в конце.

Начало смены (Gateway → Watch):
- Передать `shift_id`, `employee_id`, `site_id`, `start_ts_ms`, `planned_end_ts_ms`.
- Передать `mode = GATEWAY`, чтобы часы переключились в соответствующий режим.
- Ответ часов: `OK` + подтверждение записи контекста.

Конец смены (Watch → Gateway):
- Передать `packet_id`, `schema_version`, `payload_enc`, `payload_key_enc`, `payload_hash`.
- Поддержать чанки (например, 20–200 КБ на пакет, в зависимости от BLE MTU).
- Ответ шлюза: `ACK` после успешного приема и отправки на сервер.

Идемпотентность в GATEWAY:
- `packet_id` используется для повторов.
- При повторе шлюз возвращает `ACK` без повторной отправки, если сервер уже принял пакет.
## 10. Очередь, ретраи и оффлайн‑поведение
1. Пакет смены кладется в локальную очередь на часах до подтвержденного `202 Accepted`.
2. При отсутствии сети:
   - статус “в очереди” отображается на часах;
   - повторная отправка по экспоненциальному backoff (например: 1, 2, 5, 10, 30, 60 мин).
3. При `401/403` ретраи прекращаются до ручной ре‑аутентификации устройства.
4. При `400/422` пакет помечается ошибкой и сохраняется для диагностики.
5. Очередь и метаданные должны переживать перезапуск часов.

Для `GATEWAY`:
- если шлюз недоступен — часы сохраняют пакет до следующей синхронизации;
- повторы передачи по BLE разрешены до подтверждения `ACK`.

## 11. Синхронизация времени
1. В ответе `202 Accepted` сервер возвращает `server_time`.
2. На часах хранится смещение `server_time_offset`.
3. Все метки времени в пакете дополняются исходным `device_time` и `server_time_offset`.

## 12. Чек‑лист для backend (обязателен для прямой выгрузки)
1. Эндпоинт `POST /api/v1/watch/packets` принимает зашифрованный payload, валидирует схему, сохраняет и ставит в обработку.
2. Идемпотентность по `packet_id` + `Idempotency-Key`, TTL хранения ключей ≥ 30 дней.
3. Дешифрование “payload_enc” серверным ключом, проверка `payload_hash`.
4. Хранение “сырых” событий отдельно от агрегатов; независимая обработка.
5. Логи приема пакетов: время, размер, device_id, статус, причина ошибки.
6. Отчетность по ошибкам (4xx/5xx) и мониторинг очередей.
7. API выдачи `device_id`/`device_secret` и отзыв/ротация ключей.
8. Сервер возвращает `server_time` и статус для синхронизации часов (DIRECT).

## 13. Разрешения и требования Wear OS
Bluetooth сканирование:
- Для Android 12+ требуется `BLUETOOTH_SCAN`; при подключениях к устройствам также `BLUETOOTH_CONNECT`.citeturn0search1turn0search3
- Для Android 11 и ниже для BLE‑скана нужны `BLUETOOTH_ADMIN` и локационные разрешения. `BluetoothLeScanner` указывает, что для результатов сканирования требуется `ACCESS_FINE_LOCATION` (Android Q+), а для S+ — `BLUETOOTH_SCAN`; есть флаг `usesPermissionFlags="neverForLocation"` если приложение гарантирует, что не извлекает местоположение.citeturn1search3
- На Android 11+ сканирование Bluetooth запрещено без включенной системной настройки геолокации и выданного разрешения.citeturn1search5

Сенсоры тела (если используем HR):
- Для чтения пульса требуется `BODY_SENSORS`.citeturn1search2
- Для фонового доступа к пульсу на Android 13+ требуется `BODY_SENSORS_BACKGROUND` (restricted/allowlist).citeturn1search1turn1search2
- Для Wear OS 6 (API 36) меняются permissions на `android.permission.health.*` — учитывать при таргете.citeturn1search0

Foreground service:
- Непрерывный сбор сенсоров должен выполняться в foreground service, который обязан показывать постоянное уведомление.citeturn0search7turn0search2
- Для уведомлений на Android 13+ требуется `POST_NOTIFICATIONS`, но запуск foreground service не требует этого разрешения.citeturn0search6

Wear OS UX‑разрешения:
- Разрешения запрашиваем в контексте, пользователю показываются диалоги последовательно.citeturn1search4

## 14. Manifest и Gradle (минимальный шаблон)
Manifest (добавить по необходимости, лишние не включать):
```
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.BODY_SENSORS" />
<uses-permission android:name="android.permission.BODY_SENSORS_BACKGROUND" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />

<uses-feature android:name="android.hardware.sensor.accelerometer" android:required="true" />
<uses-feature android:name="android.hardware.sensor.gyroscope" android:required="true" />
<uses-feature android:name="android.hardware.sensor.barometer" android:required="false" />
<uses-feature android:name="android.hardware.sensor.magnetometer" android:required="false" />
<uses-feature android:name="android.hardware.sensor.heartrate" android:required="false" />

<service
    android:name=".service.CollectorService"
    android:exported="false"
    android:foregroundServiceType="dataSync|connectedDevice|health" />
```

Gradle (примеры зависимостей, версии брать из BOM/каталога версий проекта):
```
implementation("androidx.core:core-ktx")
implementation("androidx.activity:activity-compose")
implementation("androidx.lifecycle:lifecycle-runtime-ktx")
implementation("androidx.work:work-runtime-ktx")
implementation("androidx.room:room-ktx")
ksp("androidx.room:room-compiler")
implementation("com.squareup.okhttp3:okhttp")
implementation("com.squareup.retrofit2:retrofit")
implementation("com.google.crypto.tink:tink-android")
implementation("androidx.wear.compose:compose-material")
```

## 15. Рекомендованные библиотеки и классы
Сбор и обработка:
- Kotlin + Coroutines/Flow.
- `SensorManager`, `SensorEventListener` для акселя/гиро/баро/маг.
- Пульс: `Sensor.TYPE_HEART_RATE` или Health Services (если решим).

Хранение:
- Room (SQLite) для очереди и событий.
- Шифрование: `EncryptedFile`/`EncryptedSharedPreferences` или Tink.

Сеть:
- OkHttp/Retrofit + gzip.

Фоновая отправка:
- WorkManager для гарантированной доставки при наличии сети, ограничения: гарантирует запуск после выполнения `Constraints`, длительность фоновой работы лимитирована, для долгих задач использует foreground service.citeturn0search11turn0search10

## 16. Room‑схема (локальные таблицы на часах)
Цель: хранить сырые события, очередь пакетов и метаданные для устойчивой отправки.

Рекомендуемые Entity (минимум):
- `SensorSampleEntity`: `id`, `type`, `ts_ms`, `x`, `y`, `z`, `quality`.
- `HeartRateEntity`: `id`, `ts_ms`, `bpm`, `confidence`.
- `BaroEntity`: `id`, `ts_ms`, `hpa`.
- `MagEntity`: `id`, `ts_ms`, `x`, `y`, `z`.
- `BleEventEntity`: `id`, `ts_ms`, `beacon_id`, `rssi`.
- `WearEventEntity`: `id`, `ts_ms`, `state`.
- `BatteryEventEntity`: `id`, `ts_ms`, `level`.
- `DowntimeReasonEntity`: `id`, `ts_ms`, `reason_id`, `zone_id`.
- `PacketQueueEntity`: `packet_id`, `created_ts_ms`, `shift_start_ts_ms`, `shift_end_ts_ms`, `status`, `attempt`, `last_error`, `payload_path`.
- `PacketPartEntity` (опц.): для чанков больших payloadов — `packet_id`, `part_no`, `path`.

Минимальный DDL (пример):
```sql
CREATE TABLE sensor_samples (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  type TEXT NOT NULL,
  ts_ms INTEGER NOT NULL,
  x REAL, y REAL, z REAL,
  quality REAL
);
CREATE INDEX idx_sensor_ts ON sensor_samples(ts_ms);

CREATE TABLE ble_events (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  ts_ms INTEGER NOT NULL,
  beacon_id TEXT NOT NULL,
  rssi INTEGER
);
CREATE INDEX idx_ble_ts ON ble_events(ts_ms);

CREATE TABLE wear_events (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  ts_ms INTEGER NOT NULL,
  state TEXT NOT NULL
);

CREATE TABLE packet_queue (
  packet_id TEXT PRIMARY KEY,
  created_ts_ms INTEGER NOT NULL,
  shift_start_ts_ms INTEGER NOT NULL,
  shift_end_ts_ms INTEGER NOT NULL,
  status TEXT NOT NULL,
  attempt INTEGER NOT NULL DEFAULT 0,
  last_error TEXT,
  payload_path TEXT NOT NULL
);
```

Политики:
- Ротация данных: удаляем сырые события после подтвержденной отправки + N дней буфера.
- Максимальный размер БД: при превышении — принудительная сборка пакета и выгрузка.

### 16.1. Kotlin Entity/DAO (минимальный шаблон)
Ниже минимальные классы, которые Claude Code может использовать как старт.

```kotlin
@Entity(tableName = "sensor_samples", indices = [Index("ts_ms")])
data class SensorSampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,
    val ts_ms: Long,
    val x: Float? = null,
    val y: Float? = null,
    val z: Float? = null,
    val quality: Float? = null
)

@Entity(tableName = "ble_events", indices = [Index("ts_ms")])
data class BleEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts_ms: Long,
    val beacon_id: String,
    val rssi: Int? = null
)

@Entity(tableName = "wear_events")
data class WearEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts_ms: Long,
    val state: String
)

@Entity(tableName = "packet_queue")
data class PacketQueueEntity(
    @PrimaryKey val packet_id: String,
    val created_ts_ms: Long,
    val shift_start_ts_ms: Long,
    val shift_end_ts_ms: Long,
    val status: String,
    val attempt: Int = 0,
    val last_error: String? = null,
    val payload_path: String
)

@Dao
interface SensorDao {
    @Insert fun insertSensorSamples(items: List<SensorSampleEntity>)
    @Query("SELECT * FROM sensor_samples WHERE ts_ms BETWEEN :from AND :to")
    fun range(from: Long, to: Long): List<SensorSampleEntity>
    @Query("DELETE FROM sensor_samples WHERE ts_ms < :before")
    fun deleteBefore(before: Long)
}

@Dao
interface PacketQueueDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun enqueue(item: PacketQueueEntity)
    @Query("SELECT * FROM packet_queue WHERE status = :status ORDER BY created_ts_ms ASC")
    fun byStatus(status: String): List<PacketQueueEntity>
    @Query("UPDATE packet_queue SET status = :status, attempt = :attempt, last_error = :err WHERE packet_id = :id")
    fun updateStatus(id: String, status: String, attempt: Int, err: String?)
    @Query("DELETE FROM packet_queue WHERE packet_id = :id")
    fun delete(id: String)
}
```

### 16.2. Kotlin Entity/DAO (полный набор)
Расширенная версия для всех сущностей из секции 16.

```kotlin
@Entity(tableName = "heart_rate")
data class HeartRateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts_ms: Long,
    val bpm: Int,
    val confidence: Float? = null
)

@Entity(tableName = "baro")
data class BaroEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts_ms: Long,
    val hpa: Float
)

@Entity(tableName = "mag")
data class MagEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts_ms: Long,
    val x: Float,
    val y: Float,
    val z: Float
)

@Entity(tableName = "battery")
data class BatteryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts_ms: Long,
    val level: Float
)

@Entity(tableName = "downtime_reason")
data class DowntimeReasonEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts_ms: Long,
    val reason_id: String,
    val zone_id: String? = null
)

@Entity(tableName = "packet_parts", primaryKeys = ["packet_id", "part_no"])
data class PacketPartEntity(
    val packet_id: String,
    val part_no: Int,
    val path: String
)

@Dao
interface BleDao {
    @Insert fun insert(items: List<BleEventEntity>)
    @Query("SELECT * FROM ble_events WHERE ts_ms BETWEEN :from AND :to")
    fun range(from: Long, to: Long): List<BleEventEntity>
    @Query("DELETE FROM ble_events WHERE ts_ms < :before")
    fun deleteBefore(before: Long)
}

@Dao
interface WearDao {
    @Insert fun insert(items: List<WearEventEntity>)
    @Query("SELECT * FROM wear_events WHERE ts_ms BETWEEN :from AND :to")
    fun range(from: Long, to: Long): List<WearEventEntity>
    @Query("DELETE FROM wear_events WHERE ts_ms < :before")
    fun deleteBefore(before: Long)
}

@Dao
interface HeartRateDao {
    @Insert fun insert(items: List<HeartRateEntity>)
    @Query("SELECT * FROM heart_rate WHERE ts_ms BETWEEN :from AND :to")
    fun range(from: Long, to: Long): List<HeartRateEntity>
    @Query("DELETE FROM heart_rate WHERE ts_ms < :before")
    fun deleteBefore(before: Long)
}

@Dao
interface BaroDao {
    @Insert fun insert(items: List<BaroEntity>)
    @Query("SELECT * FROM baro WHERE ts_ms BETWEEN :from AND :to")
    fun range(from: Long, to: Long): List<BaroEntity>
    @Query("DELETE FROM baro WHERE ts_ms < :before")
    fun deleteBefore(before: Long)
}

@Dao
interface MagDao {
    @Insert fun insert(items: List<MagEntity>)
    @Query("SELECT * FROM mag WHERE ts_ms BETWEEN :from AND :to")
    fun range(from: Long, to: Long): List<MagEntity>
    @Query("DELETE FROM mag WHERE ts_ms < :before")
    fun deleteBefore(before: Long)
}

@Dao
interface BatteryDao {
    @Insert fun insert(items: List<BatteryEntity>)
    @Query("SELECT * FROM battery WHERE ts_ms BETWEEN :from AND :to")
    fun range(from: Long, to: Long): List<BatteryEntity>
    @Query("DELETE FROM battery WHERE ts_ms < :before")
    fun deleteBefore(before: Long)
}

@Dao
interface DowntimeReasonDao {
    @Insert fun insert(items: List<DowntimeReasonEntity>)
    @Query("SELECT * FROM downtime_reason WHERE ts_ms BETWEEN :from AND :to")
    fun range(from: Long, to: Long): List<DowntimeReasonEntity>
    @Query("DELETE FROM downtime_reason WHERE ts_ms < :before")
    fun deleteBefore(before: Long)
}

@Dao
interface PacketPartDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(parts: List<PacketPartEntity>)
    @Query("SELECT * FROM packet_parts WHERE packet_id = :id ORDER BY part_no ASC")
    fun parts(id: String): List<PacketPartEntity>
    @Query("DELETE FROM packet_parts WHERE packet_id = :id")
    fun deleteForPacket(id: String)
}

@Database(
    entities = [
        SensorSampleEntity::class,
        BleEventEntity::class,
        WearEventEntity::class,
        HeartRateEntity::class,
        BaroEntity::class,
        MagEntity::class,
        BatteryEntity::class,
        DowntimeReasonEntity::class,
        PacketQueueEntity::class,
        PacketPartEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sensorDao(): SensorDao
    abstract fun bleDao(): BleDao
    abstract fun wearDao(): WearDao
    abstract fun heartRateDao(): HeartRateDao
    abstract fun baroDao(): BaroDao
    abstract fun magDao(): MagDao
    abstract fun batteryDao(): BatteryDao
    abstract fun downtimeReasonDao(): DowntimeReasonDao
    abstract fun packetQueueDao(): PacketQueueDao
    abstract fun packetPartDao(): PacketPartDao
}
```

### 16.3. Repository слой и Flow (пример)
```kotlin
interface SamplesRepository {
    suspend fun saveSensor(samples: List<SensorSampleEntity>)
    suspend fun saveBle(events: List<BleEventEntity>)
    suspend fun saveWear(events: List<WearEventEntity>)
    suspend fun enqueuePacket(item: PacketQueueEntity)
    fun observeQueue(status: String): Flow<List<PacketQueueEntity>>
}

class SamplesRepositoryImpl(
    private val db: AppDatabase,
    private val io: CoroutineDispatcher = Dispatchers.IO
) : SamplesRepository {
    override suspend fun saveSensor(samples: List<SensorSampleEntity>) = withContext(io) {
        db.sensorDao().insertSensorSamples(samples)
    }
    override suspend fun saveBle(events: List<BleEventEntity>) = withContext(io) {
        db.bleDao().insert(events)
    }
    override suspend fun saveWear(events: List<WearEventEntity>) = withContext(io) {
        db.wearDao().insert(events)
    }
    override suspend fun enqueuePacket(item: PacketQueueEntity) = withContext(io) {
        db.packetQueueDao().enqueue(item)
    }
    override fun observeQueue(status: String): Flow<List<PacketQueueEntity>> =
        db.packetQueueDao().byStatusFlow(status)
}
```

```kotlin
@Dao
interface PacketQueueDao {
    @Query("SELECT * FROM packet_queue WHERE status = :status ORDER BY created_ts_ms ASC")
    fun byStatusFlow(status: String): Flow<List<PacketQueueEntity>>
}
```

### 16.4. Миграции Room (пример)
```kotlin
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE sensor_samples ADD COLUMN quality REAL")
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS packet_parts (" +
                "packet_id TEXT NOT NULL," +
                "part_no INTEGER NOT NULL," +
                "path TEXT NOT NULL," +
                "PRIMARY KEY(packet_id, part_no)" +
            ")"
        )
    }
}
```

```kotlin
Room.databaseBuilder(context, AppDatabase::class.java, "watch.db")
    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
    .build()
```

## 17. Профили BLE‑сканирования и режимы батареи
Профили сканирования:
- `NORMAL`: окно 10 сек каждые 60 сек.
- `ECO`: окно 5 сек каждые 120 сек.
- `AGGRESSIVE`: окно 10 сек каждые 30 сек (использовать кратковременно).

Переключение профиля:
- Battery < 20% → `ECO`.
- Charging → `AGGRESSIVE` (опц., для калибровки).
- Нет сети более 2 часов → `ECO`.
- On‑wrist + рабочая смена → `NORMAL`.

Сенсорные профили (в паре с BLE):
- `NORMAL`: accel/gyro 25–50 Гц.
- `ECO`: accel/gyro 12–25 Гц, отключить магнитометр.
- `AGGRESSIVE`: accel/gyro 50–100 Гц краткими окнами.

## 18. Инструкция по запуску и отладке на Watch 8
Подготовка устройства:
1. Включить Developer Options на часах.
2. Включить `ADB Debugging` и (при необходимости) `Debug over Wi‑Fi`.
3. Подключить часы к той же Wi‑Fi сети, что и ПК.

ADB‑подключение:
```bash
adb connect <watch_ip>:5555
adb devices
```

Установка и запуск:
```bash
adb install -r app-debug.apk
adb shell am start -n com.example.activity_tracker/.presentation.MainActivity
```

Логи и диагностика:
```bash
adb logcat | rg activity_tracker
adb shell dumpsys batterystats | rg activity_tracker
adb shell dumpsys sensorservice
adb shell dumpsys activity service com.example.activity_tracker
```

Полевые проверки:
- Проверить, что foreground‑уведомление видно всегда.
- Отключить сеть → убедиться, что пакеты переходят в очередь.
- Включить сеть → убедиться, что очередь отправляется.

## 19. Частоты и профили сбора (предложение, требуется согласование)
Базовый профиль “Смена 24h”:
- акселерометр/гироскоп: 25–50 Гц;
- барометр: 1 Гц;
- магнитометр: 5–10 Гц;
- пульс: 0.2–1 Гц;
- BLE‑скан: окно 5–10 сек каждые 60–120 сек.

Решения фиксируются после пилотных замеров батареи на Watch 8 и альтернативной модели Watch4+.

## 20. Безопасность и целостность
Требования ТЗ:
- шифрование данных на часах;
- расшифровка только на сервере;
- защищенный канал при выгрузке.

Реализация:
- AES‑GCM для данных в `LocalStore`;
- ключ в Android Keystore;
- контрольная сумма/подпись “пакета смены” (SHA‑256 + HMAC);
- HTTPS/TLS от часов до сервера, опционально pinning сертификата.

## 21. UX на часах (минимальный)
- экран статуса: “сбор активен / нет связи / низкий заряд”;
- экран ошибки выгрузки;
- опционально: “причина простоя” с коротким списком.

UX‑принципы: быстрые, glanceable экраны и оффлайн‑устойчивость.citeturn1search7

## 22. Порядок выполнения
1. Уточнить частоты сбора и профиль BLE‑сканирования.
2. Определить формат “пакета смены” и версионирование.
3. Реализовать сбор сенсоров + wear‑событий, локальное хранение.
4. Добавить BLE‑сканирование и запись BLE‑событий.
5. Реализовать крипто‑хранилище и сбор пакета.
6. Реализовать прямую отправку на сервер (DIRECT), очередь и подтверждение (ACK/Retry).
7. Реализовать BLE‑синхронизацию со шлюзом (GATEWAY) — начало/конец смены.
8. Добавить базовый UI статуса и опционально экран причины простоя.
9. Полевое тестирование батареи, корректности данных и стабильности выгрузки (DIRECT/GATEWAY).
10. Подготовка эксплуатационной матрицы совместимости (Watch 8, Watch 7, Watch4+).

## 23. Гайд для Claude Code (как реализовывать)
Цель: реализовать Watch‑only приложение с прямой отправкой на сервер без мобильного шлюза.

Минимальная структура пакетов (внутри `com.example.activity_tracker`):
- `data` — Room entities/dao, модели очереди, локальные события.
- `sensor` — сбор сенсоров и агрегация.
- `ble` — сканер BLE.
- `crypto` — шифрование/дешифрование, ключи.
- `network` — API клиент, загрузчик, ретраи.
- `service` — foreground service, координатор сбора.
- `ui` — экраны состояния и ошибок.
- `util` — время, конфиг, сериализация.

Ключевые классы и их обязанности:
1. `CollectorService` — foreground service, старт/стоп сбора, подписка на сенсоры, запись в `LocalStore`.
2. `SensorCollector` — единый интерфейс для всех сенсоров, отдаёт `Flow<SensorSample>`.
3. `BleScanner` — циклический BLE‑скан с окнами.
4. `WearStateTracker` — события on/off‑wrist.
5. `PacketBuilder` — выборка событий за период, сбор JSON, подсчет hash.
6. `CryptoManager` — AES‑GCM, упаковка payload, шифрование ключа.
7. `UploadQueue` — Room таблица для очереди пакетов и статусов.
8. `NetworkUploader` — OkHttp/Retrofit, обработка ответов, идемпотентность.
9. `SyncWorker` — WorkManager job для отправки пакетов при наличии сети.
10. `StatusScreen` — UI статуса (сбор активен, сеть, очередь).

Контракты (важно не менять без согласования):
- формат JSON‑пакета (секция 9.6);
- `packet_id` = `Idempotency-Key`;
- `payload_hash` = sha256 от plaintext payload.

Логика запуска:
1. При старте смены запускаем `CollectorService`.
2. По расписанию/событию создаем пакет смены и кладем в очередь.
3. `SyncWorker` отправляет пакет, подтверждает статус.

Где хранить ключи:
- `device_secret` и приватные ключи — в `Android Keystore`.
- публичный ключ сервера — в ресурсах (raw) или remote config.

Definition of Done:
1. Сбор сенсоров + BLE + wear событий, запись в Room.
2. Упаковка/шифрование/отправка пакета на сервер.
3. Идемпотентные повторы при сбоях.
4. UI показывает состояние и ошибки.
5. Тесты сериализации, крипто и очереди.

### 23.1. Skeleton классы (CollectorService, SyncWorker)
```kotlin
class CollectorService : LifecycleService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var sensorCollector: SensorCollector
    private lateinit var bleScanner: BleScanner

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildForegroundNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        sensorCollector.start(scope)
        bleScanner.start(scope)
        return START_STICKY
    }

    override fun onDestroy() {
        sensorCollector.stop()
        bleScanner.stop()
        scope.cancel()
        super.onDestroy()
    }
}
```

```kotlin
class SyncWorker(
    appContext: Context,
    params: WorkerParameters,
    private val uploader: NetworkUploader,
    private val queueDao: PacketQueueDao
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val items = queueDao.byStatus("PENDING")
        for (item in items) {
            val result = uploader.upload(item)
            when (result) {
                UploadResult.Success -> queueDao.delete(item.packet_id)
                is UploadResult.Retry -> queueDao.updateStatus(item.packet_id, "PENDING", item.attempt + 1, result.reason)
                is UploadResult.Fail -> {
                    queueDao.updateStatus(item.packet_id, "FAILED", item.attempt + 1, result.reason)
                    return Result.failure()
                }
            }
        }
        return Result.success()
    }
}
```

```kotlin
val constraints = Constraints.Builder()
    .setRequiredNetworkType(NetworkType.CONNECTED)
    .build()

val request = OneTimeWorkRequestBuilder<SyncWorker>()
    .setConstraints(constraints)
    .build()

WorkManager.getInstance(context)
    .enqueueUniqueWork("sync_packets", ExistingWorkPolicy.KEEP, request)
```

### 23.2. Repository для упаковки/крипто/загрузки (pipeline)
```kotlin
interface PacketPipeline {
    suspend fun buildPacket(shiftStart: Long, shiftEnd: Long): PacketQueueEntity
    suspend fun encryptPacket(packet: PacketQueueEntity): EncryptedPacket
    suspend fun upload(packet: EncryptedPacket): UploadResult
}

data class EncryptedPacket(
    val packetId: String,
    val payloadEnc: ByteArray,
    val payloadKeyEnc: ByteArray,
    val payloadHash: String
)

class PacketPipelineImpl(
    private val packetBuilder: PacketBuilder,
    private val crypto: CryptoManager,
    private val uploader: NetworkUploader,
    private val queueDao: PacketQueueDao
) : PacketPipeline {
    override suspend fun buildPacket(shiftStart: Long, shiftEnd: Long): PacketQueueEntity {
        val packet = packetBuilder.build(shiftStart, shiftEnd)
        queueDao.enqueue(packet)
        return packet
    }

    override suspend fun encryptPacket(packet: PacketQueueEntity): EncryptedPacket {
        return crypto.encrypt(packet)
    }

    override suspend fun upload(packet: EncryptedPacket): UploadResult {
        return uploader.upload(packet)
    }
}
```

Граф зависимостей (упрощенно):
```
CollectorService -> Sensor/BLE/Wear -> Room
PacketBuilder -> Room -> JSON payload
CryptoManager -> JSON -> payload_enc + payload_key_enc + hash
NetworkUploader -> HTTPS -> server
PacketQueueDao -> статусы/ретраи
```

### 23.3. Итерационный чек‑лист (реальные часы)
Цель: по окончании каждой итерации можно проверить работу на реальных часах.

**Итерация 1 — сбор и локальное хранение**
1. Запуск `CollectorService` как foreground service с постоянным уведомлением.
2. Сбор акселерометра/гироскопа + события wear/on‑off + базовые BLE‑сканы.
3. Запись событий в Room (проверка ростов таблиц).
4. Экран статуса: “Сбор активен / Ошибка”.

Проверка на часах:
- Включить сервис, убедиться, что уведомление постоянно.
- Подвигать рукой → записи должны появляться.
- Снять часы → событие wear/off‑wrist.
- В логах/Room видно, что данные пишутся.

**Итерация 2 — сбор пакета, шифрование и очередь**
1. Реализовать `PacketBuilder` (выборка событий за период).
2. Реализовать `CryptoManager` (AES‑GCM + ключ в Keystore).
3. Сохранение зашифрованного payload на диск, запись в `packet_queue`.
4. UI показывает “Пакет готов / В очереди”.

Проверка на часах:
- Сформировать пакет смены вручную (по кнопке/таймеру).
- Убедиться, что payload создается и шифруется.
- Очередь пополняется, ошибки отсутствуют.

**Итерация 3 — синхронизация (DIRECT + GATEWAY)**
1. DIRECT: отправка на сервер, обработка ответов 202/4xx/5xx, ретраи.
2. GATEWAY: BLE‑синхронизация (начало/конец смены), ACK.
3. Очередь очищается после подтверждения, ошибки фиксируются.
4. UI статуса показывает “Отправлено / Ошибка / В очереди”.

Проверка на часах:
- DIRECT: отключить сеть → пакет остается в очереди, включить → отправка проходит.
- GATEWAY: подключить тестовый шлюз, получить ACK, очередь очищается.
- Проверить идемпотентность (повторная отправка не создает дубли).

## 24. Тестирование и приемка
- Юнит‑тесты сериализации пакета и крипто‑модуля.
- Интеграционные тесты прямой отправки на сервер (обрывы, повторы, идемпотентность).
- Тесты батареи на Watch 8 (24h режим) и Watch4+.
- Проверка UX и стабильности на Wear OS 3+ устройствах.citeturn2search5

### 24.1. KPI чек‑лист (батарея и стабильность)
Батарея:
- 24 часа непрерывной работы с профилем `NORMAL` без принудительного отключения сборов.
- Потеря заряда за 1 час при `NORMAL` ≤ X% (фиксируется пилотом).

Стабильность:
- Потери данных при обрывах сети — 0 (подтверждается очередью + ретраями).
- Дубликаты пакетов на сервере — 0 при повторной отправке (идемпотентность).
- Среднее время выгрузки пакета ≤ Y минут при стабильной сети.

Уточнения KPI:
- X и Y фиксируются по результатам пилота на Watch 8.

## 25. Риски и меры
- Риск перерасхода батареи из‑за BLE: ограниченные окна скана и авто‑остановка.citeturn2search6
- Риск потери сенсорных данных в фоне: обязательный foreground service.citeturn1search0
- Риск отсутствия связи для выгрузки: очередь на часах, ретраи, индикация статуса.
- Риск недостаточной частоты: включить `HIGH_SAMPLING_RATE_SENSORS` при необходимости.citeturn1search6

## 26. Опциональные интеграции (не в MVP)
Samsung Health Data SDK:
- требует Samsung Health 6.30.2+, Android 10+, эмулятор не поддерживается.citeturn2search3

## 27. Открытые вопросы
- Точные пороги классификации классов активности A/B/C.
- Политика переполнения памяти на часах.
- Протокол подтверждения при выгрузке (ACK/Retry) и идемпотентность на сервере (DIRECT и GATEWAY).
- Приоритеты BLE‑зон при множественных метках.
- Канал связи по умолчанию (Wi‑Fi/LTE) и политика экономии трафика.
- Требования к сертификации и политике ключей (ротация, отзыв устройств).
- Сценарий привязки часов к сотруднику:
  - в режиме `GATEWAY` — через приложение оператора;
  - в режиме `DIRECT` — через сервер или ввод/скан на часах.
- Уточнить расхождение ТЗ: в таблице 6 упомянут Watch 7 при целевой модели Watch 8.
