# Синхронизация по файлу (удалённый режим)

WiFi-sync работает только в одной подсети. Файловый обмен — дополнительный механизм для периодической синхронизации когда партнёры находятся в разных сетях: один экспортирует JSON-снимок через мессенджер, второй открывает файл в приложении и получает merge.

---

## Пользовательский поток

**Экспорт (устройство A):**
1. Настройки → «Поделиться задачами»
2. Android Share Sheet → выбрать Telegram / WhatsApp / любой мессенджер
3. Файл `familytasks_export.json` уходит партнёру

**Импорт (устройство B):**
1. Открыть файл из мессенджера → «Открыть в FamilyTasks»
2. Автоматический merge без каких-либо подтверждений
3. Toast «Получено X задач от [имя]»

Обмен двусторонний: каждое устройство должно поделиться своим файлом, чтобы оба получили обновления.

---

## Архитектура

Вся логика сосредоточена в `ExportImportManager` (`data/sync/`). Намеренно не добавлено в `SyncService` — файловый и WiFi-механизмы полностью независимы.

### Экспорт — `buildShareIntent()`

```
getAllTasksSnapshot()             ← все задачи, sinceTimestamp = 0 (полный снимок)
+ me из PreferencesManager       ← своё устройство (как в SyncService.buildDevicesPayload)
+ others из getAllDevices()
→ SyncPayload (тот же формат что POST /sync)
→ writeText(cache/export/familytasks_export.json)
→ FileProvider URI
→ Intent.ACTION_SEND, type = "application/json"
```

### Импорт — `importFromUri(uri)`

```
contentResolver.openInputStream(uri)
→ decodeFromString<SyncPayload>
→ проверка payload.deviceId == pairedDeviceId    ← отклонить файл от чужого устройства
→ syncUseCase.mergeAll(remoteTasks, remoteDevices)  ← тот же MergeTasksUseCase
→ Toast-сообщение с именем отправителя и кол-вом активных задач
```

### Intent-filter

`AndroidManifest.xml`: `ACTION_VIEW + application/json` на `MainActivity` (`launchMode=singleTop`). Срабатывает когда пользователь открывает `.json` файл из любого приложения. `onNewIntent` обрабатывает случай когда приложение уже запущено.

---

## Что работает так же как WiFi-sync

Оба механизма используют один `mergeAll()` → `MergeTasksUseCase`:

- **LWW-merge** — побеждает бо́льший `updatedAt`, tie-break в пользу локальной
- **Tombstone** — `deleted = true` передаётся в файле, партнёр применяет удаление через merge
- **Уведомления** — `notifyNewTask`, `notifyStatusChanged`, `scheduleDueNotifications` срабатывают из `MergeTasksUseCase` так же как при входящем WiFi-sync
- **Повторяющиеся задачи** — `scheduleRecurrenceSpawn` вызывается при `assignedTo == myDeviceId && recurrence != None`, owner-only правило соблюдается автоматически
- **Логи** — тег `ExportImport` в AppLogger, merge-статистика через стандартный тег `MergeTasksUseCase`

---

## Что отличается от WiFi-sync

### Нет delta sync

WiFi-sync отправляет только задачи с `updatedAt > lastConfirmedSyncTimestamp`. Файловый экспорт всегда полный (`sinceTimestamp = 0`). Merge идемпотентен — задачи которые у партнёра уже есть попадут в `unchanged`. Для периодического обмена с десятками задач размер файла незначителен.

`lastConfirmedSyncTimestamp` (watermark) не трогается — принадлежит WiFi-механизму.

### Нет tombstone purge

WiFi-sync физически удаляет tombstone-ы из БД после подтверждённого round-trip. Здесь подтверждения нет — tombstone-ы остаются до следующего успешного WiFi-синка. Для приложения с несколькими десятками задач это приемлемо.

### Двухшаговый обмен

WiFi-sync — один round-trip (A → B → A). Файловый — два раздельных действия: если обменялись только в одну сторону, второй участник не получил обновления.

---

## Безопасность

Файл содержит все задачи в открытом виде — те же данные что и POST /sync. Приватность определяется мессенджером (E2E в Telegram Secret Chat / WhatsApp) или другим каналом передачи.

Защита от чужого файла: `importFromUri` проверяет `payload.deviceId == pairedDeviceId` и отклоняет файл от несопряжённого устройства с сообщением «Файл от незнакомого устройства».

---

## Затронутые файлы

| Файл | Изменение |
|---|---|
| `data/sync/ExportImportManager.kt` | новый — вся логика экспорта/импорта |
| `res/xml/file_paths.xml` | добавлен путь `cache/export/` для FileProvider |
| `AndroidManifest.xml` | intent-filter `ACTION_VIEW + application/json` |
| `ui/settings/SettingsViewModel.kt` | инъекция `ExportImportManager`, `buildExportIntent()` |
| `ui/settings/SettingsScreen.kt` | кнопка «Поделиться задачами» |
| `MainActivity.kt` | `handleImportIntent()` — запускает merge, показывает Toast |

---

## Логи

```
I  ExportImport       Export: 12 tasks → familytasks_export.json
I  ExportImport       Import done: 10 active tasks from Мама
W  ExportImport       Import rejected: unknown deviceId ab12cd34
E  ExportImport       Import failed: ...
```

После `Import done` следует стандартный блок `MergeTasksUseCase` с детализацией по задачам.
