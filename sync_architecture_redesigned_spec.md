# Спецификация синхронизации — новая архитектура

Описание актуальной архитектуры синхронизации после редизайна на событийную модель.

---

## Обзор

Синхронизация состоит из двух независимых задач:

| Задача | Механизм | Цель |
|---|---|---|
| Найти IP партнёра | UDP discovery (burst) | Установить `peerIp` |
| Обменяться задачами | HTTP POST /sync | Обновить базу данных |

Одно без другого не работает: HTTP требует известный `peerIp`, UDP не несёт задачи.

---

## Компоненты

```
SyncService (Foreground Service — контейнер)
  │
  ├── UdpDiscovery      ← находит IP партнёра
  ├── HttpServer :8080  ← принимает входящие sync-запросы
  ├── triggerSync()     ← отправляет исходящий sync-запрос
  └── observeTaskChanges() ← подписка на изменения задач

SyncManager (@Singleton)
  └── peerIp / syncState / lastSyncAt  ← разделяемое состояние
```

---

## Состояние системы

Всё поведение определяется двумя переменными:

```
peerIp:    null      → партнёр не найден, идёт discovery
           "x.x.x.x" → партнёр известен, discovery приостановлен

syncState: IDLE      → ничего не происходит
           SYNCING   → идёт HTTP обмен
           SUCCESS   → последний sync успешен
           ERROR     → последний sync завершился ошибкой
```

---

## Фаза 1 — Обнаружение IP (UdpDiscovery)

### Запуск discovery

При старте `SyncService` (или при потере `peerIp`):
1. Захватить `MulticastLock` — разрешает WiFi-чипу пропускать broadcast-пакеты в CPU
2. Открыть `DatagramSocket` на UDP порту `9090` — начать слушать
3. Запустить burst: отправить **5 UDP-пакетов** на `255.255.255.255:9090` с интервалом **1.5 секунды**
4. После burst broadcast-корутина завершается; listen-корутина продолжает работать

### Формат UDP-пакета

```json
{ "deviceId": "aaa-111", "httpPort": 8080 }
```

### Приём пакета от партнёра

При получении UDP от `pairedDeviceId`:
1. **Немедленно ответить своим пакетом** — иначе партнёр не узнает наш IP
2. Вызвать `onPeerDiscovered(senderIp, deviceId)` → в `SyncService`:
   - `syncManager.setPeerIp(ip)` — запомнить IP
   - `udpDiscovery.pauseDiscovery()` — закрыть сокет, отпустить `MulticastLock`
   - `triggerSync()` — немедленно синхронизировать задачи

### Параметры

| Параметр | Значение |
|---|---|
| UDP порт | 9090 |
| Broadcast-адрес | 255.255.255.255 |
| Количество пакетов в burst | 5 |
| Интервал между пакетами | 1.5 секунды |
| Общее время burst | ~6 секунд |
| Фильтрация входящих | только от `pairedDeviceId` |

### MulticastLock — жизненный цикл

```
SyncService.onCreate()     → acquireLock() + startListening()
onPeerDiscovered()         → pauseDiscovery() → releaseLock()
PeerUnreachableException   → restartDiscovery() → acquireLock()
WiFi disconnect            → pauseDiscovery() → releaseLock()
WiFi connect               → restartDiscovery() → acquireLock()
SyncService.onDestroy()    → stop() → releaseLock()
```

`MulticastLock` держится **только пока `peerIp == null`**. После обнаружения партнёра WiFi-чип возвращается к аппаратной фильтрации broadcast-пакетов.

---

## Фаза 2 — Синхронизация задач (HTTP)

### Когда вызывается triggerSync()

| Событие | Источник |
|---|---|
| Обнаружен IP партнёра | `onPeerDiscovered` в `SyncService` |
| Изменена / создана / удалена задача | `observeTaskChanges()` с debounce 3с |
| Партнёр инициировал sync к нам | — (пассивная сторона, отвечает `HttpServer`) |

### Алгоритм triggerSync()

