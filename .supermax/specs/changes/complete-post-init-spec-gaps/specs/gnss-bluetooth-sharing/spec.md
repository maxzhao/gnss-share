---
title: 蓝牙会话边界与恢复增量规格
created: 2026-09-19
updated: 2026-09-19
doc_role: delta-spec
authority: proposed
status: draft
change_id: complete-post-init-spec-gaps
capability: gnss-bluetooth-sharing
merged_to: []
validation:
  automated: passed
  human: not-run
archive_state:
sources:
  - shared/src/main/java/dezz/gnssshare/shared/BluetoothContract.java
  - server-app/src/main/java/dezz/gnssshare/server/GNSSServerService.java
  - client-app/src/main/java/dezz/gnssshare/client/ConnectionManager.java
confidence: high
taskadmin_tag: master
taskadmin_id: 1
---

# Delta Spec: gnss-bluetooth-sharing

## ADDED Requirements

### Requirement: 新会话立即获得当前响应并维持响应活性

授权 RFCOMM 会话建立后，手机 SHALL 在进入心跳读取循环前立即发送当前 `ServerResponse`。此后即使定位和状态未变化，只要会话收到合法心跳且距离上次响应已达到约 1 秒，手机 SHALL 重发当前响应，使客户端能够区分安静但存活的会话与停滞会话。

#### Scenario: 连接后立即收到状态

- GIVEN 授权平板刚建立 RFCOMM 会话
- WHEN 服务端完成授权并创建会话处理器
- THEN 服务端在等待首个心跳前发送当前 `ServerResponse`
- AND 客户端可立即呈现当前服务状态

#### Scenario: 没有新定位但会话存活

- GIVEN 授权会话持续收到每秒心跳
- AND 一秒内没有新的定位或状态响应
- WHEN 服务端处理下一次合法心跳
- THEN 服务端重发当前 `ServerResponse`
- AND 客户端响应活动计时被刷新

### Requirement: 双向停滞和畸形输入关闭会话并恢复

手机 SHALL 在超过 3 秒未收到客户端心跳时关闭会话。平板 SHALL 在超过 3 秒未收到服务端响应时关闭会话并进入同一保存目标的重连流程。服务端收到非 `0x01` 心跳字节时 SHALL 关闭会话。客户端 SHALL 仅接受正长度且不大于 `1 MiB` 的 4 字节大端帧；非法长度、截断 payload 或无法解析为 `LocationProto.ServerResponse` 的 payload SHALL 使当前会话失败并进入既有重连流程，不得继续在失去帧边界的流上解析。

#### Scenario: 客户端心跳停止

- GIVEN 授权会话已建立
- WHEN 服务端超过 3 秒未读取到合法心跳
- THEN 服务端关闭该会话
- AND 重新等待保存的授权平板

#### Scenario: 服务端响应停止

- GIVEN 客户端服务仍运行且目标仍有效
- WHEN 客户端超过 3 秒未收到任何完整服务端响应
- THEN 客户端关闭当前 socket
- AND 对同一保存手机进入重连流程

#### Scenario: 收到非法心跳

- GIVEN 服务端正在读取授权会话
- WHEN 收到的控制字节不是 `0x01`
- THEN 服务端关闭该会话
- AND 不把该字节解释为其他命令

#### Scenario: 收到非法帧长度

- GIVEN 客户端正在读取 4 字节大端帧头
- WHEN 解码长度小于等于 0 或大于 `1 MiB`
- THEN 客户端拒绝该帧并关闭当前会话
- AND 进入既有重连流程

#### Scenario: payload 截断或无法解析

- GIVEN 客户端已接受一个合法范围内的帧长度
- WHEN payload 在完整读取前结束或 Protobuf 解析失败
- THEN 当前会话失败并关闭
- AND 客户端不尝试从未知流偏移继续解帧

## MODIFIED Requirements

None。

## REMOVED Requirements

None。

## Acceptance And Validation

- Static: verify `HEARTBEAT_INTERVAL_MS=1000`, `STALE_CONNECTION_TIMEOUT_MS=3000`, `RESPONSE_INTERVAL_MS=1000`, `MAX_FRAME_BYTES=1024*1024`, immediate `sendResponse(lastServerResponse)`, invalid-heartbeat break and client frame guards.
- Automated: passed on 2026-09-19；`ANDROID_HOME="$HOME/Android/Sdk" ANDROID_SDK_ROOT="$HOME/Android/Sdk" ./gradlew assembleDebug lintDebug` completed with `BUILD SUCCESSFUL` and 146 actionable tasks。
- Human/review: confirm the exact timeout and frame-size constants are intended stable product contracts before merge.
- Until human review passes, this delta remains `status: draft` and SHALL NOT modify the stable owner.

## Source Trace

- Stable owner: `.supermax/specs/gnss-bluetooth-sharing/spec.md`。
- Proposal: `.supermax/specs/changes/complete-post-init-spec-gaps/proposal.md`。
- Shared constants: `shared/src/main/java/dezz/gnssshare/shared/BluetoothContract.java`。
- Server handler: `server-app/src/main/java/dezz/gnssshare/server/GNSSServerService.java#ClientHandler`。
- Client reader/recovery: `client-app/src/main/java/dezz/gnssshare/client/ConnectionManager.java`。
- TaskAdmin task: `master/1`。