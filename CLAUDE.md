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
  └─ wifiReceiver → если peerIp == null → SubnetScanner.scan()
                    если peerIp != null → GET /identify к сохранённому peerIp (проверка актуальности)
                        ├─ ответ совпадает с pairedDeviceId → "peer confirmed" → triggerSync()
                        └─ ответ не совпадает / timeout (delay 1.5 сек перед вызовом) → peerIp = null → SubnetScanner.scan()

WiFi отключилось
  └─ wifiReceiver → scanJob отменяется; peerIp НЕ очищается
       (при переподключении к той же сети triggerSync сразу попробует сохранённый IP)
       (если IP устарел → PeerUnreachableException очистит его и запустит scan)

SubnetScanner.scan() — guard: если WiFi недоступен (isWifiConnected() == false), выход без запуска
       │
       ├─ SubnetScanner — параллельные GET /identify к 192.168.x.1–254
       │   (concurrency=10, timeout 1.5 сек на хост)
       │   (найден deviceId == pairedDeviceId)
       │        └─ SyncManager.setPeerIp(ip) → triggerSync()
       │                 │
       │                 ├─ Sync OK → lastSyncAt обновлён
       │                 └─ PeerUnreachableException (HTTP timeout)
       │                      └─ peerIp = null → SubnetScanner.scan() повторно
       │
       ├─ HttpServer :8080
       │   GET /identify          ←── входящий scan (без параметров) → { deviceId }
       │   GET /identify?deviceId ←── входящий keepAlive от партнёра
       │       └─ onPeerIdentified: deviceId == pairedDeviceId && peerIp == null
       │              └─ SyncManager.setPeerIp(senderIp) → triggerSync()
       │   POST /sync     ←── входящий запрос от партнёра
       │       └─ senderIp из заголовка "remote-addr"
       │          если senderIp != null && deviceId == pairedDeviceId && peerIp == null:
       │              └─ SyncManager.setPeerIp(senderIp)  ← пир нашёл нас раньше
       │          └─ MergeTasksUseCase → ответ с локальными задачами
       │
       └─ keepAlive (раз в час)
           если peerIp != null:
               GET /identify?deviceId=... → peerIp
               ├─ OK  → IP актуален, ничего не делаем
               └─ fail → peerIp = null → SubnetScanner.scan()
           если peerIp == null && scan не запущен:
               └─ SubnetScanner.scan()

Задача изменилась (debounce 3 сек):
  observeTaskChanges() → triggerSync()
      если peerIp == null && scan не запущен → SubnetScanner.scan()
```

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
| status | TaskStatus | `todo / in_progress / done` |
| priority | Priority | `low / normal / high` |
| dueDate | Long? | Unix timestamp мс |
| createdBy | String | deviceId создателя |
| createdAt | Long | неизменяемый |
| updatedAt | Long | обновляется при каждом изменении |
| deleted | Boolean | tombstone-флаг, default `false` |
| completedAt | Long? | Unix timestamp мс момента перехода в `done`; null если статус не `done` или задача завершена до версии 2 БД |

В Room enum'ы хранятся как String (`status.value`, `priority.value`). Конвертация — extension-функции `toDomain()` / `toEntity()` в `TaskEntity.kt`.

**Схема БД: версия 2.** Миграция `MIGRATION_1_2` (в `AppDatabase.kt`) добавила колонку `completedAt INTEGER` (nullable). Задачи, завершённые до обновления, имеют `completedAt = null`.

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
ACCESS_NETWORK_STATE
FOREGROUND_SERVICE, FOREGROUND_SERVICE_CONNECTED_DEVICE, FOREGROUND_SERVICE_DATA_SYNC
POST_NOTIFICATIONS            ← требует явного разрешения на Android 13+
RECEIVE_BOOT_COMPLETED
USE_EXACT_ALARM, SCHEDULE_EXACT_ALARM
REQUEST_IGNORE_BATTERY_OPTIMIZATIONS  ← запрос исключения из Doze
CAMERA                        ← для сканирования QR
```

`CHANGE_WIFI_MULTICAST_STATE` остался в манифесте как наследие UDP-подхода — можно удалить, функциональности не несёт.

