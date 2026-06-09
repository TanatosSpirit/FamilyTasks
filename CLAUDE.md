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
                    SyncManager, BootReceiver

notifications/   ← NotificationScheduler, DueNotificationWorker
core/            ← DeviceIdManager, PreferencesManager, AppLogger
di/              ← AppModule, DatabaseModule, WorkerModule

ui/
  theme/         ← Color, Theme, Type
  navigation/    ← Screen (sealed), NavGraph
  onboarding/    ← OnboardingScreen, QrCodeScreen + ViewModels
  tasks/         ← TaskListScreen, TaskDetailScreen, CreateTaskScreen,
                    EditTaskContent + ViewModels
  settings/      ← SettingsScreen + ViewModel
  logs/          ← LogsScreen, LogsViewModel
```

### Синхронизация — ключевой поток

```
WiFi подключилось
  └─ wifiReceiver → udpDiscovery.restartDiscovery(resetCycle=true)
       │
       ├─ UdpDiscovery  ─── burst 5 пакетов, затем backoff: 30 сек → 5 мин → 15 мин
       │   (получен ответ)  └─ onPeerDiscovered() [ровно один раз — peerDiscovered flag]
       │                        └─ SyncManager.setPeerIp(ip) → triggerSync()
       │                             │
       │                             ├─ Sync OK → lastSyncAt обновлён
       │                             └─ PeerUnreachableException (HTTP timeout)
       │                                  └─ peerIp = null
       │                                     restartDiscovery(resetCycle=false)
       │                                          └─ burst → ответ партнёра → triggerSync()
       │
       └─ HttpServer :8080
           POST /sync  ←── входящий запрос от партнёра
           └─ MergeTasksUseCase → ответ с локальными задачами

Задача изменилась (debounce 3 сек):
  observeTaskChanges() → triggerSync()
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
| `AppLogger.kt` | встроенное логирование — singleton, circular buffer 500 записей |

---

## Известные ограничения (из спецификации)

- **Last-write-wins**: если оба офлайн редактировали одну задачу одновременно, одно изменение будет потеряно молча — это ожидаемое поведение для семейного приложения с двумя пользователями.
- **UDP broadcast**: может быть заблокирован в корпоративных сетях. Для домашней сети не актуально. Fallback — ручной ввод IP в настройках (не реализован, добавить при необходимости).
- **Два устройства**: архитектура рассчитана строго на два. Третье потребует изменения логики sync и UI.
- **Фоновая работа**: `SyncService` как Foreground Service — единственный надёжный способ держать HTTP-сервер и UDP-листенер живыми.
- **UDP backoff**: при подключении к WiFi `SyncService` сразу запускает burst (5 UDP-пакетов с паузой 1.5 сек). Если партнёр не ответил — backoff: 30 сек × 4 цикла, затем 5 мин × 6 циклов, затем 15 мин. Резервный триггер — WorkManager каждые 5 мин. При HTTP timeout backoff **не сбрасывается** — `restartDiscovery(resetCycle=false)` продолжает с текущей фазы. Сброс (`resetCycle=true`) только при переподключении WiFi.

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

### PeerUnreachableException — recovery без дедлока

При `PeerUnreachableException` в `triggerSync()` (HTTP timeout) важно вызвать `udpDiscovery?.restartDiscovery(scope, resetCycle = false)` **сразу после** очистки `peerIp`. Без этого возникает дедлок: `peerIp = null`, `broadcastJob` паузнут ещё до sync, discovery молчит — выйти можно только переподключив WiFi.

### UdpDiscovery — состояние между перезапусками

`cycleCount` и `peerDiscovered` — `@Volatile` поля класса, не локальные переменные.

- **`peerDiscovered`**: предотвращает повторные вызовы `onPeerDiscovered` при получении нескольких UDP-ответов за один burst. `sendBroadcast()` при этом вызывается на каждый пакет — это нужно, чтобы партнёр мог узнать наш IP при переподключении. Сбрасывается только в `restartDiscovery()`.
- **`cycleCount`**: сохраняет фазу backoff между перезапусками. `restartDiscovery(resetCycle=true)` сбрасывает его в 0 (WiFi reconnect — ищем быстро); `resetCycle=false` сохраняет (HTTP failure — не спамим).

### AppLogger — встроенный лог-вьюер

`AppLogger` — Kotlin `object` (синглтон без DI) в `core/`. Хранит последние 500 записей в `ArrayDeque` в памяти, публикует их через `StateFlow<List<LogEntry>>`. Каждый вызов дублируется в Logcat.

```kotlin
AppLogger.d(TAG, "сообщение")   // уровни: d / i / w / e
AppLogger.e(TAG, "ошибка", throwable)
```

Покрытые источники: `SyncService`, `HttpClient`, `HttpServer`, `UdpDiscovery`, `MergeTasksUseCase`, `BootReceiver`, `NotificationScheduler`.

Доступ в UI: **Настройки → Диагностика → Логи синхронизации**. Там же кнопка Share — экспортирует лог в `familytasks_log.txt` через стандартный Android Share Sheet (FileProvider, `cache/logs/`).

**Удаление**: удалить `core/AppLogger.kt`, `ui/logs/`, убрать `Screen.Logs` из навигации, секцию «Диагностика» из `SettingsScreen`, заменить `AppLogger.` на `Log.` в sync-файлах и `MergeTasksUseCase.kt` (Find & Replace).

### Логирование энергозатратных событий

Следующие события логируются специально для анализа энергопотребления:

| Событие | Файл | Уровень | Что анализировать |
|---|---|---|---|
| `MulticastLock acquired/released` | `UdpDiscovery` | I | Время удержания лока — прямой расход заряда WiFi-чипа |
| `Burst cycle N — next in Xs` | `UdpDiscovery` | D | Переход между фазами backoff (30с → 5мин → 15мин) |
| `UDP broadcast paused — peer found` | `UdpDiscovery` | I | Момент, когда радио перестаёт слать burst'ы |
| `WiFi connected/disconnected` | `SyncService` | I | Изменение состояния сети |
| `Boot completed — starting SyncService` | `BootReceiver` | I | Холодный старт по загрузке системы |
| `Schedule notification [tag] in Nm` | `NotificationScheduler` | D | Выставление будильника через WorkManager |
| `Cancel notifications for task ...` | `NotificationScheduler` | D | Отмена будильника WorkManager |
| `triggerSync skipped — no peer IP` | `SyncService` | D | Бесполезная работа (дебаунс сработал, но пира нет) |
| `triggerSync skipped — already syncing` | `SyncService` | D | Конкурентные вызовы sync |
| `Peer unreachable: ... — clearing IP, restarting discovery` | `SyncService` | W | HTTP timeout: IP очищен, discovery перезапущен с сохранённым backoff |
