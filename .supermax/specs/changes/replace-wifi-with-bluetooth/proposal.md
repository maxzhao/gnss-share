---
title: 将 GNSS 传输从 Wi-Fi/TCP 替换为一对一蓝牙
created: 2026-09-12
updated: 2026-09-12
doc_role: change-proposal
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
  - server-app/src/main/java/dezz/gnssshare/server/GNSSServerService.java
  - client-app/src/main/java/dezz/gnssshare/client/ConnectionManager.java
  - client-app/src/main/java/dezz/gnssshare/client/GNSSClientService.java
  - proto/location.proto
confidence: high
---

# Proposal: replace-wifi-with-bluetooth

## Intent

让无 GNSS 信号的 Android 平板通过经典蓝牙持续接收 Android 手机采集的定位数据，并在用户打开两端应用后自动运行、自动连接和断线重连。

## Scope

### In Scope

- 手机 `server-app` 使用安全的经典蓝牙 RFCOMM 监听并发送 GNSS 数据。
- 平板 `client-app` 连接用户保存的手机并发布现有模拟定位。
- 用户先在系统蓝牙设置中完成配对，再分别在手机和平板应用中选择唯一对端。
- 手机只接受保存的平板，且同时只允许一个连接。
- 两端应用打开后自动启动前台服务；后台、锁屏和从最近任务划掉应用后服务继续运行。
- 平板未连接时持续重试；连接断开后自动恢复。
- 蓝牙关闭时在应用前台请求用户开启；权限、绑定或配对异常必须显示明确状态。
- 删除 Wi-Fi/TCP、IP/端口配置、开机启动、手动启停及旧蓝牙触发启停行为。
- 保留 GNSS/Fused Location、Protobuf、模拟定位、日志导出和静态抖动行为。

### Non-Goals

- 不保留 Wi-Fi/TCP 回退、兼容模式或迁移阶段。
- 不使用 BLE，不支持同时连接多台平板或多台手机。
- 不在应用内扫描、发起系统配对或自动选择任意已配对设备。
- 不增加账号、互联网中继、应用层加密、协议版本协商或新依赖。
- 不在设备开机后自动启动；用户每次重启设备后必须至少打开一次对应应用。

## Affected Capabilities

- `server-app/`: 手机端设备绑定、前台服务、RFCOMM 服务端、GNSS 发送和 UI。
- `client-app/`: 平板端设备绑定、前台服务、RFCOMM 客户端、重连、模拟定位和 UI。
- `shared/`: 两端共同使用的 RFCOMM UUID 与流协议常量。
- `proto/location.proto`: 保持不变。
- `.supermax/AGENTS.md`: 实现和验收后更新稳定项目路由与范围。

## Proposed Behavior Change

- 唯一传输从 Wi-Fi 热点上的 TCP `:8887` 改为安全经典蓝牙 RFCOMM。
- 手机保存唯一平板地址，平板保存唯一手机地址；重新选择时替换旧值。
- 手机拒绝地址不匹配的设备，已连接时不接受第二个会话。
- 两端由应用启动触发服务，不再由按钮、开机广播或任意蓝牙 ACL 事件控制服务。
- 系统级强行停止仍可停止应用；应用内不提供停止入口。

## Risks And Compatibility

- 两台设备必须支持经典蓝牙 BR/EDR 和 RFCOMM；BLE-only 设备不支持。
- Android 不允许应用保证静默开启蓝牙，必须使用系统确认界面。
- RFCOMM 的对端身份是设备蓝牙地址；它能区分设备，不能区分同一平板上的不同应用。
- 真实后台稳定性受设备厂商电池策略影响，需在目标手机和平板上手工验收。
- 这是有意的不兼容替换；旧 IP、网关和蓝牙触发设备配置不迁移、不再生效。

## Acceptance Criteria

- 两端已配对并互相保存后，先打开手机应用、再打开平板应用，无需点击启动或连接按钮即可接收定位。
- 平板锁屏、手机锁屏或从最近任务划掉任一应用后，前台服务和连接继续工作。
- 连接因距离、蓝牙切换或进程重建而断开后，在服务仍运行且条件恢复时自动重连。
- 未保存的平板即使已与手机配对，也不能接收任何 GNSS 数据。
- 目标设备取消配对或不可用时，保存值不被替换，应用不连接其他设备，并提示重新配对或重新选择。
- 设备重启后两端服务均不自动启动；打开应用后才启动。
- 蓝牙关闭时打开应用会显示系统开启提示；拒绝后显示错误且不尝试连接。
- 两个 APK 均可通过 `./gradlew assembleDebug` 构建。

## Validation Evidence

- Automated: `ANDROID_HOME="$HOME/Android/Sdk" ANDROID_SDK_ROOT="$HOME/Android/Sdk" ./gradlew clean assembleDebug lintDebug` passed on 2026-09-12.
- Static: `git --no-pager diff --check` and removed Wi-Fi/TCP/boot/manual-control reference scan passed.
- Independent code review: no remaining high/medium-severity implementation findings.
- Human: not run; all target-device acceptance scenarios remain required before stable acceptance.

## Source Trace

- 用户于 2026-09-12 确认设备角色、一对一绑定、自动启动、后台持续、无开机启动、蓝牙开启提示、绑定失效处理、删除旧功能及保留定位行为。
- 当前 TCP 服务端：`server-app/src/main/java/dezz/gnssshare/server/GNSSServerService.java`。
- 当前 Wi-Fi/TCP 客户端：`client-app/src/main/java/dezz/gnssshare/client/ConnectionManager.java`、`GNSSClientService.java`。
- 当前旧蓝牙触发逻辑：`server-app/src/main/java/dezz/gnssshare/server/BluetoothReceiver.java`。
- Android 官方连接说明：https://developer.android.com/develop/connectivity/bluetooth/connect-bluetooth-devices
- Android 官方权限说明：https://developer.android.com/develop/connectivity/bluetooth/bt-permissions
