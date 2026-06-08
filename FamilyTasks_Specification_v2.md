# FamilyTasks

**Спецификация приложения для Android**

Версия 2.0 · Июнь 2026

> Версия 2.0 — актуализация после реализации. Отражает принятые технические решения, точные версии библиотек и фактическую структуру проекта. Все 5 фаз реализованы.

---

# 1. Обзор проекта

FamilyTasks — Android-приложение для совместного управления задачами между двумя устройствами в рамках одной семьи. Приложение работает полностью автономно, без серверов и облачных сервисов. Синхронизация происходит напрямую между устройствами через локальную WiFi-сеть.

**Целевая аудитория:** семья из 2 человек, пользующихся двумя Android-устройствами в одной домашней сети.

**Ключевая идея:** каждое устройство является одновременно клиентом и сервером. Нет главного устройства, нет зависимости от интернета, нет сторонних сервисов.

## 1.1 Основные принципы

- **Без серверов** — приложение работает полностью офлайн
- **Минимальные зависимости** — только стандартный Android SDK, Jetpack и узкоспециализированные библиотеки
- **Симметричная архитектура** — оба устройства равноправны
- **Простота** — семейный инструмент, не корпоративный таск-трекер
- **Правило конфликтов:** побеждает последняя запись по времени (`updatedAt`)

---

# 2. Технический стек

| **Компонент** | **Решение** | **Версия** | **Обоснование** |
|---|---|---|---|
| Язык | Kotlin | 2.0.21 | Современный стандарт Android-разработки |
| UI | Jetpack Compose | BOM 2024.12.01 | Декларативный UI, нет XML-разметки |
| Локальная БД | Room (SQLite) | 2.6.1 | Официальная ORM от Google, надёжна |
| DI | Hilt | 2.52 | Стандарт для Clean Architecture, упрощает тестирование |
| HTTP-сервер | NanoHTTPD | 2.3.1 | ~50KB, встраивается в APK |
| HTTP-клиент | OkHttp | 4.12.0 | Зрелая библиотека, поддержка coroutines через suspend |
| Обнаружение устройств | UDP Broadcast | — | Не требует mDNS, работает на любом домашнем роутере |
| ID устройства | UUID v4 | — | Генерируется при первом запуске, хранится в SharedPreferences |
| Уведомления | WorkManager + NotificationManager | 2.10.0 | Надёжная доставка даже в фоне |
| Сериализация | kotlinx.serialization | 1.7.3 | Официальный Kotlin JSON, поддержка `@Serializable` |
| QR-коды | ZXing Android Embedded | 4.3.0 | Генерация и сканирование QR без внешних зависимостей |
| Аннотационный процессор | KSP | 2.0.21-1.0.27 | Быстрее kapt, официальный путь для Room и Hilt |

**Build system:** AGP 8.7.3, Gradle 8.9, Java 17.

**Package:** `com.familytasks.app` · **compileSdk/targetSdk:** 35 · **minSdk:** 26 (Android 8.0)

---

# 3. Архитектура

## 3.1 Общая схема

Каждое устройство запускает два параллельных компонента: HTTP-сервер (NanoHTTPD на порту 8080) и UDP-listener (порт 9090). При появлении в WiFi-сети устройство рассылает UDP broadcast с своим `deviceId` и портом HTTP-сервера. Второе устройство получает broadcast, узнаёт IP собеседника и инициирует HTTP-синхронизацию.

## 3.2 Слои приложения

| **Слой** | **Пакет** | **Ответственность** |
|---|---|---|
| UI (Presentation) | `ui/` | Compose Screens, ViewModels, StateFlow → UI |
| Domain | `domain/` | UseCases, Repository interfaces — не зависит ни от чего |
| Data (Local) | `data/local/` | Room DAO, Entity, маппинг domain ↔ entity |
| Data (Sync) | `data/sync/` | HttpServer, HttpClient, UdpDiscovery, SyncService, SyncWorker |
| Data (Repository) | `data/repository/` | Реализации интерфейсов из domain |
| Core | `core/` | DeviceIdManager, PreferencesManager |
| Notifications | `notifications/` | NotificationScheduler, DueNotificationWorker |
| DI | `di/` | Hilt-модули: AppModule, DatabaseModule, WorkerModule |

