# ADR 0007: Frontiers and durability contracts

<!-- markdownlint-disable MD013 -->

Status: Accepted

## Context

Journal order, SQL visibility, stable media, checkpoint coverage, and safe
reuse advance asynchronously. Treating them as one "commit index" causes early
visibility, write-ahead violations, unsafe truncation, or false durability.

## Decision

Use the following standard names and owners:

| Name | Unit and owner | Meaning |
| --- | --- | --- |
| `prepared` | `JournalPosition`; journal appender | Highest gap-free entry fully published into the bounded local pipeline |
| `memoryReplicated` | `JournalPosition`; future replicated-journal provider | Highest epoch-qualified prefix held by the required volatile quorum |
| `journalCommitted` | `JournalPosition`; journal provider/consensus commit tracker | Highest chosen, non-reorderable prefix; local mode chooses its published ordered history |
| `applied` | `JournalPosition`; apply/recovery coordinator | Highest committed prefix whose redo and decisions were processed locally |
| `visibleCsn` | `CommitSequence`; transaction publication barrier | Highest gap-free SQL-visible commit sequence |
| `localWalDurable` | mapped journal prefix plus replica-local `durableEndLsn`; WAL provider | Highest prefix recoverable from this replica's forced WAL |
| `quorumWalDurable` | `JournalPosition`; future replicated-journal provider | Highest epoch-qualified prefix forced on the configured durable quorum |
| `checkpointed` | `JournalPosition`; checkpoint coordinator | Highest prefix covered by a complete atomically published local recovery checkpoint |
| `durableRecovery` | `JournalPosition`; recovery policy/coordinator | Highest committed prefix recoverable from a validated base plus required durable suffix and transaction/MVCC state |
| `safeTruncate` | `JournalPosition` mapped by WAL retention policy | Highest prefix reclaimable after every recovery and retention consumer agrees |

`JournalFrontiers` exposes only journal-position facts. `visibleCsn` remains a
transaction-owned value. `durableRecovery` and `safeTruncate` are derived
aggregate decisions, not writable counters owned by the append path. Snapshots
capture an atomic typed view rather than a bag of similarly named longs.

The Phase 1 client requirement is only `LOCAL_DURABLE`. After R2,
`QUORUM_DURABLE` is the default replicated requirement and succeeds only when
the transaction decision and every required predecessor are journal-committed,
forced on a persistence quorum, and locally applied/published. `CHECKPOINTED`
is an admin coverage and reclamation fact, not a client commit tier.

## Invariants

- Each frontier is monotonic and gap-free within one database/journal lineage.
- A commit reply also waits for local `visibleCsn` to cover its CSN; a durable
  but unapplied decision is not yet a successful local SQL commit response.
- Local page flush depends only on the local WAL half-open durable boundary.
- Queries use applied materialized state filtered by `visibleCsn`, never WAL.
- `durableRecovery` proves a same-lineage checkpoint plus every required suffix
  and outcome; it is not `max(checkpointed, localWalDurable)`.
- Restarted `NodeIncarnation`s cannot vote, acknowledge replication, or serve
  until installed state and suffix are validated.

## Consequences

Single-node implementations may observe several values advance together, but
tests and APIs preserve their meanings for later providers. Metrics can expose
lag without changing ownership.

## Alternatives

- `CHECKPOINTED` as a durability option was rejected because checkpoint timing
  is an administrative policy and can add unbounded commit latency.
- Volatile consensus as the normal meaning of commit was rejected. An explicit
  non-durable acknowledgement may be proposed only in optional R4.
- Selecting Raft, VSR, a consensus library, or an entry granularity here was
  rejected; R20-R23 own that evidence and ADR.

## Required evidence

- P10 deterministic fake-provider tests for every legal ordering and lag.
- Crash histories proving no reply above its requested durability/visibility.
- Pre-R2 quorum, membership, incarnation, state-sync, and mixed-version proof.
- R3 uses complete validated snapshot installation plus suffix replay for the
  in-place engine. R4, R5, and R6 remain independent options after R3.

## Authoritative context

- [Product durability promises](../governance/product-charter.md)
- [Replicated journal plan](../plans/river-replicated-journal-durability-plan.md)
- [Implementation plan P10 and R20-R23](../plans/river-project-implementation-plan.md)
