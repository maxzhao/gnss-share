---
title: 为客户端和服务端增加快捷设置与界面服务开关
created: 2026-09-13
updated: 2026-09-13
doc_role: change-proposal
authority: proposed
status: draft
accepted_at:
merged_to: []
validation:
  automated: passed
  human: not-run
archive_state:
change_id: add-service-controls
capability: service-controls
sources:
  - User requirements and decisions confirmed in chat on 2026-09-13
  - server-app/src/main/AndroidManifest.xml
  - server-app/src/main/java/dezz/gnssshare/server/MainActivity.java
  - server-app/src/main/java/dezz/gnssshare/server/GNSSServerService.java
  - server-app/src/main/java/dezz/gnssshare/server/GNSSServerTileService.java
  - server-app/src/main/java/dezz/gnssshare/server/ServiceControl.java
  - client-app/src/main/AndroidManifest.xml
  - client-app/src/main/java/dezz/gnssshare/client/MainActivity.java
  - client-app/src/main/java/dezz/gnssshare/client/GNSSClientService.java
  - client-app/src/main/java/dezz/gnssshare/client/GNSSClientTileService.java
  - client-app/src/main/java/dezz/gnssshare/client/ServiceControl.java
  - https://developer.android.com/develop/ui/views/quicksettings-tiles
  - https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
confidence: high
---

# Proposal: add-service-controls

## Intent

让用户在客户端和服务端的 Android 快捷设置下拉菜单或应用界面中查看对应前台服务是否运行，并启动或停止该服务，同时保留打开应用后立即尝试启动服务的现有行为。

## Scope

### In Scope

- `server-app` 和 `client-app` 各提供一个由用户添加的 Android Quick Settings Tile。
- Tile 以对应前台服务的真实生命周期显示开启或关闭，并可切换服务。
- 当服务已运行时，点击 Tile 立即停止服务；安全锁屏不阻止停止操作。
- 当服务未运行时：若设备处于安全锁定状态，则先通过 `TileService.unlockAndRun()` 等待用户认证；之后若服务仍停止，按点击时捕获的主应用进程是否存在和当前前置条件选择直接启动或打开 `MainActivity`。
- 权限不足、蓝牙不可用或关闭时，即使主应用进程存在，也打开应用处理前置条件。
- 两端主界面各增加一个按钮；服务运行时显示“停止”，服务停止时显示“启动”，点击后切换服务。
- 用户从界面停止服务后，界面停留期间不得被周期刷新立即重启；应用之后再次进入 `onStart` 时仍按现有逻辑自动启动服务。
- 服务完成前台创建时原子发布带当前主进程 PID 的应用私有运行标记并请求 Tile 激活刷新；服务销毁开始时删除标记并请求关闭刷新。

### Non-Goals

- 不自动或静默把 Tile 添加到快捷设置；用户通过系统编辑面板添加。
- 不增加开机自动启动、通知栏启停动作、桌面小组件、计划任务或持久化“期望运行”配置。
- 不改变蓝牙传输、连接重试、GNSS/惯性定位、模拟定位、Protobuf 协议或目标设备绑定行为。
- 不增加过渡阶段、旧行为兼容开关、新依赖或广泛测试基础设施。

## Affected Capabilities

- `server-app/`: Quick Settings Tile、服务生命周期状态、前置条件判断和界面启停按钮。
- `client-app/`: Quick Settings Tile、服务生命周期状态、前置条件判断和界面启停按钮。
- `shared/`: 可复用的服务控制辅助逻辑，仅在两端实现确有相同责任时增加。
- `proto/location.proto`: 保持不变。

## Proposed Behavior Change