**Принцип зависимостей:** `ui` → `domain` ← `data`. Слой `domain` не импортирует ни Android, ни Room, ни Hilt.

## 3.3 SyncManager

`SyncManager` — `@Singleton`, хранит текущее состояние синхронизации в `StateFlow`. Используется сервисом для обновления и ViewModels для чтения.

```kotlin
class SyncManager {
    val peerIp: StateFlow<String?>        // IP сопряжённого устройства, null = офлайн
    val syncState: StateFlow<SyncState>   // IDLE / SYNCING / SUCCESS / ERROR
    val lastSyncAt: StateFlow<Long>       // Unix timestamp мс последней успешной синхронизации
}
```

## 3.4 Протокол обнаружения устройств

Обнаружение происходит через UDP broadcast на адрес `255.255.255.255`, порт `9090`.

**Важно:** Android требует `CHANGE_WIFI_MULTICAST_STATE` и `WifiManager.MulticastLock`, иначе broadcast-пакеты фильтруются WiFi-чипом на уровне драйвера.

**Пакет обнаружения:**
```json
{ "deviceId": "aaa-111", "httpPort": 8080 }
```

**Последовательность:**

1. Устройство A подключается к WiFi → `WifiReceiver` принимает `WIFI_STATE_CHANGED`
2. A запускает `SyncService.ACTION_SYNC_NOW`, `UdpDiscovery` рассылает broadcast
3. Устройство B получает пакет, сохраняет IP отправителя из заголовка `DatagramPacket`
4. B рассылает свой broadcast в ответ
5. A получает broadcast B, оба вызывают `SyncManager.setPeerIp(ip)` и запускают синхронизацию

Broadcast повторяется каждые 30 секунд, и однократно при каждом подключении к WiFi. При получении broadcast от **неизвестного** `deviceId` (не совпадает с `pairedDeviceId`) — пакет игнорируется.

## 3.5 HTTP API

**Единственный эндпоинт:** `POST /sync` (порт 8080)

Запрос и ответ имеют одинаковую структуру `SyncPayload`. Один HTTP-запрос обеспечивает полный двусторонний обмен: инициатор отправляет свои задачи и устройства, получатель применяет мёрж и возвращает свои.

**Формат `SyncPayload`:**

```json
{
  "deviceId": "aaa-111",
  "tasks": [
    {
      "id": "550e8400-e29b-41d4-a716-446655440000",
      "title": "Купить молоко",
      "description": "2 литра, 3.2%",
      "assignedTo": "bbb-222",
      "status": "todo",
      "priority": "normal",
      "dueDate": 1718100000000,
      "createdBy": "aaa-111",
      "createdAt": 1718000000000,
      "updatedAt": 1718050000000,
      "deleted": false
    }
  ],
  "devices": [
    {
      "deviceId": "aaa-111",
      "displayName": "Мама",
      "color": -12303292
    }
  ]
}
```

**Поле `devices` в v2.0:** добавлено для обмена профилями устройств при синхронизации. В версии 1.0 отсутствовало.

**Тайм-ауты HTTP-клиента:** connect 5 сек, read 10 сек.

## 3.6 Правило мёржа (`MergeTasksUseCase`)

При получении задачи от собеседника:

- Задачи нет локально → добавить
- Задача есть → сравнить `updatedAt`: побеждает бо́льшее значение
- `updatedAt` одинаковый → оставить локальную (tie-break)

Удаление — только через tombstone: `deleted = true` + обновить `updatedAt`. Физического удаления из БД нет. UI всегда фильтрует `deleted = false`. Это предотвращает «воскрешение» удалённых задач при синхронизации офлайн-устройства.

## 3.7 Офлайн-режим

Все изменения сохраняются локально в Room с актуальным `updatedAt`. При следующем подключении запускается полная синхронизация — передаётся весь массив задач целиком, очередь изменений не нужна.

---

# 4. Модель данных

## 4.1 Задача (Task)

**Domain-модель:** `domain/model/Task.kt`  
**Room-entity:** `data/local/TaskEntity.kt`  
**Sync DTO:** `data/sync/SyncDto.kt` → `TaskDto`

