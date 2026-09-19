---
title: 增加 GNSS 失锁后的惯性航位推算
created: 2026-09-13
updated: 2026-09-19
doc_role: change-proposal
authority: proposed
status: accepted
accepted_at: 2026-09-19
merged_to:
  - .supermax/specs/inertial-location-estimation/spec.md
validation:
  automated: passed
  human: passed
archive_state: retained
change_id: add-inertial-dead-reckoning
capability: inertial-location-estimation
sources:
  - User decisions confirmed in chat on 2026-09-13
  - server-app/src/main/java/dezz/gnssshare/server/GNSSServerService.java
  - client-app/src/main/java/dezz/gnssshare/client/GNSSClientService.java
  - proto/location.proto
  - https://github.com/maddevsio/mad-location-manager-lib
confidence: high
taskadmin_tag: master
taskadmin_id: 1
---

# Proposal: add-inertial-dead-reckoning

## Intent

让手机服务端在 GNSS/Fused 定位变差或停止回调后，利用手机内置惯性传感器与最后可用真实位置持续估算位置，并继续向客户端发送带新时间戳的定位，而不是无限重复旧坐标。

## Scope

### In Scope

- 在 `server-app` 集成免费开源 MIT `mad-location-manager-lib` 的 GPS/加速度卡尔曼滤波核心，不重写滤波算法。
- 使用 Android `TYPE_LINEAR_ACCELERATION` 与 `TYPE_ROTATION_VECTOR` 将设备坐标加速度转换为 ENU 后送入滤波器。
- 所有实时 GNSS/Fused 位置用于校正滤波器；报告精度越差，校正权重越低，不设置任意“差 GPS”硬阈值。
- 每 500 ms 发布一次当前估算位置；没有新的真实位置时持续惯性预测，不设置预测时长上限。
- 持久保存最后一个有效的实时 GNSS/Fused 位置。服务在无信号环境启动时，从应用缓存与 Android 系统缓存中选择时间最新的有效真实位置作为绝对坐标起点，不限制缓存年龄。
- 预测位置使用新时间戳、`provider="dead_reckoning"`、沿用最后有效高度，并发布随预测时长持续增长的水平 `accuracy`。
- 缺少必要传感器时明确显示惯性辅助不可用；仍转发新到达的真实定位，但不伪造预测位置。
- 沿用当前按授权客户端连接启停定位的生命周期：断开后的 15 秒仅是采集延迟停止时间，不是预测上限；超时停止后保留持久位置供下次连接重新初始化。

### Non-Goals

- 不引入商业 SDK、Token、收费服务、地图、路径规划或地图匹配。
- 不接入车辆里程计、OBD、外置 IMU、摄像头、Wi-Fi/基站指纹或云端定位。
- 不承诺无 GNSS 时的精度、道路约束或误差上限；预测可无限持续，误差也可无限增长。
- 不为缺少起始真实位置的设备虚构绝对经纬度。
- 不增加传感器调参 UI、模式切换、兼容路径或过渡实现。
- 不修改蓝牙传输、客户端模拟定位规则或 `proto/location.proto`。

## Affected Capabilities

- `server-app/`: 原生滤波依赖、JNI、传感器采集、位置缓存、估算调度、状态与通知。
- `.github/workflows/release.yml`: 安装与本地构建一致的固定 NDK/CMake。
- `client-app/`: 无代码行为变更；继续按新时间戳注入收到的位置。
- `proto/location.proto`: 保持不变；现有 `provider` 与 `accuracy` 字段表达预测来源和增长的不确定度。
- `shared/`: 保持不变。

## Proposed Behavior Change

- 服务端不再把最后一个 `ServerResponse` 当作无限期的新鲜定位重复发送。
- 服务端在具有绝对坐标起点和必要传感器时，以 500 ms 周期产生新估算；实时定位到达时校正估算，实时定位停止时继续预测。
- 应用重启或定位采集重新启动时可从最后持久位置恢复，不要求启动后先获得新的卫星定位。
- 从未存在任何有效应用/系统缓存且没有实时定位时，服务端保持等待位置状态。

## Risks And Compatibility

- 消费级手机 IMU 偏置会快速累积；无限期输出仅保证连续性，不保证有用精度。
- 手机固定方式、震动、磁场和系统虚拟传感器实现会影响结果。
- 新增 NDK/CMake 与递归 Git submodule 构建要求；发布工作流已使用 `submodules: recursive`。
- `mad-location-manager-lib`、Eigen 与 GeographicLib 的源代码和许可必须随固定提交保留；不得在 Gradle/CMake 构建时联网获取。
- `provider="dead_reckoning"` 是现有字符串字段的新取值，不改变 Protobuf 编码，旧客户端仍可接收。

## Acceptance Criteria

- 有有效起点且手机具备必要传感器时，停止 GNSS/Fused 更新超过 500 ms 后，服务端仍持续发送时间戳递增的预测位置。
- 预测持续时间没有上限；水平 `accuracy` 在两次真实校正之间单调增大。
- 室内启动且没有新定位时，服务端使用应用缓存与系统缓存中更新的有效真实位置初始化并开始预测。
- 没有任何有效起点时不发送虚构经纬度；首次获得实时定位后自动开始融合和预测。
- 新实时定位到达后滤波器校正，预测误差计时重新开始，后续输出连续恢复。
- 缺少 `TYPE_LINEAR_ACCELERATION` 或 `TYPE_ROTATION_VECTOR` 时显示不支持惯性辅助；实时定位仍可转发，失锁后不生成预测。
- 客户端无需修改即可显示并注入每个新时间戳位置。
- `./gradlew assembleDebug lintDebug` 构建通过；在目标手机/平板上完成人工连续性验收。

## Open Questions

- None.

## Validation Evidence

- Automated: existing Gradle build/lint, recursive submodule, native ABI and diff checks passed as recorded by the linked delta.
- Human: passed; the user confirmed the existing specification and its target-device acceptance behavior were manually reviewed on 2026-09-19.
- Lifecycle: accepted and merged into `.supermax/specs/inertial-location-estimation/spec.md`; this workspace is retained as change evidence.

## Source Trace

- 用户确认：只使用手机内置硬件；免费开源且只提供定位；允许无上限误差和无限期预测；最后可用真实位置可作为无信号启动起点；接受 500 ms 输出、`dead_reckoning` provider、增长精度和缺传感器失败行为。
- 当前采集和发送：`server-app/src/main/java/dezz/gnssshare/server/GNSSServerService.java`。
- 当前客户端按时间戳注入：`client-app/src/main/java/dezz/gnssshare/client/GNSSClientService.java`。
- 线协议：`proto/location.proto`。
- 上游滤波器：https://github.com/maddevsio/mad-location-manager-lib ，评估提交 `5e426f6a0893e26cb0912437e753f92e2dad5a63`。
