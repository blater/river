---
id: tic-428c
status: in_progress
type: story
priority: 2
delivery: code
created: 2026-09-11
parent: tic-c8d1
---
# Simplify LockExactTable

File: `river-tx/src/main/java/io/riverdb/tx/LockExactTable.java`. Baseline slopwatch score: **90.204**.

## Approach

Review `LockExactTable.tryAcquire`, `LockExactTable.holds`, `LockExactTable.valid` first. Separate their distinct validation, execution and cleanup responsibilities into concrete local operations; flatten status-dependent control flow while preserving ordering and ownership. Reuse an existing owner where one exists, and avoid new delegation layers that merely move branches.

## Acceptance

The file and any extracted files score below 90 with the unchanged full-repository
`slopwatch -follow=false -include-tests -format json -limit 0 .` scan. Preserve
behavior, public contracts and failure cleanup. No suppressions, scorer changes,
new per-row allocation, or arbitrary file splitting. Luna/high codes; Sol/high
reviews; the lead reviews architectural effects across adjacent owners.
Run focused `river-tx` checks and the epic's light performance check, record the
before/after score and result, then integrate this ticket independently.

## Review notes

Removed the private `createHolding` forwarding hop and call the existing
`LockExactAdmissionController` owner directly from `tryAcquire`. Admission
ordering, resource lookup, conflict checks, statuses, and allocation behavior
are unchanged. The unchanged scorer result is 90.204 before and 89.918 after;
focused transaction checks remain pending the shared build slot.
