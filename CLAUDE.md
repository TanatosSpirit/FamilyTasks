# FamilyTasks — CLAUDE.md

Android-приложение для совместного управления задачами между двумя устройствами в одной WiFi-сети. Работает **полностью офлайн**, без серверов и облачных сервисов.

---

## Быстрый старт

```bash
# Сборка debug APK (из корня проекта)
./gradlew assembleDebug

# Запуск тестов
./gradlew test

# Установка на устройство
./gradlew installDebug

# Полная очистка
./gradlew clean
```

Открывать через **File → Open** в Android Studio (не Import). Gradle синхронизируется автоматически.

---

## Технический стек

| Компонент | Версия | Артефакт |
|---|---|---|
| Kotlin | 2.0.21 | — |
| AGP | 8.7.3 | — |
| Gradle | 8.9 | — |
| Jetpack Compose BOM | 2024.12.01 | `androidx.compose:compose-bom` |
| Room | 2.6.1 | `androidx.room:room-*` |
| Hilt | 2.52 | `com.google.dagger:hilt-android` |
| WorkManager | 2.10.0 | `androidx.work:work-runtime-ktx` |
| NanoHTTPD | 2.3.1 | `org.nanohttpd:nanohttpd` |
| OkHttp | 4.12.0 | `com.squareup.okhttp3:okhttp` |
| kotlinx.serialization | 1.7.3 | `kotlinx-serialization-json` |
| ZXing Android Embedded | 4.3.0 | `com.journeyapps:zxing-android-embedded` |
| compileSdk / targetSdk | 35 | — |
| minSdk | 26 (Android 8.0) | — |

**Аннотационный процессор: KSP** (не kapt). Используется для Room и Hilt.

---

## Архитектура

### Слои

```
domain/          ← бизнес-логика, не зависит ни от чего
  model/         ← Task, Device, TaskStatus, Priority, SortOrder, фильтры
  repository/    ← интерфейсы TaskRepository, DeviceRepository
  usecase/       ← MergeTasksUseCase, SyncUseCase

data/
  local/         ← Room: TaskEntity, DeviceEntity, TaskDao, DeviceDao, AppDatabase
  repository/    ← TaskRepositoryImpl, DeviceRepositoryImpl
  sync/          ← HttpServer, HttpClient, UdpDiscovery, SyncService,
                    SyncWorker, SyncManager, BootReceiver, WifiReceiver

notifications/   ← NotificationScheduler, DueNotificationWorker
core/            ← DeviceIdManager, PreferencesManager
di/              ← AppModule, DatabaseModule, WorkerModule

ui/
  theme/         ← Color, Theme, Type
  navigation/    ← Screen (sealed), NavGraph
  onboarding/    ← OnboardingScreen, QrCodeScreen + ViewModels
  tasks/         ← TaskListScreen, TaskDetailScreen, CreateTaskScreen,
                    EditTaskContent + ViewModels
  settings/      ← SettingsScreen + ViewModel
```

### Синхронизация — ключевой поток

```
WiFi подключилось
  └─ WifiReceiver → SyncService.ACTION_SYNC_NOW
       │
       ├─ UdpDiscovery  ─── broadcast 255.255.255.255:9090 каждые 30 сек
       │   (получен ответ)  └─ SyncManager.setPeerIp(ip)
       │                        └─ triggerSync()
       │
       └─ HttpServer :8080
           POST /sync  ←── входящий запрос от партнёра
           └─ MergeTasksUseCase → ответ с локальными задачами

WorkManager периодически каждые 5 минут:
  SyncWorker → httpClient.sync(peerIp) → MergeTasksUseCase
```

---

## Протокол синхронизации

**Единственный эндпоинт: `POST /sync`**

Тело запроса = тело ответа (одна структура):
```json
{
  "deviceId": "aaa-111",
  "tasks": [{ "id": "uuid", "title": "...", "updatedAt": 1718050000000, "deleted": false, ... }],
  "devices": [{ "deviceId": "aaa-111", "displayName": "Мама", "color": -12345678 }]
}
```

**Правило мёржа** (`MergeTasksUseCase`):
- Задачи нет локально → добавить
- Задача есть → побеждает бо́льший `updatedAt`
- `updatedAt` одинаковый → оставить локальную (tie-break)
- **Удаление = tombstone**: `deleted = true` + обновить `updatedAt`. UI фильтрует `deleted = false`.

---

## Модель данных

### Task

| Поле | Тип | Примечание |
|---|---|---|
| id | String (UUID) | генерируется при создании |
| title | String | обязательно, макс. 200 символов |
| description | String? | — |
| assignedTo | String? | deviceId назначенного, null = никому |
| status | TaskStatus | `todo / in_progress / done` |
| priority | Priority | `low / normal / high` |
| dueDate | Long? | Unix timestamp мс |
| createdBy | String | deviceId создателя |
| createdAt | Long | неизменяемый |
| updatedAt | Long | обновляется при каждом изменении |
| deleted | Boolean | tombstone-флаг, default `false` |

В Room enum'ы хранятся как String (`status.value`, `priority.value`). Конвертация — extension-функции `toDomain()` / `toEntity()` в `TaskEntity.kt`.

### Device (SharedPreferences + Room)

`deviceId` генерируется один раз при первом запуске и хранится вечно. Сопряжение через QR-код (JSON: `{ deviceId, displayName, color }`). Сопряжённый `deviceId` хранится в `PreferencesManager.pairedDeviceId`.

---

## DI (Hilt)

