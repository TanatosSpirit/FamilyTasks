# Анализ лога FamilyTasks — 2026-06-13

Источник: `logs/familytasks_log.txt` (230 записей, период 15:54–23:06)

---

## Баги

### 1. Race condition в `triggerSync` guard

**Подтверждён логом:** строки 40 и 50 — два одновременных `triggerSync → 192.168.100.100` в 22:29:48, два параллельных POST /sync ушли к партнёру.

**Причина — `SyncService.kt` строки 279–285:**
```kotlin
if (syncManager.syncState.value == SyncState.SYNCING) {  // ← READ
    return
}
scope.launch {
    syncManager.setSyncState(SyncState.SYNCING)  // ← WRITE (отложено до запуска корутины)
```
Два вызова `triggerSync()` проходят проверку до того, как хоть одна корутина выставит `SYNCING`.

**Фикс:** перенести check-and-set внутрь корутины + `Mutex`:
```kotlin
private val syncMutex = Mutex()

private fun triggerSync() {
    val peerIp = syncManager.peerIp.value ?: run { ... return }
    scope.launch {
        if (!syncMutex.tryLock()) {
            AppLogger.d(TAG, "triggerSync skipped — already syncing")
            return@launch
        }
        try {
            syncManager.setSyncState(SyncState.SYNCING)
            AppLogger.i(TAG, "triggerSync → $peerIp")
            // ... тело sync ...
        } finally {
            syncMutex.unlock()
        }
    }
}
```

**Риск:** при текущей idempotent-merge логике данные не портятся, но `syncState` и `lastSyncAt` обновляются некорректно при параллельном исполнении.

---

### 2. KeepAlive ложно диагностирует "peer IP stale" при выключенном WiFi

**Подтверждён логом:** строка 123 (19:09:58) — `KeepAlive failed — peer IP stale, starting scan`, но WiFi выключен с 18:40:27. `httpClient.identify()` вернул `null` из-за отсутствия сети, не из-за смены IP. `peerIp` обнулён → при следующем подключении полный scan вместо быстрой проверки.

**Причина — `SyncService.kt` строки 248–257:** перед HTTP-вызовом нет проверки наличия WiFi.

**Фикс** — добавить в `startKeepAlive()` перед `httpClient.identify()`:
```kotlin
if (!isWifiConnected()) {
    AppLogger.d(TAG, "KeepAlive — WiFi not connected, skipping")
    continue
}
```

---

### 3. Неверный уровень лога для "WiFi connected"

**Подтверждён логом:** строки 120, 214, 229 — `W SyncService  WiFi connected, IP: ...`. Warning для информационного события засоряет фильтр по уровню E/W.

**Фикс — `SyncService.kt` строка 71:**
```kotlin
// Было:
AppLogger.w(TAG, "WiFi connected, IP: $ip")
// Стало:
AppLogger.i(TAG, "WiFi connected, IP: $ip")
```

---

### 4. Потенциальный баг: отсутствует лог "Scan complete" (~19:48)

Скан запущен в 19:47:58 (строка 122). Партнёр нашёл нас сам в 19:54:16 (строка 117). Между этими точками нет ни `Peer found at`, ни `Scan complete — peer not on subnet`. `SubnetScanner.kt` логирует оба исхода (строки 50 и 52–54), поэтому молчание аномально. Возможно: `CancellationException` до строки с логом.

**Фикс — `SyncService.kt`**, обернуть `subnetScanner.scan()` в `try/finally`:
```kotlin
scanJob = scope.launch {
    AppLogger.i(TAG, "Subnet scan started")
    try {
        val ip = subnetScanner.scan()
        if (ip != null) {
            syncManager.setPeerIp(ip)
            triggerSync()
        }
    } catch (e: CancellationException) {
        AppLogger.d(TAG, "Subnet scan cancelled")
        throw e
    }
}
```

---

## Косметика

### 5. Non-atomic счётчики в SubnetScanner

**`SubnetScanner.kt` строки 30–43:** `responded++` и `wrongId++` вызываются из 10 параллельных корутин без синхронизации → потерянные инкременты. Функционально не критично, только диагностический лог врёт.

**Фикс:**
```kotlin
val responded = AtomicInteger(0)
val wrongId   = AtomicInteger(0)
// внутри flow:
responded.incrementAndGet()
// ...
wrongId.incrementAndGet()
// в конце scan():
AppLogger.i(TAG, "Scan complete — ... (${responded.get()} responded, ${wrongId.get()} wrong deviceId)")
```

---

## Наблюдения (не баги в нашем коде)

**Повторные сканы от партнёра (20:35–20:54):** партнёр (`84362d3b`) слал анонимные `/identify` + `/sync` каждые ~3–7 мин (строки 92, 97, 102 и др.), все синки с 0 изменений. Признак нестабильности `peerIp` на стороне партнёра — вероятно, WiFi-флуктуации или аналогичный баг с KeepAlive (см. Баг 2).

**Смена IP партнёра .4 → .100** (после 67-мин разрыва 18:40–19:47): все механизмы восстановления отработали верно.

**Новая задача в 23:06:** первый sync с 43 задачами — партнёр получил новую задачу и вернул 43. Всё корректно.

---

## Очерёдность исправлений

| Приоритет | Баг | Файл | Строка |
|---|---|---|---|
| ✅ Готово | Race condition в `triggerSync` | `SyncService.kt` | 279–285 |
| 🟡 Средний | KeepAlive без WiFi-guard | `SyncService.kt` | 248 |
| 🟢 Низкий | `AppLogger.w` → `.i` для WiFi connected | `SyncService.kt` | 71 |
| 🟢 Низкий | Тихая отмена скана без лога | `SyncService.kt` | 226–233 |
| ⚪ Косметика | Non-atomic счётчики | `SubnetScanner.kt` | 30–43 |