`SyncService` объявлен с `android:foregroundServiceType="connectedDevice|dataSync"` (обязательно для Android 14+).

### Cleartext HTTP (Android 9+)

Синхронизация идёт по HTTP (не HTTPS). Android 9+ блокирует cleartext-трафик по умолчанию. В `AndroidManifest.xml` подключён `@xml/network_security_config` с `cleartextTrafficPermitted="true"`. **Без этого OkHttp бросает исключение ещё до отправки запроса** — `HttpClient.sync()` вернёт `null`, и UI покажет "Ошибка синхронизации" вне зависимости от состояния сети. Это же правило касается `SubnetScanner`: запросы к `/identify` тоже идут по HTTP.

### Достижимость в фоне (Doze + Samsung One UI)

Foreground Service **не выводит приложение из Doze** и не держит CPU/WiFi. Когда телефон заблокирован и неподвижен, наступает Doze: CPU уходит в suspend (accept-loop NanoHTTPD не выполняется), сеть для приложения замораживается, WiFi-радио уходит в power-save. Итог — заблокированный на часы телефон перестаёт отвечать партнёру на `/identify` и `/sync`. Симметрично страдает и отправитель: `keepAlive` — это `delay()` в корутине, в Doze таймер откладывается.

**Реализован уровень 1 (Doze whitelist, минимум по батарее).** Это сознательный выбор: `WifiLock`/`WakeLock` **не используются**, чтобы не возвращать постоянный расход батареи (ср. удалённый MulticastLock, ~600 мАч/сутки). Жёсткой гарантии «отклик в любую миллисекунду» нет — WiFi power-save может добавлять задержку на пробуждение или редкий обрыв входящего соединения. Если потребуется надёжнее — следующий шаг это `WifiLock(WIFI_MODE_FULL_HIGH_PERF)` (уровень 2) и постоянный partial `WakeLock` (уровень 3), оба ценой батареи.

