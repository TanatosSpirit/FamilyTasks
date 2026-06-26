# Почему SyncService использует тип specialUse

## Предыстория

Изначально `SyncService` был объявлен с `android:foregroundServiceType="connectedDevice|dataSync"`.

## Проблема с dataSync на Android 15

Обнаружено по crash-логам (`adb logcat -b crash`) 2026-06-18 на обоих устройствах.

Android 15 вводит лимит **6 часов работы в 24-часовом окне** для foreground-сервисов типа `dataSync`/`mediaProcessing` (общий бюджет на тип, не на инстанс; сбрасывается, когда пользователь открывает приложение на передний план). По истечении лимита:

- Система зовёт `Service.onTimeout()` — если сервис не остановится сам за несколько секунд, крашит всё приложение (`RemoteServiceException$ForegroundServiceDidNotStopInTimeException`).
- До конца 24-часового окна **любая** попытка повторно поднять `dataSync`-сервис (keepAlive-будильник, BootReceiver, открытие MainActivity) тоже крашит приложение (`ForegroundServiceStartNotAllowedException: Time limit already exhausted`).

У одного из двух телефонов (S23 Ultra) это повторялось часами несколько дней подряд (14, 17, 18 июня) — `SyncService` работал не 24/7, как задумано, а с многочасовыми перебоями.

## Почему specialUse

`connectedDevice` в список ограниченных типов не входит, но выбрали `specialUse` по примеру `syncthing-android` — тот же класс задачи (постоянная LAN-синхронизация между двумя устройствами), проверено годами в проде. Лимита времени у `specialUse` нет.

## Детали реализации

`ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE` как константа доступен только с API 34 (`UPSIDE_DOWN_CAKE`). В `SyncService.startForegroundCompat()` проверка версии сдвинута с `Q` на `UPSIDE_DOWN_CAKE`; на API 29–33 вызывается `startForeground()` без указания типа (так же делает `syncthing-android`).

Обязательный атрибут манифеста: `<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" .../>` с текстовым описанием use-case. Без него `startForeground()` с этим типом падает на API 34+.
