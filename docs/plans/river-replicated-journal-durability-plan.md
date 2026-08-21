# River Replicated Journal, Durability, and Storage Evolution Plan

<!-- markdownlint-disable MD013 -->

Status: Reviewed and staged in the high-level plan; requires ADRs and performance prototypes before replicated implementation

Audience: River storage, transaction, recovery, replication, SQL, and operations contributors

Scope: Evolution from the single-node River WAL to resilient full-database replication, optional volatile-quorum acknowledgement, and a TigerBeetle-inspired copy-on-write checkpoint model

Related plans:

- [River High-Level Architecture and Delivery Plan](river-high-level-plan.md)
- [River Engineering Personas and Performance Charter](river-engineering-personas-and-performance-charter.md)

Comparative decision analysis: [River and TigerBeetle: Comparative Analysis and Recommended Choices](river-tigerbeetle-comparison-and-recommendations.md)

## 1. Purpose

River needs high transaction throughput without making resilience, recovery, or the meaning of `COMMIT` ambiguous. The target architecture must support:

- The existing single-node durable-WAL mode.
- Durable consensus replication across independent failure domains.
- An explicit lower-latency acknowledgement after volatile consensus, where a bounded recovery-point objective is acceptable.
- Asynchronous WAL, state-materialization, checkpoint, and compaction work without exposing partially durable database state.
- Efficient SQL reads, indexes, and constraint enforcement without querying raw WAL records.
- Full-replica failover and state synchronization before considering sharding or distributed transactions.

The principal local-storage reference is TigerBeetle's combination of a bounded consensus journal, deterministic state application, copy-on-write grid, LSM forest, and atomic superblock checkpoint. River will adopt the applicable invariants without assuming that TigerBeetle's specialized transaction model maps directly to general SQL.

## 2. Decision summary

1. **The replicated journal establishes order; materialized state serves queries.** SQL execution never scans the journal as a second data store.
2. **A recoverable history is a checkpoint plus a journal-committed suffix.** SQL visibility within that history still follows transaction decision records and MVCC. Neither the checkpoint nor the bounded journal is independently the complete long-term source of truth.
3. **Durable quorum is the default distributed commit contract.** A successful normal `COMMIT` survives the configured process, machine, and storage failures.
4. **Volatile quorum is a separate acknowledgement tier.** It is not described as durable and may roll back after a complete cluster outage.
5. **Replication and storage writes proceed concurrently.** Durable mode waits for the fastest valid persistence quorum, which need not include the primary's local device.
6. **All durability frontiers are explicit.** River does not redefine an existing "forced LSN" to mean "present in follower memory."
7. **The first replicated system is one consensus group with full replicas.** Sharding, cross-group transactions, and globally distributed SQL are deferred.
8. **The initial replication transport may reuse the existing physiological WAL.** A later deterministic transaction envelope and copy-on-write storage format require separate ADRs and compatibility gates.
9. **In-place pages retain the WAL-before-page rule.** Fully independent state and WAL writers require copy-on-write generations and an atomic database root.
10. **A bounded ring applies backpressure.** River never overwrites recovery or catch-up history merely because a ring is full.

### 2.1 Build-sequencing decision

River does not implement consensus in the first storage kernel, but it also does
not postpone every replication concern. The split is:

- **Core in Phases 0/1:** concrete LocalWal lineage, append, force, scan,
  recovery, and retention behavior, with vocabulary that keeps logical order,
  physical LSNs, SQL visibility, and durability distinct.
- **Reference implementation in Phase 1:** the existing local physiological WAL
  remains the crash-correctness oracle and performance baseline. It does not
  implement a speculative provider interface.
- **Post-operational-beta implementation:** consensus, full replicas, state
  synchronization, membership, and failover arrive in R2/R3. R24 derives the
  minimal shared journal seam when both LocalWal and the replicated provider are
  real. R4 volatile acknowledgement is optional and non-default.
- **Independent storage decision:** R5 copy-on-write checkpoints or an LSM
  redesign is not a prerequisite for R2/R3 and proceeds only after its own
  format, recovery, and workload gate.

This avoids both failure modes: embedding cluster complexity in the first local
kernel and freezing a transaction API whose only possible meaning of commit is
"call local `force()`."

## 3. Non-goals

