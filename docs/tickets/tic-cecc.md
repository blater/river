---
id: tic-cecc
status: closed
type: story
priority: 2
delivery: code
created: 2026-09-11
parent: tic-c8d1
---
# Simplify RiverPrimaryKeyResultSet

File: `river-jdbc/src/main/java/io/riverdb/jdbc/RiverPrimaryKeyResultSet.java`. Baseline slopwatch score: **96.997**.

## Approach

Give the identical read-only JDBC metadata methods one narrow inherited owner,
RiverMetadataResultSet, with PrimaryKey as its immediate consumer and index/catalog
metadata as following consumers. Keep requireOpen and metadata selection concrete;
preserve closed-before-invalid precedence, primary-key cursor state, fetch-size,
null diagnostics and query lifetime. Do not change AbstractResultSet defaults.

## Acceptance

The file and any extracted files score below 90 with the unchanged full-repository
`slopwatch -follow=false -include-tests -format json -limit 0 .` scan. Preserve
behavior, public contracts and failure cleanup. No suppressions, scorer changes,
new per-row allocation, or arbitrary file splitting. Luna/high codes; Sol/high
reviews; the lead reviews architectural effects across adjacent owners.
Run focused `river-jdbc` checks and the epic's light performance check, record the
before/after score and result, then integrate this ticket independently.


## Validation

Implementation `89b6ef03`. RiverPrimaryKeyResultSet falls from 96.997 to
87.8491; the shared RiverMetadataResultSet scores 0. Root and Sol reviewed
closed/error precedence, metadata dispatch and inherited read-only behavior;
row state, null diagnostics, query lifetime and allocations are unchanged.
Review corrected the metadata return type and an annotation before validation.

Eighteen focused driver and metadata-growth tests passed, no failures/skips;
JDBC checks, source-policy validation and TPS installation passed in nine seconds
with `--no-daemon`. Log: `/private/tmp/river-score-jdbc-tic-cecc-gradle.log`.

Java TPS light run, tiny/standard, four terminals, one warehouse, seed 42,
maximum attempts 32, five seconds warmup and ten seconds measured:
**462.200 TPS**, zero retries/errors, valid checkpoint/recovery/accounting,
valid capture and zero remaining transactions/locks/waits. Three cutoff
transactions drained normally. Consistent with adjacent same-configuration runs;
no speedup claim. Version `tic-cecc-89b6ef03-jvm`; evidence:
`/private/tmp/river-score-20260911/tic-cecc-candidate` and adjacent `.log`.


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
