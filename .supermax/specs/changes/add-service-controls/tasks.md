---
title: 快捷设置与界面服务开关实施清单
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
change_id: add-service-controls
capability: service-controls
sources:
  - .supermax/specs/changes/add-service-controls/proposal.md
  - .supermax/specs/changes/add-service-controls/design.md
  - .supermax/specs/changes/add-service-controls/specs/service-controls/spec.md
confidence: high
---

# Tasks: add-service-controls

> This checklist owns implementation convergence only; it is not TaskAdmin task status or progress.

## Context

- Proposal: `.supermax/specs/changes/add-service-controls/proposal.md`
- Design: `.supermax/specs/changes/add-service-controls/design.md`
- Delta spec: `.supermax/specs/changes/add-service-controls/specs/service-controls/spec.md`
- TaskAdmin task: not applicable

## Phase 1: Service Control Foundation

- [x] T001 Add one minimal package-local service-control helper to each app for atomically publishing/reading that app's lifecycle PID marker, default-main-process detection, prerequisite evaluation, service start/stop, Activity launch and Tile refresh; reuse the same structure without introducing a new shared dependency or generic framework.
- [x] T002 Update `GNSSServerService` and `GNSSClientService` lifecycle boundaries to publish the running PID and request Tile refresh after successful foreground creation, then delete the marker and refresh at destruction start without changing transport, location or protocol behavior.

## Phase 2: Quick Settings Tiles

- [x] T003 Add one monochrome vector Tile icon and localized Tile label to each app; declare each `TileService` in its app manifest with `BIND_QUICK_SETTINGS_TILE`, `QS_TILE`, `ACTIVE_TILE=true`, `TOGGLEABLE_TILE=true` and a private named Tile process.
- [x] T004 Implement each Tile's `onStartListening()` and `onClick()` so active stops service immediately even while securely locked; inactive secure-lock clicks defer through `unlockAndRun()`, preserve click-time main-process existence, and re-check stopped state after authentication; cold main process opens Activity, missing prerequisites open Activity, permitted warm-process starts run without opening Activity, and warm `singleTask` Activity fallback re-enters startup through a private `onNewIntent()` marker.
- [x] T005 Apply the conservative Android 14+ `location` foreground-service direct-start rule: server direct-start requires background location; client opens Activity on Android 14+ unless its manifest/permission model is deliberately changed by an agreed later specification.

## Phase 3: Main-Screen Controls

- [x] T006 Add a `serviceControlButton` to the server service-status card and client service-status card plus default/Russian localized Start/Stop strings.
- [x] T007 Wire each button to current service state: Stop sets an Activity-local manual-stop guard and stops service; Start clears the guard and invokes the existing prerequisite/startup flow; stopped rendering shows the localized stopped status.
- [x] T008 Preserve `onCreate`/`onStart` automatic startup while ensuring a manual stop is not undone by periodic status refresh in the same visible Activity cycle; source inspection verifies a later `onStart` clears the guard and automatically invokes startup.

## Phase 4: Validation And Convergence

- [x] T009 Run `./gradlew assembleDebug`; repair and rerun until both debug APKs build. Run the closest existing lint task if manifest/resource validation requires it.
- [x] T010 Run `git --no-pager diff --check`, verify both Tile declarations and localized resources, and confirm `proto/location.proto` is unchanged.
- [ ] T011 On target phone/tablet, execute the focused manual matrix: Tile add/state, Tile stop, secure-lock active immediate stop, secure-lock inactive authentication/cancellation/post-unlock re-check, cold-process Tile start, warm-process direct start where permitted, permission/Bluetooth fallback to Activity, screen start/stop text, same-cycle stop persistence, later `onStart` auto-restart, and service-active state during disconnected/waiting conditions.
- [x] T012 Reconcile implementation with proposal/design/delta spec, including the target-device Tile synchronization repair, and record exact automated/static evidence plus human `not-run` state. Keep all artifacts draft while device validation is incomplete.
- [ ] T013 After implementation plus automated and human validation converge, merge behavior into `.supermax/specs/service-controls/spec.md`, mark workspace artifacts `status: accepted` with `accepted_at`, `merged_to`, validation and `archive_state: retained`, then update `.supermax/specs/changes/index.md` and `.supermax/specs/log.md`.

## Dependencies

- T001 precedes T002, T004, T005 and T007.
- T003 and T006 may proceed independently after source inspection.
- T002-T005 precede Tile validation; T006-T008 precede screen validation.
- T009-T012 precede T013.

## Validation

- Automated: passed — focused `ANDROID_HOME="$HOME/Android/Sdk" ANDROID_SDK_ROOT="$HOME/Android/Sdk" ./gradlew :server-app:compileDebugJavaWithJavac :client-app:compileDebugJavaWithJavac :server-app:lintDebug :client-app:lintDebug` returned `BUILD SUCCESSFUL` with 98 actionable tasks; cross-process lifecycle-state source proof passed; final command repeated both app compile/lint tasks plus `assembleDebug` and returned `BUILD SUCCESSFUL` with 143 actionable tasks.
- Static: passed — `git --no-pager diff --check`; `proto/location.proto` no-diff assertion; merged-manifest inspection for protected independent-process Tiles with `ACTIVE_TILE=true` and `TOGGLEABLE_TILE=true`; lifecycle-state proof that both services atomically publish/delete their PID marker at foreground-create/destruction boundaries, both Tile helpers read it only when matching the current default-main-process PID, each publication requests refresh, and no connection/GNSS status maps to Tile state; source inspection for secure-lock inactive `unlockAndRun()` deferral with immediate active stop, click-time main-process preservation and post-unlock stopped-state re-check, Android 14+ routing, `onNewIntent()` fallback, stopped rendering and manual-stop guard.
- Human: not run — one phone/tablet pair must cover T011; no broad instrumentation suite is required.
- Failure: record exact failing command/scenario, leave `validation` as `failed` or `not-run`, keep `status: draft`, and do not merge into stable specs.
