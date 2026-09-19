---
title: 服务控制状态损坏与启动失败回退增量规格
created: 2026-09-19
updated: 2026-09-19
doc_role: delta-spec
authority: proposed
status: draft
change_id: complete-post-init-spec-gaps
capability: service-controls
merged_to: []
validation:
  automated: passed
  human: not-run
archive_state:
sources:
  - server-app/src/main/java/dezz/gnssshare/server/ServiceControl.java
  - server-app/src/main/java/dezz/gnssshare/server/GNSSServerTileService.java
  - client-app/src/main/java/dezz/gnssshare/client/ServiceControl.java
  - client-app/src/main/java/dezz/gnssshare/client/GNSSClientTileService.java
confidence: high
taskadmin_tag: master
taskadmin_id: 1
---

# Delta Spec: service-controls

## ADDED Requirements

### Requirement: 损坏的服务生命周期标记按停止处理并清理

任一应用读取跨进程服务 PID 标记时，若文件缺失、为空、不是有效十进制 PID、读取失败或 PID 不对应当前默认主应用进程，系统 SHALL 报告服务停止。对于存在但无法解析或读取的损坏标记，系统 SHALL 尝试删除该标记，使后续 Tile 刷新不会持续依赖无效状态。该失败 SHALL NOT 使 Tile 显示活动或阻止应用正常启动服务。

#### Scenario: PID 标记内容损坏

- GIVEN 生命周期标记文件存在
- AND 内容为空、非数字或无法解析为有效 PID
- WHEN 独立 Tile 进程查询服务状态
- THEN 查询返回服务停止
- AND Tile 显示 `STATE_INACTIVE`
- AND 应用尝试删除损坏标记

#### Scenario: 标记 PID 已失效

- GIVEN 标记包含一个有效 PID
- WHEN 默认主应用进程不存在或当前 PID 不匹配
- THEN 查询返回服务停止
- AND 不把旧标记解释为持久运行意图

### Requirement: Tile 直接启动调用失败时回退到可见应用

当非活动 Tile 已选择直接启动路径，但 `startForegroundService` 或兼容启动调用同步抛出运行时或安全异常时，应用 SHALL 保持 Tile 非活动并打开对应 `MainActivity`，由可见界面重新执行权限、蓝牙和平台前置条件流程。调用失败 SHALL NOT 被解释为服务已经运行。

#### Scenario: 直接启动同步失败

- GIVEN 点击前主应用进程存在
- AND静态前置条件允许尝试直接启动
- WHEN实际服务启动调用抛出运行时或安全异常
- THEN服务控制返回启动失败
- AND Tile 打开对应 `MainActivity`
- AND Tile 在服务真实完成前台创建前保持非活动

#### Scenario: 回退界面已存在

- GIVEN 直接启动失败且已有 `singleTask` `MainActivity`
- WHEN Tile 打开该 Activity
- THEN Activity 通过私有启动标记在 `onNewIntent()` 中重新运行启动前置条件流程
- AND 不因 Activity 已存在而丢失显式启动请求

## MODIFIED Requirements

None。

## REMOVED Requirements

None。

## Acceptance And Validation

- Static: verify both `ServiceControl` implementations symmetrically treat unreadable/invalid PID markers as stopped and delete them, and both TileService implementations open `MainActivity` when direct `startService()` returns false.
- Automated: passed on 2026-09-19；`ANDROID_HOME="$HOME/Android/Sdk" ANDROID_SDK_ROOT="$HOME/Android/Sdk" ./gradlew assembleDebug lintDebug` completed with `BUILD SUCCESSFUL` and 146 actionable tasks。
- Human/review: confirm corrupt-state cleanup and Activity fallback are intended stable failure contracts before merge.
- Until human review passes, this delta remains `status: draft` and SHALL NOT modify the stable owner.

## Source Trace

- Stable owner: `.supermax/specs/service-controls/spec.md`。
- Proposal: `.supermax/specs/changes/complete-post-init-spec-gaps/proposal.md`。
- Server evidence: `server-app/src/main/java/dezz/gnssshare/server/ServiceControl.java`、`GNSSServerTileService.java`。
- Client evidence: `client-app/src/main/java/dezz/gnssshare/client/ServiceControl.java`、`GNSSClientTileService.java`。
- TaskAdmin task: `master/1`。