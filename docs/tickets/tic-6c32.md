---
id: tic-6c32
status: in_progress
type: story
priority: 2
delivery: code
created: 2026-09-11
parent: tic-c8d1
---
# Simplify TransactionManager

File: `river-tx/src/main/java/io/riverdb/tx/TransactionManager.java`. Baseline slopwatch score: **160.555**.

## Approach

Consolidate repeated commit-group member validation: owned transaction, admitted
state, non-null result and duplicate transaction/result checks. Keep distinct
PREPARED, COMMITTING and accepted-either-state contracts explicit, including their
existing status codes. Share group completion loops through the existing completion
owner where exact. Keep every public operation under the same manager monitor,
validate the whole group before mutation, preserve publication/durability ordering
and result resets, and add no per-group allocation or new lock/state owner.

## Acceptance

The file and any extracted files score below 90 with the unchanged full-repository
`slopwatch -follow=false -include-tests -format json -limit 0 .` scan. Preserve
behavior, public contracts and failure cleanup. No suppressions, scorer changes,
new per-row allocation, or arbitrary file splitting. Luna/high codes; Sol/high
reviews; the lead reviews architectural effects across adjacent owners.
Run focused `river-tx` checks and the epic's light performance check, record the
before/after score and result, then integrate this ticket independently.
