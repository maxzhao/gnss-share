---
title: GNSS 与手机惯性传感器连续定位规格
created: 2026-09-19
updated: 2026-09-19
type: source
doc_role: spec
authority: normative
status: active
sources:
  - .supermax/specs/changes/add-inertial-dead-reckoning/specs/inertial-location-estimation/spec.md
  - git:16107491dcee38250dcd8130b6b57f0311111fd5
confidence: high
taskadmin_tag: master
taskadmin_id: 1
---

# GNSS 与手机惯性传感器连续定位规格

> **TLDR**：手机服务端使用最后有效真实位置、GNSS/Fused 校正和内置惯性传感器持续生成带明确来源与递增误差的绝对位置，并在能力缺失或原生失败时安全降级。

## Purpose

本规格定义 `server-app` 如何融合真实 GNSS/Fused 位置与手机内置惯性传感器，并在真实定位停止后持续产生绝对位置。蓝牙传输、客户端模拟定位、日志导出和 Protobuf 编码由其他能力负责。

## Scope

### In Scope

- 固定源码的 GPS/加速度卡尔曼滤波核心及 server-only JNI 集成。
- 实时真实位置的加权校正和最后有效真实位置持久化。
- 应用缓存、系统 last-known location 与 Fused last location 起点选择。
- 线性加速度和旋转矢量到真北 ENU 的转换。
- 每 500 ms 的无上限预测、误差增长和元数据语义。
- 授权客户端连接、15 秒延迟停止、wake lock 和失败降级。

### Out Of Scope

- 商业 SDK、Token、地图匹配、道路约束或误差上限承诺。
- 车辆里程计、OBD、外置 IMU、摄像头、指纹或云端定位。
- 缺少真实绝对起点时虚构经纬度。
- 传感器调参 UI、模式切换、蓝牙传输或客户端注入规则变更。
- `proto/location.proto` 变更。

## Actors And Triggers

- 授权蓝牙客户端：连接时启动采集，断开后触发延迟停止。
- Android GNSS/Fused providers：提供真实位置校正和缓存候选。
- 手机惯性传感器：在有效旋转姿态基础上提供预测输入。
- `GNSSServerService`：管理采集生命周期并发送实时校正或预测位置。
- 触发：授权连接、位置采集启动、实时位置到达、传感器事件、500 ms ticker、断开超时和服务销毁。

## Requirements

### Requirement: 使用固定开源滤波核心

系统 SHALL 使用固定提交 `5e426f6a0893e26cb0912437e753f92e2dad5a63` 的 MIT `maddevsio/mad-location-manager-lib` 作为 GPS/加速度卡尔曼预测与校正核心。系统 SHALL 通过 server-only NDK/CMake/JNI 集成固定源码及递归 Eigen/GeographicLib 依赖，构建时 SHALL NOT 联网获取依赖。

#### Scenario: 构建原生滤波器

- GIVEN 主仓库与递归 submodule 已检出
- WHEN 构建 `server-app`
- THEN CMake 编译固定上游滤波核心与最小 JNI 包装
- AND `client-app` 与 `shared` 不引入原生依赖

#### Scenario: 依赖未检出

- GIVEN 必需的固定 submodule 内容缺失
- WHEN 配置原生构建
- THEN 构建以明确错误失败
- AND 构建脚本不执行网络 clone 或 download

### Requirement: 真实位置作为加权校正

系统 SHALL 将每个经纬度有限且范围有效的实时 GNSS/Fused `Location` 送入同一滤波会话校正。系统 SHALL 使用 Android 报告的水平和速度精度平方作为测量方差；缺失或非正数时 SHALL 分别使用 `8.0` 和 `0.1`。当 speed 或 bearing 缺失时，系统 SHALL 保留当前估算速度或航向并使用 `1.0e12` 的弱速度测量方差，而不是把位置解释为高可信静止。系统 SHALL 使用 `0.3` 作为加速度方差，且 SHALL NOT 使用定位精度阈值丢弃“差 GPS”。

