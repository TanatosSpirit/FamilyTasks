# Редизайн синхронизации — событийная модель

**Статус: реализовано**

Старая модель: polling (UDP каждые 30с + WorkManager каждые 15 мин).  
Новая модель: event-driven (UDP только при подключении + HTTP при изменении задачи).

---

## Концепция

```
Старая модель:
  UDP broadcast каждые 30с (всегда) ──► нашли IP ──► HTTP sync
  WorkManager каждые 15 мин ──────────────────────► HTTP sync

Новая модель:
  Подключились к WiFi ──► UDP burst (5 пакетов) ───► нашли IP ──► pauseDiscovery()
  Изменили задачу ─────────────────────────────────► HTTP sync (debounce 3с)
  HTTP timeout ────────────────────────────────────► peerIp = null → restartDiscovery()
  Партнёр вернулся ────► его UDP burst ────────────► нашли IP снова
```

---

## Что реализовано

### UdpDiscovery.kt ✅

**Было:** `startBroadcasting()` — бесконечный цикл каждые 30с.

**Стало:** `startBurst()` — 5 пакетов с интервалом 1.5с, затем пауза, затем снова.

При получении пакета от партнёра — **сначала отвечаем своим пакетом** (иначе партнёр не узнает наш IP), затем вызываем `onPeerDiscovered`.

Новые методы:
- `pauseDiscovery()` — останавливает только broadcastJob (listen-сокет и lock не трогаются)
- `restartDiscovery(scope)` — шлёт новый burst со сбросом backoff-счётчика (listen уже жив, lock уже держится)

`startBurst()` работает в цикле с прогрессивным backoff: **5 пакетов → пауза → снова 5 пакетов**. Пауза увеличивается по мере того как партнёр не отвечает:

| Цикл | Пауза | Итого с начала |
|---|---|---|
| 1–4 | 30 сек | ~2 мин |
| 5–10 | 5 мин | ~32 мин |
| 11+ | 15 мин | бесконечно |

Счётчик `cycleCount` — локальная переменная корутины. Сбрасывается автоматически при `restartDiscovery()`, т.к. создаётся новая корутина.

### Принцип ответственности за burst

**Кто ушёл из сети — тот и объявляет о себе по возвращению.**

- Собственный WiFi reconnect → `restartDiscovery()` → burst (мы ушли и вернулись)
- `PeerUnreachableException` → только `setPeerIp(null)`, **без burst** → ждём UDP от партнёра

Партнёр, вернувшись в сеть, запустит свой burst. Наш listen-сокет открыт всегда — мы его услышим немедленно вне зависимости от фазы backoff.

MulticastLock держится **всё время жизни SyncService**. Освобождается только в `stop()`.

**Почему не освобождать при известном `peerIp`:** партнёр может вернуться с новым IP (DHCP). При возвращении он пришлёт UDP burst — нужно его услышать немедленно. Если lock отпущен, burst отфильтруется WiFi-чипом — тупик: A считает B живым, B не может достучаться до A.

### HttpClient.kt ✅

Добавлен `PeerUnreachableException`. При `SocketTimeoutException` или `ConnectException` — бросает его вместо возврата `null`. Остальные ошибки по-прежнему возвращают `null` (transient сбои).

### SyncService.kt ✅

**`onPeerDiscovered` callback:** после `syncManager.setPeerIp(ip)` — вызывает `udpDiscovery.pauseDiscovery()`. Останавливает только burst, listen-сокет и lock остаются активными.

**`triggerSync()`:** различает два типа ошибок:
- `PeerUnreachableException` → `syncManager.setPeerIp(null)` + `ERROR` (без restartDiscovery — партнёр ушёл, его обязанность объявить о себе)
- Прочие исключения → только `ERROR`, `peerIp` не сбрасывается

**`observeTaskChanges()`:** подписка на `taskRepository.observeAllTasks()` с `drop(1)` + `distinctUntilChanged()` + `debounce(3_000L)` → `triggerSync()`. Начальная эмиссия при старте пропускается (`drop(1)`).

**WiFi BroadcastReceiver:** зарегистрирован динамически в `onCreate()` (манифестный не работает на Android 8+). При disconnect — `peerIp = null` + `pauseDiscovery()`. При connect — если `peerIp == null`, вызывает `restartDiscovery()`.

Добавлена инъекция `TaskRepository` для `observeTaskChanges()`.

### FamilyTasksApplication.kt ✅

Удалены `schedulePeriodicSync()` и все связанные импорты WorkManager. `WorkManager.Configuration.Provider` оставлен — нужен для `DueNotificationWorker`.

### SyncManager.kt — не изменялся

Debounce реализован в `SyncService.observeTaskChanges()`. Менять `SyncManager` не потребовалось.

### TaskRepositoryImpl.kt — не изменялся

Триггер на изменения задач реализован через подписку на Flow в `SyncService`, а не через вызов из репозитория. Репозиторий остался чистым.

### SyncWorker.kt — не изменялся

Класс оставлен, но больше не планируется. Удалить при необходимости.

---

## Оценка энергоэффективности

| Источник расхода | Старая модель | Новая модель | Изменение |
|---|---|---|---|
| UDP broadcast (отправка) | 2880 раз/сутки | ~5 раз при подключении | **−99%** |
| Пробуждение WiFi-радио для отправки | 2880 раз/сутки | ~5 раз/сутки | **−99%** |
| MulticastLock | постоянно | постоянно | без изменений |
| WorkManager wake-up | ~96 раз/сутки | 0 | **−100%** |
| DatagramSocket создание/закрытие | каждые 30с | только burst | **−99%** |
| HTTP sync | по polling | только при изменении задачи | зависит от активности |

**Backoff при длительном отсутствии партнёра:** если партнёр не отвечает, burst-интервал растёт 30с → 5 мин → 15 мин. При отсутствии партнёра 5 часов: ~28 циклов вместо 600 без backoff.

**Принцип ответственности:** `PeerUnreachableException` больше не запускает burst на нашей стороне — партнёр ушёл сам и сам объявит о себе по возвращению. Если мы тоже уходили — запускаем burst при своём WiFi reconnect с backoff.

---

## Риски и ограничения

| Риск | Вероятность | Митигация |
|---|---|---|
| UDP burst потерялся целиком | низкая | 5 попыток за ~6 секунд |
| Оба устройства шлют burst одновременно | низкая | listen-корутина получит хотя бы один пакет от другого |
| Задача изменена пока партнёр офлайн | высокая (норма) | sync произойдёт когда партнёр вернётся и пришлёт UDP |
| Transient HTTP ошибка (не timeout) | средняя | `peerIp` не сбрасывается, только `ERROR` статус |
| Merge-induced повторный sync | низкая | `distinctUntilChanged()` блокирует повтор если список не изменился |
