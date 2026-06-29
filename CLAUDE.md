# FamilyTasks — CLAUDE.md

Android-приложение для совместного управления задачами между двумя устройствами в одной WiFi-сети. Работает **полностью офлайн**, без серверов и облачных сервисов.

---

## Быстрый старт

> **Сборкой и коммитами занимается пользователь.** Не запускать `./gradlew` самостоятельно и не предлагать / не выполнять `git commit` — команды ниже приведены для справки.

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
  sync/          ← HttpServer, HttpClient, SubnetScanner, SyncService,
                    SyncManager, BootReceiver, KeepAliveReceiver,
                    ExportImportManager

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

Четыре события запускают работу sync-слоя:

- **WiFi подключилось** (`ipTrackingCallback.onAvailable`): если `peerIp == null` → `SubnetScanner.scan()`; если `peerIp != null` → verify сохранённого IP через GET /identify (до 3 попыток, см. «WiFi reconnected — verify retry»), при провале → `peerIp = null` → scan.
- **WiFi отключилось** (`wifiReceiver`): `scanJob`/`verifyJob` отменяются, `peerIp` **не** очищается (переподключение к той же сети сразу пробует сохранённый IP).
- **Задача изменилась** (`observeTaskChanges`, debounce 3 сек) → `triggerSync()`; если `peerIp == null` и scan не идёт → scan.
- **keepAlive** (`AlarmManager.setExactAndAllowWhileIdle`, раз в час, срабатывает в Doze) → `performKeepAlive()`: verify `peerIp` либо scan, если IP потерян.

`SubnetScanner` шлёт параллельные GET /identify по подсети (concurrency=10, timeout 1.5 сек); найдя `deviceId == pairedDeviceId`, вызывает `setPeerIp(ip)` → `triggerSync()`. Входящие запросы обрабатывает `HttpServer :8080` (GET /identify, POST /sync) — он же пассивно восстанавливает `peerIp` из заголовка `remote-addr`.

**Полные диаграммы** (layer / flowchart / sequence / peerIp-recovery) — в `docs/architecture.md`. Ключевые правила обработчиков — в разделе «Конвенции» ниже.

---

## Протокол синхронизации

**Два эндпоинта на порту 8080:**

### GET /identify
Используется `SubnetScanner` при сканировании подсети и `keepAlive` для проверки актуальности IP.
```
→ GET /identify                      ← scan: отправитель не идентифицирует себя
→ GET /identify?deviceId=aaa-111     ← keepAlive: отправитель сообщает свой deviceId
← { "deviceId": "aaa-111" }
```
Параметр `deviceId` опциональный. Если передан и совпадает с `pairedDeviceId`, получатель сохраняет IP отправителя из заголовка `remote-addr` — **пассивное восстановление IP без scan**. Не требует аутентификации.

