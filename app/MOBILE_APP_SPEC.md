# Техническое задание: Мобильное приложение оператора (Android, Kotlin + Compose)

Дата: 2026-02-25
Основание: `IMPLEMENTATION_PLAN_WATCH.md`, `ТЗ_Смарт-часы_Вариант 1.md`, `BACKEND_SPEC.md`

---

## 1. Общее описание системы

### 1.1. Назначение мобильного приложения

Мобильное приложение оператора площадки (шлюз) предназначено для:

1. **Авторизации оператора** и выбора контекста работы (площадка, смена).
2. **Выдачи и привязки** смарт-часов Samsung Galaxy Watch 8 к сотрудникам на смену.
3. **Возврата часов** и закрытия привязки по окончании смены.
4. **Выгрузки зашифрованных данных** (пакетов смены) с часов по Bluetooth и их передачи на сервер (GATEWAY-режим).
5. **Оффлайн-работы** — сохранение операций и пакетов в локальную очередь с автоматической синхронизацией при появлении сети.
6. **Ведения журнала операций** — выдачи, возвраты, выгрузки, статусы, ошибки.
7. **Отображения операционной сводки** по текущей смене.

### 1.2. Контекст

Приложение является частью программно-аппаратного комплекса контроля подрядчиков:

- **Смарт-часы (Wear OS)** — собирают сырые данные сенсоров, шифруют и формируют «пакет смены».
- **Мобильное приложение оператора (данный документ)** — шлюз между часами и сервером (GATEWAY-режим).
- **Backend (FastAPI)** — принимает пакеты, расшифровывает, анализирует.
- **Web-интерфейс** — аналитика, справочники, администрирование.

**Роль приложения в потоке данных (GATEWAY-режим):**

```
[Watch] --BLE--> [Мобильное приложение] --HTTPS--> [Backend API]
                       |
                       +-- Привязка часов к сотруднику
                       +-- Контекст смены (site_id, shift_date, employee_id)
                       +-- Локальная очередь неотправленных пакетов
```

**Взаимодействие с Wear OS приложением:**

- Часы уже реализованы (Kotlin, Wear OS): сбор сенсоров, BLE-сканирование, шифрование AES-256-GCM + RSA-OAEP, формирование пакетов.
- Мобильное приложение считывает зашифрованный пакет с часов по Bluetooth и передаёт на сервер **без расшифровки** (расшифровка — только на сервере).

**Взаимодействие с Backend:**

- Backend реализован на FastAPI (Python): `BACKEND_SPEC.md`.
- Мобильное приложение использует GATEWAY-эндпоинт: `POST /api/v1/gateway/packets`.
- Авторизация: JWT (web-token, роль `operator`).
- Справочники (сотрудники, часы, площадки) загружаются с сервера и кэшируются локально.

### 1.3. Целевые пользователи

| Роль | Описание |
|------|----------|
| **Оператор площадки / диспетчер** | Основной пользователь. Выдаёт/принимает часы, выгружает данные, контролирует статусы устройств. Работает на планшете или смартфоне на площадке. |

### 1.4. Стек технологий

> На дворе 25 февраля 2026 года. Экосистема Android окончательно стабилизировалась вокруг Kotlin-first и Compose-only подходов. XML и Java — глубокое легаси.

- **Язык:** Kotlin 2.1+ (K2 compiler)
- **UI:** Jetpack Compose (Material 3), Compose Navigation
- **Архитектура:** Clean Architecture + MVI (Model-View-Intent)
- **DI:** Koin 4.x или Hilt (Dagger) — выбор фиксируется при старте
- **Сеть:** Ktor Client 3.x (Kotlin-native HTTP-клиент) или Retrofit 2.x + OkHttp 5.x
- **Сериализация:** Kotlinx Serialization 2.x
- **Локальная БД:** Room 2.7+ (KSP) с поддержкой Flow
- **Bluetooth:** Android Bluetooth API (`BluetoothGatt`, `BluetoothLeScanner`), Companion Device Manager
- **Фоновые задачи:** WorkManager 2.10+ (гарантированная доставка)
- **Безопасность:** EncryptedSharedPreferences, Android Keystore, Tink
- **Навигация:** Compose Navigation (type-safe routes)
- **Изображения:** Coil 3.x (Compose-native)
- **Логирование:** Timber / kotlin-logging
- **Сборка:** Gradle 8.x + Kotlin DSL, Version Catalog (libs.versions.toml)
- **Min SDK:** 29 (Android 10)
- **Target SDK:** 36 (Android 16)
- **Compile SDK:** 36

### 1.5. Совместимость

| Параметр | Значение |
|----------|----------|
| **Android** | 10+ (API 29+) |
| **Целевые устройства** | Samsung Galaxy Tab A9+, Samsung Galaxy A-серия, любой Android 10+ с Bluetooth 5.0+ |
| **Bluetooth** | BLE 5.0+, Classic Bluetooth для передачи данных с часов |
| **Ориентация** | Портретная + ландшафтная (адаптивный layout) |
| **Локализация** | Русский (основной), English (опционально) |
| **Часовой пояс** | Настраивается по площадке (Europe/Moscow по умолчанию) |

---

## 2. Архитектура

### 2.1. Общая диаграмма

```
┌─────────────────────────────────────────────────┐
│              Мобильное приложение                │
│                                                   │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐       │
│  │    UI    │  │    UI    │  │    UI    │       │
│  │ (Compose)│  │ (Compose)│  │ (Compose)│       │
│  │  Login   │  │  Binding │  │  Upload  │       │
│  └────┬─────┘  └────┬─────┘  └────┬─────┘       │
│       │              │              │             │
│  ┌────┴──────────────┴──────────────┴─────┐      │
│  │         ViewModel (MVI)                │      │
│  │   State / Intent / SideEffect          │      │
│  └────┬──────────────┬──────────────┬─────┘      │
│       │              │              │             │
│  ┌────┴─────┐  ┌─────┴─────┐ ┌─────┴──────┐     │
│  │ UseCases │  │ UseCases  │ │ UseCases   │     │
│  │  (Auth)  │  │(Binding)  │ │(Upload/BLE)│     │
│  └────┬─────┘  └─────┬─────┘ └─────┬──────┘     │
│       │              │              │             │
│  ┌────┴──────────────┴──────────────┴─────┐      │
│  │            Repository Layer            │      │
│  │  AuthRepo | DeviceRepo | PacketRepo    │      │
│  │  EmployeeRepo | BindingRepo | SyncRepo │      │
│  └────┬──────────────┬──────────────┬─────┘      │
│       │              │              │             │
│  ┌────┴────┐   ┌─────┴─────┐ ┌─────┴──────┐     │
│  │ Remote  │   │  Local    │ │ Bluetooth  │     │
│  │DataSrc  │   │ DataSrc   │ │  DataSrc   │     │
│  │(Ktor/   │   │ (Room +   │ │ (BLE GATT) │     │
│  │Retrofit)│   │ DataStore)│ │            │     │
│  └─────────┘   └───────────┘ └────────────┘     │
│                                                   │
└─────────────────────────────────────────────────┘
         │                              │
    HTTPS/TLS                    Bluetooth
         │                              │
   ┌─────┴─────┐               ┌────────┴────────┐
   │  Backend   │               │  Samsung Watch  │
   │  (FastAPI) │               │  (Wear OS App)  │
   └────────────┘               └─────────────────┘
```

### 2.2. Слои приложения (Clean Architecture)

