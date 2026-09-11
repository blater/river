---
id: tic-d369
status: open
type: story
priority: 2
delivery: code
created: 2026-09-11
parent: tic-c8d1
---
# Simplify SqlSessionExecutionCoordinator

File: `river-engine/src/main/java/io/riverdb/engine/sql/SqlSessionExecutionCoordinator.java`. Baseline slopwatch score: **285.853**.

## Approach

Keep one session execution gate. Move prepared-plan admission and binding into a cohesive existing or local owner, and isolate scan completion from command dispatch. Preserve reusable execution workspaces, authorization, transaction boundaries and cleanup; do not add an allocation or a second executor on the per-row path.

## Acceptance

The file and any extracted files score below 90 with the unchanged full-repository
`slopwatch -follow=false -include-tests -format json -limit 0 .` scan. Preserve
behavior, public contracts and failure cleanup. No suppressions, scorer changes,
new per-row allocation, or arbitrary file splitting. Luna/high codes; Sol/high
reviews; the lead reviews architectural effects across adjacent owners.
Run focused `river-engine` checks and the epic's light performance check, record the
before/after score and result, then integrate this ticket independently.