| **Поле** | **Тип (domain)** | **Хранение в Room** | **Описание** |
|---|---|---|---|
| id | String (UUID) | TEXT PRIMARY KEY | Генерируется через `UUID.randomUUID()` при создании |
| title | String | TEXT | Обязательное поле, макс. 200 символов |
| description | String? | TEXT nullable | Подробное описание, опционально |
| assignedTo | String? | TEXT nullable | deviceId назначенного, `null` = никому |
| status | TaskStatus (enum) | TEXT (`"todo"` / `"in_progress"` / `"done"`) | Конвертация через `.value` / `fromString()` |
| priority | Priority (enum) | TEXT (`"low"` / `"normal"` / `"high"`) | Конвертация через `.value` / `fromString()` |
| dueDate | Long? | INTEGER nullable | Unix timestamp мс |
| createdBy | String | TEXT | deviceId создателя, не изменяется |
| createdAt | Long | INTEGER | Unix timestamp мс, не изменяется никогда |
| updatedAt | Long | INTEGER | Обновляется при каждом изменении через `System.currentTimeMillis()` |
| deleted | Boolean | INTEGER (0/1) | Tombstone-флаг, default `false` |

**Конвертация Room ↔ domain:** extension-функции `toDomain()` / `toEntity()` в файле `TaskEntity.kt`.  
**Конвертация domain ↔ DTO:** extension-функции `toDto()` / `toDomain()` в файле `SyncDto.kt`.

## 4.2 Устройство / Пользователь (Device)

**Domain-модель:** `domain/model/Device.kt`  
**Room-entity:** `data/local/DeviceEntity.kt`

| **Поле** | **Тип** | **Описание** |
|---|---|---|
| deviceId | String (UUID) | PRIMARY KEY. Генерируется один раз, хранится в SharedPreferences навсегда |
| displayName | String | Имя пользователя, задаётся при онбординге |
| color | Int | ARGB-цвет аватара, выбирается при онбординге из 8 предустановленных |
| lastSeenAt | Long? | Последний раз онлайн, только локально, не синхронизируется |

Список устройств хранится в Room. `pairedDeviceId` (deviceId партнёра) дополнительно хранится в SharedPreferences для фильтрации UDP broadcast.

## 4.3 Перечисления

```kotlin
enum class TaskStatus(val value: String) { TODO("todo"), IN_PROGRESS("in_progress"), DONE("done") }
enum class Priority(val value: String)   { LOW("low"), NORMAL("normal"), HIGH("high") }
enum class SortOrder                     { DUE_DATE, PRIORITY, CREATED_AT }
enum class AssigneeFilter                { ALL, MINE, OTHERS }
enum class StatusFilter                  { ACTIVE, DONE, ALL }
```

## 4.4 SharedPreferences (PreferencesManager)

Ключи `device_prefs.xml`:

| Ключ | Тип | Описание |
|---|---|---|
| `device_id` | String | UUID устройства |
| `display_name` | String | Имя пользователя |
| `avatar_color` | Int | ARGB цвет аватара |
| `onboarding_done` | Boolean | Флаг завершения онбординга |
| `paired_device_id` | String? | deviceId сопряжённого устройства |
| `last_sync_at` | Long | Timestamp последней успешной синхронизации |
| `notify_due_date` | Boolean | Уведомления о сроке (default: true) |
| `notify_new_task` | Boolean | Уведомления о новых задачах (default: true) |
| `notify_status_change` | Boolean | Уведомления о смене статуса (default: true) |

---

# 5. Онбординг и сопряжение устройств

## 5.1 Первый запуск (OnboardingScreen)

1. Приложение генерирует UUID → `DeviceIdManager` → сохраняет в SharedPreferences навсегда
2. Пользователь вводит своё имя (например, «Мама»)
3. Пользователь выбирает цвет аватара из 8 предустановленных
4. Приложение показывает короткий код устройства (первые 8 символов UUID)
5. Переход к экрану сопряжения

Флаг `isOnboardingComplete` определяет стартовый маршрут `NavGraph`: `Onboarding` или `TaskList`.

## 5.2 Сопряжение (QrCodeScreen)

Экран имеет две вкладки:

- **«Мой QR-код»** — генерирует QR из JSON `{ deviceId, displayName, color }` с помощью `QRCodeWriter` (ZXing core)
- **«Сканировать»** — запускает `ScanContract` (ZXing Android Embedded) через `ActivityResultLauncher`

После сканирования: партнёрский `Device` сохраняется в Room, его `deviceId` — в `PreferencesManager.pairedDeviceId`. Сопряжение выполняется один раз; повторное доступно из экрана настроек.