```
app/
├── build.gradle.kts
├── src/main/
│   ├── java/com/example/activity_tracker_mobile/
│   │   │
│   │   ├── di/                           # DI-модули (Koin/Hilt)
│   │   │   ├── AppModule.kt
│   │   │   ├── NetworkModule.kt
│   │   │   ├── DatabaseModule.kt
│   │   │   ├── BluetoothModule.kt
│   │   │   └── UseCaseModule.kt
│   │   │
│   │   ├── domain/                       # Бизнес-логика (чистый Kotlin)
│   │   │   ├── model/                    # Domain-модели
│   │   │   │   ├── Employee.kt
│   │   │   │   ├── Device.kt
│   │   │   │   ├── DeviceBinding.kt
│   │   │   │   ├── Shift.kt
│   │   │   │   ├── ShiftPacket.kt
│   │   │   │   ├── PacketQueueItem.kt
│   │   │   │   ├── OperationLog.kt
│   │   │   │   ├── Site.kt
│   │   │   │   └── User.kt
│   │   │   │
│   │   │   ├── repository/              # Интерфейсы репозиториев
│   │   │   │   ├── AuthRepository.kt
│   │   │   │   ├── EmployeeRepository.kt
│   │   │   │   ├── DeviceRepository.kt
│   │   │   │   ├── BindingRepository.kt
│   │   │   │   ├── PacketRepository.kt
│   │   │   │   ├── SiteRepository.kt
│   │   │   │   └── SyncRepository.kt
│   │   │   │
│   │   │   └── usecase/                 # Use Cases
│   │   │       ├── auth/
│   │   │       │   ├── LoginUseCase.kt
│   │   │       │   ├── LogoutUseCase.kt
│   │   │       │   └── RefreshTokenUseCase.kt
│   │   │       ├── binding/
│   │   │       │   ├── BindDeviceUseCase.kt
│   │   │       │   ├── UnbindDeviceUseCase.kt
│   │   │       │   └── GetActiveBindingsUseCase.kt
│   │   │       ├── upload/
│   │   │       │   ├── ReadPacketFromWatchUseCase.kt
│   │   │       │   ├── UploadPacketUseCase.kt
│   │   │       │   └── RetryUploadUseCase.kt
│   │   │       ├── device/
│   │   │       │   ├── ScanForWatchUseCase.kt
│   │   │       │   ├── GetDeviceListUseCase.kt
│   │   │       │   └── UpdateDeviceStatusUseCase.kt
│   │   │       ├── employee/
│   │   │       │   ├── FindEmployeeUseCase.kt
│   │   │       │   └── GetEmployeeListUseCase.kt
│   │   │       └── sync/
│   │   │           ├── SyncReferenceDataUseCase.kt
│   │   │           └── SyncPendingPacketsUseCase.kt
│   │   │
│   │   ├── data/                         # Реализация данных
│   │   │   ├── remote/                   # Сетевой слой
│   │   │   │   ├── api/
│   │   │   │   │   ├── AuthApi.kt
│   │   │   │   │   ├── GatewayApi.kt
│   │   │   │   │   ├── DeviceApi.kt
│   │   │   │   │   ├── EmployeeApi.kt
│   │   │   │   │   ├── BindingApi.kt
│   │   │   │   │   └── SiteApi.kt
│   │   │   │   ├── dto/                  # Data Transfer Objects (request/response)
│   │   │   │   │   ├── AuthDto.kt
│   │   │   │   │   ├── GatewayPacketDto.kt
│   │   │   │   │   ├── DeviceDto.kt
│   │   │   │   │   ├── EmployeeDto.kt
│   │   │   │   │   ├── BindingDto.kt
│   │   │   │   │   └── SiteDto.kt
│   │   │   │   ├── interceptor/
│   │   │   │   │   ├── AuthInterceptor.kt
│   │   │   │   │   └── TokenRefreshInterceptor.kt
│   │   │   │   └── NetworkClient.kt
│   │   │   │
│   │   │   ├── local/                    # Локальное хранилище
│   │   │   │   ├── db/
│   │   │   │   │   ├── AppDatabase.kt
│   │   │   │   │   ├── dao/
│   │   │   │   │   │   ├── EmployeeDao.kt
│   │   │   │   │   │   ├── DeviceDao.kt
│   │   │   │   │   │   ├── BindingDao.kt
│   │   │   │   │   │   ├── PacketQueueDao.kt
│   │   │   │   │   │   ├── OperationLogDao.kt
│   │   │   │   │   │   └── SiteDao.kt
│   │   │   │   │   └── entity/
│   │   │   │   │       ├── EmployeeEntity.kt
│   │   │   │   │       ├── DeviceEntity.kt
│   │   │   │   │       ├── BindingEntity.kt
│   │   │   │   │       ├── PacketQueueEntity.kt
│   │   │   │   │       ├── OperationLogEntity.kt
│   │   │   │   │       └── SiteEntity.kt
│   │   │   │   ├── datastore/
│   │   │   │   │   ├── UserPreferences.kt    # DataStore: токены, контекст
│   │   │   │   │   └── AppSettings.kt
│   │   │   │   └── secure/
│   │   │   │       └── SecureStorage.kt       # EncryptedSharedPreferences
│   │   │   │
│   │   │   ├── bluetooth/                # Bluetooth-взаимодействие с часами
│   │   │   │   ├── WatchConnection.kt
│   │   │   │   ├── WatchScanner.kt
│   │   │   │   ├── WatchPacketReader.kt
│   │   │   │   ├── WatchShiftContextWriter.kt
│   │   │   │   └── BleProtocol.kt         # Протокол обмена данными
│   │   │   │
│   │   │   ├── repository/               # Реализации репозиториев
│   │   │   │   ├── AuthRepositoryImpl.kt
│   │   │   │   ├── EmployeeRepositoryImpl.kt
│   │   │   │   ├── DeviceRepositoryImpl.kt
│   │   │   │   ├── BindingRepositoryImpl.kt
│   │   │   │   ├── PacketRepositoryImpl.kt
│   │   │   │   ├── SiteRepositoryImpl.kt
│   │   │   │   └── SyncRepositoryImpl.kt
│   │   │   │
│   │   │   └── worker/                   # WorkManager задачи
│   │   │       ├── SyncPacketsWorker.kt
│   │   │       └── SyncReferenceDataWorker.kt
│   │   │
│   │   ├── presentation/                 # UI-слой (Compose + MVI)
│   │   │   ├── navigation/
│   │   │   │   ├── AppNavGraph.kt
│   │   │   │   └── Route.kt
│   │   │   │
│   │   │   ├── theme/
│   │   │   │   ├── Theme.kt
│   │   │   │   ├── Color.kt
│   │   │   │   ├── Type.kt
│   │   │   │   └── Shape.kt
│   │   │   │
│   │   │   ├── common/                   # Общие Compose-компоненты
│   │   │   │   ├── LoadingIndicator.kt
│   │   │   │   ├── ErrorMessage.kt
│   │   │   │   ├── StatusBadge.kt
│   │   │   │   ├── SearchBar.kt
│   │   │   │   ├── ConfirmDialog.kt
│   │   │   │   └── PullToRefresh.kt
│   │   │   │
│   │   │   ├── login/
│   │   │   │   ├── LoginScreen.kt
│   │   │   │   ├── LoginViewModel.kt
│   │   │   │   └── LoginContract.kt       # State / Intent / Effect
│   │   │   │
│   │   │   ├── home/
│   │   │   │   ├── HomeScreen.kt
│   │   │   │   ├── HomeViewModel.kt
│   │   │   │   └── HomeContract.kt
│   │   │   │
│   │   │   ├── binding/
│   │   │   │   ├── issue/                 # Выдача часов
│   │   │   │   │   ├── IssueScreen.kt
│   │   │   │   │   ├── IssueViewModel.kt
│   │   │   │   │   └── IssueContract.kt
│   │   │   │   └── return_device/         # Возврат часов
│   │   │   │       ├── ReturnScreen.kt
│   │   │   │       ├── ReturnViewModel.kt
│   │   │   │       └── ReturnContract.kt
│   │   │   │
│   │   │   ├── upload/
│   │   │   │   ├── UploadScreen.kt
│   │   │   │   ├── UploadViewModel.kt
│   │   │   │   └── UploadContract.kt
│   │   │   │
│   │   │   ├── devices/
│   │   │   │   ├── DeviceListScreen.kt
│   │   │   │   ├── DeviceListViewModel.kt
│   │   │   │   └── DeviceListContract.kt
│   │   │   │
│   │   │   ├── journal/
│   │   │   │   ├── JournalScreen.kt
│   │   │   │   ├── JournalViewModel.kt
│   │   │   │   └── JournalContract.kt
│   │   │   │
│   │   │   ├── summary/
│   │   │   │   ├── SummaryScreen.kt
│   │   │   │   ├── SummaryViewModel.kt
│   │   │   │   └── SummaryContract.kt
│   │   │   │
│   │   │   └── settings/
│   │   │       ├── SettingsScreen.kt
│   │   │       └── SettingsViewModel.kt
│   │   │
│   │   ├── util/
│   │   │   ├── DateTimeUtils.kt
│   │   │   ├── NetworkMonitor.kt
│   │   │   ├── PermissionHelper.kt
│   │   │   └── Extensions.kt
│   │   │
│   │   └── App.kt                        # Application class
│   │
│   ├── res/
│   │   ├── values/
│   │   │   ├── strings.xml                # RU-локализация
│   │   │   └── themes.xml
│   │   └── values-en/
│   │       └── strings.xml                # EN (опционально)
│   │
│   └── AndroidManifest.xml
│
├── libs.versions.toml                     # Version Catalog
└── proguard-rules.pro
```

### 2.3. Принципы архитектуры

1. **Однонаправленный поток данных (UDF):** UI → Intent → ViewModel → State → UI.
2. **Offline-first:** все операции сохраняются локально, синхронизация — фоновая.
3. **Repository pattern:** единственная точка доступа к данным; решает, откуда брать (сеть / кэш / БД).
4. **Dependency Injection:** все зависимости инжектятся, никаких синглтонов в коде.
5. **Иммутабельные состояния:** все UI-состояния — `data class` с `copy()`.
6. **Корутины + Flow:** асинхронность через `suspend` и `Flow`; никаких callback-ов.
7. **Разделение слоёв:** `domain` не зависит от `data` и `presentation`; маппинг Entity ↔ Domain ↔ DTO.

---

## 3. Модель данных (локальная БД)

### 3.1. Room Database — `AppDatabase`

Локальная БД хранит кэшированные справочники, очередь пакетов, журнал операций и контекст текущей смены.

```kotlin
@Database(
    entities = [
        EmployeeEntity::class,
        DeviceEntity::class,
        BindingEntity::class,
        PacketQueueEntity::class,
        OperationLogEntity::class,
        SiteEntity::class,
        ShiftContextEntity::class,
        DowntimeReasonEntity::class,
    ],
    version = 1,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun employeeDao(): EmployeeDao
    abstract fun deviceDao(): DeviceDao
    abstract fun bindingDao(): BindingDao
    abstract fun packetQueueDao(): PacketQueueDao
    abstract fun operationLogDao(): OperationLogDao
    abstract fun siteDao(): SiteDao
    abstract fun shiftContextDao(): ShiftContextDao
}
```

### 3.2. Таблица `employees` — Кэш сотрудников

> Данные загружаются с сервера (`GET /api/v1/employees`) и кэшируются для оффлайн-работы.

| Поле | Тип | Описание |
|------|-----|----------|
| `id` | TEXT, PK | UUID сотрудника (с сервера) |
| `full_name` | TEXT, NOT NULL | ФИО |
| `company_id` | TEXT, NULLABLE | UUID компании |
| `company_name` | TEXT, NULLABLE | Название компании (денормализация для оффлайн) |
| `position` | TEXT, NULLABLE | Должность |
| `pass_number` | TEXT, NULLABLE | Номер пропуска (RFID) |
| `personnel_number` | TEXT, NULLABLE, UNIQUE | Табельный номер |
| `brigade_id` | TEXT, NULLABLE | UUID бригады |
| `brigade_name` | TEXT, NULLABLE | Название бригады |
| `site_id` | TEXT, NULLABLE | Площадка по умолчанию |
| `status` | TEXT, NOT NULL | 'active', 'inactive', 'archived' |
| `synced_at` | INTEGER, NOT NULL | Время последней синхронизации (Unix ms) |

**Индексы:**
- `idx_employees_personnel_number` ON `(personnel_number)`
- `idx_employees_pass_number` ON `(pass_number)`
- `idx_employees_site_id` ON `(site_id)`
- `idx_employees_full_name` ON `(full_name)` — для поиска

```kotlin
@Entity(
    tableName = "employees",
    indices = [
        Index("personnel_number", unique = true),
        Index("pass_number"),
        Index("site_id"),
        Index("full_name"),
    ]
)
data class EmployeeEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "full_name") val fullName: String,
    @ColumnInfo(name = "company_id") val companyId: String? = null,
    @ColumnInfo(name = "company_name") val companyName: String? = null,
    val position: String? = null,
    @ColumnInfo(name = "pass_number") val passNumber: String? = null,
    @ColumnInfo(name = "personnel_number") val personnelNumber: String? = null,
    @ColumnInfo(name = "brigade_id") val brigadeId: String? = null,
    @ColumnInfo(name = "brigade_name") val brigadeName: String? = null,
    @ColumnInfo(name = "site_id") val siteId: String? = null,
    val status: String = "active",
    @ColumnInfo(name = "synced_at") val syncedAt: Long = 0L,
)

@Dao
interface EmployeeDao {
    @Query("SELECT * FROM employees WHERE site_id = :siteId AND status = 'active' ORDER BY full_name")
    fun observeBySite(siteId: String): Flow<List<EmployeeEntity>>

    @Query("SELECT * FROM employees WHERE personnel_number = :number LIMIT 1")
    suspend fun findByPersonnelNumber(number: String): EmployeeEntity?

    @Query("SELECT * FROM employees WHERE pass_number = :pass LIMIT 1")
    suspend fun findByPassNumber(pass: String): EmployeeEntity?

    @Query("SELECT * FROM employees WHERE full_name LIKE '%' || :query || '%' AND site_id = :siteId AND status = 'active'")
    suspend fun search(query: String, siteId: String): List<EmployeeEntity>

    @Upsert
    suspend fun upsertAll(employees: List<EmployeeEntity>)

    @Query("DELETE FROM employees WHERE synced_at < :before")
    suspend fun deleteStale(before: Long)
}
```

