---
id: tic-6d7c
status: open
type: story
priority: 2
delivery: code
created: 2026-09-11
parent: tic-c8d1
---
# Simplify RiverDaemonTargets

File: `river-server-app/src/main/java/io/riverdb/server/app/RiverDaemonTargets.java`. Baseline slopwatch score: **181.796**.

## Approach

Share runtime-record admission used by listing and endpoint resolution; keep endpoint filtering before target acquisition. Separate user-facing row formatting only if still needed. Preserve stale-record warnings, duplicate endpoint conflict, lock revalidation and exact close/status ordering. Reuse current runtime codec/storage owners; do not add a second registry or lifetime owner.

## Acceptance

The file and any extracted files score below 90 with the unchanged full-repository
`slopwatch -follow=false -include-tests -format json -limit 0 .` scan. Preserve
behavior, public contracts and failure cleanup. No suppressions, scorer changes,
new per-row allocation, or arbitrary file splitting. Luna/high codes; Sol/high
reviews; the lead reviews architectural effects across adjacent owners.
Run focused `river-server-app` checks and the epic's light performance check, record the
before/after score and result, then integrate this ticket independently.
