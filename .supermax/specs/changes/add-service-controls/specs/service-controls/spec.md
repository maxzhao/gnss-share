---
title: 客户端与服务端服务控制增量规格
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
change_id: add-service-controls
capability: service-controls
sources:
  - User requirements and decisions confirmed in chat on 2026-09-13
  - .supermax/specs/changes/add-service-controls/proposal.md
  - .supermax/specs/changes/add-service-controls/design.md
  - server-app/src/main/AndroidManifest.xml
  - server-app/src/main/java/dezz/gnssshare/server/MainActivity.java
  - server-app/src/main/java/dezz/gnssshare/server/GNSSServerService.java
  - server-app/src/main/java/dezz/gnssshare/server/GNSSServerTileService.java
  - server-app/src/main/java/dezz/gnssshare/server/ServiceControl.java
  - client-app/src/main/AndroidManifest.xml
  - client-app/src/main/java/dezz/gnssshare/client/MainActivity.java
  - client-app/src/main/java/dezz/gnssshare/client/GNSSClientService.java
  - client-app/src/main/java/dezz/gnssshare/client/GNSSClientTileService.java
  - client-app/src/main/java/dezz/gnssshare/client/ServiceControl.java
confidence: high
---

# Delta Spec: service-controls

## ADDED Requirements

### Requirement: Each Application Exposes A Toggleable Quick Settings Tile

`server-app` and `client-app` SHALL each expose one Android Quick Settings Tile representing whether its own foreground service exists and is running. Each Tile SHALL declare `android.service.quicksettings.ACTIVE_TILE=true` so lifecycle calls to `TileService.requestListeningState()` are effective, and SHALL declare `android.service.quicksettings.TOGGLEABLE_TILE=true`. The user SHALL add the Tile through Android system UI; the applications SHALL NOT silently add it.

#### Scenario: Tile reflects a running service

- GIVEN the corresponding foreground service has completed foreground service creation
- WHEN Android starts Tile listening or the service requests a Tile refresh
- THEN the Tile state SHALL be `STATE_ACTIVE`
- AND the state SHALL remain active while Bluetooth is disconnected, a peer is unavailable, or GNSS data is not currently flowing, provided the service still exists

#### Scenario: Tile reflects a stopped service

- GIVEN the corresponding foreground service does not exist, has been destroyed, or the main application process no longer exists
- WHEN Android starts Tile listening or the service requests a Tile refresh
- THEN the Tile state SHALL be `STATE_INACTIVE`

#### Scenario: Tile installation

- GIVEN either application is installed or upgraded
- WHEN the user has not added its Tile through Android Quick Settings editing
- THEN the application SHALL NOT claim the Tile was added
- AND all in-app service controls SHALL continue to work independently

### Requirement: Active Tile Stops The Corresponding Service

Clicking an active Tile SHALL stop only the foreground service owned by that application and update observable controls to stopped state. Secure lock SHALL NOT delay this restrictive stop action.

#### Scenario: Stop from Tile

- GIVEN the Tile is active and its foreground service is running
- WHEN the user clicks the Tile, whether unlocked or securely locked
- THEN the application SHALL request that service to stop
- AND the Tile SHALL become inactive no later than the next Tile lifecycle refresh
- AND an open application screen SHALL show its service button as “Start”/localized equivalent on its next status refresh

### Requirement: Secure Lock Requires Authentication Before Inactive Tile Startup

An inactive Tile SHALL NOT start a service or open `MainActivity` while the device is securely locked. It SHALL preserve click-time main-process existence across authentication and re-check service state before continuing.

#### Scenario: Securely locked inactive Tile

- GIVEN the Tile is inactive
- AND `TileService.isLocked()` and `TileService.isSecure()` are both true
- WHEN the user clicks the Tile
- THEN the Tile SHALL remain inactive
- AND the application SHALL defer startup through `TileService.unlockAndRun()`
- AND it SHALL NOT start the service or open `MainActivity` before successful authentication

#### Scenario: Service starts while authentication is pending

- GIVEN inactive Tile startup was deferred for secure authentication
- AND another application path starts the corresponding service before the unlock callback executes
- WHEN the unlock callback runs
- THEN the application SHALL re-read service state
- AND SHALL NOT start the service again or open `MainActivity`
- AND the Tile SHALL render or refresh as active

#### Scenario: Continue after authentication

- GIVEN inactive Tile startup was deferred for secure authentication
- AND the service remains stopped when the unlock callback runs
- WHEN the application continues startup routing
- THEN it SHALL use whether the main application process existed at the original click
- AND SHALL evaluate current prerequisites and platform direct-start permission
- AND SHALL follow the existing direct-start or Activity-fallback requirements below

### Requirement: Inactive Tile Selects Direct Start Or App Launch

Clicking an inactive Tile SHALL use the main application process state captured before the click and current service prerequisites to select exactly one startup path, after secure authentication when required.

#### Scenario: Main application process did not exist before click

