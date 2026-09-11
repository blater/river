---
id: tic-ed05
status: closed
type: story
priority: 2
delivery: code
created: 2026-09-11
parent: tic-c8d1
---
# Simplify SqlParserTest

File: `river-sql/src/test/java/io/riverdb/sql/SqlParserTest.java`. Baseline slopwatch score: **140.493**.

## Approach

Separate point-statement and nested-query scenarios from the main grammar suite,
with shared predicate assertions and parameter/text support. Preserve all 49
scenario bodies, discovery and allocation guard; keep each helper in one owner.

## Acceptance

The file and any extracted files score below 90 with the unchanged full-repository
`slopwatch -follow=false -include-tests -format json -limit 0 .` scan. Preserve
behavior, public contracts and failure cleanup. No suppressions, scorer changes,
new per-row allocation, or arbitrary file splitting. Luna/high codes; Sol/high
reviews; the lead reviews architectural effects across adjacent owners.
Run focused `river-sql` checks and the epic's light performance check, record the
before/after score and result, then integrate this ticket independently.

## Validation

Accepted `58c545cf`: 29 grammar/scalar, 6 point-statement and 14 nested-query
scenarios retain all 49 original method bodies byte-for-byte and 1,402 assertions.
Predicate and parameter/text support each have one owner. All five files score
below 90, maximum 8.390 (original 140.493). Sol/high and lead approved helpers,
visibility and discovery. All 49 tests passed without skips, including the warmed
allocation guard; SQL checks/installTps passed in 6s. Log:
`/private/tmp/river-score-jdbc-tic-ed05-gradle.log`.

Test-only epic light workload `tic-ed05-58c545cf-jvm`: 313.42 TPS, p99 61.506 ms,
524 retries, zero failed/unknown outcomes, valid invariants and graceful cleanup.
Artifact: `/Users/blater/src/ingres/river-harness/runs/river_harness_20260911_051610_54d5388a`.
