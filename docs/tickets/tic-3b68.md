---
id: tic-3b68
status: in_progress
type: story
priority: 2
delivery: code
created: 2026-09-11
parent: tic-c8d1
---
# Simplify IndexedTableStoreFactory

File: `river-engine/src/main/java/io/riverdb/engine/table/IndexedTableStoreFactory.java`. Baseline slopwatch score: **91.896**.

## Approach

Give existing IndexedOpenFiles sole ownership of unpublished file acquisition and
cleanup. Share row/version reopen-or-create, create acquisition, and cleanup status
precedence across factory/construction callers. Keep validation and store lease
ownership in the factory, sharing its repeated admission checks. Preserve acquisition
order, missing-page corruption mapping and exact failure ordering; add no state or class.

## Acceptance

The file and any extracted files score below 90 with the unchanged full-repository
`slopwatch -follow=false -include-tests -format json -limit 0 .` scan. Preserve
behavior, public contracts and failure cleanup. No suppressions, scorer changes,
new per-row allocation, or arbitrary file splitting. Luna/high codes; Sol/high
reviews; the lead reviews architectural effects across adjacent owners.
Run focused `river-engine` checks and the epic's light performance check, record the
before/after score and result, then integrate this ticket independently.

## Validation

Accepted `b56ee021`: existing IndexedOpenFiles owns ordered file acquisition and
exhaustive failure cleanup; Factory retains validation/lease ownership and
Construction retains allocation. No added class/state or allocation. All three
files score 0 (Factory 91.902 before). Sol/high and lead approved exact resource,
short-circuit and cleanup precedence.

All 30 focused store/recovery tests passed without skips; full engine checks and
installTps passed in 3m42s. Logs: `/private/tmp/river-tic-3b68-focused.log`,
`/private/tmp/river-tic-3b68-engine-install.log`. Epic light workload
`tic-3b68-b56ee021-jvm`: 305.75 TPS, p99 62.292 ms, 523 retries, zero failed/
unknown outcomes, valid invariants and graceful cleanup. Artifact:
`/Users/blater/src/ingres/river-harness/runs/river_harness_20260911_051138_2408a408`.
No observed regression against recent controls; no speedup claim.