#### Scenario: 高精度实时位置

- GIVEN 滤波器已初始化
- WHEN 收到带较小正精度的有效实时位置
- THEN 系统以较高测量权重校正当前估算
- AND 立即输出使用原 provider 与原时间戳的校正位置

#### Scenario: 低精度实时位置

- GIVEN 滤波器已初始化
- WHEN 收到带较大正精度的有效实时位置
- THEN 系统仍执行校正但使用较低测量权重
- AND 不因跨越任意硬阈值切换模式或丢弃位置

### Requirement: 使用最后有效真实位置启动

系统 SHALL 通过一次 `SharedPreferences.Editor.apply()` 事务持久保存非 mock 且坐标有效的实时 GNSS/Fused 位置及其时间戳、provider、坐标、高度、水平精度、速度、方位和速度精度。首次有效实时回调 SHALL 无条件替换缓存起点；后续实时回调 SHALL 优先通过 `elapsedRealtimeNanos` 排序，重复或更旧回调 SHALL NOT 重置预测。异步 Fused 缓存回调 SHALL NOT 覆盖已经收到的实时位置。每次位置采集启动时，系统 SHALL 从应用缓存、所有 Android `LocationManager` provider（包括当前禁用 provider）的 last-known location，以及启用且可用的 Fused last location 中选择时间戳最新的非 mock 有效真实位置作为起点，不设置最大年龄。预测位置与 mock-provider 位置 SHALL NOT 被保存或选为真实起点。

#### Scenario: 室内启动且应用缓存可用

- GIVEN 服务端曾持久保存有效实时位置
- AND 当前没有新的 GNSS/Fused 回调
- WHEN 授权客户端连接并启动位置采集
- THEN 系统使用缓存位置初始化绝对坐标
- AND 使用当前 monotonic time 初始化滤波时间
- AND 在传感器可用时开始预测

#### Scenario: 系统缓存更新

- GIVEN 应用缓存和一个系统 last-known location 都有效
- WHEN 系统缓存时间戳更新
- THEN 系统使用系统缓存作为起点

#### Scenario: 没有任何起点

- GIVEN 应用缓存、系统缓存和实时位置都不存在或无效
- WHEN 位置采集启动
- THEN 系统显示等待初始位置
- AND 不发送虚构的绝对经纬度
- AND 首个有效实时位置到达后自动初始化

### Requirement: 使用手机惯性传感器预测

系统 SHALL 在采集活动期间注册 `TYPE_LINEAR_ACCELERATION` 和 `TYPE_ROTATION_VECTOR`，使用旋转四元数把设备坐标线性加速度转换为磁北 ENU，再根据最新真实位置和时间通过 Android `GeomagneticField` 校正为真北 ENU，并将 ENU 加速度及 monotonic timestamp 输入滤波器。原生会话的校正、预测、读取和销毁 SHALL 串行执行；不大于最近原生校正或预测时间的排队传感器事件 SHALL 被丢弃。

#### Scenario: 必要传感器可用

- GIVEN 两种必要传感器存在且注册成功
- AND 滤波器已有真实起点
- WHEN 线性加速度事件在有效旋转样本后到达
- THEN 系统将其转换为真北 ENU
- AND 更新同一滤波会话的预测状态

#### Scenario: 必要传感器不可用

- GIVEN 任一必要传感器缺失或注册失败
- WHEN 位置采集启动
- THEN 系统明确显示惯性辅助不支持
- AND 继续转发后续有效实时位置
- AND 实时位置停止后不生成带新时间戳的预测位置

### Requirement: 每 500 ms 无限期发布预测

滤波器已初始化且惯性传感器可用时，系统 SHALL 每 500 ms 发布当前预测位置。只要授权客户端仍连接且采集未停止，该发布 SHALL 无时长上限。