### 3.3. Таблица `devices` — Кэш смарт-часов

> Данные загружаются с сервера (`GET /api/v1/devices`) и кэшируются.

| Поле | Тип | Описание |
|------|-----|----------|
| `device_id` | TEXT, PK | ID устройства (с сервера, `dev_xxxx`) |
| `serial_number` | TEXT, NULLABLE | Серийный номер |
| `model` | TEXT, NULLABLE | Модель ("Galaxy Watch 8") |
| `status` | TEXT, NOT NULL | 'active', 'revoked', 'suspended' |
| `charge_status` | TEXT | 'charged', 'low', 'charging', 'unknown' |
| `employee_id` | TEXT, NULLABLE | Текущий сотрудник (если привязан) |
| `employee_name` | TEXT, NULLABLE | ФИО привязанного сотрудника |
| `site_id` | TEXT, NULLABLE | Площадка |
| `last_sync_at` | TEXT, NULLABLE | Последняя синхронизация (ISO 8601) |
| `local_status` | TEXT, NOT NULL | Локальный статус оператора: 'available', 'issued', 'discharged', 'faulty' |
| `synced_at` | INTEGER, NOT NULL | Время кэширования (Unix ms) |

```kotlin
@Entity(
    tableName = "devices",
    indices = [
        Index("site_id"),
        Index("local_status"),
        Index("employee_id"),
    ]
)
data class DeviceEntity(
    @PrimaryKey @ColumnInfo(name = "device_id") val deviceId: String,
    @ColumnInfo(name = "serial_number") val serialNumber: String? = null,
    val model: String? = null,
    val status: String = "active",
    @ColumnInfo(name = "charge_status") val chargeStatus: String = "unknown",
    @ColumnInfo(name = "employee_id") val employeeId: String? = null,
    @ColumnInfo(name = "employee_name") val employeeName: String? = null,
    @ColumnInfo(name = "site_id") val siteId: String? = null,
    @ColumnInfo(name = "last_sync_at") val lastSyncAt: String? = null,
    @ColumnInfo(name = "local_status") val localStatus: String = "available",
    @ColumnInfo(name = "synced_at") val syncedAt: Long = 0L,
)

@Dao
interface DeviceDao {
    @Query("SELECT * FROM devices WHERE site_id = :siteId ORDER BY device_id")
    fun observeBySite(siteId: String): Flow<List<DeviceEntity>>

    @Query("SELECT * FROM devices WHERE site_id = :siteId AND local_status = 'available' AND status = 'active'")
    suspend fun getAvailable(siteId: String): List<DeviceEntity>

    @Query("SELECT * FROM devices WHERE site_id = :siteId AND local_status = 'issued'")
    fun observeIssued(siteId: String): Flow<List<DeviceEntity>>

    @Query("SELECT * FROM devices WHERE device_id = :deviceId LIMIT 1")
    suspend fun findById(deviceId: String): DeviceEntity?

    @Upsert
    suspend fun upsertAll(devices: List<DeviceEntity>)

    @Query("UPDATE devices SET local_status = :status, employee_id = :empId, employee_name = :empName WHERE device_id = :deviceId")
    suspend fun updateLocalStatus(deviceId: String, status: String, empId: String?, empName: String?)

    @Query("SELECT COUNT(*) FROM devices WHERE site_id = :siteId AND local_status = :status")
    suspend fun countByStatus(siteId: String, status: String): Int
}
```

### 3.4. Таблица `bindings` — Привязки часы↔сотрудник (локальные + серверные)

| Поле | Тип | Описание |
|------|-----|----------|
| `id` | INTEGER, PK, AUTOINCREMENT | Локальный ID |
| `server_id` | INTEGER, NULLABLE | ID на сервере (после синхронизации) |
| `device_id` | TEXT, NOT NULL | ID часов |
| `employee_id` | TEXT, NOT NULL | UUID сотрудника |
| `employee_name` | TEXT, NOT NULL | ФИО (для оффлайн) |
| `site_id` | TEXT, NOT NULL | Площадка |
| `shift_date` | TEXT, NOT NULL | Дата смены (ISO: "2026-02-25") |
| `shift_type` | TEXT | 'day', 'night' |
| `bound_at` | INTEGER, NOT NULL | Время выдачи (Unix ms) |
| `unbound_at` | INTEGER, NULLABLE | Время возврата (Unix ms) |
| `status` | TEXT, NOT NULL | 'active', 'closed', 'lost' |
| `data_uploaded` | INTEGER, NOT NULL | 0/1 — выгружены ли данные |
| `is_synced` | INTEGER, NOT NULL | 0/1 — синхронизировано с сервером |
| `created_at` | INTEGER, NOT NULL | Время создания (Unix ms) |

```kotlin
@Entity(
    tableName = "bindings",
    indices = [
        Index("device_id", "status"),
        Index("employee_id", "shift_date"),
        Index("site_id", "shift_date"),
        Index("is_synced"),
    ]
)
data class BindingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "server_id") val serverId: Long? = null,
    @ColumnInfo(name = "device_id") val deviceId: String,
    @ColumnInfo(name = "employee_id") val employeeId: String,
    @ColumnInfo(name = "employee_name") val employeeName: String,
    @ColumnInfo(name = "site_id") val siteId: String,
    @ColumnInfo(name = "shift_date") val shiftDate: String,
    @ColumnInfo(name = "shift_type") val shiftType: String = "day",
    @ColumnInfo(name = "bound_at") val boundAt: Long,
    @ColumnInfo(name = "unbound_at") val unboundAt: Long? = null,
    val status: String = "active",
    @ColumnInfo(name = "data_uploaded") val dataUploaded: Boolean = false,
    @ColumnInfo(name = "is_synced") val isSynced: Boolean = false,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Dao
interface BindingDao {
    @Query("SELECT * FROM bindings WHERE site_id = :siteId AND shift_date = :date ORDER BY bound_at DESC")
    fun observeByShift(siteId: String, date: String): Flow<List<BindingEntity>>

    @Query("SELECT * FROM bindings WHERE status = 'active' AND site_id = :siteId")
    fun observeActive(siteId: String): Flow<List<BindingEntity>>

    @Query("SELECT * FROM bindings WHERE device_id = :deviceId AND status = 'active' LIMIT 1")
    suspend fun findActiveByDevice(deviceId: String): BindingEntity?

    @Query("SELECT * FROM bindings WHERE employee_id = :empId AND status = 'active' LIMIT 1")
    suspend fun findActiveByEmployee(empId: String): BindingEntity?

    @Insert
    suspend fun insert(binding: BindingEntity): Long

    @Update
    suspend fun update(binding: BindingEntity)

    @Query("UPDATE bindings SET status = 'closed', unbound_at = :unboundAt, is_synced = 0 WHERE id = :id")
    suspend fun closeBinding(id: Long, unboundAt: Long)

    @Query("UPDATE bindings SET data_uploaded = 1 WHERE id = :id")
    suspend fun markDataUploaded(id: Long)

    @Query("SELECT * FROM bindings WHERE is_synced = 0")
    suspend fun getUnsynced(): List<BindingEntity>
}
```

### 3.5. Таблица `packet_queue` — Очередь пакетов на отправку

> Зашифрованные пакеты, считанные с часов, ожидающие отправки на сервер.

| Поле | Тип | Описание |
|------|-----|----------|
| `packet_id` | TEXT, PK | UUID пакета (из часов) |
| `device_id` | TEXT, NOT NULL | ID часов |
| `employee_id` | TEXT, NULLABLE | UUID сотрудника |
| `binding_id` | INTEGER, NULLABLE | Локальный ID привязки |
| `site_id` | TEXT, NOT NULL | Площадка |
| `shift_start_ts` | INTEGER, NOT NULL | Начало смены (Unix ms) |
| `shift_end_ts` | INTEGER, NOT NULL | Конец смены (Unix ms) |
| `schema_version` | INTEGER, NOT NULL | Версия схемы пакета |
| `payload_enc` | TEXT, NOT NULL | Зашифрованный payload (Base64) |
| `payload_key_enc` | TEXT, NOT NULL | Зашифрованный AES-ключ (Base64) |
| `iv` | TEXT, NOT NULL | IV для AES-GCM (Base64) |
| `payload_hash` | TEXT, NOT NULL | SHA-256 от plaintext |
| `payload_size_bytes` | INTEGER | Размер payload |
| `status` | TEXT, NOT NULL | 'pending', 'uploading', 'uploaded', 'error' |
| `attempt` | INTEGER, NOT NULL | Количество попыток отправки |
| `last_error` | TEXT, NULLABLE | Последняя ошибка |
| `server_status` | TEXT, NULLABLE | Статус от сервера ('accepted', 'processed', 'error') |
| `created_at` | INTEGER, NOT NULL | Время создания (Unix ms) |
| `uploaded_at` | INTEGER, NULLABLE | Время успешной отправки (Unix ms) |

```kotlin
@Entity(
    tableName = "packet_queue",
    indices = [
        Index("status"),
        Index("device_id"),
        Index("site_id"),
        Index("created_at"),
    ]
)
data class PacketQueueEntity(
    @PrimaryKey @ColumnInfo(name = "packet_id") val packetId: String,
    @ColumnInfo(name = "device_id") val deviceId: String,
    @ColumnInfo(name = "employee_id") val employeeId: String? = null,
    @ColumnInfo(name = "binding_id") val bindingId: Long? = null,
    @ColumnInfo(name = "site_id") val siteId: String,
    @ColumnInfo(name = "shift_start_ts") val shiftStartTs: Long,
    @ColumnInfo(name = "shift_end_ts") val shiftEndTs: Long,
    @ColumnInfo(name = "schema_version") val schemaVersion: Int = 1,
    @ColumnInfo(name = "payload_enc") val payloadEnc: String,
    @ColumnInfo(name = "payload_key_enc") val payloadKeyEnc: String,
    val iv: String,
    @ColumnInfo(name = "payload_hash") val payloadHash: String,
    @ColumnInfo(name = "payload_size_bytes") val payloadSizeBytes: Int? = null,
    val status: String = "pending",
    val attempt: Int = 0,
    @ColumnInfo(name = "last_error") val lastError: String? = null,
    @ColumnInfo(name = "server_status") val serverStatus: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "uploaded_at") val uploadedAt: Long? = null,
)

@Dao
interface PacketQueueDao {
    @Query("SELECT * FROM packet_queue ORDER BY created_at DESC")
    fun observeAll(): Flow<List<PacketQueueEntity>>

    @Query("SELECT * FROM packet_queue WHERE status = 'pending' ORDER BY created_at ASC")
    suspend fun getPending(): List<PacketQueueEntity>

    @Query("SELECT COUNT(*) FROM packet_queue WHERE status = 'pending'")
    fun observePendingCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM packet_queue WHERE status = 'error'")
    fun observeErrorCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun enqueue(packet: PacketQueueEntity)

    @Query("UPDATE packet_queue SET status = :status, attempt = :attempt, last_error = :error WHERE packet_id = :packetId")
    suspend fun updateStatus(packetId: String, status: String, attempt: Int, error: String?)

    @Query("UPDATE packet_queue SET status = 'uploaded', server_status = :serverStatus, uploaded_at = :uploadedAt WHERE packet_id = :packetId")
    suspend fun markUploaded(packetId: String, serverStatus: String, uploadedAt: Long)

    @Query("DELETE FROM packet_queue WHERE status = 'uploaded' AND uploaded_at < :before")
    suspend fun cleanupUploaded(before: Long)

    @Query("SELECT * FROM packet_queue WHERE status IN ('pending', 'error') AND site_id = :siteId")
    fun observeUnsent(siteId: String): Flow<List<PacketQueueEntity>>
}
```

