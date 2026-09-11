---
id: tic-04aa
status: in_progress
type: story
priority: 2
delivery: code
created: 2026-09-11
parent: tic-c8d1
---
# Simplify RiverDaemonTarget

File: `river-server-app/src/main/java/io/riverdb/server/app/RiverDaemonTarget.java`. Baseline slopwatch score: **122.981**.

## Approach

Separate stateless instance/runtime identity binding from the live target capability
owner. Keep listing admission distinct, use the existing record parsers, and retain
one bound-runtime carrier with its file identity. Give runtime revalidation its
local phase while preserving lock/instance-before-runtime checks, close/status
precedence, missing-runtime behavior and every retained field's ownership.

## Acceptance

The file and any extracted files score below 90 with the unchanged full-repository
`slopwatch -follow=false -include-tests -format json -limit 0 .` scan. Preserve
behavior, public contracts and failure cleanup. No suppressions, scorer changes,
new per-row allocation, or arbitrary file splitting. Luna/high codes; Sol/high
reviews; the lead reviews architectural effects across adjacent owners.
Run focused `river-server-app` checks and the epic's light performance check, record the
before/after score and result, then integrate this ticket independently.
