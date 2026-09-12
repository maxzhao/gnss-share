---
title: 一对一蓝牙 GNSS 替换实施清单
created: 2026-09-12
updated: 2026-09-12
doc_role: implementation-tasks
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
  - .supermax/specs/changes/replace-wifi-with-bluetooth/proposal.md
  - .supermax/specs/changes/replace-wifi-with-bluetooth/design.md
  - .supermax/specs/changes/replace-wifi-with-bluetooth/specs/gnss-bluetooth-sharing/spec.md
confidence: high
---

# Tasks: replace-wifi-with-bluetooth

> This checklist owns implementation convergence only; it is not TaskAdmin task status or progress.

## Context

- Proposal: `.supermax/specs/changes/replace-wifi-with-bluetooth/proposal.md`
- Design: `.supermax/specs/changes/replace-wifi-with-bluetooth/design.md`
- Delta spec: `.supermax/specs/changes/replace-wifi-with-bluetooth/specs/gnss-bluetooth-sharing/spec.md`
- TaskAdmin task: not applicable

## Phase 1: Shared Contract And Platform Configuration

- [x] T001 Add one shared RFCOMM contract class under `shared/src/main/java/dezz/gnssshare/shared/` containing the fixed service name, UUID, heartbeat byte and framing/timing constants used by both apps.
- [x] T002 Update both manifests for required classic Bluetooth, `BLUETOOTH_CONNECT`, `location|connectedDevice` foreground service types and remove Wi-Fi/network, scan and boot-start declarations that no longer apply.

## Phase 2: Phone Server

- [x] T003 Replace `ServerSocket`/`Socket` in `GNSSServerService` with one secure RFCOMM listener and one authorized `BluetoothSocket`; enforce saved-tablet address matching before GNSS starts or data is sent.
- [x] T004 Preserve current Protobuf framing, heartbeat behavior, GNSS/Fused Location collection and delayed GNSS stop; replace TCP read timeout with a cancelable heartbeat watchdog suitable for `BluetoothSocket`.
- [x] T005 Replace old multi-device Bluetooth trigger preferences/UI with one saved tablet selector from bonded devices; auto-start the service from `MainActivity` after permission/Bluetooth prerequisites.
- [x] T006 Delete `BluetoothReceiver`, old Bluetooth auto-stop logic, boot/manual start-stop receiver behavior, network-interface UI and all server TCP/Wi-Fi code without a compatibility path.

## Phase 3: Tablet Client

- [x] T007 Replace Wi-Fi gateway/IP discovery and `java.net.Socket` in `ConnectionManager` with one secure `BluetoothSocket` to the saved phone and a single cancelable reconnect loop.
- [x] T008 Adapt `GNSSClientService` to Bluetooth streams while preserving framed Protobuf receive, mock-location publication, widget broadcasts, notifications and connection states.
- [x] T009 Replace gateway/manual-IP UI and preferences with one saved phone selector from bonded devices; auto-start the service from `MainActivity` after permission/Bluetooth prerequisites.
- [x] T010 Delete `BootReceiver`, manual start/stop controls, network callbacks and all client TCP/Wi-Fi code without a compatibility path.

## Phase 4: Validation And Convergence

- [x] T011 Run `./gradlew assembleDebug`; repair and rerun until both debug APKs build. Final validation: `ANDROID_HOME="$HOME/Android/Sdk" ANDROID_SDK_ROOT="$HOME/Android/Sdk" ./gradlew clean assembleDebug lintDebug` passed on 2026-09-12.
- [ ] T012 On one target phone/tablet pair, validate mutual selection, automatic startup/connection, GNSS-to-mock-location flow, lock/recent-task persistence, disconnect recovery, unauthorized-tablet rejection, Bluetooth enable/deny behavior, unpaired-target retention and no boot startup.
- [ ] T013 Reconcile implementation with proposal/design/delta spec; update `.supermax/AGENTS.md` only after Bluetooth becomes stable project behavior.
- [ ] T014 Record automated and human evidence. After both pass, merge the accepted delta into `.supermax/specs/gnss-bluetooth-sharing/spec.md`, mark this workspace `status: accepted` with `merged_to`, `accepted_at`, validation and `archive_state: retained`, then update indexes/logs.

## Dependencies

- T001-T002 precede transport implementation.
- T003-T006 and T007-T010 may proceed independently after T001-T002.
- T011 precedes T012；T011-T013 precede T014.

## Validation

- Automated: `./gradlew assembleDebug`。
- Human: the single end-to-end target-device checklist in T012；no broad new test infrastructure is required。
- Failure: leave all change artifacts draft, record the exact failure, and do not merge into stable specs。
