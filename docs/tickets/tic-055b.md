---
id: tic-055b
status: closed
type: story
priority: 2
delivery: code
created: 2026-09-11
parent: tic-c8d1
---
# Simplify SqlCommand

File: `river-sql/src/main/java/io/riverdb/sql/SqlCommand.java`. Baseline slopwatch score: **128.210**.

## Approach

Review `SqlCommand.lowerJoinAggregateSource`, `SqlCommand.aggregateOutputProjection`, `SqlCommand.copyBlockFrom` first. Separate their distinct validation, execution and cleanup responsibilities into concrete local operations; flatten status-dependent control flow while preserving ordering and ownership. Reuse an existing owner where one exists, and avoid new delegation layers that merely move branches.

## Acceptance

The file and any extracted files score below 90 with the unchanged full-repository
`slopwatch -follow=false -include-tests -format json -limit 0 .` scan. Preserve
behavior, public contracts and failure cleanup. No suppressions, scorer changes,
new per-row allocation, or arbitrary file splitting. Luna/high codes; Sol/high
reviews; the lead reviews architectural effects across adjacent owners.
Run focused `river-sql` checks and the epic's light performance check, record the
before/after score and result, then integrate this ticket independently.

## Implementation ownership

Owner: lead-integrated relational domain builder (`complete_9e2f`). Stable base:
`832ae0d4`. Branch: `ticket/tic-055b-m5-completion`. Worktree:
`/private/tmp/river-m5-055b`.

Parser-owned values retain the U00 binding lifetime. The owner access migration
was reviewed against every River caller and the bound execution ownership contract.

## Implementation and focused validation (2026-09-11)

The final implementation has three concrete owners:

- The existing column-constraint component owns its metadata arrays, identity,
  read/mutation admission, reset, and preparation/publication of the existing
  growth reservation. `SqlCommand` supplies the sole authoritative column count.
  All replacement arrays are prepared before any publication; borrowed identifier
  identity survives growth and reset reuses the same storage.
- `SqlJoinAggregateLowering` owns the entire root/source graph rewrite, including
  block admission, copies, rewrites, and final pipeline compilation. The parser
  invokes it directly. Aggregate output materialization and group-expression
  construction remain with their existing aggregate/projection owners. The
  existing query-state owner handles complete block copy validation/publication.
- Existing aggregate, grouping, order, constraint, projection-symbol, and mutation
  expression owners expose read-only metadata. Constructors, mutation, growth,
  and reset remain package-private. Superseded command forwards and unused APIs
  are removed, with all River callers/tests migrated together. The obsolete
  constraint-admission and expression-view helpers are deleted.

No SQL/protocol/JDBC behavior or command borrowing lifetime changes. Existing
borrowed identifiers and expressions remain valid only until parser reuse;
bound execution retains its existing owned copies. The new lowering operation
has no state. Constraint ownership adds one object per command construction,
with existing arrays moved intact and no additional per-row allocation/buffers.

Independent architecture/relational review covers the final public read surface,
receiver types, bound-snapshot preservation, mutation ordering, error/fallback
semantics, copy/reset alias behavior, and complete growth publication. Compiler checks preserved bound snapshot receivers; no bound object gained
parser-owner references.

The unchanged full-repository scorer covered **2,640 Java files**, with **18**
remaining files at or above 90. Final scores:

| File/owner | Baseline | Final |
| --- | ---: | ---: |
| SqlCommand | 128.210 | 89.884 |
| SqlCommandColumnConstraints | 0.000 | 76.421 |
| SqlQueryParser | 68.006 | 67.508 |
| SqlCommandQueryState | 59.466 | 59.466 |
| SqlJoinAggregateLowering | new stateless operation | 0 |

All exposed metadata owners score below 18. No new file crossed 90. Existing
high-scoring callers retain unchanged responsibilities and scores:
`SqlPointCommandExecutor` 119.659, `SqlProjectionBinder` 105.642, and
`SqlDerivedReferenceValidator` 96.971. Artifact:
`/private/tmp/river-055b-scores.json`.

Final affected SQL-module tests and focused engine ownership, prepared-statement,
joined/grouped aggregate, and ordered-query tests pass (89 SQL tests and 24 engine
tests). SQL runtime invocation and module graph gates pass. The added metadata
test covers growth retention, invalid-index fallbacks, and parser reset/reuse.
Log: `/private/tmp/river-055b-final-tests.log`.

Final source `7591c25d` passed clean `check :river-bench:installTps` in 2m53s:
1,955 tests, zero failures/errors, 18 existing skips. Four matched JVM samples
passed outcomes, invariants, report checksums, comparison eligibility/key, and
graceful cleanup. Candidate TPS 517.67/487.20 lies within adjacent controls
542.15/385.52; no speedup is claimed. Full configuration and immutable report IDs
are in `docs/performance-checkpoints.md`; logs and frozen candidate are under
`/private/tmp/river-m5-055b-evidence/`.

Accepted for promotion at `perf-checkpoint-20260911-sql-command-ownership-m5`.
Native compilation remains independently blocked by `tic-ae17`.