### POST /sync
Тело запроса = тело ответа (одна структура):
```json
{
  "deviceId": "aaa-111",
  "tasks": [{ "id": "uuid", "title": "...", "updatedAt": 1718050000000, "deleted": false, "completedAt": 1718049000000, ... }],
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
| status | TaskStatus | `todo / done` (два состояния) |
| priority | Priority | `low / normal / high` |
| dueDate | Long? | Unix timestamp мс |
| createdBy | String | deviceId создателя |
| createdAt | Long | неизменяемый |
| updatedAt | Long | обновляется при каждом изменении |
| deleted | Boolean | tombstone-флаг, default `false` |
| completedAt | Long? | Unix timestamp мс момента перехода в `done`; null если статус не `done` или задача завершена до версии 2 БД |
| recurrence | Recurrence | правило повтора; хранится строкой `repeatRule` (`"none"`, `"days:N"`, `"weekdays:mon,wed,sat"`). См. «Повторяющиеся задачи» |
| isSecret | Boolean | `false` по умолчанию. Только локально: не передаётся при WiFi-sync и не попадает в файловый экспорт. В `TaskDto` поля нет — `isSecret` никогда не покидает устройство. В списке отображается иконкой замка. |

В Room enum'ы и `recurrence` хранятся как String (`status.value`, `priority.value`, `recurrence.serialize()`). Конвертация — extension-функции `toDomain()` / `toEntity()` в `TaskEntity.kt`.

**Схема БД: версия 4.** `MIGRATION_1_2` добавила `completedAt INTEGER` (nullable); `MIGRATION_2_3` добавила `repeatRule TEXT NOT NULL DEFAULT 'none'`; `MIGRATION_3_4` добавила `isSecret INTEGER NOT NULL DEFAULT 0`. Все в `AppDatabase.kt`. Задачи, завершённые до v2, имеют `completedAt = null`.

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

## Повторяющиеся задачи

Задача с дедлайном и правилом повтора (`«Каждые 1..7 дней»` или `«По дням недели»`) **в момент прохождения дедлайна** создаёт клон себя со сдвинутым дедлайном и гасит собственный повтор; клон продолжает цепочку. Обрывается нажатием «Готово». Триггер — `RecurrenceSpawnWorker` (`OneTimeWorkRequest` на момент `dueDate`), отдельный от `DueNotificationWorker`.

Ключевые решения: спавн **owner-only** (`assignedTo == myDeviceId`) — убирает распределённую дедупликацию, напарник получает клон по `/sync`; «гашение родителя» (`recurrence = None`) как маркер бесплодности = идемпотентность без поля схемы; правило хранится **строкой** `repeatRule` — новые подтипы добавляются без миграции и правок движка (движок вызывает только `nextDueDate()`). Планирование/отмена — `NotificationScheduler.scheduleRecurrenceSpawn` / `cancelRecurrenceSpawn`, из тех же мест, что и due-уведомления.

Полное описание, ограничения, план будущих изменений и список тестов: `docs/recurring-tasks.md`.

---

## Синхронизация по файлу (удалённый режим)

Дополнительный механизм для случая когда партнёры в разных сетях. Настройки → «Поделиться задачами» → Share Sheet → файл `familytasks_export.json` в мессенджер. Партнёр открывает файл → «Открыть в FamilyTasks» → автоматический merge → Toast с результатом.

Использует тот же `SyncPayload` формат и `MergeTasksUseCase` что и WiFi-sync. Экспорт — всегда полный снимок (`sinceTimestamp = 0`), delta sync не применяется. Tombstone purge откладывается до следующего WiFi-синка. Повторяющиеся задачи и уведомления работают идентично — оба механизма идут через один `mergeAll()`. Импорт защищён проверкой `payload.deviceId == pairedDeviceId`.

Точка входа — `ExportImportManager.kt`. Подробнее: `docs/file-sync.md`.

---

## Разрешения

```xml
INTERNET, ACCESS_WIFI_STATE, CHANGE_WIFI_STATE
ACCESS_NETWORK_STATE
FOREGROUND_SERVICE, FOREGROUND_SERVICE_SPECIAL_USE
POST_NOTIFICATIONS            ← требует явного разрешения на Android 13+
RECEIVE_BOOT_COMPLETED
USE_EXACT_ALARM, SCHEDULE_EXACT_ALARM
REQUEST_IGNORE_BATTERY_OPTIMIZATIONS  ← запрос исключения из Doze
CAMERA                        ← для сканирования QR
```

`SyncService` объявлен с `android:foregroundServiceType="specialUse"` + `<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" .../>` (обязательное текстовое описание use-case, без него `startForeground()` падает на API 34+). Выбран `specialUse`, а не `dataSync` (тот на Android 15 ограничен 6 ч/сутки → краш). Тип доступен с API 34; на API 29–33 `startForeground()` вызывается без типа. Подробнее: `docs/foreground-service-type.md`.

### Cleartext HTTP (Android 9+)

