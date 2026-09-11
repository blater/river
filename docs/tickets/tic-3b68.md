---
id: tic-3b68
status: in_progress
type: story
priority: 2
delivery: code
created: 2026-09-11
parent: tic-c8d1
---
# Simplify IndexedTableStoreFactory

File: `river-engine/src/main/java/io/riverdb/engine/table/IndexedTableStoreFactory.java`. Baseline slopwatch score: **91.896**.

## Approach

Replace identical row/version reopen-or-create helpers with one local operation taking the file name. Preserve acquisition order, missing-page corruption mapping, lease ownership and cleanup precedence. Do not generalize the factory or add an interface.

## Acceptance

The file and any extracted files score below 90 with the unchanged full-repository
`slopwatch -follow=false -include-tests -format json -limit 0 .` scan. Preserve
behavior, public contracts and failure cleanup. No suppressions, scorer changes,
new per-row allocation, or arbitrary file splitting. Luna/high codes; Sol/high
reviews; the lead reviews architectural effects across adjacent owners.
Run focused `river-engine` checks and the epic's light performance check, record the
before/after score and result, then integrate this ticket independently.
