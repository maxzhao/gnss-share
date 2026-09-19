---
title: 补齐 init supermax 后的行为边界规格
created: 2026-09-19
updated: 2026-09-19
doc_role: change-proposal
authority: proposed
status: draft
accepted_at:
merged_to: []
validation:
  automated: passed
  human: not-run
archive_state:
change_id: complete-post-init-spec-gaps
capabilities:
  - gnss-bluetooth-sharing
  - inertial-location-estimation
  - service-controls
sources:
  - git:6ea4554e0e292e108465cb2378862a2a92092b3d..16107491dcee38250dcd8130b6b57f0311111fd5
  - Current source inspection on 2026-09-19
confidence: high
taskadmin_tag: master
taskadmin_id: 1
---

# Proposal: complete-post-init-spec-gaps

## Intent

补充三组已正式化能力中未被原增量规格明确表达、但由当前实现直接证明的运行边界和失败恢复行为。该提案只修复规格覆盖，不改变应用代码或已接受行为。

## Scope

### In Scope

- 蓝牙会话的立即首帧、双向活动超时、非法心跳、非法帧长度、Protobuf 解析失败和恢复路径。
- 惯性预测仅使用时间顺序正确且与最新旋转姿态相隔不超过 100 ms 的线性加速度事件。
- 服务控制遇到损坏 PID 标记时按停止处理并清理，以及直接启动调用失败时回退到可见 Activity。
- 为每项遗漏提供可验证场景和精确源代码证据。

### Out Of Scope

- 修改 Android 源码、资源、manifest、Gradle 或线协议。
- 改变现有 1 MiB 帧上限、3 秒超时、100 ms 姿态新鲜度或 Activity 回退实现。
- 重开已经人工审核通过的三组原始变更。
- 在本提案审核前修改三个稳定规格。

## Affected Capabilities

- `.supermax/specs/gnss-bluetooth-sharing/spec.md`
- `.supermax/specs/inertial-location-estimation/spec.md`
- `.supermax/specs/service-controls/spec.md`

## Proposed Behavior Change

该提案不改变运行行为，只把当前代码已有但原规格遗漏的边界提升为显式候选契约：

- 畸形或停滞蓝牙会话必须关闭并进入既有恢复流程，且连接建立后立即提供当前状态。
- 惯性预测不得使用过期或时间倒序的旋转姿态组合。
- 损坏的跨进程服务状态不得产生假活动 Tile；直接启动调用失败时必须打开应用处理。

## Risks And Compatibility

- 把当前实现常量提升为规范会降低未来调整自由；审核时应确认 `1 MiB`、`3 秒` 和 `100 ms` 是否确属产品契约。
- 这些行为已经存在，因此接受提案不会造成线协议或运行兼容性变化。
- 在人工审核前，稳定规格中的 Coverage Gaps 链接本工作区，本提案保持 `status: draft`。

## Acceptance Criteria

- 每项新增要求都能映射到当前源代码分支和至少一个失败场景。
- `proto/location.proto`、应用代码和构建配置没有因本提案改变。
- `./gradlew assembleDebug lintDebug` 通过。
- 人工审核确认这些实现边界应成为稳定契约后，三个 delta 合并到各自稳定 owner。

## Validation Evidence

- Automated: passed; `ANDROID_HOME="$HOME/Android/Sdk" ANDROID_SDK_ROOT="$HOME/Android/Sdk" ./gradlew assembleDebug lintDebug` completed with `BUILD SUCCESSFUL` and 146 actionable tasks on 2026-09-19.
- Static: lifecycle, exact-path, stale-state, protocol no-diff and `git --no-pager diff --check` audits passed; source branches were verified in `BluetoothContract`, `GNSSServerService`, `ConnectionManager`, `DeadReckoningEstimator`, and both `ServiceControl`/Tile pairs.
- Human: not run for this newly authored text; the user's approval applies to the three pre-existing specs only.

## Source Trace

- Shared Bluetooth constants: `shared/src/main/java/dezz/gnssshare/shared/BluetoothContract.java`。
- Bluetooth server session: `server-app/src/main/java/dezz/gnssshare/server/GNSSServerService.java` 的 `ClientHandler`。
- Bluetooth client session: `client-app/src/main/java/dezz/gnssshare/client/ConnectionManager.java`。
- Inertial sensor ordering: `server-app/src/main/java/dezz/gnssshare/server/DeadReckoningEstimator.java#onSensorChanged`。
- Cross-process state and Tile fallback: both applications' `ServiceControl.java` and TileService classes。
- TaskAdmin task: `master/1`。