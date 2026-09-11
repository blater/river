---
id: tic-3fc3
status: in_progress
type: story
priority: 2
delivery: code
created: 2026-09-11
parent: tic-c8d1
---
# Simplify TransactionManagerTest

File: `river-tx/src/test/java/io/riverdb/tx/TransactionManagerTest.java`. Baseline slopwatch score: **132.029**.

## Approach

Move shared lock admission, parking checks and fake/blocking participants into one
package-private test support owner. Preserve all 35 scenario bodies, explicit
barriers, timeouts, interrupt restoration and resource lifetimes. Expose only
the fake state directly inspected by the scenarios.

## Acceptance

The file and any extracted files score below 90 with the unchanged full-repository
`slopwatch -follow=false -include-tests -format json -limit 0 .` scan. Preserve
behavior, public contracts and failure cleanup. No suppressions, scorer changes,
new per-row allocation, or arbitrary file splitting. Luna/high codes; Sol/high
reviews; the lead reviews architectural effects across adjacent owners.
Run focused `river-tx` checks and the epic's light performance check, record the
before/after score and result, then integrate this ticket independently.

## Validation

Implementation `658c5bf9`; Luna/high, Sol/high and lead accepted. All 35
scenario bodies are unchanged. Scores: test 11.375 (from 132.029), support 7.680.
35 focused tests, no failures/errors/skips; transaction checks/installTps passed
in 9s. Log: `/private/tmp/river-score-jdbc-tic-3fc3-gradle.log`.
Light sample/all JVM workload,4 workers,1 warehouse,seed 42,max-retries 20,
5s warmup/10s measured, version `tic-3fc3-658c5bf9-jvm`:301.80 TPS,
p99 64.291ms; zero failed/unknown, valid invariants, graceful inactive cleanup.
Artifact:`river_harness_20260911_055257_07cf0df1` under harness runs.
No observed regression; test-only change makes no speedup claim.
