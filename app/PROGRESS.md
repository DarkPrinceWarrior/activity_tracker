# Прогресс реализации Activity Tracker для Wear OS

Дата начала: 2026-02-04
Основание: IMPLEMENTATION_PLAN_WATCH.md

## Общая структура итераций

### Итерация 1 — Сбор и локальное хранение
- [ ] Room базу данных (entities, DAOs, Database)
- [ ] Foreground Service (CollectorService)
- [ ] SensorCollector - сбор акселерометра/гироскопа
- [ ] WearStateTracker - события on/off-wrist
- [ ] BleScanner - базовое BLE-сканирование
- [ ] Repository слой для записи данных
- [ ] Минимальный UI статуса
- [ ] Манифест с разрешениями

### Итерация 2 — Сбор пакета, шифрование и очередь
- [ ] PacketBuilder - формирование пакета смены
- [ ] CryptoManager - шифрование AES-GCM + Keystore
- [ ] PacketQueue - очередь пакетов
- [ ] UI отображение очереди

### Итерация 3 — Синхронизация (DIRECT)
- [ ] NetworkUploader - отправка на сервер
- [ ] API клиент (Retrofit/OkHttp)
- [ ] Обработка ответов и ретраи
- [ ] WorkManager для фоновой синхронизации
- [ ] Идемпотентность
- [ ] UI статусов отправки

---

## Детальный лог выполнения

### 2026-02-04 - Начало работы

#### ✅ Шаг 1: Добавление зависимостей (ЗАВЕРШЕН)
- Обновлен `libs.versions.toml` с версиями Room, Lifecycle, WorkManager, OkHttp, Retrofit, Tink, Coroutines
- Добавлен KSP плагин
- Обновлен `build.gradle.kts` с новыми зависимостями
- Зависимости: Room (2.6.1), Lifecycle (2.7.0), WorkManager (2.9.0), OkHttp (4.12.0), Retrofit (2.9.0), Tink (1.12.0)

#### ✅ Шаг 2: Создание Room базы данных (ЗАВЕРШЕН)
- Создана структура пакетов: data/local/entity, data/local/dao
- Созданы 10 Room entities:
  - SensorSampleEntity, BleEventEntity, WearEventEntity
  - HeartRateEntity, BaroEntity, MagEntity
  - BatteryEntity, DowntimeReasonEntity
  - PacketQueueEntity, PacketPartEntity
- Созданы 10 DAOs с методами insert, range, deleteBefore
- Создан AppDatabase класс (версия 1)

#### ✅ Шаг 3: Repository слой и инициализация (ЗАВЕРШЕН)
- Создан SamplesRepository интерфейс с методами для всех типов данных
- Создан SamplesRepositoryImpl с корректной обработкой IO операций
- Создан DatabaseProvider (singleton) для безопасной инициализации БД
- Создан ActivityTrackerApp (Application класс) с lazy инициализацией
- Обновлен AndroidManifest.xml для регистрации Application класса

#### ✅ Шаг 4: SensorCollector - сбор данных с сенсоров (ЗАВЕРШЕН)
- Созданы модели: SensorSample, SensorType
- Создан SensorProfile с 3 режимами: NORMAL (25 Гц), ECO (12.5 Гц), AGGRESSIVE (50 Гц)
- Создан SensorCollector с Flow для:
  - Акселерометра (обязательный)
  - Гироскопа (обязательный)
  - Барометра (опциональный, 1 Гц)
  - Магнитометра (опциональный, 10 Гц, отключен в ECO)
- Создан SensorDataAggregator для буферизации и пакетной записи в БД
- Реализована логика автовыбора профиля по заряду батареи

#### ✅ Шаг 5: WearStateTracker - отслеживание ношения часов (ЗАВЕРШЕН)
- Создана модель WearState с состояниями: ON_WRIST, OFF_WRIST, UNKNOWN
- Создан WearStateTracker с использованием датчика TYPE_LOW_LATENCY_OFFBODY_DETECT
- Реализован fallback для устройств без датчика (предположение ON_WRIST)
- Создан WearDataAggregator для записи событий ношения в БД
- Отправка событий только при изменении состояния (оптимизация)

