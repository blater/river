---
id: tic-b251
status: open
type: story
priority: 2
delivery: code
created: 2026-09-11
parent: tic-c8d1
---
# Simplify RiverDaemonIdentity

File: `river-server-app/src/main/java/io/riverdb/server/app/RiverDaemonIdentity.java`. Baseline slopwatch score: **931.632**.

## Approach

Separate first creation, restart admission and staged-creation recovery. Give resource ownership and cleanup one explicit owner per operation; simplify completeCreate, recoverCreate and residue cleanup without changing identity, lock, crash-recovery or filesystem guarantees.

## Acceptance

The file and any extracted files score below 90 with the unchanged full-repository
`slopwatch -follow=false -include-tests -format json -limit 0 .` scan. Preserve
behavior, public contracts and failure cleanup. No suppressions, scorer changes,
new per-row allocation, or arbitrary file splitting. Luna/high codes; Sol/high
reviews; the lead reviews architectural effects across adjacent owners.
Run focused `river-server-app` checks and the epic's light performance check, record the
before/after score and result, then integrate this ticket independently.
