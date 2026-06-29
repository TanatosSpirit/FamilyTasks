# Proposal: фото-аватарка вместо цветного кружка

**Статус:** предлагается к реализации  
**Приоритет:** низкий (UX-улучшение, не влияет на функциональность)  
**Затрагивает:** `SettingsScreen.kt`, `SettingsViewModel.kt`, `PreferencesManager.kt`, `SyncDto.kt`, `SyncService.kt`, `TaskListScreen.kt`, `CreateTaskScreen.kt`, `TaskDetailScreen.kt`, `build.gradle.kts`

---

## Суть фичи

Пользователь кликает по цветному кружку своего аккаунта в Настройках → открывается инструмент выбора фото из галереи → выбранное фото кропается в круг (zoom + pan внутри фиксированного круглого кадра, как везде принято) → сохраняется как JPEG 200×200 → отображается вместо цветного кружка во всём приложении. Цвет остаётся как fallback, пока аватарка не выбрана. Аватарка партнёра приходит по WiFi-синку.

---

## Текущее состояние

- `Device.color: Int` — единственное визуальное поле, ARGB-цвет
- `DeviceDto.color: Int` передаётся в каждом `POST /sync`
- Цветной кружок отрисовывается в ~5 местах: Settings (своё + партнёр), TaskList (назначен), CreateTask / EditTask (выбор исполнителя)
- Нет библиотек для загрузки изображений и кропа

---

## Из чего состоит реализация

### 1. Выбор фото

`ActivityResultContracts.PickVisualMedia` — Android Photo Picker, не требует разрешений на API 33+. На API 26–32 fallback через `GetContent("image/*")` + разрешение `READ_EXTERNAL_STORAGE`. Запускается через `rememberLauncherForActivityResult`.

### 2. Кроп

Встроенного кроп-инструмента в Android нет. Два варианта:

**Вариант A — uCrop (рекомендуется)**

Библиотека `com.github.yalantis:ucrop` (~200KB AAR). Activity-based (не Compose-native, но запускается через `rememberLauncherForActivityResult`). Поддерживает: кроп в круг, zoom + pan пальцем, задаётся целевой размер. Одна зависимость, дни реализации — 1.

```kotlin
// Запуск uCrop
val uCropLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
    if (result.resultCode == Activity.RESULT_OK) {
        val uri = UCrop.getOutput(result.data!!)
        vm.saveAvatar(uri)
    }
}

// В обработчике выбора фото
val destUri = Uri.fromFile(File(context.cacheDir, "avatar_crop.jpg"))
val options = UCrop.Options().apply {
    setCircleDimmedLayer(true)
    setCompressionFormat(Bitmap.CompressFormat.JPEG)
    setCompressionQuality(80)
}
uCropLauncher.launch(
    UCrop.of(sourceUri, destUri)
        .withAspectRatio(1f, 1f)
        .withMaxResultSize(200, 200)
        .withOptions(options)
        .getIntent(context)
)
```

**Вариант B — кастомный Compose-кроп**

`Modifier.transformable` + `Canvas` + `clip(CircleShape)`. ~300–400 строк, много edge-case'ов (overscroll, pinch limits). 2–3 дня. Оправдан только если важно избежать Activity-based UX или нет возможности добавить зависимость.

### 3. Хранение

```
filesDir/
  avatars/
    own.jpg               ← своя аватарка
    {pairedDeviceId}.jpg  ← аватарка партнёра
```

Файл: JPEG, 200×200 пикселей, quality 80 → ~5–15 КБ. Путь известен через `context.filesDir`. Никакой дополнительной миграции БД не нужно — Room не задействован.

В `PreferencesManager` добавить версию для отслеживания изменений:

```kotlin
var avatarVersion: Long
    get() = prefs.getLong(KEY_AVATAR_VERSION, 0L)
    set(value) = prefs.edit().putLong(KEY_AVATAR_VERSION, value).apply()

var peerAvatarVersion: Long
    get() = prefs.getLong(KEY_PEER_AVATAR_VERSION, 0L)
    set(value) = prefs.edit().putLong(KEY_PEER_AVATAR_VERSION, value).apply()
```

### 4. Отображение

Добавить **Coil** (`io.coil-kt:coil-compose`). Логика: если файл аватарки существует — `AsyncImage`, иначе — цветной `Box` как сейчас.

```kotlin
@Composable
fun DeviceAvatar(avatarFile: File?, color: Int, size: Dp = 40.dp) {
    if (avatarFile != null && avatarFile.exists()) {
        AsyncImage(
            model = avatarFile,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(size).clip(CircleShape)
        )
    } else {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(Color(color))
        )
    }
}
```

