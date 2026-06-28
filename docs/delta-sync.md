# Delta-sync — отправка только изменённых задач

---

## Проблема

Каждый POST /sync отправлял **весь** локальный снимок задач. При росте числа задач — избыточная сериализация и передача при каждом изменении (debounce 3 сек). Основная нагрузка — CPU и память (JSON-сериализация), а не надёжность сети.

---

## Схема

### Watermark

Каждое устройство хранит `lastConfirmedSyncTimestamp` (`PreferencesManager`, ключ `"last_confirmed_sync_ts"`, default `0L`) — время последнего успешно подтверждённого sync-раунда в роли initiator-а.

В запросе отправляются только задачи с `updatedAt > lastConfirmedSyncTimestamp`. Ответ партнёра тоже фильтруется по тому же `sinceTimestamp` (см. «Симметричная дельта» ниже).

### Порядок операций в `triggerSync()`

```kotlin
val snapshotTimestamp = System.currentTimeMillis()   // 1. ДО snapshot
val watermark = prefs.lastConfirmedSyncTimestamp
    .takeIf { snapshotTimestamp >= it } ?: run {     // 2. guard на clock jump
        prefs.lastConfirmedSyncTimestamp = 0L
        0L
    }
val localTasks = syncUseCase.getLocalSnapshot()      // 3. полный снимок (нужен для purge)
val delta = localTasks.filter { it.updatedAt > watermark }
val payload = SyncPayload(
    sinceTimestamp = watermark,
    tasks = delta.map { it.toDto() }, ...
)
// ... http call ...
if (success) {
    mergeAll(...)
    purgeConfirmedTombstones(localTasks, remoteTasks)
    prefs.lastConfirmedSyncTimestamp = snapshotTimestamp  // 4. advance после success
}
```

**Почему `snapshotTimestamp` берётся до `getLocalSnapshot()`:** если задача изменилась во время самого HTTP-раунда (`updatedAt = T+1`, снимок строился при `T`), watermark выставится в `T`. Следующая дельта включит `T+1 > T` — ничего не потеряется. Если бы watermark брался в момент success (`T+2`), то `T+1 < T+2` и изменение пропало бы навсегда.

### Симметричная дельта

Responder в `onSyncRequest` тоже фильтрует свой ответ по `incoming.sinceTimestamp`:

```kotlin
val responseTasks = if (incoming.sinceTimestamp > 0L) {
    localTasks.filter { it.updatedAt > incoming.sinceTimestamp }
} else {
    localTasks
}
```

**Почему это безопасно без отдельного watermark на стороне responder-а:** `sinceTimestamp = T` означает, что initiator подтвердил все данные до `T`. В предыдущем раунде initiator получил полный ответ responder-а, значит у initiator-а есть все задачи responder-а с `updatedAt ≤ T`. Responder может безопасно ответить только `updatedAt > T`.

Если initiator сбросил watermark (`sinceTimestamp = 0`) — responder возвращает полный снимок. Никакого дополнительного состояния на стороне responder-а не требуется.

---

## Guard на clock jump-back

`System.currentTimeMillis()` не монотонный: NTP-корректировка или ручная смена времени могут двинуть часы назад. Тогда `snapshotTimestamp < lastConfirmedSyncTimestamp`, и фильтр `updatedAt > watermark` тихо исключит все недавние задачи навсегда.

Guard в `triggerSync()`: если `snapshotTimestamp < watermark` — сбросить watermark в `0` и залогировать `"Clock jumped back — resetting sync watermark to 0"`. Следующий sync станет полным снимком.

---

## DTO

```kotlin
@Serializable
data class SyncPayload(
    val deviceId: String,
    val sinceTimestamp: Long = 0L,   // default 0 = полный снимок
    val tasks: List<TaskDto>,
    val devices: List<DeviceDto>
)
```

`sinceTimestamp = 0` — backward-compatible: старая версия без delta sync проигнорирует незнакомое поле (kotlinx.serialization). Старая версия в роли initiator-а не шлёт `sinceTimestamp` → responder получит `0` → отдаст полный снимок.

---

## Watermark и tombstone purge — общий gate

Оба механизма срабатывают в одном месте — success-ветке `triggerSync()` после `response.deviceId == prefs.pairedDeviceId`:

1. `mergeAll(remoteTasks, remoteDevices)`
2. `purgeConfirmedTombstones(localTasks, remoteTasks)`
3. `prefs.lastConfirmedSyncTimestamp = snapshotTimestamp`

`localTasks` — полный снимок (не дельта), взятый до отправки. Нужен и для purge, и как база для фильтрации дельты.

---

## Ключевые правила watermark

- **Продвигается только при success** (null response, PeerUnreachableException, self-loop — не продвигают). Следующая дельта снова включит весь период с прошлого успеха.
- **Watermark на стороне responder-а не хранится** — responder использует `sinceTimestamp` из входящего запроса как фильтр для своего ответа.
- **Сброс в `0`** нужен при: первом запуске после сопряжения, смене paired-устройства, clock jump-back (автоматически), восстановлении из бэкапа (будущая опция в настройках).

---

## Диагностика

Лог-строка при каждом `triggerSync()`:
```
Delta: sending 2/47 tasks (watermark=1718892000000)
```
`2/47` — задач в дельте / всего в локальной БД.

---

## Совместимость с tombstone purge

После purge tombstone физически удалён из БД. В следующей дельте (`updatedAt > watermark`) его уже нет — корректно: партнёр подтвердил, что тоже держит его как `deleted`.

Если партнёр (старая версия, без purge) повторно пришлёт уже вычищенный tombstone — initiator примет его через `mergeAll` (ветка `local == null` → `toUpsert`), а при следующем успешном раунде снова purge'ит. Временный «пинг-понг», прекращается после обновления партнёра.

---

## Риски и edge cases

| Сценарий | Последствие | Митигация |
|---|---|---|
| Clock jump-back | Дельта пустая, изменения не уходят | Guard сбрасывает watermark в 0 автоматически |
| Восстановление из бэкапа при уже вычищенных tombstone у партнёра | Resurrection активной задачи | Известный trade-off tombstone GC; ручной сброс watermark снимет симптомы |
| Watermark продвинулся, crash до записи в SharedPreferences | Следующая дельта включит чуть больше | Idempotent merge справляется — лишние задачи попадут в `unchanged` |
| Два устройства одновременно стали initiator-ом | Один из раундов упрётся в `syncMutex` | Существующий механизм, не изменялся |
