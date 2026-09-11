---
id: tic-b251
status: in_progress
type: story
priority: 2
delivery: code
created: 2026-09-11
parent: tic-c8d1
---
# Simplify RiverDaemonIdentity

File: `river-server-app/src/main/java/io/riverdb/server/app/RiverDaemonIdentity.java`. Baseline slopwatch score: **931.632**.

## Approach

Separate first creation, restart admission and staged-creation recovery. Give resource ownership and cleanup one explicit owner per operation; simplify completeCreate, recoverCreate and residue cleanup without changing identity, lock, crash-recovery or filesystem guarantees.

## Acceptance

The file and any extracted files score below 90 with the unchanged full-repository
`slopwatch -follow=false -include-tests -format json -limit 0 .` scan. Preserve
behavior, public contracts and failure cleanup. No suppressions, scorer changes,
new per-row allocation, or arbitrary file splitting. Luna/high codes; Sol/high
reviews; the lead reviews architectural effects across adjacent owners.
Run focused `river-server-app` checks and the epic's light performance check, record the
before/after score and result, then integrate this ticket independently.

## Implementation notes

Ownership is split at the existing lifecycle boundaries: admission owns directory and lock
acquisition, staged recovery owns bootstrap namespace validation and repair, and commit owns
publication plus residue cleanup. `RiverDaemonIdentity` remains the public façade and
`IdentityResult` remains the sole retained-capability carrier; helpers use concrete package-local
classes and preserve force-before-remove ordering and first-failure cleanup precedence.


## Validation

Final source `4de3d42a`: original file **0**, ten new lifecycle owners all below
90 (maximum **65.480**, creation); identity test **5.688**. The unchanged
IdentityRecords file belongs to separate ticket `tic-2de1`.

Luna/high implemented; Sol/high and the lead approved after correcting stage
handle cleanup, benign CLOSED handling and recovery error precedence. Focused
identity/credential/runtime tests and `:river-server-app:check
:river-bench:installTps` passed with `--no-daemon`. Fault tests exercise closure
of an acquired malformed stage and publication after a benign CLOSED result.

The epic's light JVM sample at version `tic-b251-4de3d42a-jvm` passed at
269.52 TPS, p99 65.372ms, 426 retries, zero failed/unknown outcomes, passing
invariants and graceful shutdown. Startup 1.279s; stop 0.879s. Adjacent unchanged
control: 258.89 TPS. No observed regression in this short diagnostic.
Artifact: `/Users/blater/src/ingres/river-harness/runs/river_harness_20260911_025827_90081ad0`.
Accepted at `perf-checkpoint-20260911-score-first8`.
