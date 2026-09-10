---
id: tic-a73c
status: closed
type: story
priority: 1
delivery: code
created: 2026-09-10
branch: ticket/tic-a73c-insert-admission
base-commit: ad1db42f5d3d9dcdb6789111a99384041ccaec77
parent: tic-6d42
deps:
  - tic-c7e2
---
# Prepare and admit INSERT rows once, then stage the admitted result

## Change

Replace the repeated preparation and validation passes in
`SqlDescriptorPointInsertExecution` and `RelationalDescriptorBatchInsert` with
one statement-owned insert owner. The owner reserves one logical-ID range, then
evaluates, encodes, checks, plans, protects, validates uniqueness, and stages each
row exactly once into the transaction's pending mutation storage. Statement
rollback is owned by `SqlAtomicStatementLifecycle`; pending rows remain
transaction-local until commit. Single-row foreign keys validate before append;
multi-row statements validate staged rows after all rows so forward references
within the statement retain their existing semantics.

The batch is bound to the active transaction identity and is reset at every
statement boundary. Staging consumes only the values supplied for the current
row through the batch owner; there is no second rebuild, fingerprint comparison,
caller-controlled bypass flag, or unchecked insert entry point. Logical IDs
reserved for a failed statement may leave internal gaps; this is not a visible
SQL guarantee and avoids reusing an identity after rollback.

## Correctness and boundaries

Use the existing transaction mutation and statement-savepoint budgets. A resource
failure after an earlier row has entered pending storage is rolled back by the
statement lifecycle before the statement returns. Keep statement atomicity and
existing foreign-key ordering, including relationships among rows in the same
statement. Preserve null, default, check-constraint and generated-value
semantics; each expression is evaluated once.

Admission is valid only during the transaction and statement call that owns it.
Preserve duplicate detection against earlier writes in the same transaction,
delete/reinsert, nullable unique keys, concurrent same-key inserts, cancellation
and rollback/savepoint behavior. A retry starts a fresh statement and rebuilds
the row from its input; no admission is reused across statements.

## Acceptance

- Real prepared single-row and multi-row INSERT use the same owning mechanism.
  Source and focused diagnostics show one evaluation/encoding/key plan and one
  uniqueness admission per key; no second published probe during staging.
- Focused tests cover successful insertion, intra-batch and concurrent duplicate
  rejection, existing transaction writes, a late constraint/resource failure
  leaving no partial statement, and rollback/retry state reuse. Extend existing
  tests rather than mirroring every helper call in new mocks.
- Reprofile the 5.74-second unique-validation subtree and report preparation,
  probe and lock work removed. Complete the parent epic's matched INSERT/TPS,
  slopmark and correctness checks.

No B-tree format, lock storage layout, clustered-row implementation, commit
pipeline or benchmark change. Lower-level probe efficiency belongs to tic-2e91.


## Candidate evidence

The session batch retains only its transaction identity, table and row-ID range.
Existing pending rows/tuple intents own payloads. Remove the receipt, fingerprint,
separate batch uniqueness table, allocator adapter and logical-ID rebinding pass.
SQL and direct descriptor inserts use the same owner. The mutation plan acquires
protection; uniqueness validation and tuple staging consume that contract without
reacquisition or repeated lock ownership lookups. Private index builds retain
lifecycle protection. There is no compatibility fallback or second staging API.

Independent review checked INSERT/update/delete/backfill protection, statement
rollback and deferred FK visibility. The added late-FK test commits an earlier
transaction write while proving the failed batch leaves no rows. Existing tests
cover forward self-reference, duplicates, savepoints, replay and resource limits.

The full-suite index-drop test exposed an allocator-only commit after a rejected
INSERT: row admission rejected its zero version count. The traced failure was
`SHARED_GROUP/PREFLIGHT_OPERATION_ADMISSION`. Skip row admission when there are
no row versions; retain the existing allocator WAL and publication path. Independent
review confirmed zero-version publication and recovery support. The original
`EmbeddedRiverDropIndexTest` now passes; temporary tracing was removed.

Focused correctness passed with `--no-daemon`: `SqlDescriptorInsertBatchTest`,
`SqlCompositeForeignKeyTest`, `SqlAtomicStatementLifecycleTest`,
`RelationalDescriptorRowPathTest`, `RelationalDescriptorTupleDeltaPlanTest`,
`IndexedRelationalWalHarnessTest` and `IndexedMaximumRelationalReplayTest`.
Log: `/private/tmp/river-a73c-focused.log`. The index rollback regression rerun
is `/private/tmp/river-a73c-drop-index.log`.

Slopmark before/after for touched production files is retained in
`/private/tmp/river-a73c-slopmark.txt`. SQL INSERT execution falls 27.233 → 6.315;
the batch state falls 14.438 → 8.161; tuple access stays 0. Consolidated batch
insertion rises 12.427 → 56.737, mainly the analyzer's path count for sequential
status gates. Review retained the shallow single-pass owner (133 → 91 lines),
which replaces multiple passes and storage owners, rather than splitting it to
lower the score. No new technical responsibility or duplicate policy was added.

Final affected-module and policy check passed:

```sh
GRADLE_USER_HOME=/private/tmp/river-gradle-insert-admission ./gradlew --no-daemon \
  --project-cache-dir /private/tmp/river-project-cache-insert-admission \
  :river-engine:test verifySourcePolicy verifyModuleGraph
```

`BUILD SUCCESSFUL`; log `/private/tmp/river-a73c-engine-final.log`.
The full engine suite includes the existing large-cardinality SQL tests.

Step 5 should include both the prepared single-row profile and the usual
multi-row TPC-C work: per-row reservation and pending-key lookup must be measured
with growing transaction state, as well as the work eliminated here.
Performance, clean integration checks and promotion remain deferred to step 5.
No throughput claim or ticket closure follows from correctness alone.


## Step 5 acceptance — 2026-09-10

Accepted after fresh interleaved measurements. Short 2s-warmup/10s samples were
240.9 / 240.3 TPS control and 205.5 / 199.4 candidate. That repeated drop triggered
longer comparisons rather than dismissal. With identical 5s warmup/30s windows,
interleaved control/candidate/control/candidate results were 256.900, 262.567,
253.967, 264.933 TPS. No code changed between these comparisons. Longer results
show no sustained regression; the initial short-window sensitivity remains in
the evidence and does not establish a throughput improvement claim.

Four-worker INSERT wall profiles show descriptor INSERT falling from 8.082 to
6.142 accumulated thread-seconds, published probes 3.750 → 2.434 and lock work
4.905 → 4.304 in matched 20s captures. The 25s workload rates were 7,523.17 and
8,026.01 inserts/s, with final row counts verified. Inclusive groups overlap;
unmounted virtual-thread waits are absent. This supports the source-level work
removal, not exact per-call latency.

Clean checkpoint completed with `--no-daemon`: the first run hit 392 allocated
bytes in an unchanged transaction allocation test (256-byte allowance). The
isolated rerun and complete transaction suite passed without changing the test;
the subsequent full `check :river-bench:installTps` passed. Logs, samples,
profiles and cleanup receipts are under `/private/tmp/insert-step5` with
`a73c-` labels; TPS console logs are `/private/tmp/insert-a73c-*.log`.
All TPS samples passed invariants, zero retries/errors, capture and cleanup.

Checkpoint: `perf-checkpoint-20260910-insert-admission`.
