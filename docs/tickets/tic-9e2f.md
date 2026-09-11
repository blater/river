---
id: tic-9e2f
status: in_progress
type: story
priority: 2
delivery: code
created: 2026-09-11
parent: tic-c8d1
---
# Simplify IndexedTableStore

File: `river-engine/src/main/java/io/riverdb/engine/table/IndexedTableStore.java`. Baseline slopwatch score: **157.040**.

## Approach

Review `IndexedTableStore.close`, `IndexedTableStore.loadLogicalRowIdFloors`, `IndexedTableStore.initialize` first. Separate their distinct validation, execution and cleanup responsibilities into concrete local operations; flatten status-dependent control flow while preserving ordering and ownership. Reuse an existing owner where one exists, and avoid new delegation layers that merely move branches.

## Acceptance

The file and any extracted files score below 90 with the unchanged full-repository
`slopwatch -follow=false -include-tests -format json -limit 0 .` scan. Preserve
behavior, public contracts and failure cleanup. No suppressions, scorer changes,
new per-row allocation, or arbitrary file splitting. Luna/high codes; Sol/high
reviews; the lead reviews architectural effects across adjacent owners.
Run focused `river-engine` checks and the epic's light performance check, record the
before/after score and result, then integrate this ticket independently.

## Implementation and focused validation (2026-09-11)

Completed on `ticket/tic-9e2f-m5-completion`, based on pushed master `705488d5`.
`close()` keeps its synchronized boundary and delegates first-attempt admission
and detach to a concrete local operation. Four local resource operations own the
existing retry flags. Cleanup still attempts every owner in order, returns the
first failure, accepts `CLOSED` for files and sidecars, requires `OK` for provider
release, and retries only incomplete owners. Two failure tests prove these
boundaries using the existing file fixture.

Checkpoint floor import now belongs to the existing logical-row-ID registry,
which already owns floor admission and implements the source contract. The store
retains the checkpoint-null guard and import-before-checkpoint-load ordering;
source rewind, declared count, invalid-input translation, and exhaustion checks
are unchanged. No new class, public API, durable format, allocation, or copy
contract changes.

The initial parked forwarding removal was rejected by
`verifyIndexedTableClassReferences`, which enforces the named K16
`IndexedTable -> Store -> Kernel` boundary. It was dropped completely; the final
change preserves the original facade, callers, and private kernel/page fields.

The unchanged full-repository scorer covered 2,641 Java files: store
**157.040 → 89.278**, registry **16.315 → 67.203**, construction test **6.008**.
All three touched Java files are below 90; 19 other files remain at or above 90.
Score artifact: `/private/tmp/river-9e2f-scores.json`.

Final `verifyIndexedTableClassReferences` and focused store lifecycle,
construction, differential/interrupted recovery, relational WAL, logical-row-ID,
and checkpoint-generation checks passed: **59 tests in 12 suites**.
Log: `/private/tmp/river-9e2f-final-focused.log`.
Independent recovery/ownership review and lead review cover the final reduced
change. Full checkpoint validation, matched workload evidence, and pushed
integration remain the lead integrator's promotion gates.
