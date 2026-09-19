---
title: 惯性姿态样本时序边界增量规格
created: 2026-09-19
updated: 2026-09-19
doc_role: delta-spec
authority: proposed
status: draft
change_id: complete-post-init-spec-gaps
capability: inertial-location-estimation
merged_to: []
validation:
  automated: passed
  human: not-run
archive_state:
sources:
  - server-app/src/main/java/dezz/gnssshare/server/DeadReckoningEstimator.java
confidence: high
taskadmin_tag: master
taskadmin_id: 1
---

# Delta Spec: inertial-location-estimation

## ADDED Requirements

### Requirement: 线性加速度必须具有新鲜且时间一致的旋转姿态

系统 SHALL 仅在已有旋转矢量样本，且线性加速度事件时间戳不早于该旋转样本、两者相隔不超过 `100 ms` 时，把设备坐标加速度转换为 ENU 并输入原生预测。没有旋转样本、旋转样本来自加速度事件之后或旋转样本已超过 `100 ms` 时，系统 SHALL 丢弃该次线性加速度事件而不推进滤波器。事件转换后的 monotonic 时间仍 SHALL 严格大于最近一次原生校正或预测时间。

#### Scenario: 使用新鲜旋转姿态

- GIVEN 滤波器已初始化且两种传感器可用
- AND 已收到旋转矢量样本
- WHEN 线性加速度事件发生在该旋转样本之后且间隔不超过 `100 ms`
- AND 其 monotonic 时间晚于最近一次原生操作
- THEN 系统使用该姿态完成坐标转换
- AND 将转换后的 ENU 加速度送入同一原生会话

#### Scenario: 尚无旋转姿态

- GIVEN 滤波器已初始化
- AND 尚未收到任何旋转矢量样本
- WHEN 线性加速度事件到达
- THEN 系统丢弃该事件
- AND 不调用原生预测

#### Scenario: 旋转姿态过期

- GIVEN 最近旋转矢量样本早于当前线性加速度事件超过 `100 ms`
- WHEN 该线性加速度事件到达
- THEN 系统丢弃该事件
- AND 等待更新的旋转姿态

#### Scenario: 传感器事件时间倒序

- GIVEN 线性加速度事件时间早于当前旋转姿态，或其 monotonic 时间不晚于最近原生校正或预测
- WHEN 系统处理该事件
- THEN 丢弃该事件
- AND 原生滤波时间不得倒退

## MODIFIED Requirements

None。

## REMOVED Requirements

None。

## Acceptance And Validation

- Static: verify `MAX_ROTATION_AGE_NS = 100_000_000L` and all timestamp guards in `DeadReckoningEstimator.onSensorChanged()`.
- Automated: passed on 2026-09-19；`ANDROID_HOME="$HOME/Android/Sdk" ANDROID_SDK_ROOT="$HOME/Android/Sdk" ./gradlew assembleDebug lintDebug` completed with `BUILD SUCCESSFUL` and 146 actionable tasks。
- Human/review: confirm `100 ms` is an intended stable sensor-fusion boundary before merge.
- Until human review passes, this delta remains `status: draft` and SHALL NOT modify the stable owner.

## Source Trace

- Stable owner: `.supermax/specs/inertial-location-estimation/spec.md`。
- Proposal: `.supermax/specs/changes/complete-post-init-spec-gaps/proposal.md`。
- Evidence: `server-app/src/main/java/dezz/gnssshare/server/DeadReckoningEstimator.java#onSensorChanged`。
- TaskAdmin task: `master/1`。