---
title: 快捷设置与界面服务开关设计
created: 2026-09-13
updated: 2026-09-13
doc_role: design
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
  - .supermax/specs/changes/add-service-controls/specs/service-controls/spec.md
  - server-app/src/main/java/dezz/gnssshare/server/MainActivity.java
  - server-app/src/main/java/dezz/gnssshare/server/GNSSServerService.java
  - client-app/src/main/java/dezz/gnssshare/client/MainActivity.java
  - client-app/src/main/java/dezz/gnssshare/client/GNSSClientService.java
  - https://developer.android.com/develop/ui/views/quicksettings-tiles
  - https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
confidence: high
---

# Design: add-service-controls

## Summary

为两个应用分别增加一个独立进程 `TileService`、一个最小服务状态/控制辅助类和一个主界面启停按钮。服务的 `onCreate()`/`onDestroy()` 是生命周期边界；Activity 读取主进程静态状态，服务通过应用私有原子 PID 标记向 Tile 进程发布运行/停止状态，并在每次发布后请求 active-Tile 监听刷新。Activity 保留 `onCreate`/`onStart` 自动启动，但手动停止只抑制当前可见周期内的再次启动。

## Context

- Proposal: `.supermax/specs/changes/add-service-controls/proposal.md`
- Delta spec: `.supermax/specs/changes/add-service-controls/specs/service-controls/spec.md`
- Server sources: `server-app/src/main/AndroidManifest.xml`, `server-app/src/main/java/dezz/gnssshare/server/{MainActivity,GNSSServerService,GNSSServerTileService,ServiceControl}.java`, `server-app/src/main/res/{drawable/ic_quick_settings_service.xml,layout/activity_main_server.xml,values*/strings.xml}`
- Client sources: `client-app/src/main/AndroidManifest.xml`, `client-app/src/main/java/dezz/gnssshare/client/{MainActivity,GNSSClientService,GNSSClientTileService,ServiceControl}.java`, `client-app/src/main/res/{drawable/ic_quick_settings_service.xml,layout/activity_main.xml,values*/strings.xml}`
- Constraints: server min SDK 24, client min SDK 28, target SDK 36, Java 21, no protocol change, no new dependency, no transition behavior.

## Approach

### 1. Per-app Service Control Boundary

Each application SHALL own a small package-local control class, avoiding transport or GNSS logic in `TileService`. It SHALL provide:

- the expected main process name (`applicationId`);
- service-running detection from an app-private atomically written service-process PID marker, accepted only when it equals the current default main-process PID;
- main-process-running detection from `ActivityManager.getRunningAppProcesses()`;
- prerequisite evaluation using currently granted permissions, Bluetooth support and enabled state;
- `startForegroundService`, `stopService`, main-Activity launch and Tile refresh operations.

The initial `ActivityManager.getRunningServices()` design was rejected after target-device evidence: lifecycle refresh callbacks could run while the independent Tile process still observed an absent or stale service record. Each service now writes its current main-process PID with `AtomicFile` only after foreground creation succeeds and deletes it at the start of destruction; each Tile accepts active only when the marker PID equals the currently enumerated default main-process PID. The marker represents observed lifecycle, not desired state. Force stop, crash, abrupt death or process restart leaves no false active state because the main process is absent or its PID no longer matches.

### 2. Quick Settings Tile

Each manifest SHALL declare one exported `TileService` guarded by `android.permission.BIND_QUICK_SETTINGS_TILE`, with the `android.service.quicksettings.action.QS_TILE` intent filter, `ACTIVE_TILE=true`, `TOGGLEABLE_TILE=true`, localized label and monochrome vector icon. `ACTIVE_TILE=true` is required because Android documents that `TileService.requestListeningState()` does nothing without it. The Tile service SHALL run in a named private process such as `:quick_settings_tile` so that Tile creation does not make the main process appear pre-existing.

`onStartListening()` SHALL map the interpreted service state to `Tile.STATE_ACTIVE` or `Tile.STATE_INACTIVE`, set label/content description and call `updateTile()`.

`onClick()` SHALL:

1. Capture whether the default main process existed at click time and re-read service state.
2. If active, call `stopService()` and immediately render inactive pending lifecycle confirmation, including while securely locked.
3. If inactive and `isLocked() && isSecure()`, render inactive and defer the remaining decision through `unlockAndRun()`; after authentication, re-read service state and stop if it is already active without starting or opening Activity.
4. If still inactive, use the click-time main-process value: absent opens `MainActivity`; present plus current direct-start prerequisites starts the service without Activity; otherwise open `MainActivity` for permission/Bluetooth setup.
5. Leave the Tile inactive until successful service creation requests an active refresh.

