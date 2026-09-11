---
id: tic-b169
status: closed
type: story
priority: 2
delivery: code
created: 2026-09-11
parent: tic-c8d1
---
# Simplify RiverDaemonPaths

File: `river-server-app/src/main/java/io/riverdb/server/app/RiverDaemonPaths.java`. Baseline slopwatch score: **192.224**.

## Approach

Review `RiverDaemonPaths.verify`, `RiverDaemonPaths.inspectFile`, `RiverDaemonPaths.ensureDirectory` first. Separate their distinct validation, execution and cleanup responsibilities into concrete local operations; flatten status-dependent control flow while preserving ordering and ownership. Reuse an existing owner where one exists, and avoid new delegation layers that merely move branches.

## Acceptance

The file and any extracted files score below 90 with the unchanged full-repository
`slopwatch -follow=false -include-tests -format json -limit 0 .` scan. Preserve
behavior, public contracts and failure cleanup. No suppressions, scorer changes,
new per-row allocation, or arbitrary file splitting. Luna/high codes; Sol/high
reviews; the lead reviews architectural effects across adjacent owners.
Run focused `river-server-app` checks and the epic's light performance check, record the
before/after score and result, then integrate this ticket independently.

## Validation

Accepted `567ec7d2`, Luna/high implemented; Sol/high/lead approved. Canonical
selection, capability probing, collision admission and parent mutation now have
concrete stateless owners. The old facade is removed and callers migrate directly.
Validation precedes carrier publication; identity, symlink, overlap, ancestor,
private-directory, force/close and status contracts are unchanged. Scores:
selection 5.688, inspection 10.931, probe 28.057, parents 18.946 (original 192.224).

All 12 focused path/default-client/lifecycle tests and server checks passed;
benchmark installation passed. Logs: `/private/tmp/river-tic-b169-focused.log`
and `/private/tmp/river-tic-b169-check.log`.
The epic's light JVM sample/all candidate was 262.65 TPS; adjacent frozen control
was similarly lower at 266.35 TPS (p99 62.226/64.913ms). Both passed with zero
failed/unknown outcomes, valid invariants and graceful cleanup. The adjacent
control reproduces the lower rate; no change-attributed regression or speedup claim.
Artifacts under `/Users/blater/src/ingres/river-harness/runs/`:
`river_harness_20260911_043732_cba08bf2`,
`river_harness_20260911_043840_3f3f47b6`.