### 3.6. Таблица `operation_log` — Журнал операций оператора

| Поле | Тип | Описание |
|------|-----|----------|
| `id` | INTEGER, PK, AUTOINCREMENT | ID |
| `type` | TEXT, NOT NULL | 'issue', 'return', 'upload', 'upload_error', 'sync', 'status_change' |
| `device_id` | TEXT, NULLABLE | ID часов |
| `employee_id` | TEXT, NULLABLE | UUID сотрудника |
| `employee_name` | TEXT, NULLABLE | ФИО |
| `site_id` | TEXT, NOT NULL | Площадка |
| `shift_date` | TEXT, NOT NULL | Дата смены |
| `details` | TEXT, NULLABLE | JSON с дополнительными данными |
| `status` | TEXT, NOT NULL | 'success', 'error', 'pending' |
| `error_message` | TEXT, NULLABLE | Текст ошибки |
| `is_synced` | INTEGER, NOT NULL | 0/1 |
| `created_at` | INTEGER, NOT NULL | Время (Unix ms) |

```kotlin
@Entity(
    tableName = "operation_log",
    indices = [
        Index("site_id", "shift_date"),
        Index("type"),
        Index("device_id"),
        Index("employee_id"),
        Index("created_at"),
    ]
)
data class OperationLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,
    @ColumnInfo(name = "device_id") val deviceId: String? = null,
    @ColumnInfo(name = "employee_id") val employeeId: String? = null,
    @ColumnInfo(name = "employee_name") val employeeName: String? = null,
    @ColumnInfo(name = "site_id") val siteId: String,
    @ColumnInfo(name = "shift_date") val shiftDate: String,
    val details: String? = null,
    val status: String = "success",
    @ColumnInfo(name = "error_message") val errorMessage: String? = null,
    @ColumnInfo(name = "is_synced") val isSynced: Boolean = false,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Dao
interface OperationLogDao {
    @Query(
        "SELECT * FROM operation_log WHERE site_id = :siteId AND shift_date = :date ORDER BY created_at DESC"
    )
    fun observeByShift(siteId: String, date: String): Flow<List<OperationLogEntity>>

    @Query(
        "SELECT * FROM operation_log WHERE site_id = :siteId ORDER BY created_at DESC LIMIT :limit"
    )
    fun observeRecent(siteId: String, limit: Int = 100): Flow<List<OperationLogEntity>>

    @Insert
    suspend fun insert(log: OperationLogEntity): Long

    @Query("DELETE FROM operation_log WHERE created_at < :before")
    suspend fun deleteOlderThan(before: Long)
}
```

### 3.7. Таблица `sites` — Кэш площадок

```kotlin
@Entity(tableName = "sites")
data class SiteEntity(
    @PrimaryKey val id: String,
    val name: String,
    val address: String? = null,
    val timezone: String = "Europe/Moscow",
    val status: String = "active",
    @ColumnInfo(name = "synced_at") val syncedAt: Long = 0L,
)

@Dao
interface SiteDao {
    @Query("SELECT * FROM sites WHERE status = 'active' ORDER BY name")
    fun observeAll(): Flow<List<SiteEntity>>

    @Query("SELECT * FROM sites WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): SiteEntity?

    @Upsert
    suspend fun upsertAll(sites: List<SiteEntity>)
}
```

### 3.8. Таблица `shift_context` — Текущий контекст работы оператора

> Хранит выбранный контекст: площадка + дата + тип смены. Singleton-запись (id = 1).

```kotlin
@Entity(tableName = "shift_context")
data class ShiftContextEntity(
    @PrimaryKey val id: Int = 1,
    @ColumnInfo(name = "site_id") val siteId: String,
    @ColumnInfo(name = "site_name") val siteName: String,
    @ColumnInfo(name = "shift_date") val shiftDate: String,
    @ColumnInfo(name = "shift_type") val shiftType: String = "day",
    @ColumnInfo(name = "operator_id") val operatorId: String,
    @ColumnInfo(name = "operator_name") val operatorName: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long = 0L,
)
```

### 3.9. DataStore — Пользовательские настройки и токены

```kotlin
data class UserPreferences(
    val userId: String = "",
    val userEmail: String = "",
    val userName: String = "",
    val userRole: String = "",
    val scopeType: String = "",
    val scopeIds: List<String> = emptyList(),
    val isLoggedIn: Boolean = false,
    val lastSyncTimestamp: Long = 0L,
    val serverBaseUrl: String = "",
)
```

**Правила хранения:**
- `accessToken` и `refreshToken` — **только** в `EncryptedSharedPreferences` или Android Keystore, НЕ в DataStore.
- Остальные поля `UserPreferences` — в Jetpack DataStore (Proto или Preferences).
- Пакеты смены хранятся в Room (`packet_queue`), **не** на файловой системе.

### 3.10. Политики хранения и очистки

| Данные | Срок хранения | Условие очистки |
|--------|---------------|-----------------|
| Кэш сотрудников | Обновляется при каждом открытии (если есть сеть) | `synced_at < now() - 7 дней` |
| Кэш устройств | Аналогично | Аналогично |
| Очередь пакетов | До подтверждённой отправки + 30 дней | `status='uploaded' AND uploaded_at < now() - 30 дней` |
| Журнал операций | 90 дней | `created_at < now() - 90 дней` |
| Привязки | 90 дней | `status='closed' AND created_at < now() - 90 дней` |

---

## 4. API-интеграция с Backend

### 4.1. Базовая конфигурация HTTP-клиента

```kotlin
object NetworkConfig {
    const val BASE_URL = "https://api.activity-tracker.example.com"
    const val CONNECT_TIMEOUT_SEC = 30L
    const val READ_TIMEOUT_SEC = 60L
    const val WRITE_TIMEOUT_SEC = 120L
    const val MAX_RETRIES = 3
}
```

**Интерцепторы:**
- `AuthInterceptor` — добавляет `Authorization: Bearer <access_token>` ко всем запросам (кроме login/refresh).
- `TokenRefreshInterceptor` — при 401 автоматически обновляет токен через `POST /api/v1/auth/refresh` и повторяет запрос.
- Логирование запросов/ответов (debug-режим).

### 4.2. Аутентификация оператора (Web JWT)

#### 4.2.1. `POST /api/v1/auth/login` — Вход оператора

**Request:**
```json
{
  "email": "operator@example.com",
  "password": "securePassword123"
}
```

**Response 200:**
```json
{
  "access_token": "eyJhbGciOiJIUzI1NiIs...",
  "refresh_token": "ref_xxxxx",
  "token_type": "Bearer",
  "expires_in": 28800,
  "user": {
    "id": "uuid",
    "email": "operator@example.com",
    "full_name": "Петров П.П.",
    "role": "operator",
    "scope_type": "site",
    "scope_ids": ["site_01", "site_02"]
  }
}
```

**Response 401:** Неверный логин/пароль.
**Response 423:** Аккаунт заблокирован (5 неудачных попыток).

**Логика в приложении:**
1. Отправить email + password.
2. Сохранить `access_token` и `refresh_token` в `EncryptedSharedPreferences`.
3. Сохранить данные пользователя в `DataStore`.
4. Перейти на экран выбора контекста (площадка + смена).

#### 4.2.2. `POST /api/v1/auth/refresh` — Обновление токена

**Request:**
```json
{
  "refresh_token": "ref_xxxxx"
}
```

**Response 200:** Новая пара `access_token` + `refresh_token`.

#### 4.2.3. `GET /api/v1/auth/me` — Профиль текущего пользователя

Используется при запуске приложения для валидации сессии.

### 4.3. GATEWAY-эндпоинт — Приём пакета от шлюза (КЛЮЧЕВОЙ)

#### 4.3.1. `POST /api/v1/gateway/packets` — Отправка зашифрованного пакета

> Соответствует разделу 23 BACKEND_SPEC.md.

**Доступ:** `Authorization: Bearer <web_token>` (роль: `operator`).