Activity launch SHALL use `TileService.startActivityAndCollapse(PendingIntent)` on API 34+ and the supported older overload on earlier versions. The launch Intent SHALL carry a private tile-start marker; because `MainActivity` is `singleTask`, `onNewIntent()` SHALL clear any Activity-local manual-stop guard and re-run `continueStartup()` when an existing warm Activity receives that explicit Tile request.

### 3. Direct-start Prerequisites

Both apps SHALL require granted fine/coarse location permission, required Bluetooth permission, a Bluetooth adapter, and enabled Bluetooth before a direct Tile start.

- Server: also require granted `ACCESS_BACKGROUND_LOCATION`. This matches its existing startup prerequisite and enables location use while backgrounded on supported Android versions.
- Client: because it declares a `location|connectedDevice` foreground service but does not hold `ACCESS_BACKGROUND_LOCATION`, direct background start from Tile is not treated as safe on Android 14+; open Activity instead. On earlier supported Android versions, direct start is allowed when the normal prerequisites pass.

A selected/bonded target is not a service-start prerequisite because current services intentionally start and expose setup/waiting state without a selected or reachable target.

### 4. Activity Button And Automatic Startup

Each service-status card SHALL contain a full-width button with id `serviceControlButton`.

- Running: label `Stop`, click `stopService()` and set an Activity-local `manualStopRequested=true`.
- Stopped: label `Start`, clear `manualStopRequested` and invoke the existing `continueStartup()` prerequisite flow.
- Status update loops SHALL only render state and button label; they SHALL NOT start services. A stopped service SHALL render the localized stopped status rather than “Starting service…”.
- Existing `onCreate()` and `onStart()` calls to `continueStartup()` remain. `onStart()` clears `manualStopRequested` before automatic startup, so leaving and returning to the Activity starts the service again as requested.
- `continueStartup()` SHALL not auto-start while `manualStopRequested` is true. User actions that explicitly request startup clear the flag first.

### 5. State Propagation

After successful foreground creation, each service SHALL atomically write the current process PID as its app-private running marker, then request Tile listening-state refresh through `TileService.requestListeningState()`. At destruction start it SHALL clear process-local running state, delete that marker and request another Tile refresh before transport/resource teardown. No service-record settling delay or repeated refresh is needed because the Tile reads the lifecycle publication directly.

`GNSSServerService.isServiceRunning()` and `GNSSClientService.isServiceRunning()` remain the main-process Activity fast path. Tile state uses the cross-process marker plus current default-main-process PID query. Connection, transport, peer and location status remain separate from service-running state and SHALL NOT write the marker.

## Decisions

### Decision: Tile Uses A Separate Process

- Choice: declare Tile as `android:process=":quick_settings_tile"`.
- Rationale: Android must instantiate `TileService` before `onClick()`; in the default process that would erase the distinction between “main app process existed before click” and “Tile started it.”
- Alternatives considered: infer from Activity visibility or service static fields. Rejected because the user explicitly chose process existence, not Activity visibility or service state.

### Decision: Lifecycle PID Marker Plus Process Presence

- Choice: publish the service lifecycle through an app-private `AtomicFile` containing the service/main-process PID; compare it with `getRunningAppProcesses()` for the default process.
- Rationale: static fields are not visible across processes, and target-device evidence showed `getRunningServices()` did not provide timely/reliable state to the Tile process even when `requestListeningState()` was delivered. Atomic write/delete gives both processes one immediate state boundary, while PID matching invalidates crash/force-stop/restart residue.
- Alternatives considered: repeated delayed `requestListeningState()`, connection-state mapping, bound service, exported receiver, ContentProvider or AIDL. Rejected because refresh retries do not fix an unreliable read authority, connection state is not service state, and a two-state app-private lifecycle publication does not require a broader IPC surface.

### Decision: Secure Lock Gates Only Inactive Startup

- Choice: allow an active Tile to stop immediately, but defer every inactive startup/Activity-fallback decision through `TileService.unlockAndRun()` while `isLocked() && isSecure()`.
- Rationale: stopping is a safe restrictive action; starting a location foreground service or opening its setup flow from a securely locked device requires authentication.
- Continuity: capture main-process existence before deferral to preserve cold/warm click semantics, then re-check service state after unlock to avoid duplicate startup if another path started it during authentication.

### Decision: Conservative Android 14+ Location Start

