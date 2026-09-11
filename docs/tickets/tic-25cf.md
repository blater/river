---
id: tic-25cf
status: open
type: story
priority: 2
delivery: code
created: 2026-09-11
parent: tic-c8d1
---
# Simplify TupleBTreeLeafSplit

File: `river-storage/src/main/java/io/riverdb/storage/btree/TupleBTreeLeafSplit.java`. Baseline slopwatch score: **139.126**.

## Approach

Review `TupleBTreeLeafSplit.splitInsert`, `TupleBTreeLeafSplit.prepare`, `TupleBTreeLeafSplit.equalAt` first. Separate their distinct validation, execution and cleanup responsibilities into concrete local operations; flatten status-dependent control flow while preserving ordering and ownership. Reuse an existing owner where one exists, and avoid new delegation layers that merely move branches.

## Acceptance

The file and any extracted files score below 90 with the unchanged full-repository
`slopwatch -follow=false -include-tests -format json -limit 0 .` scan. Preserve
behavior, public contracts and failure cleanup. No suppressions, scorer changes,
new per-row allocation, or arbitrary file splitting. Luna/high codes; Sol/high
reviews; the lead reviews architectural effects across adjacent owners.
Run focused `river-storage` checks and the epic's light performance check, record the
before/after score and result, then integrate this ticket independently.
