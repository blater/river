# River and TigerBeetle: Comparative Analysis and Recommended Choices

<!-- markdownlint-disable MD013 -->

Status: Reviewed decision analysis; recommendations incorporated into the high-level plan and feed focused ADRs

Audience: River architecture, storage, transaction, replication, SQL, and operations contributors

Related plans:

- [River High-Level Architecture and Delivery Plan](river-high-level-plan.md)
- [River Replicated Journal, Durability, and Storage Evolution Plan](river-replicated-journal-durability-plan.md)
- [River Engineering Personas and Performance Charter](river-engineering-personas-and-performance-charter.md)

## 1. Purpose

This document compares River's proposed replicated-journal and storage architecture with TigerBeetle and turns the comparison into implementation choices.

The following product objective is not negotiable for the purpose of this analysis:

> River is a general relational database with SQL, arbitrary schemas and indexes, interactive transactions, constraints, joins, and mixed OLTP/query workloads.

River is therefore not trying to reproduce TigerBeetle's accounting API, fixed transaction vocabulary, fixed object shapes, or single specialized workload. The goal is to adopt TigerBeetle mechanisms where they strengthen River without undermining the relational objective.

## 2. Executive conclusion

TigerBeetle is the stronger integrated design for a narrow set of deterministic, non-interactive, highly contended operations. River's proposed design is the better product architecture for a general relational database, but it has a much larger correctness and performance surface.

The recommended strategy is:

1. Preserve River's SQL, MVCC, arbitrary-index, and interactive-transaction model.
2. Borrow TigerBeetle's bounded journal, batching, concurrent replication/persistence, copy-on-write checkpoint root, checksums, state synchronization, incremental background work, and deterministic simulation practices.
3. Replicate River's physiological WAL first, then evolve toward a canonical resolved transaction-effect representation where benchmarks and recovery design justify it.
4. Use durable consensus as the default and first distributed contract.
5. Add volatile-quorum acknowledgement only after durable-quorum measurements demonstrate a material benefit and its weaker semantics are fully supported.
6. Retain concurrent execution for independent relational workloads, but add bounded deterministic scheduling and compiled fast paths for predictable high-volume transactions.
7. Prefer logical equivalence and checkpoint-based repair over requiring byte-identical physical replicas.
8. Stage the storage evolution: correct in-place pages and WAL first, copy-on-write checkpoint generations second, and an LSM conversion only if workload measurements support it.

Most of River's current high-level choices remain appropriate because the target is a general relational database. The weaknesses should be mitigated rather than "solved" by narrowing River into a TigerBeetle-like domain engine.

## 3. Shared architectural foundation

River and TigerBeetle share or can share these principles:

- A bounded ordered journal.
- The transaction decision is journal-committed before its CSN becomes SQL-visible; journal commitment of physiological frames alone does not expose their owning transaction.
- Queries operate on materialized state, not raw journal records.
- Recovery uses a validated checkpoint plus its required journal-committed suffix and transaction/MVCC recovery state.
- Journal persistence, replication, prefetch, and state construction are pipelined.
- Checkpoints provide a safe journal-reclamation boundary.
- Checksums cover messages, journal entries, state blocks, and checkpoint metadata.
- Lagging replicas install state and then replay a suffix.
- All queues and retained histories are bounded and apply backpressure.
- Background storage work is incremental and subject to latency budgets.

These mechanisms are suitable for a general relational database. TigerBeetle's fixed accounting state machine, sequential application model, fixed-size records, and byte-identical LSM layout are not general requirements.

## 4. Comparative analysis

