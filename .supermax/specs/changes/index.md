# Specification Changes Index

Purpose: route Agents to proposed, accepted, rejected, or superseded behavior changes. Read lifecycle frontmatter before using a change as current behavior.

## Draft

- [[complete-post-init-spec-gaps/proposal|补齐 init supermax 后的行为边界规格]] — 补充蓝牙畸形会话恢复、惯性姿态样本新鲜度、损坏 PID 标记和直接启动失败回退；仅完成代码证据提取，人工审核前不修改稳定规格。

## Accepted

- [[replace-wifi-with-bluetooth/proposal|将 GNSS 传输从 Wi-Fi/TCP 替换为一对一蓝牙]] — 2026-09-19 人工审核通过，已合并到 [[../gnss-bluetooth-sharing/spec|稳定蓝牙共享规格]]。
- [[add-inertial-dead-reckoning/proposal|增加 GNSS 失锁后的惯性航位推算]] — 2026-09-19 人工审核通过，已合并到 [[../inertial-location-estimation/spec|稳定惯性连续定位规格]]。
- [[add-service-controls/proposal|为客户端和服务端增加快捷设置与界面服务开关]] — 2026-09-19 人工审核通过，已合并到 [[../service-controls/spec|稳定服务控制规格]]。

## Rejected Or Superseded

- None.
