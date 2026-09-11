---
id: tic-6d7c
status: closed
type: story
priority: 2
delivery: code
created: 2026-09-11
parent: tic-c8d1
---
# Simplify RiverDaemonTargets

File: `river-server-app/src/main/java/io/riverdb/server/app/RiverDaemonTargets.java`. Baseline slopwatch score: **181.796**.

## Approach

Share runtime-record admission used by listing and endpoint resolution; keep endpoint filtering before target acquisition. Separate user-facing row formatting only if still needed. Preserve stale-record warnings, duplicate endpoint conflict, lock revalidation and exact close/status ordering. Reuse current runtime codec/storage owners; do not add a second registry or lifetime owner.

## Acceptance

The file and any extracted files score below 90 with the unchanged full-repository
`slopwatch -follow=false -include-tests -format json -limit 0 .` scan. Preserve
behavior, public contracts and failure cleanup. No suppressions, scorer changes,
new per-row allocation, or arbitrary file splitting. Luna/high codes; Sol/high
reviews; the lead reviews architectural effects across adjacent owners.
Run focused `river-server-app` checks and the epic's light performance check, record the
before/after score and result, then integrate this ticket independently.

## Validation

Accepted `da7f4f4b`: runtime records are read, closed, parsed and admitted once;
target listing/resolution share the same admission owner. Immutable admitted
record checks no longer repeat in callers; live checksum/lock revalidation remains.
Targets 55.639 (181.796 before); Admission 0. Sol/high and lead approved status,
warning, endpoint filtering and collision/resource cleanup ordering.

All 44 focused tests and server checks/installTps passed in one invocation, log
`/private/tmp/river-tic-6d7c-focused-check.log`. Epic light workload
`tic-6d7c-da7f4f4b-jvm`: 317.40 TPS, p99 61.374 ms, 517 retries, zero failed/
unknown outcomes, valid invariants and graceful cleanup; start 1.328s, stop 0.845s.
Artifact: `/Users/blater/src/ingres/river-harness/runs/river_harness_20260911_051352_00224157`.
No observed regression against recent controls; no speedup claim.
