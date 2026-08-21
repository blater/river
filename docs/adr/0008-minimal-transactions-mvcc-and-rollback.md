# ADR 0008: Minimal transactions, MVCC, and rollback

Status: Proposed — P09 and transactional review required

## Context

Phase 1 needs enough transaction state for crash recovery without prematurely
claiming Phase 2 isolation, indexed MVCC, savepoints, vacuum, or status
compaction. Transaction outcomes, physical recovery handlers, and bootstrap
catalog records also need explicit ownership.

## Decision

K09 implements the minimal lifecycle `ACTIVE -> COMMITTING -> COMMITTED` or
`ACTIVE -> ABORTING -> ABORTED`, with an `INDETERMINATE` failure result that
fences service when a local durability outcome cannot be proved. Each
transaction has a typed `TransactionId`, `lastLsn`, and WAL `prevLsn` chain.
Commit/abort decisions are versioned records. Undo follows `prevLsn` and emits
redo-only CLRs carrying `undoNextLsn`; durable MVCC version chains are for
visibility reconstruction, not rollback control.

Transaction ID allocation reserves/persists a high-water that cannot regress or
reuse an ID within a `DatabaseIncarnation`. K09 journals reservations/outcomes;
K11 reconstructs and validates the high-water during analysis before new
transactions begin, then captures it in checkpoints/control state.

The durable idempotency outcome store is transaction-owned in K09. K12 supplies
only its stable bootstrap physical identity and minimal open-time records. One
bounded record maps `(DatabaseIncarnation, IdempotencyKey)` to transaction,
decision, CSN if committed, journal position, satisfied durability receipt,
and final/unknown state. An expired result is reported as unavailable, never
silently re-executed under the same key.

K09 is not a storage-recovery implementation bucket. Transaction recovery
owns decision/chain state; K07 heap/version and K08 B+tree modules own typed
redo/undo handlers; `river-recovery` K11 validates, dispatches, and orders them.
Structural B+tree operations are redo-only system transactions distinct from a
user transaction's undo chain.

K12 contains only database/catalog bootstrap identity, format/schema version,
stable initial physical relation/index IDs, durable ID high-waters, and the
minimal outcome-store locator. SQL catalog rows, DDL overlays, statistics,
privileges, and general dependency metadata remain T07 or later.

For Phase 2, propose durable prior-version records referenced by tuple headers,
transaction outcomes mapped to an ordered `CommitSequence`, read-committed
statement snapshots, repeatable-read transaction snapshots, and key-range
locking for serializable isolation. The exact tuple/version representation and
status-freezing layout remain P09-gated.

## Invariants

- A force failure after decision append never converts commit to abort.
- Visibility advances only through the ordered transaction publication barrier
  after the requested durability condition and complete base/index effects.
- Crash during rollback resumes from CLRs without repeating completed undo.
- Recovery opens snapshots only after outcome, `visibleCsn`, transaction-ID
  high-water, and idempotency state are rebuilt.
- Ordinary MVCC snapshots pin durable versions, not WAL retention.
- Phase 1 makes no full concurrency/isolation claim and K08 uses single-version
  index entries until Phase 2's indexed-MVCC protocol is proved.

## Consequences

Phase 1 can prove repeatable crash recovery with a deliberately small
transaction surface. Phase 2 adds lock, visibility, vacuum, savepoint, and
catalog breadth without changing rollback lineage or reusing IDs.

## Alternatives

- Using version chains as the undo log was rejected because pruning and crash
  rollback then have conflicting lifetimes.
- Storing all recovery handlers in `river-tx` was rejected as a dependency and
  ownership violation.
- Implementing SSI initially was rejected in favor of key-range 2PL, subject
  to the later locking ADR and isolation evidence.

## Required evidence

- P09 persistent-version layout, lookup, allocation, scan, and write-amplification
  measurements through `river-bench`.
- Phase 0 review of the transaction contracts, state authority, WAL lineage,
  outcome ownership, and the boundary between K09, K11, and storage handlers.
- K09/K11 crash matrices for commit, unknown outcome, abort, CLR, high-water,
  outcome lookup, and repeated recovery before G1.
- Phase 2 isolation histories, indexed visibility, bounded status/version
  growth, and lock/latch ordering review before G2.

The K09/K11 and Phase 2 tests validate the later implementations. They do not
form a circular prerequisite for accepting this Phase 0 architecture decision.

## Authoritative context

- [High-level transaction plan](../plans/river-high-level-plan.md)
- [Implementation plan K07-K12 and T01-T09](../plans/river-project-implementation-plan.md)
- [Engineering charter](../plans/river-engineering-personas-and-performance-charter.md)
