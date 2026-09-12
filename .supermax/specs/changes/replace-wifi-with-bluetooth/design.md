---
title: 一对一 RFCOMM GNSS 传输设计
created: 2026-09-12
updated: 2026-09-12
doc_role: design
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
  - server-app/src/main/AndroidManifest.xml
  - client-app/src/main/AndroidManifest.xml
  - server-app/src/main/java/dezz/gnssshare/server/GNSSServerService.java
  - client-app/src/main/java/dezz/gnssshare/client/ConnectionManager.java
  - client-app/src/main/java/dezz/gnssshare/client/GNSSClientService.java
  - proto/location.proto
confidence: high
---

# Design: replace-wifi-with-bluetooth

## Summary

直接用 Android 安全经典蓝牙 RFCOMM 替换 TCP Socket。两端继续使用现有字节流协议和 `LocationProto.ServerResponse`；不引入通用传输抽象、兼容层或第三方依赖。

## Context

- Proposal: `.supermax/specs/changes/replace-wifi-with-bluetooth/proposal.md`
- Delta spec: `.supermax/specs/changes/replace-wifi-with-bluetooth/specs/gnss-bluetooth-sharing/spec.md`
- Modules: `:server-app`, `:client-app`, `:shared`
- Android: compile/target SDK 36；server min SDK 24；client min SDK 28。
- `proto/location.proto` 是位置消息源，不修改字段或生成物。

## Ownership

| Owner | Responsibility | Must not own |
| --- | --- | --- |
| `shared` | RFCOMM 服务名、UUID、心跳字节、帧头长度等双方必须一致的常量 | 连接生命周期、设备选择、GNSS 或模拟定位 |
| `server-app` | 保存唯一平板、RFCOMM 监听与准入、单连接、GNSS 采集与发送 | 平板重连和模拟定位 |
| `client-app` | 保存唯一手机、RFCOMM 主动连接、心跳、断线重连、接收与模拟定位 | 手机 GNSS 采集或服务端准入 |
| `proto/location.proto` | `ServerResponse` 和 `LocationUpdate` 消息定义 | 传输选择与生命周期 |

依赖方向保持为 `server-app -> shared`、`client-app -> shared`；两应用不互相依赖。

## RFCOMM Contract

- Transport: secure Bluetooth Classic RFCOMM (`listenUsingRfcommWithServiceRecord` / `createRfcommSocketToServiceRecord`)。
- Service name: `GNSS Share Location`。
- Service UUID: `a8e7f1b0-3d4c-4f29-9e6a-7b8c0d1e2f30`。
- Server-to-client frame: 4-byte unsigned big-endian payload length followed by one serialized `LocationProto.ServerResponse`。
- Client-to-server heartbeat: one byte `0x01` every 1 second。
- A connection is stale after 3 seconds without expected heartbeat/response activity; owner closes it so normal reconnect/listen logic can resume。
- Preserve the current rule that the server sends its latest response immediately after an authorized connection and at least once per second while only heartbeat traffic exists。
- No schema, compression, handshake, protocol negotiation or application-layer encryption change。System pairing plus secure RFCOMM owns link authentication/encryption。

## Phone Server Flow

1. `MainActivity` launch checks location and `BLUETOOTH_CONNECT` permissions and asks the user to enable Bluetooth with the system flow when disabled。
2. When prerequisites are satisfied, launch idempotently starts `GNSSServerService`; no start/stop control is shown。
3. Service reads the single saved tablet identity. If absent or no longer bonded, it stays alive, opens no RFCOMM listener, and exposes `setup required` / `target unavailable` state。
4. With a bonded target, service registers the fixed RFCOMM service and blocks on `BluetoothServerSocket.accept()` in a worker。
5. After accept, compare `BluetoothSocket.getRemoteDevice().getAddress()` with the saved tablet address before sending data or starting GNSS。Close mismatches immediately。
6. Accept only one authorized active socket。Close the listening socket while that session is active; recreate it after disconnect。
7. Start existing GNSS/Fused Location updates only after the authorized client connects。After disconnect, preserve the current delayed GNSS stop behavior while the foreground service remains alive and returns to listening。
8. Bluetooth being turned off closes sockets and pauses listening；turning it back on resumes listening without requiring another service start。

## Tablet Client Flow

1. `MainActivity` launch checks `BLUETOOTH_CONNECT` and existing mock-location prerequisites and asks the user to enable Bluetooth when disabled。
2. When prerequisites are sufficient, launch idempotently starts `GNSSClientService`; no start/stop control is shown。
3. Service reads the single saved phone identity. If absent or no longer bonded, it stays alive but does not select another device or connect。
4. With a bonded target and enabled adapter, `ConnectionManager` opens one secure `BluetoothSocket` to the fixed UUID on that exact device。Only one connect attempt may run at a time。
5. Failed or lost connections schedule the next attempt after the existing 500 ms reconnect delay while the service runs, the target remains bonded, and Bluetooth is enabled。
6. On connection, receive the unchanged framed Protobuf stream and continue current location display, widget broadcast, new-timestamp filtering and mock-location publication。
7. Bluetooth being turned off pauses attempts；turning it back on resumes them。

