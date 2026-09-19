---
title: 客户端与服务端服务控制规格
created: 2026-09-19
updated: 2026-09-19
type: source
doc_role: spec
authority: normative
status: active
sources:
  - .supermax/specs/changes/add-service-controls/specs/service-controls/spec.md
  - git:16107491dcee38250dcd8130b6b57f0311111fd5
confidence: high
taskadmin_tag: master
taskadmin_id: 1
---

# 客户端与服务端服务控制规格

> **TLDR**：客户端和服务端均通过反映真实服务生命周期的 Quick Settings Tile 与应用内按钮安全启停自己的前台服务，同时保留应用进入时自动启动。

## Purpose

本规格定义 `server-app` 和 `client-app` 的用户可见服务控制入口、跨进程运行状态、锁屏认证、平台前置条件和自动启动共存规则。连接、GNSS 数据有效性和服务运行状态是相互独立的概念。

## Scope

### In Scope

- 每个应用一个由用户添加的可切换 Quick Settings Tile。
- 活动 Tile 停止服务，非活动 Tile 按锁屏、主进程和前置条件路由启动。
- 服务生命周期通过应用私有原子 PID 标记发布到独立 Tile 进程。
- 两端主界面提供启动或停止按钮。
- 界面同一可见周期的手动停止保护和之后 `onStart` 自动启动。
- Android 14+ `location` 前台服务后台创建限制。

### Out Of Scope

- 静默添加 Tile、开机自动启动、通知栏动作、桌面小组件或计划任务。
- 持久化“期望运行”或永久禁用状态。
- 蓝牙传输、设备绑定、重连、GNSS/惯性定位、模拟定位或 Protobuf 行为变更。

## Actors And Triggers

- 用户：添加或点击 Tile、点击应用内按钮、重新进入应用。
- Android System UI：创建独立 Tile 进程、报告锁屏状态并执行认证回调。
- 主应用进程：创建或销毁对应前台服务并发布生命周期。
- 触发：Tile 开始监听、Tile 点击、Activity `onStart`/`onNewIntent`、服务创建或销毁、前置条件变化。

## Requirements

### Requirement: 每个应用暴露可切换 Quick Settings Tile

`server-app` 和 `client-app` SHALL 各自暴露一个表示本应用前台服务是否存在并运行的 Android Quick Settings Tile。每个 Tile SHALL 声明 `android.service.quicksettings.ACTIVE_TILE=true` 和 `android.service.quicksettings.TOGGLEABLE_TILE=true`。用户 SHALL 通过系统界面添加 Tile；应用 SHALL NOT 静默添加。

#### Scenario: Tile 反映运行服务

- GIVEN 对应前台服务已完成前台创建
- WHEN Android 开始 Tile 监听或服务请求刷新
- THEN Tile 状态为 `STATE_ACTIVE`
- AND 即使蓝牙断开、对端不可用或 GNSS 数据未流动，只要服务仍存在就保持活动

#### Scenario: Tile 反映停止服务

- GIVEN 对应服务不存在、已销毁或主应用进程已不存在
- WHEN Android 开始 Tile 监听或服务请求刷新
- THEN Tile 状态为 `STATE_INACTIVE`

#### Scenario: 用户未添加 Tile

- GIVEN 应用已安装或升级
- WHEN 用户未通过系统快捷设置编辑界面添加 Tile
- THEN 应用不得声称 Tile 已添加
- AND 应用内服务控制仍独立工作

### Requirement: 活动 Tile 停止对应服务

点击活动 Tile SHALL 只停止该应用拥有的前台服务，并使可见控制更新为停止状态。安全锁屏 SHALL NOT 延迟这一限制性停止操作。

#### Scenario: 从 Tile 停止

- GIVEN Tile 活动且其服务正在运行
- WHEN 用户在解锁或安全锁定状态点击 Tile
- THEN 应用请求停止该服务
- AND Tile 最迟在下一次生命周期刷新时变为非活动
- AND 已打开界面的按钮在下一次状态刷新时显示“启动”或本地化等价文本

