# Plan улучшений FamilyTasks

Собрано из двух обсуждений:
- Анализ энергоэффективности текущей синхронизации
- Разбор стека Syncthing и применимости к проекту

**Легенда усилий:** 🟢 минимальное · 🟡 малое · 🟠 среднее · 🔴 высокое  
**Легенда выгоды:** ↑ малая · ↑↑ средняя · ↑↑↑ высокая

---

## 1. NetworkType.CONNECTED в WorkManager

**Усилие:** 🟢 минимальное (3 строки)  
**Выгода:** ↑↑ корректность поведения

**Зачем:** `SyncWorker` сейчас запускается WorkManager-ом даже без WiFi и сразу выходит (`peerIp == null`). Это бесполезный запуск воркера.

**Что сделать:**
- Файл: `FamilyTasksApplication.kt`, метод `schedulePeriodicSync()`
- Добавить `Constraints` с `NetworkType.CONNECTED` к `PeriodicWorkRequestBuilder`

```kotlin
val constraints = Constraints.Builder()
    .setRequiredNetworkType(NetworkType.UNMETERED)
    .build()

val request = PeriodicWorkRequestBuilder<SyncWorker>(5, TimeUnit.MINUTES)
    .setConstraints(constraints)
    .build()
```

> **Примечание:** WorkManager принудительно поднимает минимум до 15 минут в production. Код говорит 5 — работает как 15. Это нормальное поведение.

---

## 2. Адаптивный интервал UDP broadcast

**Усилие:** 🟡 малое  
**Выгода:** ↑↑ снижение пробуждений WiFi-радио

**Зачем:** Сейчас UDP broadcast отправляется каждые 30 секунд **всегда** — даже если `peerIp` уже известен. После обнаружения партнёра частота открытия обнаружения не нужна.

**Что сделать:**
- Файл: `UdpDiscovery.kt`, метод `startBroadcasting()`
- Добавить зависимость от `SyncManager` (или передавать callback/флаг наличия peer)
- Использовать длинный интервал, если peer уже найден

```kotlin
// Логика в цикле:
val interval = if (peerIsKnown()) 120_000L else 30_000L
delay(interval)
```

**Варианты реализации:**
- Передать `SyncManager` в `UdpDiscovery` (простейший)
- Передать `() -> Boolean` лямбду `isPeerKnown` (чище по зависимостям)

---

## 3. Освобождать MulticastLock когда IP партнёра закеширован

**Усилие:** 🟠 среднее  
**Выгода:** ↑↑↑ самый значимый выигрыш по батарее

**Зачем:** `MulticastLock` — запрет WiFi-чипу фильтровать broadcast/multicast пакеты. Пока lock держится, чип обрабатывает весь широковещательный мусор в сети вместо аппаратной фильтрации. Это постоянный расход, независимо от активности приложения.

**Что сделать:**
- Файл: `UdpDiscovery.kt`
- После успешного `onPeerDiscovered` — отпускать `multicastLock`
- При потере `peerIp` (например, timeout `lastSeenAt`) — снова захватывать для очередного цикла поиска
- Добавить метод `UdpDiscovery.releaseLock()` / `acquireLock()` с вызовом из `SyncService`

**Сложности:**
- Нужен механизм "peer пропал" — сейчас его нет. `lastSeenAt` обновляется, но нет порога "считать peer потерянным"
- Предлагается порог: если `lastSeenAt` старше 5 минут — сбрасывать `peerIp = null` и переходить в режим активного поиска

---

## 4. Переиспользование DatagramSocket для отправки broadcast

**Усилие:** 🟢 минимальное  
**Выгода:** ↑ незначительная (устраняет 2 syscall каждые 30 с)

**Зачем:** `sendBroadcast()` в `UdpDiscovery.kt:91` создаёт и закрывает `DatagramSocket` при каждом вызове. Незначительно, но бесплатное улучшение.

