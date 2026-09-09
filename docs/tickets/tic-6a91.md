---
id: tic-6a91
status: closed
type: story
priority: 1
assignee: blater
delivery: code
base-commit: da7a87947becbc879bbded31329d906e23f2e6c9
branch: ticket/tic-6a91-mapped-wal
created: 2026-09-09
---
# Replace WAL channel writes with mapped I/O

User-directed replacement of the WAL I/O mechanism. Use bounded mapped storage,
explicit mapping lifetimes, and mapped synchronization through the platform
boundary. Keep transaction semantics, group commit, and workload definitions intact.

## Acceptance

- WAL writes and commit synchronization use the mapped provider, with no legacy
  WAL write path or fallback flag.
- Growth, truncation, close/reopen, interrupted writes and corruption detection
  are covered; allocated capacity must not become apparent WAL records.
- Independent recovery/ownership review, affected-module checks, and real server
  restart validation pass.
- Repeat the same JVM TPC-C samples and isolated INSERT workload. Record mapped
  synchronization semantics accurately; do not infer power-loss equivalence
  from an API name or benchmark speed.

## Evidence

Starting source includes the completed local server-discovery fixes at da7a8794.
Two adjacent channel baselines before implementation: 169.71 and 168.47 committed
TPS, sample all, four workers, one warehouse, seed 42, retries 20, 15s warmup,
30s measurement. Logs: `/private/tmp/river-mapped-wal/baseline-{1,2}.log`.
Baseline Slopmark: `/private/tmp/river-mapped-wal-slopmark-before.txt`.

Native blocker diagnosed: GraalVM 25.0.4's
`SubstrateOptimizeSharedArenaAccessPhase` introduces an exception-path dependency
across exits of the same loop. Before/after graph replay is complete before the
pass and stalls immediately after its rewrite, before canonicalization. The
later loop-frequency NPE is a consequence. Clean O3/PGO reproduction and the
exact cycle are recorded in
`/private/tmp/river-mapped-wal/compiler-root-cause.md`.
No per-method optimizer exclusions have been added.

The source trigger was isolated in a standalone stateful cursor reproducer.
Restructuring only `IndexedVacuumRowCursor.next()` to use a controlled loop exit
preserves row/status/pin behavior and resolves the actual clean O3/PGO build.
No optimizer exclusions or global return-style rules were introduced. Evidence:
`/private/tmp/river-mapped-wal/native-cursor-refactor-clean.log` and the
`reproducer` directory alongside it.

Implementation validation: clean O3/PGO native build and full check passed. Native creation,
100 acknowledged commits, SIGKILL/reopen with every row/value checked, public
stop and readiness cleanup passed. Final tps-test reported OK, zero errors or
retries. Two native harness samples passed all invariants at 168.04 and 167.76
TPS, within the earlier native baseline range; no native gain is claimed.
The earlier JVM matched samples improved from 169.71/168.47 to 238.29/246.12 TPS.
Detailed commands/results are in `docs/performance-checkpoints.md` and
`docs/benchmark-log.md`.

## Promotion decision

User approved tag/merge/push on 2026-09-10 after reviewing the measurements.
Native p99 rose from 84.61/88.15 ms to 104.53/108.99 ms and retries from 624/572
to 795/778. Faster commits increasing contention is a plausible explanation,
not an established cause; the older native controls are not adjacent. Retain
this as a follow-up measurement question rather than claim a native speedup.
Checkpoint: `perf-checkpoint-20260910-mapped-wal`.
