---
id: tic-0b25
status: closed
type: story
priority: 2
delivery: code
created: 2026-09-11
parent: tic-c8d1
---
# Simplify CatalogLifecycleRemediationTest

File: `river-engine/src/test/java/io/riverdb/engine/schema/catalog/CatalogLifecycleRemediationTest.java`. Baseline slopwatch score: **125.095**.

## Approach

Move catalog lifecycle fixture construction, durable record builders and shared
assertions into one package-private test support owner. Keep all 23 scenarios and
explicit resource cleanup in the test class; use record accessors for its moved
Opened carrier. Preserve allocation checks and encoded byte boundaries.

## Acceptance

The file and any extracted files score below 90 with the unchanged full-repository
`slopwatch -follow=false -include-tests -format json -limit 0 .` scan. Preserve
behavior, public contracts and failure cleanup. No suppressions, scorer changes,
new per-row allocation, or arbitrary file splitting. Luna/high codes; Sol/high
reviews; the lead reviews architectural effects across adjacent owners.
Run focused `river-engine` checks and the epic's light performance check, record the
before/after score and result, then integrate this ticket independently.

## Validation

Implementation `c56c8e97`; Luna/high, Sol/high and lead accepted. All 23
scenario bodies retain their assertions and lifetimes; only moved record fields
use accessors. Scores: test 14.330 (from 125.095), support 14.854.
All 23 focused tests passed without failures, errors or skips; engine checks and
installed workload build passed in 11s. Log:
`/private/tmp/river-score-jdbc-tic-0b25-gradle.log`.
Light JVM sample/all, four workers, one warehouse, seed 42, max-retries 20,
5s warmup/10s measured, version `tic-0b25-c56c8e97-jvm`: 298.18 TPS,
p99 64.389ms, 522 retries; zero failed/unknown, valid invariants, clean stop.
Artifact: `river_harness_20260911_054518_5390330b` under harness runs.
No observed regression; test-only change makes no performance claim.