Этот composable заменяет все 5 мест с цветным кружком.

### 5. Синхронизация по WiFi

Размер: 200×200 JPEG 80% ≈ 5–15 КБ → Base64 ≈ 7–20 КБ. Для домашнего WiFi незначительно.

Добавить два поля в `DeviceDto`:

```kotlin
@Serializable
data class DeviceDto(
    val deviceId: String,
    val displayName: String,
    val color: Int,
    val avatarBase64: String? = null,    // null = аватарки нет, показывать цвет
    val avatarVersion: Long = 0L         // timestamp последнего изменения
)
```

В `SyncService.buildDevicesPayload()`:

```kotlin
val ownAvatarFile = File(filesDir, "avatars/own.jpg")
DeviceDto(
    deviceId = prefs.deviceId,
    displayName = prefs.displayName,
    color = prefs.avatarColor,
    avatarBase64 = if (ownAvatarFile.exists()) {
        Base64.encodeToString(ownAvatarFile.readBytes(), Base64.NO_WRAP)
    } else null,
    avatarVersion = prefs.avatarVersion
)
```

При получении sync-ответа: если `avatarVersion` партнёра новее сохранённого → декодировать и записать файл.

```kotlin
// После merge, в triggerSync()
response.devices.find { it.deviceId == prefs.pairedDeviceId }?.let { dto ->
    if (dto.avatarBase64 != null && dto.avatarVersion > prefs.peerAvatarVersion) {
        val bytes = Base64.decode(dto.avatarBase64, Base64.NO_WRAP)
        File(filesDir, "avatars/${prefs.pairedDeviceId}.jpg").writeBytes(bytes)
        prefs.peerAvatarVersion = dto.avatarVersion
    }
}
```

Старые клиенты (без поля) получат `avatarBase64 = null` по умолчанию — упадут на цвет. Обратная совместимость не ломается.

**Альтернатива — отдельный эндпоинт `GET /avatar/{deviceId}`:** чище архитектурно (не мешает JSON с бинарными данными), но добавляет второй HTTP round-trip и версионирование хэшей. Для 2-устройственного приложения Base64 в DTO проще и достаточно.

---

## Что нужно добавить / изменить

| Зависимость / файл | Изменение |
|---|---|
| `build.gradle.kts` | + `io.coil-kt:coil-compose`, + `com.github.yalantis:ucrop` |
| `PreferencesManager.kt` | + `avatarVersion`, `peerAvatarVersion` |
| `SyncDto.kt` | + `avatarBase64`, `avatarVersion` в `DeviceDto` |
| `SyncService.kt` | заполнять avatarBase64 при отправке, сохранять при получении |
| `SettingsScreen.kt` | клик по кружку → uCrop flow; новый `DeviceAvatar` composable |
| `SettingsViewModel.kt` | `saveAvatar(uri)` — читает файл, сжимает, сохраняет, обновляет `avatarVersion` |
| `TaskListScreen.kt`, `CreateTaskScreen.kt`, `TaskDetailScreen.kt` | заменить Box-кружки на `DeviceAvatar` |
| `AndroidManifest.xml` | `READ_EXTERNAL_STORAGE` для API 26–32 (только если нужен fallback) |

---

## Ограничения и решения

**Аватарка только своя — не партнёра при онбординге.** При первом QR-сопряжении аватарка ещё не передаётся (QR — только JSON с `deviceId`, `displayName`, `color`). Придёт при первом WiFi-синке. Это нормально: пока нет синка — цветной кружок.

**Размер payload на каждом синке.** Аватарка отправляется в каждом `POST /sync`, даже если не изменилась. Оптимизация: не отправлять если `dto.avatarVersion <= peerAvatarVersion` (требует хранения версии партнёра). При 20 КБ на запрос для домашней сети некритично, но оптимизация тривиальна.

**uCrop — не Compose-native.** Открывается как отдельная Activity, визуально отличается от остального Compose-UI. Для MVP приемлемо; при желании заменяется кастомным Compose-кропом без смены остальной архитектуры.

---

## Рекомендуемый стек

- **uCrop** — кроп (минимум кода, проверенная библиотека)
- **Coil** — отображение (AsyncImage, кэш, clip)
- **Base64 в DeviceDto** — синхронизация (без смены протокола)
- **filesDir/avatars/** — хранение (простой файловый доступ)

Ориентировочный объём: **1.5–2 дня** реализации.
