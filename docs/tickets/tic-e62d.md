---
id: tic-e62d
status: closed
type: story
priority: 2
delivery: code
created: 2026-09-11
parent: tic-c8d1
---
# Simplify DatabaseResourceGovernorTest

File: `river-engine/src/test/java/io/riverdb/engine/runtime/DatabaseResourceGovernorTest.java`. Baseline slopwatch score: **101.255**.

## Approach

Move shared demand/counter assertions, governor setup, allocation/thread helpers
and the Fixture record into one package-private support owner. Preserve all 22
scenarios, conservation, ownership and allocation proofs. Migrate every moved
record component access to its accessor.

## Acceptance

The file and any extracted files score below 90 with the unchanged full-repository
`slopwatch -follow=false -include-tests -format json -limit 0 .` scan. Preserve
behavior, public contracts and failure cleanup. No suppressions, scorer changes,
new per-row allocation, or arbitrary file splitting. Luna/high codes; Sol/high
reviews; the lead reviews architectural effects across adjacent owners.
Run focused `river-engine` checks and the epic's light performance check, record the
before/after score and result, then integrate this ticket independently.

## Validation

Implementation `7e4cdbd1`; Luna/high, Sol/high and lead accepted. Test and
support both score 0. All 22 focused tests passed without failures, errors or
skips; engine checks and installTps passed in 8s. Log:
`/private/tmp/river-score-jdbc-tic-e62d-gradle.log`.
Light sample/all JVM workload: 4 workers, 1 warehouse, seed 42, max-retries 20,
5s warmup and 10s measured, version `tic-e62d-7e4cdbd1-jvm`: 307.70 TPS,
p99 62.063ms. Zero failed/unknown outcomes, valid invariants and graceful
inactive cleanup. Artifact: `river_harness_20260911_061657_323e7a6a` under
harness runs. No observed regression; test-only change makes no speedup claim.
