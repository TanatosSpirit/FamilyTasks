# FamilyTasks — Архитектура

## 1. Слои приложения

```mermaid
graph TD
    subgraph UI["UI Layer (Jetpack Compose)"]
        TaskList[TaskListScreen]
        TaskDetail[TaskDetailScreen]
        CreateTask[CreateTaskScreen]
        Settings[SettingsScreen]
        Onboarding[OnboardingScreen]
        Logs[LogsScreen]
    end

    subgraph VM["ViewModels"]
        TLvm[TaskListViewModel]
        TDvm[TaskDetailViewModel]
        CTvm[CreateTaskViewModel]
        Svm[SettingsViewModel]
        Lvm[LogsViewModel]
    end

    subgraph Domain["Domain Layer"]
        MergeUC[MergeTasksUseCase]
        SyncUC[SyncUseCase]
        TaskRepo[TaskRepository]
        DevRepo[DeviceRepository]
    end

    subgraph Data["Data Layer"]
        TaskRepoImpl[TaskRepositoryImpl]
        DevRepoImpl[DeviceRepositoryImpl]
        TaskDao[TaskDao]
        DevDao[DeviceDao]
        DB[(Room DB\nv2)]
    end

    subgraph Sync["Sync Layer"]
        SyncSvc[SyncService\nForeground Service]
        SyncMgr[SyncManager\nSingleton]
        HttpSrv[HttpServer\n:8080]
        HttpCli[HttpClient]
        Scanner[SubnetScanner]
    end

    subgraph Core["Core"]
        DevIdMgr[DeviceIdManager]
        PrefMgr[PreferencesManager]
        Logger[AppLogger]
    end

    UI --> VM
    VM --> Domain
    VM --> Core
    Domain --> TaskRepo
    Domain --> DevRepo
    TaskRepo --> TaskRepoImpl
    DevRepo --> DevRepoImpl
    TaskRepoImpl --> TaskDao
    DevRepoImpl --> DevDao
    TaskDao --> DB
    DevDao --> DB
    SyncSvc --> SyncMgr
    SyncSvc --> HttpSrv
    SyncSvc --> HttpCli
    SyncSvc --> Scanner
    SyncSvc --> MergeUC
    SyncSvc --> TaskRepo
    SyncMgr --> Core
```

---

## 2. Поток синхронизации (Flowchart)

```mermaid
flowchart TD
    WiFi[WiFi подключилось] --> CheckIP{peerIp\nсохранён?}

    CheckIP -- да --> Idle[Idle\nmesh-роуминг]
    CheckIP -- нет --> Scan[SubnetScanner\nGET /identify × 253 хостов\nconcurrency=10, timeout 1.5s]

    Scan -- найден --> SaveIP[SyncManager.setPeerIp]
    Scan -- не найден --> WaitEvent[Ожидание события]

    SaveIP --> TriggerSync

    subgraph TaskChange["Задача изменилась"]
        Debounce[debounce 3s] --> TriggerSync
    end

    TriggerSync{triggerSync} -- уже syncing --> Skip[пропустить]
    TriggerSync -- peerIp == null --> Scan
    TriggerSync -- peerIp OK --> PostSync[POST /sync → peerIp:8080]

    PostSync -- OK --> Merge[MergeTasksUseCase\nadded/updated > 0 → upsert]
    PostSync -- timeout --> ClearIP[peerIp = null] --> Scan

    Merge --> UpdateLastSync[lastSyncAt обновлён]

    subgraph KeepAlive["keepAlive раз в час"]
        KA{peerIp\nесть?} -- да --> GetIdentify[GET /identify?deviceId=...]
        KA -- нет --> Scan
        GetIdentify -- OK --> KAidle[IP актуален]
        GetIdentify -- fail --> ClearIP
    end

    subgraph IncomingKA["Входящий keepAlive от партнёра"]
        PartnerKA[GET /identify?deviceId=partner] --> CheckPaired{pairedDeviceId?}
        CheckPaired -- да, peerIp==null --> SaveIP
        CheckPaired -- нет --> Ignore[игнорировать]
    end

    subgraph IncomingSync["Входящий /sync от партнёра"]
        PartnerSync[POST /sync] --> CheckSyncPaired{pairedDeviceId?}
        CheckSyncPaired -- да, peerIp==null --> SaveIP
        CheckSyncPaired -- нет --> Reject[отклонить]
        CheckSyncPaired -- да, peerIp OK --> Merge
    end
```

---

## 3. Протокол синхронизации (Sequence)

```mermaid
sequenceDiagram
    participant A as Устройство A
    participant B as Устройство B

    Note over A,B: Discovery через SubnetScanner
    A->>B: GET /identify
    B-->>A: { "deviceId": "b-id" }
    Note over A: deviceId совпадает с pairedDeviceId → setPeerIp(B)

    Note over A,B: Синхронизация
    A->>B: POST /sync { deviceId, tasks[], devices[] }
    B->>B: MergeTasksUseCase (last-write-wins по updatedAt)
    B-->>A: { deviceId, tasks[], devices[] }
    A->>A: MergeTasksUseCase

    Note over A,B: keepAlive (раз в час)
    A->>B: GET /identify?deviceId=a-id
    B-->>A: { "deviceId": "b-id" }
    Note over A: IP актуален, ничего не делаем

    Note over A,B: Восстановление IP через входящий keepAlive
    B->>A: GET /identify?deviceId=b-id
    Note over A: peerIp==null → setPeerIp(B) → triggerSync()
    A->>B: POST /sync ...
```

---

## 4. Модель данных

```mermaid
classDiagram
    class Task {
        +String id
        +String title
        +String? description
        +String? assignedTo
        +TaskStatus status
        +Priority priority
        +Long? dueDate
        +String createdBy
        +Long createdAt
        +Long updatedAt
        +Boolean deleted
        +Long? completedAt
    }

    class TaskStatus {
        <<enumeration>>
        TODO
        IN_PROGRESS
        DONE
    }

    class Priority {
        <<enumeration>>
        LOW
        NORMAL
        HIGH
    }

    class Device {
        +String deviceId
        +String displayName
        +Int color
    }

    class SyncPayload {
        +String deviceId
        +List~Task~ tasks
        +List~Device~ devices
    }

    Task --> TaskStatus
    Task --> Priority
    SyncPayload --> Task
    SyncPayload --> Device
```

---

## 5. Механизмы восстановления peerIp

```mermaid
flowchart LR
    Lost[peerIp потерян]

    Lost -->|задача изменилась\nмгновенно| S1[triggerSync\n→ scan]
    Lost -->|keepAlive таймер\nраз в час| S2[keepAlive\n→ scan]
    Lost -->|входящий keepAlive\nот партнёра| S3[onPeerIdentified\n→ setPeerIp\n→ triggerSync]

    S1 --> Found[peerIp восстановлен]
    S2 --> Found
    S3 --> Found
```
