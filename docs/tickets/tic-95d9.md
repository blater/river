---
id: tic-95d9
status: closed
type: story
priority: 2
delivery: code
created: 2026-09-11
parent: tic-c8d1
---
# Simplify CheckpointControlStoreTest

File: `river-engine/src/test/java/io/riverdb/engine/checkpoint/CheckpointControlStoreTest.java`. Baseline slopwatch score: **92.773**.

## Approach

Organize the fixture and scenarios by the behavior they prove; start with `CheckpointControlStoreTest.finalDirectoryForceCrashBoundaryDeterminesCheckpointAuthority`, `CheckpointControlStoreTest.sparseManifestForceCrashSelectsOneIntactRoot`, `FaultFixture.<init>`. Share setup only where ownership and assertions stay explicit; remove redundant cases only with a named retained proof.

## Acceptance

The file and any extracted files score below 90 with the unchanged full-repository
`slopwatch -follow=false -include-tests -format json -limit 0 .` scan. Preserve
behavior, public contracts and failure cleanup. No suppressions, scorer changes,
new per-row allocation, or arbitrary file splitting. Luna/high codes; Sol/high
reviews; the lead reviews architectural effects across adjacent owners.
Run focused `river-engine` checks and the epic's light performance check, record the
before/after score and result, then integrate this ticket independently.

## Validation

Accepted `bb883740`: all 25 existing scenarios remain in the test; shared fault
setup and assertions have one fixture owner. Both files score 0 (previously
92.773). Sol/high approved the extraction; lead review checked test discovery
and retained fault ordering. All 25 tests passed, no skips; engine checks and
`installTps` passed (`/private/tmp/river-score-jdbc-tic-95d9-gradle.log`).

The epic light workload, version `tic-95d9-bb883740-jvm`, passed at 272.81 TPS,
p99 61.047 ms, 450 retries, zero failed/unknown outcomes, all invariants and
shutdown passed. Artifact:
`/Users/blater/src/ingres/river-harness/runs/river_harness_20260911_044355_1f8afb38`.
This is a test-only refactor with no production changes.