- Claiming that hardware-isolated volatile memory provides ACID durability.
- Querying or indexing arbitrary WAL records during normal SQL execution.
- Serving linearizable reads from followers in the first replication milestone.
- Per-table or per-range consensus groups in the first implementation.
- Distributed transactions, cross-shard foreign keys, or global secondary indexes.
- Byte-for-byte compatibility with TigerBeetle data files or protocol messages.
- Replacing the River SQL, MVCC, lock, or catalog model with TigerBeetle's accounting state machine.
- Selecting Raft, Viewstamped Replication, or a specific library without an ADR and fault-model evaluation.

## 4. Architectural model

```text
SQL transaction
      |
      v
leader execution, locking, validation, and deterministic result capture
      |
      v
ordered operation / WAL batch
      |
      +------------------+-------------------+
      |                  |                   |
      v                  v                   v
local journal       follower journals   state prefetch
      |                  |                   |
      +------- journal-commit frontier ------+
                             |
                             v
                 redo chosen journal history
                 and publish transaction decisions
                             |
                             v
              tables, indexes, catalog, MVCC
                             |
                             v
               asynchronous state persistence
                             |
                             v
                  atomic checkpoint root
```

The recoverable history at journal-committed operation `J` is:

```text
history(J) = checkpoint(S) + journal_entries(S, J]
```

That suffix may include updates from a transaction whose commit decision has not
yet appeared. Recovery or apply repeats the history, while transaction decision
records determine which CSNs are visible and which loser transactions are
undone. The live queryable database at applied journal position `A` and visible
commit sequence `V` is the materialized state through `A`, filtered by MVCC
visibility through `V`. A replica may serve a read only when the chosen read
protocol proves that both frontiers and the required MVCC state cover it.

## 5. Terminology and monotonic frontiers

River must represent these values separately even when some are equal in a single-node configuration.

| Frontier | Meaning |
| --- | --- |
| `prepared` | Highest operation accepted into the local journal pipeline. |
| `memoryReplicated` | Highest contiguous prefix known to reside in the required volatile replication quorum. |
| `journalCommitted` | Highest contiguous prefix chosen by the consensus protocol and no longer reorderable. In single-node mode this is the locally accepted ordered history. |
| `applied` | Highest journal-committed position whose redo effects and transaction decisions have been processed locally. |
| `visibleCsn` | Highest contiguously published SQL commit sequence; journal commitment alone does not make an owning transaction visible. |
| `localWalDurable` | Highest contiguous prefix recoverable from this replica's stable journal. |
| `quorumWalDurable` | Highest contiguous prefix persisted by the configured durable quorum. |
| `checkpointed` | Highest operation represented by a complete, atomically published local state checkpoint. |
| `durableRecovery` | Highest journal-committed prefix guaranteed recoverable under the selected durability policy. |
| `safeTruncate` | Highest operation whose journal space may be reused. |

Frontiers are prefix properties. River does not permit holes such as operation 102 being durable while operation 101 is volatile. Forcing or checkpointing a later operation covers all earlier operations in that log generation.

`durableRecovery` is not an arithmetic maximum of unrelated counters. It is the
highest journal-committed prefix recoverable from a validated checkpoint of the
same database lineage plus every required durable suffix record:

```text
localDurableRecovery = recoverablePrefix(
    validatedCheckpoint,
    durableJournalSuffix,
    transactionAndMvccRecoveryState)
```

For the in-place page engine, `checkpointed` is coverage information and is not
an independent replacement for the WAL/undo suffix. A later copy-on-write
checkpoint may be an independently complete base only when its atomic root,
incarnation, transaction state, and journal-commit proof all validate. Cluster
recovery must reject entries merely prepared by an obsolete leader.

## 6. Client-visible durability contracts

The initial names are architectural terms; SQL syntax and API spelling require an ADR.

| Contract | Acknowledgement condition | Intended guarantee |
| --- | --- | --- |
| `LOCAL_DURABLE` | Commit record is stable in the local WAL. | Current single-node crash durability. |
| `QUORUM_DURABLE` | The journal-committed prefix through the transaction decision is stable on a persistence quorum. | Default replicated ACID durability and failover. |
| `QUORUM_ACCEPTED` | The operation is ordered and present in a volatile quorum. | Low-latency acknowledgement with an explicit non-zero RPO. |

`checkpointed` remains an administrative coverage/reclamation frontier. It is
not a client durability requirement or commit tier; clients cannot make commit
latency depend on checkpoint scheduling.

Provisional client behavior:

