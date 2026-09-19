---
title: 惯性航位推算实施清单
created: 2026-09-13
updated: 2026-09-13
doc_role: implementation-tasks
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
  - .supermax/specs/changes/add-inertial-dead-reckoning/proposal.md
  - .supermax/specs/changes/add-inertial-dead-reckoning/design.md
  - .supermax/specs/changes/add-inertial-dead-reckoning/specs/inertial-location-estimation/spec.md
confidence: high
---

# Tasks: add-inertial-dead-reckoning

> This checklist owns implementation convergence only; it is not TaskAdmin task status or progress.

## Context

- Proposal: `.supermax/specs/changes/add-inertial-dead-reckoning/proposal.md`
- Design: `.supermax/specs/changes/add-inertial-dead-reckoning/design.md`
- Delta spec: `.supermax/specs/changes/add-inertial-dead-reckoning/specs/inertial-location-estimation/spec.md`
- TaskAdmin task: not applicable

## Phase 1: Native Dependency

- [x] T001 Add `mad-location-manager-lib` at the specified server C++ path and fixed commit with recursive Eigen/GeographicLib submodules；preserve source/license provenance and make missing source fail without network fetching。
- [x] T002 Configure `server-app` and release CI for pinned CMake/NDK, then implement the minimal per-session JNI handle API for correction, ENU prediction, estimate retrieval, bearing normalization and destruction。

## Phase 2: Android Estimator

- [x] T003 Add one server-owned estimator manager that serializes native access, collects `TYPE_LINEAR_ACCELERATION` and `TYPE_ROTATION_VECTOR`, corrects magnetic-north ENU to true north, detects unsupported sensors and owns the 500 ms ticker。
- [x] T004 Persist accepted live real locations, query all system/Fused last-known candidates on collection start, choose the newest valid candidate without an age limit, and initialize using monotonic current time。
- [x] T005 Map real corrections and predicted estimates into existing `LocationProto.LocationUpdate` fields, including `dead_reckoning`, new timestamps, retained altitude and monotonically growing finite accuracy。

## Phase 3: Service Integration

- [x] T006 Integrate estimator start/stop, `PARTIAL_WAKE_LOCK` and fallback behavior into `GNSSServerService` while preserving authorized-client gating, 15-second disconnect delay, Bluetooth framing and live-location forwarding when sensors/native code are unavailable。
- [x] T007 Add concise server status/notification strings for initializing, active, waiting for an anchor and unsupported inertial assistance；add no new user controls。

## Phase 4: Validation And Convergence

- [x] T008 Initialize recursive submodules and run `./gradlew assembleDebug lintDebug` plus `git --no-pager diff --check`; repair and rerun failures。Final automated result: both commands passed on 2026-09-13。
- [ ] T009 On a target phone/tablet, validate live correction, GNSS-loss continuity, increasing timestamps/accuracy, cached-anchor indoor restart, reacquisition, no-anchor waiting, under/over-15-second reconnect behavior and unsupported-sensor fallback where hardware permits。
- [ ] T010 Reconcile implementation, proposal, design and delta spec；record exact automated/human evidence。After required validation passes, merge into a stable spec and update lifecycle fields/indexes/logs；otherwise retain `status: draft`。

## Dependencies

- T001 precedes T002。
- T002-T004 precede T005-T007。
- T005-T007 precede T008-T009。
- T008-T009 precede T010。

## Validation

- Automated: `./gradlew assembleDebug lintDebug` and `git --no-pager diff --check`。
- Manual: one target phone/tablet scenario set in T009；no broad new test framework。
- Failure: record the exact failing build/device scenario, leave change artifacts draft, and do not merge stable behavior。