#### ✅ Шаг 6: BleScanner - сканирование BLE-меток (ЗАВЕРШЕН)
- Создана модель BleBeacon (timestamp, beaconId, rssi)
- Создан BleProfile с 3 режимами сканирования:
  - NORMAL: 10 сек каждые 60 сек
  - ECO: 5 сек каждые 120 сек
  - AGGRESSIVE: 10 сек каждые 30 сек
- Создан BleScanner с периодическим сканированием окнами (экономия батареи)
- Создан BleDataAggregator для буферизации и пакетной записи в БД
- Реализована обработка ошибок и проверки доступности Bluetooth

#### ✅ Шаг 7: CollectorService - Foreground Service (ЗАВЕРШЕН)
- Создан CollectorService (extends Service)
- Реализован foreground notification с каналом "Activity Collection"
- Координация всех сборщиков данных:
  - SensorCollector (акселерометр, гироскоп, барометр, магнитометр)
  - BleScanner (BLE-метки)
  - WearStateTracker (on/off-wrist)
- Использование CoroutineScope с SupervisorJob для параллельного сбора
- Реализованы START_STICKY и обработка ACTION_START/STOP_COLLECTION
- Обновлен AndroidManifest.xml:
  - Добавлены все необходимые разрешения (Bluetooth, сенсоры, сеть)
  - Зарегистрирован CollectorService с foregroundServiceType
  - Добавлены требуемые hardware features

#### ✅ Шаг 8: HeartRateCollector - сбор данных пульса (ЗАВЕРШЕН)
- Создана модель HeartRateSample (timestamp, bpm, confidence)
- Создан HeartRateCollector с датчиком TYPE_HEART_RATE
- Частота сбора: ~1 Гц (SENSOR_DELAY_NORMAL) согласно плану
- Фильтрация нереалистичных значений (0-250 bpm)
- Расчет confidence на основе accuracy сенсора (HIGH=1.0, MEDIUM=0.85, LOW=0.6)
- Создан HeartRateDataAggregator с меньшим буфером (20 vs 100)
- Интегрирован в CollectorService как 7-й параллельный поток

#### ✅ Шаг 9: BatteryTracker - мониторинг батареи (ЗАВЕРШЕН)
- Создана модель BatterySample (timestamp, level, isCharging)
- Создан BatteryTracker с BroadcastReceiver для ACTION_BATTERY_CHANGED
- Отправка начального состояния батареи при запуске
- Методы getCurrentBatteryLevel() и isCharging() для синхронного доступа
- Создан BatteryDataAggregator (без буферизации, события редкие)
- Интегрирован в CollectorService как 8-й параллельный поток

#### ✅ Шаг 10: Минимальный UI статуса (ЗАВЕРШЕН)
- Создан StatusScreen с Wear Compose:
  - Индикатор "Сбор активен / Сбор остановлен"
  - Цветовая индикация статуса
  - Кнопка Запустить/Остановить
  - Информация о количестве потоков (8)
- Создан StatusViewModel для управления состоянием
- Обновлен MainActivity для использования StatusScreen
- Добавлена зависимость lifecycle-viewmodel-compose

---

## 🎉 ИТЕРАЦИЯ 1 ЗАВЕРШЕНА! 🎉

Готова полная система сбора и локального хранения данных:
- ✅ Room база данных (10 таблиц)
- ✅ 8 параллельных потоков сбора (сенсоры, BLE, wear, пульс, батарея)
- ✅ Foreground Service с уведомлением
- ✅ Все разрешения в манифесте
- ✅ Минимальный UI для управления

**Статус:** Готовы к Итерации 2 - упаковка и шифрование пакетов

**Следующий шаг:** PacketBuilder - формирование пакета смены в JSON

---

## ИТЕРАЦИЯ 2 — Сбор пакета, шифрование и очередь

### 2026-02-25 - Итерация 2

