---
title: 手机 GNSS 与 IMU 连续定位设计
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
change_id: add-inertial-dead-reckoning
capability: inertial-location-estimation
sources:
  - .supermax/specs/changes/add-inertial-dead-reckoning/proposal.md
  - server-app/src/main/java/dezz/gnssshare/server/GNSSServerService.java
  - server-app/build.gradle
  - proto/location.proto
  - https://github.com/maddevsio/mad-location-manager-lib
  - https://raw.githubusercontent.com/wiki/maddevsio/mad-location-manager-lib/Android.md
confidence: high
---

# Design: add-inertial-dead-reckoning

## Summary

在手机端引入固定版本的 `mad-location-manager-lib` C++ 滤波核心，通过一个最小 JNI 封装接收真实位置和 ENU 加速度并返回二维估算。Android 层负责传感器、最后位置持久化、系统缓存查询、500 ms 输出调度和现有 Protobuf 映射。客户端与线协议不变。

## Context

- Proposal: `.supermax/specs/changes/add-inertial-dead-reckoning/proposal.md`
- Delta spec: `.supermax/specs/changes/add-inertial-dead-reckoning/specs/inertial-location-estimation/spec.md`
- Existing transport change: `.supermax/specs/changes/replace-wifi-with-bluetooth/` remains draft and is not modified by this change.
- Server min SDK 24, compile/target SDK 36, Java 21。
- Server and release CI pin Android NDK `28.2.13676358` and CMake `3.22.1`。

## Ownership

| Owner | Responsibility | Must not own |
| --- | --- | --- |
| `server-app/src/main/cpp/external/mad-location-manager-lib/` | Fixed upstream filter source and its recursive dependencies | Android lifecycle, Protobuf, Bluetooth |
| `server-app/src/main/cpp/` | CMake target and minimal JNI handle/API | Sensor registration, persistence, UI |
| `server-app` Java | Sensor-to-ENU conversion, anchors, scheduling, output metadata, lifecycle | Reimplementation of Kalman matrices |
| `GNSSServerService` | Start/stop orchestration and existing response delivery | Native filter internals |
| `client-app` | Existing receive/display/mock-location behavior | Server-side prediction |
| `proto/location.proto` | Existing fields | Filter state or transport negotiation |

## Dependency And Build Contract

- Add `maddevsio/mad-location-manager-lib` as a Git submodule at `server-app/src/main/cpp/external/mad-location-manager-lib`, pinned to `5e426f6a0893e26cb0912437e753f92e2dad5a63`。
- Initialize its pinned Eigen and GeographicLib submodules recursively。No build step may clone or download source。Missing submodules SHALL fail with a clear configuration error。
- Build a server-only C++17 shared library through Android Gradle `externalNativeBuild`, pinned NDK `28.2.13676358` and CMake `3.22.1`。Release CI installs the same versions。Do not add NDK dependencies to `client-app` or `shared`。
- Preserve upstream source and license text. Project code calls the upstream predict/update implementation; only JNI marshaling, lifetime, ENU input and output metadata remain local。

## Native API

One Java-owned native handle represents one filter session。JNI SHALL provide exactly these operations:

1. `nativeCreate(accelerationVariance, defaultLocationVariance, defaultSpeedVariance) -> long handle`。
2. `nativeCorrect(handle, latitude, longitude, altitude, locationVariance, speed, bearing, speedVariance, monotonicSeconds)` to initialize or correct with a real anchor。
3. `nativePredict(handle, eastAcceleration, northAcceleration, upAcceleration, monotonicSeconds) -> boolean`。
4. `nativeEstimate(handle) -> [latitude, longitude, speed, bearing]`。The wrapper converts upstream Cartesian velocity angle back to Android bearing `[0,360)`。
5. `nativeDestroy(handle)`。

Calls for one handle SHALL be serialized by the Java owner。Invalid handles or non-finite native output SHALL fail without emitting a location。

## Real Location And Anchor Contract

An accepted real location is a non-mock Android GNSS/Fused/system-last-known `Location` with finite latitude/longitude inside valid geographic ranges。A dead-reckoning or mock-provider output is never persisted or reused as a real anchor。

- Every accepted live GNSS/Fused callback that is newer than the current real anchor is persisted through one `SharedPreferences.Editor.apply()` transaction with timestamp, provider, latitude, longitude, optional altitude, horizontal accuracy, speed, bearing and speed accuracy。Duplicate or older callbacks do not reset prediction。
- On each collection start, load the application anchor and query last-known locations from all `LocationManager` providers, including currently disabled providers；when Fused Location is enabled and available, also query its last location。
- Select the valid candidate with the greatest wall-clock timestamp。There is no maximum-age rejection。
- Initialize native monotonic time with current `SystemClock.elapsedRealtimeNanos()` even when the selected anchor is old；the original wall-clock age initializes uncertainty growth instead of native filter delta time。
- Continue requesting live location immediately。The first valid live callback always replaces a cache anchor regardless of wall-clock changes；later live callbacks are ordered by `elapsedRealtimeNanos` when available。An asynchronous Fused cache callback cannot replace an already received live location。
- If no candidate exists, keep sensors/listeners ready but emit no absolute position until the first accepted live callback。

No binary accuracy cutoff is used。For filter measurement covariance, use squared Android horizontal/speed accuracy when present and positive；otherwise use upstream example defaults `locationVariance=8.0` and `speedVariance=0.1`。When speed or bearing is absent, preserve the current estimate as the velocity observation and assign variance `1.0e12` so position can be corrected without falsely asserting that the vehicle is stopped。Use upstream example `accelerationVariance=0.3`。This keeps imprecise measurements as weak corrections instead of switching behavior at an arbitrary threshold。

## Sensor Flow

