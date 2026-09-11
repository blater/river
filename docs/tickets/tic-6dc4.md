---
id: tic-6dc4
status: in_progress
type: story
priority: 2
delivery: code
created: 2026-09-11
parent: tic-c8d1
---
# Simplify LocalWalTest

File: `river-wal/src/test/java/io/riverdb/wal/local/LocalWalTest.java`. Baseline slopwatch score: **118.104**.

## Approach

Move shared directory/WAL setup, reservation and force helpers, boundary assertions
and record/decision batch fakes into one package-private support owner. Preserve
all 23 scenario bodies, failure positions, force metrics, durable byte assertions
and close/reopen lifetimes.

## Acceptance

The file and any extracted files score below 90 with the unchanged full-repository
`slopwatch -follow=false -include-tests -format json -limit 0 .` scan. Preserve
behavior, public contracts and failure cleanup. No suppressions, scorer changes,
new per-row allocation, or arbitrary file splitting. Luna/high codes; Sol/high
reviews; the lead reviews architectural effects across adjacent owners.
Run focused `river-wal` checks and the epic's light performance check, record the
before/after score and result, then integrate this ticket independently.

## Validation

Implementation `2572599c`; Luna/high, Sol/high and lead accepted. Test 13.785
(from 118.1), support 8.161. All 23 focused tests passed without failures/errors/
skips; WALchecks/installTps passed in 6s. Log:
`/private/tmp/river-score-jdbc-tic-6dc4-gradle.log`.
Light sample/all JVM workload,4 workers,1 warehouse,seed 42,max-retries 20,
5s warmup/10s measured, version `tic-6dc4-2572599c-jvm`:297.69 TPS,
p99 64.160ms; zero failed/unknown, valid invariants, graceful inactive cleanup.
Artifact:`river_harness_20260911_055852_f1c1c10a` under harness runs.
No observed regression; test-only change makes no speedup claim.