```
1. peerIp == null?  → выйти (нечего делать)
2. syncState == SYNCING? → выйти (уже идёт)
3. syncState = SYNCING
4. Собрать SyncPayload (все локальные задачи + устройства)
5. HttpClient.sync(peerIp, 8080, payload)
   ├── Успех (200 OK) →
   │     MergeTasksUseCase(response.tasks)
   │     upsertDevices(response.devices)
   │     prefs.lastSyncAt = now
   │     syncState = SUCCESS
   ├── null (ошибка ответа, не timeout) →
   │     syncState = ERROR
   │     peerIp НЕ сбрасывается
   └── PeerUnreachableException (timeout / ConnectException) →
         syncManager.setPeerIp(null)
         syncState = ERROR
         udpDiscovery.restartDiscovery()  ← перейти к фазе 1
```

### Формат запроса/ответа (POST /sync)

Тело запроса = тело ответа:
```json
{
  "deviceId": "aaa-111",
  "tasks": [
    {
      "id": "uuid",
      "title": "Купить молоко",
      "description": null,
      "assignedTo": "bbb-222",
      "status": "todo",
      "priority": "normal",
      "dueDate": 1718050000000,
      "createdBy": "aaa-111",
      "createdAt": 1718000000000,
      "updatedAt": 1718050000000,
      "deleted": false
    }
  ],
  "devices": [
    { "deviceId": "aaa-111", "displayName": "Мама", "color": -12345678 }
  ]
}
```

### HTTP параметры

| Параметр | Значение |
|---|---|
| Эндпоинт | POST /sync |
| Порт | 8080 |
| Протокол | HTTP (cleartext, `network_security_config`) |
| Connect timeout | 5 секунд |
| Read timeout | 10 секунд |
| При timeout → | `PeerUnreachableException` → сброс `peerIp` |
| При прочих ошибках → | `null`, только `ERROR` статус |

### Правило мёржа (MergeTasksUseCase)

- Задачи нет локально → добавить
- `remote.updatedAt > local.updatedAt` → заменить
- `updatedAt` одинаковый → оставить локальную (tie-break)
- Удаление = tombstone: `deleted = true` + обновить `updatedAt`

### Debounce при изменении задач

`SyncService.observeTaskChanges()` подписан на `taskRepository.observeAllTasks()`:

```
Flow<List<Task>>
  .drop(1)              ← пропустить начальную эмиссию при старте
  .distinctUntilChanged() ← не реагировать если список не изменился
  .debounce(3_000L)     ← ждать 3с тишины перед sync
  .collect { triggerSync() }
```

Максимальная задержка между сохранением задачи и sync на партнёре: **3 секунды** (при известном `peerIp`).

---

## Фаза 3 — Пассивный приём (HttpServer)

`HttpServer` на порту `8080` работает всё время жизни `SyncService`, независимо от состояния `peerIp`.

При получении `POST /sync`:
1. Читать тело через `DataInputStream.readFully()` (важно: `InputStream.read()` не гарантирует чтение всех байт)
2. Запустить `MergeTasksUseCase` с задачами из запроса
3. Ответить своим `SyncPayload` (текущий снимок локальных задач)

Партнёр, получив ответ, тоже вызовет `MergeTasksUseCase` — оба устройства синхронизированы за один HTTP-обмен.

---

## WiFi — обработка состояний

Реализована через динамический `BroadcastReceiver` (`WifiManager.NETWORK_STATE_CHANGED_ACTION`), зарегистрированный в `SyncService.onCreate()`. Манифестный receiver не используется (не работает на Android 8+ для implicit broadcasts).

| Событие | Действие |
|---|---|
| WiFi connected | если `peerIp == null` → `restartDiscovery()` |
| WiFi disconnected | `peerIp = null` + `pauseDiscovery()` |
| Сервис уничтожен | `unregisterReceiver()` + `udpDiscovery.stop()` |

---

## Полные сценарии

### Оба устройства приходят домой

```
Устройство A                              Устройство B
     │  [WiFi connected]                       │  [WiFi connected]
     │  SyncService.onCreate()                 │  SyncService.onCreate()
     │  acquireLock()                          │  acquireLock()
     │  startListening()                       │  startListening()
     │                                         │
     │──── UDP burst [1/5] ──────────────────►│
     │◄─── UDP ответ ──────────────────────────│  (B получил, ответил)
     │                                         │
     │  onPeerDiscovered(ipB)                  │  onPeerDiscovered(ipA)
     │  setPeerIp(ipB)                         │  setPeerIp(ipA)
     │  pauseDiscovery() → releaseLock()       │  pauseDiscovery() → releaseLock()
     │  triggerSync()                          │  triggerSync()
     │                                         │
     │──── POST /sync (все задачи A) ────────►│
     │     HttpServer B: merge(задачи A)       │
     │◄─── ответ (все задачи B) ───────────────│
     │  merge(задачи B)                        │
     │  syncState = SUCCESS                    │  syncState = SUCCESS
     │                                         │
     │  [Обе базы синхронизированы, ~6 сек]   │
```

