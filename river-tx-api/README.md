# Provisional transaction contracts

This module is the dependency-minimal K03 seam between access methods,
transaction state, and recovery. It depends only on `river-base` through the
approved module graph. It does not contain a transaction manager, durable
codec, heap/index/page type, lock-table implementation, or recovery algorithm.

## Authority and ownership

- `TransactionContext` is an immutable operation view created by the trusted
  transaction implementation. Its `Snapshot` and `CancellationToken` are
  borrowed for the context lifetime; it is not a public authentication token.
- `Snapshot` exposes a commit publication boundary and a sorted primitive
  active-transaction set. Implementations own the immutable snapshot storage;
  the deterministic snapshot makes one lifecycle-time copy.
- `VersionRecord`, `VersionPointer`, `TransactionOutcome`,
  `RecoveryTransactionView`, `VisibilityResult`, and `VacuumResult` are
  caller-owned reusable carriers. A version append borrows bytes for the call;
  a read may return a provider-owned immutable borrow valid until its documented
  reclamation boundary.
- `LockToken` is an authenticated provider capability. An active token cannot
  be reset, only the issuing provider can release it, and a completed or stale
  token cannot release a later lock occupying the same slot.
- Provider mutation ownership is implementation-defined and explicit. The
  deterministic model is construction-thread owned and returns `NOT_OWNER`
  for cross-thread mutation.

## Status semantics

Expected outcomes use `StatusCode` and caller-owned `StatusDetail` rather than
exceptions or allocated result wrappers.

| Status | Contract meaning in this slice |
| --- | --- |
| `OK` | Operation completed and its output carrier is populated. |
| `RETRY` | Lock contention, unavailable retained outcome, or bounded vacuum work remains. |
| `CONFLICT` | Stale/foreign capability, wrong database/store identity, illegal lifecycle transition, or missing version address. |
| `NOT_OWNER` | A provider mutation was attempted outside its declared owner. |
| `CANCELLED` / `TIMEOUT` | The context was cancelled or the lock deadline had elapsed. |
| `RESOURCE_EXHAUSTED` | A fixed transaction, version, payload, or lock capacity was reached. |
| `FENCED` | Visibility depends on an indeterminate transaction decision. |

The lifecycle accepted by `TransactionStorage.storeRecoveryView` is
`ACTIVE -> COMMITTING -> COMMITTED` or
`ACTIVE -> ABORTING -> ABORTED`. Either decision state may become
`INDETERMINATE`. Terminal state is immutable and exact repeated writes are
idempotent. Commit publication ordering and transaction-ID allocation remain
owned by the future `river-tx` implementation.

## Allocation and copy policy

The designated warmed semantic path reuses all result/request carriers and
tests outcome lookup, cached visibility, borrowed version read, and lock
acquire/release with at most 256 measured bytes across 10,000 iterations on the
local supported JVM. The threshold is measurement noise, not a production
budget. Version append makes one explicit bounded copy to establish a stable
provider lifetime. The fake preallocates all transaction, version, payload, and
lock capacity.

## Deliberately deferred

This is provisional contract evidence, not K03 or G0 promotion. ADR 0008 and
P09 still gate persistent version layout and status freezing. The following are
not selected here:

- durable transaction, snapshot, version-pointer, or version-record encodings;
- transaction-ID reservation/high-water persistence and CSN publication;
- statement versus transaction snapshot acquisition policy;
- lock upgrades, conversion queues, fairness, deadlock detection, escalation,
  savepoints, and the final serializable range-lock protocol;
- version-chain pruning horizons, outcome compaction, vacuum scheduling, and
  physical rollback handlers;
- transaction manager, commit protocol, CLRs, recovery dispatch, or page/index
  integration;
- public/JDBC transaction facade or external-input validation.

Every real storage/lock provider must run the reusable
`TransactionProviderContractTest`; provider-specific crash, persistence,
concurrency, and performance tests remain additive.
