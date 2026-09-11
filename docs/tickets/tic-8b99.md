---
id: tic-8b99
status: closed
type: story
priority: 2
delivery: code
created: 2026-09-11
parent: tic-c8d1
---
# Simplify TableDefinitionColumnView

File: `river-engine/src/main/java/io/riverdb/engine/relational/TableDefinitionColumnView.java`. Baseline slopwatch score: **94.471**.

## Approach

Review `TableDefinitionColumnView.defaultTextLength`, `TableDefinitionColumnView.findColumn`, `TableDefinitionColumnView.name` first. Separate their distinct validation, execution and cleanup responsibilities into concrete local operations; flatten status-dependent control flow while preserving ordering and ownership. Reuse an existing owner where one exists, and avoid new delegation layers that merely move branches.

## Acceptance

The file and any extracted files score below 90 with the unchanged full-repository
`slopwatch -follow=false -include-tests -format json -limit 0 .` scan. Preserve
behavior, public contracts and failure cleanup. No suppressions, scorer changes,
new per-row allocation, or arbitrary file splitting. Luna/high codes; Sol/high
reviews; the lead reviews architectural effects across adjacent owners.
Run focused `river-engine` checks and the epic's light performance check, record the
before/after score and result, then integrate this ticket independently.

## Validation

Accepted `14e1504b`: removed four private forwarding methods; TableDefinition
uses its owned counts and existing indexed column-name accessor. ColumnView
94.471 → 0; TableDefinition 13.044. Sol/high and lead approved exact retained
name/default semantics and no added allocation. All 21 table/schema capacity,
catalog, default-value and relational tests passed; engine checks/installTps
passed (`/private/tmp/river-tic-8b99-build.log`).

Epic light workload `tic-8b99-14e1504b-jvm`: 308.49 TPS, p99 62.521 ms,
530 retries, zero failed/unknown outcomes, valid invariants and graceful stop.
Artifact: `/Users/blater/src/ingres/river-harness/runs/river_harness_20260911_045206_7206287e`.
No observed regression against recent adjacent controls; no speedup claim.
