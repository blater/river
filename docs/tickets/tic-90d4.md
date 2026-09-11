---
id: tic-90d4
status: in_progress
type: story
priority: 2
delivery: code
created: 2026-09-11
parent: tic-c8d1
---
# Simplify FaultingDurableDirectory

File: `river-engine/src/test/java/io/riverdb/engine/testsupport/fault/FaultingDurableDirectory.java`. Baseline slopwatch score: **120.929**.

## Approach

Remove the no-op diagnostic `record` facade and the pure durability calculations whose values it discards. Preserve the fake's observable results, fault hooks, crash generations, handle synchronization and content/namespace persistence model. Reassess the score before introducing any further ownership changes.

## Acceptance

The file and any extracted files score below 90 with the unchanged full-repository
`slopwatch -follow=false -include-tests -format json -limit 0 .` scan. Preserve
behavior, public contracts and failure cleanup. No suppressions, scorer changes,
new per-row allocation, or arbitrary file splitting. Luna/high codes; Sol/high
reviews; the lead reviews architectural effects across adjacent owners.
Run focused `river-engine` checks and the epic's light performance check, record the
before/after score and result, then integrate this ticket independently.
