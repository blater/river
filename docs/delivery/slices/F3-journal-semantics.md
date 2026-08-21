# F3: journal semantics

Status: implemented provisionally; P05/P06/P09 evidence still blocks freeze

Deliverables: P10, K03 journal portion

## Outcome

Every journal provider implements one provider-independent contract for bounded
ordered reservation/publication, gap-free frontiers, named durability waits,
retention, lineage fencing, and idempotent outcome lookup. The deterministic
fake exercises success, lag, gaps, backpressure, cancellation, unknown outcome,
restart, and stale incarnation without assuming a local WAL layout or consensus
algorithm.

## Units and authority

- `JournalPosition` is a logical history identity containing database
  incarnation, generation, and sequence.
- `recordStartLsn` and exclusive `durableEndLsn` are local WAL byte boundaries,
  never logical journal identities.
- `CommitSequence` is transaction visibility order and is mapped by transaction
  decision records, not inferred from an LSN.
- The journal provider owns prepared/chosen/durability frontiers. Transactions
  own `visibleCsn`; recovery/retention derive `durableRecovery` and
  `safeTruncate`.

## Contract invariants

- Reservations and retained payload bytes are bounded. A full ring returns
  backpressure before overwrite.
- Publication may complete out of order internally, but no public frontier
  skips a hole.
- A durability wait names the required capability and exact inclusive logical
  prefix. Unsupported durability fails before append/commit work begins.
- Local WAL initially advertises only `LOCAL_DURABLE`.
- Success means the requested prefix is definitely covered. An I/O/force result
  that cannot distinguish durable from not durable returns an unknown outcome
  and fences the local provider/database; it is never reported as abort.
- Database and node incarnation are validated once at admission/reopen. A stale
  history cannot advance, wait on, retain, or resolve outcomes in the current
  lineage.
- Request/idempotency lookup returns the same durable outcome after retry and
  restart; it never invents success from a best-effort diagnostic.
- Retention leases are bounded, observable, cancellable, and participate in
  `safeTruncate` without becoming an unbounded pin.

## Deterministic provider suite

- contiguous and out-of-order publication with a permanent/temporary hole;
- capacity and retention backpressure;
- wait before/after frontier, timeout, cancellation, and provider fencing;
- supported/unsupported durability capability negotiation;
- append before/after simulated write and force, including unknown completion;
- restart with volatile suffix removed and durable suffix preserved;
- stale database/node incarnation rejection;
- duplicate request before decision, after decision, after response loss, and
  after restart;
- monotonic snapshot of all owned frontiers under concurrent readers;
- position↔local-LSN↔transaction-decision↔CSN inspection mapping;
- safe release only below every active retention consumer.

The same suite will run against the fake, local WAL provider, and later
replicated-journal provider. Provider-specific tests add mechanics but cannot
weaken these semantics.

## Allocation and ownership

Reserve/encode/publish/wait common paths use caller-owned request/result slots,
preallocated reservations/tickets, and primitive sequence storage. No exception,
future, varargs, boxed ID, record object, or formatted diagnostic is created per
record. P09 must measure this claim before the contract is frozen.

## Out of scope

- selecting Raft/VSR or an entry granularity;
- advertising `QUORUM_DURABLE` before R2;
- `QUORUM_ACCEPTED`, checkpointed commit, or follower reads;
- transaction SQL visibility implementation;
- local WAL block/segment mechanics beyond the provider seam.