**Что сделать:**
- Файл: `UdpDiscovery.kt`
- Вынести `DatagramSocket` для отправки в поле класса, открывать в `start()`, закрывать в `stop()`
- Отдельно от `listenSocket` (тот уже хранится как поле)

---

## 5. Delta sync — передавать только изменённые задачи

**Усилие:** 🟡 малое  
**Выгода:** ↑↑ снижение сетевого трафика при каждом sync

**Зачем:** Сейчас при каждом sync отправляются **все** задачи. При накоплении задач это избыточно: если с момента последнего sync изменилось 2 задачи из 50 — незачем гонять остальные 48.

**Что сделать:**

**1) Расширить `SyncPayload` в `SyncDto.kt`:**
```kotlin
@Serializable
data class SyncPayload(
    val deviceId: String,
    val sinceTimestamp: Long = 0,   // ← добавить: клиент сообщает свой lastSyncAt
    val tasks: List<TaskDto>,
    val devices: List<DeviceDto>
)
```

**2) Фильтрация на стороне `HttpServer.kt`:**
```kotlin
// При формировании ответа — отдавать только задачи новее sinceTimestamp запроса
val filteredTasks = localTasks.filter { it.updatedAt > incoming.sinceTimestamp }
```

**3) Аналогично в `SyncService.triggerSync()` и `SyncWorker.doWork()`:**
```kotlin
val payload = SyncPayload(
    deviceId = deviceIdManager.deviceId,
    sinceTimestamp = prefs.lastSyncAt,  // ← передаём свой lastSyncAt
    tasks = localTasks
        .filter { it.updatedAt > partnerLastSyncAt }  // опционально, если знаем
        .map { it.toDto() },
    ...
)
```

**Важное ограничение:** tombstone-задачи (`deleted = true`) **обязаны** попадать в delta, иначе удаление не дойдёт до партнёра. Фильтрация только по `updatedAt > sinceTimestamp` корректна — tombstone всегда обновляет `updatedAt`.

---

## 6. Debounce перед triggerSync (на будущее)

**Усилие:** 🟡 малое  
**Выгода:** ↑ только если добавить instant-sync при сохранении задачи

**Зачем:** Если в будущем добавить "синхронизировать сразу после сохранения задачи" — без debounce каждое нажатие кнопки при редактировании будет порождать отдельный HTTP-запрос. Паттерн из Syncthing: ждать 5–10 секунд после последнего изменения, затем запускать sync.

**Что сделать:**
- Файл: `SyncManager.kt` или новый `SyncDebouncer`
- `Job` с `delay(5_000)`, отменяемый при каждом новом вызове `scheduleSyncDebounced()`
- Вызывать из `TaskRepositoryImpl` после успешного `upsertTask`

**Сейчас не нужно** — sync не привязан к сохранению задачи. Реализовывать только вместе с instant-sync фичей.

---

## 7. HTTPS + mTLS с certificate pinning

**Усилие:** 🔴 высокое  
**Выгода:** ↑↑↑ безопасность в недоверенных сетях (кафе, гости, офис)

**Зачем:** Сейчас `POST /sync` на порту 8080 — полностью открытый plain HTTP. Любое устройство в той же WiFi-сети может:
- получить все задачи семьи (GET-подобный запрос с пустым payload)
- внедрить фейковые задачи
- удалить чужие задачи через tombstone

**Что сделать:**

**Шаг 1 — Генерация сертификата при первом запуске**
- Файл: `DeviceIdManager.kt` (или новый `CertManager.kt`)
- `KeyPairGenerator.getInstance("RSA")` → X.509 self-signed cert
- Хранить в Android `KeyStore` (системное хранилище, не файл)
- Вычислять SHA-256 fingerprint сертификата

**Шаг 2 — Включить fingerprint в QR-код**
- Файл: `QrCodeViewModel.kt`, `OnboardingViewModel.kt`
- Текущий формат QR: `{ deviceId, displayName, color }`
- Новый формат: `{ deviceId, displayName, color, certFingerprint }`
- Хранить `pairedCertFingerprint` в `PreferencesManager`