- Required Android sensors: `TYPE_LINEAR_ACCELERATION` and `TYPE_ROTATION_VECTOR`。Register both at `SENSOR_DELAY_GAME` only while current location collection is active。
- Keep the latest rotation quaternion；accept linear acceleration only after a rotation sample exists。Rotate device-frame linear acceleration to magnetic-north ENU, then use Android `GeomagneticField` from the latest real anchor to rotate horizontal acceleration to true-north ENU before JNI。
- Sensor callbacks and native access run through one serialized owner so location corrections, predictions and destruction cannot race。The owner rejects sensor timestamps not greater than the latest native correction/prediction timestamp, preventing negative filter deltas from queued events。
- If either required sensor is absent or registration fails, mark inertial assistance unavailable, do not call native prediction, and retain ordinary real-location forwarding。

## Output Contract

- A valid live location corrects the filter and immediately emits the corrected estimate using the source provider and source wall-clock timestamp。
- While initialized and sensor support is active, a 500 ms ticker emits the latest predicted estimate with:
  - current wall-clock timestamp；
  - `provider="dead_reckoning"`；
  - predicted latitude/longitude, speed and normalized bearing；
  - altitude from the latest accepted real anchor；
  - `location_age=0` because the estimate is generated now；
  - current real GNSS satellite count。
- Between real corrections, predicted horizontal accuracy SHALL be finite and nondecreasing using:

  `sqrt(baseAccuracy² + speedSigma²*t² + 0.25*accelerationVariance*t⁴)`

  where `t` is the selected anchor's nonnegative wall-clock age at correction plus monotonic seconds since that correction, `baseAccuracy` is the latest positive Android horizontal accuracy or `sqrt(8.0)`, `speedSigma` is the latest positive Android speed accuracy or `sqrt(0.1)`, and `accelerationVariance=0.3`。A live correction resets these terms。The serialized float is capped at `Float.MAX_VALUE` if needed。
- When inertial assistance is unavailable, emit accepted live locations normally and allow the existing cached response behavior；do not generate fresh timestamps after live callbacks stop。

## Lifecycle

1. Authorized client connects: cancel delayed stop, acquire a non-reference-counted `PARTIAL_WAKE_LOCK`, initialize location manager, estimator, anchors, native filter and sensors, then request live updates。
2. GNSS/Fused becomes unavailable: no lifecycle transition occurs；the 500 ms predictor continues indefinitely while the client remains connected。
3. Client disconnects: retain current collection for existing `LOCATION_STOP_DELAY_MS=15000` to permit quick reconnect。
4. No reconnect within 15 seconds: unregister location/sensor listeners, stop ticker, destroy native handle, release the wake lock and retain only the persisted real anchor。Startup failure and service destruction also release the wake lock。
5. Later reconnect: initialize a new session from the freshest persisted/system real anchor。The 15-second delay is never a prediction-duration limit。

## Status And Failure Handling

- Expose user-visible states for `inertial initializing`, `inertial active`, `waiting for initial location`, and `inertial unsupported` without adding controls。
- Native load/create/predict/estimate failures SHALL be logged, mark inertial assistance unavailable for that collection session, and fall back to forwarding live locations。They SHALL NOT crash the foreground service or emit non-finite coordinates。
- Persistent anchor parse failure SHALL discard only that invalid anchor and continue with system/live locations。

## Decisions

### Decision: Latest real location without age limit

Required for indoor/no-signal startup。Age is not used as rejection because the user prefers continuity over bounded error；published prediction accuracy represents growing uncertainty。

### Decision: Weight poor locations instead of a threshold

Android accuracy becomes measurement covariance。This addresses weak signal without an arbitrary cutoff or mode switch。

### Decision: Native upstream filter, thin local integration

The selected maintained library already owns Kalman prediction/correction and geographic conversion。The project only supplies Android data and JNI, avoiding a second filter implementation。

### Decision: Keep the existing 15-second disconnect delay

It saves power after the consumer disappears while allowing quick reconnect。Continuous prediction remains unlimited whenever the authorized client is connected。

## Validation Strategy

- Automated: recursive submodules present；`./gradlew assembleDebug lintDebug`；`git --no-pager diff --check`。
- Static: no `proto/location.proto` or client behavior changes；native build performs no network fetch。
- Manual target-device scenarios: live correction；GNSS loss with increasing timestamps/accuracy；indoor restart from cached anchor；reacquisition correction；missing-anchor waiting；disconnect under/over 15 seconds；unsupported-sensor fallback。
- Do not add broad test infrastructure。Add only narrowly scoped tests if estimator-independent Java calculations are extracted and an existing test task can run them。

## Validation Evidence

- Automated: `ANDROID_HOME="$HOME/Android/Sdk" ANDROID_SDK_ROOT="$HOME/Android/Sdk" ./gradlew assembleDebug lintDebug` passed on 2026-09-13 for both applications and all four default server ABIs。
- Static: recursive submodule revisions verified；`git --no-pager diff --check` passed；no client/shared/proto behavior change in this workspace。
- Independent review: wake-lock lifecycle, true-north correction, missing-velocity handling, all-provider cached anchors, monotonic native timestamps, stale-anchor uncertainty and live-callback ordering reviewed；all reported high/medium findings were repaired。
- Human: not run；target-device scenarios remain required, so this workspace stays `status: draft`。

## Risks And Failure Handling

- Unlimited dead reckoning can become unusably wrong；this is accepted product behavior and must remain visible through increasing accuracy。
- Rotation-vector quality varies by device and magnetic environment。No map/vehicle constraints are added without a separate approved change。
- If NDK integration or upstream source cannot build on supported Android ABIs, keep this workspace draft, record exact evidence, and do not claim completion。
