# Обращения к базе данных

Схема БД: три таблицы — `tasks`, `devices`, `task_sync_state`. Все управляются через Room DAO.

---

## TaskDao — справочник запросов

| Метод | SQL | Тип | Объём |
|---|---|---|---|
| `observeActiveTasks()` | `SELECT * FROM tasks WHERE deleted = 0 ORDER BY createdAt DESC` | Flow (живой) | только не-tombstone строки |
| `observeAllTasks()` | `SELECT * FROM tasks ORDER BY createdAt DESC` | Flow (живой) | все строки, включая tombstone-ы |
| `getById(id)` | `SELECT * FROM tasks WHERE id = :id LIMIT 1` | suspend, разовый | 1 строка |
| `getAll()` | `SELECT * FROM tasks` | suspend, разовый | все строки, включая tombstone-ы |
| `upsert(task)` | INSERT OR UPDATE по PK | suspend, разовый | 1 строка |
| `upsertAll(tasks)` | INSERT OR UPDATE по PK, batch | suspend, разовый | N строк в одной транзакции |
| `softDelete(id, updatedAt)` | `UPDATE tasks SET deleted=1, updatedAt=:updatedAt WHERE id=:id` | suspend, разовый | 1 строка |

> `observeActiveTasks()` определён, но нигде не вызывается — `TaskListViewModel` использует `observeAllTasks()` и фильтрует `deleted` в памяти.

---

## DeviceDao — справочник запросов

| Метод | SQL | Тип | Объём |
|---|---|---|---|
| `observeAll()` | `SELECT * FROM devices` | Flow (живой) | как правило 1 строка (партнёр) |
| `getById(id)` | `SELECT * FROM devices WHERE deviceId = :id LIMIT 1` | suspend, разовый | 1 строка |
| `getAll()` | `SELECT * FROM devices` | suspend, разовый | как правило 1 строка |
| `upsert(device)` | INSERT OR UPDATE по PK | suspend, разовый | 1 строка |

> Своё устройство в таблице `devices` **не хранится** — только партнёр. Собственные данные живут в SharedPreferences (`PreferencesManager`).

---

## TaskSyncStateDao — справочник запросов

| Метод | SQL | Тип | Объём |
|---|---|---|---|
| `observeAll()` | `SELECT * FROM task_sync_state` | Flow (живой) | все подтверждённые задачи |
| `upsertAll(entries)` | INSERT OR UPDATE по PK, batch | suspend, разовый | N строк в одной транзакции |
| `deleteByIds(ids)` | `DELETE FROM task_sync_state WHERE taskId IN (:ids)` | suspend, разовый | N строк |

Пишется из `MergeTasksUseCase.invoke()` — единая точка для WiFi и файлового импорта, см. `docs/pending-sync-indicator.md`. Таблица физически отделена от `tasks`: запись сюда не переэмиттит `observeAllTasks()` и не трогает `SyncService.observeTaskChanges()`.

---

## Обращения по ситуациям

### Открытие списка задач (`TaskListScreen`)

Подписки живут, пока экран подписан (`SharingStarted.WhileSubscribed(5000)`):

```
TaskListViewModel.init
  ├─ taskRepository.observeAllTasks()    → Flow подписка, все задачи вкл. tombstone-ы
  └─ deviceRepository.observeDevices()  → Flow подписка, устройство партнёра
```

Фильтрация `deleted`, статуса и исполнителя, а также сортировка — в памяти поверх этих Flow.

---

### Открытие экрана создания задачи (`CreateTaskScreen`)

```
CreateTaskViewModel.init
  └─ deviceRepository.getAllDevices()   → разовое чтение, ~1 строка (для списка "Назначить")
```

---

### Открытие деталей задачи (`TaskDetailScreen`)

```
TaskDetailViewModel.load(taskId)
  ├─ taskRepository.getTaskById(id)     → разовое чтение, 1 строка
  └─ deviceRepository.getAllDevices()   → разовое чтение, ~1 строка
```

> Деталь-экран не подписан на Flow — если во время просмотра пришёл sync и задача обновилась, на экране останутся устаревшие данные до следующего `load()`.

---

### Пользователь создаёт задачу

```
CreateTaskViewModel.save()
  └─ taskRepository.upsertTask(task)    → запись 1 строки
       └─ Room эмитирует в observeAllTasks()
            └─ SyncService.observeTaskChanges: debounce 3s → triggerSync()
```

---

### Пользователь редактирует задачу или меняет статус

```
TaskDetailViewModel.updateTask() / cycleStatus()
  └─ taskRepository.upsertTask(task)    → запись 1 строки
       └─ (тот же каскад в observeTaskChanges)
```

---

### Пользователь удаляет задачу

```
TaskDetailViewModel.delete()
  └─ taskRepository.deleteTask(id, now) → softDelete: UPDATE 1 строки (deleted=1)
       └─ (тот же каскад в observeTaskChanges)
```

Физического удаления нет — строка остаётся как tombstone.