- GIVEN the foreground service is stopped
- AND the main application process did not exist before the Tile click
- WHEN the user clicks the inactive Tile
- THEN the Tile SHALL open that application's `MainActivity`
- AND `MainActivity` SHALL run the existing prerequisite and automatic service startup flow, including when an existing `singleTask` Activity receives the Tile launch through `onNewIntent()`
- AND the Tile SHALL remain inactive until the service actually enters its created/running lifecycle

#### Scenario: Main process exists and direct start is permitted

- GIVEN the foreground service is stopped
- AND the main application process existed before the Tile click
- AND all required location and Bluetooth permissions are granted
- AND classic Bluetooth is supported and enabled
- AND the current Android version and granted permissions permit background creation of that application's `location` foreground service
- WHEN the user clicks the inactive Tile
- THEN the application SHALL start its service without opening `MainActivity`
- AND the Tile SHALL become active only after service creation succeeds

#### Scenario: A prerequisite is missing

- GIVEN the foreground service is stopped
- AND at least one required permission is absent, Bluetooth is unsupported, Bluetooth is disabled, or the platform does not safely permit direct background creation of the `location` foreground service
- WHEN the user clicks the inactive Tile
- THEN the Tile SHALL open that application's `MainActivity` even if the main application process already exists
- AND a warm `singleTask` Activity SHALL treat the Tile Intent as an explicit start request, clear its Activity-local manual-stop guard, and re-run the startup flow from `onNewIntent()`
- AND the existing UI SHALL handle permission or Bluetooth setup
- AND the Tile SHALL remain inactive until service creation succeeds

#### Scenario: Target device is not selected or reachable

- GIVEN required permissions are granted and Bluetooth is supported and enabled
- AND no target device is selected or the saved target is unavailable
- WHEN another rule permits service startup
- THEN lack of a connected/selected target SHALL NOT by itself prevent service creation
- AND the running service SHALL expose its existing setup, waiting, or retrying status

### Requirement: Main Application Process Detection Is Independent Of Tile Process

Each Tile SHALL run outside the default main application process so Tile instantiation does not itself satisfy the “main process exists” condition.

#### Scenario: Tile process starts a cold application

- GIVEN neither the main application process nor the Tile process exists
- WHEN Android creates the Tile process for a click
- THEN the application SHALL still classify the main application process as absent
- AND SHALL open `MainActivity` rather than treating Tile process creation as an already-running app

### Requirement: Service Running State Is Published Across Processes

Each application SHALL publish its service lifecycle through an app-private atomically written PID marker. A service SHALL be treated as active only while the marker exists and its PID equals the currently running default main-application process PID. Bluetooth connection, peer availability, GNSS flow and other operational states SHALL NOT affect this marker.

#### Scenario: Normal service lifecycle

- GIVEN the service is stopped
- WHEN service creation succeeds and foreground state is entered
- THEN the service SHALL atomically write its current main-process PID to the app-private lifecycle marker
- AND SHALL request a Tile refresh
- AND the cross-process query SHALL report active while that PID matches the current default main process
- WHEN service destruction begins
- THEN the service SHALL delete the marker before resource teardown
- AND SHALL request a Tile refresh
- AND the cross-process query SHALL report inactive immediately after marker deletion

#### Scenario: Main process terminates abnormally

- GIVEN the main application process terminates without a normal service-destruction callback and leaves a lifecycle marker
- WHEN the Tile determines the default main process is absent or its live PID differs from the marker PID
- THEN it SHALL interpret the service as stopped
- AND SHALL display `STATE_INACTIVE`

#### Scenario: Operational state changes while service remains alive

- GIVEN the lifecycle marker PID still matches the default main process
- WHEN Bluetooth disconnects, a peer becomes unavailable, GNSS data stops flowing, or another operational status changes
- THEN the lifecycle marker SHALL remain unchanged
- AND the Tile SHALL remain `STATE_ACTIVE`

### Requirement: Both Main Screens Provide A Start/Stop Button

The server and client main screens SHALL each provide one service-control button whose label and action derive from the corresponding service's running lifecycle, not transport connection state.

#### Scenario: Running service in the main screen

- GIVEN the corresponding foreground service is running
- WHEN the screen renders or performs its periodic status update
- THEN the button SHALL display “Stop” or the localized equivalent
- WHEN the user clicks it
- THEN the service SHALL stop
- AND the button SHALL update to “Start” or the localized equivalent

#### Scenario: Stopped service in the main screen

- GIVEN the corresponding foreground service is stopped
- WHEN the screen renders or performs its periodic status update
- THEN the button SHALL display “Start” or the localized equivalent
- AND the status text SHALL display that the service is stopped rather than that startup is still in progress when no prerequisite error supersedes it
- WHEN the user clicks it
- THEN the screen SHALL run the existing permission/Bluetooth prerequisite flow
- AND SHALL start the service when those prerequisites are satisfied

#### Scenario: Connection state differs from service state

- GIVEN the service is running but is waiting for a target, reconnecting, awaiting location, or reporting another existing operational status
- WHEN the screen updates
- THEN the service-control button SHALL continue to display “Stop”
- AND the existing status text SHALL continue to display the more detailed operational state separately