### Requirement: 安全锁屏要求认证后才能启动

非活动 Tile 在设备安全锁定时 SHALL NOT 启动服务或打开 `MainActivity`。它 SHALL 跨认证保存点击时主应用进程是否存在，并在继续前重新检查服务状态。

#### Scenario: 安全锁定时点击非活动 Tile

- GIVEN Tile 非活动
- AND `TileService.isLocked()` 与 `TileService.isSecure()` 都为 true
- WHEN 用户点击 Tile
- THEN Tile 保持非活动
- AND 应用通过 `TileService.unlockAndRun()` 延迟启动
- AND 成功认证前不启动服务或打开 `MainActivity`

#### Scenario: 认证期间服务已启动

- GIVEN 非活动 Tile 的启动因认证延迟
- AND 另一入口在解锁回调前启动对应服务
- WHEN 解锁回调执行
- THEN 应用重新读取服务状态
- AND 不重复启动服务或打开 `MainActivity`
- AND Tile 刷新为活动

#### Scenario: 认证后继续

- GIVEN 非活动 Tile 的启动因认证延迟
- AND 解锁回调执行时服务仍停止
- WHEN 应用继续启动路由
- THEN 使用原始点击时主应用进程是否存在的值
- AND 重新评估当前前置条件及平台直接启动许可

### Requirement: 非活动 Tile 选择直接启动或打开应用

点击非活动 Tile SHALL 使用点击前捕获的主应用进程状态和当前服务前置条件，在需要时完成安全认证后，选择且仅选择一个启动路径。

#### Scenario: 点击前主应用进程不存在

- GIVEN 前台服务停止
- AND Tile 点击前主应用进程不存在
- WHEN 用户点击非活动 Tile
- THEN Tile 打开该应用的 `MainActivity`
- AND `MainActivity` 执行既有前置条件与自动启动流程，包括已有 `singleTask` Activity 通过 `onNewIntent()` 接收启动请求
- AND 服务真正创建前 Tile 保持非活动

#### Scenario: 主进程存在且允许直接启动

- GIVEN 服务停止且点击前主应用进程存在
- AND 所需位置和蓝牙权限已授予
- AND经典蓝牙受支持且已开启
- AND 当前 Android 版本和授权允许后台创建该应用的 `location` 前台服务
- WHEN 用户点击非活动 Tile
- THEN 应用不打开 `MainActivity` 而直接启动服务
- AND 只有服务创建成功后 Tile 才变为活动

#### Scenario: 前置条件缺失

- GIVEN 服务停止
- AND 权限缺失、蓝牙不支持或关闭，或平台不允许安全地后台创建 `location` 前台服务
- WHEN 用户点击非活动 Tile
- THEN 即使主应用进程存在也打开该应用的 `MainActivity`
- AND 暖 `singleTask` Activity 将 Tile Intent 视为显式启动请求，清除 Activity 本地手动停止保护并从 `onNewIntent()` 重跑启动流程
- AND 服务真正创建前 Tile 保持非活动

#### Scenario: 目标设备未选择或不可达

- GIVEN 权限和蓝牙前置条件满足
- AND 目标设备未选择或保存目标不可用
- WHEN 其他规则允许启动服务
- THEN 目标缺失或未连接本身不阻止服务创建
- AND 运行服务显示既有设置、等待或重试状态

### Requirement: 主应用进程检测独立于 Tile 进程

每个 Tile SHALL 运行在默认主应用进程之外，使 Tile 实例化本身不会满足“主进程存在”条件。

#### Scenario: Tile 进程启动冷应用

- GIVEN 主应用进程和 Tile 进程都不存在
- WHEN Android 为点击创建 Tile 进程
- THEN 应用仍将主应用进程判定为不存在
- AND 打开 `MainActivity`，而不是把 Tile 进程误认为已运行的主应用