Синхронизация идёт по HTTP (не HTTPS). Android 9+ блокирует cleartext-трафик по умолчанию. В `AndroidManifest.xml` подключён `@xml/network_security_config` с `cleartextTrafficPermitted="true"`. **Без этого OkHttp бросает исключение ещё до отправки запроса** — `HttpClient.sync()` вернёт `null`, и UI покажет "Ошибка синхронизации" вне зависимости от состояния сети. Это же правило касается `SubnetScanner`: запросы к `/identify` тоже идут по HTTP.

### Достижимость в фоне (Doze + Samsung One UI)

Foreground Service **не выводит приложение из Doze** и не держит CPU/WiFi — заблокированный телефон перестаёт отвечать на `/identify` и `/sync`. Лечится через `keepAlive` на `AlarmManager.setExactAndAllowWhileIdle()` (срабатывает в Doze) + `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`. Реализован уровень 1 (Doze whitelist) — постоянные `WifiLock`/`WakeLock` сознательно не используются ради батареи.

**Важно:** code-only недостаточно — One UI требует ручной настройки батареи на ОБОИХ телефонах. Полный разбор, объяснение уровней и пользовательский чек-лист: `docs/background-reachability.md`.

---

## Ключевые файлы

| Файл | Зачем читать первым |
|---|---|
| `SyncService.kt` | точка входа всей синхронизации |
| `MergeTasksUseCase.kt` | логика разрешения конфликтов |
| `SubnetScanner.kt` | обнаружение партнёра сканированием подсети |
| `HttpServer.kt` | NanoHTTPD сервер, GET /identify + POST /sync |
| `HttpClient.kt` | два OkHttpClient: syncClient (5/10 сек) и scanClient (1.5 сек) |
| `SyncDto.kt` | сериализуемые DTO + маппинг domain ↔ DTO, включая IdentifyResponse |
| `PreferencesManager.kt` | все ключи SharedPreferences |
| `TaskListViewModel.kt` | фильтрация, сортировка, compose state |
| `AppLogger.kt` | встроенное логирование — singleton, circular buffer 500 записей |
| `ExportImportManager.kt` | экспорт/импорт задач через файл для удалённого режима |
| `docs/database-access.md` | все обращения к БД: какие запросы, когда, в каком объёме |

---

## Энергопотребление

Три фазы: discovery (scan ~2–5 сек при старте/сбое), idle (keepAlive раз в час), sync (один POST /sync с debounce 3 сек). Предыдущий UDP+MulticastLock потреблял ~600 мАч/сутки за счёт постоянного удержания WiFi-чипа вне power-save; HTTP-подход полностью устранил эту нагрузку. Если scan >10 сек — роутер не отвечает ICMP unreachable и 253 запроса ждут полного таймаута 1.5 сек (~38 сек при 10 параллельных). Подробнее: `docs/energy-profiles.md`.

---

## Известные ограничения

- **Last-write-wins**: если оба офлайн редактировали одну задачу одновременно, одно изменение будет потеряно молча — ожидаемое поведение для семейного приложения с двумя пользователями.
- **Subnet /24**: `SubnetScanner` сканирует только первые три октета своего IP (192.168.1.*). Сети с маской /20 или /16 не поддерживаются — часть хостов будет недоступна.
- **Два устройства**: архитектура рассчитана строго на два. Третье потребует изменения логики sync и UI.
- **Фоновая работа**: `SyncService` как Foreground Service — единственный надёжный способ держать HTTP-сервер живым.
- **keepAlive раз в час**: если IP партнёра изменился, а задачи не менялись, максимальная задержка обнаружения — 1 час. На практике смена IP в домашней сети происходит при перезагрузке роутера; sync fail триггерит scan мгновенно.

---

## Конвенции

