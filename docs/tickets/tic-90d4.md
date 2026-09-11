---
id: tic-90d4
status: closed
type: story
priority: 2
delivery: code
created: 2026-09-11
parent: tic-c8d1
---
# Simplify FaultingDurableDirectory

File: `river-engine/src/test/java/io/riverdb/engine/testsupport/fault/FaultingDurableDirectory.java`. Baseline slopwatch score: **120.929**.

## Approach

Remove the no-op diagnostic `record` facade and pure calculations it discards.
Separate the existing file handle, durable entry image and shared fault sequencer.
Directory and files use the same sequencer directly under the directory monitor;
the directory retains crash generations, namespace state and handle accounting.
Delete forwarding chains and preserve all material fault and persistence behavior.

## Acceptance

The file and any extracted files score below 90 with the unchanged full-repository
`slopwatch -follow=false -include-tests -format json -limit 0 .` scan. Preserve
behavior, public contracts and failure cleanup. No suppressions, scorer changes,
new per-row allocation, or arbitrary file splitting. Luna/high codes; Sol/high
reviews; the lead reviews architectural effects across adjacent owners.
Run focused `river-engine` checks and the epic's light performance check, record the
before/after score and result, then integrate this ticket independently.


## Validation

Implementation `bee220fb` on `ticket/tic-90d4-fault-directory`.
FaultingDurableDirectory falls from 120.929 to 29.4298; FaultingDurableFile scores
18.6385, FaultingDurableEntry and FaultingDurableFaultBoundary both 0.
Root and Sol reviewed the shared monitor/sequence/decision, crash generations,
stale handle close, result publication, BEFORE/AFTER timing, short/partial/torn
writes, durable range publication and removal of only observationally dead work.

With `--no-daemon`, the focused DatabaseControlStoreTest (6),
CheckpointControlStoreTest (25) and IndexedGroupCommitFaultTest (20) cases all
passed: 51 tests, zero failures/errors/skips. Engine checks and TPS installation
passed in 19 seconds. Log: `/private/tmp/river-score-jdbc-tic-90d4-gradle.log`.

Light JVM sample all, four workers, one warehouse, seed 42, 20 retries,
5-second warmup and 10-second measurement: **281.52 TPS**, p99 **60.424 ms**,
466 retries, zero failed/unknown outcomes, passed invariants and graceful inactive
cleanup. This test-only change leaves production source unchanged; the result is
within the current short-sample range, with no performance claim.
Version `tic-90d4-bee220fb-jvm`; artifact
`/Users/blater/src/ingres/river-harness/runs/river_harness_20260911_070237_ff2ce2b6`.

Delivered in `perf-checkpoint-20260911-score-first51`.
