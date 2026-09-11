---
id: tic-4fd6
status: closed
type: story
priority: 2
delivery: code
created: 2026-09-11
parent: tic-c8d1
---
# Simplify LoopbackRiverServer

File: `river-server/src/main/java/io/riverdb/server/LoopbackRiverServer.java`. Baseline slopwatch score: **113.333**.

## Approach

Give the existing framed request loop and exact-read operation one stateless
connection-loop owner. Reuse the server's codec/counters and each slot's buffers;
keep listener, reservation/release, endpoint and terminal cleanup in the server.
Preserve authentication deadline/idle transition, partial EOF, retry, validation,
status/counter order and buffer lifetime. Remove the redundant fence-close
wrapper after confirming its sole private caller receives validated nonnull input.

## Acceptance

The file and any extracted files score below 90 with the unchanged full-repository
`slopwatch -follow=false -include-tests -format json -limit 0 .` scan. Preserve
behavior, public contracts and failure cleanup. No suppressions, scorer changes,
new per-row allocation, or arbitrary file splitting. Luna/high codes; Sol/high
reviews; the lead reviews architectural effects across adjacent owners.
Run focused `river-server` checks and the epic's light performance check, record the
before/after score and result, then integrate this ticket independently.

## Validation

Source `5a6e9a14`: LoopbackRiverServer **113.333 → 89.809**; the stateless
request loop scores **19.726**. Root and Sol/high reviewed framing, authentication
deadlines, retries, buffer lifetime and cleanup. The loop adds no per-frame
allocation. All 19 focused server tests, server checks and source policy passed;
log: `/private/tmp/river-tic-4fd6-loopback-check.log`. The periodic integration
checkpoint covers the standalone smoke.

The external harness used sample/all, four workers, one warehouse, seed 42,
20 retries, five seconds warmup, and GraalVM 25 for both variants. Candidate
version `tic-4fd6-5a6e9a14-jvm` and its repeat/longer labels identify this source;
controls use the frozen first51 installation. Short samples prompted a longer
paired check rather than acceptance of an unexplained slowdown.

| Variant | Seconds | TPS | p99 ms | Retries | Artifact suffix |
| --- | ---: | ---: | ---: | ---: | --- |
| Candidate | 10 | 248.45 | 68.94 | 430 | 082104_b3581a36 |
| Control | 10 | 266.96 | 62.59 | 466 | 082306_7c7cea6c |
| Candidate repeat | 10 | 260.58 | 70.12 | 415 | 082610_1df97e96 |
| Candidate longer | 20 | 300.63 | 59.08 | 956 | 082821_1ff623df |
| Control longer | 20 | 293.57 | 59.38 | 934 | 083328_4fa932dc |

Artifacts are below `~/src/ingres/river-harness/runs/`, prefixed
`river_harness_20260911_`. Every run passed validation with zero failed or unknown
outcomes, graceful shutdown and inactive service afterward. The longer pair
reverses the short-run throughput difference with nearly identical p99 and
retries per commit. No repeatable regression was established; accepted as a
behavior-preserving refactor, with no speedup claim.


## M5 integration promotion

Accepted in the eleven-ticket integration at source `2c5dd377`, checkpoint
`perf-checkpoint-20260911-score-first62-m5`. Current-platform clean checks
passed (1,952 tests, zero failures/errors, 18 existing skips), all touched files
score below 90, and independent integration review found no blocking issue.
The adjacent master/candidate/candidate/master JVM series passed at
562.82/526.70/501.22/422.15 TPS with valid invariants, zero failed/unknown
outcomes and graceful cleanup. No repeated candidate regression was observed.
Full evidence is in `docs/performance-checkpoints.md`. Native compilation
remains blocked on unchanged master by the separately tracked `tic-ae17`;
this acceptance certifies the JVM path, not native execution.