- Enum-значения хранятся как строки в Room: `"todo"`, `"done"`, `"low"`, `"normal"`, `"high"`.
- `updatedAt` обновляется через `System.currentTimeMillis()` при каждом изменении задачи — никогда не проставляется вручную в UI.
- Удаление задач — только `taskRepository.deleteTask(id, updatedAt)`, который ставит `deleted = true`. Физического удаления нет.
- `completedAt` выставляется автоматически в `TaskDetailViewModel` (`cycleStatus` и `updateTask`): при переходе в `DONE` → `System.currentTimeMillis()`, при переходе обратно — `null`. В `EditTaskContent` та же логика применяется на стороне UI перед вызовом `onSave`. Никогда не проставлять вручную.
- `dueDate` редактируется как при создании (`CreateTaskScreen`), так и при редактировании (`EditTaskContent`) — через `DatePickerDialog` + `TimePickerDialog`. При открытии формы редактирования пикер инициализируется текущим значением дедлайна, если оно есть. Кнопка "Убрать" сбрасывает в `null`. Значение передаётся в `task.copy(dueDate = ...)` как `Long?`.
- StateFlow для UI-состояния, `collectAsStateWithLifecycle()` в Compose.
- `combine` с >5 потоками: сначала сгруппировать в промежуточные data-классы (см. `TaskListViewModel`).

### Разделители по датам в списке задач

При `sortOrder == DUE_DATE` и фильтре активных задач список разбивается на именованные секции. Секция создаётся только если на эту дату есть задачи.

| Секция | Условие |
|---|---|
| **Просрочено** | `dueDate < todayStart` (до полуночи сегодняшнего дня) |
| **Сегодня / Завтра** | точное сравнение с полуночью нужного дня |
| **Пт 25 июля** | остальные будущие дни (`SimpleDateFormat("EE d MMMM", Locale("ru"))`) |
| **Без срока** | `dueDate == null` |

При сортировке по приоритету/дате создания и в режиме «Готово» разделители не показываются. Реализовано через `sealed class TaskListItem { Header; Item }` и `buildDateGroupedList()` в `TaskListScreen.kt` — ViewModel не затронута. Подробнее: `docs/date-group-headers.md`.

### Цвет дедлайна в списке задач

Функция `dueDateColor` в `TaskListScreen.kt` принимает `dueDate`, `status` и `completedAt`.

| Условие | Цвет |
|---|---|
| `status != DONE`, срок истёк | красный |
| `status != DONE`, до срока < 1 час | оранжевый |
| `status != DONE`, до срока ≥ 1 час | серый |
| `status == DONE`, `completedAt == null` | серый (задача из эпохи до v2) |
| `status == DONE`, `completedAt <= dueDate` | зелёный (успели вовремя) |
| `status == DONE`, `completedAt > dueDate` | красный (сдали с опозданием) |

### Данные об устройствах — два источника

- **Room (`devices`)** — хранит данные о партнёре (получены через QR или sync). Своё устройство там **не хранится**.
- **PreferencesManager** — единственный источник для своего `displayName` и `avatarColor`.

Следствия:
1. `SyncService.buildDevicesPayload()` всегда добавляет себя из `PreferencesManager`, чтобы партнёр получал актуальное имя при каждом sync.
2. `CreateTaskViewModel` и `TaskDetailViewModel` при формировании списка "назначить" синтезируют запись о себе из `PreferencesManager` — **не из Room**.

### HttpServer — чтение тела запроса

`NanoHTTPD` отдаёт `InputStream`; `InputStream.read()` не гарантирует чтение всех байт за один вызов. Всегда использовать `DataInputStream(session.inputStream).readFully(body)`.

### HttpServer — IP отправителя и два способа его захвата

NanoHTTPD кладёт IP входящего подключения в заголовок `"remote-addr"`. `SyncService` использует его в двух местах:

1. **POST /sync** → `onSyncRequest(payload, senderIp)`: партнёр нашёл нас через свой scan и прислал sync — сохраняем его IP если `peerIp == null`.
2. **GET /identify?deviceId=...** → `onPeerIdentified(callerDeviceId, senderIp)`: партнёр прислал keepAlive с идентификатором — если это paired-устройство и `peerIp == null`, сохраняем IP и сразу вызываем `triggerSync()`.

