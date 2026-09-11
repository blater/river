---
id: tic-01f7
status: in_progress
type: story
priority: 2
delivery: code
created: 2026-09-11
parent: tic-c8d1
---
# Simplify CatalogIndexCodec

File: `river-engine/src/main/java/io/riverdb/engine/relational/CatalogIndexCodec.java`. Baseline slopwatch score: **91.625**.

## Approach

Review `CatalogIndexCodec.decode`, `CatalogIndexCodec.decodeForTable`, `CatalogIndexCodec.encode` first. Separate their distinct validation, execution and cleanup responsibilities into concrete local operations; flatten status-dependent control flow while preserving ordering and ownership. Reuse an existing owner where one exists, and avoid new delegation layers that merely move branches.

## Acceptance

The file and any extracted files score below 90 with the unchanged full-repository
`slopwatch -follow=false -include-tests -format json -limit 0 .` scan. Preserve
behavior, public contracts and failure cleanup. No suppressions, scorer changes,
new per-row allocation, or arbitrary file splitting. Luna/high codes; Sol/high
reviews; the lead reviews architectural effects across adjacent owners.
Run focused `river-engine` checks and the epic's light performance check, record the
before/after score and result, then integrate this ticket independently.

## Review notes

Removed the redundant four-argument `decodeForTable` forwarding overload.
Its production cleanup caller and focused codec tests now call the canonical
five-argument method with `null` for the optional column name. The canonical
method still owns scratch reset, validation, conflict, corruption, and result
status behavior. The codec score is 91.625 before and 89.521 after.


Validation: source `bf2b1962`, Luna/high implementation, Sol/high and lead
approved. Score **89.521** (91.625 before). Focused `CatalogIndexCodecTest` and
`RelationalDatabaseTest`, engine policy checks and installTps passed with
`--no-daemon`. The epic's light JVM sample/all passed at **306.28 TPS**,
p99 63.799ms, 495 retries, zero failed/unknown outcomes, valid invariants and
graceful cleanup. Recent integrated control 314.68 TPS; no observed regression.
Artifact: `/Users/blater/src/ingres/river-harness/runs/river_harness_20260911_032929_24a2cce7`.
