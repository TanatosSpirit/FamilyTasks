# Pending-sync indicator

Визуальный индикатор на карточке задачи, когда задача ещё не была получена партнёром.

## Проблема

В офлайн-режиме пользователь создаёт задачи и не знает, какие из них уже дошли до партнёра. Время последней синхронизации в заголовке экрана есть, но сравнивать его с каждой задачей неудобно.

## Решение

Карточка задачи, которую партнёр ещё не получил, отображается приглушённой (alpha 0.65) и со значком синхронизации слева от названия. После успешного синка карточка возвращается к нормальному виду автоматически.

Семантика приглушённости: задача **неактивна у партнёра** — существует только на этом устройстве.

## Экспериментальный тумблер — индикатор по умолчанию выключен

Настройки → «Экспериментальные фичи» → «Отображать индикатор синхронизации задач». Хранится в `PreferencesManager.showPendingSyncIndicator` (+ `showPendingSyncIndicatorFlow`), default `false`.

Тумблер гейтит **только отображение**: `isPendingSync` в `TaskListScreen.kt` требует `state.showPendingSyncIndicator == true` как первое условие. Вся watermark-логика ниже (`lastConfirmedSyncTimestamp`, `lastPartnerFileTimestamp`, `lastOwnFileConfirmedTimestamp`, обновление при импорте файла) продолжает работать независимо от тумблера — выключение не экономит вычисления, только прячет иконку/приглушение карточки. Причина: нерешённый вопрос по стратегии гашения индикатора в remote-режиме (см. `proposal/pending-sync-indicator-strategy.md`) не должен блокировать уже написанный и протестированный код — тумблер позволяет отложить выбор варианта A/B/C/D, не удаляя фичу.

`prefs.showPendingSyncIndicatorFlow` подключён отдельным (пятым) аргументом в верхний `combine` в `TaskListViewModel.kt` — не через `syncStatus` (тот уже занимает все 5 типизированных слотов `combine`), а напрямую в `TaskListUiState`.

## Как вычисляется isPendingSync

```kotlin
val syncThreshold = if (task.createdBy == state.myDeviceId) {
    maxOf(state.lastConfirmedSyncTimestamp, state.lastOwnFileConfirmedTimestamp)
} else {
    maxOf(state.lastConfirmedSyncTimestamp, state.lastPartnerFileTimestamp)
}
val isPendingSync = state.showPendingSyncIndicator &&
    !task.isSecret &&
    state.hasPairedDevice &&
    task.updatedAt > syncThreshold
```

Порог зависит от того, кто создал задачу:

- **Моя задача** (`createdBy == myDeviceId`) — порог `max(lastConfirmedSyncTimestamp, lastOwnFileConfirmedTimestamp)`. Индикатор гаснет когда партнёр подтвердил получение по WiFi (`lastConfirmedSyncTimestamp`) **или** вернул мою задачу в своём файле (`lastOwnFileConfirmedTimestamp`).
- **Задача партнёра** (`createdBy == pairedDeviceId`) — порог `max(lastConfirmedSyncTimestamp, lastPartnerFileTimestamp)`. Партнёр заведомо знает свою задачу; индикатор не нужен пока задача не была изменена мной после последнего импорта.

Дополнительные условия:
- `state.showPendingSyncIndicator` — экспериментальный тумблер, default `false` (см. выше)
- `!task.isSecret` — секретные задачи никогда не синкаются, замок уже говорит о них всё
- `hasPairedDevice` — без сопряжённого устройства индикатор бессмысленен

## Три watermark-а

| Поле | Где пишется | Семантика |
|---|---|---|
| `lastConfirmedSyncTimestamp` | **только** `SyncService.triggerSync()` после WiFi round-trip | Delta-sync watermark: партнёр подтвердил получение моих задач до этого момента по WiFi. Также фильтр исходящей дельты. |
| `lastOwnFileConfirmedTimestamp` | `ExportImportManager.importFromUri()` | max `updatedAt` **моих** задач, вернувшихся в файле партнёра — индикатор-only |
| `lastPartnerFileTimestamp` | `ExportImportManager.importFromUri()` | max `updatedAt` задач **партнёра** в последнем импортированном файле — индикатор-only |

Подробнее о `lastConfirmedSyncTimestamp`: `docs/delta-sync.md`.

### Почему файловые watermark-и отделены от delta-watermark

`lastConfirmedSyncTimestamp` — боевой watermark delta-sync: `triggerSync()` шлёт по WiFi только задачи с `updatedAt > lastConfirmedSyncTimestamp`. WiFi-watermark безопасен, потому что выставляется в `snapshotTimestamp` (текущее время) **после атомарной отправки всего `> watermark` и подтверждения**.

Файловый снимок партнёра — точечный и не атомарный: `max(updatedAt моих задач в файле)` **не** доказывает, что партнёр получил все мои задачи до этого момента. Если бы файловый импорт двигал `lastConfirmedSyncTimestamp`, watermark мог перескочить через мою недоставленную задачу (партнёр поднял `updatedAt` другой моей задачи — например, отметил «Готово»), и следующая WiFi-дельта тихо пропустила бы недоставленную задачу. Поэтому файловое подтверждение пишется в **отдельное** поле `lastOwnFileConfirmedTimestamp`, которое влияет **только** на индикатор и никогда — на фильтр дельты. Разбор сценария потери: история коммитов фичи.

## Файловый обмен и индикатор

Файловый импорт обновляет оба **индикаторных** watermark-а (delta-watermark не трогает):

```kotlin
// свои задачи в файле партнёра → партнёр их знает (только индикатор)
val maxOwnUpdatedAt = remoteTasks.filter { it.createdBy == myDeviceId }.maxOfOrNull { it.updatedAt }
if (maxOwnUpdatedAt > lastOwnFileConfirmedTimestamp) lastOwnFileConfirmedTimestamp = maxOwnUpdatedAt

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
| `PreferencesManager.kt` | добавлены `lastConfirmedSyncTimestampFlow`, `lastPartnerFileTimestampFlow`, `lastOwnFileConfirmedTimestampFlow`, `showPendingSyncIndicatorFlow` (default `false`) |
| `ExportImportManager.kt` | при импорте обновляет два индикаторных watermark-а (`lastOwnFileConfirmedTimestamp`, `lastPartnerFileTimestamp`); delta-watermark не трогает |
| `TaskListViewModel.kt` | `syncTimestamps` промежуточный flow (3 потока, `Triple`); `SyncStatus` и `TaskListUiState` получили `lastPartnerFileTimestamp`, `lastOwnFileConfirmedTimestamp`, `myDeviceId`, `showPendingSyncIndicator` (подключён отдельным аргументом верхнего `combine`, не через `syncStatus`) |
| `TaskListScreen.kt` | `isPendingSync` вычисляется через `syncThreshold` с учётом `createdBy`, гейтится `state.showPendingSyncIndicator`; `TaskCard` и `SwipeableTaskCard` получили параметр `isPendingSync` |
| `SettingsViewModel.kt` / `SettingsScreen.kt` | секция «Экспериментальные фичи», тумблер `setShowPendingSyncIndicator` |

## Побочное изменение: формула bgAlpha свайпа

Базовый сдвиг 0.20 в формуле зелёного фона при свайпе (`0.20 + fraction * 1.4`) был убран — без этого фон просвечивал сквозь приглушённые карточки даже в покое. Новая формула: `fraction * 1.8`. Достигает того же максимума 0.90 ровно на пороге 50%. Подробнее: `docs/swipe-to-complete.md`.
