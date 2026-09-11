---
id: tic-650c
status: in_progress
type: story
priority: 2
delivery: code
created: 2026-09-11
parent: tic-c8d1
---
# Simplify RiverCatalogResultSet

File: `river-jdbc/src/main/java/io/riverdb/jdbc/RiverCatalogResultSet.java`. Baseline slopwatch score: **94.624**.

## Approach

Build on `tic-cecc`. Use the existing shared RiverMetadataResultSet for the
identical read-only JDBC methods. Retain catalog-specific metadata selection,
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

Implementation `ef6dbee8` on `ticket/tic-650c-catalog-metadata`, following
`tic-cecc`. Catalog score falls from 94.624 to 26.2614; shared owner remains 0.
Root and Sol reviewed dynamic table/type metadata selection, exact closed/invalid
precedence, inherited read-only methods and unchanged cursor/lifetime behavior.

Eighteen focused driver/metadata-growth tests passed, no failures/skips;
JDBC checks, source-policy validation and TPS installation passed in nine seconds
with `--no-daemon`. Log: `/private/tmp/river-score-jdbc-tic-650c-gradle.log`.

Java TPS light run, tiny/standard, four terminals, one warehouse, seed 42,
maximum attempts 32, five seconds warmup and ten seconds measured:
**479.200 TPS**, one retry and zero errors. The retry was one server-captured
deadlock with one matching client outcome; reconciliation passed. Checkpoint,
recovery and capture passed; two cutoff transactions drained normally and no
transactions/locks/waits remained. No repeated regression or speedup claim.
Version `tic-650c-ef6dbee8-jvm`; evidence:
`/private/tmp/river-score-20260911/tic-650c-candidate` and adjacent `.log`.
