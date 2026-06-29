# Секретные задачи

Секретная задача существует **только на устройстве, где была создана** — она не уходит партнёру ни по WiFi-sync, ни через файловый экспорт, ни в логах не светится название. Типичный сценарий — сюрприз партнёру: купить подарок, организовать поездку.

Флаг `isSecret` устанавливается вручную при создании или редактировании задачи переключателем «Секретная задача». В списке задача отображается с иконкой замка.

---

## Что реализовано

- Поле `isSecret: Boolean` в `Task` и `TaskEntity` (DB v4, `DEFAULT 0`).
- Переключатель «Секретная задача» в `CreateTaskScreen` и `EditTaskContent`.
- Иконка замка на карточке в `TaskListScreen`.
- Три уровня изоляции от sync-каналов (см. ниже).

---

## Модель данных

`isSecret` — обычное булево поле `Task`. В Room хранится как `INTEGER NOT NULL DEFAULT 0`.

**Поля нет в `TaskDto`.** Это сознательное решение: `Task.toDto()` просто не копирует `isSecret`, поэтому даже если секретная задача каким-то образом попала бы в payload, поле физически не сериализовалось бы. При `TaskDto.toDomain()` `isSecret` получает дефолт `false` — входящие от партнёра задачи никогда не становятся секретными.

---

## Изоляция от sync-каналов

Три независимых слоя, каждый закрывает отдельный путь утечки.

### 1. WiFi-sync: инициатор (POST /sync исходящий)

`SyncService.triggerSync()`, `SyncService.kt`:

```kotlin
val delta = localTasks.filter { !it.isSecret && it.updatedAt > watermark }
```

Секретные задачи не попадают в delta, которую мы отправляем партнёру.

### 2. WiFi-sync: ответчик (ответ на входящий POST /sync)

`SyncService.startHttpServer()` → лямбда `onSyncRequest`, `SyncService.kt`:

```kotlin
val responseTasks = if (incoming.sinceTimestamp > 0L) {
    localTasks.filter { !it.isSecret && it.updatedAt > incoming.sinceTimestamp }
} else {
    localTasks.filter { !it.isSecret }
}
```

Когда партнёр опрашивает нас — он не получает секретные задачи ни в delta-ответе, ни в полном снимке.

### 3. Файловый экспорт

`ExportImportManager.buildShareIntent()`, `ExportImportManager.kt`:

```kotlin
val tasks = syncUseCase.getLocalSnapshot().filter { !it.isSecret }
```

Файл `familytasks_export.json`, отправляемый через Share Sheet, не содержит секретных задач.

---

## Секретные задачи не тригерят WiFi-sync

`SyncService.observeTaskChanges()` подписана на `observeAllTasks()`. Без дополнительной обработки создание секретной задачи вызывало бы `triggerSync()` через debounce — sync был бы холостым (payload пуст), но лишним.

Решение — фильтр до `distinctUntilChanged()`:

```kotlin
taskRepository.observeAllTasks()
    .map { tasks -> tasks.filter { !it.isSecret } }
    .distinctUntilChanged()
    .drop(1)
    .debounce(3_000L)
    .collect { triggerSync() }
```

Если изменилась только секретная задача, отфильтрованный список не меняется → `distinctUntilChanged()` не пропускает эмиссию → `triggerSync()` не вызывается. Порядок операторов не нарушен (см. конвенцию в CLAUDE.md про `distinctUntilChanged`/`drop(1)`).

---

## Логи

Sync-слой (`SyncService`, `HttpClient`, `SubnetScanner`) пишет в AppLogger только идентификаторы и счётчики задач — названия не фигурируют. Пути утечки через sync-логи нет.

**Исключение: `RecurrenceSpawnWorker`.** Он логирует `task.title` в диагностических строках (`spawn['…']: created 1 occurrence(s)`). Если партнёр возьмёт телефон и зайдёт в Настройки → Диагностика → Логи, он теоретически может увидеть название секретной повторяющейся задачи. Это ограничение текущей реализации без PIN-защиты; для обычных (не повторяющихся) секретных задач таких записей нет.

---

## Tombstone секретной задачи

Когда секретная задача удаляется, создаётся tombstone (`deleted=true, isSecret=true`). Tombstone никогда не уходит партнёру. После первого же успешного WiFi-sync `purgeConfirmedTombstones` его физически удалит: условие «remote не прислал эту задачу» выполняется всегда — партнёр о ней не знает. Это корректное поведение: tombstone секретной задачи бесполезен, задача исчезает из БД чисто.

---

## Миграция БД

`MIGRATION_3_4` (`AppDatabase.kt`):

```sql
ALTER TABLE tasks ADD COLUMN isSecret INTEGER NOT NULL DEFAULT 0
```

Все существующие задачи получают `isSecret = 0` (не секретные). Зарегистрирована в `DatabaseModule.kt`.

---

## Известные ограничения

- **Видимость на экране.** Секретная задача защищена от передачи, но не от взгляда партнёра на экран. PIN-защита или скрытие секретных задач за отдельным разделом не реализованы — это отдельная фича.
- **Уведомления работают без изменений.** Если `assignedTo == myDeviceId`, due-уведомление придёт в обычном режиме. Push-текст «Срок задачи через 1 час» не содержит названия задачи, утечки нет.
- **Повторяющиеся задачи.** Секретная задача с `recurrence != None` создаёт клоны только на своём устройстве (owner-only спавн). Клоны наследуют `isSecret = true` через `task.copy(...)` в `RecurrenceSpawnWorker`.
- **Экспортный файл от партнёра.** При импорте файла от партнёра секретные задачи партнёра не придут — он их тоже не экспортирует.