### Requirement: Manual Stop And Existing Automatic Startup Coexist

A stop requested from the current main screen SHALL prevent immediate automatic restart during that same Activity visible cycle, while later Activity entry SHALL retain the existing automatic-start behavior.

#### Scenario: Stop while remaining on the screen

- GIVEN the service is running and the Activity is visible
- WHEN the user presses the screen's Stop button
- THEN the service SHALL stop
- AND periodic status refreshes SHALL NOT restart it while that Activity remains in the same visible cycle

#### Scenario: Re-enter application after manual stop

- GIVEN the user stopped the service from the main screen
- AND then caused the Activity to leave and later enter `onStart` again
- WHEN the existing startup prerequisites are satisfied
- THEN the Activity SHALL automatically start the service without requiring the Start button

#### Scenario: Open application normally while service is stopped

- GIVEN the application is launched or brought through a new `onStart` while its service is stopped
- WHEN required permissions and Bluetooth conditions are satisfied
- THEN it SHALL preserve the current behavior of immediately starting the service

### Requirement: Service Control Does Not Change GNSS Protocol Or Transport Semantics

The new controls SHALL only govern service lifecycle and SHALL NOT modify location message schema, framing, Bluetooth peer authorization, reconnect behavior, GNSS collection, inertial estimation, mock-location publication or saved target semantics.

#### Scenario: Protocol compatibility

- GIVEN the controls are implemented
- WHEN source changes are reviewed and both apps communicate
- THEN `proto/location.proto` SHALL remain unchanged
- AND existing framed Protobuf exchange SHALL remain the wire contract

## MODIFIED Requirements

### Requirement: Applications Support Both Automatic And User-Initiated Service Lifecycle

The server and client services SHALL still be automatically started by their existing main-Activity startup flows, and SHALL additionally be startable/stoppable by their own Quick Settings Tile and main-screen button under the requirements above. They SHALL remain stopped after an explicit stop until another explicit start, a permitted inactive-Tile click, or a later main-Activity `onStart` automatic startup event.

#### Scenario: No persistent disabled mode

- GIVEN the user previously stopped a service
- WHEN the application later enters its normal startup flow with prerequisites satisfied
- THEN the previous stop SHALL NOT act as a persistent opt-out
- AND the service SHALL start automatically

## REMOVED Requirements

### Requirement: No User-Facing Manual Service Controls

Reason: the earlier Bluetooth change intentionally removed manual controls, but this change explicitly restores complete Tile and in-app lifecycle controls while retaining automatic startup.

## Source Trace

- User decisions: chat confirmation on 2026-09-13, including acceptance of opening the app when permissions or Bluetooth prerequisites are missing.
- Existing startup behavior: `server-app/src/main/java/dezz/gnssshare/server/MainActivity.java`, `client-app/src/main/java/dezz/gnssshare/client/MainActivity.java`.
- Existing lifecycle state and publication boundaries: `server-app/src/main/java/dezz/gnssshare/server/{GNSSServerService,ServiceControl}.java`, `client-app/src/main/java/dezz/gnssshare/client/{GNSSClientService,ServiceControl}.java`.
- Existing Android declarations: both `src/main/AndroidManifest.xml` files.
- Superseded proposed no-manual-control behavior: `.supermax/specs/changes/replace-wifi-with-bluetooth/specs/gnss-bluetooth-sharing/spec.md`.
- Platform Tile contract, including lock state and `unlockAndRun()`: https://developer.android.com/develop/ui/views/quicksettings-tiles
- Platform foreground-service restrictions: https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start

## Validation Evidence

- Automated/static: passed — focused `ANDROID_HOME="$HOME/Android/Sdk" ANDROID_SDK_ROOT="$HOME/Android/Sdk" ./gradlew :server-app:compileDebugJavaWithJavac :client-app:compileDebugJavaWithJavac :server-app:lintDebug :client-app:lintDebug` passed (`BUILD SUCCESSFUL`, 98 actionable tasks); the cross-process lifecycle-state proof verified symmetric `AtomicFile` PID write/read/delete, foreground-create/destruction publication order, refresh after both transitions, and no connection/GNSS mapping; final `ANDROID_HOME="$HOME/Android/Sdk" ANDROID_SDK_ROOT="$HOME/Android/Sdk" ./gradlew :server-app:compileDebugJavaWithJavac :client-app:compileDebugJavaWithJavac :server-app:lintDebug :client-app:lintDebug assembleDebug && git --no-pager diff --check && test -z "$(git --no-pager diff -- proto/location.proto)"` passed (`BUILD SUCCESSFUL`, 143 actionable tasks; `final-validation: passed`).
- Human/device: prior target-device execution exposed stale Tile visual state under the removed `getRunningServices()` implementation; the corrected atomic PID-marker synchronization has not yet been rerun on devices. Tile lifecycle publication/refresh, secure-lock authentication/cancellation, post-unlock state re-check, process-state routing and foreground-service behavior therefore remain `not-run` for the corrected build, and this delta stays `status: draft`, `merged_to: []`, and unarchived.
