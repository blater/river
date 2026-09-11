---
id: tic-a39e
status: closed
type: story
priority: 2
delivery: code
created: 2026-09-11
parent: tic-c8d1
---
# Simplify SqlQuery

File: `river-sql/src/main/java/io/riverdb/sql/SqlQuery.java`. Baseline slopwatch score: **90.981**.

## Approach

Review `SqlQuery.hasCardinalityBlock`, `SqlQuery.compileDerived`, `SqlQuery.promoteRootBlockPipeline` first. Separate their distinct validation, execution and cleanup responsibilities into concrete local operations; flatten status-dependent control flow while preserving ordering and ownership. Reuse an existing owner where one exists, and avoid new delegation layers that merely move branches.

## Acceptance

The file and any extracted files score below 90 with the unchanged full-repository
`slopwatch -follow=false -include-tests -format json -limit 0 .` scan. Preserve
behavior, public contracts and failure cleanup. No suppressions, scorer changes,
new per-row allocation, or arbitrary file splitting. Luna/high codes; Sol/high
reviews; the lead reviews architectural effects across adjacent owners.
Run focused `river-sql` checks and the epic's light performance check, record the
before/after score and result, then integrate this ticket independently.

## Validation

Accepted `8393fb96`, Luna/high implemented; Sol/high/lead approved. A redundant
private forwarding method is removed; cardinality and nested routing retain
their distinct decisions in one stateless owner. Query APIs, source/total block
counts, validation/reset ordering and allocations are unchanged. Query score
65.736 (90.981 before), routing 11.008.

SQL checks and 21 focused nested/scope/join/union/view engine tests passed with
engine policy checks and benchmark installation. Log:
`/private/tmp/river-tic-a39e-build.log`. The epic's light JVM sample/all passed
at 314.18 TPS, p99 60.522ms, zero failed/unknown outcomes, valid invariants and
graceful cleanup, consistent with recent short controls. Artifact:
`/Users/blater/src/ingres/river-harness/runs/river_harness_20260911_043104_7b02cf65`.