| Dimension | River proposed approach | TigerBeetle | Consequence |
| --- | --- | --- | --- |
| Product scope | General SQL database | Specialized financial transaction database | River serves broader workloads but carries much more complexity. |
| Transaction shape | Interactive, variable, multi-statement | Non-interactive predefined operations | River needs locks/MVCC and cannot simply replicate input commands. |
| Execution | Concurrent transactions and query operators | Sequential deterministic state-machine commit | River can exploit independent work; TigerBeetle has simpler ordering and tails. |
| Replicated input | Initially WAL; later resolved transaction effects | Immutable deterministic prepares | River needs an explicit nondeterminism and result-capture boundary. |
| Durability | Local durable, durable quorum, optional volatile quorum | Durable persistence quorum | River offers policy flexibility but adds semantic and operational risk. |
| Local storage | In-place pages initially; optional CoW/LSM evolution | Integrated CoW grid and LSM forest | River can deliver incrementally but carries a constrained intermediate state. |
| Query state | Heap/B+tree/MVCC, later storage alternatives | Specialized LSM trees and fixed lookup paths | River supports arbitrary access paths but has higher write and memory cost. |
| Constraints | General unique, foreign-key, check, and catalog rules | Domain-specific accounting invariants | River's replicated effects and conflict control are harder. |
| Physical determinism | Logical equivalence is sufficient initially | Byte-identical deterministic layout | TigerBeetle can perform stronger block repair. |
| Resource bounds | Bounded kernel queues plus variable SQL work | Static allocation and explicit global bounds | River requires admission and spill policies for unpredictable statements. |
| Consensus | ADR pending; likely maintained Raft baseline | Custom VSR with flexible quorums and protocol-aware recovery | River can reduce implementation risk but may initially miss specialized optimizations. |
| Testing | Crash, history, property, and planned simulation tests | Deterministic full-cluster simulation is central | River must make simulation a first-class deliverable, not an optional test suite. |

## 5. Advantage analysis

### 5.1 General relational capability

River supports data and operations TigerBeetle intentionally leaves to a general-purpose database:

- Arbitrary table definitions and row shapes.
- Arbitrary primary and secondary indexes.
- Interactive transactions and savepoints.
- Joins, aggregation, sorting, and analytical scans.
- Transactional catalog and schema changes.
- General unique, check, and referential constraints.
- Multiple isolation levels and historical snapshots.

**Assessment:** River's current choice is the correct choice for the product objective. Replacing it with a fixed operation vocabulary would invalidate the objective rather than improve the implementation.

### 5.2 Parallelism for independent work

TigerBeetle's target workload contains popular accounts that naturally serialize. River workloads may contain independent tenants, tables, keys, scans, and partitions. Concurrent execution can use multiple cores and hide I/O for those workloads.

**Assessment:** River should retain concurrent MVCC and lock-based execution. It should add deterministic transaction templates and execution lanes where contention makes serialization cheaper, rather than imposing one sequential state machine on every workload.

### 5.3 Flexible durability and deployment

River's planned separation of local durability, durable consensus, volatile acceptance, and checkpoint durability permits embedded, single-node, replicated, and replayable-ingest deployments.

**Assessment:** The separation is useful for a general database, but durable consensus must remain the default. Volatile acknowledgement is an optional product capability, not the foundation of normal SQL `COMMIT`.

### 5.4 Incremental delivery

River can replicate a correct existing WAL before replacing its storage engine. This enables earlier validation of consensus, failover, transaction recovery, and operational tooling.

**Assessment:** The staged choice is best for River. An immediate combined consensus/WAL/CoW/LSM rewrite would multiply risk and delay feedback.

### 5.5 Multiple physical access strategies

General SQL benefits from different physical organizations. Heap plus B+tree can be preferable for update-heavy relational rows, stable row identifiers, and ordered queries; LSM structures can be preferable for write-heavy key-oriented workloads.

**Assessment:** River should not choose LSM solely because TigerBeetle uses it. The storage ADR must use representative River workloads, including scans, joins, multi-index writes, vacuum, and mixed read/write operation.

## 6. Disadvantages and recommended responses

Each subsection identifies the weakness, plausible choices, the recommendation, and why that choice fits the relational objective.

### 6.1 Weakness: nondeterministic general SQL replication

Interactive SQL may depend on concurrent reads, locks, generated values, timestamps, planner choices, physical page allocation, and arbitrary expressions. Replicating SQL text and independently re-executing it can diverge.

