# Pending-sync indicator

Визуальный индикатор на карточке задачи, когда задача ещё не была получена партнёром.

## Проблема

В офлайн-режиме пользователь создаёт задачи и не знает, какие из них уже дошли до партнёра. Время последней синхронизации в заголовке экрана есть, но сравнивать его с каждой задачей неудобно.

## Решение

Карточка задачи, которую партнёр ещё не получил, отображается приглушённой (alpha 0.65) и со значком синхронизации слева от названия. После успешного синка карточка возвращается к нормальному виду автоматически.

Семантика приглушённости: задача **неактивна у партнёра** — существует только на этом устройстве.

## Экспериментальный тумблер — индикатор по умолчанию выключен

Настройки → «Экспериментальные фичи» → «Отображать индикатор синхронизации задач». Хранится в `PreferencesManager.showPendingSyncIndicator` (+ `showPendingSyncIndicatorFlow`), default `false`.

Тумблер гейтит **только отображение**: `isPendingSync` в `TaskListScreen.kt` требует `state.showPendingSyncIndicator == true` как первое условие. Механизм подтверждений ниже продолжает работать независимо от тумблера — выключение не экономит вычисления, только прячет иконку/приглушение карточки.

## Как вычисляется isPendingSync

```kotlin
val isPendingSync = state.showPendingSyncIndicator &&
    !task.isSecret &&
    state.hasPairedDevice &&
    task.updatedAt > (state.confirmedSyncMap[task.id] ?: 0L)
```

Формула одна и та же для своих задач и задач партнёра — `createdBy` в вычислении не участвует (см. ниже, почему это стало возможно).

Дополнительные условия:
- `state.showPendingSyncIndicator` — экспериментальный тумблер, default `false` (см. выше)
- `!task.isSecret` — секретные задачи никогда не синкаются, замок уже говорит о них всё
- `hasPairedDevice` — без сопряжённого устройства индикатор бессмысленен

## Per-task подтверждение (вместо трёх скалярных watermark-ов)

Раньше индикатор гасился по скалярному watermark-у (`max(updatedAt)` среди подмножества задач в последнем sync/файле) — это давало **max-overshoot**: если партнёр поднимал `updatedAt` одной моей старой задачи (например, отмечал «Готово»), watermark перепрыгивал через другую, реально недоставленную мою задачу, и индикатор на ней гас ложно. Баг чисто косметический (WiFi-дельта и файловый экспорт читают своё состояние независимо от watermark-а и всё равно довозят задачу), но воспроизводимый — см. `proposal/pending-sync-indicator-strategy.md`, раздел «Эксперимент».

Текущая реализация — точная, **per-task**, таблица `task_sync_state(taskId, confirmedUpdatedAt)` (миграция `MIGRATION_4_5`, БД версия 5):

- Пишется в `MergeTasksUseCase.invoke()` — единственная точка, общая для WiFi-инициатора, WiFi-responder-а и файлового импорта (все три идут через `SyncUseCase.mergeAll()`).
- Правило: для каждой входящей `remote`-задачи, если `remote.updatedAt >= local.updatedAt` (та версия, что была локально *до* этого merge) — пишем `TaskSyncConfirmation(remote.id, remote.updatedAt)`. Если задачи нет среди входящих вовсе — ничего не пишем, индикатор остаётся.
- Монотонность гарантирована конструкцией (`local.updatedAt` никогда не уменьшается во времени), поэтому запись — простой `upsert` без предварительного чтения старого значения: новое подтверждение всегда `>=` любого более раннего.
- Таблица `task_sync_state` физически отделена от `tasks` — запись в неё не переэмиттит `observeAllTasks()`, поэтому `SyncService.observeTaskChanges()` (debounce → `triggerSync()`) не задет и паразитный resync-цикл не возникает.
- `SyncUseCase.purgeConfirmedTombstones()` удаляет строки `task_sync_state` для тех же `id`, что физически покидают `tasks`.

`lastConfirmedSyncTimestamp` в `PreferencesManager` **остаётся**, но теперь используется только как боевой delta-sync watermark (`docs/delta-sync.md`) — индикатор от него больше не зависит ни прямо, ни как fallback.

## Реактивность

`TaskSyncStateDao.observeAll()` — живой Flow, обёрнутый в `TaskSyncStateRepository`. `TaskListViewModel` подписан на него внутри промежуточного `syncAndIndicator` combine (вместе с `syncStatus` и `showPendingSyncIndicatorFlow` — чтобы не превышать 5 потоков в одном `combine`, см. конвенцию в CLAUDE.md). Room переисполняет запрос ровно тогда, когда меняется сама таблица `task_sync_state`, то есть синхронно с merge-событиями — не чаще.

## Затронутые файлы

| Файл | Изменение |
|---|---|
| `TaskSyncStateEntity.kt` / `TaskSyncStateDao.kt` (новые, `data/local`) | таблица `task_sync_state`, `observeAll()` / `upsertAll()` / `deleteByIds()` |
| `TaskSyncConfirmation.kt` (новый, `domain/model`) | доменная модель подтверждения |
| `TaskSyncStateRepository.kt` / `TaskSyncStateRepositoryImpl.kt` (новые) | доступ к таблице через Flow |
| `AppDatabase.kt` | `MIGRATION_4_5`, версия БД 5, новая Entity |
| `DatabaseModule.kt` / `AppModule.kt` | DI: DAO + биндинг репозитория |
| `MergeTasksUseCase.kt` | пишет `confirmedEntries` рядом с `toUpsert`, единая точка для всех каналов |
| `SyncUseCase.kt` | `purgeConfirmedTombstones` чистит `task_sync_state` по тем же `id` |
| `PreferencesManager.kt` | убраны `lastPartnerFileTimestamp`/`lastOwnFileConfirmedTimestamp`; `lastConfirmedSyncTimestamp` остался как delta-sync watermark |
| `ExportImportManager.kt` | убраны блоки записи файловых watermark-ов — `mergeAll()` теперь сам пишет подтверждения |
| `TaskListViewModel.kt` | `syncAndIndicator` combine вместо `syncTimestamps`/`Triple`; `confirmedSyncMap` в `TaskListUiState` |
| `TaskListScreen.kt` | `isPendingSync` без ветвления по `createdBy` — единая формула через `confirmedSyncMap` |

## Часовые пояса партнёров не влияют на сравнение

`updatedAt` / `confirmedUpdatedAt` — это `Long` из `System.currentTimeMillis()`, Unix-epoch в миллисекундах от UTC. Это абсолютный момент времени: значение не зависит от часового пояса, выставленного на устройстве. Сравнение `remote.updatedAt >= local.updatedAt` в `MergeTasksUseCase.invoke()` корректно и тогда, когда партнёры физически в разных таймзонах (файловый sync — единственный канал, где это реально может случиться) — часовой пояс влияет только на то, как эта метка *форматируется для показа* человеку (`SimpleDateFormat` в заголовках списка), но не на само число. Модернизация не нужна.

Единственное, что могло бы сломать эту логику — рассинхронизированные (физически неверно выставленные) системные часы устройства, а не разница часовых поясов. Это отдельный, не рассматриваемый сейчас риск.

## Побочное изменение: формула bgAlpha свайпа

Базовый сдвиг 0.20 в формуле зелёного фона при свайпе (`0.20 + fraction * 1.4`) был убран — без этого фон просвечивал сквозь приглушённые карточки даже в покое. Новая формула: `fraction * 1.8`. Достигает того же максимума 0.90 ровно на пороге 50%. Подробнее: `docs/swipe-to-complete.md`.
