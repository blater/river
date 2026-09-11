---
id: tic-3a3d
status: in_progress
type: story
priority: 2
delivery: code
created: 2026-09-11
parent: tic-c8d1
---
# Simplify RiverJdbcResultSet

File: `river-jdbc/src/main/java/io/riverdb/jdbc/RiverJdbcResultSet.java`. Baseline slopwatch score: **113.508**.

## Approach

Keep cursor/row admission, null state, metadata and text scratch in ResultSet.
Replace its existing converter with one scalar-conversion context; stateless
object conversion reuses that context and its checked narrowing. Admit each
value and typed target once. Preserve target, row, type and null-check order,
overflow behavior and returned object types. Keep the short temporal getters
concrete, without callback dispatch or another retained converter.

## Acceptance

The file and any extracted files score below 90 with the unchanged full-repository
`slopwatch -follow=false -include-tests -format json -limit 0 .` scan. Preserve
behavior, public contracts and failure cleanup. No suppressions, scorer changes,
new per-row allocation, or arbitrary file splitting. Luna/high codes; Sol/high
reviews; the lead reviews architectural effects across adjacent owners.
Run focused `river-jdbc` checks and the epic's light performance check, record the
before/after score and result, then integrate this ticket independently.

## Validation

Source `d1c0a300`: ResultSet **113.508 → 86.669**, ScalarConversion **85.485**,
ObjectConversion **29.838**, numeric boundary test **6.610**. Root and Sol/high
approved conversion semantics and ownership: one converter per result set, no
new per-getter state or allocation beyond the existing JDBC result objects.

All **44 JDBC tests passed**, with no failures, errors or skips. Source policy
and the installed TPS distribution build passed. Logs:
`/private/tmp/river-score-jdbc-tic-3a3d-gradle.log` and
`/private/tmp/river-score-jdbc-tic-3a3d-module-check.log`. The initial sandbox
attempt failed before compilation when Gradle could not create its local
file-lock service; the authorized rerun succeeded.

The Java TPS runner used tiny/standard, four terminals, one warehouse, seed 42,
32 maximum attempts and five seconds warmup. Both variants explicitly used
GraalVM 25 through RIVER_JAVA; the control was the installed first51 build.

| Variant | Measured seconds | TPS | Cutoff transactions drained |
| --- | ---: | ---: | ---: |
| Candidate | 10 | 225.800 | 3 |
| Control | 10 | 279.400 | 3 |
| Candidate longer | 20 | 317.800 | 0 |
| Control longer | 20 | 184.350 | 4 |

Versions are `tic-3a3d-d1c0a300-graal25` and
`score-first51-3a3d-graal25-control`, with `-longer` for the longer runs.
Artifacts are `/private/tmp/river-score-20260911/tic-3a3d-{candidate,control}`
and their `-longer` directories. Every run completed at checkpoint with status
OK, zero errors/retries, successful capture and deadlock reconciliation, and
zero remaining transactions or locks. The longer pair reversed the short-run
difference. Allocation per committed transaction in the short runs was similar
(about 21.5 KB candidate and 21.8 KB control). No repeatable regression was
established; these variable short runs do not support a speed claim.

Accepted and merged into the local integration branch. Master promotion, tag
and push await the combined integration checkpoint; the campaign is paused at
the user's request.
