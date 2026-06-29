# Предложение: переход на edge-to-edge и устранение deprecation warning statusBarColor

## Контекст

При сборке возникает предупреждение:

```
w: Theme.kt:49:20 'var statusBarColor: Int' is deprecated. Deprecated in Java.
```

Источник — строка в `ui/theme/Theme.kt`:

```kotlin
window.statusBarColor = colorScheme.primary.toArgb()
```

`statusBarColor` устарел начиная с API 35 (Android 15). На targetSdk 35 эта строка уже **игнорируется**: система принудительно включает edge-to-edge режим. На API 26–34 (остальные поддерживаемые устройства) продолжает работать.

---

## Варианты решения

### Вариант 1 — удалить строку

Убрать `window.statusBarColor = colorScheme.primary.toArgb()` из `SideEffect`.

**Плюсы:** одна строка изменений, нет предупреждения.  
**Минусы:** на API 26–34 цвет статус-бара перестанет совпадать с primary-цветом темы.  
**Риск:** низкий, но поведение на старых устройствах меняется.

---

### Вариант 2 — @Suppress + guard по версии API

Обернуть вызов в проверку версии и явно подавить deprecation:

```kotlin
@Suppress("DEPRECATION")
if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
    window.statusBarColor = colorScheme.primary.toArgb()
}
```

**Плюсы:** поведение на API 26–34 сохраняется, намерение задокументировано явно.  
**Минусы:** `@Suppress` — обходной манёвр, не решение.  
**Риск:** минимальный.

---

### Вариант 3 — enableEdgeToEdge() (рекомендуемый Google)

Полноценный переход на современный подход к системным барам.

#### Что такое edge-to-edge

Edge-to-edge — режим, при котором контент приложения рисуется под статус-баром и навигационной панелью. Системные бары становятся прозрачными, а приложение само решает, что показывать под ними. Google принудительно включил этот режим для всех приложений с targetSdk 35.

#### Что делает enableEdgeToEdge()

`androidx.activity.enableEdgeToEdge()` (доступен с `androidx.activity:activity:1.8.0`):

- устанавливает прозрачный фон статус-бара и навигационной панели
- автоматически подбирает светлые/тёмные иконки статус-бара под тему
- заменяет ручной `statusBarColor` + `isAppearanceLightStatusBars`

#### Что нужно изменить

**`MainActivity.kt`** — вызвать до `setContent {}`:
```kotlin
enableEdgeToEdge()
setContent { FamilyTasksTheme { ... } }
```

**`Theme.kt`** — убрать весь `SideEffect` (включая `statusBarColor` и `isAppearanceLightStatusBars`). `enableEdgeToEdge()` берёт это на себя автоматически.

**Экраны** — каждый экран, использующий `Scaffold`, получает `PaddingValues` и должен передавать их в `contentPadding` или `Modifier.padding(paddingValues)`. Если `Scaffold` уже используется везде и padding применяется — ничего не поедет. Если какой-то экран игнорирует insets — контент уедет под статус-бар.

#### Файлы под проверку

Все экраны проекта:
- `TaskListScreen.kt`
- `TaskDetailScreen.kt`
- `CreateTaskScreen.kt`
- `SettingsScreen.kt`
- `LogsScreen.kt`
- `OnboardingScreen.kt`
- `QrCodeScreen.kt`

#### Плюсы

- устраняет deprecated API без обходных манёвров
- правильно работает на API 35+ (где edge-to-edge обязателен)
- убирает лишний `SideEffect` из `Theme.kt`
- соответствует текущим рекомендациям Google

#### Минусы

- затрагивает все экраны (нужна ручная проверка каждого)
- потенциальные визуальные регрессии если insets не обрабатываются

#### Оценка

~1–2 часа: изменения в `MainActivity` и `Theme.kt` (15 мин) + проверка каждого экрана на предмет перекрытия контента системными барами.

---

## Текущий статус

Варнинг оставлен без изменений, ждёт отдельной задачи. Вариант 3 предпочтителен как долгосрочное решение.