**Шаг 3 — HTTPS в HttpServer**
- Файл: `HttpServer.kt`
- NanoHTTPD поддерживает SSL: `makeSSLSocketFactory(keyStore, password)`
- Загрузить сертификат устройства из Android KeyStore → передать в NanoHTTPD

**Шаг 4 — CertificatePinner в OkHttpClient**
- Файл: `HttpClient.kt`
- `CertificatePinner.Builder().add("*", "sha256/$pairedFingerprint")`
- Передать fingerprint партнёра в `HttpClient` через конструктор или Hilt

**Шаг 5 — Обновить AndroidManifest.xml**
- Убрать `cleartextTrafficPermitted="true"` (или ограничить только localhost)
- После перехода на HTTPS cleartext больше не нужен

**Основная сложность:** синхронизация состояния — `HttpClient` должен знать fingerprint партнёра до первого запроса. Нужно пробросить через `PreferencesManager` → `AppModule`.

---

## Что реализовывать НЕ стоит

| Идея | Причина |
|---|---|
| Block Exchange Protocol (как в Syncthing) | Задачи — маленькие JSON-объекты, не файлы. Блочная модель не нужна |
| Protobuf + LZ4 сжатие | Payload задач ~1–5 КБ. JSON приемлем, выгода незначительна |
| Persistent TCP соединение + PING keepalive | Оверинжиниринг для 2 устройств с редкими изменениями |
| Global Discovery + Relay серверы | Приложение явно работает только в локальной сети, без внешних серверов |
| Delta Index с `sequence_number` | `updatedAt` уже играет роль sequence — достаточно |
| Conflict-файлы (как в Syncthing) | Last-write-wins задокументировано как приемлемое поведение. Усложнение UX не оправдано |
| Multi-source синхронизация | Только 2 устройства по архитектуре |

---

## Сводная таблица приоритетов

| # | Улучшение | Усилие | Выгода | Файлы |
|---|---|---|---|---|
| 1 | NetworkType.CONNECTED в WorkManager | 🟢 | ↑↑ | `FamilyTasksApplication.kt` |
| 2 | Адаптивный интервал UDP broadcast | 🟡 | ↑↑ | `UdpDiscovery.kt` |
| 3 | Delta sync (`sinceTimestamp`) | 🟡 | ↑↑ | `SyncDto.kt`, `HttpServer.kt`, `SyncService.kt`, `SyncWorker.kt` |
| 4 | Переиспользование DatagramSocket | 🟢 | ↑ | `UdpDiscovery.kt` |
| 5 | Освободить MulticastLock после обнаружения | 🟠 | ↑↑↑ | `UdpDiscovery.kt`, `SyncService.kt`, `SyncManager.kt` |
| 6 | HTTPS + mTLS + certificate pinning | 🔴 | ↑↑↑ | `HttpServer.kt`, `HttpClient.kt`, `QrCodeViewModel.kt`, `OnboardingViewModel.kt`, `PreferencesManager.kt`, `AppModule.kt`, `AndroidManifest.xml` |
| 7 | Debounce перед sync | 🟡 | ↑ | `SyncManager.kt` — только вместе с instant-sync |

---

## Рекомендуемый порядок реализации

**Итерация 1 — быстрые победы** (можно сделать за один день):
- [x] улучшение #1 — NetworkType constraint
- [x] улучшение #4 — переиспользование сокета
- [x] улучшение #2 — адаптивный UDP интервал

**Итерация 2 — протокол** (потребует тестирования обоими устройствами):
- [ ] улучшение #3 — delta sync

**Итерация 3 — battery** (требует продуманного state machine):
- [ ] улучшение #5 — MulticastLock management + "peer lost" timeout

**Итерация 4 — security** (ломает обратную совместимость, потребует повторного QR-сопряжения):
- [ ] улучшение #6 — mTLS

**По необходимости:**
- [ ] улучшение #7 — debounce, только если добавляем instant-sync
