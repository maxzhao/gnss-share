---
title: 手机到平板的一对一蓝牙 GNSS 共享规格
created: 2026-09-12
updated: 2026-09-12
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
change_id: replace-wifi-with-bluetooth
capability: gnss-bluetooth-sharing
sources:
  - User decisions confirmed in chat on 2026-09-12
  - .supermax/specs/changes/replace-wifi-with-bluetooth/proposal.md
  - .supermax/specs/changes/replace-wifi-with-bluetooth/design.md
  - proto/location.proto
confidence: high
---

# Delta Spec: gnss-bluetooth-sharing

## Capability Boundary

本规格定义手机 `server-app` 向无 GNSS 信号的平板 `client-app` 共享定位时的蓝牙传输、设备绑定、连接生命周期和用户可见失败行为。GNSS 采集、Protobuf 字段语义、模拟定位、日志导出和静态抖动保持现状。

## ADDED Requirements

### Requirement: 唯一传输为安全经典蓝牙 RFCOMM

系统 SHALL 仅通过安全 Bluetooth Classic RFCOMM 在手机和平板之间传输 GNSS 数据，服务名 SHALL 为 `GNSS Share Location`，UUID SHALL 为 `a8e7f1b0-3d4c-4f29-9e6a-7b8c0d1e2f30`。

#### Scenario: 已配置设备建立连接

- GIVEN 手机和平板支持经典蓝牙、已在系统中配对并在双方应用中保存对端
- AND 两端蓝牙已开启且前台服务正在运行
- WHEN 平板连接固定 UUID
- THEN 手机通过安全 RFCOMM 接受该平板
- AND 两端不依赖 Wi-Fi、热点、IP 地址、TCP 端口或互联网

### Requirement: 双方保存唯一对端

手机 SHALL 保存且使用一个目标平板，平板 SHALL 保存且使用一个目标手机。选择新设备 SHALL 替换旧设备。应用 SHALL 只列出系统已配对设备，不扫描或发起配对。

#### Scenario: 首次绑定

- GIVEN 用户已在系统蓝牙设置中配对手机和平板
- WHEN 用户在手机应用选择该平板，并在平板应用选择该手机
- THEN 双方持久保存所选设备名称和蓝牙地址
- AND 后续启动无需重新选择

#### Scenario: 更换目标

- GIVEN 应用已保存一个目标设备
- WHEN 用户从已配对列表明确选择另一个设备
- THEN 新设备原子替换旧设备
- AND 针对旧设备的活动连接被关闭

### Requirement: 手机实施设备级准入和单连接

手机 SHALL 在发送任何数据或启动定位采集前，将已接受套接字的远端蓝牙地址与保存的平板地址比较。地址不一致 SHALL 立即关闭。手机 SHALL 同时最多保持一个授权平板会话。

#### Scenario: 未授权平板尝试连接

- GIVEN 另一台平板已与手机完成系统配对，但不是手机应用保存的平板
- WHEN 它连接 GNSS RFCOMM UUID
- THEN 手机关闭连接
- AND 不向它发送状态或位置数据
- AND 不因它启动 GNSS 采集

#### Scenario: 已有授权会话

- GIVEN 保存的平板已经连接
- WHEN 任意设备尝试建立第二个会话
- THEN 手机不建立第二个活动 GNSS 会话

### Requirement: 手机应用启动服务

用户通过启动器打开 `server-app` 后，应用 SHALL 在所需权限和蓝牙开启条件满足后自动、幂等地启动 `GNSSServerService`，无需启动按钮。服务 SHALL 在没有连接时继续监听或等待配置。

#### Scenario: 正常启动

- GIVEN 手机已授权、蓝牙已开启并保存了仍处于配对状态的平板
- WHEN 用户打开 `server-app`
- THEN GNSS 前台服务自动启动
- AND RFCOMM 服务开始等待该平板连接

#### Scenario: 尚未选择平板

- GIVEN 手机未保存目标平板
- WHEN 用户打开 `server-app`
- THEN 前台服务在满足平台权限后启动但不接受 GNSS 会话
- AND UI 明确要求用户选择已配对平板

### Requirement: 平板应用自动连接和持续重试

用户通过启动器打开 `client-app` 后，应用 SHALL 在所需权限和蓝牙开启条件满足后自动、幂等地启动 `GNSSClientService`。服务 SHALL 只连接保存的手机，并在目标仍配对、蓝牙开启且服务运行时对失败或断开的连接持续重试。

#### Scenario: 正常自动连接

- GIVEN 平板已保存并仍配对目标手机，且手机 RFCOMM 服务可用
- WHEN 用户打开 `client-app`
- THEN 平板无需连接按钮即建立会话
- AND 接收定位并继续现有模拟定位行为

#### Scenario: 手机暂时不可达

- GIVEN 平板服务正在运行且目标手机仍为已配对设备
- WHEN 手机关机、超出范围或 RFCOMM 服务暂不可用
- THEN 平板保持断开/重连中的可见状态
- AND 以单一重试循环持续连接同一手机
- AND 手机恢复可达后自动连接

### Requirement: 绑定失效不自动改绑

若保存的目标不再出现在系统已配对设备中，应用 SHALL 保留保存值、停止监听或连接该会话并提示重新配对或明确选择替代设备。应用 SHALL NOT 自动选择其他已配对设备。

#### Scenario: 目标被取消配对

- GIVEN 应用已保存目标设备
- WHEN 该设备被系统取消配对
- THEN UI 显示目标不可用
- AND 保存的名称和地址保持不变
- AND 不连接已配对列表中的其他设备

### Requirement: 蓝牙关闭时由用户确认开启

