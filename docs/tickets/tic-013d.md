---
id: tic-013d
status: closed
type: story
priority: 2
delivery: code
created: 2026-09-11
parent: tic-c8d1
---
# Simplify RiverDaemonRuntimeRecords

File: `river-server-app/src/main/java/io/riverdb/server/app/RiverDaemonRuntimeRecords.java`. Baseline slopwatch score: **384.232**.

## Approach

Review `RiverDaemonRuntimeRecords.cleanupCurrentWithRuntimeRoot`, `RiverDaemonRuntimeRecords.recoverStaleWithRuntimeRoot`, `RiverDaemonRuntimeRecords.write` first. Separate their distinct validation, execution and cleanup responsibilities into concrete local operations; flatten status-dependent control flow while preserving ordering and ownership. Reuse an existing owner where one exists, and avoid new delegation layers that merely move branches.

## Acceptance

The file and any extracted files score below 90 with the unchanged full-repository
`slopwatch -follow=false -include-tests -format json -limit 0 .` scan. Preserve
behavior, public contracts and failure cleanup. No suppressions, scorer changes,
new per-row allocation, or arbitrary file splitting. Luna/high codes; Sol/high
reviews; the lead reviews architectural effects across adjacent owners.
Run focused `river-server-app` checks and the epic's light performance check, record the
before/after score and result, then integrate this ticket independently.

## Delivery evidence

Runtime lifecycle ownership is split across codec/model, publication, stale recovery,
current cleanup, ready-record access and raw runtime storage owners. River-owned callers
use those owners directly while `RiverDaemonRuntimeRecords` retains the public stale-recovery
entrypoint and lifecycle metadata. The final focused scan leaves every original and extracted
file below 90 (maximum **49.639**). Focused server compile and runtime, identity, stop,
stop-request and client tests passed; the required server check and TPS installation are run
from the isolated ticket worktree and passed.


Validation: source `b12f3518`, Luna/high implemented; Sol/high and lead approved.
Original score 18.390 (384.232 before); seven new owners all below 90, maximum 49.639.
Server-app checks and installTps passed with `--no-daemon`. Runtime/ready bytes,
identity validation, force/close order and missing-runtime distinctions preserved.
The epic's light JVM sample/all passed at 308.64 TPS, p99 58.491ms, 506 retries,
zero failed/unknown outcomes, valid invariants and graceful cleanup. Startup 1.329s,
stop 0.872s. No observed regression against recent integrated control 314.68 TPS.
Artifact: `/Users/blater/src/ingres/river-harness/runs/river_harness_20260911_034035_92f33862`.

Delivered at `perf-checkpoint-20260911-score-first13`.