```sql
COMMIT DURABILITY DURABLE;   -- default
COMMIT DURABILITY ACCEPTED;  -- explicit weaker contract
```

Every successful response includes or makes available:

- Transaction identity and idempotency key.
- Commit operation or commit sequence.
- Acknowledgement tier.
- Cluster epoch or view.
- Whether the outcome is final or may be rolled back after total-cluster recovery.

An accepted transaction may later receive a durable notification when `durableRecovery` covers it. Irreversible external effects should wait for durable acknowledgement unless the application owns an independent replayable source of truth.

## 7. Failure contract

### 7.1 Durable quorum

`QUORUM_DURABLE` must survive all failures in the declared placement policy, including process restart and loss of up to the configured number of replica machines or devices. Consensus acknowledgement from a durable replica occurs only after the journal I/O completion satisfies the platform durability contract.

The durable quorum and view-change/election quorum must intersect so that every future primary contains, or can recover, every possibly committed operation. Flexible quorums may be considered, but their intersection and availability properties must be mechanically tested.

### 7.2 Volatile quorum

`QUORUM_ACCEPTED` may survive minority node, rack, or power-domain failures while a sufficiently current set of replicas remains alive. It does not promise survival of:

- Complete cluster power loss.
- Simultaneous restart of all replicas.
- Correlated process, kernel, firmware, or deployment failure.
- Unsafe rolling maintenance that erases current replicas faster than they rehydrate.
- Loss of every volatile copy before WAL or checkpoint persistence catches up.

A restarted volatile replica receives a new incarnation identity, starts without voting rights, and cannot participate in an election until it has installed a valid checkpoint and journal suffix. River must not allow a restarted process to claim the replication rights of forgotten memory.

After total-cluster recovery, River restores exactly through `durableRecovery`, changes epoch, and reports the lost accepted interval. It never attempts to merge arbitrary surviving fragments beyond the last provably journal-committed recoverable prefix.

### 7.3 Common-mode failures

Replica placement must separate machines, storage devices, racks, power domains, and, where required, control-plane or availability-zone failure domains. Placement reduces correlated hardware risk but does not replace:

- Stable journal copies.
- Independent backups.
- Checksums and corruption repair.
- Staged deployments.
- End-to-end idempotency.
- Deterministic fault simulation.

## 8. Commit and application protocol

### 8.1 Leader execution

The leader continues to use River's transaction, MVCC, and lock machinery for interactive SQL. Before replication can make a transaction visible, River produces a deterministic commit representation containing:

- Transaction ID and idempotency key.
- Read/validation version and final commit sequence.
- Base-row insert, update, and delete effects.
- Primary, secondary, and unique-index effects.
- Catalog and schema effects where applicable.
- Resolved generated values, timestamps, identities, and nondeterministic function results.
- Commit or abort decision and result metadata.
- Checksums, format version, and predecessor hash or equivalent ordering proof.

Applying this representation on a valid prior state must not independently fail a uniqueness or foreign-key check. Those decisions are made once by the authoritative transaction protocol and encoded in the operation.

### 8.2 Initial WAL-compatible representation

The first replicated implementation may stream River WAL frames as they are produced and use the transaction decision record as the visibility boundary. Requirements are:

- Every WAL frame has an unambiguous transaction owner and format version.
- Consensus ordering covers all records required by a commit decision.
- A commit is not acknowledged above the durability frontier of any required earlier frame.
- Followers do not expose uncommitted effects.
- New-primary recovery discards or undoes incomplete transactions using existing recovery semantics.
- The mapping between consensus operation, commit sequence, and WAL LSN is explicit and inspectable.

The provisional baseline is a bounded batch of versioned WAL frames, preserving
per-frame transaction and predecessor information, followed eventually by the
transaction decision record. The ADR must compare that baseline with one entry
per frame and a canonical transaction envelope. Large transactions must stream
without requiring an unbounded in-memory commit object.

### 8.3 Apply

Replicas process journal-committed entries in order. With physiological WAL,
redo may materialize an owning transaction's hidden versions before its decision
record arrives. A position is `applied` only after all redo and decision records
through that position have been processed; `visibleCsn` advances separately and
atomically publishes the base-table, index, catalog, and MVCC visibility effects
of each committed transaction.

