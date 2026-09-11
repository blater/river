---
id: tic-6a7f
status: in_progress
type: story
priority: 2
delivery: code
created: 2026-09-11
parent: tic-c8d1
---
# Simplify LocalWal

File: `river-wal/src/main/java/io/riverdb/wal/local/LocalWal.java`. Baseline slopwatch score: **159.675**.

## Approach

Review `LocalWal.truncateDecisionlessRecoveredSuffix`, `LocalWal.beginLogicalStream`, `LocalWal.cancelLogicalStream` first. Separate their distinct validation, execution and cleanup responsibilities into concrete local operations; flatten status-dependent control flow while preserving ordering and ownership. Reuse an existing owner where one exists, and avoid new delegation layers that merely move branches.

## Acceptance

The file and any extracted files score below 90 with the unchanged full-repository
`slopwatch -follow=false -include-tests -format json -limit 0 .` scan. Preserve
behavior, public contracts and failure cleanup. No suppressions, scorer changes,
new per-row allocation, or arbitrary file splitting. Luna/high codes; Sol/high
reviews; the lead reviews architectural effects across adjacent owners.
Run focused `river-wal` checks and the epic's light performance check, record the
before/after score and result, then integrate this ticket independently.


## Pending authorization

Automatic approval review rejected the footer and direct logical-stream owner
source moves, then rejected the corrected, independently reviewed footer
resubmission. It requires specific user authorization beyond the overall score
campaign. The rejected edits have not been applied. Root requested that
approval; other file tickets continue while it is pending.

The concrete corrected proposal is
`/private/tmp/river-tic-6a7f-rejected-refactors-v2.patch`, with rationale and
focused test selection in the adjacent `.md` file. Root and Sol reviewed the
proposal as preserving codec arguments, bytes, shared checksum and footer buffer,
all recovery callers, force/release/fencing order, and default force causes.
Existing partial source work remains isolated on `ticket/tic-6a7f-local-wal`.
