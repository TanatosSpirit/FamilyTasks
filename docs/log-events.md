# Логирование энергозатратных событий

Следующие события логируются специально для анализа энергопотребления:

| Событие | Файл | Уровень | Что анализировать |
|---|---|---|---|
| `Scanning 192.168.x.* for ...` | `SubnetScanner` | I | Начало scan — сколько раз запускается в сутки |
| `Peer found at ...` | `SubnetScanner` | I | Успешный scan — как быстро нашли партнёра |
| `Scan complete — peer not on subnet` | `SubnetScanner` | I | Партнёр офлайн — scan прошёл впустую |
| `KeepAlive diag: doze=... battIgnored=... wifiOn=... activeWifi=... ownIp=... peerIp=...` | `SyncService` | I | Снимок состояния устройства в момент keepAlive: Doze-режим, исключение из батарейной оптимизации, статус WiFi, текущий IP |
| `KeepAlive identify attempt N failed — retrying in 3s` | `SyncService` | D | Попытка N из 3 не вернула pairedDeviceId — ждём и пробуем ещё |
| `KeepAlive OK — peer still at ...` | `SyncService` | D | Штатный keepAlive — IP не изменился |
| `KeepAlive failed — peer IP stale` | `SyncService` | I | Все 3 попытки identify не вернули pairedDeviceId → IP устарел → scan |
| `KeepAlive — no peer IP, starting scan` | `SyncService` | D | peerIp==null при срабатывании таймера → scan |
| `Peer reached us from ... — saving IP` | `SyncService` | I | Партнёр нашёл нас через scan (входящий /sync) |
| `Peer reached us from ... via keepAlive — saving IP` | `SyncService` | I | Партнёр восстановил наш IP пассивно через keepAlive |
| `WiFi connected — starting subnet scan` | `SyncService` | I | peerIp был null → scan запущен при подключении |
| `WiFi reconnected — verifying peer IP ...` | `SyncService` | D | peerIp есть → задержка 1.5 сек + до 3 попыток /identify |
| `WiFi reconnected — verify attempt N failed — retrying in 3s` | `SyncService` | D | Попытка N из 3 не вернула pairedDeviceId — ждём и пробуем ещё |
| `WiFi reconnected — peer confirmed at ...` | `SyncService` | I | /identify подтвердил IP (на любой из 3 попыток) → triggerSync() |
| `WiFi reconnected — peer IP stale, clearing IP, starting scan` | `SyncService` | I | Все 3 попытки /identify не вернули pairedDeviceId → IP устарел → scan |
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
| `Purged N confirmed tombstone(s)` | `SyncService` | I | После успешного sync (как initiator) — сколько tombstone-задач удалено физически, т.к. партнёр подтвердил, что тоже их пометил как deleted |
