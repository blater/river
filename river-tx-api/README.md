# Provisional transaction contracts

This module is the dependency-minimal K03 seam between access methods,
transaction state, and recovery. It depends only on `river-base` through the
approved module graph. It does not contain a transaction manager, durable
codec, heap/index/page type, lock-table implementation, or recovery algorithm.

## Authority and ownership

- `TransactionContext` is a reusable operation view owned and rebound by the
  trusted transaction implementation. Each binding authenticates one active
  transaction generation to its provider; terminal transition retires that
  binding before the carrier may be reused. Its `Snapshot` and
  `CancellationToken` are borrowed for the binding lifetime.
- `Snapshot` exposes a commit publication boundary and a sorted primitive
  active-transaction set. Implementations own the immutable snapshot storage;
  the deterministic snapshot makes one lifecycle-time copy.
- `VersionRecord`, `VersionPointer`, `VersionReadResult`, `TransactionOutcome`,
  `RecoveryTransactionView`, and `VisibilityResult` are caller-owned reusable
  carriers. A version append borrows bytes for the call and copies them into
  stable provider storage. A read copies into an explicitly configured
  caller-owned destination, so provider reclamation cannot mutate retained
  read bytes.
- `LockToken` is an authenticated provider capability. An active token cannot
  be reset, only the issuing provider can release it, and a completed or stale
  token cannot release a later lock occupying the same slot.
- `LockRequest` borrows scalar or tuple identity bytes for one provider call.
  Tuple exact keys are canonical user-tuple bodies: tuple headers and physical
  row-id suffixes are excluded. Tuple range endpoints are prefix cuts; inclusive
  lower and upper endpoints respectively sit before and after all descendants,
  while exclusive endpoints reverse those cuts. Null endpoints are namespace
  infinities.
- Every context-based lock mutation also supplies the transaction generation
  captured by value when the operation began. A reused context carrier cannot
  authorize a stale asynchronous operation after rebinding. Commit and abort
  freeze new mutations; terminal token acknowledgement may deactivate a caller
  carrier only after its canonical holding generation no longer exists.
- Provider mutation ownership is implementation-defined and explicit. The
  deterministic model is construction-thread owned and returns `NOT_OWNER`
  for cross-thread mutation.

## Status semantics

Expected outcomes use `StatusCode` and caller-owned `StatusDetail` rather than
exceptions or allocated result wrappers.

| Status | Contract meaning in this slice |
| --- | --- |
| `OK` | Operation completed and its output carrier is populated. |
| `RETRY` | Lock contention or an unavailable retained transaction outcome. |
| `CONFLICT` | Stale/foreign capability, wrong database/store identity, illegal lifecycle transition, or missing version address. |
| `NOT_OWNER` | A provider mutation was attempted outside its declared owner. |
| `CANCELLED` / `TIMEOUT` | The context was cancelled or the lock deadline had elapsed. |
| `RESOURCE_EXHAUSTED` | A fixed transaction, version, payload, or lock capacity was reached. |
| `FENCED` | Visibility depends on an indeterminate transaction decision. |

The ordinary lifecycle accepted by `TransactionStorage.storeRecoveryView` is
`ACTIVE -> COMMITTING -> COMMITTED` or
`ACTIVE -> ABORTING -> ABORTED`. Commit durability uncertainty may transition
`COMMITTING -> INDETERMINATE`. An uncertain state is stable against ordinary
runtime writes but is not final: only the separately granted
`RecoveryTransactionStorage` capability, after authoritative journal evidence,
can resolve it to committed or to aborting. The latter still requires
WAL-driven loser undo and CLRs before `ABORTED`. Final decisions are immutable
and exact repeated writes are
idempotent. Commit publication ordering and transaction-ID allocation remain
owned by the future `river-tx` implementation.

## Allocation and copy policy

The designated warmed semantic path reuses all result/request carriers and
tests outcome lookup, authoritative visibility, copied version read, and lock
acquire/release with at most 256 measured bytes across 10,000 iterations on the
local supported JVM. The threshold is measurement noise, not a production
budget. Version append and read each make one explicit bounded copy at their
ownership boundary. The fake preallocates all transaction, version, payload,
and lock capacity.

## Deliberately deferred

This is provisional contract evidence, not K03 or G0 promotion. ADR 0008 and
P09 still gate persistent version layout and status freezing. The following are
not selected here:

- durable transaction, snapshot, version-pointer, or version-record encodings;
- transaction-ID reservation/high-water persistence and CSN publication;
- statement versus transaction snapshot acquisition policy;
- lock escalation and savepoint-specific lock release policy;
- validated status freezing and any cached-CSN visibility shortcut;
- version-chain reachability, snapshot/recovery horizons, outcome compaction,
  safe vacuum candidates/reclamation, and physical rollback handlers;
- transaction manager, commit protocol, CLRs, recovery dispatch, or page/index
  integration;
- public/JDBC transaction facade or external-input validation.

Every real storage/lock provider must run the reusable
`TransactionProviderContractTest`; provider-specific crash, persistence,
concurrency, and performance tests remain additive.