### Requirement: 服务运行状态跨进程发布

每个应用 SHALL 通过应用私有的原子 PID 标记发布服务生命周期。只有标记存在且 PID 等于当前运行的默认主应用进程 PID 时，服务 SHALL 被判定为活动。蓝牙连接、对端可用性、GNSS 流量和其他运行状态 SHALL NOT 改变此标记。

#### Scenario: 正常服务生命周期

- GIVEN 服务停止
- WHEN 服务创建成功并进入前台状态
- THEN 服务原子写入当前主进程 PID
- AND 请求 Tile 刷新
- AND 跨进程查询在 PID 匹配期间报告活动
- WHEN 服务销毁开始
- THEN 服务在资源拆除前删除标记
- AND 请求 Tile 刷新
- AND 标记删除后跨进程查询立即报告非活动

#### Scenario: 主进程异常终止

- GIVEN 主应用进程未执行正常服务销毁回调而终止并遗留标记
- WHEN Tile 发现默认主进程不存在或其实时 PID 与标记不同
- THEN 将服务解释为停止
- AND 显示 `STATE_INACTIVE`

#### Scenario: 服务仍存活但运行状态变化

- GIVEN 生命周期标记 PID 仍匹配默认主进程
- WHEN 蓝牙断开、对端不可用、GNSS 数据停止或其他运行状态变化
- THEN 生命周期标记保持不变
- AND Tile 保持 `STATE_ACTIVE`

### Requirement: 两端主界面提供启动或停止按钮

服务端和客户端主界面 SHALL 各提供一个按钮，其标签和动作来自对应服务运行生命周期，而不是传输连接状态。

#### Scenario: 主界面中的运行服务

- GIVEN 对应前台服务正在运行
- WHEN 界面渲染或周期刷新
- THEN 按钮显示“停止”或本地化等价文本
- WHEN 用户点击
- THEN 服务停止，按钮更新为“启动”

#### Scenario: 主界面中的停止服务

- GIVEN 对应前台服务停止
- WHEN 界面渲染或周期刷新
- THEN 按钮显示“启动”或本地化等价文本
- AND 没有更优先的前置条件错误时，状态显示服务已停止而不是仍在启动
- WHEN 用户点击
- THEN 界面执行既有权限和蓝牙前置条件流程，并在满足后启动服务

#### Scenario: 连接状态不同于服务状态

- GIVEN 服务运行但正在等待目标、重连、等待位置或报告其他状态
- WHEN 界面更新
- THEN 服务按钮继续显示“停止”
- AND 状态文本独立显示更详细的运行状态

### Requirement: 手动停止与自动启动共存

当前主界面发出的停止请求 SHALL 防止服务在同一 Activity 可见周期被立即自动重启；之后再次进入 Activity SHALL 保留自动启动行为。

#### Scenario: 停止后留在界面

- GIVEN 服务运行且 Activity 可见
- WHEN 用户点击界面停止按钮
- THEN 服务停止
- AND 该 Activity 同一可见周期的周期刷新不得重新启动服务

#### Scenario: 手动停止后重新进入

- GIVEN 用户从主界面停止服务后离开，并使 Activity 之后再次进入 `onStart`
- WHEN 既有启动前置条件满足
- THEN Activity 自动启动服务，无需再次点击启动按钮

#### Scenario: 正常打开已停止应用

- GIVEN 应用启动或通过新的 `onStart` 返回且服务停止
- WHEN 权限和蓝牙条件满足
- THEN 应用立即自动启动服务

### Requirement: 服务控制不改变定位协议或传输语义

新控制 SHALL 只管理服务生命周期，SHALL NOT 修改位置消息结构、帧协议、蓝牙对端授权、重连、GNSS 采集、惯性估算、模拟定位发布或保存目标语义。

#### Scenario: 协议兼容