#### Scenario: GNSS/Fused 停止更新

- GIVEN 滤波器已有真实起点且传感器可用
- WHEN GNSS/Fused 不再产生位置回调
- THEN 系统继续每 500 ms 发布预测位置
- AND 每个位置具有新的当前 wall-clock timestamp
- AND `provider` 为 `dead_reckoning`
- AND 客户端可按现有新时间戳规则持续注入这些位置

#### Scenario: 长时间无信号

- GIVEN 授权客户端保持连接
- WHEN 真实定位长期不可用
- THEN 系统不因持续时间停止预测
- AND 允许位置误差持续累积

### Requirement: 预测位置元数据明确

预测位置 SHALL 使用滤波器纬度、经度、速度和归一化到 `[0,360)` 的 Android bearing，沿用最后有效真实高度，设置 `location_age=0` 并保留当前卫星数。预测 `accuracy` SHALL 在真实校正之间有限且单调不减，并按以下公式计算：

`sqrt(baseAccuracy² + speedSigma²*t² + 0.25*0.3*t⁴)`

其中 `t` 是起点在校正时的非负 wall-clock 年龄加上最近真实校正后的 monotonic 秒数；`baseAccuracy` 是最近正水平精度或 `sqrt(8.0)`；`speedSigma` 是最近正速度精度或 `sqrt(0.1)`。超过 float 范围时 SHALL 限制为 `Float.MAX_VALUE`。

#### Scenario: 预测误差增长

- GIVEN 最近一次真实校正后的 `baseAccuracy` 已确定
- WHEN 连续发布多个预测位置且没有新真实校正
- THEN 后一个位置的 `accuracy` 不小于前一个
- AND 新真实校正到达时误差基准和计时重新开始

### Requirement: 断线延迟不是预测上限

系统 SHALL 仅在授权客户端连接期间运行位置与传感器采集。采集活动期间 SHALL 持有 `PARTIAL_WAKE_LOCK`，确保锁屏时 CPU 可继续处理传感器和 500 ms ticker。客户端断开后 SHALL 沿用 15 秒延迟停止；延迟期间重连 SHALL 继续当前会话，超过 15 秒 SHALL 停止 ticker、定位与传感器、销毁原生会话并释放 wake lock，但保留持久真实位置。启动失败与服务销毁也 SHALL 释放 wake lock。

#### Scenario: 保持连接时长期失锁

- GIVEN 客户端保持授权连接
- WHEN GNSS 长期失锁超过 15 秒
- THEN 系统仍持续预测

#### Scenario: 断开超过 15 秒后重连

- GIVEN 客户端断开超过 15 秒且采集已停止
- WHEN 客户端稍后重新连接
- THEN 系统创建新滤波会话
- AND 从应用或系统最新有效真实位置重新初始化
- AND 不把停止前的预测位置当作真实起点

### Requirement: 原生失败安全降级

原生库加载、创建、预测或估算失败时，系统 SHALL 记录错误、显示本次采集会话惯性辅助不可用并继续处理实时定位。系统 SHALL NOT 因原生失败终止前台服务，也 SHALL NOT 发送非有限坐标。

#### Scenario: 原生估算无效

- GIVEN 原生估算返回非有限坐标或调用失败
- WHEN 服务端准备发布预测
- THEN 丢弃该预测
- AND 保留实时定位转发能力
- AND 用户可见状态指出惯性辅助不可用

### Requirement: 服务端发送校正与预测位置

服务端 SHALL 发送滤波后的实时校正位置和按本规格生成的预测位置，而不是在没有新定位时把最后一个真实 `ServerResponse` 无限重复解释为新鲜位置。现有 4 字节长度帧、`LocationProto.ServerResponse`、客户端解析与 Mock Location 新时间戳过滤 SHALL 保持不变。

#### Scenario: 现有客户端接收预测