| Choice | Benefits | Costs |
| --- | --- | --- |
| Replicate SQL or stored commands | Small replication payload; conceptually close to state-machine replication | Unsafe for arbitrary nondeterminism, concurrency, and changing plans. |
| Replicate physiological WAL | Reuses recovery, supports streaming large transactions, preserves leader decisions | Tied to page format and leader physical choices; harder rolling upgrades. |
| Replicate canonical logical write sets | Storage-independent and easier to inspect | Requires complete resolved-effect capture; may be large; follower must reproduce physical effects. |
| Force all transactions through one deterministic executor | Simplifies ordering and replay | Sacrifices general concurrent SQL performance and interactive semantics. |

**Recommendation:** use physiological WAL as the first replication representation, with explicit transaction decision records and format negotiation. In parallel, define a canonical resolved transaction-effect envelope for future use at commit, CDC, logical repair, and storage migration boundaries.

The canonical envelope records:

- Resolved row values and generated values.
- Base-table and authoritative-index mutations.
- Transaction and commit sequence.
- Catalog effects.
- Constraint outcome metadata.
- Idempotency key and result identity.

River must never require followers to rerun parsing, planning, nondeterministic expressions, or constraint decisions.

**Why the current choice remains best:** physiological WAL is compatible with a page-oriented relational engine and streams arbitrary transaction sizes. A TigerBeetle-style input-only operation log is not sufficient for unrestricted SQL without first resolving all nondeterminism.

**Validation gate:** replay the same committed WAL on replicas under different thread schedules and prove logical table, index, catalog, and MVCC equivalence.

### 6.2 Weakness: lower TPS on TigerBeetle's specialized workload

General SQL adds parsing, binding, planning, MVCC visibility checks, lock management, tuple encoding, and arbitrary index maintenance.

| Choice | Benefits | Costs |
| --- | --- | --- |
| Optimize only generic SQL execution | One programming model | Leaves avoidable overhead on repetitive OLTP paths. |
| Add prepared and cached plans | Retains SQL and removes repeated front-end work | Does not remove generic expression and row-layer overhead. |
| Add compiled transaction templates | Near-domain-operation fast path with SQL semantics | Requires versioning, invalidation, security, and observability. |
| Add hard-coded domain commands | Maximum specialization | Fragments the database and narrows the product. |

**Recommendation:** build a layered fast path:

1. Prepared statements with stable parameter and result contracts.
2. Cached physical plans guarded by schema/statistics generations.
3. Batched parameter execution and batched commit.
4. Compiled transaction templates that combine several SQL statements under one server-side transaction boundary.
5. Optional native operators only when they remain expressible and inspectable through relational semantics.

The transaction template is River's analogue to a TigerBeetle operation: deterministic after parameter binding, server-local, network-round-trip efficient, and still governed by SQL constraints and authorization.

**Why the current choice remains best:** keeping SQL as the semantic authority preserves generality. Fast paths should optimize common SQL transactions rather than replace them with a second domain database.

**Validation gate:** compare a compiled transfer-like River transaction with generic prepared SQL and TigerBeetle-style operation costs; attribute every remaining CPU cycle, allocation, journal byte, and index write.

### 6.3 Weakness: variable tail latency and resource use

Arbitrary transactions, joins, sorts, lock waits, row sizes, and index counts make static TigerBeetle-style global allocation impractical.

| Choice | Benefits | Costs |
| --- | --- | --- |
| Static allocation for the whole engine | Strong bounds and predictable failure behavior | Incompatible with unpredictable SQL and result sizes. |
| Unbounded dynamic allocation | Simple APIs | Unacceptable overload behavior and latency collapse. |
| Budgeted dynamic allocation with spill/admission | Supports SQL while bounding kernel risk | More accounting and rejection paths. |

**Recommendation:** retain dynamic SQL execution but make every resource domain explicit and bounded:

- Per-session and per-query memory budgets.
- Per-transaction mutation and lock budgets.
- Bounded journal publication and consensus windows.
- Bounded result buffering and cursor lifetimes.
- Admission queues by workload class.
- Disk spill for relational operators, never for core consensus metadata.
- Reserved recovery, checkpoint, and consensus capacity.
- Incremental vacuum, compaction, and checkpoint work with per-commit or per-time quanta.

