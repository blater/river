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

## Validation

Implementation `36c100bb`, based on accepted tic-6d7c; Luna/high, Sol/high and
lead accepted. Target 84.3224 (from 122.981); new binding owner 0; existing
admission owner remains 0. Final lock-held probe is retained after successful
runtime revalidation. All 37 focused lifecycle/identity/stop tests and server-app
checks passed; installed workload built. Log:
`/private/tmp/river-tic-04aa-focused-check.log`.
Light sample/all JVM workload, 4 workers, 1 warehouse, seed 42, max-retries 20,
5s warmup/10s measured, version `tic-04aa-36c100bb-jvm`: 308.46 TPS,
p99 63.963ms; passed, zero failed/unknown outcomes, valid invariants, clean stop.
Artifact: `river_harness_20260911_054731_280f6bf9` under harness runs.
No observed regression; no speedup claim.
