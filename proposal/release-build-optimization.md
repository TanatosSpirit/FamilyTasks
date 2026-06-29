# Proposal: Release-сборка с R8-оптимизацией

**Статус:** реализовано  
**Приоритет:** высокий  
**Затрагивает:** `app/build.gradle.kts`, `app/proguard-rules.pro`

---

## Проблема

`app/build.gradle.kts` имел `isMinifyEnabled = false` в release-блоке. Следствия:

- APK не отличался по размеру от debug-сборки — R8 не запускался, несмотря на то что `proguard-android-optimize.txt` был подключён.
- Dead-code elimination, shrinking ресурсов, inline-оптимизации — всё отсутствовало.
- `material-icons-extended`, Compose, OkHttp и другие крупные зависимости полностью шли в APK без вырезания неиспользуемого кода.

---

## Изменения

### `app/build.gradle.kts`

```kotlin
release {
    isMinifyEnabled = true      // было false
    isShrinkResources = true    // добавлено
    proguardFiles(
        getDefaultProguardFile("proguard-android-optimize.txt"),
        "proguard-rules.pro"
    )
}
```

### `app/proguard-rules.pro`

Добавлены keep-правила для kotlinx.serialization:

```
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class com.familytasks.app.**$Companion {
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class com.familytasks.app.**
-keepclassmembers class com.familytasks.app.<1>$Companion {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers @kotlinx.serialization.Serializable class com.familytasks.app.** {
    *** Companion;
}
-keep,includedescriptorclasses class com.familytasks.app.**$$serializer { *; }
```

**Почему эти правила обязательны:** был проверен jar-файл `kotlinx-serialization-json-jvm-1.7.3.jar` из локального Gradle-кэша — в `META-INF/proguard` ничего нет, library не несёт consumer-правил. Без явного keep R8 мог бы вырезать метод `serializer()` у `@Serializable`-классов (`SyncDto.kt`, `QrCodeViewModel.kt`), и синхронизация молча бы сломалась в release-сборке — баг, который не воспроизводится в debug.

NanoHTTPD и ZXing keep-правила присутствовали ранее. Room и Hilt не требуют ручных правил — их AAR несут собственные consumer-rules.

---

## Результат

Release APK стал **в 10 раз меньше** debug-сборки.

---

## R8 как аналог `-O3`

R8 — это Android-аналог флага `-O3` в gcc/clang:

| gcc/clang | Android R8 |
|---|---|
| Dead code elimination | Tree-shaking неиспользуемых классов/методов |
| Function inlining | Kotlin/Java method inlining |
| Symbol stripping | Обфускация (переименование классов/методов) |
| `-O3` ruleset | `proguard-android-optimize.txt` (уже подключён) |
| `-fvisibility` | `isShrinkResources` — удаление неиспользуемых ресурсов |

AGP 8.7.3 запускает R8 в **full mode** по умолчанию (максимально агрессивные оптимизации). Никаких дополнительных флагов в `gradle.properties` не нужно.

---

## Как собирать release APK

**Android Studio → Build → Generate Signed App Bundle / APK**

### Шаг 1. Выбор формата

Выбрать **APK** (не Android App Bundle).

`.aab` (App Bundle) — только для Google Play: сам файл не устанавливается на телефон напрямую, Play Store нарезает из него APK под каждое устройство. Это приложение распространяется через прямую установку на два телефона, не через Store — `.aab` бесполезен.

### Шаг 2. Keystore (первый раз)

Нажать **"Create new..."** рядом с полем Key store path:
- Указать путь для `.jks`-файла **вне папки репозитория** (например, `D:\Keys\familytasks.jks`)
- Задать пароль keystore, alias ключа (например `familytasks`), пароль ключа
- Заполнить поля сертификата (для личного использования — любые значения), срок 25 лет по умолчанию — нормально

При последующих сборках выбирать существующий keystore через **"Choose existing..."**.

### Шаг 3. Сборка

- Build Variant: **release**
- Нажать **Finish** — APK будет в `app/release/`

### Важно про keystore

**Сохранить `.jks`-файл и пароли вне git и вне репозитория.** Потеря keystore означает невозможность подписать обновление тем же ключом — установить поверх существующей версии не получится, придётся переустанавливать через удаление (со всеми последствиями, описанными ниже).

---

## Переход с debug на release на устройстве

### Почему нельзя поставить release поверх debug

Каждый APK подписан — debug-сборки используют автоматический `debug.keystore` от Android Studio, release — ваш новый keystore. Разные подписи → Android возвращает `INSTALL_FAILED_UPDATE_INCOMPATIBLE`.

### Порядок перехода

Делать **по одному телефону за раз**:

1. **Убедиться**, что на втором телефоне приложение установлено и задачи актуальны (он будет источником данных).
2. **Удалить** debug-версию на первом телефоне.  
   ⚠️ Удаление сотрёт локальную базу задач и `deviceId` (хранится в SharedPreferences — не переживает удаление приложения).
3. **Установить** release APK на первый телефон.
4. **Пересканировать QR-код** на обоих устройствах — после удаления сгенерируется новый `deviceId`, старое сопряжение недействительно.
5. После повторного сопряжения **первый sync** восстановит все задачи с потока второго телефона — `MergeTasksUseCase` добавляет любую задачу, которой нет локально.
6. Повторить шаги 1–5 для второго телефона.

После этого одноразового перехода все **следующие** release-сборки (тот же keystore) устанавливаются поверх без удаления.

---

## Debug vs release — когда что использовать

| | Debug | Release |
|---|---|---|
| Скорость сборки | Быстро (R8 не запускается) | Медленнее (R8 full mode) |
| IDE-дебаггер и breakpoints | Да | Нет (`isDebuggable = false`) |
| Стектрейсы в Logcat | Читаемые | Обфусцированные (нужен `mapping.txt`) |
| Размер APK | ~10× больше | Оптимизированный |
| Риск R8-ломающих изменений | Нет | Есть (новый `@Serializable`-класс без keep-правил = молчаливый баг) |

**Рекомендация:**
- Повседневная разработка / правки sync-логики → **debug**.
- Перед установкой на оба телефона "для жизни" → **release**.
- После добавления нового `@Serializable`-класса или зависимости с рефлексией — собрать release и проверить sync end-to-end хотя бы один раз, прежде чем считать билд готовым.

Файл `mapping.txt` с таблицей переименований R8 создаётся при каждой release-сборке: `app/build/outputs/mapping/release/mapping.txt`. Он нужен для расшифровки обфусцированных стектрейсов из Logcat через Android Studio (Build → Analyze APK) или `retrace`.
