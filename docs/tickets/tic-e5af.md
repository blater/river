---
id: tic-e5af
status: closed
type: story
priority: 2
delivery: code
created: 2026-09-11
parent: tic-c8d1
---
# Simplify CatalogTableDecoder

File: `river-engine/src/main/java/io/riverdb/engine/relational/CatalogTableDecoder.java`. Baseline slopwatch score: **190.238**.

## Approach

Review `CatalogTableDecoder.decodeCopied`, `CatalogTableDecoder.decodeForScan`, `CatalogTableDecoder.decode` first. Separate their distinct validation, execution and cleanup responsibilities into concrete local operations; flatten status-dependent control flow while preserving ordering and ownership. Reuse an existing owner where one exists, and avoid new delegation layers that merely move branches.

Implementation plan: keep shared record header/name/index validation and
`TableDefinition` reset/publication in `CatalogTableDecoder`; the existing
scan owner handles only scan-specific copy and name admission before calling
that shared path. Move column metadata, defaults, check nodes, reusable
`TableSchema`, and scratch arrays into one `CatalogColumnDecoder` created once.
The embedded index tail has its own static `CatalogTableIndexDecoder`, and the
unused `columnFlags` scratch array is removed. All decoders preserve the
existing status precedence and source-buffer restoration.

## Acceptance

The file and any extracted files score below 90 with the unchanged full-repository
`slopwatch -follow=false -include-tests -format json -limit 0 .` scan. Preserve
behavior, public contracts and failure cleanup. No suppressions, scorer changes,
new per-row allocation, or arbitrary file splitting. Luna/high codes; Sol/high
reviews; the lead reviews architectural effects across adjacent owners.
Run focused `river-engine` checks and the epic's light performance check, record the
before/after score and result, then integrate this ticket independently.

## Validation

Accepted source `89d6fcbd`, reviewed by Sol/high and the lead. Scores: table
10.352, column 69.548, index 5.688, scan 7.427, shared record 0. Persisted bytes,
reset/status precedence and buffer restoration are unchanged; unused column-flags
storage is removed. Focused catalog/default/check/lifecycle tests, the added
truncated-record regression, engine checks and benchmark installation passed.
Log: `/private/tmp/river-tic-e5af-build.log`.

The epic's short sample/all candidate/control returned 267.84/308.37 TPS. The
longer matched 30s pair returned 379.94/380.63 TPS (p99 50.561/47.579ms), so the
short-run gap did not repeat. All runs passed with zero failed/unknown outcomes,
valid invariants and graceful cleanup. No speedup claim.
Artifacts below `/Users/blater/src/ingres/river-harness/runs/`:
`river_harness_20260911_040117_dd4373a6`,
`river_harness_20260911_040200_2c9a404b`,
`river_harness_20260911_040239_a42c9e92`,
`river_harness_20260911_040330_2ab1effd`.

Delivered at `perf-checkpoint-20260911-score-first15`.
