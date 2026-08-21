# River product charter

Status: accepted execution baseline

Accepted: 2026-08-09 through the instruction to implement the River project
plan in full

## Product objective

River is a general relational database written in Java. It targets high OLTP
throughput and predictable tail latency while retaining SQL, arbitrary
transactionally maintained indexes, multi-statement transactions, relational
constraints, and meaningful analytical scans.

The first product is a crash-safe, operational single-node database. Replicated
durability extends that same engine after the local product has passed its
backup, restore, upgrade, boundedness, and soak gates; it is not a separate
database or an excuse to defer local correctness.

## Delivery priority: useful function first

River measures progress by useful database behavior on the shortest path to a
working kernel: storing data, recovering it, transacting on it, and querying
it. Infrastructure, observability, plans, evidence, and review exist only to
help deliver that behavior. They are not independent product outcomes and must
not displace kernel implementation.

The following rules govern investment and sequencing:

- Beyond the minimum needed to build and test the current kernel, no
  infrastructure or observability task starts without a named, immediate
  production-kernel consumer.
- The task must state the consumer's present need and the smallest capability
  that unblocks it. Anticipated future consumers do not justify work now.
- Diagnostics are added with the kernel path that needs them. There is no
  separate expansion of observability APIs, event catalogs, exporters, or
  review machinery ahead of working database behavior.
- A planned module is not included in the active build or wired to other
  modules until the same delivery slice gives it production code and an
  immediate consumer or entry point.
- Reviews and evidence are bounded checks on a concrete code or product
  decision. Repeating a review without new risk or implementation evidence is
  not progress.

When useful function and supporting process compete for capacity, useful
function takes priority. The required support is the smallest amount that
makes the current kernel change safe and testable.

## Supported delivery target

The M6 operational beta provides:

- local durable commit backed by a recoverable WAL;
- heap tables and transactionally consistent B+tree indexes;
- read-committed, repeatable-read, and serializable transactions;
- SQL DDL/DML, joins, aggregation, sorting, immediate constraints, and
  explainable plans within a versioned conformance profile;
- embedded and secure remote JDBC access;
- bounded execution memory with vectorized scans and spill where required;
- backup, restore, verification, a target-neutral logical migration utility
  with one supported initial source adapter, observability, packaging, and
  format upgrade/rollback procedures.

The M7/M8 extension adds one consensus group per database, durable full
replicas, state synchronization, membership operations, and operational
failover. `QUORUM_DURABLE` is the distributed durability default.

## Durability promises

`LOCAL_DURABLE` acknowledges only after the local WAL durability contract is
satisfied. `QUORUM_DURABLE` acknowledges only after a declared persistence
quorum covers the complete transaction decision prefix and the leader has
applied and published the transaction visibility decision.

An optional volatile quorum acknowledgement is not called durable, is never the
default, and ships only after R3 if it demonstrates material benefit and exact
bounded-loss reporting. Hardware isolation reduces correlated risk but does not
turn volatile memory into durable storage.

## Sources of truth

The ordered journal selects recoverable history. Heap, index, catalog, and MVCC
state materialize that history and are the source queried by SQL. A query never
scans the journal to compensate for apply lag. Authoritative indexes and
constraints share the transaction decision and visibility point with their
base rows.

## Engineering objectives

- Zero steady-state allocation on designated kernel data paths after warm-up,
  or an explicitly measured fixed amortized budget.
- No per-row allocation in SQL batch execution.
- Explicit buffer ownership and copy budgets at asynchronous boundaries.
- Bounded queues, rings, histories, pins, versions, locks, result retention,
  and state-transfer windows with backpressure before overwrite.
- Status-driven expected failure; exceptions only at required Java/public
  boundaries and cold control paths.
- Validation once at external, persisted, replica, or ownership boundaries;
  trusted typed internal calls do not repeat defensive checks.
- Portable standard Java is the correctness reference; native acceleration is
  optional and contract-tested.

Numeric throughput, latency, allocation, copy, amplification, queue, and
recovery envelopes are owned by P05. They cannot be invented in this charter.

## Explicit exclusions from the initial product

- sharding, distributed SQL planning, multi-leader writes, or cross-database
  distributed transactions;
- follower reads before R6 and linearizable follower reads without a separate
  proof;
- volatile quorum acknowledgement before R4;
- copy-on-write/LSM conversion before the independently measured R5 decision;
- parser or SQL-standard completeness outside the published conformance matrix;
- byte compatibility with legacy Ingres storage formats;
- a migration source adapter beyond the explicitly supported initial target,
  or migration work that displaces the M1→M5 kernel and relational path;
- byte-identical physical layout across replicas;
- arbitrary selective page transfer from fuzzy in-place checkpoints;
- native I/O as a requirement for correctness;
- browser administration, XA, and every JDBC optional method in v1.

## Milestone authority

The milestone and gate definitions in the implementation plan are binding.
Code presence does not waive a hard dependency or gate. Optional capability
work cannot displace the M1→M6 local critical path.
