---
id: tic-c55f
status: closed
type: story
priority: 2
delivery: code
created: 2026-09-11
parent: tic-c8d1
---
# Simplify RiverdForeground

File: `river-server-app/src/main/java/io/riverdb/server/app/RiverdForeground.java`. Baseline slopwatch score: **174.887**.

## Approach

Move the existing namespace preparation sequence into RiverDaemonPathParents:
verify, prepare datadir/optional-ready parents, ensure runtime root, close, then
verify again after mutation. Keep its call after resource admission and before
identity mutation. Preserve exact statuses and keep foreground lifetime unchanged.

## Acceptance

The file and any extracted files score below 90 with the unchanged full-repository
`slopwatch -follow=false -include-tests -format json -limit 0 .` scan. Preserve
behavior, public contracts and failure cleanup. No suppressions, scorer changes,
new per-row allocation, or arbitrary file splitting. Luna/high codes; Sol/high
reviews; the lead reviews architectural effects across adjacent owners.
Run focused `river-server-app` checks and the epic's light performance check, record the
before/after score and result, then integrate this ticket independently.

## Validation

Accepted `0c21686a`: PathParents owns the existing ordered namespace preparation
sequence, called at the same pre-mutation boundary. No new owner/state or lifetime
change. Foreground 49.117 (174.887 before); PathParents 18.946. Sol/high and lead
approved exact verify/mutate/close/reverify ordering and statuses.

All 12 focused tests and server checks/installTps passed, log
`/private/tmp/river-tic-c55f-focused-check.log`. Epic light workload
`tic-c55f-0c21686a-jvm`: 302.06 TPS, p99 63.078 ms, 481 retries, zero failed/
unknown outcomes, valid invariants and graceful cleanup; start 1.328s, stop 0.811s.
Artifact: `/Users/blater/src/ingres/river-harness/runs/river_harness_20260911_052635_b150d152`.
No observed regression against recent controls; no speedup claim.
