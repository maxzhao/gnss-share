# Implementation Plan

## Goal

- Observable outcome: promote the three manually approved post-`6ea4554` change specifications to normative stable specifications, reconcile their lifecycle metadata, and capture evidence-backed behavior omitted from those approved texts as a separate draft gap delta without changing application behavior.

## Scope

- In scope:
  - Accept and merge `replace-wifi-with-bluetooth`, `add-inertial-dead-reckoning`, and `add-service-controls` into stable capability owners.
  - Record user-confirmed human review as passed and preserve existing automated evidence.
  - Add one retrospective draft change workspace for uncovered Bluetooth session failure/timing constraints, inertial sensor-sample constraints, and service-control fallback/corrupt-state behavior.
  - Update specification indexes/logs and stable project routing facts in `.supermax/AGENTS.md`.
  - Run repository build/lint, Markdown/reference checks, protocol no-diff and Git diff checks.
- Out of scope:
  - Android application behavior changes.
  - Rewriting legacy accepted `design.md` or `tasks.md`; retain them as historical evidence only.
  - Human-facing README revision.

## Authoritative Inputs

- Read first:
  - `.supermax/AGENTS.md`
  - `.supermax/specs/index.md`
  - `.supermax/specs/changes/index.md`
  - `.supermax/specs/changes/{replace-wifi-with-bluetooth,add-inertial-dead-reckoning,add-service-controls}/proposal.md`
  - Corresponding delta specs under each change workspace.
- External references:
  - User confirmation in the current chat that existing specs passed human review.
  - Git range `6ea4554e0e292e108465cb2378862a2a92092b3d..16107491dcee38250dcd8130b6b57f0311111fd5`.
- Constraints:
  - `proto/location.proto` remains unchanged.
  - Stable specs own observable behavior; implementation plans and volatile evidence remain in TaskAdmin.
  - Unreviewed omissions remain draft rather than being silently promoted to normative behavior.

## Current Facts

- Evidence-backed current behavior:
  - Three implemented draft change workspaces cover Bluetooth RFCOMM sharing, inertial location estimation, and service controls.
  - Their recorded automated validation passed; the user now confirms human review passed.
  - No accepted stable specification currently exists.
  - Code inspection found narrow behavior gaps not explicitly specified: exact malformed-frame/session recovery and response liveness, stale rotation-sample rejection, corrupt PID-marker handling, and direct-start failure Activity fallback.
  - `proto/location.proto` has no diff in the target Git range.

## Spec References

- Change/proposal:
  - `.supermax/specs/changes/replace-wifi-with-bluetooth/proposal.md`
  - `.supermax/specs/changes/add-inertial-dead-reckoning/proposal.md`
  - `.supermax/specs/changes/add-service-controls/proposal.md`
  - `.supermax/specs/changes/complete-post-init-spec-gaps/proposal.md`
- Delta specs:
  - `.supermax/specs/changes/replace-wifi-with-bluetooth/specs/gnss-bluetooth-sharing/spec.md`
  - `.supermax/specs/changes/add-inertial-dead-reckoning/specs/inertial-location-estimation/spec.md`
  - `.supermax/specs/changes/add-service-controls/specs/service-controls/spec.md`
  - `.supermax/specs/changes/complete-post-init-spec-gaps/specs/gnss-bluetooth-sharing/spec.md`
  - `.supermax/specs/changes/complete-post-init-spec-gaps/specs/inertial-location-estimation/spec.md`
  - `.supermax/specs/changes/complete-post-init-spec-gaps/specs/service-controls/spec.md`
- Stable owners:
  - `.supermax/specs/gnss-bluetooth-sharing/spec.md`
  - `.supermax/specs/inertial-location-estimation/spec.md`
  - `.supermax/specs/service-controls/spec.md`

## Approach

1. Create stable normative specs by merging only the manually approved requirements/scenarios from the three existing deltas and resolving the service-controls supersession explicitly.
2. Update those proposals and delta specs to accepted lifecycle state with stable owners, human/automated validation status, retained archive state and this task identity.
3. Create one new draft proposal with three small capability deltas for evidence-backed omissions; keep human validation `not-run` and do not merge those omissions into stable owners.
4. Update `.supermax/specs/index.md`, `.supermax/specs/changes/index.md`, `.supermax/specs/log.md`, and stable routing facts in `.supermax/AGENTS.md`.
5. Validate artifact structure, references, lifecycle consistency, unchanged protocol, Gradle build/lint and diff hygiene; then record convergence in TODO and complete the task.

## Decisions

- Decision: separate approved behavior from newly extracted omissions.
  - Choice: approved deltas become normative stable specs; omissions use one new draft change workspace.
  - Rationale: user approval applies explicitly to existing specs, while newly discovered details have code evidence but no recorded human review.
  - Rejected alternative: silently add omitted details to normative specs during promotion.
- Decision: keep three stable capability owners.
  - Choice: Bluetooth sharing, inertial estimation and service controls remain separate.
  - Rationale: each has a distinct actor/behavior/validation boundary.
  - Rejected alternative: one monolithic repository-wide specification.

## Affected Paths

- `.supermax/tasks/` — linked TaskAdmin plan/TODO and metadata.
- `.supermax/specs/gnss-bluetooth-sharing/spec.md` — normative Bluetooth behavior.
- `.supermax/specs/inertial-location-estimation/spec.md` — normative inertial behavior.
- `.supermax/specs/service-controls/spec.md` — normative lifecycle-control behavior.
- `.supermax/specs/changes/*` — accepted lifecycle convergence plus one draft gap workspace.
- `.supermax/specs/{index.md,changes/index.md,log.md}` — routing and lifecycle log.
- `.supermax/AGENTS.md` — stable transport/build routing facts.

## Risks And Failure Handling

- Risk: stable specs accidentally absorb unreviewed inferred behavior.
- Failure response: keep every newly extracted omission in the draft gap delta until separately reviewed.
- Risk: proposal/delta/stable lifecycle or links diverge.
- Failure response: run exact-path and frontmatter checks and do not mark the task done until all links resolve.
- Risk: native Android build environment fails.
- Failure response: record the exact command/error and leave task in review or in-progress; do not claim validation passed.

## Acceptance Criteria

- [ ] Three stable specs exist with `authority: normative`, `status: active`, complete requirements/scenarios and this task identity.
- [ ] Three original changes are `status: accepted`, `human: passed`, `automated: passed`, `archive_state: retained`, and `merged_to` their stable owners.
- [ ] Newly discovered omissions are persisted in one linked draft change workspace and are not silently merged into normative specs.
- [ ] Stable and change indexes/logs accurately route all artifacts.
- [ ] `.supermax/AGENTS.md` no longer identifies Wi-Fi/TCP as the current transport.
- [ ] `proto/location.proto` is unchanged.
- [ ] Relevant Gradle build/lint and repository consistency checks pass.

## Validation Plan

- Command/check: `ANDROID_HOME="$HOME/Android/Sdk" ANDROID_SDK_ROOT="$HOME/Android/Sdk" ./gradlew assembleDebug lintDebug`
- Expected result: `BUILD SUCCESSFUL` for both applications, including native server build.
- Command/check: `git --no-pager diff --check`
- Expected result: no whitespace errors.
- Command/check: `git --no-pager diff --exit-code 6ea4554e0e292e108465cb2378862a2a92092b3d..HEAD -- proto/location.proto`
- Expected result: no protocol schema change.
- Command/check: exact-path/frontmatter/index reference audit.
- Expected result: all task/spec links resolve and lifecycle states agree.
- Remaining manual review: only the newly extracted gap delta; the three existing deltas are user-confirmed as reviewed.
