---
id: tic-8c5e
status: closed
type: story
priority: 2
delivery: code
created: 2026-09-11
parent: tic-c8d1
---
# Simplify BenchmarkSchemaValidator

File: `river-bench/src/main/java/io/riverdb/bench/harness/BenchmarkSchemaValidator.java`. Baseline slopwatch score: **94.224**.

## Approach

Keep parsing and recursive JSON Schema validation in BenchmarkSchemaValidator.
Move the existing sample, result and streaming-manifest cross-field rules into
one concrete artifact-semantics owner. Preserve schema dispatch, malformed-input
handling, error strings and error ordering. Add no schema framework or new rules.

## Acceptance

The file and any extracted files score below 90 with the unchanged full-repository
`slopwatch -follow=false -include-tests -format json -limit 0 .` scan. Preserve
behavior, public contracts and failure cleanup. No suppressions, scorer changes,
new per-row allocation, or arbitrary file splitting. Luna/high codes; Sol/high
reviews; the lead reviews architectural effects across adjacent owners.
Run focused `river-bench` checks and the epic's light performance check, record the
before/after score and result, then integrate this ticket independently.


## Validation

Implementation `ba04b7ac` (initial extraction `9881c2f4`) on
`ticket/tic-8c5e-benchmark-schema`. BenchmarkSchemaValidator falls from 94.224
to 13.1147; BenchmarkArtifactSemantics scores 40.8154 and the specialized
BenchmarkStreamingManifestSemantics 27.3697. Root and Sol reviewed the exact
schema dispatch, malformed-object handling, error strings and ordering, and
cross-field predicates. Missing retained imports and an inaccurate comment were
fixed before building. Structural and semantic validation still both report
errors for invalid objects in their original order.

With `--no-daemon`, BenchmarkSchemaValidatorTest (6) and
StreamingBenchmarkArtifactWriterTest (8) passed: 14 tests, no failures/errors/skips.
Bench checks and TPS installation passed in 9 seconds. Log:
`/private/tmp/river-score-jdbc-tic-8c5e-gradle.log`.

Light JVM sample all, four workers, one warehouse, seed 42, 20 retries,
5-second warmup/10-second measurement: **271.98 TPS**, p99 **64.094 ms**,
zero failed/unknown outcomes, passed invariants and graceful inactive cleanup.
The result is within current short-run variation; no performance claim.
Version `tic-8c5e-ba04b7ac-jvm`; artifact
`/Users/blater/src/ingres/river-harness/runs/river_harness_20260911_071144_ff4c1be9`.

Delivered in `perf-checkpoint-20260911-score-first51`.
