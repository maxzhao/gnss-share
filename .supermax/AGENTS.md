# GNSS Sharing System

> SuperMax project-level supplemental rules. Priority is lower than the main system prompt, Skill Gate, tool-safety rules, and loaded skill instructions.

## Project Scope

- Purpose: Share smartphone GNSS and inertially estimated location data with an Android car multimedia client over a one-to-one secure Bluetooth Classic RFCOMM connection.
- Ownership boundary: Android server/client applications, the server-only native inertial filter integration, their shared Android library, and the shared Protocol Buffers location schema in this repository.

## Project Root

`<project_dir>`

## Repository Map And Agent Routing

- Start with `settings.gradle`, root `build.gradle`, and `gradle/libs.versions.toml` for module membership, versions, repositories, and dependency policy.
- `server-app/` owns smartphone GNSS/Fused collection, inertial estimation, foreground-service lifecycle, authorized RFCOMM serving, and related UI. Its manifest and module `build.gradle` are authoritative for Android integration and build configuration; `src/main/cpp/` owns the server-only native filter/JNI build.
- `client-app/` owns RFCOMM connection/reconnection, received-location handling, mock-location publication, foreground-service control, and client UI. Its manifest and module `build.gradle` are authoritative for Android integration and build configuration.
- `shared/` owns Android code and resources reused by both applications, including the RFCOMM service/framing constants; inspect concrete classes before changing shared behavior.
- `proto/location.proto` is the source of truth for the wire message schema consumed by both application modules. Generated protobuf output under build directories is not source.
- `.github/workflows/release.yml` is the source of truth for release automation. `.gradle/`, module/root `build/`, IDE state, and `local.properties` are local or generated rather than source.

## Environment And Stack

- Android multi-project Gradle build with modules `:server-app`, `:client-app`, and `:shared`.
- Java 21 source/target compatibility; Android compile/target SDK 36.
- Protocol Buffers Java Lite uses `proto/location.proto` and `protoc` 3.21.7.
- The transport is secure Bluetooth Classic RFCOMM with one saved peer per application; `shared/src/main/java/dezz/gnssshare/shared/BluetoothContract.java` owns its UUID, framing, heartbeat, timeout, and retry constants.
- The server native inertial build uses NDK `28.2.13676358`, CMake `3.22.1`, C++17, and the recursively checked-out `mad-location-manager-lib` submodule pinned by Git.
- The server minimum SDK is 24; the client minimum SDK is 28.

## Commands And Validation

- Before shell commands, follow the current session Justfile check rules. This repository currently has no Justfile.
- Build both debug applications: `./gradlew assembleDebug`.
- Discover narrower module checks through `./gradlew tasks` and prefer the closest applicable Gradle task when changing one module.
- Release signing reads `KEY_PASSWORD` and `keystore.jks`; never inspect, print, or persist secrets.
- After behavior-affecting changes, run the closest relevant existing validation. Do not report unvalidated changes as complete.

## Execution Boundaries

- Preserve compatibility of `proto/location.proto` unless a coordinated server/client protocol change is requested.
- Preserve the one-to-one RFCOMM identity and framing contract unless a coordinated server/client behavior change is requested.
- Treat Gradle build output, `.cxx/`, and generated protobuf/native outputs as generated artifacts; edit their source configuration, JNI/C++ source, fixed submodule pointer, or schema instead.
- `.supermax/tasks/`: TaskAdmin internal storage. Load `task-admin` before task operations.
- `.supermax/drafts/`: temporary Agent drafts, not durable knowledge, task progress, or runtime source.
- `.supermax/`: independent project knowledge Vault. Read it through the entry chain below.

## Project Knowledge Vault

- External Vault ownership follows `project-admin`: only Git's verified primary checkout or an independent non-Git project may register/use Obsidian or manage Syncthing. Linked Worktrees maintain local `.supermax/` files only; do not redirect or create alternate identities.
- Required root zones are `inbox/`, `specs/`, `wiki/`, `tasks/`, `drafts/`, `translate-cache/`, `attachments/`, and `canvases/`. Create optional root categories only when project content requires them.
- Start retrieval at `.supermax/index.md`, then follow the appropriate direct category index. Capture and review enter through `.supermax/inbox/index.md`.
- Do not create retired `.supermax/knowledge/` or root `raw/` paths. Explicitly target this project Vault for graph operations; do not rely on an active default Vault.
- Syncthing watches ordinary changes. Run the guarded project-admin scan command only when the user explicitly requests a forced/manual scan.

## Project Knowledge And Source Entrypoints

- L0: `.supermax/AGENTS.md`
- L1: `.supermax/index.md`
- L1/L2: `.supermax/wiki/index.md`, `.supermax/specs/index.md`; review entry: `.supermax/inbox/index.md`
- L2: child indexes and selected notes under maintained categories

## Update Rules

- Update this file only for stable changes to project scope, source-of-truth routing, command entrypoints, validation, compatibility boundaries, or knowledge governance.
- Inspect relevant source-of-truth files before adding facts. Preserve user-authored sections and patch only the owning semantic section.
- Keep task progress, temporary debugging state, raw validation logs, research bodies, and drafts in their owning workflows or Vault zones, not here.
