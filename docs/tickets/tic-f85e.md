---
id: tic-f85e
status: in_progress
type: story
priority: 2
delivery: code
created: 2026-09-11
parent: tic-c8d1
---
# Simplify RiverIndexInfoResultSet

File: `river-jdbc/src/main/java/io/riverdb/jdbc/RiverIndexInfoResultSet.java`. Baseline slopwatch score: **95.622**.

## Approach

Build on `tic-650c`. Use the existing shared RiverMetadataResultSet for the
identical read-only JDBC methods. Retain index-specific metadata selection,
closed diagnostics, cursor/fetch-size/null behavior and query lifecycle. Delete
the duplicated methods; add no new state, helper or allocation.

## Acceptance

The file and any extracted files score below 90 with the unchanged full-repository
`slopwatch -follow=false -include-tests -format json -limit 0 .` scan. Preserve
behavior, public contracts and failure cleanup. No suppressions, scorer changes,
new per-row allocation, or arbitrary file splitting. Luna/high codes; Sol/high
reviews; the lead reviews architectural effects across adjacent owners.
Run focused `river-jdbc` checks and the epic's light performance check, record the
before/after score and result, then integrate this ticket independently.


## Validation

Implementation `07584f67` on `ticket/tic-f85e-index-metadata`, following
`tic-650c`. Index score falls from 95.622 to 27.3371; shared owner remains 0.
Root and Sol approved exact metadata/closed/error/cursor behavior. Eighteen
focused JDBC tests, JDBC checks, source policy and installation passed, no
failures/skips, nine seconds with `--no-daemon`. Logs:
`/private/tmp/river-score-jdbc-tic-f85e-gradle.log` and
`/private/tmp/river-score-jdbc-tic-f85e-install-final.log`.

The initial tiny/standard Java TPS sample was 371.400 TPS, triggering direct lead
investigation. Configuration: four terminals, one warehouse, seed 42, maximum
attempts 32, five seconds warmup, initially ten then twenty seconds measured.
The first longer controls accidentally used OpenJDK26 while candidates used
GraalVM25; those comparisons are invalid. One later sample consumed an isolation
build under the wrong version label and is excluded. Original artifacts remain
unchanged; `tic-f85e-comparison-notes.md` records the correction.

Valid GraalVM25 twenty-second samples, all identical workload configuration:

| Build | Version | TPS | Evidence directory |
| --- | --- | ---: | --- |
| Candidate | tic-f85e-07584f67-longer-jvm | 531.800 | tic-f85e-candidate-longer |
| Candidate | tic-f85e-07584f67-longer-repeat-jvm | 536.550 | tic-f85e-candidate-longer-repeat |
| Stable first51 | score-first51-f85e-graal25-control | 462.050 | tic-f85e-control-graal25 |
| Candidate | tic-f85e-07584f67-final-graal25 | 450.700 | tic-f85e-candidate-final |
| Stable first51 | score-first51-f85e-final-graal25-control | 536.700 | tic-f85e-control-final |

Evidence root: `/private/tmp/river-score-20260911`. The final commands explicitly
set `RIVER_JAVA=/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java`.
All valid samples passed recovery/checkpoint, accounting/capture and zero-resource
cleanup; one candidate retry was a reconciled deadlock. Lead inspection found
essentially unchanged per-transaction allocation, identical three-millisecond GC
time in the adjacent pair, nearly identical lock-release time and faster candidate
WAL force. Overlapping stable/candidate ranges show no repeatable code regression;
this is diagnostic acceptance, not a speedup claim. Future Java samples explicitly
select the JDK. No production change was made to compensate for measurement noise.
