---
id: tic-e1f7
status: open
type: story
priority: 2
delivery: code
created: 2026-09-11
parent: tic-c8d1
---
# Simplify RiverClientConnection

File: `river-client/src/main/java/io/riverdb/client/RiverClientConnection.java`. Baseline slopwatch score: **195.799**.

## Approach

Review `RemoteQuery.prepareMetadata`, `RemoteQuery.close`, `RemoteQuery.next` first. Separate their distinct validation, execution and cleanup responsibilities into concrete local operations; flatten status-dependent control flow while preserving ordering and ownership. Reuse an existing owner where one exists, and avoid new delegation layers that merely move branches.

## Acceptance

The file and any extracted files score below 90 with the unchanged full-repository
`slopwatch -follow=false -include-tests -format json -limit 0 .` scan. Preserve
behavior, public contracts and failure cleanup. No suppressions, scorer changes,
new per-row allocation, or arbitrary file splitting. Luna/high codes; Sol/high
reviews; the lead reviews architectural effects across adjacent owners.
Run focused `river-client` checks and the epic's light performance check, record the
before/after score and result, then integrate this ticket independently.