The initial leader replies only after both the requested durability frontier
covers the transaction decision and local apply/publication makes its CSN
visible. Replication, persistence, and application still overlap; the response
waits for the slower required condition. Any later optimization that replies
before local application needs a separate read-your-writes/result-routing ADR.

### 8.4 Provisional post-beta module boundaries

These modules are not part of the initial build. Their boundaries show how R24
can compose consensus with concrete LocalWal without moving consensus into
storage or transaction code; the R0 consensus ADR may refine their names and
the shared seam.

| Future module | Principal dependencies | Responsibility |
| --- | --- | --- |
| `river-replication-api` | `river-format` | Node/incarnation, epoch/view, membership, protocol-message, transport, and state-transfer contracts. |
| `river-consensus` | `river-replication-api`, `river-platform`, `river-observability-api` | Pure protocol state machine, elections, quorum rules, reconfiguration, and deterministic simulation hooks through injected log/transport ports. |
| `river-replicated-journal` | `river-wal`, `river-consensus`, `river-replication-api` | Compose ordered consensus with each replica's local WAL, derive the shared provider seam, and implement quorum durability/frontier semantics. |
| `river-state-sync` | `river-replication-api`, `river-recovery`, `river-backup`, storage snapshot APIs | Bootstrap/catch-up planning, snapshot installation, suffix replay, validation, and voting/read-serving admission. |

Expected roles include:

| Class or interface | Responsibility |
| --- | --- |
| `ReplicationTransport` | Bounded authenticated message transport; an Aeron adapter may implement it after measurement but does not define consensus semantics. |
| `ConsensusNode` | Deterministic role/term/log state machine with no SQL, page, or socket implementation knowledge. |
| `ConsensusLogPort` | Protocol-facing append, truncate-conflicting-suffix, persist, and snapshot-boundary contract. |
| `ReplicatedJournal` | R24 owner that maps logical entries to consensus positions and replica-local WAL ranges and defines only the shared LocalWal seam it consumes. |
| `ReplicationBatcher` | Forms bounded frame batches without changing per-entry order or transaction ownership. |
| `JournalCommitTracker` | Publishes the gap-free `journalCommitted` prefix independently of SQL `visibleCsn`. |
| `QuorumDurabilityTracker` | Publishes `memoryReplicated` and `quorumWalDurable` from epoch-qualified acknowledgements. |
| `FollowerApplyCoordinator` | Replays the chosen prefix and atomically publishes transaction decisions in CSN order. |
| `StateSyncCoordinator` | Chooses checkpoint plus suffix, installs it, validates lineage/checksums, and gates replica admission. |
| `ReplicaAdmissionGate` | Prevents stale or newly restarted incarnations from voting or serving reads before catch-up proof. |

## 9. Query, index, and constraint model

### 9.1 Normal queries

Queries read the materialized storage engine and its in-memory write layers. They do not scan the consensus journal. Each read is associated with a visible commit sequence and an applied frontier.

In the existing page engine:

- The buffer cache contains current page versions.
- Lineage-qualified page WAL tokens make redo idempotent without comparing bare
  offsets from different database incarnations or WAL generations.
- Disk pages form an older materialized base.
- Restart or follower catch-up replays WAL into page state before serving reads.

In a later TigerBeetle-inspired storage engine:

- Mutable in-memory tables receive current operations.
- Immutable in-memory tables are being flushed.
- Immutable copy-on-write disk tables form lower levels.
- Lookups merge those query-optimized layers at a snapshot.

### 9.2 Indexes

Authoritative indexes are synchronous transaction state. A transaction operation updates the row and every affected primary, secondary, unique, and catalog index at one commit sequence.

An asynchronous derived index is permitted only when:

- It is not used to enforce correctness.
- It publishes its applied frontier.
- The planner refuses it for snapshots newer than that frontier or has a proved delta-completion strategy.

### 9.3 Constraints

Unique, exclusion, and foreign-key decisions execute against the leader's authoritative snapshot with the appropriate key/range locks or reservations. The replicated operation captures the decision and resulting writes. Followers do not rerun nondeterministic SQL validation.

Cross-partition constraints are deferred with sharding. The first replicated database uses a single transaction and consensus domain.

## 10. Local persistence evolution

### 10.1 Existing in-place page engine

While River uses in-place page writes, it retains the core WAL invariant:

```text
pageToken.lineage == durableWalEnd.lineage
    && pageToken.recordEndLsn <= durableWalEnd.endExclusive
before that exact page image is written in place
```

