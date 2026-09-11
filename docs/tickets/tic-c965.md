---
id: tic-c965
status: in_progress
type: story
priority: 2
delivery: code
created: 2026-09-11
parent: tic-c8d1
---
# Simplify SqlAggregateAccumulatorSet

File: `river-engine/src/main/java/io/riverdb/engine/sql/SqlAggregateAccumulatorSet.java`. Baseline slopwatch score: **102.407**.

## Approach

Share duplicated scalar accumulation between point and block inputs within the
existing class. Keep row-specific null/text extraction and conditional high-word
access in callers; classify numeric input once. The shared operation owns existing
numeric delegation and SUM/AVG/MIN/MAX selection. Preserve status, count, carry,
null publication, distinct storage and text scratch without new state or allocation.
Share text candidate comparison, winner copy and tail clearing after each input
path has prepared and admitted its candidate; retain candidate erasure/error timing.

## Acceptance

The file and any extracted files score below 90 with the unchanged full-repository
`slopwatch -follow=false -include-tests -format json -limit 0 .` scan. Preserve
behavior, public contracts and failure cleanup. No suppressions, scorer changes,
new per-row allocation, or arbitrary file splitting. Luna/high codes; Sol/high
reviews; the lead reviews architectural effects across adjacent owners.
Run focused `river-engine` checks and the epic's light performance check, record the
before/after score and result, then integrate this ticket independently.


## Validation

Implementation `4bb02e02` on `ticket/tic-c965-scalar-accumulation`:
102.407 → **86.5164**, no new owner or allocation. Root and Sol reviewed exact
numeric/high-word access, status/count/carry/null behavior, text candidate
publication, copying and stale-tail erasure. Review removed an unnecessary
nonnumeric high-word read before testing.

Ten scalar, grouped, block-view and distinct-store tests passed; engine checks,
source policy and TPS installation passed with `--no-daemon` in 14 seconds.
Log: `/private/tmp/river-tic-c965-gradle.log`.

Light external sample/all, four workers, one warehouse, seed 42, retry limit 20,
five-second warmup/ten-second measurement: **270.74 TPS**, p99 **72.810 ms**,
463 retries, zero failed/unknown outcomes, passed invariants and graceful inactive
cleanup. Within observed short-run variation; no performance claim. Version
`tic-c965-4bb02e02-jvm`; artifact
`/Users/blater/src/ingres/river-harness/runs/river_harness_20260911_081214_8b448b74`.
