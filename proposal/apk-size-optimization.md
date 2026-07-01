# Proposal: Дальнейшая оптимизация размера APK

**Статус:** частично реализовано (см. «Что уже сделано»)  
**Приоритет:** низкий — текущий размер отличный для семейного приложения  
**Затрагивает:** `app/build.gradle.kts`, `app/proguard-rules.pro`, потенциально архитектурные слои

---

## Исходная точка и история изменений

Анализировалась release-сборка с R8 (`isMinifyEnabled = true`, `isShrinkResources = true`).

| Этап | APK (compressed) | DEX (compressed) |
|---|---|---|
| До сессии | 1 794 KB | 1 564 KB |
| После packaging excludes | 1 750 KB | 1 563 KB |
| После ZXing proguard | **1 691 KB** | **1 502 KB** |
| Итого экономия | **−103 KB (−5.7%)** | **−62 KB (−4%)** |

### Что уже реализовано

**1. Packaging excludes** (`app/build.gradle.kts`):
```kotlin
packaging {
    resources {
        excludes += setOf(
            "okhttp3/internal/publicsuffix/publicsuffixes.gz", // −40 KB
            "okhttp3/internal/publicsuffix/NOTICE",
            "DebugProbesKt.bin",   // debug-артефакт корутин в release
            "META-INF/NOTICE.md",
        )
    }
}
```

**2. Сужение keep-правил ZXing** (`app/proguard-rules.pro`):

Было:
```
-keep class com.google.zxing.** { *; }   // все 280 классов
-keep class com.journeyapps.** { *; }
```

Стало:
```
-keep class com.journeyapps.** { *; }
# com.google.zxing: let R8 tree-shake; only QR writer is used directly
```

Результат: `com.google.zxing` — 280 → 139 классов (−141). R8 удалил декодеры для EAN,
UPC, Code39, Code128, Aztec, DataMatrix, PDF417 и других форматов, которые не используются.
QR-генерация и сканирование проверены на реальном устройстве — работает.

---

## Распределение текущего APK

| Компонент | Классов | Доля DEX | Примечание |
|---|---|---|---|
| Compose (ui + foundation + material3 + runtime + animation) | ~2010 | 43.5% | Неизбежно |
| ZXing (com.google.zxing + journeyapps) | 213 | 4.8% | Уже оптимизировано |
| Наш код (com.familytasks.app) | 245 | 5.3% | — |
| WorkManager | 172 | 3.7% | Кандидат на замену |
| kotlinx.coroutines | ~195 | 4.2% | — |
| OkHttp | 115 | 2.5% | Кандидат на замену |
| kotlinx.serialization | ~144 | 3.1% | — |
| Hilt + Room + Navigation + Core | ~382 | 8.3% | — |

---

## Варианты дальнейшей оптимизации

---

### Вариант A: `resConfigs("ru")`

**Суть:** Gradle включает в APK только строки для указанных локалей. Зависимости (Compose,
WorkManager, Material и др.) несут переводы на 20+ языков — всё это лишнее.

**Изменение:**
```kotlin
defaultConfig {
    resConfigs("ru")
}
```

**За:**
- 5 минут работы, нулевой риск
- Экономия ~10–30 KB на `resources.arsc`
- Не затрагивает код

**Против:**
- Если какая-то системная библиотека показывает свой диалог без русской локализации — он
  отобразится на английском. На практике с Material3 + AppCompat это не встречается.

**Рекомендация:** Реализовать первым, самое дешёвое улучшение.

---

### Вариант B: Android App Bundle (AAB) вместо APK

**Суть:** Play Store нарезает из AAB отдельный APK под каждое устройство: только нужный ABI,
только нужная плотность экрана, только язык устройства. Пользователь скачивает меньше.

**За:**
- Без изменений кода — только формат сборки
- Для arm64-v8a устройства: убираются lib/x86_64 + lib/x86 + lib/armeabi-v7a (~27 KB на нативных либах)
- Play дополнительно сжимает ресурсы лучше встроенного aapt
- Реальная экономия загрузки: 20–40% от текущего размера

**Против:**
- Применимо **только при распространении через Google Play**
- При прямой установке (adb install, ссылка на файл) AAB не работает — нужен APK
- Этот проект распространяется как APK напрямую → вариант не применим сейчас

**Рекомендация:** Если когда-нибудь будет выход в Play Store — первый шаг.

---

### Вариант C: Заменить OkHttp на `HttpURLConnection`

**Суть:** OkHttp добавляет 115 классов (HTTP/2, interceptors, connection pooling, cookie jar,
TLS extensions). Для запросов к локальному IP по HTTP без куки нужен только базовый TCP-сокет.
`HttpURLConnection` встроен в Android, нулевой оверхед.

**Что меняется:** рефакторинг `HttpClient.kt` — замена `OkHttpClient` на `HttpURLConnection`
с теми же таймаутами.

**За:**
- Экономия ~40–50 KB в DEX
- Убирает зависимость полностью (включая `publicsuffixes.gz`, уже удалённый отдельно)
- `HttpURLConnection` достаточен для GET /identify и POST /sync по HTTP