`memoryReplicated` and `journalCommitted` cannot substitute for
the local `DurableWalEnd` in the buffer writer. A full page-write completion is
only `written`; recovery/retention treats that exact page image as stable only
after data-file force succeeds. This permits `QUORUM_ACCEPTED` to return before
disk, but local page flushing still waits for the corresponding local WAL.

### 10.2 Copy-on-write checkpoint engine

To let WAL and materialized-state writes advance independently, River introduces an optional storage generation with:

- Newly allocated immutable data and index blocks.
- External block checksums that detect corruption and misdirected I/O.
- A manifest covering every table, index, catalog structure, free-space structure, and format generation.
- A redundant, atomically advanced checkpoint root.
- Retention of the previous valid root until the next root is proven durable.

Future blocks may be written before their operations are independently durable
in the local WAL because they are unreachable from the current durable root. A
checkpoint root advances only when the complete database state through
operation `N` is valid, all transaction decisions and MVCC recovery state
through `N` are represented, and the checkpoint record proves that `N` is
journal-committed.

After a crash, unreachable blocks are reclaimed and recovery starts from the newest valid root plus the journal suffix. This is the mechanism that permits truly independent asynchronous journal and state writers without exposing partial transactions.

### 10.3 Storage-engine decision gate

The copy-on-write plan does not automatically require a full LSM conversion.
The independent R5 decision gate, which may run as research alongside R2/R3 but
does not block them, compares:

- Copy-on-write B+tree and heap generations.
- A TigerBeetle-style forest of specialized LSM trees.
- Retaining the existing page engine with WAL-gated flushes.

The decision uses measured write amplification, scan performance, point lookup latency, compaction tail latency, recovery time, and implementation complexity.

## 11. Checkpoint, retention, and state synchronization

### 11.1 Checkpoint

A valid checkpoint records:

- Immutable `CheckpointManifestId` derived from database incarnation, covered
  journal position, format generation, and complete manifest/root digest for a
  copy-on-write snapshot. An earlier fuzzy `CheckpointId` names a recovery
  boundary but does not make its in-place pages an immutable transfer set.
- Cluster and database incarnation.
- Consensus epoch/view and journal-committed operation.
- Materialized applied operation.
- Roots and checksums for all durable structures.
- Transaction and MVCC recovery metadata still required after the checkpoint.
- Free-space state and format versions.

Publishing the checkpoint root is the atomic act. Writing candidate blocks is not.

### 11.2 Ring reclamation

`safeTruncate` is bounded by all consumers:

```text
safeTruncate = min(
    checkpoint recovery coverage,
    required follower catch-up coverage,
    backup and restore coverage,
    logical decoding / CDC leases,
    active recovery and upgrade leases)
```

If the ring approaches the retained frontier, River applies backpressure. It may wait for persistence, checkpoint, detach a failed follower according to the membership protocol, or require snapshot-based state synchronization. It must not silently overwrite required entries.

### 11.3 State synchronization

A replica whose journal no longer intersects retained history installs a checkpoint snapshot and then replays the suffix. State synchronization validates block and root checksums before granting voting or read-serving rights.

The copy-on-write design should permit content-addressed or index-and-checksum block transfer and repair. Byte-identical physical convergence is desirable but not required until an ADR proves deterministic compaction and allocation across general SQL replicas.

Under the copy-on-write format, a block may be transferred as repair only when
sender and receiver name the same `CheckpointManifestId`, block identity,
format, and checksum. The in-place R3 engine uses complete validated snapshot
installation or logical rebuild rather than assuming selective blocks belong to
one immutable checkpoint. Live replicas need logical relational equivalence;
arbitrary current blocks from physically different layouts are not
interchangeable.

## 12. Consensus selection

Before R2 implementation, the replication-readiness part of R0 produces an ADR
comparing at least:

- Raft with stable quorum journal writes.
- Viewstamped Replication with TigerBeetle-style protocol-aware recovery and optional flexible quorums.
- A maintained library versus a River-owned implementation.

The preferred first candidate to evaluate is a maintained Raft core behind
River-owned log, persistence, transport, and simulation ports. That preference
is not protocol selection: the candidate must satisfy every durability,
snapshot, membership, batching, deterministic-test, and mixed-version gate, and
the ADR may reject it. Custom Raft/VSR or flexible-quorum work requires a
specific demonstrated limitation and a reviewed proof.

Evaluation criteria include:

