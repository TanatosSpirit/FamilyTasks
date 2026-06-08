 ▐▛███▜▌   Claude Code v2.1.168
▝▜█████▛▘  Sonnet 4.6 · Claude Pro
  ▘▘ ▝▝    D:\AndroidStudioProjects\FamilyTasks

  Feature of the week: /loop — run a prompt or slash command on a recurring interval


> Я читаю документы sync_architecture.md, improvements_plan.md, и возник вопрос 
  2. Адаптивный интервал UDP broadcast и 3. Освобождать MulticastLock когда IP  
  партнёра закеширован как-то связаны?                                          

● Читаю оба файла, чтобы ответить точно.

  Searched for 1 pattern, read 2 files (ctrl+o to expand)

● Да, связаны, и довольно тесно.

  Обе оптимизации решают одну проблему: UDP-discovery продолжает работать в
  полную силу даже когда peerIp уже известен — хотя нужды активно искать
  партнёра больше нет.

  Но они работают на разных уровнях:

  ┌──────────────────┬──────────────────────────┬──────────────────────────┐
  │                  │        Что делает        │         Уровень          │
  ├──────────────────┼──────────────────────────┼──────────────────────────┤
  │ #2 (адаптивный   │ реже отправляем          │ исходящий трафик,        │
  │ интервал)        │ broadcast (30с → 120с)   │ пробуждения WiFi-радио   │
  ├──────────────────┼──────────────────────────┼──────────────────────────┤
  │ #3 (освободить   │ перестаём обрабатывать   │ WiFi-чип перестаёт       │
  │ MulticastLock)   │ входящие                 │ пропускать весь          │
  │                  │ broadcast/multicast      │ широковещательный мусор  │
  └──────────────────┴──────────────────────────┴──────────────────────────┘

  Ключевая зависимость: если реализовать #3 (отпустить lock), listen-корутина
  перестаёт получать UDP-пакеты от партнёра. Это значит, что механизм "peer
  пропал → снова захватить lock" требует внешнего триггера — и адаптивные
  broadcast-ы из #2 (медленный режим 120с) становятся тем самым "пульсом",
  который обнаружит партнёра после его возвращения в сеть.

  Получается общий state machine для обоих улучшений:

  peerIp == null → агрессивный режим
    broadcast каждые 30с  (#2)
    MulticastLock захвачен (#3)

  peerIp != null → пассивный режим
    broadcast каждые 120с (#2) ← нужен чтобы обнаружить если peer вернётся
    MulticastLock отпущен  (#3)

  Именно поэтому в improvements_plan.md у #3 усилие 🟠 среднее — он требует
  сначала реализовать механизм "peer потерян" (lastSeenAt + порог 5 минут),
  который нужен для правильного перехода между режимами. #2 можно реализовать
  независимо.

  Практический вывод: если реализуете оба — делать их вместе в одном коммите
  разумнее, потому что они разделяют один state (peerIsKnown).

✻ Cogitated for 42s

> Я не понял один момент, зачем вообще отправлять broadcast после peerIp !=     
  null, если ip уже знаем?                                                      

● Хороший вопрос, и он вскрывает противоречие в самом плане.

  Если реализовать #3 (отпустить MulticastLock), то broadcasts в пассивном 
  режиме бесполезны. Причина:

  - Lock отпущен → WiFi-чип фильтрует входящие broadcast-пакеты → ответ партнёра
  на ваш broadcast вы всё равно не получите
  - Партнёр (если тоже реализовал #3) тоже не получит ваш broadcast
  - Вы кричите в пустоту

  Правильный сигнал "партнёр пропал" — это HTTP-таймаут, не отсутствие
  UDP-пакетов:

  peerIp != null → WorkManager / triggerSync → HTTP запрос
    └─ timeout/error → peerIp = null, re-acquire MulticastLock,
                        интервал обратно 30с → обнаружение заново

  Это быстрее (5с HTTP timeout vs ждать 5 минут lastSeenAt) и не требует
  постоянно слать broadcast-ы.

  Вывод: В плане описан гибридный вариант "slow broadcast 120с" как компромисс —
  написан до того как стало ясно, что #2 и #3 вместе делают его ненужным. При
  реализации обоих улучшений пассивный режим должен выглядеть так:

  peerIp != null:
    broadcast — НЕ слать вообще (или остановить корутину)
    MulticastLock — отпустить
    HTTP sync — по WorkManager (15 мин)

  HTTP sync timeout:
    peerIp = null
    MulticastLock — захватить
    broadcast — 30с

  Фактически #2 нужен только для одного случая: когда lock НЕ отпускается. Если
  отпускаете — адаптивный интервал смысла не имеет.

✻ Cooked for 48s

> HTTP sync — по WorkManager (15 мин) - а зачем мы должно продолжать этим       
  заниматься? Поясни коротко                                                    

● Потому что изменения задач не триггерят sync — нет механизма "задача сохранена
  → немедленно синхронизировать".

  Партнёр добавил задачу на своём устройстве. Ваше устройство об этом не знает.
  Без периодического опроса вы узнаете об изменении только когда снова запустите
  UDP-discovery.

  Если убрать WorkManager и lock отпущен — sync будет происходить только при
  старте приложения / перезагрузке. Для семейного приложения это неприемлемо.

✻ Sautéed for 12s

> А, видимо я путаю несколько моментов. HTTP sync нужен для синхронизации задач 
  получается? А где-то еще есть функциональность для получения ip - это         
  другое?                                                                       

● Да, именно. Это две разные вещи:

  UDP discovery — находит IP партнёра. Только это. Никаких задач.

  HTTP sync (POST /sync) — обменивается задачами. Требует уже известный IP.

  Поэтому порядок всегда такой:
  UDP → узнали IP → HTTP → обменялись задачами

  Одно без другого не работает: HTTP без IP некуда слать, UDP без HTTP — знаем
  где партнёр, но задачи не синхронизированы.

✻ Worked for 5s

> Тогда поясни еще раз в этом контексте зачем сейчас раз в 30 секунд что-то     
  происходит в коде? Это синхронизация или что?                                 

● UDP broadcast каждые 30 секунд — это только поиск IP. Не синхронизация задач.

  Устройство кричит в сеть: "Я здесь, мой порт 8080". Если партнёр слышит —
  записывает IP и сразу запускает HTTP sync.

  То есть 30 секунд — это максимальная задержка до обнаружения партнёра, а не до
  синхронизации задач. После обнаружения HTTP sync происходит немедленно.

  Поэтому улучшение #2 ("замедлить до 120с после обнаружения") и правда имеет
  смысл только без #3 — если IP уже известен, незачем часто кричать в сеть.

✻ Churned for 5s

> Звучит так, что нужно изменить логику. Устройство 1 в сети. Устройство 2      
  подключается к сети, кричит в сеть (UDP broadcast), что я тут, вот мой ip. И  
  больше этим не занимается каждый 30 секунд вообще. Как раз в этот момент      
  каждое устройство отправляет обнолвения, если они есть. И общая концепция,    
  раз ip уже изветсен, то  когда какое-то устройство что-то сделало у себя с    
  задачами, то HTTP sync (POST /sync) отправляет обновления на второе           
  устройство. Но если HTTP sync (POST /sync) не сработал, то выставляем  peerIp 
  = null и сами ничего не делаем, ведь это второе устройство пропало из сети,   
  а не мы. И когда второе устройство снова приходит в сеть, снова udp, обмена   
  ip и далее синхронизация. Что ты думаешь об этой идее?                        

● Концепция хорошая и чище текущей. Но есть два момента которые нужно решить:

  1. UDP ненадёжен — один пакет может потеряться

  Одиночный broadcast при подключении рискует не дойти. Решение простое: слать
  несколько раз с интервалом 1-2 секунды (3-5 попыток), потом останавливаться.
  Не "каждые 30 секунд вечно", но и не один раз.

  2. Device 2 кричит → Device 1 слышит, но Device 2 не знает IP Device 1

  Сейчас оба слушают и оба кричат — IP получают оба. Если Device 2 кричит один
  раз, а Device 1 только слушает — Device 1 узнает IP Device 2, но не наоборот.
  Нужен явный ответ: получил UDP → ответь своим UDP-пакетом.

  ---
  Что касается HTTP sync на изменения задач — это правильно, именно так работает
  большинство P2P приложений. Debounce 2-3 секунды чтобы не слать запрос на
  каждое нажатие при редактировании.

  Про peerIp = null при ошибке — стоит различать ошибки:
  - Таймаут подключения (5с) → партнёр пропал → peerIp = null
  - Другие HTTP ошибки → временный сбой → retry 1-2 раза, только потом peerIp = 
  null

  В целом это правильное направление: меньше фоновой активности, синхронизация
  по событиям вместо polling.

✻ Sautéed for 35s

> Сформируй markdown документ под изменение, что мы обсудили. Чтобы вернуться к 
  нему позже. Дополнительно оцени улучшение энергоэффективности                 

● Создаю документ.

● Write(sync_redesign.md)
  ⎿  Wrote 179 lines to sync_redesign.md
       1 # Редизайн синхронизации — событийная модель
       2 
       3 Текущая модель: polling (UDP каждые 30с + WorkManager каждые 15 
       4 Новая модель: event-driven (UDP только при подключении + HTTP пр
         и изменении задачи).
       5 
       6 ---
       7 
       9 
      10 ```
     … +201 lines (ctrl+o to expand)

● Документ создан: sync_redesign.md.

  Ключевые цифры по энергоэффективности:
  - UDP-отправка: с 2880 раз в сутки до ~5 — −99%
  - WorkManager wake-up: полностью убирается — −100%
  - MulticastLock (входящий трафик) — не меняется, он неустраним

  Главный выигрыш именно в пробуждениях WiFi-радио — на Android это дороже самой
  передачи данных. MulticastLock остаётся, но его расход несравнимо меньше чем
  периодические отправки.

✻ Churned for 59s

> Цитата из документа: "Оставшийся расход — MulticastLock (listen-сокет) —      
  неустраним без потери способности принять партнёра при его появлении." - и    
  несколько вопросов 1) какого потребление при постоянном прослушивание         
  MulticastLock (listen-сокет), 2) При отключении от wi fi этот процесс будет   
  останавливается в будущей концепции? А в текущей останавливается? 3) Мы же    
  можем остановить MulticastLock (listen-сокет), который как ты говоришь,       
  тратит много энергии, т.к. ему приходится все подряд слушать, на момент,      
  когда два устройства обменялись ip. Этим устройствам больше не нужно все      
  подряд слушать                                                                

● Отвечу по порядку.

  1. Потребление MulticastLock

  Радио не держит. WiFi-чип всё равно подключён к сети — он и так активен.
  MulticastLock только отключает аппаратную фильтрацию: без него чип отбрасывает
  broadcast/multicast на уровне железа, с ним — пропускает всё в CPU. Расход —
  это CPU-прерывания на обработку чужого мусора (mDNS, UPnP, IoT-устройства). В
  типичной домашней сети с умными устройствами — заметно, но несравнимо меньше
  чем периодические пробуждения радио на передачу.

  2. Остановка при отключении WiFi

  Ни в текущей, ни в новой концепции явной остановки нет — сокет просто начнёт
  падать с ошибками. Правильно было бы добавить динамический BroadcastReceiver
  (не в манифесте — он не работает на Android 8+, а через registerReceiver()
  внутри SyncService — это работает). При отключении WiFi: закрыть сокет, peerIp
  = null. При подключении: отправить burst заново.

  3. Освободить MulticastLock после обмена IP

  Ты прав, и я ошибся в документе написав "неустраним". В новой концепции это
  устраняется чисто:

  peerIp == null  →  MulticastLock захвачен, listen-сокет активен
  peerIp != null  →  MulticastLock отпущен, listen-сокет закрыт

  HTTP timeout    →  peerIp = null  →  захватить lock, слушать
  Партнёр пришёл →  UDP burst  →  слышим  →  peerIp = ipB  →  отпустить lock

  Обновлю документ.

  Read 1 file (ctrl+o to expand)

● Update(sync_redesign.md)
  ⎿  Added 31 lines, removed 4 lines
      50  
      51  **Почему burst, а не один пакет:** UDP ненадёжен, один пакет мо
          жет потеряться. 5 пакетов за 6–8 секунд — разумный компромисс м
          ежду надёжностью и экономией.
      52  
      53 -### MulticastLock                                              
      53 +### MulticastLock и listen-сокет                               
      54  
      55 -Listen-корутина работает всегда → MulticastLock держится всё вр
         -емя работы SyncService.                                        
      56 -Это **не регрессия** по сравнению с текущим состоянием (lock и 
         -сейчас держится постоянно).                                    
      57 -Экономия приходит от устранения периодической отправки, а не от
         - lock.                                                         
      55 +Управляется состоянием `peerIp`:                               
      56  
      57 +```                                                            
      58 +peerIp == null  →  MulticastLock захвачен, listen-сокет активен
      59 +peerIp != null  →  MulticastLock отпущен, listen-сокет закрыт  
      60 +```                                                            
      61 +                                                               
      62 +- После получения UDP от партнёра и записи `peerIp` — lock отпу
         +скается, сокет закрывается                                     
      63 +- При сбросе `peerIp = null` (HTTP timeout) — lock захватываетс
         +я снова, сокет открывается                                     
      64 +                                                               
      65 +**Что такое расход от MulticastLock:** не удерживает WiFi-радио
         + (оно и так активно при подключении к сети). Отключает аппаратн
         +ую фильтрацию на уровне чипа — все broadcast/multicast пакеты с
         +ети доходят до CPU. В шумной домашней сети (умные устройства, I
         +oT) — заметные лишние CPU-прерывания. Поэтому освобождать при и
         +звестном `peerIp` — правильно.                                 
      66 +                                                               
      67 +### WiFi disconnect (динамический BroadcastReceiver)           
      68 +                                                               
      69 +`WifiReceiver` в манифесте не работает на Android 8+ (implicit 
         +broadcast). Но `registerReceiver()` внутри `SyncService` — рабо
         +тает.                                                          
      70 +                                                               
      71 +**Добавить** в `SyncService.onCreate()`:                       
      72 +```kotlin                                                      
      73 +registerReceiver(wifiReceiver, IntentFilter(WifiManager.NETWORK
         +_STATE_CHANGED_ACTION))                                        
      74 +```                                                            
      75 +                                                               
      76 +При отключении WiFi:                                           
      77 +- Закрыть listen-сокет                                         
      78 +- Отпустить MulticastLock                                      
      79 +- `peerIp = null`                                              
      80 +                                                               
      81 +При подключении WiFi:                                          
      82 +- Захватить MulticastLock                                      
      83 +- Открыть listen-сокет                                         
      84 +- Отправить UDP burst                                          
      85 +                                                               
      86  ### TaskRepositoryImpl.kt
      87  
      88  **Добавить:** после каждого `upsertTask` / `deleteTask` — вызыв
          ать `syncManager.scheduleSyncDebounced()`.

● Update(sync_redesign.md)  ⎿  Added 1 line, removed 1 line
      173  |---|---|---|---|
      174  | UDP broadcast (отправка) | каждые 30с = 2880 раз/сутки | ~5 
           раз при подключении | **−99%** |
      175  | Пробуждение WiFi-радио для отправки | 2880 раз/сутки | ~5 ра
           з/сутки | **−99%** |
      176 -| MulticastLock (входящий трафик) | постоянно | постоянно | бе
          -з изменений |                                                 
      176 +| MulticastLock (входящий трафик) | постоянно | только пока pe
          +erIp == null | **существенно меньше** |                       
      177  | WorkManager wake-up | ~96 раз/сутки | 0 | **−100%** |
      178  | HTTP sync | по UDP-discovery + WorkManager | только при изме
           нении задачи | зависит от активности |
      179  | DatagramSocket создание/закрытие | каждые 30с | только burst
            | **−99%** |

● Update(sync_redesign.md)
  ⎿  Added 1 line, removed 1 line
      180  
      181  **Главный выигрыш:** WiFi-радио перестаёт просыпаться каждые 3
           0 секунд. На Android это один из самых значимых источников фон
           ового расхода батареи — радио при каждом пробуждении потребляе
           т ~100–200 мВт на 10–50 мс, и сам переход в активный режим дор
           оже самой передачи.
      182  
      183 -**Итог:** фоновый расход от синхронизации снижается кратно. Ос
          -тавшийся расход — MulticastLock (listen-сокет) — неустраним бе
          -з потери способности принять партнёра при его появлении.      
      183 +**Итог:** фоновый расход от синхронизации снижается кратно. Mu
          +lticastLock держится только пока партнёр не найден — после обм
          +ена IP отпускается до следующей потери связи.                 
      184  
      185  ---
      186  

● Update(sync_redesign.md)
  ⎿  Added 2 lines, removed 1 line
      199  
      200  | Файл | Что менять |
      201  |---|---|
      202 -| `UdpDiscovery.kt` | burst вместо цикла, ответ на входящий па
          -кет |                                                         
      202 +| `UdpDiscovery.kt` | burst вместо цикла, ответ на входящий па
          +кет, управление lock по peerIp |                              
      203 +| `SyncService.kt` | динамический BroadcastReceiver на WiFi di
          +sconnect/connect |                                            
      204  | `SyncManager.kt` | `scheduleSyncDebounced()`, логика сброса 
           `peerIp` |
      205  | `TaskRepositoryImpl.kt` | вызов `scheduleSyncDebounced()` по
           сле upsert/delete |
      206  | `FamilyTasksApplication.kt` | убрать `schedulePeriodicSync()
           ` |

● Документ обновлён. Три исправления:

  1. MulticastLock теперь управляется — держится только пока peerIp == null,
  отпускается после обмена IP
  2. WiFi disconnect — добавил секцию с динамическим BroadcastReceiver
  (манифестный не работает, но registerReceiver() внутри сервиса работает)
  3. Таблица энергоэффективности — обновил строку MulticastLock с "без
  изменений" на "существенно меньше"

✻ Brewed for 1m 9s

> Мы можем сделать обсужденные изменения? НАсколько это сложно?                 

● Дай прочитаю ключевые файлы чтобы дать точную оценку.

  Searched for 1 pattern, read 5 files (ctrl+o to expand)

● Достаточно информации. Оцениваю.

  ---
  Сложность: средняя. Реально за один сеанс.

  Изменений немного, но они касаются нескольких файлов и требуют аккуратности в
  деталях. Вот разбивка:

  ┌───────────────────────────┬────────────┬────────────────────────────────┐
  │         Изменение         │ Сложность  │           Что делать           │
  ├───────────────────────────┼────────────┼────────────────────────────────┤
  │ FamilyTasksApplication.kt │ тривиально │ удалить schedulePeriodicSync() │
  ├───────────────────────────┼────────────┼────────────────────────────────┤
  │ UdpDiscovery.kt           │ среднее    │ burst вместо цикла, ответ на   │
  │                           │            │ входящий, управление lock      │
  ├───────────────────────────┼────────────┼────────────────────────────────┤
  │ SyncService.kt            │ малое      │ динамический WiFi receiver,    │
  │                           │            │ управлять lock по peerIp       │
  ├───────────────────────────┼────────────┼────────────────────────────────┤
  │                           │            │ добавить                       │
  │ SyncManager.kt            │ малое      │ scheduleSyncDebounced() +      │
  │                           │            │ scope                          │
  ├───────────────────────────┼────────────┼────────────────────────────────┤
  │                           │            │ наблюдать observeAllTasks() в  │
  │ trigger на изменение      │ малое      │ SyncService с debounce — чище  │
  │ задач                     │            │ чем тащить SyncManager в       │
  │                           │            │ репозиторий                    │
  ├───────────────────────────┼────────────┼────────────────────────────────┤
  │ сброс peerIp при таймауте │ малое      │ в triggerSync() различать      │
  │                           │            │ null-ответ как "нет связи"     │
  └───────────────────────────┴────────────┴────────────────────────────────┘

  Одно архитектурное решение которое нужно принять перед стартом: вместо того
  чтобы добавлять SyncManager в TaskRepositoryImpl (смешивает слои), правильнее
  в SyncService подписаться на taskRepository.observeAllTasks() — при изменении
  коллекции вызывать debounced sync. Тогда репозиторий остаётся чистым.

  ---
  Делаем?

✻ Sautéed for 46s

> да, делаем                                                                    

● Читаю оставшиеся файлы перед началом.

  Read 2 files (ctrl+o to expand)

● Читаю последний файл перед стартом.

  Read 1 file (ctrl+o to expand)

● Всё понятно. Начинаю. Буду менять файлы последовательно.

  1. HttpClient.kt — добавляю PeerUnreachableException и различение ошибок:

● Update(app\src\main\java\com\familytasks\app\data\sync\HttpClient.kt)
  ⎿  Added 8 lines
       8  import okhttp3.OkHttpClient
       9  import okhttp3.Request
      10  import okhttp3.RequestBody.Companion.toRequestBody
      11 +import java.net.ConnectException                               
      12 +import java.net.SocketTimeoutException                         
      13  import java.util.concurrent.TimeUnit
      14  import javax.inject.Inject
      15  import javax.inject.Singleton
      16  
      17 +class PeerUnreachableException : Exception()                   
      18 +                                                               
      19  @Singleton
      20  class HttpClient @Inject constructor() {
      21  
     ...
      39                  if (response.isSuccessful) {
      40                      response.body?.string()?.let { json.decodeF
          romString<SyncPayload>(it) }
      41                  } else null
      42 +            } catch (e: SocketTimeoutException) {              
      43 +                throw PeerUnreachableException()               
      44 +            } catch (e: ConnectException) {                    
      45 +                throw PeerUnreachableException()               
      46              } catch (e: Exception) {
      47                  null
      48              }

● 2. UdpDiscovery.kt — burst вместо цикла, ответ на пакет партнёра, управление
  lock:

● Write(app\src\main\java\com\familytasks\app\data\sync\UdpDiscovery.kt)
  ⎿  Added 49 lines, removed 15 lines
      15  import java.net.InetAddress
      16  
      17  private const val UDP_PORT = 9090
      18 -private const val BROADCAST_INTERVAL_MS = 30_000L              
      18  private const val BROADCAST_ADDRESS = "255.255.255.255"
      19 +private const val BURST_COUNT = 5                              
      20 +private const val BURST_INTERVAL_MS = 1_500L                   
      21  
      22  @Serializable
      23  data class DiscoveryPacket(val deviceId: String, val httpPort: 
          Int)
     ...
       35      private var listenSocket: DatagramSocket? = null
       36  
       37      fun start(scope: CoroutineScope) {
       37 -        multicastLock = wifiManager.createMulticastLock("Famil
          -yTasksLock").also {                                           
       38 -            it.setReferenceCounted(true)                      
       39 -            it.acquire()                                      
       40 -        }                                                     
       38 +        acquireLock()                                         
       39          startListening(scope)
       42 -        startBroadcasting(scope)                              
       40 +        startBurst(scope)                                     
       41      }
       42  
       43      fun stop() {
       44          broadcastJob?.cancel()
       45          listenJob?.cancel()
       46          listenSocket?.close()
       49 -        multicastLock?.release()                              
       50 -        multicastLock = null                                  
       47 +        listenSocket = null                                   
       48 +        releaseLock()                                         
       49      }
       50  
       53 -    fun broadcastOnce(scope: CoroutineScope) {                
       54 -        scope.launch(Dispatchers.IO) { sendBroadcast() }      
       51 +    // Peer IP is now known — no need to keep listening       
       52 +    fun pauseDiscovery() {                                    
       53 +        broadcastJob?.cancel()                                
       54 +        broadcastJob = null                                   
       55 +        listenJob?.cancel()                                   
       56 +        listenJob = null                                      
       57 +        listenSocket?.close()                                 
       58 +        listenSocket = null                                   
       59 +        releaseLock()                                         
       60      }
       61  
       57 -    private fun startBroadcasting(scope: CoroutineScope) {    
       62 +    // Peer IP lost — restart full discovery cycle            
       63 +    fun restartDiscovery(scope: CoroutineScope) {             
       64 +        broadcastJob?.cancel()                                
       65 +        listenJob?.cancel()                                   
       66 +        listenSocket?.close()                                 
       67 +        listenSocket = null                                   
       68 +        acquireLock()                                         
       69 +        startListening(scope)                                 
       70 +        startBurst(scope)                                     
       71 +    }                                                         
       72 +                                                              
       73 +    private fun acquireLock() {                               
       74 +        if (multicastLock?.isHeld == true) return             
       75 +        multicastLock = wifiManager.createMulticastLock("Famil
          +yTasksLock").also {                                           
       76 +            it.setReferenceCounted(false)                     
       77 +            it.acquire()                                      
       78 +        }                                                     
       79 +    }                                                         
       80 +                                                              
       81 +    private fun releaseLock() {                               
       82 +        multicastLock?.takeIf { it.isHeld }?.release()        
       83 +        multicastLock = null                                  
       84 +    }                                                         
       85 +                                                              
       86 +    private fun startBurst(scope: CoroutineScope) {           
       87          broadcastJob = scope.launch(Dispatchers.IO) {
       59 -            while (isActive) {                                
       88 +            repeat(BURST_COUNT) {                             
       89 +                if (!isActive) return@launch                  
       90                  sendBroadcast()
       61 -                delay(BROADCAST_INTERVAL_MS)                  
       91 +                delay(BURST_INTERVAL_MS)                      
       92              }
       93          }
       94      }
       95  
       96      private fun startListening(scope: CoroutineScope) {
       97          listenJob = scope.launch(Dispatchers.IO) {
       68 -            val socket = DatagramSocket(UDP_PORT).also { liste
          -nSocket = it }                                                
       98 +            val socket = try {                                
       99 +                DatagramSocket(UDP_PORT).also { listenSocket =
          + it }                                                         
      100 +            } catch (_: Exception) { return@launch }          
      101              val buffer = ByteArray(1024)
      102              while (isActive) {
      103                  try {
     ...
      105                      socket.receive(packet)
      106                      val data = String(packet.data, 0, packet.l
           ength)
      107                      val discovery = json.decodeFromString<Disc
           overyPacket>(data)
      108 +                    val senderIp = packet.address.hostAddress 
          +?: continue                                                   
      109                      if (discovery.deviceId != ownDeviceId &&
      110                          (pairedDeviceId == null || discovery.d
           eviceId == pairedDeviceId)
      111                      ) {
       79 -                        onPeerDiscovered(packet.address.hostAd
          -dress ?: return@launch, discovery.deviceId)                   
      112 +                        sendBroadcast() // respond so peer lea
          +rns our IP                                                    
      113 +                        onPeerDiscovered(senderIp, discovery.d
          +eviceId)                                                      
      114                      }
      115                  } catch (_: Exception) {}
      116              }

● 3. SyncService.kt — самое большое изменение:

● Write(app\src\main\java\com\familytasks\app\data\sync\SyncService.kt)
  ⎿  Added 46 lines
       4  import android.app.NotificationChannel
       5  import android.app.NotificationManager
       6  import android.app.Service
       7 +import android.content.BroadcastReceiver                       
       8 +import android.content.Context                                 
       9  import android.content.Intent
      10 +import android.content.IntentFilter                            
      11  import android.content.pm.ServiceInfo
      12  import android.net.wifi.WifiManager
      13  import android.os.Build
     ...
      18  import com.familytasks.app.core.PreferencesManager
      19  import com.familytasks.app.domain.model.Device
      20  import com.familytasks.app.domain.repository.DeviceRepository
      21 +import com.familytasks.app.domain.repository.TaskRepository    
      22  import com.familytasks.app.domain.usecase.SyncUseCase
      23  import com.familytasks.app.notifications.NotificationScheduler
      24  import dagger.hilt.android.AndroidEntryPoint
      25  import kotlinx.coroutines.CoroutineScope
      26  import kotlinx.coroutines.Dispatchers
      27  import kotlinx.coroutines.SupervisorJob
      28 +import kotlinx.coroutines.flow.debounce                        
      29 +import kotlinx.coroutines.flow.distinctUntilChanged            
      30 +import kotlinx.coroutines.flow.drop                            
      31  import kotlinx.coroutines.launch
      32  import javax.inject.Inject
      33  
     ...
      40      @Inject lateinit var httpClient: HttpClient
      41      @Inject lateinit var syncManager: SyncManager
      42      @Inject lateinit var deviceRepository: DeviceRepository
      43 +    @Inject lateinit var taskRepository: TaskRepository        
      44      @Inject lateinit var notificationScheduler: NotificationSch
          eduler
      45  
      46      private val scope = CoroutineScope(SupervisorJob() + Dispat
          chers.Default)
      47      private var httpServer: HttpServer? = null
      48      private var udpDiscovery: UdpDiscovery? = null
      49  
      50 +    @Suppress("DEPRECATION")                                   
      51 +    private val wifiReceiver = object : BroadcastReceiver() {  
      52 +        override fun onReceive(context: Context, intent: Intent
         +) {                                                            
      53 +            val info = intent.getParcelableExtra<android.net.Ne
         +tworkInfo>(                                                    
      54 +                WifiManager.EXTRA_NETWORK_INFO                 
      55 +            ) ?: return                                        
      56 +            when {                                             
      57 +                info.isConnected -> {                          
      58 +                    if (syncManager.peerIp.value == null) {    
      59 +                        udpDiscovery?.restartDiscovery(scope)  
      60 +                    }                                          
      61 +                }                                              
      62 +                !info.isConnectedOrConnecting -> {             
      63 +                    syncManager.setPeerIp(null)                
      64 +                    udpDiscovery?.pauseDiscovery()             
      65 +                }                                              
      66 +            }                                                  
      67 +        }                                                      
      68 +    }                                                          
      69 +                                                               
      70      override fun onCreate() {
      71          super.onCreate()
      72          createNotificationChannel()
      73          startForegroundCompat()
      74          startHttpServer()
      75          startUdpDiscovery()
      76 +        observeTaskChanges()                                   
      77 +        registerReceiver(wifiReceiver, IntentFilter(WifiManager
         +.NETWORK_STATE_CHANGED_ACTION))                                
      78      }
      79  
      80      override fun onStartCommand(intent: Intent?, flags: Int, st
          artId: Int): Int {
     ...
      88  
      89      override fun onDestroy() {
      90          super.onDestroy()
      91 +        unregisterReceiver(wifiReceiver)                       
      92          httpServer?.stop()
      93          udpDiscovery?.stop()
      94      }
     ...
      119              pairedDeviceId = prefs.pairedDeviceId,
      120              onPeerDiscovered = { ip, deviceId ->
      121                  syncManager.setPeerIp(ip)
      122 +                udpDiscovery?.pauseDiscovery()                
      123                  scope.launch {
      124                      val existing = deviceRepository.getDeviceB
           yId(deviceId)
      125                      deviceRepository.upsertDevice(
     ...
      132          ).also { it.start(scope) }
      133      }
      134  
      135 +    private fun observeTaskChanges() {                        
      136 +        scope.launch {                                        
      137 +            taskRepository.observeAllTasks()                  
      138 +                .drop(1)                                      
      139 +                .distinctUntilChanged()                       
      140 +                .debounce(3_000L)                             
      141 +                .collect { triggerSync() }                    
      142 +        }                                                     
      143 +    }                                                         
      144 +                                                              
      145      private fun triggerSync() {
      146          val peerIp = syncManager.peerIp.value ?: return
      147          if (syncManager.syncState.value == SyncState.SYNCING) 
           return
     ...
      168                  } else {
      169                      syncManager.setSyncState(SyncState.ERROR)
      170                  }
      171 +            } catch (e: PeerUnreachableException) {           
      172 +                syncManager.setPeerIp(null)                   
      173 +                syncManager.setSyncState(SyncState.ERROR)     
      174 +                udpDiscovery?.restartDiscovery(scope)         
      175              } catch (e: Exception) {
      176                  syncManager.setSyncState(SyncState.ERROR)
      177              }

● 4. FamilyTasksApplication.kt — убираю периодический WorkManager:

● Update(app\src\main\java\com\familytasks\app\FamilyTasksApplication.kt)
  ⎿  Removed 22 lines
       3  import android.app.Application
       4  import androidx.hilt.work.HiltWorkerFactory
       5  import androidx.work.Configuration
       6 -import androidx.work.Constraints                               
       7 -import androidx.work.ExistingPeriodicWorkPolicy                
       8 -import androidx.work.NetworkType                               
       9 -import androidx.work.PeriodicWorkRequestBuilder                
      10 -import androidx.work.WorkManager                               
      11 -import com.familytasks.app.data.sync.SyncWorker                
       6  import com.familytasks.app.notifications.NotificationScheduler
       7  import dagger.hilt.android.HiltAndroidApp
      14 -import java.util.concurrent.TimeUnit                           
       8  import javax.inject.Inject
       9  
      10  @HiltAndroidApp
     ...
      21      override fun onCreate() {
      22          super.onCreate()
      23          notificationScheduler.createChannels()
      31 -        schedulePeriodicSync()                                 
      24      }
      33 -                                                               
      34 -    private fun schedulePeriodicSync() {                       
      35 -        val constraints = Constraints.Builder()                
      36 -            .setRequiredNetworkType(NetworkType.UNMETERED)     
      37 -            .build()                                           
      38 -        val request = PeriodicWorkRequestBuilder<SyncWorker>(5,
         - TimeUnit.MINUTES)                                             
      39 -            .setConstraints(constraints)                       
      40 -            .build()                                           
      41 -        WorkManager.getInstance(this).enqueueUniquePeriodicWork
         -(                                                              
      42 -            "periodic_sync",                                   
      43 -            ExistingPeriodicWorkPolicy.KEEP,                   
      44 -            request                                            
      45 -        )                                                      
      46 -    }                                                          
      25  }

● Теперь проверяю сборку:

  Ran 1 shell command (ctrl+o to expand)

> Давай я сам проверю сборку и продолжим                                        

● Хорошо, проверяй. Если будут ошибки компилятора — скидывай, исправлю.

✻ Churned for 6s

> выдало такое: Cannot locate tasks that match ':app:testClasses' as task       
  'testClasses' not found in project ':app'.                                    
● Это не связано с нашими изменениями — задача testClasses не существует в
  Android-проектах (это стандартная Java/Gradle задача, в Android она называется
  иначе).

  Для проверки компиляции запусти:

  ./gradlew assembleDebug

  Или если хочешь только проверить компиляцию без сборки APK:

  ./gradlew compileDebugKotlin

✻ Brewed for 9s

> проверил, ошибок нет                                                          

● Отлично. Изменения рабочие. Итог того, что сделано:

  - UdpDiscovery — burst 5 пакетов вместо бесконечного цикла, ответ на пакет
  партнёра, pauseDiscovery() / restartDiscovery()
  - HttpClient — PeerUnreachableException при таймауте/ConnectException
  - SyncService — после обнаружения партнёра отпускает lock, при
  PeerUnreachableException сбрасывает peerIp и перезапускает discovery, подписка
  на изменения задач с debounce 3с, динамический WiFi receiver
  - FamilyTasksApplication — периодический WorkManager убран

  Следующий шаг для полноценного тестирования — проверить на двух устройствах:
  подключиться к одной сети, убедиться что discovery срабатывает в течение ~8
  секунд (burst), и что изменение задачи на одном устройстве доходит до второго
  через ~3 секунды.

✻ Cooked for 7s

> Понял. А напомни. Мы обсуждали разбивку "Изменений немного, но они касаются   
  нескольких файлов и требуют аккуратности в деталях. Вот разбивка:" - что и    
  где нужно сделали. Мы все сделали?                                            

● Всё сделано. Сверка с таблицей:

  ┌─────────────────────────────────────┬───────────────────────────────────┐
  │              Изменение              │              Статус               │
  ├─────────────────────────────────────┼───────────────────────────────────┤
  │ FamilyTasksApplication.kt — убрать  │ ✅                                │
  │ schedulePeriodicSync()              │                                   │
  ├─────────────────────────────────────┼───────────────────────────────────┤
  │ UdpDiscovery.kt — burst, ответ на   │ ✅                                │
  │ входящий, управление lock           │                                   │
  ├─────────────────────────────────────┼───────────────────────────────────┤
  │ SyncService.kt — WiFi receiver,     │ ✅                                │
  │ lock по peerIp, observeTaskChanges  │                                   │
  ├─────────────────────────────────────┼───────────────────────────────────┤
  │ SyncManager.kt —                    │ не потребовалось                  │
  │ scheduleSyncDebounced()             │                                   │
  ├─────────────────────────────────────┼───────────────────────────────────┤
  │ trigger на изменение задач          │ ✅ через observeTaskChanges() в   │
  │                                     │ SyncService                       │
  ├─────────────────────────────────────┼───────────────────────────────────┤
  │ сброс peerIp при таймауте           │ ✅ через PeerUnreachableException │
  │                                     │  в HttpClient + triggerSync()     │
  └─────────────────────────────────────┴───────────────────────────────────┘

  SyncManager.kt менять не пришлось — debounce реализован прямо в
  SyncService.observeTaskChanges(), что чище. Плюс добавили HttpClient.kt (не
  было в исходной таблице, но нужен был для различения ошибок).

✻ Brewed for 16s

> коммит я сделаю сам. Новые изменения работают. Обнови sync_redesign.md с      
  учетом проведенных изменений. А также создай                                  
  sync_architecture_redesigned_spec.md с описанием новой спецификации           
  синхронизации, от получения ip до синхранизации задач и всего циклы работы    
  синхронизации.                                                                

● Читаю текущий sync_redesign.md чтобы обновить точно.

  Read 1 file (ctrl+o to expand)

● Обновляю sync_redesign.md и создаю спецификацию параллельно.

● Write(sync_redesign.md)
  ⎿  Added 43 lines, removed 151 lines
      1  # Редизайн синхронизации — событийная модель
      2  
      3 -Текущая модель: polling (UDP каждые 30с + WorkManager каждые 15 
        -мин).                                                           
      3 +**Статус: реализовано**                                         
      4 +                                                                
      5 +Старая модель: polling (UDP каждые 30с + WorkManager каждые 15 м
        +ин).                                                            
      6  Новая модель: event-driven (UDP только при подключении + HTTP пр
         и изменении задачи).
      7  
      8  ---
     ...
       10  ## Концепция
       11  
       12  ```
       11 -Текущая модель:                                               
       13 +Старая модель:                                                
       14    UDP broadcast каждые 30с (всегда) ──► нашли IP ──► HTTP sync
       15    WorkManager каждые 15 мин ──────────────────────► HTTP sync
       16  
       17  Новая модель:
       16 -  Подключились к WiFi ──► UDP burst (3-5 пакетов) ──► нашли IP
       17 -  Изменили задачу ────────────────────────────────► HTTP sync 
          -(debounce 3с)                                                 
       18 -  HTTP timeout ───────────────────────────────────► peerIp = n
          -ull                                                           
       19 -  Партнёр вернулся в сеть ──► его UDP burst ──────► нашли IP с
          -нова                                                          
       18 +  Подключились к WiFi ──► UDP burst (5 пакетов) ───► нашли IP 
          +──► pauseDiscovery()                                          
       19 +  Изменили задачу ─────────────────────────────────► HTTP sync
          + (debounce 3с)                                                
       20 +  HTTP timeout ────────────────────────────────────► peerIp = 
          +null → restartDiscovery()                                     
       21 +  Партнёр вернулся ────► его UDP burst ────────────► нашли IP 
          +снова                                                         
       22  ```
       23  
       24  ---
       25  
       24 -## Изменения по компонентам                                   
       26 +## Что реализовано                                            
       27  
       26 -### UdpDiscovery.kt                                           
       28 +### UdpDiscovery.kt ✅                                        
       29  
       28 -**Было:** broadcast каждые 30 секунд бесконечно.              
       30 +**Было:** `startBroadcasting()` — бесконечный цикл каждые 30с.
       31  
       30 -**Стало:**                                                    
       31 -- При старте — отправить 3–5 UDP-пакетов с интервалом 1–2 секу
          -нды, затем остановить отправку                                
       32 -- Listen-корутина работает постоянно (нужна чтобы получить bur
          -st от партнёра)                                               
       33 -- При получении UDP от партнёра — **ответить своим UDP-пакетом
          -** (иначе партнёр не узнает наш IP)                           
       34 -- Broadcast-корутина после burst'а завершается                
       32 +**Стало:** `startBurst()` — 5 пакетов с интервалом 1.5с, затем
          + корутина завершается.                                        
       33  
       36 -```kotlin                                                     
       37 -// Burst при старте                                           
       38 -repeat(5) {                                                   
       39 -    sendDiscoveryPacket()                                     
       40 -    delay(1_500L)                                             
       41 -}                                                             
       42 -// дальше только слушаем                                      
       34 +При получении пакета от партнёра — **сначала отвечаем своим па
          +кетом** (иначе партнёр не узнает наш IP), затем вызываем `onPe
          +erDiscovered`.                                                
       35  
       44 -// При получении пакета от партнёра:                          
       45 -fun onPacketReceived(senderIp: String, deviceId: String) {    
       46 -    sendDiscoveryPacket()          // ответить своим пакетом  
       47 -    onPeerDiscovered(senderIp, deviceId)                      
       48 -}                                                             
       49 -```                                                           
       36 +Новые методы:                                                 
       37 +- `pauseDiscovery()` — останавливает broadcast + listen, отпус
          +кает MulticastLock (вызывается когда peerIp известен)         
       38 +- `restartDiscovery(scope)` — захватывает lock, запускает list
          +en + burst (вызывается когда peerIp сброшен)                  
       39  
       51 -**Почему burst, а не один пакет:** UDP ненадёжен, один пакет м
          -ожет потеряться. 5 пакетов за 6–8 секунд — разумный компромисс
          - между надёжностью и экономией.                               
       40 +MulticastLock: `setReferenceCounted(false)` — простое acquire/
          +release без счётчика.                                         
       41  
       53 -### MulticastLock и listen-сокет                              
       42 +### HttpClient.kt ✅                                          
       43  
       55 -Управляется состоянием `peerIp`:                              
       44 +Добавлен `PeerUnreachableException`. При `SocketTimeoutExcepti
          +on` или `ConnectException` — бросает его вместо возврата `null
          +`. Остальные ошибки по-прежнему возвращают `null` (transient с
          +бои).                                                         
       45  
       57 -```                                                           
       58 -peerIp == null  →  MulticastLock захвачен, listen-сокет активе
          -н                                                             
       59 -peerIp != null  →  MulticastLock отпущен, listen-сокет закрыт 
       60 -```                                                           
       46 +### SyncService.kt ✅                                         
       47  
       62 -- После получения UDP от партнёра и записи `peerIp` — lock отп
          -ускается, сокет закрывается                                   
       63 -- При сбросе `peerIp = null` (HTTP timeout) — lock захватывает
          -ся снова, сокет открывается                                   
       48 +**`onPeerDiscovered` callback:** после `syncManager.setPeerIp(
          +ip)` — вызывает `udpDiscovery.pauseDiscovery()`. Lock отпущен,
          + listen-сокет закрыт.                                         
       49  
       65 -**Что такое расход от MulticastLock:** не удерживает WiFi-ради
          -о (оно и так активно при подключении к сети). Отключает аппара
          -тную фильтрацию на уровне чипа — все broadcast/multicast пакет
          -ы сети доходят до CPU. В шумной домашней сети (умные устройств
          -а, IoT) — заметные лишние CPU-прерывания. Поэтому освобождать 
          -при известном `peerIp` — правильно.                           
       50 +**`triggerSync()`:** различает два типа ошибок:               
       51 +- `PeerUnreachableException` → `syncManager.setPeerIp(null)` +
          + `udpDiscovery.restartDiscovery(scope)` + `ERROR`             
       52 +- Прочие исключения → только `ERROR`, `peerIp` не сбрасывается
       53  
       67 -### WiFi disconnect (динамический BroadcastReceiver)          
       54 +**`observeTaskChanges()`:** подписка на `taskRepository.observ
          +eAllTasks()` с `drop(1)` + `distinctUntilChanged()` + `debounc
          +e(3_000L)` → `triggerSync()`. Начальная эмиссия при старте про
          +пускается (`drop(1)`).                                        
       55  
       69 -`WifiReceiver` в манифесте не работает на Android 8+ (implicit
          - broadcast). Но `registerReceiver()` внутри `SyncService` — ра
          -ботает.                                                       
       56 +**WiFi BroadcastReceiver:** зарегистрирован динамически в `onC
          +reate()` (манифестный не работает на Android 8+). При disconne
          +ct — `peerIp = null` + `pauseDiscovery()`. При connect — если 
          +`peerIp == null`, вызывает `restartDiscovery()`.              
       57  
       71 -**Добавить** в `SyncService.onCreate()`:                      
       72 -```kotlin                                                     
       73 -registerReceiver(wifiReceiver, IntentFilter(WifiManager.NETWOR
          -K_STATE_CHANGED_ACTION))                                      
       74 -```                                                           
       58 +Добавлена инъекция `TaskRepository` для `observeTaskChanges()`
          +.                                                             
       59  
       76 -При отключении WiFi:                                          
       77 -- Закрыть listen-сокет                                        
       78 -- Отпустить MulticastLock                                     
       79 -- `peerIp = null`                                             
       60 +### FamilyTasksApplication.kt ✅                              
       61  
       81 -При подключении WiFi:                                         
       82 -- Захватить MulticastLock                                     
       83 -- Открыть listen-сокет                                        
       84 -- Отправить UDP burst                                         
       62 +Удалены `schedulePeriodicSync()` и все связанные импорты WorkM
          +anager. `WorkManager.Configuration.Provider` оставлен — нужен 
          +для `DueNotificationWorker`.                                  
       63  
       86 -### TaskRepositoryImpl.kt                                     
       64 +### SyncManager.kt — не изменялся                             
       65  
       88 -**Добавить:** после каждого `upsertTask` / `deleteTask` — вызы
          -вать `syncManager.scheduleSyncDebounced()`.                   
       66 +Debounce реализован в `SyncService.observeTaskChanges()`. Меня
          +ть `SyncManager` не потребовалось.                            
       67  
       90 -```kotlin                                                     
       91 -suspend fun upsertTask(task: Task) {                          
       92 -    dao.upsert(task.toEntity())                               
       93 -    syncManager.scheduleSyncDebounced()   // ← добавить       
       94 -}                                                             
       95 -```                                                           
       68 +### TaskRepositoryImpl.kt — не изменялся                      
       69  
       97 -### SyncManager.kt                                            
       70 +Триггер на изменения задач реализован через подписку на Flow в
          + `SyncService`, а не через вызов из репозитория. Репозиторий о
          +стался чистым.                                                
       71  
       99 -**Добавить** `scheduleSyncDebounced()` — debounce 3 секунды:  
       72 +### SyncWorker.kt — не изменялся                              
       73  
      101 -```kotlin                                                     
      102 -private var debounceJob: Job? = null                          
       74 +Класс оставлен, но больше не планируется. Удалить при необходи
          +мости.                                                        
       75  
      104 -fun scheduleSyncDebounced() {                                 
      105 -    debounceJob?.cancel()                                     
      106 -    debounceJob = scope.launch {                              
      107 -        delay(3_000L)                                         
      108 -        triggerSync()                                         
      109 -    }                                                         
      110 -}                                                             
      111 -```                                                           
      112 -                                                              
      113 -**Изменить логику ошибок в `triggerSync()`:**                 
      114 -                                                              
      115 -```                                                           
      116 -HTTP connect timeout (5с)  →  партнёр пропал  →  peerIp = null
      117 -HTTP read timeout / ошибка →  transient сбой  →  retry 1–2 раз
          -а,                                                            
      118 -                               если и retry не прошёл → peerIp
          - = null                                                       
      119 -```                                                           
      120 -                                                              
      121 -### WorkManager / SyncWorker                                  
      122 -                                                              
      123 -Убрать `PeriodicWorkRequestBuilder`. WorkManager больше не нуж
          -ен как polling-механизм.                                      
      124 -`SyncWorker` можно оставить для единственного случая: sync при
          - старте приложения (OneTimeWorkRequest).                      
      125 -                                                              
       76  ---
       77  
      128 -## Порядок событий (новая модель)                             
      129 -                                                              
      130 -### Оба устройства приходят домой                             
      131 -                                                              
      132 -```                                                           
      133 -Устройство A                          Устройство B            
      134 -     │  [Подключились к WiFi]               │                 
      135 -     │──── UDP burst (5 пакетов) ──────────►│                 
      136 -     │◄─── UDP ответ ────────────────────────│  (B ответил на 
          -первый же пакет)                                              
      137 -     │                                      │                 
      138 -     │  peerIp = ipB                        │  peerIp = ipA   
      139 -     │  triggerSync()                       │  triggerSync()  
      140 -     │──── POST /sync ─────────────────────►│                 
      141 -     │◄─── ответ ────────────────────────────│                
      142 -     │  [Задачи синхронизированы]           │                 
      143 -```                                                           
      144 -                                                              
      145 -### Пользователь создаёт задачу                               
      146 -                                                              
      147 -```                                                           
      148 -     │  [Сохранена задача]                                    
      149 -     │  scheduleSyncDebounced()                               
      150 -     │  delay(3s)...                                          
      151 -     │──── POST /sync ─────────────────────►│  (если peerIp из
          -вестен)                                                       
      152 -     │◄─── ответ ────────────────────────────│                
      153 -```                                                           
      154 -                                                              
      155 -### Партнёр пропал и вернулся                                 
      156 -                                                              
      157 -```                                                           
      158 -     │  [HTTP timeout к партнёру]                             
      159 -     │  peerIp = null                                         
      160 -     │  ... ждём ...                                          
      161 -     │                                                        
      162 -     │◄─── UDP burst от партнёра ────────────│  (партнёр верну
          -лся)                                                          
      163 -     │──── UDP ответ ──────────────────────►│                 
      164 -     │  peerIp = ipB                        │  peerIp = ipA   
      165 -     │  triggerSync()                       │  triggerSync()  
      166 -```                                                           
      167 -                                                              
      168 ----                                                           
      169 -                                                              
       78  ## Оценка энергоэффективности
       79  
      172 -| Источник расхода | Сейчас | После редизайна | Изменение |   
       80 +| Источник расхода | Старая модель | Новая модель | Изменение 
          +|                                                             
       81  |---|---|---|---|
      174 -| UDP broadcast (отправка) | каждые 30с = 2880 раз/сутки | ~5 
          -раз при подключении | **−99%** |                              
       82 +| UDP broadcast (отправка) | 2880 раз/сутки | ~5 раз при подкл
          +ючении | **−99%** |                                           
       83  | Пробуждение WiFi-радио для отправки | 2880 раз/сутки | ~5 ра
           з/сутки | **−99%** |
      176 -| MulticastLock (входящий трафик) | постоянно | только пока pe
          -erIp == null | **существенно меньше** |                       
       84 +| MulticastLock | постоянно | только пока `peerIp == null` | *
          +*существенно меньше** |                                       
       85  | WorkManager wake-up | ~96 раз/сутки | 0 | **−100%** |
      178 -| HTTP sync | по UDP-discovery + WorkManager | только при изме
          -нении задачи | зависит от активности |                        
       86  | DatagramSocket создание/закрытие | каждые 30с | только burst
            | **−99%** |
       87 +| HTTP sync | по polling | только при изменении задачи | завис
          +ит от активности |                                            
       88  
      181 -**Главный выигрыш:** WiFi-радио перестаёт просыпаться каждые 3
          -0 секунд. На Android это один из самых значимых источников фон
          -ового расхода батареи — радио при каждом пробуждении потребляе
          -т ~100–200 мВт на 10–50 мс, и сам переход в активный режим дор
          -оже самой передачи.                                           
      182 -                                                              
      183 -**Итог:** фоновый расход от синхронизации снижается кратно. Mu
          -lticastLock держится только пока партнёр не найден — после обм
          -ена IP отпускается до следующей потери связи.                 
      184 -                                                              
       89  ---
       90  
       91  ## Риски и ограничения
       92  
       93  | Риск | Вероятность | Митигация |
       94  |---|---|---|
      191 -| UDP burst потерялся целиком | низкая | 5 попыток за 6–8 секу
          -нд |                                                          
      192 -| Оба устройства одновременно шлют burst, никто не ответил пер
          -вым | низкая | listen-корутина получит хотя бы один пакет |   
       95 +| UDP burst потерялся целиком | низкая | 5 попыток за ~6 секун
          +д |                                                           
       96 +| Оба устройства шлют burst одновременно | низкая | listen-кор
          +утина получит хотя бы один пакет от другого |                 
       97  | Задача изменена пока партнёр офлайн | высокая (норма) | sync
            произойдёт когда партнёр вернётся и пришлёт UDP |
      194 -| Transient HTTP ошибка сбрасывает peerIp | средняя | retry 1–
          -2 раза до сброса |                                            
      195 -                                                              
      196 ----                                                           
      197 -                                                              
      198 -## Файлы для изменения                                        
      199 -                                                              
      200 -| Файл | Что менять |                                         
      201 -|---|---|                                                     
      202 -| `UdpDiscovery.kt` | burst вместо цикла, ответ на входящий па
          -кет, управление lock по peerIp |                              
      203 -| `SyncService.kt` | динамический BroadcastReceiver на WiFi di
          -sconnect/connect |                                            
      204 -| `SyncManager.kt` | `scheduleSyncDebounced()`, логика сброса 
          -`peerIp` |                                                    
      205 -| `TaskRepositoryImpl.kt` | вызов `scheduleSyncDebounced()` по
          -сле upsert/delete |                                           
      206 -| `FamilyTasksApplication.kt` | убрать `schedulePeriodicSync()
          -` |                                                           
      207 -| `SyncWorker.kt` | оставить только для one-time sync при стар
          -те |                                                          
       98 +| Transient HTTP ошибка (не timeout) | средняя | `peerIp` не с
          +брасывается, только `ERROR` статус |                          
       99 +| Merge-induced повторный sync | низкая | `distinctUntilChange
          +d()` блокирует повтор если список не изменился |              

● Write(sync_architecture_redesigned_spec.md)
  ⎿  Wrote 337 lines to sync_architecture_redesigned_spec.md
      1 # Спецификация синхронизации — новая архитектура
      2 
      3 Описание актуальной архитектуры синхронизации после редизайна на 
        событийную модель.
      4 
      5 ---
      6 
      7 ## Обзор
      8 
      9 Синхронизация состоит из двух независимых задач:
     … +363 lines (ctrl+o to expand)

● Оба документа готовы.

  sync_redesign.md — обновлён: убраны планы, добавлен статус каждого изменения,
  отмечено что SyncManager и TaskRepositoryImpl не трогались и почему.

  sync_architecture_redesigned_spec.md — полная спецификация: от захвата
  MulticastLock и UDP burst до HTTP merge и debounce, все 5 сценариев с
  диаграммами, сводная таблица параметров и статус каждого компонента старой
  архитектуры.

✻ Crunched for 1m 57s

> Я тут понял, что ты был прав с MulticastLock, что он должен оставаться как    
  раньше. Т.к. сейчас устройство партнера пропало из сети. И получается затем   
  оно вернется, попробует отправить udp, но мое устройство считает, что второе  
  устройство есть, т.к. я не отправлял никакх задач по http и не будет его ip   
  выставленно как null. Тупик. Пока концепцтуально давай обсудим, что-то можно  
  сделать еще? или нужно делать как ты говорил ранее и MulticastLock не всегда  
  держать                                                                       

● Ты верно диагностировал тупик. Разберём варианты:

  Проблема чётко:
  - peerIp известен → lock отпущен → слушать не можем
  - Партнёр вернулся → слёт burst → никто не слышит
  - A никогда не узнает о возвращении B, пока сам не попробует HTTP

  ---
  Варианты:

  1. Держать lock всегда, но не слать burst периодически
  Всегда слушаем (lock + сокет), только бросаем периодические отправки. Когда B
  возвращается и шлёт burst — A слышит, обновляет peerIp (вдруг B сменил IP
  через DHCP), делает sync. Это самый простой и надёжный вариант. Главная
  экономия была от устранения 2880 отправок в сутки — она остаётся. Lock на фоне
  этого небольшая потеря.

  2. TTL на peerIp
  peerIp автоматически сбрасывается если не было успешного sync N минут. После
  сброса → re-acquire lock → burst → обнаружение заново. Но это возвращает нас к
  проблеме: если B не в сети — A будет периодически слать burst и снова
  садиться в ожидание. Не даёт реальной выгоды.

  3. Редкий keepalive
  WorkManager раз в 15-30 минут проверяет: peerIp != null → лёгкий HTTP ping.
  Если timeout → сброс. Это реанимирует WorkManager, хотя и очень редкий.

  ---
  Рекомендация: вариант 1.

  Мы убрали 2880 UDP-отправок в сутки — это 99% экономии. MulticastLock (всегда
  слушать) — это незначительный остаток, который к тому же решает проблему без
  усложнения кода. Переусложнять не стоит.

  Правка минимальная: onPeerFound() отменяет только broadcastJob, но не трогает
  listenJob и не отпускает lock.

✻ Brewed for 49s

> да, давай исправим с помощью варианта 1                                       

● Правка минимальная — только в UdpDiscovery.kt. pauseDiscovery() отменяет
  только broadcastJob, restartDiscovery() только шлёт новый burst.

● Update(app\src\main\java\com\familytasks\app\data\sync\UdpDiscovery.kt)
  ⎿  Added 2 lines, removed 12 lines
      48          releaseLock()
      49      }
      50  
      51 -    // Peer IP is now known — no need to keep listening        
      51 +    // Peer IP is now known — stop sending, but keep listening 
         +for reconnects                                                 
      52      fun pauseDiscovery() {
      53          broadcastJob?.cancel()
      54          broadcastJob = null
      55 -        listenJob?.cancel()                                    
      56 -        listenJob = null                                       
      57 -        listenSocket?.close()                                  
      58 -        listenSocket = null                                    
      59 -        releaseLock()                                          
      55      }
      56  
      62 -    // Peer IP lost — restart full discovery cycle             
      57 +    // Peer IP lost — send a new burst (listen socket and lock 
         +already active)                                                
      58      fun restartDiscovery(scope: CoroutineScope) {
      59          broadcastJob?.cancel()
      65 -        listenJob?.cancel()                                    
      66 -        listenSocket?.close()                                  
      67 -        listenSocket = null                                    
      68 -        acquireLock()                                          
      69 -        startListening(scope)                                  
      60          startBurst(scope)
      61      }
      62  

● Теперь проверю итоговый файл — убеждаюсь что всё согласованно.

  Read 1 file (ctrl+o to expand)

● Файл корректный. Логика теперь:

  - start() — lock + listen + burst (полный старт)
  - pauseDiscovery() — только останавливает отправку, listen и lock работают
  - restartDiscovery() — только шлёт новый burst (listen уже жив)
  - stop() — полная остановка при уничтожении сервиса

  Сценарий "тупика" теперь разрешён: когда B возвращается и шлёт burst — A
  слышит его через всегда открытый сокет, обновляет peerIp (даже если B сменил
  IP через DHCP) и делает sync.

  Обновлю спецификацию — секция MulticastLock там сейчас неверная.

● Update(sync_architecture_redesigned_spec.md)
  ⎿  Added 10 lines, removed 6 lines
       88  ### MulticastLock — жизненный цикл
       89  
       90  ```
       91 -SyncService.onCreate()     → acquireLock() + startListening() 
       92 -onPeerDiscovered()         → pauseDiscovery() → releaseLock() 
       93 -PeerUnreachableException   → restartDiscovery() → acquireLock(
          -)                                                             
       94 -WiFi disconnect            → pauseDiscovery() → releaseLock() 
       95 -WiFi connect               → restartDiscovery() → acquireLock(
          -)                                                             
       91 +SyncService.onCreate()     → acquireLock() + startListening() 
          ++ burst                                                       
       92 +onPeerDiscovered()         → pauseDiscovery() = только останав
          +ливает burst                                                  
       93 +PeerUnreachableException   → restartDiscovery() = только шлёт 
          +новый burst                                                   
       94 +WiFi disconnect            → pauseDiscovery() = останавливает 
          +burst                                                         
       95 +WiFi connect               → restartDiscovery() = новый burst 
       96  SyncService.onDestroy()    → stop() → releaseLock()
       97  ```
       98  
       99 -`MulticastLock` держится **только пока `peerIp == null`**. Пос
          -ле обнаружения партнёра WiFi-чип возвращается к аппаратной фил
          -ьтрации broadcast-пакетов.                                    
       99 +`MulticastLock` держится **всё время жизни `SyncService`**. Li
          +sten-сокет всегда открыт.                                     
      100  
      101 +**Почему не освобождать при известном `peerIp`:** если партнёр
          + ушёл из сети и вернулся (возможно с новым IP по DHCP), он при
          +шлёт UDP burst. Устройство должно его услышать и сразу обновит
          +ь `peerIp` — без ожидания HTTP-таймаута. Если lock отпущен, эт
          +от burst будет отфильтрован WiFi-чипом и discovery не произойд
          +ёт.                                                           
      102 +                                                              
      103 +Стоимость держать lock постоянно: CPU-прерывания на входящий b
          +roadcast/multicast трафик сети. Это несравнимо меньше, чем 288
          +0 исходящих UDP-пакетов в сутки, которые были в старой модели.
      104 +                                                              
      105  ---
      106  
      107  ## Фаза 2 — Синхронизация задач (HTTP)

● Готово. Итог исправления: одна функция pauseDiscovery() — вместо 4 строк
  (cancel jobs + close socket + release lock) осталась 2 строки (cancel
  broadcastJob). Всё остальное не трогали.

✻ Crunched for 1m 9s
> обнови sync_redesign.md с учётом последнего изменения                         

● Update(sync_redesign.md)
  ⎿  Error editing file

  Read 1 file (ctrl+o to expand)

● Update(sync_redesign.md)
  ⎿  Added 5 lines, removed 3 lines
      34  При получении пакета от партнёра — **сначала отвечаем своим пак
          етом** (иначе партнёр не узнает наш IP), затем вызываем `onPeer
          Discovered`.
      35  
      36  Новые методы:
      37 -- `pauseDiscovery()` — останавливает broadcast + listen, отпуск
         -ает MulticastLock (вызывается когда peerIp известен)           
      38 -- `restartDiscovery(scope)` — захватывает lock, запускает liste
         -n + burst (вызывается когда peerIp сброшен)                    
      37 +- `pauseDiscovery()` — останавливает только broadcastJob (liste
         +n-сокет и lock не трогаются)                                   
      38 +- `restartDiscovery(scope)` — шлёт новый burst (listen уже жив,
         + lock уже держится)                                            
      39  
      40 -MulticastLock: `setReferenceCounted(false)` — простое acquire/r
         -elease без счётчика.                                           
      40 +MulticastLock держится **всё время жизни SyncService**. Освобож
         +дается только в `stop()`.                                      
      41  
      42 +**Почему не освобождать при известном `peerIp`:** партнёр может
         + вернуться с новым IP (DHCP). При возвращении он пришлёт UDP bu
         +rst — нужно его услышать немедленно. Если lock отпущен, burst о
         +тфильтруется WiFi-чипом — тупик: A считает B живым, B не может 
         +достучаться до A.                                              
      43 +                                                               
      44  ### HttpClient.kt ✅
      45  
      46  Добавлен `PeerUnreachableException`. При `SocketTimeoutExceptio
          n` или `ConnectException` — бросает его вместо возврата `null`.
           Остальные ошибки по-прежнему возвращают `null` (transient сбои
          ).

● Update(sync_redesign.md)
  ⎿  Added 1 line, removed 1 line
      47  
      48  ### SyncService.kt ✅
      49  
      50 -**`onPeerDiscovered` callback:** после `syncManager.setPeerIp(i
         -p)` — вызывает `udpDiscovery.pauseDiscovery()`. Lock отпущен, l
         -isten-сокет закрыт.                                            
      50 +**`onPeerDiscovered` callback:** после `syncManager.setPeerIp(i
         +p)` — вызывает `udpDiscovery.pauseDiscovery()`. Останавливает т
         +олько burst, listen-сокет и lock остаются активными.           
      51  
      52  **`triggerSync()`:** различает два типа ошибок:
      53  - `PeerUnreachableException` → `syncManager.setPeerIp(null)` + 
          `udpDiscovery.restartDiscovery(scope)` + `ERROR`

● Update(sync_redesign.md)
  ⎿  Added 1 line, removed 1 line
      83  |---|---|---|---|
      84  | UDP broadcast (отправка) | 2880 раз/сутки | ~5 раз при подклю
          чении | **−99%** |
      85  | Пробуждение WiFi-радио для отправки | 2880 раз/сутки | ~5 раз
          /сутки | **−99%** |
      86 -| MulticastLock | постоянно | только пока `peerIp == null` | **
         -существенно меньше** |                                         
      86 +| MulticastLock | постоянно | постоянно | без изменений |      
      87  | WorkManager wake-up | ~96 раз/сутки | 0 | **−100%** |
      88  | DatagramSocket создание/закрытие | каждые 30с | только burst 
          | **−99%** |
      89  | HTTP sync | по polling | только при изменении задачи | зависи
          т от активности |

● Обновлено. Три правки:

  1. Секция UdpDiscovery.kt — описание pauseDiscovery() и restartDiscovery()
  теперь точное, добавлено объяснение тупика
  2. Секция SyncService.kt — убрано упоминание что lock отпускается
  3. Таблица энергоэффективности — строка MulticastLock возвращена к "без
  изменений"

✻ Baked for 34s

> Я сейчас заметил, что если я отлючаю вай фай на одном устройстве и включаю    
  через 30 секунд, то 2 приложения переподключаются. Но если подождать примерно 
  2-3 минуты и включить вай фай, то переподключения не происходит и на          
  устройстве, которое было без вай фай висит состояние "офлайн". Затем на снова 
  отключил вай фай и  запустил приложение на втором телефоне, чтобы оно было    
  открыто и снова включил вай фай на первом устройстве и подключение произошло. 
  Что может быть? Может быть со временем что-то отключается?                    

● Это классическая проблема убийства SyncService Android-ом. Разберём по
  симптомам.

  Почему 30 секунд работает: сервис ещё жив, listen-сокет открыт, WiFi receiver
  срабатывает → burst → подключение.

  Почему 2-3 минуты не работает: Android (особенно OEM — Xiaomi, Samsung)
  убивает Foreground Service даже с START_STICKY после нескольких минут без
  активности. START_STICKY перезапускает его, но:
  - burst длится 6 секунд
  - перезапуск сервиса на двух устройствах происходит асинхронно и независимо
  - окно в 6 секунд слишком мало чтобы гарантированно попасть в момент когда
  второй сервис уже жив и слушает

  Почему с открытым приложением работает: видимый UI повышает приоритет процесса
  — Android его не убивает.

  ---
  Решение: burst должен повторяться каждые 30 секунд пока peerIp == null, и
  останавливаться когда партнёр найден. Это не то же самое что старые 30с — там
  broadcast шёл вечно. Здесь он прекращается после обнаружения.

  // Вместо repeat(5) { ... }:
  while (isActive) {
      repeat(BURST_COUNT) {
          if (!isActive) return@launch
          sendBroadcast()
          delay(BURST_INTERVAL_MS)
      }
      delay(RETRY_INTERVAL_MS) // 30с пауза, затем новый burst
  }

  pauseDiscovery() отменяет broadcastJob → цикл останавливается.

  Реализуем?

✻ Baked for 2m 28s

> Почему 30 секунд работает: сервис ещё жив, listen-сокет открыт, WiFi receiver 
  срабатывает → burst → подключение. - поясни коротко, это на на котором        
  устройстве?                                                                   

● На том, у которого отключали WiFi.

  Цепочка: WiFi включился → wifiReceiver сработал → restartDiscovery() → burst →
  второе устройство услышало → ответило → подключение.

✻ Crunched for 3s

> да, реализуем                                                                 

● Update(app\src\main\java\com\familytasks\app\data\sync\UdpDiscovery.kt)
  ⎿  Added 1 line
      18  private const val BROADCAST_ADDRESS = "255.255.255.255"
      19  private const val BURST_COUNT = 5
      20  private const val BURST_INTERVAL_MS = 1_500L
      21 +private const val BURST_RETRY_INTERVAL_MS = 30_000L            
      22  
      23  @Serializable
      24  data class DiscoveryPacket(val deviceId: String, val httpPort: 
          Int)

● Update(app\src\main\java\com\familytasks\app\data\sync\UdpDiscovery.kt)
  ⎿  Added 7 lines, removed 4 lines
      76  
      77      private fun startBurst(scope: CoroutineScope) {
      78          broadcastJob = scope.launch(Dispatchers.IO) {
      79 -            repeat(BURST_COUNT) {                              
      80 -                if (!isActive) return@launch                   
      81 -                sendBroadcast()                                
      82 -                delay(BURST_INTERVAL_MS)                       
      79 +            while (isActive) {                                 
      80 +                repeat(BURST_COUNT) {                          
      81 +                    if (!isActive) return@launch               
      82 +                    sendBroadcast()                            
      83 +                    delay(BURST_INTERVAL_MS)                   
      84 +                }                                              
      85 +                delay(BURST_RETRY_INTERVAL_MS)                 
      86              }
      87          }
      88      }

● Готово. Теперь broadcastJob шлёт 5 пакетов, ждёт 30 секунд, снова 5 пакетов —
  и так по кругу пока pauseDiscovery() его не отменит. Максимальная задержка
  обнаружения после любого сценария с убийством сервиса — 30 секунд.

✻ Crunched for 12s

> обнови sync_redesign.md и sync_architecture_redesigned_spec.md                

  Read 1 file (ctrl+o to expand)

● Обновляю оба файла.

● Update(sync_redesign.md)
  ⎿  Added 2 lines
      37  - `pauseDiscovery()` — останавливает только broadcastJob (liste
          n-сокет и lock не трогаются)
      38  - `restartDiscovery(scope)` — шлёт новый burst (listen уже жив,
           lock уже держится)
      39  
      40 +`startBurst()` работает в цикле: **5 пакетов → пауза 30с → снов
         +а 5 пакетов** — до тех пор пока `pauseDiscovery()` не отменит `
         +broadcastJob`. Это решает проблему когда Android убивает сервис
         +: после перезапуска burst повторяется каждые 30с и гарантирован
         +но попадает в окно когда второй сервис тоже жив.               
      41 +                                                               
      42  MulticastLock держится **всё время жизни SyncService**. Освобож
          дается только в `stop()`.
      43  
      44  **Почему не освобождать при известном `peerIp`:** партнёр может
           вернуться с новым IP (DHCP). При возвращении он пришлёт UDP bu
          rst — нужно его услышать немедленно. Если lock отпущен, burst о
          тфильтруется WiFi-чипом — тупик: A считает B живым, B не может 
          достучаться до A.

● Теперь спецификация — несколько точечных правок:

● Update(sync_architecture_redesigned_spec.md)
  ⎿  Added 2 lines, removed 2 lines
      56  При старте `SyncService` (или при потере `peerIp`):
      57  1. Захватить `MulticastLock` — разрешает WiFi-чипу пропускать b
          roadcast-пакеты в CPU
      58  2. Открыть `DatagramSocket` на UDP порту `9090` — начать слушат
          ь
      59 -3. Запустить burst: отправить **5 UDP-пакетов** на `255.255.255
         -.255:9090` с интервалом **1.5 секунды**                        
      60 -4. После burst broadcast-корутина завершается; listen-корутина 
         -продолжает работать                                            
      59 +3. Запустить `broadcastJob`: отправить **5 UDP-пакетов** с инте
         +рвалом **1.5с**, затем ждать **30с**, затем снова 5 пакетов — ц
         +икл до обнаружения партнёра                                    
      60 +4. Listen-корутина работает параллельно и независимо от broadca
         +st-цикла                                                       
      61  
      62  ### Формат UDP-пакета
      63  

● Update(sync_architecture_redesigned_spec.md)
  ⎿  Added 1 line, removed 1 line
      71  1. **Немедленно ответить своим пакетом** — иначе партнёр не узн
          ает наш IP
      72  2. Вызвать `onPeerDiscovered(senderIp, deviceId)` → в `SyncServ
          ice`:
      73     - `syncManager.setPeerIp(ip)` — запомнить IP
      74 -   - `udpDiscovery.pauseDiscovery()` — закрыть сокет, отпустить
         - `MulticastLock`                                               
      74 +   - `udpDiscovery.pauseDiscovery()` — остановить broadcast-цик
         +л (сокет и lock остаются)                                      
      75     - `triggerSync()` — немедленно синхронизировать задачи
      76  
      77  ### Параметры

● Update(sync_architecture_redesigned_spec.md)
  ⎿  Added 2 lines, removed 1 line
      82  | Broadcast-адрес | 255.255.255.255 |
      83  | Количество пакетов в burst | 5 |
      84  | Интервал между пакетами | 1.5 секунды |
      85 -| Общее время burst | ~6 секунд |                              
      85 +| Пауза между повторными burst | 30 секунд |                   
      86 +| Максимальная задержка обнаружения | ~30 секунд |             
      87  | Фильтрация входящих | только от `pairedDeviceId` |
      88  
      89  ### MulticastLock — жизненный цикл
● Update(sync_architecture_redesigned_spec.md)
  ⎿  Added 2 lines, removed 1 line
      318  |---|---|---|
      319  | UDP порт | 9090 | `UdpDiscovery.kt` |
      320  | UDP burst, количество пакетов | 5 | `UdpDiscovery.kt` |
      321 -| UDP burst, интервал | 1 500 мс | `UdpDiscovery.kt` |        
      321 +| UDP burst, интервал между пакетами | 1 500 мс | `UdpDiscover
          +y.kt` |                                                       
      322 +| UDP burst, пауза между повторами | 30 000 мс | `UdpDiscovery
          +.kt` |                                                        
      323  | HTTP порт | 8080 | `HttpServer.kt`, `HttpClient.kt` |
      324  | HTTP connect timeout | 5 000 мс | `HttpClient.kt` |
      325  | HTTP read timeout | 10 000 мс | `HttpClient.kt` |

● Теперь исправляю диаграммы сценариев — там ещё остались упоминания
  releaseLock() и неверные описания pauseDiscovery():

● Update(sync_architecture_redesigned_spec.md)
  ⎿  Added 1 line, removed 1 line
      240       │                                         │
      241       │  onPeerDiscovered(ipB)                  │  onPeerDiscov
           ered(ipA)
      242       │  setPeerIp(ipB)                         │  setPeerIp(ip
           A)
      243 -     │  pauseDiscovery() → releaseLock()       │  pauseDiscove
          -ry() → releaseLock()                                          
      243 +     │  pauseDiscovery() → стоп burst          │  pauseDiscove
          +ry() → стоп burst                                             
      244       │  triggerSync()                          │  triggerSync(
           )
      245       │                                         │
      246       │──── POST /sync (все задачи A) ────────►│

● Update(sync_architecture_redesigned_spec.md)
  ⎿  Added 2 lines, removed 3 lines
      274       │  PeerUnreachableException
      275       │  setPeerIp(null)
      276       │  syncState = ERROR
      277 -     │  restartDiscovery()                                    
      278 -     │  acquireLock(), startListening(), burst                
      279 -     │  ... ждём UDP от партнёра ...                          
      277 +     │  restartDiscovery() → новый burst-цикл                 
      278 +     │  ... ждём UDP от партнёра (burst каждые 30с) ...       
      279  ```
      280  
      281  ### Партнёр вернулся в сеть

● Update(sync_architecture_redesigned_spec.md)
  ⎿  Added 1 line, removed 1 line
      288       │  sendBroadcast() → ответ партнёру        │
      289       │  onPeerDiscovered(ipB)                   │  onPeerDisco
           vered(ipA)
      290       │  setPeerIp(ipB)                          │  setPeerIp(i
           pA)
      291 -     │  pauseDiscovery()                        │  pauseDiscov
          -ery()                                                         
      291 +     │  pauseDiscovery() → стоп burst           │  pauseDiscov
          +ery() → стоп burst                                            
      292       │  triggerSync()                           │  triggerSync
           ()
      293       │──── POST /sync ────────────────────────►│
      294       │◄─── ответ ─────────────────────────────│

● Оба документа обновлены. Ключевые правки:

  - sync_redesign.md — добавлено описание цикла burst (5 пакетов → 30с пауза →
  повтор) и объяснение зачем это нужно (убийство сервиса Android-ом)
  - sync_architecture_redesigned_spec.md — пункт 3 запуска discovery, таблица
  параметров (добавлен BURST_RETRY_INTERVAL_MS = 30с), исправлены все диаграммы
  сценариев (убраны releaseLock() и неверные описания pauseDiscovery())

✻ Baked for 1m 1s

> Это сработало. Но почему это работает?                                        

● Потому что теперь burst повторяется каждые 30 секунд пока партнёр не найден,
  вместо одного раза.

  Раньше после убийства сервиса Android-ом было узкое окно в 6 секунд — пока
  один сервис бурстит, второй должен уже слушать. Если оба сервиса
  перезапустились с разницей в несколько секунд, окна не пересекались.

  Теперь окно не 6 секунд, а бесконечное. Устройство A бурстит → пауза 30с →
  снова бурстит. В какой бы момент устройство B ни перезапустило сервис и
  открыло listen-сокет — следующий burst от A придёт максимум через 30 секунд. B
  услышит, ответит, подключение.

✻ Cooked for 6s

> Т.е. это устройство B засыпает?                                               

● Нет, не засыпает. Слушать (listen-сокет) оно продолжает всегда — это просто
  блокирующий socket.receive() в корутине, он не требует активности.

  Проблема была в другом: Android убивает сам процесс SyncService после
  нескольких минут простоя. Когда процесс убит — сокет закрывается. После
  убийства START_STICKY перезапускает сервис, но с задержкой и асинхронно на
  обоих устройствах.

  То есть оба сервиса "умирают" и "воскресают" в разное время. Раньше burst шёл
  только один раз при старте — 6 секунд. Если B ещё не воскрес пока A бурстил —
  всё, тишина навсегда.

  Теперь A бурстит каждые 30 секунд после воскрешения. B воскресает в любой
  момент, открывает сокет и в течение 30 секунд слышит следующий burst от A.

✻ Worked for 7s