- 两端服务从“仅由应用自动启动且无手动停止入口”改为“保留自动启动，并增加 Tile 与应用内手动启停入口”。
- Tile 的激活状态只表示应用私有生命周期标记存在，且其中发布服务状态的主进程 PID 等于当前默认主进程 PID；界面按钮使用同一服务生命周期的进程内静态状态。两者均不表示蓝牙已连接、正在发送/接收位置或定位有效。
- Tile 运行于独立 Android 进程，使其能区分点击前主应用进程是否已存在；主进程不存在时启动应用界面，存在且前置条件满足时直接启动服务。
- 两端 Tile manifest 均声明 `ACTIVE_TILE=true`，使服务生命周期中的 `TileService.requestListeningState()` 实际触发监听刷新；直接启动前保持 Tile 关闭，只有服务完成前台创建后才激活。
- Tile 回退到已存在的 `singleTask` `MainActivity` 时携带私有启动标记；`onNewIntent()` 清除当前界面的手动停止保护并重新执行前置条件流程，避免暖 Activity 不重新进入 `onStart()` 时丢失显式启动请求。
- Tile 的 inactive 启动路径在安全锁屏下必须先完成认证；解锁回调保留点击时主进程是否已存在的语义，并重新确认服务仍停止，避免认证期间另一入口已启动服务后重复执行启动或 Activity 回退。
- Tile 启动路径必须遵守 Android 14+ 对 `location` 前台服务和 while-in-use 权限的限制；服务端只有在已授予 `ACCESS_BACKGROUND_LOCATION` 且其他条件满足时才允许后台直接启动，否则打开 Activity。客户端没有后台定位授权时，不从 Tile 后台直接创建 `location` 前台服务，而是打开可见 Activity 后启动。

## Risks And Compatibility

- Android 系统不允许应用静默添加 Tile；安装或升级后用户仍需手动添加。
- Tile 独立进程不能直接读取主进程中的静态 `isServiceRunning()`。最终实现不再依赖已弃用且在目标设备存在传播/可见性差异的 `ActivityManager.getRunningServices()`；服务通过应用私有 `AtomicFile` 原子发布/删除当前主进程 PID，Tile 将该 PID 与 `getRunningAppProcesses()` 中当前默认主进程核对。进程异常死亡、强杀或 PID 变化都会使旧标记失效，不需要连接状态或持久化“期望运行”配置。
- Android 仅为 manifest 中声明 `android.service.quicksettings.ACTIVE_TILE=true` 的 Tile 响应 `requestListeningState()`；遗漏该元数据会使生命周期刷新请求无效。
- Android 14+ 会限制后台启动需要 while-in-use 定位权限的前台服务。直接启动条件必须保守；不满足时打开 Activity，避免 `SecurityException` 或无定位能力的服务。
- 安全锁屏下允许 inactive Tile 直接继续启动会绕过用户认证；仅启动分支通过 `unlockAndRun()` 延迟，active Tile 停止分支保持立即可用。
- “重新进入应用仍自动启动”是有意行为：用户在界面停止后离开再返回，服务会再次启动。
- 厂商 Quick Settings 外观和状态刷新时机不同，需在目标手机和平板上人工验收。

## Acceptance Criteria

- 用户手动把两个 Tile 添加到各设备快捷设置后，服务运行时 Tile 显示开启，服务停止时显示关闭。
- 点击开启状态 Tile 后，即使设备安全锁定，对应服务也立即停止，Tile 变为关闭；已打开的应用界面按钮变为“启动”。
- 点击关闭状态 Tile 且设备安全锁定时，在认证完成前不得启动服务或打开应用；认证后若服务仍停止，继续点击时已选择的冷/暖主进程语义和当前前置条件路由。
- 点击关闭状态 Tile 且主应用进程不存在时，系统打开对应应用；应用完成现有前置条件流程后启动服务。
- 点击关闭状态 Tile 且主应用进程已存在、权限和蓝牙条件满足、平台允许后台创建对应前台服务时，服务直接启动且不打开界面。
- 点击关闭状态 Tile 时若权限不足、蓝牙不支持或关闭，则打开应用处理前置条件，Tile 在服务真正创建前保持关闭。
- 应用界面中，服务运行时按钮显示“停止”，点击后停止；服务停止时按钮显示“启动”，点击后沿用现有前置条件流程启动。
- 用户从界面停止服务后，只要该 Activity 未离开并重新进入 `onStart`，周期状态刷新不重新启动服务；离开后再次进入应用则保留现有自动启动行为。
- Tile、按钮和状态文本以服务真实运行状态同步；服务停止且前置条件满足时状态文本明确显示服务未运行，而非持续显示正在启动；蓝牙连接失败或等待位置时，只要服务仍存在就保持开启/“停止”。每次服务发布运行或停止标记后都请求 active Tile 监听刷新。
- `proto/location.proto` 未改变，两个 APK 均通过 `./gradlew assembleDebug` 构建。