- Durable and volatile acknowledgement semantics.
- Quorum intersection and availability.
- Reconfiguration and node incarnation handling.
- Log repair, corruption, and partially persisted entry handling.
- Snapshot/state synchronization.
- Batching and pipelining.
- Deterministic simulation suitability.
- Upgrade compatibility and protocol versioning.
- Operational understandability.

Volatile acknowledgement is not implemented by merely disabling `force()` in a standard protocol. The chosen protocol must explicitly model volatile replicas, restarts, epochs, catch-up, and the durable recovery frontier.

## 13. Throughput design

The default durable path is designed so that persistence need not determine transaction throughput:

- Batch many transactions or WAL frames into each consensus/storage operation.
- Write the journal sequentially with direct or otherwise proved durable I/O.
- Replicate, persist, prefetch, and prepare state application concurrently.
- Acknowledge on the fastest valid durable quorum rather than the primary's device specifically.
- Keep the inner state-application loop allocation-free or allocation-bounded.
- Incrementally schedule checkpoint and compaction work to bound tail latency.
- Separate journal bandwidth from checkpoint and compaction devices where benchmarks justify it.
- Reserve CPU, memory, I/O requests, and queue capacity so foreground SQL,
  backup, compaction, and state sync cannot starve journal, consensus, recovery,
  or checkpoint-root progress.

Phase 0 establishes numeric budgets for:

- Bytes of journal and network traffic per committed row and transaction.
- Sustainable journal bandwidth and force/group size.
- Batch fill delay and p50/p99/p99.9 commit latency.
- Accepted-to-durable lag in time, bytes, and operations.
- State-apply lag and follower catch-up rate.
- Checkpoint and compaction write amplification.
- Recovery time per GiB and maximum restart objective.

The volatile tier proceeds only if durable-quorum benchmarks show a material workload benefit that cannot be achieved through batching, pipelining, faster stable media, or flexible persistence quorums.

## 14. Backpressure and overload

All queues, journals, operation windows, and state-transfer buffers are bounded. Admission control considers:

- Journal free space.
- Accepted-but-not-durable bytes and age.
- Consensus replication lag.
- Apply lag.
- Checkpoint lag.
- Free copy-on-write blocks.
- Backup and CDC retention pins.

When the configured accepted-mode RPO budget is exceeded, River blocks new `QUORUM_ACCEPTED` commits until durability catches up or fails them with a stable retryable error. It does not silently continue accumulating unbounded volatile state. Waiting longer and returning a stronger durable result may be supported only if the client contract permits it.

## 15. Observability and administration

River exposes at minimum:

- Every frontier in Section 5 by replica and cluster.
- Oldest and newest journal operation and ring occupancy.
- Accepted-but-not-durable operations, bytes, and maximum age.
- Quorum membership, node incarnation, view/term, and leader.
- Per-replica journal, apply, checkpoint, and state-sync lag.
- Persistence latency and errors by device.
- Checkpoint roots, operation coverage, and validation status.
- Current recovery-point objective and estimated rollback interval.
- Backpressure reason and affected durability tier.
- Last failover and recovery decision, including discarded operation range.

Administrative operations include safe add, remove, drain, restart, checkpoint, state-sync, leadership transfer, durability wait, and accepted-to-durable fencing. Rolling maintenance refuses unsafe sequencing unless an explicit disaster-recovery override is used.

## 16. Security and correctness boundaries

- Cluster messages are authenticated and integrity-protected.
- Replica identity and incarnation cannot be reused accidentally.
- Journal, block, manifest, and checkpoint formats are checksummed and versioned.
- Client idempotency keys survive retry, failover, unknown outcomes, and accepted-mode rollback.
- Backup is independent of replication and protects against operator error and correlated logical corruption.
- Replicated deterministic bugs are not treated as hardware redundancy success.

## 17. Delivery phases

### Phase R0: vocabulary, ADRs, and R24 contract derivation

The local kernel establishes concrete LocalWal lineage, durability, recovery,
and retention behavior. R0 does not freeze a provider interface in Phase 0.
The distributed-readiness work may mature in parallel and R24 derives the
shared compatibility seam before R2 integration.

Concrete and deferred deliverables:

- Preserve the ADR distinction among logical journal order, physical LSN,
  transaction ID, CSN, local durability, and checkpoint coverage.
