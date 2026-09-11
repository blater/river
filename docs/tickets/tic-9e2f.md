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
Removed package-private store forwarders and migrated every owned caller directly
to the existing kernel or relational-services owner; `IndexedTable` keeps its
monitor and notification boundaries. No public API, durable format, WAL ordering,
allocation, or copy contract changes.

`close()` keeps its synchronized boundary and delegates first-attempt admission
and detach to a concrete local operation. Four local resource operations own the
existing retry flags. Cleanup still attempts every owner in order, returns the
first failure, accepts `CLOSED` for files and sidecars, requires `OK` for provider
release, and retries only incomplete owners. No new class or ownership layer.

The unchanged full-repository scorer covered 2,641 Java files: store
**157.040 → 88.461**, with every touched Java file below 90 and 19 remaining files
at or above 90. Score artifact: `/private/tmp/river-9e2f-scores.json`.

Targeted `:river-engine:compileTestJava` passed. Focused indexed-store lifecycle,
construction, differential/interrupted recovery, relational WAL, maximum replay,
free-page recovery, allocation, and group-commit fault tests passed (76 tests in
13 suites). The two new
fault tests cover eager cleanup and first-error precedence, retry completion,
`CLOSED` normalization, and failed provider release without reclosing completed
files. Logs: `/private/tmp/river-9e2f-compile.log` and
`/private/tmp/river-9e2f-focused.log`.

Independent recovery/ownership review and lead review approved the final source
and both failure tests without blockers. Full checkpoint validation, matched
workload evidence, and pushed integration remain the lead integrator's promotion
gates.