#### ✅ Шаг 11: PacketBuilder — формирование JSON-пакета смены (ЗАВЕРШЕН)
- Созданы модели пакета в `packet/model/ShiftPacket.kt`:
  - `ShiftPacket` — корневая структура (schema_version, packet_id, device, shift, samples, meta)
  - `DeviceInfo` — информация об устройстве (device_id, model, fw, app_version, tz)
  - `ShiftPeriod` — период смены (start_ts_ms, end_ts_ms)
  - `TimeSync` — синхронизация времени (server_time_offset_ms, server_time_ms)
  - `ShiftSamples` — все типы данных (accel, gyro, baro, mag, hr, ble, wear, battery, downtime_reason)
  - Sample-классы для каждого типа данных
  - `PacketMeta` — метаданные пакета (created_ts_ms, seq, upload_attempt)
- Создан `PacketBuilder` в `packet/PacketBuilder.kt`:
  - Выборка всех 8 типов данных из Room за период [startTs, endTs]
  - Маппинг entity → модель пакета
  - `build(startTs, endTs, seq)` → `ShiftPacket`
  - `toJson(packet)` → JSON-строка (Gson)
  - `sizeBytes(packet)` — размер JSON в байтах
  - `device_id` генерируется UUID и сохраняется в SharedPreferences
  - Логирование размеров всех коллекций

#### ✅ Шаг 12: CryptoManager — AES-256-GCM шифрование (ЗАВЕРШЕН)
- Создан `crypto/CryptoManager.kt`:
  - Гибридная схема шифрования (envelope encryption) согласно секции 9.2
  - Генерация случайного AES-256 ключа на каждый пакет
  - Шифрование контента: AES-256-GCM (IV=12 байт, тег=128 бит)
  - Шифрование data_key: RSA/OAEP с SHA-256 публичным ключом сервера
  - SHA-256 хэш от plaintext для проверки целостности на сервере
  - Результат `EncryptedPacket`: payloadEncBase64, payloadKeyEncBase64, ivBase64, payloadHashSha256
  - `getServerPublicKeyBytes()` — TODO-заглушка до получения ключа от бэкенда
  - Fallback/placeholder при отсутствии ключа сервера (для разработки)

#### ✅ Шаг 13: PacketPipeline — оркестрация сборки, шифрования и очереди (ЗАВЕРШЕН)
- Создан `packet/PacketPipeline.kt`:
  - `buildAndEnqueue(startTs, endTs, seq)` — полный цикл: Room → JSON → AES-GCM → диск → packet_queue
  - Сохранение зашифрованного payload в `files/packets/<packet_id>.enc`
  - Запись `PacketQueueEntity` в БД со статусом `pending`
  - Статусы пакетов: `pending`, `uploading`, `uploaded`, `error`
  - `readPayloadFromDisk(path)` и `deletePayload(path)` для управления файлами
  - Возвращает `PipelineResult` (packetId, payloadSizeBytes, payloadPath)

#### ✅ Шаг 14: Расширение UI — статус пакетов в StatusScreen (ЗАВЕРШЕН)
- Обновлён `StatusViewModel`:
  - Хранит `shiftStartTs` при старте сбора
  - При остановке автоматически вызывает `PacketPipeline.buildAndEnqueue()`
  - `pendingPacketsCount: StateFlow<Int>` — наблюдение за очередью (status=pending)
  - `uploadedPacketsCount: StateFlow<Int>` — наблюдение за отправленными (status=uploaded)
- Обновлён `StatusScreen`:
  - Новые параметры `pendingPackets` и `uploadedPackets`
  - Отображение "В очереди: N пак." (желтый/secondary цвет)
  - Отображение "Отправлено: N пак." (зеленый/primary цвет)
  - Индикатор активности встроен в текст статуса "● Сбор активен"
- Обновлён `MainActivity`: передача новых параметров через `collectAsState()`

---

## 🎉 ИТЕРАЦИЯ 2 ЗАВЕРШЕНА! 🎉

Готова система упаковки и шифрования пакетов:
- ✅ PacketBuilder — выборка данных из Room за период → JSON по схеме 9.6
- ✅ CryptoManager — AES-256-GCM + RSA-OAEP (envelope encryption)
- ✅ PacketPipeline — оркестрация builder→crypto→queue
- ✅ UI статуса очереди пакетов

**Статус:** Готовы к Итерации 3 — сетевая синхронизация (NetworkUploader, WorkManager)

**Следующий шаг:** NetworkUploader — отправка пакетов на сервер (DIRECT)