**Против:**
- Несколько часов рефакторинга
- `HttpURLConnection` многословнее OkHttp: JSON-тело, таймауты, чтение ответа — больше
  boilerplate кода
- Нужно самостоятельно управлять потоками (OkHttp делает это за вас)
- Требует тщательного тестирования sync и scan после замены

**Рекомендация:** Осмысленно при желании убрать внешнюю зависимость целиком. Не критично.

---

### Вариант D: Заменить NanoHTTPD на `ServerSocket`

**Суть:** NanoHTTPD — полноценный HTTP-сервер с multipart, сессиями, mime-types, virtual files.
Из этого используется ровно два эндпоинта: GET /identify и POST /sync. Это ~50–80 строк на
чистом `ServerSocket`.

**За:**
- Убирает ~28 классов
- `HttpServer.kt` и так уже содержит всю логику — NanoHTTPD только парсит HTTP-запрос
- Исключает `META-INF/nanohttpd/` файлы из APK

**Против:**
- Нужно самостоятельно парсить HTTP: первую строку запроса, заголовки, тело
- Текущий `HttpServer.kt` использует удобные абстракции NanoHTTPD (`IHTTPSession`,
  `NanoHTTPD.newFixedLengthResponse`)
- Риск subtle-багов при парсинге (chunked encoding, неполное чтение тела)
- Экономия небольшая (28 классов ≈ 10–15 KB DEX)

**Рекомендация:** Интересно как упражнение, но соотношение риск/экономия плохое.

---

### Вариант E: Заменить WorkManager на AlarmManager

**Суть:** WorkManager добавляет 172 класса для управления фоновыми задачами. В проекте
он используется для двух воркеров: `DueNotificationWorker` (уведомления о дедлайне) и
`RecurrenceSpawnWorker` (клонирование повторяющихся задач). Оба — однократные задачи
"запусти через N миллисекунд". `AlarmManager.setExactAndAllowWhileIdle()` уже используется
для keepAlive и делает то же самое.

**За:**
- Убирает ~172 класса (~70–80 KB DEX)
- `AlarmManager` уже в проекте (keepAlive), паттерн знаком
- WorkManager здесь избыточен: нет chains, нет constraints, нет retry-политик

**Против:**
- Значительный рефакторинг: `DueNotificationWorker`, `RecurrenceSpawnWorker`,
  `NotificationScheduler`, `WorkerModule`, `HiltWorkerFactory` в `FamilyTasksApplication`
- `WorkManager` переживает перезапуск приложения и перепланирует воркеры автоматически.
  При переходе на `AlarmManager` это нужно реализовать самостоятельно через `BootReceiver`
  (который уже есть) + сохранение запланированных alarm'ов в SharedPreferences
- Несколько дней работы с тщательным тестированием

**Рекомендация:** Единственный большой рефакторинг, который реально оправдан при жёстком
требовании по размеру. Без такого требования — не стоит.

---

### Вариант F: Заменить journeyapps на CameraX + прямой `QRCodeReader`

**Суть:** journeyapps запускает отдельную Activity с собственным UI и камерой. Можно
встроить CameraX-превью прямо в Compose и вызывать `QRCodeReader` напрямую в ImageAnalysis.

**За:**
- Убирает 74 journeyapps класса
- Нативный Compose UI для сканера вместо чужой Activity
- CameraX — стандартная рекомендуемая библиотека для камеры

**Против:**
- CameraX добавляет свои зависимости (~100+ классов) — итоговый баланс может быть нулевым
  или отрицательным
- Полный рефакторинг экрана сканирования: `QrCodeScreen.kt`, `PortraitCaptureActivity`
- Lifecycle и permissions камеры — нетривиальная интеграция в Compose

**Рекомендация:** Интересно с точки зрения UX-контроля, но не для оптимизации размера.

---

## Сводная таблица

| Вариант | Экономия | Усилия | Риск | Рекомендация |
|---|---|---|---|---|
| A: `resConfigs("ru")` | ~10–30 KB | 5 мин | нет | **Сделать** |
| B: App Bundle | −20–40% загрузки | нет кода | нет | При выходе в Play Store |
| C: OkHttp → HttpURLConnection | ~40–50 KB DEX | несколько часов | средний | По желанию |
| D: NanoHTTPD → ServerSocket | ~10–15 KB DEX | день | средний | Не стоит |
| E: WorkManager → AlarmManager | ~70–80 KB DEX | несколько дней | высокий | Только при жёстком требовании |
| F: journeyapps → CameraX | ~0 KB (баланс) | несколько дней | высокий | Нет |

---

## Контекст

Текущий APK в **1 691 KB** для приложения на Compose + Hilt + Room + WorkManager + OkHttp +
ZXing — отличный результат. R8 full mode, `isShrinkResources`, baseline profile — стандартный
чеклист полностью выполнен.

Для сравнения: типичное Compose-приложение с таким стеком без оптимизации весит 8–12 MB.
Профессиональная команда с требованием минимального APK в первую очередь перешла бы на
App Bundle (Play Store) и сделала `resConfigs`. Рефакторинг WorkManager/OkHttp рассматривается
только при конкретном целевом показателе (например, "меньше 1 MB").
