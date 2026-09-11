---
id: tic-3a4f
status: in_progress
type: story
priority: 2
delivery: code
created: 2026-09-11
parent: tic-c8d1
---
# Simplify RiverDriverTest

File: `river-jdbc/src/test/java/io/riverdb/jdbc/RiverDriverTest.java`. Baseline slopwatch score: **166.674**.

## Approach

Organize the fixture and scenarios by the behavior they prove; start with `RiverDriverTest.driverManagerExecutesStreamingSqlTransactionsAndDurableReopen`, `RiverDriverTest.bigintComparisonsReachScansIndexesJoinsAggregatesAndMutations`, `RiverDriverTest.streamsLongCatalogNamesThroughJdbcAndReopens`. Share setup only where ownership and assertions stay explicit; remove redundant cases only with a named retained proof.

## Acceptance

The file and any extracted files score below 90 with the unchanged full-repository
`slopwatch -follow=false -include-tests -format json -limit 0 .` scan. Preserve
behavior, public contracts and failure cleanup. No suppressions, scorer changes,
new per-row allocation, or arbitrary file splitting. Luna/high codes; Sol/high
reviews; the lead reviews architectural effects across adjacent owners.
Run focused `river-jdbc` checks and the epic's light performance check, record the
before/after score and result, then integrate this ticket independently.

## Delivery evidence

The four suites retain all 19 original tests. One fixture owns embedded database,
server, authenticated client setup and durable reopen; one assertion helper owns
shared column-metadata checks. Partial startup failure and repeated close retain
explicit cleanup. Sol/high reviewed the final code; the lead accepted these
boundaries. No production code changed.

Final scores: RiverDriverTest **86.617**, fixture **67.380**, streaming suite
**54.811**, bigint suite **38.074**, catalog suite **34.330**, metadata assertions
**0**. The unchanged scanner includes tests. All 19 focused tests passed with
zero failures, errors or skips; JVM benchmark distribution installation passed.

Light workload: `sample all`, four workers, one warehouse, seed 42, max retries
20, 5s warmup and 10s measurement, GraalVM 25 JVM with 1GiB heap. Version
`tic-3a4f-7af24dac-jvm` produced **252.31 committed TPS**, p99 **65.339ms**,
418 retries, zero failed/unknown outcomes, passing invariants and graceful stop
back to inactive. This lies within the adjacent controls of 240.07 and 294.15 TPS;
the short diagnostic supports acceptance, not a speed claim.

Artifact: `/Users/blater/src/ingres/river-harness/runs/river_harness_20260911_021326_73013c0b`.
Source commit: `7af24dac402edc1741d9ea5479b8ce12bff2b2f0`.