---

# 6. Функциональные требования

## 6.1 Управление задачами

- Создание задачи: название (обязательно), описание, приоритет, срок (DatePickerDialog + TimePickerDialog), назначение
- Редактирование любого поля (`EditTaskContent.kt`)
- Смена статуса циклом: `todo` → `in_progress` → `done` → `todo` (кнопка на экране детали)
- Удаление задачи: tombstone (`deleted = true` + `updatedAt = now`), физически не удаляется
- Назначение: себе, партнёру, или никому

## 6.2 Просмотр и фильтрация (TaskListScreen)

- Список активных задач (`deleted = false`)
- Фильтр по назначению: **Все / Мои / Чужие** (FilterChip в TopAppBar)
- Фильтр по статусу: активные / выполненные
- Сортировка через DropdownMenu: по сроку (`dueDate ?: Long.MAX_VALUE`), по приоритету, по дате создания
- Цветная точка = цвет создателя; цветной кружок справа = цвет назначенного
- Просроченный срок подсвечивается красным, срок < 1 часа — оранжевым

## 6.3 Уведомления (NotificationScheduler)

Три независимых канала:

| Канал | ID | Событие | Условие |
|---|---|---|---|
| Срок задачи | `ch_due_date` | `dueDate` − 1 день и − 1 час | Если `dueDate` задан и момент ещё не наступил |
| Новая задача | `ch_new_task` | Задача создана | `assignedTo` == мой `deviceId` |
| Смена статуса | `ch_status` | Статус изменён | Задачу создал я, изменил партнёр |

Планирование: `OneTimeWorkRequestBuilder<DueNotificationWorker>` с `setInitialDelay`. Все уведомления отключаются независимо в настройках.

## 6.4 Синхронизация

- Автоматическая при обнаружении партнёра через UDP
- Ручная кнопка «Синхронизировать» в `TaskListScreen` (через `SyncService.ACTION_SYNC_NOW`)
- Статус в TopAppBar: **Офлайн / Синхронизация... / Синхр. N мин назад**
- Периодическая синхронизация каждые 5 минут через `PeriodicWorkRequest<SyncWorker>`

---

# 7. Пользовательские сценарии

## Сценарий 1: создание и назначение задачи

Мама открывает приложение, нажимает «+», вводит «Забрать ребёнка из школы», назначает Папе, ставит срок на 15:00. Нажимает «Создать». Задача появляется у Папы при следующей синхронизации (автоматически, как только оба телефона в одной WiFi-сети). Папа получает уведомление в канале `ch_new_task`.

## Сценарий 2: офлайн-работа

Папа уходит из дома. Дома меняет задачу Мама — её `updatedAt` = T1. На улице Папа меняет ту же задачу — его `updatedAt` = T2. Когда Папа возвращается и подключается к WiFi, `WifiReceiver` запускает синхронизацию. `MergeTasksUseCase` сравнивает T1 и T2: побеждает бо́льший. Ни один из пользователей не видит диалогов конфликта.

## Сценарий 3: выполнение задачи

Папа меняет статус на «Готово» — `updatedAt` обновляется. При синхронизации Мама видит задачу в разделе выполненных. Если задачу создавала Мама — она получает уведомление в канале `ch_status`.

---

# 8. Нефункциональные требования

| **Требование** | **Целевой показатель** |
|---|---|
| Время синхронизации (оба онлайн) | < 2 секунды |
| Время обнаружения устройства в сети | < 5 секунд (broadcast каждые 30 сек) |
| Размер APK | < 15 MB |
| Поддерживаемые версии Android | API 26+ (Android 8.0) |
| Работа без интернета | 100% функциональность |
| Максимальное количество задач | До 10 000 (Room без проблем) |
| Время запуска приложения | < 1 секунды (cold start) |

---

# 9. Разрешения Android