**Request Body:**
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
  "payload_hash": "sha256-hex-of-original-plaintext-payload",

  "operator_id": "uuid-оператора",
  "site_id": "site_01",
  "employee_id": "uuid-сотрудника",
  "binding_id": 1234,
  "uploaded_from": "gateway",
  "gateway_device_info": {
    "model": "Samsung Galaxy Tab A9",
    "os_version": "Android 15",
    "app_version": "1.0.0"
  }
}
```

**Response 202 Accepted:**
```json
{
  "packet_id": "550e8400-e29b-41d4-a716-446655440000",
  "status": "accepted",
  "server_time": "2026-02-25T14:30:00.000Z"
}
```

**Response 409 Conflict:** Пакет уже принят (идемпотентность).
**Response 400:** Ошибка валидации.
**Response 401:** Невалидный/истёкший токен.
**Response 403:** Оператор не имеет доступа к `site_id`.

**Логика в приложении:**
1. Считать зашифрованный пакет с часов по Bluetooth.
2. Сохранить в `packet_queue` (Room) со статусом `pending`.
3. Попытаться отправить на сервер немедленно.
4. При 202 → обновить статус на `uploaded`.
5. При 409 → считать успехом (идемпотентность).
6. При 4xx/5xx → сохранить ошибку, оставить в очереди для повтора.
7. При отсутствии сети → WorkManager отправит при появлении.

**Идемпотентность:**
- `Idempotency-Key` header = `packet_id`.
- Повторная отправка безопасна — сервер возвращает 409 без дублирования.

#### 4.3.2. `GET /api/v1/watch/packets/{packet_id}` — Проверка статуса пакета

**Response 200:**
```json
{
  "packet_id": "550e8400-...",
  "status": "processed"
}
```

Используется для отображения финального статуса обработки пакета в журнале.

### 4.4. Справочники (кэширование для оффлайн)

#### 4.4.1. `GET /api/v1/employees` — Список сотрудников

**Query:** `site_id`, `status=active`, `page`, `page_size=200`

Приложение загружает всех активных сотрудников площадки и сохраняет в Room. Пагинация: загрузка по 200 записей до исчерпания.

#### 4.4.2. `GET /api/v1/devices` — Список устройств

**Query:** `status=active`, `site_id` (по scope оператора), `page`, `page_size=100`

#### 4.4.3. `GET /api/v1/sites` — Список площадок

Загружает площадки, доступные оператору (по `scope_ids`).

#### 4.4.4. `GET /api/v1/downtime-reasons` — Справочник причин простоя

Кэшируется для передачи на часы при начале смены (GATEWAY).

### 4.5. Привязки часов

#### 4.5.1. `POST /api/v1/bindings` — Создать привязку (выдача часов)

**Request:**
```json
{
  "device_id": "dev_a1b2c3d4e5f6",
  "employee_id": "uuid",
  "site_id": "site_01",
  "shift_date": "2026-02-25",
  "shift_type": "day"
}
```

**Response 201:** Привязка создана (возвращает `id`).
**Response 409:** Часы уже выданы / сотрудник уже имеет активную привязку.

#### 4.5.2. `PUT /api/v1/bindings/{id}/close` — Закрыть привязку (возврат)

**Response 200:** Привязка закрыта.

#### 4.5.3. `GET /api/v1/bindings` — История привязок

**Query:** `device_id`, `employee_id`, `date`, `site_id`

### 4.6. Маппинг DTO ↔ Entity ↔ Domain

```
Сервер JSON → DTO (Kotlinx Serialization) → Mapper → Entity (Room) → Mapper → Domain Model → UI State
```

| Слой | Класс | Пример |
|------|-------|--------|
| **DTO** | `EmployeeDto` | `@Serializable`, snake_case, nullable |
| **Entity** | `EmployeeEntity` | `@Entity`, Room-аннотации |
| **Domain** | `Employee` | Чистая `data class`, бизнес-правила |

```kotlin
@Serializable
data class EmployeeDto(
    val id: String,
    @SerialName("full_name") val fullName: String,
    @SerialName("company_id") val companyId: String? = null,
    @SerialName("company_name") val companyName: String? = null,
    val position: String? = null,
    @SerialName("pass_number") val passNumber: String? = null,
    @SerialName("personnel_number") val personnelNumber: String? = null,
    @SerialName("brigade_id") val brigadeId: String? = null,
    @SerialName("brigade_name") val brigadeName: String? = null,
    @SerialName("site_id") val siteId: String? = null,
    val status: String = "active",
)

fun EmployeeDto.toEntity(syncedAt: Long): EmployeeEntity =
    EmployeeEntity(
        id = id,
        fullName = fullName,
        companyId = companyId,
        companyName = companyName,
        position = position,
        passNumber = passNumber,
        personnelNumber = personnelNumber,
        brigadeId = brigadeId,
        brigadeName = brigadeName,
        siteId = siteId,
        status = status,
        syncedAt = syncedAt,
    )

fun EmployeeEntity.toDomain(): Employee =
    Employee(
        id = id,
        fullName = fullName,
        companyName = companyName,
        position = position,
        passNumber = passNumber,
        personnelNumber = personnelNumber,
        brigadeName = brigadeName,
        siteId = siteId,
    )
```

### 4.7. Обработка ошибок сети

| HTTP-код | Поведение приложения |
|----------|---------------------|
| 200-202 | Успех, обновить локальные данные |
| 400 | Показать ошибку валидации, НЕ повторять |
| 401 | Попытаться refresh token; если не помогло → экран логина |
| 403 | Показать "Нет доступа", НЕ повторять |
| 409 | Для пакетов — считать успехом (идемпотентность) |
| 422 | Показать ошибку, НЕ повторять |
| 429 | Rate limit — повторить после Retry-After |
| 5xx | Поставить в очередь, повторить с экспоненциальным backoff |
| Нет сети | Поставить в очередь, WorkManager отправит при появлении |

---

## 5. Bluetooth-протокол обмена с часами (GATEWAY)

### 5.1. Общая схема

```
[Часы (Wear OS)]          [Мобильное приложение]
      │                            │
      │  ◄── BLE Scan/Connect ──── │  (1) Оператор нажимает "Подключить"
      │                            │
      │  ◄── GATT Service ──────── │  (2) Подключение по BLE GATT
      │                            │
      │  ────── Shift Context ───► │  (3) Начало смены: передать контекст
      │   (employee_id, site_id,   │
      │    shift_date, mode=GATEWAY)│
      │                            │
      │       ... СМЕНА ...        │
      │                            │
      │  ◄── Request Packet ────── │  (4) Конец смены: запросить пакет
      │                            │
      │  ────── Shift Packet ───► │  (5) Часы передают зашифрованный пакет
      │   (чанками по BLE MTU)     │       по чанкам
      │                            │
      │  ◄── ACK ──────────────── │  (6) Подтверждение приёма
      │                            │
```

### 5.2. BLE GATT Service (на часах)

> Протокол определяется Wear OS приложением. Мобильное приложение подключается как GATT Client.

**Service UUID:** `0000ff01-0000-1000-8000-00805f9b34fb` (пример, фиксируется при реализации)

**Characteristics:**

| Характеристика | UUID | Свойства | Описание |
|----------------|------|----------|----------|
| Shift Context | `0000ff02-...` | Write | Передача контекста смены на часы |
| Packet Request | `0000ff03-...` | Write | Запрос пакета с часов |
| Packet Data | `0000ff04-...` | Read, Notify | Чанки зашифрованного пакета |
| Packet Meta | `0000ff05-...` | Read | Метаданные пакета (packet_id, size, hash) |
| Status | `0000ff06-...` | Read, Notify | Статус часов (collecting, idle, error) |
| ACK | `0000ff07-...` | Write | Подтверждение приёма пакета |

### 5.3. Протокол начала смены (Gateway → Watch)

**Шаг 1: Подключение**
1. Мобильное приложение сканирует BLE-устройства (`BluetoothLeScanner`).
2. Находит часы по `device_id` (из справочника) или по advertised service UUID.
3. Устанавливает GATT-соединение.

**Шаг 2: Передача контекста смены**

Записываем в характеристику `Shift Context` JSON:

```json
{
  "command": "start_shift",
  "shift_id": "uuid",
  "employee_id": "uuid",
  "site_id": "site_01",
  "start_ts_ms": 1700000000000,
  "planned_end_ts_ms": 1700043200000,
  "mode": "GATEWAY",
  "downtime_reasons": [
    { "id": "wait_tools", "name": "Жду инструмент" },
    { "id": "wait_material", "name": "Жду материал" },
    { "id": "no_task", "name": "Нет задания" }
  ]
}
```

**Ответ часов** (через Status characteristic):
```json
{
  "status": "ok",
  "message": "Shift context saved, collection started"
}
```

### 5.4. Протокол конца смены (Watch → Gateway)

**Шаг 1: Запрос метаданных пакета**

Записываем в `Packet Request`:
```json
{ "command": "get_packet" }
```

Читаем из `Packet Meta`:
```json
{
  "packet_id": "550e8400-...",
  "device_id": "dev_a1b2c3d4e5f6",
  "shift_start_ts": 1700000000000,
  "shift_end_ts": 1700043200000,
  "schema_version": 1,
  "payload_hash": "sha256hex...",
  "total_chunks": 150,
  "chunk_size": 512,
  "total_size_bytes": 76800,
  "payload_key_enc": "Base64...",
  "iv": "Base64..."
}
```

**Шаг 2: Считывание пакета чанками**

Подписываемся на `Packet Data` (Notify). Часы отправляют чанки:

```
Chunk format: [2 bytes: chunk_index] [N bytes: data]
```

- MTU: 512 байт (запрашиваем максимальный)
- Приложение собирает чанки в буфер до `total_chunks`.
- При потере чанка — запрос повторной отправки через `Packet Request`.

**Шаг 3: Верификация**

1. Собрать `payload_enc` из чанков (Base64).
2. Проверить `payload_hash` на стороне мобильного приложения невозможно (данные зашифрованы).
3. Проверить целостность по количеству чанков и размеру.

**Шаг 4: Подтверждение (ACK)**

Записываем в `ACK`:
```json
{
  "packet_id": "550e8400-...",
  "status": "received",
  "chunks_received": 150
}
```

Часы помечают пакет как выгруженный. При отсутствии ACK — пакет остаётся на часах для повторной выгрузки.

### 5.5. Обработка ошибок BLE

| Ситуация | Поведение |
|----------|-----------|
| Обрыв соединения при передаче | Показать ошибку, предложить повторить. Часы хранят пакет. |
| Часы не найдены при сканировании | "Часы не обнаружены. Убедитесь, что часы включены и находятся рядом." |
| Таймаут подключения (>30 сек) | Отменить, предложить повторить |
| Неполный пакет (chunks_received < total_chunks) | НЕ отправлять ACK, запросить повтор |
| Ошибка GATT (статус != 0) | Логировать, показать оператору "Ошибка Bluetooth, код N" |

### 5.6. Разрешения Android для Bluetooth

```xml
<uses-permission android:name="android.permission.BLUETOOTH_SCAN"
    android:usesPermissionFlags="neverForLocation" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />

<uses-feature android:name="android.hardware.bluetooth_le"
    android:required="true" />