Use static or startup-sized allocation inside narrow kernel components where upper bounds are known: journal slots, message windows, I/O requests, consensus state, checksum buffers, and recovery work descriptors.

**Why the current choice remains best:** budgeted dynamic execution is necessary for general SQL. TigerBeetle's static-allocation discipline should be adopted inside bounded kernel paths, not imposed on arbitrary query plans.

**Validation gate:** overload tests must demonstrate bounded memory, bounded queues, explicit rejection/backpressure, and recovery progress while large SQL work is admitted or cancelled.

### 6.4 Weakness: volatile acknowledgement complicates durability

An operation may be consensus-ordered and visible but not survive a total cluster restart.

| Choice | Benefits | Costs |
| --- | --- | --- |
| Remove volatile acknowledgement | Simplest durable semantics | Gives up a possible latency/RPO trade-off. |
| Make volatile acknowledgement the default | Lowest commit latency | Violates normal durability expectations and increases application risk. |
| Keep it explicit and optional | Supports replayable or latency-sensitive workloads | Adds frontiers, client behavior, recovery reporting, and operational controls. |

**Recommendation:** do not implement `QUORUM_ACCEPTED` until durable quorum, batching, pipelining, and flexible persistence placement are benchmarked. If the benefit remains material, expose it only through an explicit transaction/database policy and a distinct response tier.

Required protections include:

- `accepted` and `durable` receipts.
- Accepted-to-durable notifications or waits.
- Bounded accepted-to-durable age and bytes.
- Backpressure when the RPO budget is exceeded.
- New incarnation identity after restart.
- Exact rollback-range reporting after total-cluster recovery.
- Persistent client idempotency keys.
- A durable fence before irreversible external effects.

**Why the current choice remains best:** the plan's separation of acknowledgement tiers is preferable to pretending RAM consensus is durable. However, omitting the volatile tier entirely is the current implementation recommendation until measurements justify it.

**Validation gate:** accepted mode must show a workload-level latency or throughput improvement that exceeds its implementation and operational cost; total-cluster tests must recover exactly the durable prefix.

### 6.5 Weakness: the intermediate in-place page engine constrains asynchronous persistence

In-place pages cannot safely contain changes whose undo/redo information is absent from stable storage.

| Choice | Benefits | Costs |
| --- | --- | --- |
| Keep in-place pages indefinitely | Reuses mature page/B+tree methods | Page flush remains gated by local WAL durability. |
| Rewrite immediately to CoW/LSM | Clean independent writers and atomic roots | High combined storage, SQL, recovery, and replication risk. |
| Stage WAL replication, then introduce CoW generations | Separates correctness milestones | Carries an intermediate architecture and migration work. |

**Recommendation:** retain the page-LSN/WAL-force invariant for the first durable replicated engine. Prototype a copy-on-write database manifest and atomic root after replication and failover are correct.

The CoW prototype must atomically cover:

- Base tables.
- Every authoritative index.
- Catalog and schema state.
- MVCC and transaction recovery metadata.
- Allocation/free-space metadata.
- Format and cluster incarnation.

Only then may page/state writers advance independently of local WAL durability.

**Why the current choice remains best:** staging is the safest route for a new general relational engine. TigerBeetle's integrated storage model is a valuable target, but adopting every component at once would make failures impossible to localize.

**Validation gate:** crash at every candidate-block, manifest, root, and journal boundary; recovery must select either the complete old root or complete new root, never mixed relational/index state.

### 6.6 Weakness: weaker physical repair

Concurrent page allocation, vacuum, index maintenance, and compaction can produce physically different but logically equivalent replicas.

| Choice | Benefits | Costs |
| --- | --- | --- |
| Require byte-identical replicas | Precise block repair and strong debugging | Constrains concurrency, allocation, maintenance, and upgrades. |
| Repair only by full backup restore | Simple correctness story | Slow recovery and high network cost. |
| Checkpoint-identity block transfer plus logical verification | Efficient common-case repair without full physical determinism | Cannot borrow arbitrary current blocks across different checkpoint layouts. |