| **Разрешение** | **Зачем нужно** |
|---|---|
| `INTERNET` | HTTP-сервер и HTTP-клиент |
| `ACCESS_WIFI_STATE` | Определение подключения к WiFi |
| `CHANGE_WIFI_STATE` | Работа с WiFi |
| `CHANGE_WIFI_MULTICAST_STATE` | **Критично** для UDP broadcast — без него пакеты фильтруются WiFi-чипом |
| `ACCESS_NETWORK_STATE` | Мониторинг состояния сети |
| `FOREGROUND_SERVICE` | Базовое разрешение для Foreground Service |
| `FOREGROUND_SERVICE_CONNECTED_DEVICE` | **Android 14+**: тип сервиса `connectedDevice` |
| `FOREGROUND_SERVICE_DATA_SYNC` | **Android 14+**: тип сервиса `dataSync` |
| `POST_NOTIFICATIONS` | Уведомления — **Android 13+ требует явного разрешения от пользователя** |
| `RECEIVE_BOOT_COMPLETED` | Запуск `SyncService` после перезагрузки устройства |
| `USE_EXACT_ALARM` | Точные будильники для уведомлений о сроке |
| `SCHEDULE_EXACT_ALARM` | Дополнительное разрешение для точных будильников |
| `CAMERA` | Сканирование QR-кода при сопряжении |

**Важно для Android 14+:** `SyncService` должен объявлять `android:foregroundServiceType="connectedDevice|dataSync"` в AndroidManifest, иначе `startForeground()` бросает исключение.

---

# 10. DI (Dependency Injection)

Используется **Hilt 2.52** с **KSP** (не kapt).

## Модули

| Модуль | Тип | Что предоставляет |
|---|---|---|
| `AppModule` | `@Binds` abstract | `TaskRepository` ← `TaskRepositoryImpl`, `DeviceRepository` ← `DeviceRepositoryImpl` |
| `DatabaseModule` | `@Provides` object | `AppDatabase`, `TaskDao`, `DeviceDao` |
| `WorkerModule` | `@Provides` object | `WorkManager` |

## Singleton-компоненты

- `SyncManager` — `@Singleton`, StateFlow с состоянием синхронизации
- `PreferencesManager` — `@Singleton`, обёртка над SharedPreferences
- `DeviceIdManager` — `@Singleton`, генерация и доступ к UUID
- `HttpClient` — `@Singleton`, единственный экземпляр OkHttpClient

## WorkManager + Hilt

`FamilyTasksApplication` реализует `Configuration.Provider` и возвращает `HiltWorkerFactory`. Воркеры (`SyncWorker`, `DueNotificationWorker`) аннотированы `@HiltWorker` и используют `@AssistedInject`. В AndroidManifest отключён стандартный `WorkManagerInitializer` через `tools:node="remove"`.

---

# 11. Структура проекта