```

**Runtime-запрос:**
- Android 12+ (API 31+): `BLUETOOTH_SCAN` + `BLUETOOTH_CONNECT`
- Android 10-11 (API 29-30): `ACCESS_FINE_LOCATION` + `BLUETOOTH_ADMIN`

---

## 6. Функциональные модули (детальная логика)

### 6.1. Авторизация и выбор контекста

#### 6.1.1. Вход в систему

1. Оператор вводит email + пароль.
2. Приложение вызывает `POST /api/v1/auth/login`.
3. При успехе — сохраняет токены и данные пользователя.
4. При ошибке — отображает причину (неверный пароль / аккаунт заблокирован).
5. При 5 неудачных попытках — показать "Обратитесь к администратору".

#### 6.1.2. Выбор контекста работы

После входа оператор **обязан** выбрать:
- **Площадку** — из списка доступных (по `scope_ids`).
- **Дату смены** — по умолчанию сегодня.
- **Тип смены** — дневная / ночная (по умолчанию дневная).

Без выбранного контекста операции выдачи/возврата/выгрузки **запрещены**.

Контекст сохраняется в `shift_context` (Room) и действует до явной смены или выхода.

#### 6.1.3. Автоматический вход

При запуске приложения:
1. Проверить наличие `access_token`.
2. Вызвать `GET /api/v1/auth/me` для валидации.
3. Если токен валиден → пропустить экран логина, загрузить сохранённый контекст.
4. Если 401 → попробовать `refresh`. Если не помогло → экран логина.

### 6.2. Выдача часов и привязка к сотруднику

#### 6.2.1. Идентификация сотрудника

Оператор идентифицирует сотрудника одним из способов:
- **Табельный номер** — ввод вручную (NumPad).
- **RFID-пропуск** — сканирование NFC (если устройство поддерживает).
- **Поиск по ФИО** — текстовый поиск из кэшированного списка.

Результат: карточка сотрудника с ФИО, должностью, компанией, бригадой.

#### 6.2.2. Назначение часов

Режимы назначения (настраиваемые):
- **По очереди** — первые свободные часы из списка.
- **Случайно** — случайный выбор из доступных.
- **Вручную** — оператор выбирает конкретные часы (по device_id / QR).

**Валидации перед выдачей:**
1. Часы доступны (`local_status = 'available'`, `status = 'active'`).
2. Сотрудник не имеет активной привязки на эту смену.
3. Часы не привязаны к другому сотруднику.

**При нарушении:** показать причину ("Часы уже выданы Иванову И.И." / "Сотрудник уже получил часы dev_xxx").

#### 6.2.3. Процесс выдачи

```
1. Идентифицировать сотрудника
2. Назначить часы (авто или вручную)
3. Оператор нажимает "Выдать"
4. Создать локальную привязку (Room: bindings, status='active')
5. Обновить статус часов (local_status='issued')
6. Записать в журнал операций (type='issue')
7. [Если есть сеть] Отправить на сервер: POST /api/v1/bindings
8. [GATEWAY] Подключиться к часам по BLE, передать shift context
9. Показать подтверждение
```

#### 6.2.4. Начало смены (GATEWAY — передача контекста на часы)

После выдачи часов оператор может передать контекст смены:
1. Нажать "Начать смену" (или автоматически при выдаче).
2. Подключиться к часам по BLE (раздел 5.3).
3. Передать `shift_id`, `employee_id`, `site_id`, `mode=GATEWAY`, `downtime_reasons`.
4. Часы начинают сбор данных.
5. Показать статус "Смена начата" / "Ошибка подключения".

### 6.3. Возврат часов и закрытие смены

#### 6.3.1. Выбор часов для возврата

Способы:
- Из списка выданных часов (экран "Выданные").
- По device_id / QR-коду.
- По сотруднику (найти привязку).

#### 6.3.2. Процесс возврата

```
1. Выбрать часы (или сотрудника)
2. Инициировать выгрузку данных (раздел 6.4)
3. [Опционально] Дождаться завершения выгрузки
4. Оператор нажимает "Принять часы"
5. Закрыть привязку (Room: bindings.status='closed', unbound_at=now())
6. Обновить статус часов (local_status='available')
7. Записать в журнал (type='return')
8. [Если есть сеть] PUT /api/v1/bindings/{id}/close
```

#### 6.3.3. Исключения при возврате

| Ситуация | Действие |
|----------|----------|
| Часы не найдены | "Часы не найдены в списке выданных" |
| Привязка отсутствует | "Нет активной привязки для этих часов" |
| Данные не выгружены | Предупреждение: "Данные ещё не выгружены. Продолжить без выгрузки?" |
| Часы потеряны | Кнопка "Пометить как потерянные" → `status='lost'` |

### 6.4. Выгрузка данных с часов (BLE → Server)

#### 6.4.1. Полный процесс выгрузки

```
1. Оператор нажимает "Выгрузить данные" (для конкретных часов)
2. Сканирование BLE-устройств
3. Подключение к часам по GATT (раздел 5)
4. Считывание метаданных пакета
5. Считывание зашифрованного пакета чанками
   → Прогресс-бар: "Считывание... 45/150 чанков"
6. Верификация целостности (количество чанков, размер)
7. Отправка ACK на часы
8. Сохранение в packet_queue (Room, status='pending')
9. Записать в журнал (type='upload')
10. Попытка отправки на сервер:
    → POST /api/v1/gateway/packets
    → При 202: status='uploaded', обновить журнал
    → При ошибке: status='error', оставить в очереди
11. Обновить привязку: data_uploaded=true
12. Показать результат оператору
```

#### 6.4.2. Прогресс выгрузки (UI)

```
Состояние: "Подключение к часам..." → "Считывание данных (45%)" →
           "Отправка на сервер..." → "Готово ✓" / "Ошибка ✗"
```

#### 6.4.3. Повторная выгрузка

- При обрыве BLE → "Повторить считывание".
- При ошибке сервера → пакет в очереди, повторная отправка автоматически.
- При повторном считывании того же пакета → идемпотентность по `packet_id`.

### 6.5. Оффлайн-режим и очереди

#### 6.5.1. Принцип Offline-First

Все операции выполняются **сначала локально**, затем синхронизируются с сервером:
- **Привязки** → сохраняются в Room, затем `POST /api/v1/bindings`.
- **Возвраты** → аналогично, затем `PUT /api/v1/bindings/{id}/close`.
- **Пакеты** → сохраняются в `packet_queue`, затем `POST /api/v1/gateway/packets`.
- **Журнал** → записи создаются локально, синхронизация опциональна.

#### 6.5.2. WorkManager задачи

**SyncPacketsWorker** — отправка неотправленных пакетов:
```kotlin
class SyncPacketsWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val pending = packetQueueDao.getPending()
        for (packet in pending) {
            val result = gatewayApi.uploadPacket(packet.toRequest())
            when {
                result.isSuccess -> {
                    packetQueueDao.markUploaded(
                        packet.packetId,
                        result.body.status,
                        System.currentTimeMillis(),
                    )
                }
                result.code == 409 -> {
                    packetQueueDao.markUploaded(
                        packet.packetId,
                        "accepted",
                        System.currentTimeMillis(),
                    )
                }
                result.code in 400..499 -> {
                    packetQueueDao.updateStatus(
                        packet.packetId, "error",
                        packet.attempt + 1, result.errorMessage,
                    )
                }
                else -> return Result.retry()
            }
        }
        return Result.success()
    }
}
```

**Constraints:**
```kotlin
val constraints = Constraints.Builder()
    .setRequiredNetworkType(NetworkType.CONNECTED)
    .build()

val request = PeriodicWorkRequestBuilder<SyncPacketsWorker>(
    15, TimeUnit.MINUTES,
).setConstraints(constraints)
    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
    .build()

WorkManager.getInstance(context)
    .enqueueUniquePeriodicWork(
        "sync_packets",
        ExistingPeriodicWorkPolicy.KEEP,
        request,
    )
```

**SyncReferenceDataWorker** — обновление справочников:
- Запускается при запуске приложения и периодически (каждые 4 часа).
- Обновляет: сотрудники, устройства, площадки, причины простоя.

#### 6.5.3. Индикация оффлайн-состояния

- Баннер вверху экрана: "Нет подключения к интернету" (красный).
- Счётчик: "3 пакета в очереди на отправку".
- При появлении сети — автоматическая синхронизация + уведомление "Синхронизировано".

#### 6.5.4. Конфликты синхронизации

| Конфликт | Решение |
|----------|---------|
| Привязка создана оффлайн, сервер отвечает 409 | Обновить `server_id` из ответа, пометить как синхронизированную |
| Часы уже привязаны другим оператором | Показать "Конфликт привязки — обратитесь к администратору" |
| Справочник изменился на сервере | Полная перезагрузка кэша |

### 6.6. Журнал операций

#### 6.6.1. Типы записей

| Тип | Описание | Статусы |
|-----|----------|---------|
| `issue` | Выдача часов сотруднику | success / error |
| `return` | Возврат часов | success / error |
| `upload` | Выгрузка данных с часов | success / error / pending |
| `upload_error` | Ошибка отправки на сервер | error |
| `sync` | Синхронизация очереди | success / error |
| `status_change` | Смена статуса часов (разряжены/неисправны) | success |

#### 6.6.2. Фильтрация

- По дате / диапазону дат.
- По сотруднику (ФИО / табельный).
- По устройству (device_id).
- По типу операции.
- По статусу (success / error / pending).

---

## 7. UI/UX — Экраны и навигация (Jetpack Compose + Material 3)

### 7.1. Навигация

```
Login → Context Selection → Home (Bottom Nav)
                              ├── Выдача (Issue)
                              ├── Возврат (Return)
                              ├── Выгрузка (Upload)
                              ├── Журнал (Journal)
                              └── Ещё (More)
                                    ├── Часы на площадке (Devices)
                                    ├── Сводка (Summary)
                                    ├── События (Events)
                                    └── Настройки (Settings)
```

### 7.2. Экран: Вход в систему (`LoginScreen`)

**Элементы:**
- Логотип системы
- Поле email (keyboardType = Email)
- Поле пароль (keyboardType = Password, видимость)
- Кнопка "Войти" (крупная, Material 3 FilledButton)
- Сообщение об ошибке (красный текст)
- Индикатор загрузки (CircularProgressIndicator)

**MVI Contract:**
```kotlin
data class LoginState(
    val email: String = "",
    val password: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
)

sealed interface LoginIntent {
    data class EmailChanged(val value: String) : LoginIntent
    data class PasswordChanged(val value: String) : LoginIntent
    data object LoginClicked : LoginIntent
}