**Recommendation:** define immutable copy-on-write checkpoint-manifest
identities. In R5, blocks are transferable only when the receiving replica
expects the same manifest identity, block identity, format, and checksum. The
earlier in-place R3 engine installs a complete validated snapshot or logically
rebuilds the affected range/index rather than treating a fuzzy checkpoint's
mutable pages as one immutable block set.

Add:

- External checksums for misdirected-I/O detection.
- Merkle or hash-chained checkpoint manifests.
- Offline and online table/index verification.
- Index rebuild from base relational state.
- Full snapshot fallback.

Deterministic compaction and allocation may be introduced for a future LSM implementation if it does not compromise SQL concurrency or upgradeability.

**Why the current choice remains best:** logical equivalence is the right initial contract for a general relational database. Byte-identical replicas are an optimization with substantial architectural cost, not a prerequisite for correct replication.

**Validation gate:** corrupt individual blocks, manifests, indexes, and WAL records and prove River either repairs from an identical checkpoint, rebuilds logically, or fails closed with a precise recovery action.

### 6.7 Weakness: consensus design is not yet proven

TigerBeetle owns an integrated VSR implementation with flexible quorums, protocol-aware recovery, and deterministic simulation. River currently has a protocol decision gate.

| Choice | Benefits | Costs |
| --- | --- | --- |
| Build custom VSR immediately | Maximum control and access to TigerBeetle-like techniques | Highest correctness, testing, and schedule risk. |
| Use a maintained Raft implementation behind River interfaces | Mature baseline and familiar operations | Dependency constraints; may not expose every storage optimization. |
| Build custom Raft | Control over storage and simulation | Repeats a large body of difficult work. |

**Recommendation:** evaluate a maintained Raft core as the preferred baseline for
the first full-replica implementation behind a River-owned consensus/storage
interface, provided it supports synchronous durable acknowledgements,
snapshots, membership changes, batching, and deterministic test control. The R0
ADR still selects the protocol. Prototype VSR/flexible-quorum behavior only if
benchmarks or the fault model identify a concrete Raft limitation.

River still owns:

- Durable operation and checkpoint formats.
- Node incarnation and database identity.
- State-machine application.
- State synchronization and verification.
- Client transaction semantics.
- Fault-injection and history tests.

**Why this is best for River:** consensus is infrastructure, not the relational differentiator. A maintained core reduces risk while River concentrates on SQL transaction integration. The dependency remains replaceable under the high-level plan's interface policy.

**Validation gate:** the selected implementation must pass River's deterministic message/storage fault harness and mixed-version protocol tests; library claims are not sufficient.

### 6.8 Weakness: large and streaming transactions

TigerBeetle operations have fixed bounds. SQL transactions may update millions of rows and cannot require one in-memory consensus message.

| Choice | Benefits | Costs |
| --- | --- | --- |
| One consensus entry per transaction | Simple atomic boundary | Unbounded message and memory size. |
| One consensus entry per WAL record | Natural streaming | High consensus overhead and interleaving complexity. |
| Bounded WAL batches plus transaction decision record | Streams with amortized replication | Recovery and retention must preserve transaction dependencies. |

**Recommendation:** replicate bounded batches of versioned WAL frames. A final transaction decision record makes the effects visible. All frames carry transaction identity and predecessor/sequence information. Followers may persist provisional frames but cannot expose them before the decision.

Add quotas for transaction age, journal bytes, locks, mutations, and temporary storage. Transactions exceeding online limits either spill to a controlled durable provisional area, use bulk-load protocols, or fail before exhausting journal retention.

**Why the current choice remains best:** streaming WAL plus a decision record naturally supports arbitrary SQL transaction sizes. Fixed operation bounds alone would be an unacceptable relational limitation.

**Validation gate:** commit, abort, failover, checkpoint, and ring-wrap tests with transactions larger than a consensus batch and larger than memory mutation budgets.