### Пользователь создаёт задачу (оба онлайн)

```
     │  [Нажал "Создать"]
     │  TaskRepository.upsertTask()
     │  observeAllTasks() эмитит новый список
     │  debounce(3_000L)...
     │  [3 секунды тишины]
     │──── POST /sync ────────────────────────►│
     │◄─── ответ ─────────────────────────────│
     │  [Задача у партнёра через ~3 секунды]
```

### Партнёр ушёл из сети

```
     │  triggerSync()
     │──── POST /sync ────────────────────────►│  [недоступен]
     │  SocketTimeoutException (5с)
     │  PeerUnreachableException
     │  setPeerIp(null)
     │  syncState = ERROR
     │  restartDiscovery()
     │  acquireLock(), startListening(), burst
     │  ... ждём UDP от партнёра ...
```

### Партнёр вернулся в сеть

```
     │  [слушаем UDP]                           │  [WiFi connected]
     │                                          │  SyncService запущен / WiFi receiver
     │                                          │  restartDiscovery()
     │◄─── UDP burst от партнёра ───────────────│
     │  sendBroadcast() → ответ партнёру        │
     │  onPeerDiscovered(ipB)                   │  onPeerDiscovered(ipA)
     │  setPeerIp(ipB)                          │  setPeerIp(ipA)
     │  pauseDiscovery()                        │  pauseDiscovery()
     │  triggerSync()                           │  triggerSync()
     │──── POST /sync ────────────────────────►│
     │◄─── ответ ─────────────────────────────│
     │  [Все накопленные изменения синхронизированы]
```

### Задача создана пока партнёр офлайн

```
     │  upsertTask() — сохранено локально
     │  debounce 3с → triggerSync()
     │  peerIp == null → выйти (ничего не делать)
     │  ... партнёр офлайн ...
     │
     │◄─── UDP burst (партнёр вернулся) ────────│
     │  onPeerDiscovered → triggerSync()
     │──── POST /sync (включая новую задачу) ──►│
     │  [Задача доставлена]
```

---

## Сводка параметров

| Параметр | Значение | Файл |
|---|---|---|
| UDP порт | 9090 | `UdpDiscovery.kt` |
| UDP burst, количество пакетов | 5 | `UdpDiscovery.kt` |
| UDP burst, интервал | 1 500 мс | `UdpDiscovery.kt` |
| HTTP порт | 8080 | `HttpServer.kt`, `HttpClient.kt` |
| HTTP connect timeout | 5 000 мс | `HttpClient.kt` |
| HTTP read timeout | 10 000 мс | `HttpClient.kt` |
| Debounce перед sync | 3 000 мс | `SyncService.kt` |
| MulticastLock tag | `"FamilyTasksLock"` | `UdpDiscovery.kt` |
| MulticastLock reference counted | false | `UdpDiscovery.kt` |

---

## Что осталось от старой архитектуры

| Компонент | Статус | Причина |
|---|---|---|
| `SyncService` (Foreground Service) | ✅ оставлен | единственный способ держать HttpServer живым |
| `HttpServer` | ✅ оставлен | принимает входящие sync от партнёра |
| `MergeTasksUseCase` | ✅ оставлен | логика разрешения конфликтов не изменилась |
| `BootReceiver` | ✅ оставлен | запускает SyncService после перезагрузки |
| `WorkManager / SyncWorker` | ⚠️ не планируется | класс оставлен, но не шедулится |
| `WifiReceiver` (манифест) | ❌ мёртвый код | не работает на Android 8+, заменён динамическим receiver |
| UDP broadcast каждые 30с | ❌ удалён | заменён burst при подключении |
| `schedulePeriodicSync()` | ❌ удалён | WorkManager polling не нужен |
