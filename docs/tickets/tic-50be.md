---
id: tic-50be
status: in_progress
type: story
priority: 2
delivery: code
created: 2026-09-11
parent: tic-c8d1
---
# Simplify RelationalSchemaLifecycle

File: `river-engine/src/main/java/io/riverdb/engine/relational/RelationalSchemaLifecycle.java`. Baseline slopwatch score: **90.114**.

## Approach

Review `RelationalSchemaLifecycle.reserveIndexBuild`, `RelationalSchemaLifecycle.runIndexBuildBatches`, `RelationalSchemaLifecycle.scanUniqueIndexBatch` first. Separate their distinct validation, execution and cleanup responsibilities into concrete local operations; flatten status-dependent control flow while preserving ordering and ownership. Reuse an existing owner where one exists, and avoid new delegation layers that merely move branches.

## Acceptance

The file and any extracted files score below 90 with the unchanged full-repository
`slopwatch -follow=false -include-tests -format json -limit 0 .` scan. Preserve
behavior, public contracts and failure cleanup. No suppressions, scorer changes,
new per-row allocation, or arbitrary file splitting. Luna/high codes; Sol/high
reviews; the lead reviews architectural effects across adjacent owners.
Run focused `river-engine` checks and the epic's light performance check, record the
before/after score and result, then integrate this ticket independently.

## Review notes

Removed the private `reserveOrResumeValueIndex` forwarding hop and call the
existing `RelationalIndexSchemaLifecycle` owner directly from
`reserveIndexBuild`. Transaction begin, persistent schema admission, resume,
commit, abort, and publication ordering are unchanged.


Validation: Luna/high source `bbdf5122`, Sol/high and lead approved. Score
**89.435** (90.114 before). Focused `RelationalDatabaseTest` and
`CatalogIndexCodecTest`, engine policy checks and installTps passed with
`--no-daemon`. The epic's four-worker JVM sample/all passed at **308.43 TPS**,
p99 63.963ms, 494 retries, zero failed/unknown outcomes, valid invariants and
graceful shutdown. Recent integrated control 314.68 TPS; no observed regression.
Artifact: `/Users/blater/src/ingres/river-harness/runs/river_harness_20260911_032529_85a3984d`.
