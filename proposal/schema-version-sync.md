# Proposal: schemaVersion в SyncPayload

**Статус:** предлагается к реализации  
**Приоритет:** низкий (workaround — пересохранить задачу)  
**Затрагивает:** `SyncDto.kt`, `AppDatabase.kt`, `SyncService.kt`, `SyncUseCase.kt`, `MergeTasksUseCase.kt`

---

## Проблема

При rolling upgrade (партнёр обновляется позже) возможна потеря данных в полях, добавленных новой миграцией БД.

**Конкретный случай, выявленный 2026-06-28:**  
Задача создана с `repeatRule = "days:3"` на устройстве A (schema v3).  
Партнёр B имел schema v2 — поля `repeatRule` в его БД не было.  
После синхронизации B сохранил задачу с `repeatRule = "none"` (DEFAULT миграции).  
После обновления B до v3 задача осталась с `recurrence = None` — повтор потерян.

**Механика потери:**  
1. B (старая версия) получает задачу через `/sync`, `repeatRule` в DTO неизвестен → сохраняет `"none"`.  
2. B редактирует задачу по другому поводу → `updatedAt` у B становится выше.  
3. Следующий sync: LWW отдаёт победу B → `repeatRule = "none"` перезаписывает правильное значение на A.

Без шага 2 проблема не проявляется (tie-break оставляет локальную версию).

---

## Обходной путь (без изменений кода)

На устройстве-владельце задачи: открыть задачу → сохранить без изменений (или сделать любую правку). `updatedAt` обновится, LWW при следующем sync вернёт правильный `repeatRule` партнёру.

---

## Предлагаемые варианты реализации

### Вариант 1: Жёсткий отказ от merge при несовместимой схеме

**Идея:** если `remoteSchemaVersion < localSchemaVersion`, принять sync-payload партнёра как есть (чтобы отдать свои данные), но не применять его задачи к локальной БД.

**Изменения:**

```kotlin
// SyncDto.kt
@Serializable
data class SyncPayload(
    val deviceId: String,
    val schemaVersion: Int = 1,  // default = 1 для совместимости со старыми версиями
    val tasks: List<TaskDto>,
    val devices: List<DeviceDto>
)
```

```kotlin
// SyncService.kt — buildSyncPayload()
SyncPayload(
    deviceId = prefs.deviceId,
    schemaVersion = AppDatabase.DATABASE_VERSION,
    tasks = ...,
    devices = ...
)
```

```kotlin
// SyncService.kt — onSyncRequest()
if (payload.schemaVersion < AppDatabase.DATABASE_VERSION) {
    AppLogger.w(TAG, "Peer schema ${payload.schemaVersion} < local ${AppDatabase.DATABASE_VERSION}, skipping merge")
    // Отвечаем своими данными, но их задачи не применяем
    val localSnapshot = syncUseCase.getLocalSnapshot()
    return@onSyncRequest localSnapshot
}
// ... обычный merge
```

**Плюсы:**
- ~10 строк кода
- Нет риска затереть данные
- A всегда отдаёт B актуальные данные; после обновления B — нормальная работа

**Минусы:**
- Sync заморожен в одну сторону пока B не обновится
- Изменения B за период rolling upgrade не попадают на A

**Подходит для:** семейного приложения, где оба устройства обновляются практически одновременно.

---

### Вариант 2: Field-level защита при merge

**Идея:** при победе удалённой задачи по LWW поля, добавленные в более новых миграциях, берутся из локальной копии, а не из удалённой.

**Изменения в `MergeTasksUseCase`:**

```kotlin
fun mergeAll(
    localTasks: List<Task>,
    remoteTasks: List<Task>,
    remoteSchemaVersion: Int          // ← новый параметр
): MergeResult {
    ...
    val winner = when {
        local == null -> remote  // новая задача — берём как есть
        remote.updatedAt > local.updatedAt -> {
            // Remote выигрывает LWW, но защищаем поля из новых схем
            remote.copy(
                // schema v2 добавил completedAt
                completedAt = if (remoteSchemaVersion < 2) local.completedAt else remote.completedAt,
                // schema v3 добавил repeatRule / recurrence
                recurrence  = if (remoteSchemaVersion < 3) local.recurrence  else remote.recurrence
            )
        }
        else -> local  // tie-break или локальный новее
    }
    ...
}
```

Каждая новая миграция добавляет одну строку в блок `copy`.

**Затрагиваемые файлы дополнительно:**
- `SyncUseCase.kt` — передаёт `remoteSchemaVersion` в `mergeAll`
- `MergeTasksUseCase.kt` — логика выше
- `AppDatabase.kt` — `DATABASE_VERSION` должен быть `const val`

**Плюсы:**
- Sync продолжает работать двусторонне даже при разных версиях
- Все изменения B применяются, кроме "молодых" полей
- Расширяемо: новые миграции — одна строка

**Минусы:**
- Больше кода и тестов
- Нужно явно перечислять "молодые" поля и версии их добавления
- Если B не знает поля и отправляет default — мы используем наше значение, но B по-прежнему не знает правильного; исправится только после обновления B

**Подходит для:** если между обновлениями устройств может пройти несколько дней и данные активно редактируются.

---

### Вариант 3: UI-предупреждение без блокировки merge

**Идея:** merge работает как сейчас (без защиты), но при получении payload с `remoteSchemaVersion < local` — показываем предупреждение в логах и/или в статусной строке.

```kotlin
// SyncService.kt
if (payload.schemaVersion < AppDatabase.DATABASE_VERSION) {
    AppLogger.w(TAG, "Peer app is outdated (schema ${payload.schemaVersion}). " +
        "Some fields may not sync correctly until partner updates.")
    // merge продолжается как обычно
}
```

Пользователь видит предупреждение в «Логи синхронизации» и знает, что нужно обновить партнёра.

**Плюсы:** минимум кода, полная совместимость  
**Минусы:** не предотвращает потерю данных, только информирует

---

## Рекомендация

Для текущей стадии проекта: **Вариант 1** (жёсткий отказ).

Простота реализации перевешивает временную заморозку одностороннего sync — обновления в семейном приложении ставятся в тот же день.

Вариант 2 уместен если появится сценарий, где устройства живут на разных версиях больше суток при активном использовании.

---

## Связанные файлы

- `app/src/main/java/com/familytasks/app/data/sync/SyncDto.kt`
- `app/src/main/java/com/familytasks/app/data/local/AppDatabase.kt`
- `app/src/main/java/com/familytasks/app/data/sync/SyncService.kt`
- `app/src/main/java/com/familytasks/app/domain/usecase/SyncUseCase.kt`
- `app/src/main/java/com/familytasks/app/domain/usecase/MergeTasksUseCase.kt`
- `docs/conventions-rationale.md` — правила merge и purge
