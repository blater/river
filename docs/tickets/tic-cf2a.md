---
id: tic-cf2a
status: in_progress
type: story
priority: 2
delivery: code
created: 2026-09-11
parent: tic-c8d1
---
# Simplify TupleBTreeTestPageProvider

File: `river-storage/src/test/java/io/riverdb/storage/btree/TupleBTreeTestPageProvider.java`. Baseline slopwatch score: **118.054**.

## Approach

Separate validation/proof generation bookkeeping from actual page, generation,
root and pinned-reference ownership in the test provider. Preserve release-fault
timing, validation counters and writable-borrow transitions. Allocate the support
state once per provider; retain zero per-operation allocation.

## Acceptance

The file and any extracted files score below 90 with the unchanged full-repository
`slopwatch -follow=false -include-tests -format json -limit 0 .` scan. Preserve
behavior, public contracts and failure cleanup. No suppressions, scorer changes,
new per-row allocation, or arbitrary file splitting. Luna/high codes; Sol/high
reviews; the lead reviews architectural effects across adjacent owners.
Run focused `river-storage` checks and the epic's light performance check, record the
before/after score and result, then integrate this ticket independently.