```
app/src/main/java/com/familytasks/app/
│
├── FamilyTasksApplication.kt     ← @HiltAndroidApp, HiltWorkerFactory, WorkManager config
├── MainActivity.kt               ← @AndroidEntryPoint, запускает SyncService
│
├── core/
│   ├── DeviceIdManager.kt        ← генерация и хранение UUID (@Singleton)
│   └── PreferencesManager.kt    ← SharedPreferences обёртка (@Singleton)
│
├── domain/
│   ├── model/
│   │   ├── Task.kt
│   │   ├── Device.kt
│   │   ├── TaskStatus.kt
│   │   ├── Priority.kt
│   │   ├── SortOrder.kt
│   │   └── TaskFilter.kt         ← AssigneeFilter, StatusFilter
│   ├── repository/
│   │   ├── TaskRepository.kt     ← интерфейс
│   │   └── DeviceRepository.kt   ← интерфейс
│   └── usecase/
│       ├── MergeTasksUseCase.kt  ← логика мёржа (last-write-wins)
│       └── SyncUseCase.kt        ← оркестрация: snapshot + merge + devices
│
├── data/
│   ├── local/
│   │   ├── AppDatabase.kt        ← @Database(entities=[TaskEntity, DeviceEntity])
│   │   ├── TaskEntity.kt         ← Room entity + toDomain() / toEntity()
│   │   ├── DeviceEntity.kt       ← Room entity + toDomain() / toEntity()
│   │   ├── TaskDao.kt            ← @Upsert, Flow<List<TaskEntity>>
│   │   └── DeviceDao.kt
│   ├── repository/
│   │   ├── TaskRepositoryImpl.kt
│   │   └── DeviceRepositoryImpl.kt
│   └── sync/
│       ├── SyncDto.kt            ← SyncPayload, TaskDto, DeviceDto + маппинг
│       ├── SyncManager.kt        ← @Singleton, StateFlow peerIp/syncState/lastSyncAt
│       ├── HttpServer.kt         ← NanoHTTPD, обработка POST /sync
│       ├── HttpClient.kt         ← OkHttp, вызов POST /sync у партнёра
│       ├── UdpDiscovery.kt       ← broadcast + listener + MulticastLock
│       ├── SyncService.kt        ← Foreground Service, запускает Http + Udp
│       ├── SyncWorker.kt         ← @HiltWorker, PeriodicWork каждые 5 мин
│       ├── BootReceiver.kt       ← запуск SyncService после перезагрузки
│       └── WifiReceiver.kt       ← триггер sync при подключении к WiFi
│
├── notifications/
│   ├── NotificationScheduler.kt  ← @Singleton, создаёт каналы, планирует WorkManager
│   └── DueNotificationWorker.kt  ← @HiltWorker, показывает уведомление о сроке
│
├── di/
│   ├── AppModule.kt              ← @Binds для репозиториев
│   ├── DatabaseModule.kt         ← @Provides для Room
│   └── WorkerModule.kt           ← @Provides для WorkManager
│
└── ui/
    ├── theme/
    │   ├── Color.kt              ← Material3 цвета + AvatarColors (8 вариантов)
    │   ├── Theme.kt              ← dynamic color (Android 12+) + fallback
    │   └── Type.kt
    ├── navigation/
    │   ├── Screen.kt             ← sealed class с маршрутами
    │   └── NavGraph.kt           ← NavHost, start destination по isOnboardingComplete
    ├── onboarding/
    │   ├── OnboardingScreen.kt   ← ввод имени + выбор цвета
    │   ├── OnboardingViewModel.kt
    │   ├── QrCodeScreen.kt       ← 2 вкладки: показать QR / сканировать
    │   └── QrCodeViewModel.kt
    ├── tasks/
    │   ├── TaskListScreen.kt     ← список, фильтры, сортировка, статус sync
    │   ├── TaskListViewModel.kt  ← combine(tasks, devices, filters, syncStatus)
    │   ├── TaskDetailScreen.kt   ← просмотр + inline редактирование
    │   ├── TaskDetailViewModel.kt
    │   ├── EditTaskContent.kt    ← переиспользуемый Composable для редактирования
    │   ├── CreateTaskScreen.kt   ← форма создания задачи
    │   └── CreateTaskViewModel.kt
    └── settings/
        ├── SettingsScreen.kt     ← профили устройств, тогглы уведомлений
        └── SettingsViewModel.kt
```

---

# 12. Зависимости (gradle/libs.versions.toml)

