# Pending-sync indicator

Визуальный индикатор на карточке задачи, когда задача ещё не была получена партнёром.

## Проблема

В офлайн-режиме пользователь создаёт задачи и не знает, какие из них уже дошли до партнёра. Время последней синхронизации в заголовке экрана есть, но сравнивать его с каждой задачей неудобно.

## Решение

Карточка задачи, которую партнёр ещё не получил, отображается приглушённой (alpha 0.65) и со значком синхронизации слева от названия. После успешного синка карточка возвращается к нормальному виду автоматически.

Семантика приглушённости: задача **неактивна у партнёра** — существует только на этом устройстве.

## Как вычисляется isPendingSync

```kotlin
val syncThreshold = if (task.createdBy == state.myDeviceId) {
    state.lastConfirmedSyncTimestamp
} else {
    maxOf(state.lastConfirmedSyncTimestamp, state.lastPartnerFileTimestamp)
}
val isPendingSync = !task.isSecret &&
    state.hasPairedDevice &&
    task.updatedAt > syncThreshold
```

Порог зависит от того, кто создал задачу:

- **Моя задача** (`createdBy == myDeviceId`) — порог `lastConfirmedSyncTimestamp`. Индикатор гаснет когда партнёр подтвердил получение (WiFi round-trip или файловый обмен).
- **Задача партнёра** (`createdBy == pairedDeviceId`) — порог `max(lastConfirmedSyncTimestamp, lastPartnerFileTimestamp)`. Партнёр заведомо знает свою задачу; индикатор не нужен пока задача не была изменена мной после последнего импорта.

Дополнительные условия:
- `!task.isSecret` — секретные задачи никогда не синкаются, замок уже говорит о них всё
- `hasPairedDevice` — без сопряжённого устройства индикатор бессмысленен

## Два watermark-а

| Поле | Где пишется | Семантика |
|---|---|---|
| `lastConfirmedSyncTimestamp` | `SyncService.triggerSync()` после WiFi round-trip; `ExportImportManager.importFromUri()` когда в файле найдены свои задачи | Партнёр подтвердил получение моих задач до этого момента |
| `lastPartnerFileTimestamp` | `ExportImportManager.importFromUri()` | max `updatedAt` задач партнёра в последнем импортированном файле |

Подробнее о `lastConfirmedSyncTimestamp`: `docs/delta-sync.md`.

## Файловый обмен и индикатор

Файловый импорт обновляет оба watermark-а:

```kotlin
// свои задачи в файле партнёра → партнёр их знает
val maxOwnUpdatedAt = remoteTasks.filter { it.createdBy == myDeviceId }.maxOfOrNull { it.updatedAt }
if (maxOwnUpdatedAt > lastConfirmedSyncTimestamp) lastConfirmedSyncTimestamp = maxOwnUpdatedAt

// задачи партнёра в его файле → я их только что получил, индикатор не нужен
val maxPartnerUpdatedAt = remoteTasks.filter { it.createdBy == pairedId }.maxOfOrNull { it.updatedAt }
if (maxPartnerUpdatedAt > lastPartnerFileTimestamp) lastPartnerFileTimestamp = maxPartnerUpdatedAt
```

### Сколько раундов нужно для гашения индикаторов

| Сценарий | Раундов |
|---|---|
| Задачи только у одной стороны | 2 (A→B, B→A) |
| Задачи у обоих | 3 (A→B, B→A, A→B) |

Раунды могут быть растянуты во времени — каждый следующий файл постепенно продвигает watermark вперёд, потерь данных нет. WiFi-синк при встрече закрывает всё накопившееся за один раунд.

## Реактивность

Оба `MutableStateFlow` в `PreferencesManager` обновляются при записи. `TaskListViewModel` подписан на них через промежуточный `syncTimestamps` combine → `syncStatus` combine — карточки гаснут автоматически без перезагрузки экрана.

## Затронутые файлы

| Файл | Изменение |
|---|---|
| `PreferencesManager.kt` | добавлены `lastConfirmedSyncTimestampFlow`, `lastPartnerFileTimestampFlow` |
| `ExportImportManager.kt` | при импорте обновляет оба watermark-а |
| `TaskListViewModel.kt` | `syncTimestamps` промежуточный flow; `SyncStatus` и `TaskListUiState` получили `lastPartnerFileTimestamp` и `myDeviceId` |
| `TaskListScreen.kt` | `isPendingSync` вычисляется через `syncThreshold` с учётом `createdBy`; `TaskCard` и `SwipeableTaskCard` получили параметр `isPendingSync` |

## Побочное изменение: формула bgAlpha свайпа

Базовый сдвиг 0.20 в формуле зелёного фона при свайпе (`0.20 + fraction * 1.4`) был убран — без этого фон просвечивал сквозь приглушённые карточки даже в покое. Новая формула: `fraction * 1.8`. Достигает того же максимума 0.90 ровно на пороге 50%. Подробнее: `docs/swipe-to-complete.md`.