---

### Исходящий sync (`triggerSync` → POST /sync к партнёру)

Две полные выборки таблицы `tasks` на каждый sync:

```
triggerSync()
  ├─ syncUseCase.getLocalSnapshot()
  │    └─ taskDao.getAll()              → чтение ВСЕХ задач (активные + tombstone-ы)
  ├─ syncUseCase.getLocalDevices()
  │    └─ deviceDao.getAll()            → чтение ~1 строки
  │
  │  ... POST /sync → ответ от партнёра ...
  │
  └─ syncUseCase.mergeAll(remoteTasks, remoteDevices)
       └─ MergeTasksUseCase.invoke(remoteTasks)
            ├─ taskRepository.getAllTasksSnapshot()
            │    └─ taskDao.getAll()    → второе чтение ВСЕХ задач (для merge-map)
            ├─ taskRepository.upsertTasks(toUpsert)
            │    └─ taskDao.upsertAll() → запись только изменившихся задач
            │                             (0..N строк, N = added + updated)
            └─ taskSyncStateRepository.confirm(confirmedEntries)
                 └─ taskSyncStateDao.upsertAll() → запись подтверждений (0..N строк,
                                                    без доп. чтения — сравнение уже
                                                    в памяти из шага выше)
       └─ для каждого remoteDevice:
            deviceRepository.upsertDevice(device)
              └─ deviceDao.upsert()     → запись ~1 строки
```

**Двойное чтение `getAll()`** — известный компромисс: `getLocalSnapshot()` нужен для формирования исходящего payload до отправки, а `MergeTasksUseCase` читает заново уже после получения ответа, чтобы иметь актуальный snapshot для merge-map. Если между двумя чтениями пользователь изменил задачу локально — merge корректно подхватит это изменение.

---

### Входящий sync (партнёр прислал POST /sync)

Аналогично исходящему, но в обратном порядке: сначала merge, потом ответ.

```
HttpServer.onSyncRequest(incoming, senderIp)
  ├─ syncUseCase.getLocalSnapshot()
  │    └─ taskDao.getAll()              → чтение ВСЕХ задач (для ответного payload)
  ├─ syncUseCase.getLocalDevices()
  │    └─ deviceDao.getAll()            → чтение ~1 строки
  └─ syncUseCase.mergeAll(incoming.tasks, incoming.devices)
       └─ MergeTasksUseCase.invoke()
            ├─ taskDao.getAll()         → второе чтение ВСЕХ задач
            ├─ taskDao.upsertAll(toUpsert) → запись только изменившихся задач
            └─ taskSyncStateDao.upsertAll(confirmedEntries) → запись подтверждений
       └─ deviceDao.upsert() per device
```

Ответный payload строится из snapshot **до** merge — партнёр получает состояние, которое было на момент прихода его запроса.

---

### Фоновое отслеживание изменений (`SyncService.observeTaskChanges`)

Отдельная, всегда активная подписка в рамках Foreground Service:

```
taskRepository.observeAllTasks()   → Flow подписка (живёт всё время работы сервиса)
    .distinctUntilChanged()
    .drop(1)
    .debounce(3_000L)
    .collect { triggerSync() }
```

Эта подписка независима от `TaskListViewModel`. Когда сервис пишет в БД (upsert после merge), Room эмитирует сюда — `distinctUntilChanged` пропустит только если данные изменились, `debounce` сглаживает burst.

---

## Объём данных и рост таблицы

Таблица `tasks` растёт бесконечно: tombstone-строки (`deleted=1`) не удаляются физически. Число строк в `getAll()` = активные задачи + все когда-либо удалённые задачи.

Практически: для семейного приложения с ~10–30 активными задачами и несколькими сотнями tombstone-ов за год — объём таблицы незначителен (~hundreds KB). Однако логируемое значение в `Sync OK: sent N` отражает полный размер таблицы, а не только активные задачи.

---

## Влияние на Room-эмиссии

Любая запись в `tasks` (upsert или softDelete) в рамках одной транзакции → одна эмиссия из `observeAllTasks()`. Несколько строк в `upsertAll()` — всё равно одна эмиссия (одна транзакция).

`MergeTasksUseCase` после фикса пишет только изменившиеся задачи (`toUpsert`), а не весь merged-список. Если `toUpsert` пуст — запись не происходит вообще, Room не эмитирует, паразитный sync-цикл не запускается.

`task_sync_state` — отдельная таблица, Room инвалидирует Flow-запросы потаблично. Запись в `task_sync_state` (подтверждения) **не** переэмиттит `observeAllTasks()` (таблица `tasks`), поэтому не может разбудить `SyncService.observeTaskChanges()` независимо от того, пуст ли в этот раз `toUpsert`.

Текущий лог-формат результата merge:
```
Merge done: +1 new, ~1 updated, =23 unchanged — writing 2/25 tasks to DB
```
Дробь `2/25` = записанных / всего в таблице (активные + tombstone-ы).