- Prove current LocalWal behavior with owner-specific tests.
- Derive contiguous replicated frontiers, durability capabilities/waits,
  node incarnation, idempotent outcomes, and position mapping at R24 from both
  real implementations.
- A storage-evolution boundary that permits, but does not select, later
  copy-on-write checkpoints.

Pre-R2 distributed-readiness deliverables:

- Workload, failure-domain, RPO, RTO, and durability SLOs.
- ADR for consensus protocol and quorum configuration.
- ADR for consensus entry granularity over the established journal positions.
- ADR for client acknowledgement tiers and SQL/API behavior.
- Direct-I/O/group-commit, durable-quorum, and memory-quorum benchmarks.
- A deterministic protocol/storage simulator prototype with injected faults.

The Phase 0 compatibility gate exits when concrete LocalWal tests and the first
durable formats cannot confuse logical journal identity with physical LSN. The
pre-R2 gate exits when durable and volatile contracts are
unambiguous, quorum proofs are reviewed, and benchmarks establish whether
volatile acknowledgement is worth its complexity.

### Phase R1: explicit frontiers in single-node River

R1 is part of Phase 1 of the high-level plan, not a post-beta retrofit.

Deliver concrete single-node durability metrics, retention accounting, and
database lineage without changing commit behavior. Shared provider frontiers,
wait interfaces, and node incarnation are deferred to R24.

Exit when `LOCAL_DURABLE` passes the existing crash matrix and no component
confuses journal-committed, SQL-visible, applied, forced, checkpointed, or
reclaimable positions.

### Phase R2: durable full-replica journal

Deliver one consensus group per database, full replicas, bounded physiological
WAL-batch replication, leader election, transaction decision replication, and
follower recovery. Followers are not query-serving.

Exit when every acknowledged `QUORUM_DURABLE` transaction survives the declared
minority process, machine, and storage failures; failover preserves SQL
transaction atomicity and commit ordering; and replay under different follower
schedules produces logically equivalent table, authoritative-index, catalog,
constraint, and MVCC state.

### Phase R3: state synchronization and operational failover

Deliver complete-snapshot replica bootstrap, lagging-replica state sync,
logical verification/rebuild fallback, membership changes, leadership transfer,
safe rolling restart, corruption detection, and replica repair. The in-place
engine does not assume selective immutable-block transfer.

Exit after repeated automated loss/replacement of replicas, journal wrap, state transfer, and mixed-version rolling-upgrade rehearsals.

### Phase R4: volatile acknowledgement tier

Deliver `QUORUM_ACCEPTED`, separate memory and durable frontiers, node incarnation fencing, accepted-to-durable notification, RPO backpressure, and total-cluster rollback reporting.

Exit when minority live-node failures preserve accepted operations, total-cluster restart restores exactly the durable prefix, lost accepted operations are reported deterministically, and no accepted operation is ever advertised as durable prematurely.

### Phase R5: copy-on-write checkpoint generations

Deliver immutable future blocks, a complete database manifest and
`CheckpointManifestId`, redundant atomic root publication, unreachable-block
reclamation, checkpoint recovery, and manifest-scoped block-level state
synchronization/repair.

Exit when arbitrary crashes during journal write, state write, compaction, manifest write, and root publication recover either the old valid checkpoint or the new complete checkpoint, never a mixture.

### Phase R6: follower reads

Deliver explicit stale-snapshot reads first, followed by linearizable follower reads only if a read-index or closed-version protocol is proved. The planner and transaction layer enforce `readVersion <= applied`.

Exit when history checking proves the advertised isolation level during lag, failover, checkpoint, and membership change.

### Deferred expansion

- Per-range consensus and data distribution.
- Cross-group transaction coordination.
- Global indexes and cross-range constraints.
- Geo-distributed placement and latency policies.
- Deterministic byte-for-byte LSM convergence if justified by repair benefits.

## 18. Verification strategy

### 18.1 Model and invariant tests

- Quorum-intersection and view-change model tests.
- Monotonic, gap-free frontier properties.
- Exactly one journal-committed value per consensus position.
- Apply idempotency and transaction atomicity.
- Checkpoint-plus-suffix equivalence to uninterrupted execution.
- Ring reuse only below `safeTruncate`.
- No read above the serving replica's `applied` frontier.
- Index and base-row visibility at the same commit sequence.

### 18.2 Deterministic simulation

Inject:

