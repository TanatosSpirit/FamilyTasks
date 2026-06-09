# Встроенный лог-вьюер

Экран диагностики, доступный из настроек приложения. Показывает события синхронизации в реальном времени прямо на устройстве — без подключения к компьютеру.

---

## Доступ

**Настройки → Диагностика → Логи синхронизации**

---

## Что отображается

Каждая запись содержит:
- **Время** — `ЧЧ:мм:сс`
- **Уровень** — `D / I / W / E`
- **Тег** — источник события (`SyncService`, `SyncWorker`, `HttpClient`, `UdpDiscovery`, `HttpServer`, `MergeTasksUseCase`)
- **Сообщение** — описание события

### Покрытые события

| Источник | События |
|---|---|
| `SyncService` | запуск/остановка сервиса, HTTP-сервер запущен, партнёр обнаружен, sync начат/завершён/ошибка |
| `HttpClient` | отправка POST /sync, код ответа, таймаут, connection refused |
| `UdpDiscovery` | UDP-сокет открыт, broadcast отправлен, пакет получен от партнёра |
| `HttpServer` | входящий /sync запрос, ответ отправлен, ошибка обработки |
| `SyncWorker` | запуск планового тика WorkManager, пропуск (нет peer IP), успех, ошибка с retry |
| `MergeTasksUseCase` | старт мержа (кол-во remote/local), каждая новая/обновлённая задача, сработавшие уведомления, итог (+N new, ~N updated, =N unchanged) |

### Примеры в логах

Sync по событию (UDP или изменение задачи):
```
I  SyncService        triggerSync → 192.168.1.5
D  HttpClient         POST /sync → 192.168.1.5:8080 (12 tasks)
I  MergeTasksUseCase  Merge: 14 remote, 12 local
D  MergeTasksUseCase  New: 'Купить молоко' (assigned=me, deleted=false)
I  MergeTasksUseCase  Notify: new task assigned to me 'Купить молоко'
D  MergeTasksUseCase  Updated: 'Уборка' (local=1718050000, remote=1718050999)
I  MergeTasksUseCase  Merge done: +1 new, ~1 updated, =12 unchanged
I  SyncService        Sync OK: sent 12, received 14
```

Плановый тик WorkManager (каждые 5 минут), партнёр доступен:
```
I  SyncWorker         WorkManager sync triggered → 192.168.1.5
D  HttpClient         POST /sync → 192.168.1.5:8080 (13 tasks)
I  MergeTasksUseCase  Merge: 13 remote, 13 local
I  MergeTasksUseCase  Merge done: +0 new, ~0 updated, =13 unchanged
I  SyncWorker         WorkManager sync: success
```

Плановый тик WorkManager, партнёр ещё не найден:
```
D  SyncWorker         WorkManager sync: no peer IP, skipped
```

### Уровни и цвета

| Уровень | Цвет | Когда |
|---|---|---|
| `D` | серый | детальные события (broadcast отправлен, подробности по каждой задаче в мерже) |
| `I` | синий | штатные события (sync OK, партнёр найден, итог мержа) |
| `W` | оранжевый | нештатные, но не критичные (нет ответа, таймаут) |
| `E` | красный | ошибки с исключением |

---

## Управление

- **Порядок** — новые записи сверху.
- **Фильтр по уровню** — кнопки `D / I / W / E` вверху экрана. Повторное нажатие снимает фильтр.
- **Поделиться** — иконка Share в заголовке. Открывает стандартный Android Share Sheet; логи экспортируются как `familytasks_log.txt` и можно отправить в мессенджер, почту, Drive и т.д. Экспортируется полный буфер (без учёта текущего фильтра), записи в хронологическом порядке (старые сверху).
- **Очистка** — иконка корзины в заголовке.
- **Буфер** — хранится последние 500 записей в памяти. При перезапуске приложения очищается.

Все события одновременно пишутся в Logcat (тег совпадает с полем тега в строке лога).

---

## Удаление из кода

1. Удалить файлы: `core/AppLogger.kt`, `ui/logs/LogsScreen.kt`, `ui/logs/LogsViewModel.kt`
2. В `Screen.kt` — удалить `data object Logs`
3. В `NavGraph.kt` — удалить импорт `LogsScreen`, параметр `onLogs` и `composable(Screen.Logs.route)`
4. В `SettingsScreen.kt` — удалить параметр `onLogs` и секцию «Диагностика»
5. В sync-файлах, `SyncWorker.kt` и `MergeTasksUseCase.kt` — заменить `AppLogger.` на `Log.` (Find & Replace в IDE)
