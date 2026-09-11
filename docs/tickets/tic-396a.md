---
id: tic-396a
status: in_progress
type: story
priority: 2
delivery: code
created: 2026-09-11
parent: tic-c8d1
---
# Simplify IndexedRelationalWalHarnessTest

File: `river-engine/src/test/java/io/riverdb/engine/table/IndexedRelationalWalHarnessTest.java`. Baseline slopwatch score: **212.312**.

## Approach

Organize the fixture and scenarios by the behavior they prove; start with `IndexedRelationalWalHarnessTest.tupleLeafSplitPublishesInsideTwoMemberHybridGroupAndLeavesStoreReusable`, `IndexedRelationalWalHarnessTest.productionDispatchRetainsContiguousGroupAndPublishesOnlyFinalChunk`, `IndexedRelationalWalHarnessTest.predictTupleInsert`. Share setup only where ownership and assertions stay explicit; remove redundant cases only with a named retained proof.

## Acceptance

The file and any extracted files score below 90 with the unchanged full-repository
`slopwatch -follow=false -include-tests -format json -limit 0 .` scan. Preserve
behavior, public contracts and failure cleanup. No suppressions, scorer changes,
new per-row allocation, or arbitrary file splitting. Luna/high codes; Sol/high
reviews; the lead reviews architectural effects across adjacent owners.
Run focused `river-engine` checks and the epic's light performance check, record the
before/after score and result, then integrate this ticket independently.

## Review notes

Split the original harness into codec/retention, replay/recovery, live grouped
commit, and checkpoint lifecycle test suites. Shared concrete owners now hold
mutation/tuple builders, WAL record/group builders, database/WAL/session
resources, and registry/checkpoint assertions; superseded per-suite copies were
removed. The original 212.312 harness score is now below 90 for every extracted
test and fixture file, with 3,350 Java lines versus 3,197 before extraction.
Sol/high verified all 31 test scenarios and all 96 original method bodies remain
unchanged apart from ownership/whitespace; the lead rejected the initial duplicated
fixtures. Final commit `dcf87cfe`; maximum extracted score 87.624.
All 31 focused tests passed (5.317s), with engine checks and benchmark installation
passing in 11s. Log: `/private/tmp/river-score-20260911/tic-396a-tests.log`.
The epic's light JVM sample/all run passed at 274.85 TPS, p99 60.817ms, 430 retries,
zero failed/unknown outcomes, valid invariants and graceful cleanup. Artifact:
`/Users/blater/src/ingres/river-harness/runs/river_harness_20260911_035115_969e925d`.
Production code is unchanged; this sample is a smoke, not a speed claim.
