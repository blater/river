---
id: tic-1ca8
status: in_progress
type: story
priority: 2
delivery: code
created: 2026-09-11
parent: tic-c8d1
---
# Simplify ProtocolSqlRequestEncoder

File: `river-protocol/src/main/java/io/riverdb/protocol/ProtocolSqlRequestEncoder.java`. Baseline slopwatch score: **110.622**.

## Approach

Review `ProtocolSqlRequestEncoder.encode`, `ProtocolSqlRequestEncoder.utf8Length`, `ProtocolSqlRequestEncoder.encodedTextBytes` first. Separate their distinct validation, execution and cleanup responsibilities into concrete local operations; flatten status-dependent control flow while preserving ordering and ownership. Reuse an existing owner where one exists, and avoid new delegation layers that merely move branches.

Plan: keep request validation, payload sizing, framing, and parameter headers in
`ProtocolSqlRequestEncoder`. Move SQL and program-argument UTF-8 length/write
operations into one protocol-local offset writer because the public base UTF-8
API is position-relative and cannot preserve this encoder's absolute-offset,
unchanged-position contract without a buffer view or transient mutation.

## Acceptance

The file and any extracted files score below 90 with the unchanged full-repository
`slopwatch -follow=false -include-tests -format json -limit 0 .` scan. Preserve
behavior, public contracts and failure cleanup. No suppressions, scorer changes,
new per-row allocation, or arbitrary file splitting. Luna/high codes; Sol/high
reviews; the lead reviews architectural effects across adjacent owners.
Run focused `river-protocol` checks and the epic's light performance check, record the
before/after score and result, then integrate this ticket independently.


## Result

Source `7251282c`: request encoder **20.688** (110.622 before); new text and
parameter owners **0**; touched prepared/program encoders **45.237/7.925**.
All callers use the shared parameter owner directly. Luna/high implemented;
Sol/high and lead approved. Protocol/client checks and installTps passed with
`--no-daemon`; byte formats, validation order and allocation behavior preserved.

Java/JDBC tiny/standard, four terminals, warehouse 1, seed 42, attempts 32, 5s warmup:
10s candidate 346.1/control 477.6 TPS prompted a 30s pair, which measured
candidate 553.533/control 560.267 TPS. All returned OK, zero errors/retries and
clean transaction/lock cleanup. The large short-run gap did not repeat; accepted
as a structural refactor, with no speedup claim. Control is frozen first-three
source 444d48fb; candidate version `tic-1ca8-7251282c-jvm[-30s]`.
Artifacts/logs: `/private/tmp/river-score-20260911/protocol-tps-{candidate,control,candidate-30s,control-30s}`.
