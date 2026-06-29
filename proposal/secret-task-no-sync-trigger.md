# Секретная задача — не тригерить WiFi-sync

> **Реализовано: Вариант А** — включён в реализацию фичи «Секретные задачи» (см. `docs/secret-tasks.md`).

## Контекст

`observeTaskChanges()` в `SyncService.kt` подписана на `taskRepository.observeAllTasks()` — Room-поток, который эмитит при изменении **любой** строки в таблице `tasks`. Если секретная задача создаётся или редактируется, поток эмитит → debounce 3 сек → `triggerSync()`. Сам sync безвреден (секретная задача отфильтрована из payload), но это лишний сетевой round-trip и лишняя запись в лог.

Текущий код (`SyncService.kt`):

```kotlin
private fun observeTaskChanges() {
    scope.launch {
        taskRepository.observeAllTasks()
            .distinctUntilChanged()
            .drop(1)
            .debounce(3_000L)
            .collect { triggerSync() }
    }
}
```

---

## Вариант А — фильтр внутри `observeTaskChanges()`

Добавить `.map { tasks -> tasks.filter { !it.isSecret } }` до `distinctUntilChanged()`:

```kotlin
private fun observeTaskChanges() {
    scope.launch {
        taskRepository.observeAllTasks()
            .map { tasks -> tasks.filter { !it.isSecret } }
            .distinctUntilChanged()
            .drop(1)
            .debounce(3_000L)
            .collect { triggerSync() }
    }
}
```

**Как работает:** если изменилась только секретная задача, отфильтрованный список не меняется → `distinctUntilChanged()` не пропускает эмиссию → `triggerSync()` не вызывается. Порядок операторов сохранён (критично по конвенции: нельзя менять местами `distinctUntilChanged()` и `drop(1)`).

**Правки:** одна строка в одном файле — `SyncService.kt`.

**Минус:** Room всё равно эмитит полный список при изменении секретной задачи; фильтрация происходит в Kotlin, не в SQL. Для десятков задач несущественно.

---

## Вариант Б — отдельный DAO-запрос

Добавить запрос в `TaskDao.kt`:

```kotlin
@Query("SELECT * FROM tasks WHERE isSecret = 0 ORDER BY createdAt DESC")
fun observeNonSecretTasks(): Flow<List<TaskEntity>>
```

Пробросить через `TaskRepository` и `TaskRepositoryImpl`. В `observeTaskChanges()` заменить поток:

```kotlin
taskRepository.observeNonSecretTasks()
    .distinctUntilChanged()
    .drop(1)
    .debounce(3_000L)
    .collect { triggerSync() }
```

**Как работает:** Room не эмитит вообще при изменении секретных задач — фильтр на уровне SQLite-запроса.

**Правки:** `TaskDao.kt`, `TaskRepository.kt` (интерфейс), `TaskRepositoryImpl.kt`, `SyncService.kt`.

**Минус:** больше файлов; семантически точнее, но практической разницы нет при малом числе задач.

---

## Итог

| | Вариант А | Вариант Б |
|---|---|---|
| Файлов затронуто | 1 | 4 |
| Фильтрация | Kotlin (после эмита) | SQL (до эмита) |
| Корректность | Да | Да |
| Практическая разница | Нет (десятки задач) | Нет |

**Рекомендация:** Вариант А — меньше правок, проще ревьюить, поведение идентично.
