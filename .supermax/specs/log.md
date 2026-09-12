# Specifications Log

- 2026-09-12: Initialized specification entrypoint.
- 2026-09-12: Added draft change workspace `changes/replace-wifi-with-bluetooth/` with proposal, design, delta behavior spec, implementation checklist, and lifecycle index entry. Source: confirmed user requirements plus current Android manifests, services, connection code, preferences, and `proto/location.proto`. Validation: artifact structure and references checked; implementation/build/device validation not run.
- 2026-09-12: Implemented the draft Bluetooth change and recorded automated evidence. `ANDROID_HOME="$HOME/Android/Sdk" ANDROID_SDK_ROOT="$HOME/Android/Sdk" ./gradlew clean assembleDebug lintDebug`, `git --no-pager diff --check`, removed-feature scanning, and independent code review passed. Human phone/tablet validation remains not run, so the change stays `status: draft` and no stable spec or `.supermax/AGENTS.md` merge was performed.
