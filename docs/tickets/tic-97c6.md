---
id: tic-97c6
status: closed
type: story
priority: 2
delivery: code
created: 2026-09-11
parent: tic-c8d1
---
# Simplify RiverClientConnectionTest

File: `river-client/src/test/java/io/riverdb/client/RiverClientConnectionTest.java`. Baseline slopwatch score: **141.678**.

## Approach

Move existing database/server/TLS setup, scripted peer helpers, lease/query fakes
and assertions into one shared client-test support owner. Preserve all 13 scenario
bodies and explicit socket/server/thread cleanup; change no production code.

## Acceptance

The file and any extracted files score below 90 with the unchanged full-repository
`slopwatch -follow=false -include-tests -format json -limit 0 .` scan. Preserve
behavior, public contracts and failure cleanup. No suppressions, scorer changes,
new per-row allocation, or arbitrary file splitting. Luna/high codes; Sol/high
reviews; the lead reviews architectural effects across adjacent owners.
Run focused `river-client` checks and the epic's light performance check, record the
before/after score and result, then integrate this ticket independently.

## Validation

Accepted `8d80e72a`: one support owns existing client fixtures; all 13 scenario
bodies and cleanup behavior remain unchanged. Test 19.260 (141.678 before),
support 49.185. Sol/high and lead approved protocol peers, thread joins, fixture
visibility and resource lifetimes. All 13 tests passed without skips in 2.069s;
client checks/installTps passed in 11s. Log:
`/private/tmp/river-score-jdbc-tic-97c6-gradle.log`.

Test-only epic light workload `tic-97c6-8d80e72a-jvm`: 311.07 TPS, p99 60.916 ms,
493 retries, zero failed/unknown outcomes, valid invariants and graceful cleanup.
Artifact: `/Users/blater/src/ingres/river-harness/runs/river_harness_20260911_052950_a12bb00d`.