Второй механизм покрывает сценарий: наш scan не нашёл партнёра, но его keepAlive дошёл до нас — восстанавливаем связь без повторного scan.

### HttpServer — несколько лямбда-параметров и trailing lambda

`HttpServer` принимает два лямбда-параметра: `onSyncRequest` (suspend) и `onPeerIdentified`. Kotlin trailing lambda синтаксис `HttpServer(...) { }` привязывает блок к **последнему** параметру — то есть к `onPeerIdentified`, а `onSyncRequest` остаётся без значения (ошибка компиляции). Оба параметра передавать **только через именованные аргументы**.

### HttpClient — два клиента с разными таймаутами

`syncClient` (connect 5 сек / read 10 сек) — для POST /sync с полезной нагрузкой.
`scanClient` (connect 1.5 сек / read 1.5 сек) — для GET /identify при сканировании и keepAlive. Короткий таймаут критичен: при 253 хостах лишняя секунда на хост удваивает время scan.

### SubnetScanner — параллелизм и отмена

`flatMapMerge(concurrency = 10)` + `firstOrNull()` — как только найден нужный IP, коллектор отменяется и все 10 параллельных запросов прекращаются. Незавершённые OkHttp-вызовы отпускают потоки после истечения таймаута scanClient (≤1.5 сек). Требует `@OptIn(ExperimentalCoroutinesApi::class)`.

### startSubnetScan — до 3 попыток scan

`startSubnetScan()` делает до **3 попыток** `subnetScanner.scan()` с паузой 5 с между ними. Как только одна из попыток нашла IP — остальные не запускаются.

### startSubnetScan — PARTIAL_WAKE_LOCK на время scan

`startSubnetScan()` держит `PowerManager.PARTIAL_WAKE_LOCK` ("FamilyTasks:scan") на всё время своей корутины: все попытки `subnetScanner.scan()` и паузы между ними. `acquire(120_000L)` — защитный потолок. Освобождается в `finally`, включая путь через `CancellationException`. Без WakeLock устройство уходит в Doze посреди scan и OkHttp-вызовы зависают. Обоснование потолка и зафиксированные замеры: `docs/conventions-rationale.md`.

### performKeepAlive — PARTIAL_WAKE_LOCK на retry-цикл identify

`performKeepAlive()` держит `PowerManager.PARTIAL_WAKE_LOCK` ("FamilyTasks:keepAlive") на весь retry-цикл `/identify` (до 3 попыток с паузами по 3 с). `acquire(30_000L)` — защитный потолок. Освобождается в `finally`. Без WakeLock устройство уходит в Doze посреди цикла и паузы растягиваются. Замеры: `docs/conventions-rationale.md`.

### onSyncRequest — снимок локальных задач берётся после merge, а не до

В обработчике `POST /sync` (`SyncService.kt`, `onSyncRequest`) `syncUseCase.getLocalSnapshot()` вызывается **после** `syncUseCase.mergeAll(...)`. Порядок важен: `mergeAll` пишет входящие задачи партнёра в локальную БД, и ответ должен отражать состояние **после** этой записи.

### triggerSync — защита от параллельных вызовов

`triggerSync()` проверяет `syncState == SYNCING` и возвращается без действий, если синхронизация уже идёт. Оба устройства могут запустить scan одновременно и оба найти друг друга — без этой защиты возникает гонка.

### triggerSync — purge подтверждённых tombstone-задач после успешного sync

