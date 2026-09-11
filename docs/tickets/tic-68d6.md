---
id: tic-68d6
status: closed
type: story
priority: 2
delivery: code
created: 2026-09-11
parent: tic-c8d1
---
# Simplify RiverClientConfiguration

File: `river-client/src/main/java/io/riverdb/client/RiverClientConfiguration.java`. Baseline slopwatch score: **154.401**.

## Approach

Review `RiverClientConfiguration.readBounded`, `RiverClientConfiguration.load`, `RiverClientConfiguration.createPinnedContext` first. Separate their distinct validation, execution and cleanup responsibilities into concrete local operations; flatten status-dependent control flow while preserving ordering and ownership. Reuse an existing owner where one exists, and avoid new delegation layers that merely move branches.

## Acceptance

The file and any extracted files score below 90 with the unchanged full-repository
`slopwatch -follow=false -include-tests -format json -limit 0 .` scan. Preserve
behavior, public contracts and failure cleanup. No suppressions, scorer changes,
new per-row allocation, or arbitrary file splitting. Luna/high codes; Sol/high
reviews; the lead reviews architectural effects across adjacent owners.
Run focused `river-client` checks and the epic's light performance check, record the
before/after score and result, then integrate this ticket independently.

## Validation

Accepted source `87ef2a81`, Luna/high implemented; Sol/high and lead approved.
Strict record parsing, bounded file I/O and exact certificate trust now have
stateless owners. Configuration remains immutable with the same public API;
connector preserves exact token sizing and byte wiping. Scores: configuration
5.352 (154.401 before), parser 20, reader 30.507, trust 10.522, connector 22.482.

All 18 client and 2 CLI tests and affected checks passed; benchmark installation
passed. Log: `/private/tmp/river-tic-68d6-build.log`. The actual JVM CLI loaded
server-generated client.properties, authenticated, created/inserted/selected a
row, and stopped its isolated server cleanly. Log:
`/private/tmp/river-score-20260911/tic-68d6-cli-smoke.log`.
The initial smoke fixture's optional ready-file parent selected an inadmissible
ancestor; using the public stdout readiness contract corrected the fixture.
No product behavior was changed for this setup issue.

The epic's light JVM sample/all passed at 314.92 TPS, p99 61.768ms, zero failed/
unknown outcomes, valid invariants and graceful cleanup, consistent with adjacent
306.79/310.40/304.50 TPS samples. Artifact:
`/Users/blater/src/ingres/river-harness/runs/river_harness_20260911_042503_f4d4f6b5`.

Delivered at `perf-checkpoint-20260911-score-first19`.