sealed interface LoginEffect {
    data object NavigateToContextSelection : LoginEffect
}
```

### 7.3. Экран: Выбор контекста (`ContextSelectionScreen`)

**Элементы:**
- Выпадающий список площадок (ExposedDropdownMenu)
- Выбор даты (DatePicker, по умолчанию сегодня)
- Переключатель "Дневная / Ночная" (SegmentedButton)
- Кнопка "Начать работу"

### 7.4. Экран: Главная (`HomeScreen`)

**Bottom Navigation Bar (5 вкладок):**
1. **Выдача** — иконка часов с плюсом
2. **Возврат** — иконка часов с галочкой
3. **Выгрузка** — иконка облака со стрелкой
4. **Журнал** — иконка списка
5. **Ещё** — иконка меню (три точки)

**Top Bar:**
- Название площадки + дата смены.
- Индикатор сети (зелёный/красный).
- Счётчик неотправленных пакетов (Badge).
- Кнопка выхода / смены контекста.

### 7.5. Экран: Выдача часов (`IssueScreen`)

**Этап 1 — Идентификация сотрудника:**
- Поле "Табельный номер" (NumPad, крупный шрифт)
- Кнопка "NFC" (сканировать пропуск)
- Кнопка "Поиск" (переход к списку сотрудников)
- Найденный сотрудник: Card с ФИО, должность, компания, бригада

**Этап 2 — Назначение часов:**
- Автоматически предложенные часы (Card с device_id, модель)
- Кнопка "Другие часы" → список доступных
- Если нет доступных → сообщение "Все часы выданы / разряжены / неисправны"

**Этап 3 — Подтверждение:**
- Сводка: сотрудник + часы + площадка + смена
- Крупная кнопка "Выдать" (Material 3 FilledTonalButton, зелёный)
- Результат: "Часы выданы ✓" или ошибка

### 7.6. Экран: Возврат часов (`ReturnScreen`)

- Список выданных часов (LazyColumn, Cards)
- Каждая карточка: device_id, ФИО сотрудника, время выдачи, статус данных (выгружены/нет)
- Кнопка "Принять" на каждой карточке
- Индикатор "Данные не выгружены" (оранжевый) / "Данные выгружены" (зелёный)
- Диалог подтверждения при возврате без выгрузки

### 7.7. Экран: Выгрузка данных (`UploadScreen`)

- Список часов, требующих выгрузки (привязки с `data_uploaded = false`)
- Для каждых часов: кнопка "Выгрузить"
- Прогресс выгрузки:
  ```
  ● Подключение к часам...
  ● Считывание данных (67%)  ████████████░░░░
  ● Отправка на сервер...
  ✓ Готово — пакет принят сервером
  ```
- Очередь неотправленных пакетов: счётчик + кнопка "Повторить все"
- Ошибки: красная карточка с описанием и кнопкой "Повторить"

### 7.8. Экран: Журнал операций (`JournalScreen`)

- Фильтры: дата, тип, сотрудник, устройство, статус (ChipGroup)
- Список операций (LazyColumn):
  - Иконка типа (выдача/возврат/выгрузка)
  - Время, ФИО, device_id
  - Статус (Badge: зелёный/красный/оранжевый)
- Pull-to-refresh (RefreshIndicator)

### 7.9. Экран: Часы на площадке (`DeviceListScreen`)

- Фильтр по статусу: Все / Доступны / Выданы / Разряжены / Неисправны (Tabs или Chips)
- Список часов (LazyColumn, Cards):
  - device_id, модель, статус (цветной Badge)
  - Если выданы: ФИО сотрудника
  - Кнопка смены статуса (разряжены / неисправны)
- Счётчики: "Доступно: 15 | Выдано: 23 | Разряжено: 2 | Неисправно: 1"

### 7.10. Экран: Операционная сводка (`SummaryScreen`)

- Карточки с метриками за текущую смену:
  - **Выдано часов:** 23
  - **Возвращено:** 18
  - **Не возвращено:** 5
  - **Данные выгружены:** 16
  - **В очереди на отправку:** 2
  - **Ошибки выгрузки:** 0

### 7.11. Общие UI-принципы

1. **Крупные кнопки** — для работы в перчатках на площадке.
2. **Контрастные цвета** — для работы при ярком солнце.
3. **Понятные статусы** — цветовые индикаторы (зелёный/оранжевый/красный).
4. **Минимум ввода** — где возможно, использовать сканирование и списки.
5. **Ошибки** — понятные сообщения на русском + рекомендуемое действие.
6. **Адаптивность** — поддержка планшетов (двухпанельный layout для >600dp).
7. **Тёмная тема** — поддержка Material 3 dynamic color и тёмной темы.

---

## 8. Безопасность

### 8.1. Хранение секретов

| Данные | Способ хранения | Обоснование |
|--------|----------------|-------------|
| `access_token` | `EncryptedSharedPreferences` (AES-256-SIV) | Защита от доступа других приложений |
| `refresh_token` | `EncryptedSharedPreferences` | Аналогично |
| Пароль пользователя | **НЕ хранится** | Хранятся только токены |
| Зашифрованные пакеты | Room (SQLite) | Пакеты уже зашифрованы AES-256-GCM на часах |
| Данные пользователя | Jetpack DataStore | Некритичные данные (ФИО, роль, scope) |

### 8.2. Сетевая безопасность

- **HTTPS обязателен** — HTTP запрещён (`android:usesCleartextTraffic="false"`).
- **Certificate Pinning** (опционально для production): SHA-256 pin сертификата сервера.
- **TLS 1.2+** — минимальная версия.
- Все запросы содержат `Authorization: Bearer <token>`.

### 8.3. Защита данных на устройстве

- Зашифрованные пакеты с часов хранятся в Room **как есть** (Base64 payload). Расшифровка — только на сервере.
- Room БД: опционально `SQLCipher` для шифрования всей БД (если требуется регулятором).
- При выходе из системы: очистить токены из `EncryptedSharedPreferences`.
- При блокировке аккаунта (403): принудительный logout + очистка токенов.

### 8.4. Разрешения Android (AndroidManifest)

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />

    <!-- Bluetooth (BLE для связи с часами) -->
    <uses-permission android:name="android.permission.BLUETOOTH_SCAN"
        android:usesPermissionFlags="neverForLocation" />
    <uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
    <!-- Для Android 10-11 -->
    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION"
        android:maxSdkVersion="30" />

    <!-- NFC (опционально, для RFID-пропусков) -->
    <uses-permission android:name="android.permission.NFC" />

    <!-- Foreground service (для длительной BLE-передачи) -->
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />

    <!-- Уведомления -->
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

    <uses-feature android:name="android.hardware.bluetooth_le"
        android:required="true" />
    <uses-feature android:name="android.hardware.nfc"
        android:required="false" />

    <application
        android:name=".App"
        android:usesCleartextTraffic="false"
        android:networkSecurityConfig="@xml/network_security_config"
        ... >

        <!-- WorkManager initializer -->
        <provider
            android:name="androidx.startup.InitializationProvider"
            android:authorities="${applicationId}.androidx-startup"
            android:exported="false">
            <meta-data
                android:name="androidx.work.WorkManagerInitializer"
                android:value="androidx.startup" />
        </provider>

    </application>
</manifest>
```

### 8.5. Политика паролей и блокировки

- Минимальная длина пароля: 8 символов (валидация на сервере).
- Блокировка после 5 неудачных попыток: показать "Аккаунт заблокирован на 15 минут".
- Сброс пароля: только через администратора (нет функции "Забыли пароль" в мобилке).

### 8.6. Аудит

Все действия оператора фиксируются в локальном журнале (`operation_log`) и синхронизируются с `audit_log` на сервере:
- Вход/выход
- Выдача/возврат часов
- Выгрузка данных
- Смена статуса устройства
- Конфликты и ошибки

---

## 9. Нефункциональные требования

### 9.1. Производительность

| Метрика | Критерий |
|---------|----------|
| Холодный старт приложения | ≤ 3 сек |
| Открытие экрана (с данными из кэша) | ≤ 500 мс |
| Поиск сотрудника по табельному номеру | ≤ 200 мс (локальный кэш) |
| BLE-подключение к часам | ≤ 15 сек |
| Считывание пакета (50 КБ) по BLE | ≤ 60 сек |
| Считывание пакета (500 КБ) по BLE | ≤ 5 мин |
| Отправка пакета на сервер (при наличии сети) | ≤ 30 сек |
| Синхронизация справочников (200 сотрудников) | ≤ 10 сек |

### 9.2. Надёжность

| Требование | Критерий |
|------------|----------|
| Потеря данных при обрывах BT/сети | 0 (пакет хранится до подтверждения) |
| Дубликаты пакетов на сервере | 0 (идемпотентность по `packet_id`) |
| Crash-free rate | ≥ 99.5% |
| Работа без интернета | Полная (выдача, возврат, выгрузка с часов, журнал) |
| Автосинхронизация при появлении сети | ≤ 15 мин (WorkManager) |

### 9.3. Совместимость устройств

| Параметр | Требование |
|----------|-----------|
| Android | 10+ (API 29+) |
| Bluetooth | 5.0+ (BLE) |
| RAM | ≥ 2 ГБ |
| Хранилище | ≥ 100 МБ свободно |
| Экран | 5" – 12" (смартфоны и планшеты) |
| Целевые устройства | Samsung Galaxy Tab A9+, Samsung Galaxy A-серия |

### 9.4. Локализация

- Основной язык: **русский**.
- Формат даты/времени: `dd.MM.yyyy HH:mm` (настраивается по площадке).
- Часовой пояс: по площадке (`Europe/Moscow` по умолчанию).

### 9.5. Размер и зависимости

- APK: ≤ 30 МБ.
- Room БД: ≤ 50 МБ при 500 сотрудниках + 200 устройствах + 30 дней истории.
- Очередь пакетов: ≤ 500 МБ (при накоплении за несколько дней без сети).

---

## 10. Тестирование

### 10.1. Unit-тесты

```kotlin
class BindDeviceUseCaseTest {
    fun `bind success when device available and employee has no active binding`()
    fun `bind fails when device already issued`()
    fun `bind fails when employee already has active binding`()
    fun `bind creates local binding in offline mode`()
}

class UploadPacketUseCaseTest {
    fun `upload success returns 202`()
    fun `upload idempotent returns 409 treated as success`()
    fun `upload failure saves to queue with error status`()
    fun `upload offline saves to queue with pending status`()
}

class SyncPacketsWorkerTest {
    fun `syncs all pending packets when network available`()
    fun `handles 409 as success and marks uploaded`()
    fun `handles 4xx as permanent error`()
    fun `retries on 5xx`()
}

class AuthRepositoryTest {
    fun `login saves tokens to encrypted storage`()
    fun `auto refresh token on 401`()
    fun `logout clears all tokens`()
}
```

### 10.2. Integration-тесты

```kotlin
class PacketUploadFlowTest {
    fun `full flow - read from BLE, save to queue, upload to server`()
    fun `offline flow - save to queue, sync when network appears`()
    fun `idempotent retry - same packet_id, no duplicates`()
}

class BindingFlowTest {
    fun `issue and return flow with data upload`()
    fun `offline issue syncs when network appears`()
    fun `conflict detection when device already bound`()
}
```

### 10.3. UI-тесты (Compose)

```kotlin
class LoginScreenTest {
    fun `shows error on invalid credentials`()
    fun `navigates to context selection on success`()
    fun `shows loading indicator during login`()
}

class IssueScreenTest {
    fun `finds employee by personnel number`()
    fun `assigns available device`()
    fun `shows error when no devices available`()
}

class UploadScreenTest {
    fun `shows progress during BLE transfer`()
    fun `shows success after upload`()
    fun `shows retry button on error`()
    fun `displays pending queue count`()
}
```

### 10.4. Полевые тесты (чек-лист)

| # | Сценарий | Ожидаемый результат |
|---|----------|---------------------|
| 1 | Вход оператора | Токен получен, контекст выбран |
| 2 | Выдача часов 10 сотрудникам | 10 привязок создано, часы отображаются как выданные |
| 3 | Возврат 5 часов с выгрузкой | Пакеты считаны по BLE, отправлены на сервер (202) |
| 4 | Возврат без выгрузки | Предупреждение показано, привязка закрыта |
| 5 | Выгрузка при отсутствии сети | Пакет в очереди, счётчик отображается |
| 6 | Появление сети после оффлайн | Пакеты отправлены автоматически (WorkManager) |
| 7 | Повторная выгрузка того же пакета | Идемпотентность: 409 → успех |
| 8 | Обрыв BLE при считывании | Ошибка, повторная попытка, данные не теряются |
| 9 | 5 неудачных попыток логина | Сообщение о блокировке |
| 10 | Работа 8 часов (полная смена) | Стабильность, нет утечек памяти, батарея ≥ 30% |

---

## 11. Gradle и зависимости

### 11.1. Version Catalog (`libs.versions.toml`)

