# Specification Changes Index

Purpose: route Agents to proposed, accepted, rejected, or superseded behavior changes. Read lifecycle frontmatter before using a change as current behavior.

## Draft

- [[add-inertial-dead-reckoning/proposal|增加 GNSS 失锁后的惯性航位推算]] — 服务端融合最新真实位置与手机惯性传感器，失锁后持续输出估算；实现与自动验证已完成，人工设备验证前不是稳定行为。
- [[replace-wifi-with-bluetooth/proposal|将 GNSS 传输从 Wi-Fi/TCP 替换为一对一蓝牙]] — 完整替换两端传输、自动启动/重连和唯一设备绑定；其“无应用内启停”行为已被服务控制提案部分取代；人工设备验证前不是稳定行为。
- [[add-service-controls/proposal|为客户端和服务端增加快捷设置与界面服务开关]] — 增加由服务生命周期原子 PID 标记同步且安全锁屏下启动需认证的 Quick Settings Tile 与应用内启停按钮，保留应用进入时自动启动；目标设备发现的陈旧 Tile 状态已修复并通过自动/静态验证，修复后设备复验前不是稳定行为。

## Accepted

- None.

## Rejected Or Superseded

- None.
