---
id: tic-c726
status: in_progress
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
