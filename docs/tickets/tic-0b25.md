---
id: tic-0b25
status: in_progress
type: story
priority: 2
delivery: code
created: 2026-09-11
parent: tic-c8d1
---
# Simplify CatalogLifecycleRemediationTest

File: `river-engine/src/test/java/io/riverdb/engine/schema/catalog/CatalogLifecycleRemediationTest.java`. Baseline slopwatch score: **125.095**.

## Approach

Move catalog lifecycle fixture construction, durable record builders and shared
assertions into one package-private test support owner. Keep all 23 scenarios and
explicit resource cleanup in the test class; use record accessors for its moved
Opened carrier. Preserve allocation checks and encoded byte boundaries.

## Acceptance

The file and any extracted files score below 90 with the unchanged full-repository
`slopwatch -follow=false -include-tests -format json -limit 0 .` scan. Preserve
behavior, public contracts and failure cleanup. No suppressions, scorer changes,
new per-row allocation, or arbitrary file splitting. Luna/high codes; Sol/high
reviews; the lead reviews architectural effects across adjacent owners.
Run focused `river-engine` checks and the epic's light performance check, record the
before/after score and result, then integrate this ticket independently.