- GIVEN 服务控制已启用
- WHEN 两个应用通信
- THEN `proto/location.proto` 保持不变
- AND 既有长度帧 Protobuf 交换仍是线协议

## Edge Cases And Failure Behavior

- 活动 Tile 停止不受安全锁屏认证阻塞；非活动 Tile 启动必须遵循认证。
- 认证等待期间若其他入口启动服务，回调不得重复启动或打开界面。
- 主进程死亡或 PID 变化使旧运行标记失效，Tile 不得显示假活动状态。
- 权限、蓝牙或平台后台启动条件不足时必须回退到可见 Activity 处理。
- 界面手动停止不是持久禁用；之后新的 `onStart` 可以恢复自动启动。

## Data / Entity Constraints

- 运行标记是应用私有、原子写入的当前主进程 PID，不是持久期望状态。
- 两个应用仅控制各自服务和 Tile。
- Tile 运行在独立命名进程；服务运行在默认主应用进程。

## Behavior Coverage

| Behavior Surface | Normal Behavior | Authorization / Actors | State / Data Effects | Failure / Edge Cases | External Dependencies | Concurrency / Idempotency | Validation |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Quick Settings Tile | covered | covered | covered | covered | Android System UI | covered | automated/human passed |
| 跨进程生命周期 | covered | system/app | covered | covered | process manager/filesystem | covered | automated/human passed |
| 主界面按钮 | covered | user | covered | covered | Activity/FGS | covered | automated/human passed |
| 自动启动共存 | covered | user/app | covered | covered | Activity lifecycle | covered | automated/human passed |

## Coverage Gaps

- 损坏 PID 标记和直接启动调用失败的明确回退语义由 `.supermax/specs/changes/complete-post-init-spec-gaps/` 作为待审核增量维护。

## Acceptance Criteria

- 两个 Tile 在服务生命周期变化后显示正确活动状态，且不把连接或 GNSS 状态误作服务状态。
- 安全锁屏下停止立即执行，启动必须认证后才继续。
- 冷主进程打开应用；暖主进程仅在前置条件与平台许可满足时直接启动。
- 两个主界面按钮按真实服务状态启停服务。
- 同一可见周期的手动停止不被刷新撤销，之后重新进入仍自动启动。
- 服务控制不改变协议、传输和定位行为。

## Validation Plan

- Automated: `ANDROID_HOME="$HOME/Android/Sdk" ANDROID_SDK_ROOT="$HOME/Android/Sdk" ./gradlew :server-app:compileDebugJavaWithJavac :client-app:compileDebugJavaWithJavac :server-app:lintDebug :client-app:lintDebug assembleDebug`。
- Static: 对称 `AtomicFile` PID 写入、读取和删除，前台创建与销毁发布顺序、Tile 刷新、协议 no-diff。
- Human: 双端 Tile 添加、锁屏启停、认证取消与完成、冷暖进程路由、前置条件回退、界面按钮与异常进程终止。
- Review state: corrected implementation and existing delta manually approved by the user on 2026-09-19.

## Assumptions And Open Questions

- Assumption: Tile 外观和刷新时机可能因系统或 OEM 不同，但生命周期状态契约保持一致。
- Open question: None recorded.

## Source Trace

- Accepted change: `.supermax/specs/changes/add-service-controls/`。
- Server controls: `server-app/src/main/java/dezz/gnssshare/server/{GNSSServerTileService,ServiceControl,MainActivity,GNSSServerService}.java`。
- Client controls: `client-app/src/main/java/dezz/gnssshare/client/{GNSSClientTileService,ServiceControl,MainActivity,GNSSClientService}.java`。
- Android declarations: `server-app/src/main/AndroidManifest.xml`、`client-app/src/main/AndroidManifest.xml`。
- Related transport owner: `.supermax/specs/gnss-bluetooth-sharing/spec.md`。
- TaskAdmin task: `master/1`。