- GIVEN 客户端实现当前 Protobuf 和帧协议
- WHEN 服务端发送 `provider=dead_reckoning` 的预测 `LocationUpdate`
- THEN 客户端无需协议升级即可解析、显示并按新时间戳注入

## Edge Cases And Failure Behavior

- 没有任何有效绝对起点时不得生成预测经纬度。
- 缺少必要传感器或原生滤波失败时继续转发有效真实位置。
- 重复、过旧、mock 或非有限真实位置不得污染持久起点或倒退滤波时间。
- 断开延迟结束、启动失败和服务销毁必须释放采集资源与 wake lock。

## Data / Entity Constraints

- 持久起点必须是非 mock、经纬度有限且范围有效的真实位置。
- 预测位置不得写入真实位置缓存。
- 滤波原生句柄仅由串行化的校正、预测、估算和销毁路径访问。
- 固定依赖提交、NDK `28.2.13676358` 与 CMake `3.22.1` 构成可复现构建约束。

## Behavior Coverage

| Behavior Surface | Normal Behavior | Authorization / Actors | State / Data Effects | Failure / Edge Cases | External Dependencies | Concurrency / Idempotency | Validation |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 真实位置校正 | covered | system provider | covered | covered | GNSS/Fused | covered | automated/human passed |
| 缓存起点恢复 | covered | service | covered | covered | Android location cache | covered | automated/human passed |
| 惯性预测 | covered | authorized client lifecycle | covered | covered | sensors/native filter | covered | automated/human passed |
| 资源生命周期 | covered | authorized client | covered | covered | foreground service/wake lock | covered | automated/human passed |

## Coverage Gaps

- 旋转姿态样本的精确新鲜度边界由 `.supermax/specs/changes/complete-post-init-spec-gaps/` 作为待审核增量维护。

## Acceptance Criteria

- 有起点且传感器可用时，失去真实位置后仍每 500 ms 输出新时间戳预测。
- 预测可持续且 `accuracy` 在校正之间单调不减。
- 可从最新应用或系统真实缓存恢复；没有起点时保持等待。
- 真实位置恢复时校正同一会话并重置预测误差基准。
- 缺少传感器或原生失败时不伪造预测，实时位置仍可用。
- 断开 15 秒后的资源停止不限制保持连接时的预测时长。
- 现有客户端和 `proto/location.proto` 无需升级。

## Validation Plan

- Automated: recursive submodules present；`ANDROID_HOME="$HOME/Android/Sdk" ANDROID_SDK_ROOT="$HOME/Android/Sdk" ./gradlew assembleDebug lintDebug`。
- Static: CMake 无网络获取、原生 ABI/JNI 符号、协议和客户端 no-diff、`git --no-pager diff --check`。
- Human: 实时校正、失锁预测、时间戳与误差增长、缓存启动、重获、无起点、15 秒生命周期和缺传感器降级。
- Review state: existing delta manually approved by the user on 2026-09-19.

## Assumptions And Open Questions

- Assumption: 消费级手机 IMU 预测仅保证连续性，不承诺可用精度或误差上限。
- Open question: None recorded.

## Source Trace

- Accepted change: `.supermax/specs/changes/add-inertial-dead-reckoning/`。
- Server integration: `server-app/src/main/java/dezz/gnssshare/server/GNSSServerService.java`。
- Estimator: `server-app/src/main/java/dezz/gnssshare/server/DeadReckoningEstimator.java`。
- JNI: `server-app/src/main/java/dezz/gnssshare/server/DeadReckoningNative.java`、`server-app/src/main/cpp/dead_reckoning_jni.cpp`。
- Persistent state: `server-app/src/main/java/dezz/gnssshare/server/Preferences.java`。
- Native build: `server-app/src/main/cpp/CMakeLists.txt`、`server-app/build.gradle`、`.gitmodules`。
- Wire schema: `proto/location.proto`。
- TaskAdmin task: `master/1`。