```toml
[versions]
agp = "8.7.3"
kotlin = "2.0.21"
ksp = "2.0.21-1.0.27"
coreKtx = "1.15.0"
lifecycleRuntimeKtx = "2.8.7"
activityCompose = "1.9.3"
composeBom = "2024.12.01"
navigationCompose = "2.8.4"
room = "2.6.1"
hilt = "2.52"
hiltExt = "1.2.0"
workManager = "2.10.0"
kotlinxSerialization = "1.7.3"
okhttp = "4.12.0"
nanohttpd = "2.3.1"
zxingAndroidEmbedded = "4.3.0"

[libraries]
# Jetpack
androidx-core-ktx = { group = "androidx.core", name = "core-ktx", version.ref = "coreKtx" }
androidx-lifecycle-runtime-ktx = { group = "androidx.lifecycle", name = "lifecycle-runtime-ktx", version.ref = "lifecycleRuntimeKtx" }
androidx-lifecycle-viewmodel-compose = { group = "androidx.lifecycle", name = "lifecycle-viewmodel-compose", version.ref = "lifecycleRuntimeKtx" }
androidx-lifecycle-runtime-compose = { group = "androidx.lifecycle", name = "lifecycle-runtime-compose", version.ref = "lifecycleRuntimeKtx" }
androidx-activity-compose = { group = "androidx.activity", name = "activity-compose", version.ref = "activityCompose" }

# Compose
androidx-compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "composeBom" }
androidx-ui = { group = "androidx.compose.ui", name = "ui" }
androidx-material3 = { group = "androidx.compose.material3", name = "material3" }
androidx-material-icons-extended = { group = "androidx.compose.material", name = "material-icons-extended" }
androidx-navigation-compose = { group = "androidx.navigation", name = "navigation-compose", version.ref = "navigationCompose" }

# Room (KSP)
androidx-room-runtime = { group = "androidx.room", name = "room-runtime", version.ref = "room" }
androidx-room-ktx = { group = "androidx.room", name = "room-ktx", version.ref = "room" }
androidx-room-compiler = { group = "androidx.room", name = "room-compiler", version.ref = "room" }

# Hilt (KSP)
hilt-android = { group = "com.google.dagger", name = "hilt-android", version.ref = "hilt" }
hilt-compiler = { group = "com.google.dagger", name = "hilt-android-compiler", version.ref = "hilt" }
androidx-hilt-navigation-compose = { group = "androidx.hilt", name = "hilt-navigation-compose", version.ref = "hiltExt" }
androidx-hilt-work = { group = "androidx.hilt", name = "hilt-work", version.ref = "hiltExt" }
androidx-hilt-compiler = { group = "androidx.hilt", name = "hilt-compiler", version.ref = "hiltExt" }

# WorkManager
androidx-work-runtime-ktx = { group = "androidx.work", name = "work-runtime-ktx", version.ref = "workManager" }

# Сеть и сериализация
kotlinx-serialization-json = { group = "org.jetbrains.kotlinx", name = "kotlinx-serialization-json", version.ref = "kotlinxSerialization" }
okhttp = { group = "com.squareup.okhttp3", name = "okhttp", version.ref = "okhttp" }
nanohttpd = { group = "org.nanohttpd", name = "nanohttpd", version.ref = "nanohttpd" }

# QR
zxing-android-embedded = { group = "com.journeyapps", name = "zxing-android-embedded", version.ref = "zxingAndroidEmbedded" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
hilt = { id = "com.google.dagger.hilt.android", version.ref = "hilt" }
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
```

---

# 13. Ограничения и известные сложности

## 13.1 Конфликты (Last-Write-Wins)

Если оба пользователя офлайн одновременно редактировали одну задачу, одно из изменений будет потеряно молча — побеждает версия с бо́льшим `updatedAt`. Для семейного приложения это приемлемо. Альтернатива — vector clock — существенно усложняет реализацию.

## 13.2 UDP broadcast и нестандартные сети

На некоторых роутерах UDP broadcast может быть заблокирован (корпоративные сети, изолированные гостевые сети). Для домашней сети не актуально. Возможный fallback — ручной ввод IP партнёра в настройках (не реализован).

## 13.3 Масштабирование

Архитектура рассчитана строго на **два** устройства. Третье потребует: смены `pairedDeviceId: String?` на `pairedDeviceIds: Set<String>`, логики синхронизации «каждый с каждым», обновления UI сопряжения.

## 13.4 Фоновая работа и батарея

Android агрессивно завершает фоновые процессы. `SyncService` работает как Foreground Service с постоянным уведомлением — единственный надёжный способ держать HTTP-сервер и UDP-listener живыми. На Android 14+ обязательно указывать `foregroundServiceType`.

## 13.5 Точность уведомлений о сроке

Уведомления о сроке планируются через `WorkManager` с `setInitialDelay`. WorkManager гарантирует доставку, но допускает задержку в несколько минут (Doze mode). Для гарантированно точных уведомлений потребовался бы `AlarmManager` с `USE_EXACT_ALARM`, что создаёт дополнительные сложности с разрешениями на Android 12+.

---

# 14. Статус реализации

| **Фаза** | **Содержание** | **Статус** |
|---|---|---|
| Фаза 1 | Room DB, domain-модели, весь UI (Compose), CRUD задач, фильтры, сортировка | ✅ Реализовано |
| Фаза 2 | Генерация deviceId, онбординг, ввод имени и цвета, QR-сопряжение | ✅ Реализовано |
| Фаза 3 | NanoHTTPD сервер, POST /sync, правило мёржа, tombstone | ✅ Реализовано |
| Фаза 4 | UDP broadcast, автообнаружение, MulticastLock, WorkManager периодический sync | ✅ Реализовано |
| Фаза 5 | Уведомления (срок, назначение, выполнение), полировка UX | ✅ Реализовано |

---

*Спецификация v2.0 отражает фактическое состояние реализации проекта. Любые изменения в архитектуре или протоколе должны сначала обновляться здесь, затем в `CLAUDE.md`.*