Что есть в коде:
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` в манифесте.
- Секция «Фоновая работа» в `SettingsScreen` — показывает статус `PowerManager.isIgnoringBatteryOptimizations()` и кнопку, открывающую системный диалог (`Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`). Статус перечитывается на `ON_RESUME` (диалог не возвращает результат).

**Ручной чек-лист на ОБОИХ телефонах (без него код бессилен — One UI замораживает агрессивнее, чем перекрывает Doze-whitelist):**
1. Приложение → Настройки → Фоновая работа → «Отключить оптимизацию батареи».
2. Настройки телефона → Приложения → FamilyTasks → Батарея → **«Без ограничений»**.
3. Настройки → Батарея → Ограничения фоновой активности → добавить в **«Никогда не отправлять в сон»**; убрать из «Спящих»/«Глубоко спящих».
4. Настройки → Батарея → выключить **«Переводить неиспользуемые приложения в сон»** (или исключить приложение).

Пункты 2–4 из кода недоступны — только пользователь руками. Метки в One UI 8.5 могут чуть отличаться.

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
| `docs/database-access.md` | все обращения к БД: какие запросы, когда, в каком объёме |

---

## Энергопотребление

Механика discovery напрямую влияет на расход батареи, т.к. приложение работает как Foreground Service 24/7.

### Профили активности

**Фаза discovery (scan запущен)**
- `SubnetScanner` шлёт HTTP GET к 253 хостам, 10 параллельно, таймаут 1.5 сек
- На домашней сети (~10 устройств) большинство хостов отвечают `Connection refused` за <50 мс — scan занимает **2–5 сек** в типичном случае
- Запускается один раз при старте/переподключении WiFi, затем только при сбоях

**Фаза idle (партнёр найден)**
- Активность практически нулевая: только keepAlive раз в час (один GET /identify)
- Открытых UDP-сокетов нет — WiFi-чип может уходить в deep sleep между задачами
- NanoHTTPD держит accept loop, но это общий сокет без влияния на power-save

**Фаза sync (задача изменилась)**
- Один POST /sync с debounce 3 сек — идентично любому фоновому HTTP-запросу

### Сравнение с предыдущим UDP-подходом

| | UDP без лока | UDP с MulticastLock | HTTP scan (текущий) |
|---|---|---|---|
| Persistent сокет | UDP :9090 открыт 24/7 | UDP :9090 открыт 24/7 | нет |
| MulticastLock | нет | держится 24/7 | нет |
| WiFi power-save | частично доступен | **заблокирован** | полностью доступен |
| Фоновая активность | burst каждые 15 мин | burst каждые 15 мин | keepAlive 1/час |
| Расход заряда/сутки (MulticastLock) | — | **+~600 мАч** (≈15% на 4000 мАч) | — |
| Работа при AP Isolation | не работает | не работает | **работает** |

MulticastLock удерживал WiFi-чип вне power-save режима постоянно (~25 мА разницы с power-save × 24 ч ≈ 600 мАч). HTTP-подход полностью устранил эту нагрузку.

### Когда scan медленнее ожидаемого

Если scan занимает >10 сек — роутер не отвечает ICMP unreachable на несуществующие хосты, и все 253 запроса ждут полного таймаута 1.5 сек. При 10 параллельных это ~38 сек. Это не ошибка, просто особенность конкретного роутера.

---

## Известные ограничения

- **Last-write-wins**: если оба офлайн редактировали одну задачу одновременно, одно изменение будет потеряно молча — ожидаемое поведение для семейного приложения с двумя пользователями.
- **Subnet /24**: `SubnetScanner` сканирует только первые три октета своего IP (192.168.1.*). Сети с маской /20 или /16 не поддерживаются — часть хостов будет недоступна.
- **Два устройства**: архитектура рассчитана строго на два. Третье потребует изменения логики sync и UI.
- **Фоновая работа**: `SyncService` как Foreground Service — единственный надёжный способ держать HTTP-сервер живым.
- **keepAlive раз в час**: если IP партнёра изменился, а задачи не менялись, максимальная задержка обнаружения — 1 час. На практике смена IP в домашней сети происходит при перезагрузке роутера; sync fail триггерит scan мгновенно.

---

## Конвенции

- Enum-значения хранятся как строки в Room: `"todo"`, `"in_progress"`, `"done"`, `"low"`, `"normal"`, `"high"`.
- `updatedAt` обновляется через `System.currentTimeMillis()` при каждом изменении задачи — никогда не проставляется вручную в UI.
- Удаление задач — только `taskRepository.deleteTask(id, updatedAt)`, который ставит `deleted = true`. Физического удаления нет.
- `completedAt` выставляется автоматически в `TaskDetailViewModel` (`cycleStatus` и `updateTask`): при переходе в `DONE` → `System.currentTimeMillis()`, при переходе обратно — `null`. В `EditTaskContent` та же логика применяется на стороне UI перед вызовом `onSave`. Никогда не проставлять вручную.
- `dueDate` редактируется как при создании (`CreateTaskScreen`), так и при редактировании (`EditTaskContent`) — через `DatePickerDialog` + `TimePickerDialog`. При открытии формы редактирования пикер инициализируется текущим значением дедлайна, если оно есть. Кнопка "Убрать" сбрасывает в `null`. Значение передаётся в `task.copy(dueDate = ...)` как `Long?`.
- StateFlow для UI-состояния, `collectAsStateWithLifecycle()` в Compose.
- `combine` с >5 потоками: сначала сгруппировать в промежуточные data-классы (см. `TaskListViewModel`).

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

### triggerSync — защита от параллельных вызовов

`triggerSync()` проверяет `syncState == SYNCING` и возвращается без действий, если синхронизация уже идёт. Оба устройства могут запустить scan одновременно и оба найти друг друга — без этой защиты возникает гонка.

### Self-loop detection — защита от синка с собой

**Сценарий:** устройство A хранит `peerIp = .3` (бывший IP устройства B). B уходит офлайн, освобождает `.3`. A получает `.3` через DHCP (при reconnect или тихом renewal). Теперь `peerIp == ownIp` — A стучится к себе.

`peerIp` хранится **только в памяти** (`SyncManager` — `@Singleton`, не SharedPreferences), поэтому коллизия возможна исключительно в рамках текущей сессии сервиса.

**Три слоя защиты** (реализованы в `SyncService.kt`):

| Слой | Место | Когда ловит |
|---|---|---|
| IP-check | `ipTrackingCallback.onAvailable` | Reconnect: новый IP известен до wifiReceiver, race condition исключён |
| IP-check | `ipTrackingCallback.onLinkPropertiesChanged` | DHCP renewal без разрыва — wifiReceiver вообще не срабатывает |
| deviceId-check | `startKeepAlive` | Страховка: `identify()` вернул не `pairedDeviceId` |
| deviceId-check | `triggerSync` (response) | Ловит race condition: `peerIp` был захвачен в локальную переменную до того, как IP-check успел вызвать `setPeerIp(null)` |

**Почему проверка в `triggerSync` нужна отдельно:** `peerIp` читается в начале `triggerSync()` в локальную переменную, затем запускается `scope.launch { }`. Если `onAvailable` очистил `peerIp` в StateFlow между этими двумя точками, локальная переменная внутри корутины уже устарела — HTTP-запрос всё равно уйдёт на старый адрес. Проверка `response.deviceId == pairedDeviceId` ловит именно этот случай.

**Поведение при self-loop:** A посылает POST /sync к себе. Сервер выполняет merge своих задач со своими же (tie-break по `updatedAt` — все unchanged, запись в БД не происходит). Ответ возвращает `deviceId = ownDeviceId`. Клиентская проверка видит `ownDeviceId != pairedDeviceId` → `peerIp = null` → scan. Данные не портятся.

### Механизмы восстановления peerIp

Три независимых пути вернуть `peerIp` после потери:

| Триггер | Когда срабатывает | Задержка |
|---|---|---|
| `triggerSync` → peerIp==null | пользователь изменил задачу | мгновенно |
| `keepAlive` → peerIp==null | раз в час, если scan не запущен | до 1 часа |
| `onPeerIdentified` (входящий keepAlive) | партнёр прислал keepAlive с `?deviceId=` | до 1 часа (на стороне партнёра) |

Guard `scanJob?.isActive != true` в первых двух путях не даёт запустить параллельный scan, если он уже идёт.

`startSubnetScan()` дополнительно проверяет `isWifiConnected()` через `ConnectivityManager.getNetworkCapabilities()` — scan не запускается, если WiFi недоступен. Это предотвращает бесполезные scan-циклы после отключения сети, когда задача изменилась или сработал keepAlive при `peerIp == null`.

### MergeTasksUseCase — точечный upsert только изменившихся задач

В цикле merge ведётся отдельный список `toUpsert`, куда попадают только задачи со статусом `added` или `updated`. В конце вызывается `upsertTasks(toUpsert)`, а не весь merged-список. Если `toUpsert` пуст — запись в БД не происходит вообще.

**Почему важно:** `upsertAll()` — одна транзакция, но она касается всех переданных строк. Запись неизменившихся задач — лишний WAL-трафик, который растёт по мере накопления tombstone-ов. Запись при нулевых изменениях создаёт транзакцию и провоцирует эмиссию в `observeAllTasks()` → паразитный повторный sync через 3 секунды (debounce).

Лог-строка после merge:
```
Merge done: +1 new, ~1 updated, =23 unchanged — writing 2/25 tasks to DB
```
Дробь `2/25` = записанных строк / всего в таблице (активные + tombstone-ы).

### observeTaskChanges — порядок операторов

В `SyncService.observeTaskChanges()` операторы стоят в строгом порядке:

```kotlin
taskRepository.observeAllTasks()
    .distinctUntilChanged()  // запоминает initial-список как baseline
    .drop(1)                 // дропает только первую (initial) эмиссию
    .debounce(3_000L)
    .collect { triggerSync() }