После успешного `mergeAll(response...)` (ветка `response.deviceId == prefs.pairedDeviceId`) вызывается `syncUseCase.purgeConfirmedTombstones(localTasks, remoteTasks)` — физически удаляет из локальной БД tombstone-задачи (`deleted = true`), которые мы отправили, и для которых партнёр либо **тоже ответил `deleted = true`**, либо **не прислал задачу вовсе** (значит уже purge'ил). LWW-конфликт по-прежнему защищён: если партнёр ответил живой версией (`deleted = false`, `id in remoteIds`) — задача не удалится. Подробнее: `docs/conventions-rationale.md`.

### triggerSync — delta sync watermark

`triggerSync()` отправляет только задачи с `updatedAt > lastConfirmedSyncTimestamp` (watermark). Watermark продвигается только после подтверждённого round-trip. `onSyncRequest` (responder) симметрично фильтрует свой ответ по `incoming.sinceTimestamp`. Полное описание схемы, guard на clock jump-back и edge cases: `docs/delta-sync.md`.

### MergeTasksUseCase — tombstone для неизвестной задачи игнорируется

Если входящая задача имеет `deleted = true` и её нет в локальной БД (`local == null`) — она считается `unchanged` и не пишется в БД. Это предотвращает бесконечный sync-цикл после `purgeConfirmedTombstones`: purge физически удаляет tombstone, но партнёр (не успевший сделать свой purge) шлёт его обратно. Без этого правила tombstone возвращался бы как «New», писался в БД, срабатывал `observeTaskChanges` → новый `triggerSync` → цикл. Для двухустройственной системы это безопасно: некому реплицировать tombstone дальше. Подробнее: `docs/conventions-rationale.md`.

### Self-loop detection — защита от синка с собой

**Сценарий:** A хранит `peerIp = .3` (бывший IP B); B уходит офлайн, A получает `.3` через DHCP → `peerIp == ownIp` (коллизия возможна и после холодного рестарта — `peerIp` сидируется из `prefs.lastKnownPeerIp`).

**Четыре слоя защиты** в `SyncService.kt`: два IP-check (`onAvailable`, `onLinkPropertiesChanged`) до запуска scan/verify и два deviceId-check (`performKeepAlive`, `triggerSync` по response) на случай гонки. Данные при self-loop не портятся (merge своих со своими = tie-break). Полная таблица слоёв и разбор гонки: `docs/conventions-rationale.md`.

### Механизмы восстановления peerIp

Три независимых пути вернуть `peerIp` после потери:

| Триггер | Когда срабатывает | Задержка |
|---|---|---|
| `triggerSync` → peerIp==null | пользователь изменил задачу | мгновенно |
| `keepAlive` → peerIp==null | раз в час, если scan не запущен | до 1 часа |
| `onPeerIdentified` (входящий keepAlive) | партнёр прислал keepAlive с `?deviceId=` | до 1 часа (на стороне партнёра) |

Guard `scanJob?.isActive != true` в первых двух путях не даёт запустить параллельный scan, если он уже идёт.

### Перезапись устаревшего peerIp без scan

`onSyncRequest` и `onPeerIdentified` обновляют `peerIp` не только когда он `null`, но и когда `senderIp != peerIp` — чтобы сразу отменить текущий scan при DHCP renewal у партнёра. IP перезаписывается только от сопряжённого устройства (проверка `deviceId == pairedDeviceId`). `performKeepAlive` `peerIp` не сохраняет — только проверяет.

`startSubnetScan(confirmedNetwork: Network? = null)` — если `confirmedNetwork != null` (вызов из `onAvailable`), guard `isWifiConnected()` пропускается: сеть гарантирована коллбэком. Без этого `activeNetwork` в момент `onAvailable` ещё не переключился — race condition, при которой scan проваливается.

### performKeepAlive — retry identify и диагностика

`performKeepAlive()` делает до **3 попыток** `/identify` к `peerIp` с паузой 3 с между ними. Только если все три вернули `null` или чужой deviceId — IP объявляется устаревшим и запускается scan. Первый SYN может быть поглощён WiFi power-save, вторая попытка застаёт партнёра уже активным.

В начале каждого `performKeepAlive()` логируется снимок состояния (`KeepAlive diag:`): `isDeviceIdleMode`, `isIgnoringBatteryOptimizations`, статус WiFi, `ownIp`, `peerIp`.

### WiFi reconnected — verify retry и отмена verifyJob

Ветка `peerIp != null` в `ipTrackingCallback.onAvailable`: до **3 попыток** `/identify` с паузой 3 с. Если все три провалились — IP устарел, scan. Retry переживает сетевой глитч сразу после reconnect (характерно для mesh-роуминга), не уходя в лишний scan. Корутина хранится в `verifyJob` и отменяется при `WiFi disconnected` и в `onDestroy()`.

### MergeTasksUseCase — точечный upsert только изменившихся задач

`toUpsert` содержит только задачи со статусом `added` или `updated`. Если `toUpsert` пуст — запись в БД не происходит. Запись неизменившихся задач создаёт лишнюю транзакцию и провоцирует эмиссию в `observeAllTasks()` → паразитный повторный sync через 3 сек (debounce).

```
Merge done: +1 new, ~1 updated, =23 unchanged — writing 2/25 tasks to DB
```
`2/25` = записанных / всего в таблице (активные + tombstone-ы).

### Due-уведомления — отмена привязана к статусу/удалению, а не только к назначению

`NotificationScheduler.scheduleDueNotifications()` / `cancelDueNotifications()` вызываются из: `TaskDetailViewModel` (`cycleStatus`, `updateTask`, `delete`), `CreateTaskViewModel` (только создание) и `MergeTasksUseCase` (входящий sync).

Условие активности: `assignedTo == myDeviceId && status != DONE && !deleted`. `WorkManager.enqueueUniqueWork` persistent — переживает рестарт и сработает в заданное время независимо от статуса, если явно не отменить через `cancelUniqueWork`.

Правила:
- `TaskDetailViewModel.cycleStatus()` и `updateTask()` используют общий `applyDueNotificationState()`: ставит если eligible, иначе отменяет.
- `MergeTasksUseCase` (ветка `updated`) сравнивает `wasEligible`/`isEligible` до и после merge.
- `NotificationScheduler.scheduleDueNotifications()` — если отметка уже в прошлом, явно вызывает `cancelUniqueWork`.

Каждое решение (schedule/cancel/skip) логируется через `AppLogger.d`. Подробнее о баге: `docs/conventions-rationale.md`.

### observeTaskChanges — порядок операторов

В `SyncService.observeTaskChanges()` порядок строгий: `observeAllTasks().distinctUntilChanged().drop(1).debounce(3_000L).collect { triggerSync() }`. **Нельзя менять местами `distinctUntilChanged()` и `drop(1)`** — иначе baseline не устанавливается и после каждого sync возникает лишний `triggerSync()`. Разбор: `docs/conventions-rationale.md`.

### AppLogger — встроенный лог-вьюер

`AppLogger` — Kotlin `object` (синглтон без DI) в `core/`. Хранит последние 500 записей в `ArrayDeque` в памяти, публикует их через `StateFlow<List<LogEntry>>`. Каждый вызов дублируется в Logcat.

```kotlin
AppLogger.d(TAG, "сообщение")   // уровни: d / i / w / e
AppLogger.e(TAG, "ошибка", throwable)
```

Покрытые источники: `SyncService`, `HttpClient`, `HttpServer`, `SubnetScanner`, `MergeTasksUseCase`, `BootReceiver`, `NotificationScheduler`, `TaskDetailViewModel`.

Доступ в UI: **Настройки → Диагностика → Логи синхронизации**. Там же кнопка Share — экспортирует лог в `familytasks_log.txt` через стандартный Android Share Sheet (FileProvider, `cache/logs/`).

**Порядок записей в файле:** новые сверху, старые снизу. При чтении лога читать **снизу вверх** (старые → новые) для восстановления хронологии событий.

**Удаление**: удалить `core/AppLogger.kt`, `ui/logs/`, убрать `Screen.Logs` из навигации, секцию «Диагностика» из `SettingsScreen`, заменить `AppLogger.` на `Log.` в sync-файлах и `MergeTasksUseCase.kt` (Find & Replace).

### Логирование энергозатратных событий

Полная таблица событий с описанием что анализировать — в `docs/log-events.md`.
