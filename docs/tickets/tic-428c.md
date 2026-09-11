---
id: tic-428c
status: closed
type: story
priority: 2
delivery: code
created: 2026-09-11
parent: tic-c8d1
---
# Simplify LockExactTable

File: `river-tx/src/main/java/io/riverdb/tx/LockExactTable.java`. Baseline slopwatch score: **90.204**.

## Approach

Review `LockExactTable.tryAcquire`, `LockExactTable.holds`, `LockExactTable.valid` first. Separate their distinct validation, execution and cleanup responsibilities into concrete local operations; flatten status-dependent control flow while preserving ordering and ownership. Reuse an existing owner where one exists, and avoid new delegation layers that merely move branches.

## Acceptance

The file and any extracted files score below 90 with the unchanged full-repository
`slopwatch -follow=false -include-tests -format json -limit 0 .` scan. Preserve
behavior, public contracts and failure cleanup. No suppressions, scorer changes,
new per-row allocation, or arbitrary file splitting. Luna/high codes; Sol/high
reviews; the lead reviews architectural effects across adjacent owners.
Run focused `river-tx` checks and the epic's light performance check, record the
before/after score and result, then integrate this ticket independently.

## Review notes

Removed the private `createHolding` forwarding hop and call the existing
`LockExactAdmissionController` owner directly from `tryAcquire`. Admission
ordering, resource lookup, conflict checks, statuses, and allocation behavior
are unchanged. The unchanged scorer result is 90.204 before and 89.918 after;
focused transaction checks passed (details below).


## Validation

Final source `7d8f66df` removes only the private forwarding call to the existing
admission owner; arguments, ordering and allocation are unchanged. Score:
**89.918**, from 90.204. Luna/high implemented; Sol/high and the lead approved.
`:river-tx:check :river-bench:installTps` passed with `--no-daemon`.

The epic's light JVM sample/all run (`tic-428c-7d8f66df-jvm`) passed at
304.95 TPS, p99 64.618ms, 543 retries, zero failed/unknown outcomes, passing
invariants and graceful shutdown. Recent unchanged control 258.89 TPS; the short
samples do not establish a speedup or repeated regression.
Artifact: `/Users/blater/src/ingres/river-harness/runs/river_harness_20260911_030956_61ea73ba`.
Accepted at `perf-checkpoint-20260911-score-first8`.