- Message loss, duplication, reordering, delay, and corruption.
- Primary failure at every journal, replication, apply, and reply boundary.
- Replica restart with lost volatile state.
- Disk write failure, delayed completion, torn/misdirected writes, and latent corruption.
- Asymmetric partitions and stale leaders.
- View change during checkpoint and ring wrap.
- Full-cluster power loss at every persistence boundary.
- Unsafe operator sequences to verify they are rejected.

### 18.3 SQL and transaction histories

- Concurrent inserts against unique indexes.
- Foreign-key insert/delete races.
- Commit/abort/failover races.
- Long and streaming transactions across journal batches.
- Savepoints and rollback with a leadership change.
- DDL/catalog changes during checkpoint and failover.
- Accepted transactions followed by durable dependent transactions.
- Idempotent retries after unknown or rolled-back accepted outcomes.

### 18.4 Performance and soak

- TPS and commit latency across local, durable-quorum, and accepted-quorum modes.
- Scaling by batch size, replica count, record size, and transaction contention.
- Slowest-device versus fastest-valid-quorum behavior.
- Journal/checkpoint device contention.
- State-apply, state-sync, and restart throughput.
- Tail latency during checkpoint, compaction, backup, repair, and failover.
- Multi-day runs with ring wrap, replica churn, and storage fault injection.

## 19. Release gates

The replicated durability feature is not production-ready until:

1. Durable acknowledgement has zero acknowledged loss in the complete declared fault matrix.
2. Volatile acknowledgement rolls back only beyond the published durable frontier and reports the rollback interval.
3. Membership and restart protocols prevent forgotten replicas from voting as current replicas.
4. Indexes, constraints, catalog state, and MVCC visibility remain atomic across failover.
5. Ring retention is bounded and backpressure is exercised before overwrite.
6. Checkpoint recovery never exposes partially materialized state.
7. Backup/restore is tested independently of replication.
8. Mixed-version upgrade and rollback preserve protocol and durable-format compatibility.
9. Performance meets the numeric budgets established in Phase R0.
10. Operators can explain every frontier, recovery choice, and unavailable state using supported inspection tools.

## 20. Principal risks

| Risk | Mitigation |
| --- | --- |
| Volatile quorum is mistaken for durability. | Separate names, responses, metrics, documentation, and default policy. |
| Consensus is added below an API that assumes local `force()`. | Introduce explicit frontiers and a durability-wait abstraction first. |
| In-place pages persist unrecoverable volatile changes. | Preserve WAL-before-page until an atomic copy-on-write root exists. |
| General SQL cannot be replayed deterministically. | Replicate resolved effects and values, not SQL text or nondeterministic planning. |
| Large transactions require unbounded replication buffers. | Stream versioned frames and use an explicit transaction decision boundary. |
| Secondary indexes diverge from tables. | Include all authoritative index mutations in the atomic operation. |
| Slow followers pin the ring indefinitely. | Bound lag, snapshot-sync or remove through consensus, and apply backpressure. |
| Storage rewrite overwhelms replication delivery. | Stage replicated existing WAL before copy-on-write/LSM decisions. |
| Hardware isolation hides correlated software risk. | Simulation, staged rollout, independent backup, checksums, and explicit RPO. |
| Durable storage is optimized away before measurement. | Require durable-quorum benchmarks and bottleneck evidence in Phase R0. |

## 21. References

- [River High-Level Architecture and Delivery Plan](river-high-level-plan.md)
- [River and TigerBeetle: Comparative Analysis and Recommended Choices](river-tigerbeetle-comparison-and-recommendations.md)
- [TigerBeetle architecture](https://github.com/tigerbeetle/tigerbeetle/blob/main/docs/ARCHITECTURE.md)
- [TigerBeetle data-file design](https://github.com/tigerbeetle/tigerbeetle/blob/main/docs/internals/data_file.md)
- [TigerBeetle VSR design](https://github.com/tigerbeetle/tigerbeetle/blob/main/docs/internals/vsr.md)
- [TigerBeetle LSM design](https://github.com/tigerbeetle/tigerbeetle/blob/main/docs/internals/lsm.md)
- [Raft paper](https://raft.github.io/raft.pdf)
- Ingres WAL force semantics in [`lgforce.c`](../ingres/src/back/dmf/lg/lgforce.c)
- Ingres page-before-WAL enforcement in [`dm0p.c`](../ingres/src/back/dmf/dmp/dm0p.c)
