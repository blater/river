---
id: tic-c726
status: closed
type: story
priority: 2
delivery: code
created: 2026-09-11
parent: tic-c8d1
---
# Simplify SqlJoinBlockPipelineTest

File: `river-engine/src/test/java/io/riverdb/engine/sql/SqlJoinBlockPipelineTest.java`. Baseline slopwatch score: **207.510**.

## Approach

Organize the fixture and scenarios by the behavior they prove; start with `SqlJoinBlockPipelineTest.assertJoinPlan`, `SqlJoinBlockPipelineTest.spillsOwnedUnicodeJoinRowsIntoGroupedAndDistinctParents`, `SqlJoinBlockPipelineTest.assertDirectThreeRoleSpill`. Share setup only where ownership and assertions stay explicit; remove redundant cases only with a named retained proof.

## Acceptance

The file and any extracted files score below 90 with the unchanged full-repository
`slopwatch -follow=false -include-tests -format json -limit 0 .` scan. Preserve
behavior, public contracts and failure cleanup. No suppressions, scorer changes,
new per-row allocation, or arbitrary file splitting. Luna/high codes; Sol/high
reviews; the lead reviews architectural effects across adjacent owners.
Run focused `river-engine` checks and the epic's light performance check, record the
before/after score and result, then integrate this ticket independently.

## Review

Source `3a627815` retains the two end-to-end pipeline and spill scenarios, with
shared setup/row assertions and explicit expected plan rows. Sol/high and lead
review verified SQL, assertion order, Unicode/spill boundaries, nullability,
atomic failures and scan/session/database cleanup. All four files score 0
(original 207.510). Both focused scenarios passed (0.490s pipeline, 0.903s spill), with engine
checks and benchmark installation passing in 13s. Log:
`/private/tmp/river-score-jdbc-tic-c726-gradle.log`.
The epic's light JVM sample/all passed at 304.50 TPS, p99 63.111ms, zero failed/
unknown outcomes, valid invariants and graceful cleanup. Production is unchanged;
this is a smoke, not a speed claim. Artifact:
`/Users/blater/src/ingres/river-harness/runs/river_harness_20260911_042109_452f3e54`.

Delivered at `perf-checkpoint-20260911-score-first19`.