- `AppModule` — `@Binds` для `TaskRepository`, `DeviceRepository`
- `DatabaseModule` — `@Provides` для `AppDatabase`, `TaskDao`, `DeviceDao`
- `WorkerModule` — `@Provides` для `WorkManager`
- `SyncManager` — `@Singleton`, StateFlow с peerIp / syncState / lastSyncAt
- `FamilyTasksApplication` реализует `Configuration.Provider` → `HiltWorkerFactory`

Воркеры используют `@HiltWorker` + `@AssistedInject` (`SyncWorker`, `DueNotificationWorker`).

---

## Уведомления

Три канала: `ch_due_date`, `ch_new_task`, `ch_status`.

| Событие | Канал | Условие |
|---|---|---|
| Срок задачи | ch_due_date | за 1 день и за 1 час до `dueDate` |
| Новая задача | ch_new_task | `assignedTo` != мой deviceId |
| Смена статуса | ch_status | задачу создал я, изменил партнёр |

Планирование через `OneTimeWorkRequestBuilder` с `setInitialDelay`. Каждое уведомление можно отключить в настройках.

---

## Разрешения

```xml
INTERNET, ACCESS_WIFI_STATE, CHANGE_WIFI_STATE
CHANGE_WIFI_MULTICAST_STATE  ← критично для UDP broadcast (без него пакеты фильтруются)
ACCESS_NETWORK_STATE
FOREGROUND_SERVICE, FOREGROUND_SERVICE_CONNECTED_DEVICE, FOREGROUND_SERVICE_DATA_SYNC
POST_NOTIFICATIONS            ← требует явного разрешения на Android 13+
RECEIVE_BOOT_COMPLETED
USE_EXACT_ALARM, SCHEDULE_EXACT_ALARM
CAMERA                        ← для сканирования QR
```

`SyncService` объявлен с `android:foregroundServiceType="connectedDevice|dataSync"` (обязательно для Android 14+).

### Cleartext HTTP (Android 9+)

Синхронизация идёт по HTTP (не HTTPS). Android 9+ блокирует cleartext-трафик по умолчанию. В `AndroidManifest.xml` подключён `@xml/network_security_config` с `cleartextTrafficPermitted="true"`. **Без этого OkHttp бросает исключение ещё до отправки запроса** — `HttpClient.sync()` вернёт `null`, и UI покажет "Ошибка синхронизации" вне зависимости от состояния сети.

---

## Ключевые файлы

| Файл | Зачем читать первым |
|---|---|
| `SyncService.kt` | точка входа всей синхронизации |
| `MergeTasksUseCase.kt` | логика разрешения конфликтов |
| `UdpDiscovery.kt` | обнаружение устройств, MulticastLock |
| `HttpServer.kt` | NanoHTTPD сервер, обработка POST /sync |
| `SyncDto.kt` | сериализуемые DTO + маппинг domain ↔ DTO |
| `PreferencesManager.kt` | все ключи SharedPreferences |
| `TaskListViewModel.kt` | фильтрация, сортировка, compose state |

---

## Известные ограничения (из спецификации)

- **Last-write-wins**: если оба офлайн редактировали одну задачу одновременно, одно изменение будет потеряно молча — это ожидаемое поведение для семейного приложения с двумя пользователями.
- **UDP broadcast**: может быть заблокирован в корпоративных сетях. Для домашней сети не актуально. Fallback — ручной ввод IP в настройках (не реализован, добавить при необходимости).
- **Два устройства**: архитектура рассчитана строго на два. Третье потребует изменения логики sync и UI.
- **Фоновая работа**: `SyncService` как Foreground Service — единственный надёжный способ держать HTTP-сервер и UDP-листенер живыми.
- **WifiReceiver не работает на Android 8+**: `WIFI_STATE_CHANGED` входит в список implicit broadcasts, которые Android 8+ не доставляет ресиверам из манифеста. Триггер синхронизации при смене WiFi фактически не срабатывает — полагаемся на UDP-цикл (30 сек) и WorkManager (5 мин).

---

## Конвенции

- Enum-значения хранятся как строки в Room: `"todo"`, `"in_progress"`, `"done"`, `"low"`, `"normal"`, `"high"`.
- `updatedAt` обновляется через `System.currentTimeMillis()` при каждом изменении задачи — никогда не проставляется вручную в UI.
- Удаление задач — только `taskRepository.deleteTask(id, updatedAt)`, который ставит `deleted = true`. Физического удаления нет.
- StateFlow для UI-состояния, `collectAsStateWithLifecycle()` в Compose.
- `combine` с >5 потоками: сначала сгруппировать в промежуточные data-классы (см. `TaskListViewModel`).

### Данные об устройствах — два источника

- **Room (`devices`)** — хранит данные о партнёре (получены через QR или sync). Своё устройство там **не хранится**.
- **PreferencesManager** — единственный источник для своего `displayName` и `avatarColor`.

Следствия:
1. `SyncService.buildDevicesPayload()` всегда добавляет себя из `PreferencesManager`, чтобы партнёр получал актуальное имя при каждом sync.
2. `CreateTaskViewModel` и `TaskDetailViewModel` при формировании списка "назначить" синтезируют запись о себе из `PreferencesManager` — **не из Room**.
3. `onPeerDiscovered` в `UdpDiscovery` **не перезаписывает** `displayName`/`color`, если запись уже есть в базе — только обновляет `lastSeenAt`.

### HttpServer — чтение тела запроса

`NanoHTTPD` отдаёт `InputStream`; `InputStream.read()` не гарантирует чтение всех байт за один вызов. Всегда использовать `DataInputStream(session.inputStream).readFully(body)`.

### triggerSync — защита от параллельных вызовов

`triggerSync()` проверяет `syncState == SYNCING` и возвращается без действий, если синхронизация уже идёт. Оба устройства делают UDP-broadcast одновременно — без этой защиты возникает гонка состояний.
