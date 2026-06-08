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

**Стало:** `startBurst()` — 5 пакетов с интервалом 1.5с, затем корутина завершается.

При получении пакета от партнёра — **сначала отвечаем своим пакетом** (иначе партнёр не узнает наш IP), затем вызываем `onPeerDiscovered`.

Новые методы:
- `pauseDiscovery()` — останавливает только broadcastJob (listen-сокет и lock не трогаются)
- `restartDiscovery(scope)` — шлёт новый burst (listen уже жив, lock уже держится)

MulticastLock держится **всё время жизни SyncService**. Освобождается только в `stop()`.

**Почему не освобождать при известном `peerIp`:** партнёр может вернуться с новым IP (DHCP). При возвращении он пришлёт UDP burst — нужно его услышать немедленно. Если lock отпущен, burst отфильтруется WiFi-чипом — тупик: A считает B живым, B не может достучаться до A.

### HttpClient.kt ✅

Добавлен `PeerUnreachableException`. При `SocketTimeoutException` или `ConnectException` — бросает его вместо возврата `null`. Остальные ошибки по-прежнему возвращают `null` (transient сбои).

### SyncService.kt ✅

**`onPeerDiscovered` callback:** после `syncManager.setPeerIp(ip)` — вызывает `udpDiscovery.pauseDiscovery()`. Останавливает только burst, listen-сокет и lock остаются активными.

**`triggerSync()`:** различает два типа ошибок:
- `PeerUnreachableException` → `syncManager.setPeerIp(null)` + `udpDiscovery.restartDiscovery(scope)` + `ERROR`
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

---

## Риски и ограничения

| Риск | Вероятность | Митигация |
|---|---|---|
| UDP burst потерялся целиком | низкая | 5 попыток за ~6 секунд |
| Оба устройства шлют burst одновременно | низкая | listen-корутина получит хотя бы один пакет от другого |
| Задача изменена пока партнёр офлайн | высокая (норма) | sync произойдёт когда партнёр вернётся и пришлёт UDP |
| Transient HTTP ошибка (не timeout) | средняя | `peerIp` не сбрасывается, только `ERROR` статус |
| Merge-induced повторный sync | низкая | `distinctUntilChanged()` блокирует повтор если список не изменился |
