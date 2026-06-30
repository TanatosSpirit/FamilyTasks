# Pending-sync indicator

Визуальный индикатор на карточке задачи, когда задача ещё не была получена партнёром.

## Проблема

В офлайн-режиме пользователь создаёт задачи и не знает, какие из них уже дошли до партнёра. Время последней синхронизации в заголовке экрана есть, но сравнивать его с каждой задачей неудобно.

## Решение

Карточка задачи, которую партнёр ещё не получил, отображается приглушённой (alpha 0.65) и со значком синхронизации слева от названия. После успешного синка карточка возвращается к нормальному виду автоматически.

Семантика приглушённости: задача **неактивна у партнёра** — существует только на этом устройстве.

## Как вычисляется isPendingSync

```kotlin
val isPendingSync = !task.isSecret &&
    state.hasPairedDevice &&
    task.updatedAt > state.lastConfirmedSyncTimestamp
```

- `task.updatedAt > lastConfirmedSyncTimestamp` — задача изменена после последнего подтверждённого round-trip
- `!task.isSecret` — секретные задачи никогда не синкаются, замок уже говорит о них всё
- `hasPairedDevice` — без сопряжённого устройства индикатор бессмысленен

`lastConfirmedSyncTimestamp` — watermark из `PreferencesManager`, продвигается в `SyncService.triggerSync()` после успешного ответа партнёра. Подробнее о watermark: `docs/delta-sync.md`.

## Реактивность

`PreferencesManager.lastConfirmedSyncTimestamp` при записи обновляет `lastConfirmedSyncTimestampFlow: MutableStateFlow<Long>`. `TaskListViewModel` подписан на этот flow через `syncStatus` combine — как только sync завершается, ViewModel эмитит новый `TaskListUiState` и карточки гаснут автоматически без перезагрузки экрана.

## Затронутые файлы

| Файл | Изменение |
|---|---|
| `PreferencesManager.kt` | добавлен `lastConfirmedSyncTimestampFlow` |
| `TaskListViewModel.kt` | `SyncStatus` расширен watermark-ом (5-й поток в combine); `TaskListUiState` получил `lastConfirmedSyncTimestamp` и `hasPairedDevice` |
| `TaskListScreen.kt` | `TaskCard` и `SwipeableTaskCard` получили параметр `isPendingSync`; применяется `Modifier.alpha(0.65f)` и `Icons.Default.Sync` (14dp, серый) |

## Побочное изменение: формула bgAlpha свайпа

Базовый сдвиг 0.20 в формуле зелёного фона при свайпе (`0.20 + fraction * 1.4`) был убран — без этого фон просвечивал сквозь приглушённые карточки даже в покое. Новая формула: `fraction * 1.8`. Достигает того же максимума 0.90 ровно на пороге 50%. Подробнее: `docs/swipe-to-complete.md`.