## Device Binding And UI

- Pairing remains a prerequisite performed in Android system Bluetooth settings。Apps list `BluetoothAdapter.getBondedDevices()` only；they do not scan or initiate pairing。
- Phone UI provides one `Select tablet` / `Change tablet` action and displays the saved tablet name plus address。
- Tablet UI provides one `Select phone` / `Change phone` action and displays the saved phone name plus address。
- Selecting a device atomically replaces the previous identity。If a session targets the old identity, close it and immediately apply normal listen/connect behavior to the new target。
- If a saved identity is absent from bonded devices, retain it and show that the user must re-pair or explicitly choose a replacement。Never silently choose another bonded device。
- Remove server IP/gateway UI, network-interface display, old multi-device Bluetooth-trigger UI, and all manual service controls。Keep unrelated GNSS/Fused Location, permissions, location status, log export and static-jitter UI。

## Lifecycle And Platform Integration

- Both foreground services remain `START_STICKY` so ordinary process reclamation can recover after the user has launched the app。
- Removing an activity from recent tasks or locking either device does not stop its service。
- Remove boot receivers and `RECEIVE_BOOT_COMPLETED`; reboot clears runtime service state and no app starts until its launcher activity is opened。
- Remove in-app and notification stop actions。Android system-level force stop or equivalent OS service stop remains authoritative。After force stop, only a later explicit app launch restarts service。
- Declare classic Bluetooth hardware required。For API 31+, request `BLUETOOTH_CONNECT` at runtime；do not request `BLUETOOTH_SCAN` because discovery is out of scope。Keep legacy Bluetooth permissions only where required for pre-31 devices。
- Declare foreground service types `location|connectedDevice` and `FOREGROUND_SERVICE_CONNECTED_DEVICE` in addition to existing location service permissions。
- Remove `INTERNET`, network/Wi-Fi state/change permissions and TCP/network callback code。Remove server `android:persistent=true` so privileged installation cannot violate the no-boot-start contract。

## Decisions

### Decision: Classic RFCOMM, not BLE

- Choice: secure classic RFCOMM stream sockets。
- Rationale: current protocol is a bidirectional byte stream and Android directly provides server/client sockets。
- Rejected: BLE GATT would require chunking, characteristic design and flow control not required by the user。

### Decision: Device-address allowlist

- Choice: each side stores one bonded device name/address；server validates accepted socket address。
- Rationale: exactly matches the confirmed one-phone/one-tablet requirement with no application handshake。
- Limitation: authorization is device-level, not per-app within the authorized tablet。

### Decision: Direct replacement

- Choice: delete TCP/Wi-Fi and old Bluetooth trigger behavior in the same change。
- Rationale: the user explicitly rejected fallback, transition and compatibility paths。Old preference values remain inert and require no migration code。

## Validation Strategy

- Automated: `./gradlew assembleDebug`。
- Manual, on one target phone and tablet: pair and bind both sides；auto-start/connect；GNSS-to-mock-location data；background/lock persistence；disconnect/reconnect；wrong-tablet rejection；Bluetooth-disabled prompt；unpaired-target retention；no boot startup。
- Do not add broad unit-test infrastructure。Add only a narrow regression test if implementation extracts non-Android framing or authorization logic that is otherwise risky and directly testable。

## Validation Evidence

- Automated: `ANDROID_HOME="$HOME/Android/Sdk" ANDROID_SDK_ROOT="$HOME/Android/Sdk" ./gradlew clean assembleDebug lintDebug` passed on 2026-09-12.
- Static: `git --no-pager diff --check` and removed-feature reference scan passed.
- Independent review: current diff has no remaining high/medium-severity code findings.
- Human: not run; RFCOMM interoperability, device authorization, lifecycle, reconnect, and end-to-end GNSS/mock-location behavior require target phone/tablet validation.

## Risks And Failure Handling

- Vendor background restrictions may stop a foreground service despite correct Android APIs；report the target-device behavior rather than adding vendor-specific workarounds without approval。
- `BluetoothSocket` lacks the TCP read-timeout behavior currently used；implement heartbeat watchdogs that close stale sockets instead of emulating `java.net.Socket`。
- A blocked connect/accept must run off the main thread and be canceled by closing its socket during shutdown, target change or Bluetooth disable。Do not run concurrent connection loops。
- On build or manual validation failure, keep this workspace `status: draft`, record the exact failure, and do not merge into stable specs。
