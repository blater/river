---
id: tic-7c5c
status: in_progress
type: story
priority: 2
delivery: code
created: 2026-09-11
parent: tic-c8d1
---
# Simplify CatalogSchemaPayloadCodecTest

File: `river-engine/src/test/java/io/riverdb/engine/schema/catalog/CatalogSchemaPayloadCodecTest.java`. Baseline slopwatch score: **91.376**.

## Approach

Organize the fixture and scenarios by the behavior they prove; start with `CatalogSchemaPayloadCodecTest.columns`, `CatalogSchemaPayloadCodecTest.canonicalPackerRoundTripsMaximumMixedCompositeKeysAcrossChunks`, `CatalogSchemaPayloadCodecTest.encodeWithIdentity`. Share setup only where ownership and assertions stay explicit; remove redundant cases only with a named retained proof.

## Acceptance

The file and any extracted files score below 90 with the unchanged full-repository
`slopwatch -follow=false -include-tests -format json -limit 0 .` scan. Preserve
behavior, public contracts and failure cleanup. No suppressions, scorer changes,
new per-row allocation, or arbitrary file splitting. Luna/high codes; Sol/high
reviews; the lead reviews architectural effects across adjacent owners.
Run focused `river-engine` checks and the epic's light performance check, record the
before/after score and result, then integrate this ticket independently.

## Validation

Accepted `479d638c`, Luna/high implemented; Sol/high/lead approved. One fixture
owns encoded head/manifest/record construction. All 13 scenarios, checksum and
record ordering, malformed payload assertions, and allocation guard are retained.
Test score 0 (91.376 before), fixture 10.704. All 13 tests passed in 0.116s;
engine checks and benchmark installation passed in 8s. Log:
`/private/tmp/river-score-jdbc-tic-7c5c-gradle.log`.

The epic's light JVM sample/all passed at 301.17 TPS, p99 62.358ms, zero failed/
unknown outcomes, valid invariants and graceful cleanup. Production unchanged;
this is a smoke, not a speed claim. Artifact:
`/Users/blater/src/ingres/river-harness/runs/river_harness_20260911_043356_2aad6577`.