任一应用在前台打开且发现蓝牙关闭时 SHALL 发起 Android 系统蓝牙开启请求，不得尝试静默开启。用户拒绝后 SHALL 显示错误并停止监听/连接尝试，直至蓝牙开启。

#### Scenario: 用户同意开启

- GIVEN 蓝牙关闭
- WHEN 用户打开应用并接受系统开启请求
- THEN 应用在蓝牙开启后自动继续启动服务及监听/连接流程

#### Scenario: 用户拒绝开启

- GIVEN 蓝牙关闭
- WHEN 用户拒绝系统开启请求
- THEN 应用显示蓝牙必需错误
- AND 不建立或重试 RFCOMM 连接

### Requirement: 权限行为明确

Android 12 及以上版本的双方应用 SHALL 请求 `BLUETOOTH_CONNECT` 运行时权限。拒绝权限 SHALL 显示错误且不访问需要该权限的蓝牙 API。应用 SHALL NOT 因本功能请求 `BLUETOOTH_SCAN`。现有位置和模拟定位权限行为 SHALL 保留。

#### Scenario: 首次授权

- GIVEN 应用尚无 `BLUETOOTH_CONNECT` 权限
- WHEN 用户打开应用
- THEN 应用请求权限
- AND 授权后无需额外启动操作即可继续正常流程

### Requirement: 后台、锁屏和重启生命周期

用户打开应用并成功启动服务后，服务 SHALL 作为前台服务在 Activity 离开前台、设备锁屏或应用从最近任务划掉时继续运行。系统强行停止应用 SHALL 停止服务。设备重启后服务 SHALL NOT 自动启动，直至用户再次打开应用。

#### Scenario: 从最近任务划掉

- GIVEN 服务已运行且连接已建立
- WHEN 用户锁屏或从最近任务划掉应用
- THEN 前台服务继续运行
- AND 连接及 GNSS 共享继续

#### Scenario: 系统强行停止

- GIVEN 服务正在运行
- WHEN 用户通过 Android 系统强行停止应用或等效系统级停止入口终止应用
- THEN 服务和连接停止
- AND 再次打开应用后服务重新自动启动

#### Scenario: 设备重启

- GIVEN 应用在重启前运行
- WHEN 手机或平板完成重启但用户尚未打开对应应用
- THEN 对应 GNSS 服务不运行

### Requirement: 保留流协议和定位语义

手机 SHALL 对每个 `LocationProto.ServerResponse` 发送 4 字节大端长度和序列化 payload。平板 SHALL 按该帧格式读取。平板 SHALL 每秒发送 `0x01` 心跳；3 秒无预期活动的会话 SHALL 被关闭并进入正常恢复流程。`proto/location.proto` 及现有 GNSS/Fused Location、状态、卫星数、模拟定位新时间戳过滤、日志导出和静态抖动行为 SHALL 保持不变。

#### Scenario: 接收定位

- GIVEN 授权 RFCOMM 会话已建立
- WHEN 手机产生现有 `ServerResponse`
- THEN 平板按长度帧解析相同 Protobuf 消息
- AND 以现有规则显示并发布模拟位置

### Requirement: 用户界面只暴露必要控制

手机 SHALL 提供选择/更换唯一平板的入口；平板 SHALL 提供选择/更换唯一手机的入口。双方 SHALL 显示目标设备和连接/错误状态。双方 SHALL NOT 提供应用内启动、停止或连接按钮。

#### Scenario: 已完成设置

- GIVEN 用户已保存目标设备
- WHEN 用户打开应用
- THEN UI 显示目标设备和当前状态
- AND 服务及连接流程自动执行

## REMOVED Requirements

### Requirement: Wi-Fi/TCP GNSS 传输

Reason: 产品唯一传输改为经典蓝牙 RFCOMM；删除热点、网关 IP、手动 IP、端口 `8887`、网络回调和相关权限/UI，不保留回退。

### Requirement: 手动服务启停和开机自启

Reason: 服务由用户打开应用触发并持续运行；删除双方启停按钮、通知停止动作、启动偏好和 boot receivers。

### Requirement: 手机旧蓝牙触发设备自动启停

Reason: 旧功能只用任意已登记 ACL 连接控制 TCP 服务，与新的一对一 RFCOMM 对端授权冲突；删除多设备触发设置、`BluetoothReceiver` 和自动停止逻辑。

## Acceptance And Validation

- Automated: `ANDROID_HOME="$HOME/Android/Sdk" ANDROID_SDK_ROOT="$HOME/Android/Sdk" ./gradlew clean assembleDebug lintDebug` passed on 2026-09-12；`git --no-pager diff --check` and removed-feature reference scan also passed。
- Independent code review: no remaining high/medium-severity implementation findings。
- Human: not run；在目标手机和平板完成一次端到端检查，覆盖配对与双方选择、自动启动连接、定位接收、后台/锁屏、断开恢复、错误平板拒绝、蓝牙关闭提示、取消配对保留目标和重启不启动。
- 在人工验证通过前，本规格保持 `authority: proposed`、`status: draft`，不得合并为稳定规范。

## Open Questions

- None。

## Source Trace

- User decisions confirmed in chat on 2026-09-12。
- Proposal: `.supermax/specs/changes/replace-wifi-with-bluetooth/proposal.md`。
- Design: `.supermax/specs/changes/replace-wifi-with-bluetooth/design.md`。
- Existing wire schema: `proto/location.proto`。
- Existing server behavior: `server-app/src/main/java/dezz/gnssshare/server/GNSSServerService.java`。
- Existing client behavior: `client-app/src/main/java/dezz/gnssshare/client/ConnectionManager.java`、`GNSSClientService.java`。