```

**Нельзя менять местами `distinctUntilChanged()` и `drop(1)`.** Если `drop(1)` стоит первым, `distinctUntilChanged()` никогда не видит initial-эмиссию, baseline не устанавливается, и первая же эмиссия после дропа (даже с теми же данными) проходит насквозь до `debounce` — что даёт лишний `triggerSync()` через 3 секунды после каждого sync.

### AppLogger — встроенный лог-вьюер

`AppLogger` — Kotlin `object` (синглтон без DI) в `core/`. Хранит последние 500 записей в `ArrayDeque` в памяти, публикует их через `StateFlow<List<LogEntry>>`. Каждый вызов дублируется в Logcat.

```kotlin
AppLogger.d(TAG, "сообщение")   // уровни: d / i / w / e
AppLogger.e(TAG, "ошибка", throwable)
```

Покрытые источники: `SyncService`, `HttpClient`, `HttpServer`, `SubnetScanner`, `MergeTasksUseCase`, `BootReceiver`, `NotificationScheduler`.

Доступ в UI: **Настройки → Диагностика → Логи синхронизации**. Там же кнопка Share — экспортирует лог в `familytasks_log.txt` через стандартный Android Share Sheet (FileProvider, `cache/logs/`).

**Порядок записей в файле:** новые сверху, старые снизу. При чтении лога читать **снизу вверх** (старые → новые) для восстановления хронологии событий.

**Удаление**: удалить `core/AppLogger.kt`, `ui/logs/`, убрать `Screen.Logs` из навигации, секцию «Диагностика» из `SettingsScreen`, заменить `AppLogger.` на `Log.` в sync-файлах и `MergeTasksUseCase.kt` (Find & Replace).

### Логирование энергозатратных событий

Следующие события логируются специально для анализа энергопотребления:

| Событие | Файл | Уровень | Что анализировать |
|---|---|---|---|
| `Scanning 192.168.x.* for ...` | `SubnetScanner` | I | Начало scan — сколько раз запускается в сутки |
| `Peer found at ...` | `SubnetScanner` | I | Успешный scan — как быстро нашли партнёра |
| `Scan complete — peer not on subnet` | `SubnetScanner` | I | Партнёр офлайн — scan прошёл впустую |
| `KeepAlive OK — peer still at ...` | `SyncService` | D | Штатный keepAlive — IP не изменился |
| `KeepAlive failed — peer IP stale` | `SyncService` | I | IP изменился → scan запущен |
| `KeepAlive — no peer IP, starting scan` | `SyncService` | D | peerIp==null при срабатывании таймера → scan |
| `Peer reached us from ... — saving IP` | `SyncService` | I | Партнёр нашёл нас через scan (входящий /sync) |
| `Peer reached us from ... via keepAlive — saving IP` | `SyncService` | I | Партнёр восстановил наш IP пассивно через keepAlive |
| `WiFi connected — starting subnet scan` | `SyncService` | I | peerIp был null → scan запущен при подключении |
| `WiFi reconnected — verifying peer IP ...` | `SyncService` | D | peerIp есть → задержка 1.5 сек + проверка одним /identify |
| `WiFi reconnected — peer confirmed at ...` | `SyncService` | I | /identify подтвердил IP → triggerSync() |
| `WiFi reconnected — peer IP stale, clearing IP, starting scan` | `SyncService` | I | /identify вернул не pairedDeviceId или timeout → IP устарел → scan |
| `WiFi disconnected` | `SyncService` | I | Потеря сети → scan отменён, peerIp сохранён |
| `Boot completed — starting SyncService` | `BootReceiver` | I | Холодный старт по загрузке системы |
| `Schedule notification [tag] in Nm` | `NotificationScheduler` | D | Выставление будильника через WorkManager |
| `Cancel notifications for task ...` | `NotificationScheduler` | D | Отмена будильника WorkManager |
| `triggerSync skipped — no peer IP, starting scan` | `SyncService` | D | Задача изменилась, пир не найден → запустили scan |
| `triggerSync skipped — already syncing` | `SyncService` | D | Конкурентные вызовы sync |
| `Peer unreachable: ... — clearing IP, starting scan` | `SyncService` | W | HTTP timeout: IP очищен, scan перезапущен |
| `Own IP (...) matches peerIp — self-loop detected on connect, clearing peerIp, starting scan` | `SyncService` | W | DHCP дал нам IP бывшего партнёра при reconnect |
| `New IP (...) matches peerIp — self-loop detected on IP change, clearing peerIp, starting scan` | `SyncService` | W | DHCP renewal без разрыва — IP сменился на peerIp |
| `KeepAlive — unexpected deviceId ... at ... (expected ...) — self-loop?` | `SyncService` | W | keepAlive получил не pairedDeviceId — страховочный слой |
| `Sync response from unexpected device ... at ... (expected ...) — self-loop? Clearing IP, starting scan` | `SyncService` | W | triggerSync поймал гонку: ответ пришёл от себя |