### 6.9 Weakness: table/index/catalog atomicity is broader

A general transaction may modify many base rows, several indexes, constraint metadata, and catalogs.

| Choice | Benefits | Costs |
| --- | --- | --- |
| Recompute indexes asynchronously | Lower foreground write cost | Incorrect current queries and unusable unique constraints. |
| Replicate only base rows | Small payload | Followers must independently reproduce nondeterministic physical index changes. |
| Replicate complete authoritative effects | Strong atomicity and inspectability | Larger log and write amplification. |

**Recommendation:** every authoritative index and catalog effect belongs to the same transaction decision and commit sequence as its base-row mutation. The apply layer publishes the operation only after all structures are queryable together.

Asynchronous derived indexes are permitted only when they are not correctness authorities, expose their applied frontier, and are excluded by the planner for newer snapshots.

**Why the current choice remains best:** arbitrary synchronous indexes and constraints are fundamental relational capabilities. Their write cost is real and should be optimized through batching and key encoding, not removed from the transaction contract.

**Validation gate:** fail at every row/index/catalog application boundary and verify that no supported snapshot observes a partial operation.

### 6.10 Weakness: concurrent relational work competes with replication and maintenance

Long scans, joins, backup, vacuum, and compaction can interfere with commit latency and recovery progress.

| Choice | Benefits | Costs |
| --- | --- | --- |
| One global execution thread | Strong isolation and predictable commit work | Unacceptable for general scans and independent SQL workloads. |
| Shared unconstrained worker pool | Easy utilization | Priority inversion and recovery starvation. |
| Resource-separated schedulers with admission | Preserves concurrency and critical-path progress | More scheduling and capacity planning. |

**Recommendation:** reserve execution and I/O capacity for consensus, journal persistence, recovery, and checkpoint root publication. Govern SQL, scans, maintenance, and state sync through separate bounded workload classes.

Maintenance work is incremental and preemptible at explicit boundaries. Query operators cooperate with cancellation and resource budgets. A cluster under overload must continue to make journal and recovery progress even when it rejects new SQL work.

**Why the current choice remains best:** concurrent schedulers are necessary for general relational work. The TigerBeetle lesson is to separate the sequential control plane from parallel data-plane work, not to serialize every River operation.

**Validation gate:** demonstrate bounded commit latency and recovery progress during maximum scans, backup, compaction, follower catch-up, and deliberate overload.

### 6.11 Weakness: follower reads add a second consistency surface

TigerBeetle's specialized API and full replicas do not imply that arbitrary River SQL can immediately read any follower safely.

| Choice | Benefits | Costs |
| --- | --- | --- |
| No follower reads | Simplest first failover design | Leaves read capacity unused. |
| Explicit stale snapshot reads | Scales suitable read-only work | Applications must choose acceptable staleness. |
| Linearizable follower reads | Transparent fresh reads | Requires read-index/lease/closed-version protocol and applied synchronization. |

**Recommendation:** first deliver non-serving hot standbys. Then support explicit reads at a published closed commit sequence. Add linearizable follower reads only after the consensus and MVCC protocols prove a safe read version and `readVersion <= applied`.

**Why the current choice remains best:** deferral protects the primary objective—correct general SQL—while leaving a clear scaling path. Immediate follower reads would combine two difficult projects before failover is proven.

**Validation gate:** isolation-history testing during lag, view change, clock skew, checkpoint, and transaction cleanup.

### 6.12 Weakness: many choices increase delivery risk

River's protocol, operation format, storage format, copy-on-write structure, and optional durability tier cannot all remain open indefinitely.

**Recommendation:** make decisions in dependency order:

1. External durability and failure contract.
2. Monotonic frontier model.
3. Replication payload and transaction decision boundary.
4. Consensus implementation and membership model.
5. Durable full-replica failover.
6. State synchronization and repair identity.
7. Copy-on-write checkpoint format.
8. Optional volatile acknowledgement.
9. Optional LSM conversion and physical determinism.
10. Follower reads and only later sharding.