- Choice: start directly only when the current app has a platform-safe background location path; otherwise show Activity.
- Rationale: both services declare `location`, and Android 14+ checks while-in-use permission eligibility when the foreground service is created.
- Alternatives considered: start and catch `SecurityException`. Rejected because failure-driven behavior would cause avoidable service-start errors and ambiguous Tile feedback.

### Decision: Manual Stop Is Not Persistent

- Choice: suppress restart only within the current Activity visibility cycle.
- Rationale: this allows the new Stop button to work while preserving the confirmed “open/re-enter app immediately starts service” behavior.
- Alternatives considered: persistent desired-state preference. Rejected because it would change automatic startup semantics and exceed the request.

### Decision: No New Test Infrastructure

- Choice: use existing Gradle build/lint plus focused source/resource checks and target-device manual scenarios.
- Rationale: the change is Android lifecycle/UI integration; device behavior provides the valuable regression coverage, while broad new instrumentation infrastructure would delay the requested feature.

## Validation Strategy

- Automated: `./gradlew assembleDebug` for both applications; run the closest available lint task if build changes expose Android manifest/resource issues.
- Static: `git --no-pager diff --check`; inspect merged manifests for two correctly protected independent-process Tile declarations with `ACTIVE_TILE=true` and `TOGGLEABLE_TILE=true`; verify each service publishes after foreground creation and clears at destruction start, both Tile helpers atomically read the same PID marker and request refresh after writes/deletes, no connection/GNSS state writes that marker, and `proto/location.proto` has no change.
- Manual on target phone/tablet:
  - add each Tile and verify inactive/active appearance;
  - start/stop from Tile with main process absent and present;
  - verify secure-lock active stop is immediate, while inactive startup waits for authentication and re-checks service state afterward;
  - verify permission/Bluetooth failure opens Activity;
  - start/stop from each Activity and verify button text plus Tile state;
  - verify manual stop persists while the Activity remains in the same visible cycle;
  - leave and return to verify automatic restart;
  - verify connection/waiting/failure states do not incorrectly mark a running service stopped.

## Failure Handling

- If background direct start throws or is rejected on a supported OS/device, route that OS path to Activity launch; do not weaken permission checks.
- If the Tile state is stale, verify lifecycle marker write/delete, marker PID versus current default-main-process PID, `ACTIVE_TILE=true` and `requestListeningState()` delivery before adding another IPC mechanism.
- If inactive Tile startup proceeds under secure lock, verify the `isLocked()`/`isSecure()` gate and `unlockAndRun()` callback; do not gate the active stop path.
- If an Activity button starts the service despite a manual stop in the same visible cycle, fix the `manualStopRequested` ownership; do not remove existing automatic startup from `onCreate`/future `onStart`.
- Automated/static validation passed: focused `ANDROID_HOME="$HOME/Android/Sdk" ANDROID_SDK_ROOT="$HOME/Android/Sdk" ./gradlew :server-app:compileDebugJavaWithJavac :client-app:compileDebugJavaWithJavac :server-app:lintDebug :client-app:lintDebug` returned `BUILD SUCCESSFUL` with 98 actionable tasks; the cross-process lifecycle-state proof verified symmetric `AtomicFile` PID write/read/delete, correct foreground-create/destruction publication order, refresh after both transitions, and no connection/GNSS mapping; final `ANDROID_HOME="$HOME/Android/Sdk" ANDROID_SDK_ROOT="$HOME/Android/Sdk" ./gradlew :server-app:compileDebugJavaWithJavac :client-app:compileDebugJavaWithJavac :server-app:lintDebug :client-app:lintDebug assembleDebug && git --no-pager diff --check && test -z "$(git --no-pager diff -- proto/location.proto)"` returned `BUILD SUCCESSFUL` with 143 actionable tasks and `final-validation: passed`.
- Keep the change `status: draft` until target-device validation is recorded; human validation is `not-run`.

## Risks And Rollback

- OEM Tile refresh timing may lag lifecycle changes; `onStartListening()` remains authoritative whenever the panel opens and reads the published lifecycle marker.
- Default-main-process visibility and PID reuse assumptions must be verified on supported target devices; PID equality plus live process enumeration makes stale-file false positives unlikely without broad IPC.
- OEM lock-screen and Quick Settings authentication behavior may differ; target devices must verify callback execution, cancellation and concurrent service-start behavior.
- Rollback is deletion of the Tile declarations/classes/control helpers and UI buttons plus lifecycle hooks; transport, protocol and stored target configuration remain untouched.
