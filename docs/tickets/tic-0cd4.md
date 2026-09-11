---
id: tic-0cd4
status: in_progress
type: story
priority: 2
delivery: code
created: 2026-09-11
parent: tic-c8d1
---
# Simplify RiverJdbcStatement

File: `river-jdbc/src/main/java/io/riverdb/jdbc/RiverJdbcStatement.java`. Baseline slopwatch score: **93.080**.

## Approach

Consolidate shared SQL and prepared-handle update completion within the existing
statement owner. Preserve admission/reset/execution order, SQL-versus-handle
error wording, command completion, generated keys and update counts. Introduce
no executor, strategy, alternative JDBC path or per-operation allocation.
Give retained SQL/parameter batch arrays one statement-owned storage owner; the
existing executor consumes it directly. Preserve growth publication, count reset,
partial execution cleanup and parameter release.

## Acceptance

The file and any extracted files score below 90 with the unchanged full-repository
`slopwatch -follow=false -include-tests -format json -limit 0 .` scan. Preserve
behavior, public contracts and failure cleanup. No suppressions, scorer changes,
new per-row allocation, or arbitrary file splitting. Luna/high codes; Sol/high
reviews; the lead reviews architectural effects across adjacent owners.
Run focused `river-jdbc` checks and the epic's light performance check, record the
before/after score and result, then integrate this ticket independently.


## Validation

Implementation `c54a8290`, validation merge `42d075b2`, on
`ticket/tic-0cd4-jdbc-statement`. RiverJdbcStatement falls from 93.080 to 85.3892;
RiverJdbcBatchState and the existing executor score 0. Root and Sol reviewed
SQL/prepared completion, admission/reset order, precise failure wording, generated
keys, allocation, array growth publication, partial batch cleanup and release.

Twenty focused JDBC tests passed with zero failures/skips; JDBC checks,
source-policy validation and TPS installation passed in 14 seconds with
`--no-daemon`. Log: `/private/tmp/river-score-jdbc-tic-0cd4-gradle.log`.

The actual JDBC consumer ran through `tools/tps-test.sh` with tiny/standard,
four terminals, one warehouse, seed 42, maximum attempts 32, five seconds warmup
and ten seconds measured. Version `tic-0cd4-c54a8290-jvm`: **472.800 TPS**,
zero retries/errors, valid checkpoint/recovery and deadlock accounting, valid
capture and zero remaining transactions/locks/waits. Two in-flight transactions
at cutoff were reconciled by the normal drain. This is consistent with the
preceding same-configuration 468.500 sample; no speedup claim. Evidence:
`/private/tmp/river-score-20260911/tic-0cd4-candidate` and adjacent `.log`.