Each decision requires an ADR, focused prototype, fault model, and numeric performance gate. Later phases cannot silently reopen earlier durable formats without an upgrade plan.

**Why the current staged choice remains best:** the order isolates correctness risks and preserves a usable relational engine at every major milestone.

## 7. Recommended target architecture

The recommended River end state is not a direct TigerBeetle clone. It combines general relational execution with a TigerBeetle-inspired persistence core.

```text
SQL / prepared transaction template
                |
                v
MVCC + locks + constraint validation
                |
                v
resolved transactional effects
                |
                v
bounded replicated journal batches ----> durable consensus quorum
                |                                  |
                v                                  v
ordered apply frontier                    client durable result
                |
                v
mutable relational state
  base tables + authoritative indexes + catalog + MVCC
                |
                v
immutable flush/checkpoint state
                |
                v
copy-on-write blocks and complete database manifest
                |
                v
redundant atomic checkpoint root
```

Normal reads use materialized state. Recovery and catch-up use a validated
checkpoint plus the required journal-committed suffix; SQL visibility is
reconstructed from transaction decisions and MVCC state. Durable consensus is
the default. The optional accepted frontier is layered on later without
changing the meaning of durable commit.

## 8. Decision register

| Decision | Recommendation | Current-choice assessment |
| --- | --- | --- |
| Product model | General SQL relational database | Keep; essential objective. |
| Transaction execution | Concurrent MVCC/locking with bounded resources | Keep; best for independent arbitrary workloads. |
| Specialized fast path | Prepared plans/batching first; compiled transaction templates only after profiling | Add conditionally; borrow non-interactive efficiency without narrowing semantics or pre-empting v1. |
| First replication payload | Bounded physiological WAL batches plus decision record | Keep; best staged fit for arbitrary transactions. |
| Future replication boundary | Canonical resolved transaction effects | Develop incrementally; valuable for upgrades, CDC, and logical repair. |
| Consensus baseline | Evaluate maintained Raft behind River interfaces first | Preferred starting candidate, not selected until the R0 ADR validates it against VSR and River requirements. |
| Default commit | Durable persistence quorum | Keep; safest general database contract. |
| Volatile acknowledgement | Deferred, explicit optional tier | Keep in architecture, do not implement until justified. |
| Initial local storage | In-place pages with WAL-before-page | Keep for first replicated release. |
| Storage evolution | CoW generations and atomic complete-database root | Pursue after durable failover. |
| LSM adoption | Benchmark decision, not architectural assumption | Current neutrality is best. |
| Normal query source | Materialized state only | Keep; journal is not a query engine. |
| Authoritative indexes | Synchronous transaction state | Keep; required for relational correctness. |
| Physical replica identity | Logical equivalence plus checkpoint-scoped block identity | Best initial fit; byte identity is optional optimization. |
| Follower reads | Deferred; stale snapshot before linearizable | Keep; avoids combining failover and read-consistency risk. |
| Sharding | Deferred until single-group system is proven | Keep; distributed SQL is a separate architecture. |

## 9. Work plan mapped to replication phases

### Workstream A: relational deterministic boundary

Maps primarily to Phases R0-R2.

- Specify nondeterministic SQL value capture.
- Version and stream replicated WAL batches.
- Define the transaction decision and visibility record.
- Define the canonical effect envelope and decoder incrementally; it does not
  block initial bounded-WAL replication.
- Prove table/index/catalog equivalence after replay.

### Workstream B: high-throughput relational fast path

Prepared/cached plans and batching begin in the base River phases. Compiled
transaction templates are a measured Phase 5 candidate and may proceed in
parallel with replication only after generic-path profiling.

- Prepared and cached physical plans.
- Batched parameter execution and commit.
- Compiled server-side transaction templates.
- Allocation and branch profiling of commit application.
- Prefetch planning before synchronous mutation application.

### Workstream C: bounded execution and maintenance

Applies to every phase.

- Resource budgets and admission classes.
- Reserved replication/recovery capacity.
- Incremental checkpoint, vacuum, and compaction work.
- Overload and cancellation tests.
- Tail-latency budgets under maintenance.