## Source Trace

- 用户于 2026-09-13 确认两端增加 Tile 与界面启停按钮、保留应用打开自动启动、仅在主应用进程未运行时由 Tile 打开应用，并接受权限或蓝牙条件不足时打开应用的例外。
- 当前自动启动入口：`server-app/src/main/java/dezz/gnssshare/server/MainActivity.java` 的 `onCreate`、`onStart`、`continueStartup`；`client-app/src/main/java/dezz/gnssshare/client/MainActivity.java` 的同名流程。
- 最终主进程界面状态：`GNSSServerService.isServiceRunning()` 与 `GNSSClientService.isServiceRunning()`；最终跨进程 Tile 状态：服务生命周期写入/删除应用私有原子 PID 标记，两端 `ServiceControl.isServiceRunning()` 将标记 PID 与当前默认主进程 PID 联合校验。
- 最终 manifests 声明两端服务为 `foregroundServiceType="location|connectedDevice"`，Tile 为独立进程且同时标记 `ACTIVE_TILE=true` 与 `TOGGLEABLE_TILE=true`；服务端声明 `ACCESS_BACKGROUND_LOCATION`，客户端未声明。
- Android Quick Settings Tile 官方文档要求通过 `TileService`、`BIND_QUICK_SETTINGS_TILE`、`STATE_ACTIVE`/`STATE_INACTIVE` 和 `updateTile()` 管理状态；`requestListeningState()` 仅对标记 `ACTIVE_TILE=true` 的 Tile 生效。
- Android 前台服务官方限制说明：Android 14+ 后台创建使用 while-in-use 定位权限的 `location` 前台服务通常需要可见 Activity 或明确豁免；持有 `ACCESS_BACKGROUND_LOCATION` 时可在符合条件的后台场景持续访问定位。

## Implementation And Validation State

- Implementation: complete for both applications, including robust lifecycle-published cross-process Tile state and secure-lock authentication gating for inactive Tile startup; no stable-spec merge or archive performed.
- Automated/static evidence: focused `ANDROID_HOME="$HOME/Android/Sdk" ANDROID_SDK_ROOT="$HOME/Android/Sdk" ./gradlew :server-app:compileDebugJavaWithJavac :client-app:compileDebugJavaWithJavac :server-app:lintDebug :client-app:lintDebug` passed (`BUILD SUCCESSFUL`, 98 actionable tasks); the focused cross-process lifecycle-state proof verified symmetric `AtomicFile` PID write/read/delete, foreground-create/destruction publication order, refresh after both transitions, and no connection/GNSS mapping; final `ANDROID_HOME="$HOME/Android/Sdk" ANDROID_SDK_ROOT="$HOME/Android/Sdk" ./gradlew :server-app:compileDebugJavaWithJavac :client-app:compileDebugJavaWithJavac :server-app:lintDebug :client-app:lintDebug assembleDebug && git --no-pager diff --check && test -z "$(git --no-pager diff -- proto/location.proto)"` passed (`BUILD SUCCESSFUL`, 143 actionable tasks; `final-validation: passed`).
- Human validation: not run; target phone/tablet scenarios remain required, so this proposal remains `status: draft`.