```toml
[versions]
kotlin = "2.1.0"
agp = "8.8.0"
compose-bom = "2025.02.00"
compose-compiler = "2.1.0"
room = "2.7.0"
ktor = "3.1.0"
koin = "4.0.2"
work = "2.10.0"
navigation = "2.8.6"
datastore = "1.1.3"
security-crypto = "1.1.0-alpha07"
kotlinx-serialization = "2.1.0"
coil = "3.1.0"
timber = "5.0.1"
coroutines = "1.10.1"
lifecycle = "2.8.7"

[libraries]
# Compose
compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "compose-bom" }
compose-material3 = { group = "androidx.compose.material3", name = "material3" }
compose-ui = { group = "androidx.compose.ui", name = "ui" }
compose-ui-tooling = { group = "androidx.compose.ui", name = "ui-tooling" }
compose-navigation = { group = "androidx.navigation", name = "navigation-compose", version.ref = "navigation" }

# Room
room-runtime = { group = "androidx.room", name = "room-runtime", version.ref = "room" }
room-ktx = { group = "androidx.room", name = "room-ktx", version.ref = "room" }
room-compiler = { group = "androidx.room", name = "room-compiler", version.ref = "room" }

# Ktor
ktor-client-core = { group = "io.ktor", name = "ktor-client-core", version.ref = "ktor" }
ktor-client-okhttp = { group = "io.ktor", name = "ktor-client-okhttp", version.ref = "ktor" }
ktor-client-content-negotiation = { group = "io.ktor", name = "ktor-client-content-negotiation", version.ref = "ktor" }
ktor-serialization-json = { group = "io.ktor", name = "ktor-serialization-kotlinx-json", version.ref = "ktor" }

# DI
koin-android = { group = "io.insert-koin", name = "koin-android", version.ref = "koin" }
koin-compose = { group = "io.insert-koin", name = "koin-androidx-compose", version.ref = "koin" }

# WorkManager
work-runtime = { group = "androidx.work", name = "work-runtime-ktx", version.ref = "work" }

# Security
security-crypto = { group = "androidx.security", name = "security-crypto", version.ref = "security-crypto" }

# DataStore
datastore = { group = "androidx.datastore", name = "datastore-preferences", version.ref = "datastore" }

# Serialization
kotlinx-serialization = { group = "org.jetbrains.kotlinx", name = "kotlinx-serialization-json", version.ref = "kotlinx-serialization" }

# Utils
coil-compose = { group = "io.coil-kt.coil3", name = "coil-compose", version.ref = "coil" }
timber = { group = "com.jakewharton.timber", name = "timber", version.ref = "timber" }
coroutines = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-android", version.ref = "coroutines" }

# Lifecycle
lifecycle-viewmodel-compose = { group = "androidx.lifecycle", name = "lifecycle-viewmodel-compose", version.ref = "lifecycle" }
lifecycle-runtime-compose = { group = "androidx.lifecycle", name = "lifecycle-runtime-compose", version.ref = "lifecycle" }

# Testing
junit = { group = "junit", name = "junit", version = "4.13.2" }
mockk = { group = "io.mockk", name = "mockk", version = "1.13.14" }
turbine = { group = "app.cash.turbine", name = "turbine", version = "1.2.0" }
coroutines-test = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-test", version.ref = "coroutines" }
compose-ui-test = { group = "androidx.compose.ui", name = "ui-test-junit4" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
ksp = { id = "com.google.devtools.ksp", version = "2.1.0-1.0.29" }
```

---

## 12. Итерации реализации

### Итерация M1 — Инфраструктура и авторизация (1-2 недели)

- [ ] Инициализация проекта: Kotlin + Compose + Gradle + Version Catalog
- [ ] DI-модули (Koin)
- [ ] Room БД: все Entity + DAO + миграции
- [ ] DataStore + EncryptedSharedPreferences
- [ ] HTTP-клиент (Ktor): AuthInterceptor, TokenRefreshInterceptor
- [ ] Авторизация: Login → токены → профиль → выбор контекста
- [ ] Навигация (Compose Navigation): Login → ContextSelection → Home
- [ ] Тесты авторизации

**Критерий:** Оператор входит, выбирает площадку и смену.

### Итерация M2 — Справочники и кэширование (1 неделя)

- [ ] API: загрузка сотрудников, устройств, площадок, причин простоя
- [ ] Кэширование в Room (upsert)
- [ ] WorkManager: SyncReferenceDataWorker
- [ ] Поиск сотрудника (по табельному, по ФИО)
- [ ] Экран: Часы на площадке (DeviceListScreen)
- [ ] Тесты кэширования и поиска

**Критерий:** Справочники загружаются, работают оффлайн, обновляются автоматически.

### Итерация M3 — Выдача и возврат часов (1-2 недели)

- [ ] Экран выдачи (IssueScreen): идентификация → назначение → подтверждение
- [ ] Экран возврата (ReturnScreen): список выданных → возврат
- [ ] Привязки: локальная запись + синхронизация с сервером
- [ ] Валидации: конфликты привязок, недоступные часы
- [ ] Журнал операций: записи выдачи/возврата
- [ ] Оффлайн-привязки + синхронизация
- [ ] Тесты привязок

**Критерий:** Оператор выдаёт и принимает часы, привязки синхронизируются с сервером.

### Итерация M4 — Bluetooth и выгрузка данных (2-3 недели)

- [ ] BLE Scanner: обнаружение часов
- [ ] GATT Client: подключение, чтение характеристик
- [ ] Протокол: передача контекста смены (начало)
- [ ] Протокол: считывание пакета чанками (конец)
- [ ] Протокол: ACK/NACK
- [ ] Сборка пакета из чанков, сохранение в packet_queue
- [ ] Экран выгрузки (UploadScreen) с прогрессом
- [ ] Обработка ошибок BLE (обрывы, таймауты)
- [ ] Тесты BLE-протокола (mock/emulator)

**Критерий:** Пакет считывается с часов по BLE, сохраняется локально.

### Итерация M5 — Отправка на сервер и оффлайн (1-2 недели)

- [ ] API: POST /api/v1/gateway/packets (отправка пакета)
- [ ] Идемпотентность (Idempotency-Key = packet_id)
- [ ] WorkManager: SyncPacketsWorker (фоновая отправка)
- [ ] Экспоненциальный backoff при ошибках
- [ ] Индикация оффлайн-состояния (баннер, счётчики)
- [ ] Конфликты синхронизации
- [ ] Экран сводки (SummaryScreen)
- [ ] Тесты отправки и оффлайн-сценариев

**Критерий:** Пакет отправляется на сервер (202), при отсутствии сети — автоматическая повторная отправка.

### Итерация M6 — Журнал, полировка, тестирование (1-2 недели)

- [ ] Журнал операций (JournalScreen) с фильтрами
- [ ] Полировка UI: тёмная тема, адаптивность планшетов, анимации
- [ ] Полевые тесты (чек-лист из раздела 10.4)
- [ ] Performance: холодный старт, утечки памяти
- [ ] Безопасность: certificate pinning, Proguard/R8
- [ ] CI/CD: lint, unit tests, APK signing
- [ ] Документация: README, CHANGELOG

**Критерий:** Стабильное приложение, пройдены полевые тесты на реальном устройстве и часах.

**Общая оценка: 7-12 недель (1.5-3 месяца), 6 итераций (M1-M6).**

---

## 13. Открытые вопросы для согласования

1. **DI-фреймворк** — Koin или Hilt? Koin проще в настройке, Hilt — стандарт Google.
2. **HTTP-клиент** — Ktor Client или Retrofit? Ktor — Kotlin-native, Retrofit — проверенный.
3. **BLE GATT UUIDs** — финальные UUID характеристик фиксируются совместно с командой Wear OS.
4. **NFC-считывание RFID** — нужна ли поддержка конкретного формата RFID-пропусков?
5. **QR-код** — нужен ли сканер QR для идентификации часов/сотрудников?
6. **Режимы назначения часов** — по очереди / случайно / вручную — какой по умолчанию?
7. **SQLCipher** — нужно ли полное шифрование локальной БД (Room)?
8. **Размер пакета** — максимальный ожидаемый размер зашифрованного пакета (для оценки BLE-передачи)?
9. **Несколько операторов** — может ли несколько операторов работать с одной площадкой одновременно?
10. **Push-уведомления** — нужны ли серверные push (FCM) для критичных событий (ошибки обработки, конфликты)?

---

## 14. Финальный чек-лист (Definition of Done)

### 14.1. Функциональные требования

- [ ] Оператор входит по email/паролю и выбирает контекст (площадка + смена)
- [ ] Справочники (сотрудники, часы, площадки) кэшируются локально и обновляются автоматически
- [ ] Оператор выдаёт часы сотруднику (идентификация по табельному/RFID/ФИО)
- [ ] Валидация: нет конфликтов привязок (часы свободны, сотрудник без часов)
- [ ] Оператор возвращает часы и закрывает привязку
- [ ] Оператор считывает зашифрованный пакет с часов по Bluetooth
- [ ] Прогресс выгрузки отображается (подключение → считывание → отправка)
- [ ] Пакет отправляется на сервер через GATEWAY-эндпоинт (202 Accepted)
- [ ] Идемпотентность: повторная отправка → 409, без дубликатов
- [ ] Оффлайн: привязки и пакеты сохраняются локально и синхронизируются при появлении сети
- [ ] WorkManager гарантирует доставку неотправленных пакетов
- [ ] Журнал операций с фильтрацией
- [ ] Операционная сводка по смене
- [ ] Управление статусами часов (доступны/разряжены/неисправны)

### 14.2. Нефункциональные требования

- [ ] Время холодного старта ≤ 3 сек
- [ ] BLE-подключение к часам ≤ 15 сек
- [ ] Потеря данных при обрывах = 0
- [ ] Дубликаты пакетов на сервере = 0
- [ ] HTTPS-only, токены в EncryptedSharedPreferences
- [ ] Поддержка Android 10+ (API 29+)
- [ ] Поддержка планшетов (адаптивный UI)
- [ ] Русский язык интерфейса
- [ ] Crash-free rate ≥ 99.5%

### 14.3. Совместимость с Backend (BACKEND_SPEC.md)

- [ ] `POST /api/v1/auth/login` — авторизация оператора
- [ ] `POST /api/v1/gateway/packets` — отправка пакета (GATEWAY)
- [ ] `Idempotency-Key` header = `packet_id`
- [ ] `Authorization: Bearer <web_token>` header
- [ ] HTTP-коды ответов обрабатываются согласно таблице 4.7
- [ ] Поля `uploaded_from = 'gateway'`, `operator_id`, `gateway_device_info` передаются корректно
- [ ] Справочники загружаются через REST API и кэшируются

### 14.4. Совместимость с Wear OS приложением

- [ ] BLE GATT Client подключается к GATT Server на часах
- [ ] Контекст смены передаётся на часы при начале (GATEWAY)
- [ ] Зашифрованный пакет считывается чанками с часов
- [ ] ACK отправляется на часы после успешного приёма
- [ ] `packet_id` из часов используется для идемпотентности на сервере
