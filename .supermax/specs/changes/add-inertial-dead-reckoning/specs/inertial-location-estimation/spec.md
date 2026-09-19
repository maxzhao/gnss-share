---
title: GNSS 与手机惯性传感器连续定位规格
created: 2026-09-13
updated: 2026-09-13
type: source
doc_role: spec
authority: proposed
status: draft
accepted_at:
merged_to: []
validation:
  automated: passed
  human: not-run
archive_state:
change_id: add-inertial-dead-reckoning
capability: inertial-location-estimation
sources:
  - User decisions confirmed in chat on 2026-09-13
  - .supermax/specs/changes/add-inertial-dead-reckoning/proposal.md
  - .supermax/specs/changes/add-inertial-dead-reckoning/design.md
  - server-app/src/main/java/dezz/gnssshare/server/GNSSServerService.java
  - client-app/src/main/java/dezz/gnssshare/client/GNSSClientService.java
  - proto/location.proto
confidence: high
---

# Delta Spec: inertial-location-estimation

## Capability Boundary

本规格定义手机 `server-app` 如何把真实 GNSS/Fused 位置与手机内置惯性传感器融合，并在真实定位停止后持续产生绝对位置。蓝牙传输、客户端模拟定位、日志导出和 Protobuf 编码不属于本规格。

## ADDED Requirements

### Requirement: 使用开源滤波核心

系统 SHALL 使用固定提交 `5e426f6a0893e26cb0912437e753f92e2dad5a63` 的 MIT `maddevsio/mad-location-manager-lib` 作为 GPS/加速度卡尔曼预测与校正核心。系统 SHALL 通过 server-only NDK/CMake/JNI 集成固定源码及递归 Eigen/GeographicLib 依赖，构建时 SHALL NOT 联网获取依赖。

#### Scenario: 构建原生滤波器

- GIVEN 主仓库与递归 submodule 已检出
- WHEN 构建 `server-app`
- THEN CMake 编译固定的上游滤波核心与最小 JNI 包装
- AND `client-app` 与 `shared` 不引入原生依赖

#### Scenario: 依赖未检出

- GIVEN 必需的固定 submodule 内容缺失
- WHEN 配置原生构建
- THEN 构建以明确错误失败
- AND 构建脚本不执行网络 clone/download

### Requirement: 真实位置作为加权校正

系统 SHALL 将每个经纬度有限且范围有效的实时 GNSS/Fused `Location` 送入同一滤波会话校正。系统 SHALL 使用 Android 报告的水平和速度精度平方作为测量方差；缺失或非正数时 SHALL 分别使用 `8.0` 和 `0.1`。当 speed 或 bearing 缺失时，系统 SHALL 保留当前估算速度/航向并使用 `1.0e12` 的弱速度测量方差，而不是把位置解释为高可信静止。系统 SHALL 使用 `0.3` 作为加速度方差。系统 SHALL NOT 使用定位精度阈值丢弃“差 GPS”。

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

系统 SHALL 通过一次 `SharedPreferences.Editor.apply()` 事务持久保存非 mock 且坐标有效的实时 GNSS/Fused 位置及其时间戳、provider、坐标、高度、水平精度、速度、方位和速度精度。首次有效实时回调 SHALL 无条件替换缓存起点；后续实时回调 SHALL 优先通过 `elapsedRealtimeNanos` 排序，重复或更旧回调 SHALL NOT 重置预测。异步 Fused 缓存回调 SHALL NOT 覆盖已经收到的实时位置。每次位置采集启动时，系统 SHALL 从该应用缓存、所有 Android `LocationManager` provider（包括当前禁用 provider）的 last-known location，以及启用且可用的 Fused last location 中选择时间戳最新的非 mock 有效真实位置作为起点，不设置最大年龄。预测位置与 mock-provider 位置 SHALL NOT 被保存或选为真实起点。

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

系统 SHALL 在采集活动期间注册 `TYPE_LINEAR_ACCELERATION` 和 `TYPE_ROTATION_VECTOR`，使用旋转四元数把设备坐标线性加速度转换为磁北 ENU，再根据最新真实位置和时间通过 Android `GeomagneticField` 校正为真北 ENU，并将 ENU 加速度及 monotonic timestamp 输入滤波器。原生会话的校正、预测、读取和销毁 SHALL 串行执行；不大于最近原生校正/预测时间的排队传感器事件 SHALL 被丢弃。

#### Scenario: 必要传感器可用

- GIVEN 两种必要传感器存在且注册成功
- AND 滤波器已有真实起点
- WHEN 线性加速度事件在有效旋转样本后到达
- THEN 系统将其转换为 ENU
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
- AND 客户端现有新时间戳规则可持续注入这些位置

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
- AND 从应用/系统最新有效真实位置重新初始化
- AND 不把停止前的预测位置当作真实起点

### Requirement: 原生失败安全降级

原生库加载、创建、预测或估算失败时，系统 SHALL 记录错误、显示本次采集会话惯性辅助不可用并继续处理实时定位。系统 SHALL NOT 因原生失败终止前台服务，也 SHALL NOT 发送非有限坐标。

#### Scenario: 原生估算无效

- GIVEN 原生估算返回非有限坐标或调用失败
- WHEN 服务端准备发布预测
- THEN 丢弃该预测
- AND 保留实时定位转发能力
- AND 用户可见状态指出惯性辅助不可用

## MODIFIED Requirements

### Requirement: 服务端定位发送语义

服务端 SHALL 发送滤波后的实时校正位置和按本规格生成的预测位置，而不是在没有新定位时把最后一个真实 `ServerResponse` 无限重复解释为新鲜位置。现有 4 字节长度帧、`LocationProto.ServerResponse`、客户端解析与 Mock Location 新时间戳过滤 SHALL 保持不变。

#### Scenario: 旧客户端接收预测

- GIVEN 客户端实现当前 Protobuf 和帧协议
- WHEN 服务端发送 `provider=dead_reckoning` 的预测 `LocationUpdate`
- THEN 客户端无需协议升级即可解析、显示并按新时间戳注入

## REMOVED Requirements

None。

## Acceptance And Validation

- Automated: recursive submodules present；`ANDROID_HOME="$HOME/Android/Sdk" ANDROID_SDK_ROOT="$HOME/Android/Sdk" ./gradlew assembleDebug lintDebug` passed on 2026-09-13；`git --no-pager diff --check` passed。
- Static: `proto/location.proto`、`client-app` 和 Bluetooth framing unchanged；CMake contains no network fetch。
- Human: target phone/tablet validates live correction, GNSS-loss continuous predictions, monotonically increasing timestamps/accuracy, indoor restart from cached anchor, reacquisition, no-anchor wait, 15-second lifecycle and unsupported-sensor fallback where possible。
- Before automated and human evidence pass, this spec SHALL remain `authority: proposed`, `status: draft` and SHALL NOT merge into stable specifications。

## Open Questions

- None。

## Source Trace

- User decisions confirmed in chat on 2026-09-13。
- Proposal: `.supermax/specs/changes/add-inertial-dead-reckoning/proposal.md`。
- Design: `.supermax/specs/changes/add-inertial-dead-reckoning/design.md`。
- Existing server: `server-app/src/main/java/dezz/gnssshare/server/GNSSServerService.java`。
- Existing client timestamp filtering: `client-app/src/main/java/dezz/gnssshare/client/GNSSClientService.java`。
- Existing schema: `proto/location.proto`。
- Upstream source and Android integration guidance: https://github.com/maddevsio/mad-location-manager-lib 。
