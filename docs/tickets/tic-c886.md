---
id: tic-c886
status: closed
type: story
priority: 2
delivery: code
created: 2026-09-11
parent: tic-c8d1
---
# Simplify RiverDaemonInstance

File: `river-server-app/src/main/java/io/riverdb/server/app/RiverDaemonInstance.java`. Baseline slopwatch score: **221.321**.

## Approach

Review `RiverDaemonInstance.openCreate`, `RiverDaemonInstance.closeServices`, `RiverDaemonInstance.close` first. Separate their distinct validation, execution and cleanup responsibilities into concrete local operations; flatten status-dependent control flow while preserving ordering and ownership. Reuse an existing owner where one exists, and avoid new delegation layers that merely move branches.

## Acceptance

The file and any extracted files score below 90 with the unchanged full-repository
`slopwatch -follow=false -include-tests -format json -limit 0 .` scan. Preserve
behavior, public contracts and failure cleanup. No suppressions, scorer changes,
new per-row allocation, or arbitrary file splitting. Luna/high codes; Sol/high
reviews; the lead reviews architectural effects across adjacent owners.
Run focused `river-server-app` checks and the epic's light performance check, record the
before/after score and result, then integrate this ticket independently.

## Validation

Accepted `b4b2e7e7`: Instance retains all live resources and synchronized close
state. Stateless Admission owns identity admission/handoff; Startup owns ordered
service assembly. Scores: Instance 78.604 (221.321 before), Admission 19.765,
Startup 23.594. Sol/high and lead approved exact validation, reset, ownership,
credential, publication and cleanup ordering.

All 41 focused lifecycle tests passed, as did server-app checks/installTps. Logs:
`/private/tmp/river-tic-c886-focused.log`, `/private/tmp/river-tic-c886-check.log`.
Epic light workload `tic-c886-b4b2e7e7-jvm` passed at 310.71 TPS, p99 59.343 ms,
489 retries, zero failed/unknown outcomes, valid invariants and graceful cleanup.
Startup 1.329s, stop 0.794s. Artifact:
`/Users/blater/src/ingres/river-harness/runs/river_harness_20260911_050128_fb24d8de`.
No observed regression against recent controls; no speedup claim.

Combined path/startup standalone validation: approved O3/PGO build passed in
1m45s and actual native CLI start, generated credential authentication,
create/insert/select and graceful stop passed. Logs:
`/private/tmp/river-score-20260911/integration-first26-native.log` and
`integration-first26-native-smoke.log`.