### Workstream D: durable replication and repair

Maps to R0-R3.

- Select and wrap consensus implementation.
- Durable-quorum batching and pipelining.
- Incarnation-safe membership and restart.
- Checkpoint state transfer.
- Checksum, manifest, and logical verification.
- Automated failover and rolling upgrade.

### Workstream E: copy-on-write checkpoint evolution

Maps to independent research after the local format is understood and to R5
delivery after R3 failover is correct. It is not Phase 0 core work and does not
block R1 or R2.

- Compare CoW page generations with LSM structures.
- Define complete-database manifest and atomic root.
- Implement unreachable-block recovery.
- Support checkpoint-scoped block transfer.
- Measure write amplification, scans, and tail behavior.

### Workstream F: optional volatile acknowledgement

Maps to R0 measurement and R4 only if approved.

- Demonstrate benefit over durable batching/quorum.
- Define accepted receipts and durable fences.
- Bound RPO and enforce backpressure.
- Model volatile restart and incarnation behavior.
- Prove exact durable-prefix recovery after total outage.

### Workstream G: follower reads

Maps to R6.

- Publish applied and closed commit sequences.
- Serve explicit stale snapshots.
- Add read-index or lease protocol if linearizable reads are required.
- Run isolation history checking through failover and lag.

## 10. Cross-cutting validation gates

No weakness is considered mitigated solely by completing an implementation task. The following evidence is required:

1. **Correctness:** deterministic simulation, crash matrices, history checking, and invariant assertions.
2. **Performance:** representative SQL and transaction-template workloads with attributed CPU, allocation, network, WAL, index, and compaction costs.
3. **Boundedness:** explicit maxima, admission behavior, and backpressure for every asynchronous queue and retained history.
4. **Recovery:** checkpoint-plus-suffix equivalence after faults at every durable boundary.
5. **Operability:** supported inspection explains frontiers, quorum state, lag, checkpoint identity, repair, and discarded accepted ranges.
6. **Upgradeability:** versioned payloads, checkpoints, protocol negotiation, and mixed-version rehearsals.
7. **Relational integrity:** base rows, indexes, constraints, catalog, and MVCC visibility remain atomic at every advertised snapshot.

## 11. Rejected strategic alternatives

### Replace River with a TigerBeetle-style fixed operation database

Rejected because it abandons the general relational objective. Compiled transaction templates capture much of the performance benefit without removing SQL.

### Make all River execution single-threaded

Rejected as a global rule because arbitrary relational workloads contain independent work and analytical operators. Sequential deterministic lanes remain an optimization for contended transaction templates.

### Make volatile consensus the normal meaning of `COMMIT`

Rejected because it violates general database durability expectations. It may exist only as an explicit weaker tier.

### Query the WAL to cover storage lag

Rejected because it creates a second execution engine for visibility, indexes, joins, and constraints. River applies committed operations to query-optimized state instead.

### Require byte-identical replicas before replication ships

Rejected because it overconstrains concurrent SQL, maintenance, and upgrades. Checkpoint identity and logical verification provide a practical repair model.

### Combine consensus, CoW storage, LSM, volatile acknowledgement, and follower reads in one milestone

Rejected because failures would span too many new invariants. The staged plan preserves diagnosable milestones and a correct usable database throughout delivery.

## 12. References

- [River Replicated Journal, Durability, and Storage Evolution Plan](river-replicated-journal-durability-plan.md)
- [TigerBeetle architecture](https://github.com/tigerbeetle/tigerbeetle/blob/main/docs/ARCHITECTURE.md)
- [TigerBeetle data-file design](https://github.com/tigerbeetle/tigerbeetle/blob/main/docs/internals/data_file.md)
- [TigerBeetle VSR design](https://github.com/tigerbeetle/tigerbeetle/blob/main/docs/internals/vsr.md)
- [TigerBeetle LSM design](https://github.com/tigerbeetle/tigerbeetle/blob/main/docs/internals/lsm.md)
- [Raft paper](https://raft.github.io/raft.pdf)
