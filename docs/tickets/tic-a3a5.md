---
id: tic-a3a5
status: in_progress
type: story
priority: 2
delivery: code
created: 2026-09-11
parent: tic-c8d1
---
# Simplify EmbeddedDatabase

File: `river-engine/src/main/java/io/riverdb/engine/EmbeddedDatabase.java`. Baseline slopwatch score: **164.297**.

## Approach

Separate cold deadlock formatting into a stateless diagnostics owner, remove
private opener forwarding, and express close as local primary-service and resource
release phases. Preserve the sole synchronized lifetime owner, eager follower
cleanup after failure, all status normalization, flags and unpublished cleanup.
Use existing first-failure policy instead of repeating it in follower loops.

## Acceptance

The file and any extracted files score below 90 with the unchanged full-repository
`slopwatch -follow=false -include-tests -format json -limit 0 .` scan. Preserve
behavior, public contracts and failure cleanup. No suppressions, scorer changes,
new per-row allocation, or arbitrary file splitting. Luna/high codes; Sol/high
reviews; the lead reviews architectural effects across adjacent owners.
Run focused `river-engine` checks and the epic's light performance check, record the
before/after score and result, then integrate this ticket independently.

## Validation

Implementation `b7d250a2`; Luna/high, Sol/high and lead review accepted.
EmbeddedDatabase scores 87.9032 (from 164.297); EmbeddedDeadlockDiagnostics 0.
All 33 focused tests passed without failures, errors or skips; engine checks and
installed workload build passed (`/private/tmp/river-tic-a3a5-build.log`).
Light JVM sample/all, four workers, one warehouse, seed 42, 20 retries,
5s warmup/10s measured: 303.24 TPS, p99 61.506ms, 504 retries; passed with
zero failed/unknown outcomes, valid invariants and graceful inactive cleanup.
Artifact: `river_harness_20260911_053252_5ef71c73` under the harness runs directory.
Version: `tic-a3a5-b7d250a2-jvm`. No observed regression; no speedup claim.
