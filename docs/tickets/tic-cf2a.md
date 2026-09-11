---
id: tic-cf2a
status: closed
type: story
priority: 2
delivery: code
created: 2026-09-11
parent: tic-c8d1
---
# Simplify TupleBTreeTestPageProvider

File: `river-storage/src/test/java/io/riverdb/storage/btree/TupleBTreeTestPageProvider.java`. Baseline slopwatch score: **118.054**.

## Approach

Separate validation/proof generation bookkeeping from actual page, generation,
root and pinned-reference ownership in the test provider. Preserve release-fault
timing, validation counters and writable-borrow transitions. Allocate the support
state once per provider; retain zero per-operation allocation.

## Acceptance

The file and any extracted files score below 90 with the unchanged full-repository
`slopwatch -follow=false -include-tests -format json -limit 0 .` scan. Preserve
behavior, public contracts and failure cleanup. No suppressions, scorer changes,
new per-row allocation, or arbitrary file splitting. Luna/high codes; Sol/high
reviews; the lead reviews architectural effects across adjacent owners.
Run focused `river-storage` checks and the epic's light performance check, record the
before/after score and result, then integrate this ticket independently.

## Validation

Implementation `3d8e0c5b`; Luna/high, Sol/high and lead accepted. Provider
23.777 (from 118.054), support 0. All 34 focused TupleBTree tests passed
without failures, errors or skips; storage checks/installTps passed in 5s.
Log: `/private/tmp/river-score-jdbc-tic-cf2a-gradle.log`.
Light sample/all JVM workload: 4 workers, 1 warehouse, seed 42, max-retries 20,
5s warmup and 10s measured, version `tic-cf2a-3d8e0c5b-jvm`: 318.93 TPS,
p99 58.950ms. Zero failed/unknown outcomes, valid invariants and graceful
inactive cleanup. Artifact: `river_harness_20260911_063201_50c0bad0` under
harness runs. No observed regression; test-only change makes no speedup claim.


Delivered in pushed master integration `4a3b4ef0`, checkpoint
`perf-checkpoint-20260911-score-first46`.
