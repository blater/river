# River alpha delivery roadmap

Last updated: 2026-08-22

This roadmap defines the product boundary of each River alpha. It records what
users can rely on for evaluation, the hard limits they must design around, and
the evidence required before the next alpha is promoted. An alpha checkpoint
is not a production-readiness or compatibility promise: River remains pre-V1
and changes internal APIs and durable formats directly when that produces a
cleaner implementation.

## Delivery sequence

| Delivery | Goal | Status | Estimated remaining engineering |
| --- | --- | --- | ---: |
| Alpha 1 | Useful bounded embedded/loopback SQL database with durable n-table joins | Shipped at `e72967e` | Complete |
| Alpha 2 | Robust computed and correlated predicate subqueries (P4C) | Active on `feature/p4c-subqueries` | 9–15 engineer-days |
| Alpha 3 | Run a functional one-warehouse TPC-C workload through the independently supplied driver | Planned | 48–84 engineer-days after Alpha 2, excluding driver work |

Alpha 2 is the current product slice. Alpha 3 does not broaden Alpha 2 while
P4C is incomplete. Storage/composite-key design may be researched in parallel
only with disjoint ownership and without changing the P4C contract.

## Alpha 1 — bounded SQL and n-table joins

Alpha 1 is the current public evaluation checkpoint. Its detailed release
boundary is in [Alpha 1 known limitations](alpha-1-known-limitations.md), and
its normative SQL surface is in the
[SQL conformance profile](../compatibility/sql-conformance-profile.md).

### Included

- Durable heap/B+tree storage, WAL recovery, checkpoints, MVCC transactions,
  savepoints, immediate constraints, indexes, sequences/identity, views, and
  quiescent backup/restore.
- Embedded SQL plus authenticated TLS loopback JDBC and CLI access.
- Eight SQL scalar families, typed parameters/results, DDL/DML, aggregation,
  grouping/`HAVING`, `DISTINCT`, direct/P3 ordering, and bounded spill.
- Two-to-eight-role left-associative `INNER`/`LEFT` joins through one common
  source, with nested-loop, bounded hash, and merge strategies.
- Direct and deepest-derived durable n-table JOIN views with canonical ordered
  lineage, including explicitly aliased self-joins.
- Durable bounded `ANALYZE` statistics and deterministic SQL-order
  nested/hash/merge costing with truthful `EXPLAIN [ANALYZE]` metadata.
- Earlier bounded raw nested/correlated query forms. General computed P4C
  semantics are not part of Alpha 1.

### Hard Alpha 1 limits

| Area | Limit or restriction |
| --- | --- |
| Indexed-table capacity | **65,536 physical row/version slots per table.** Updates consume versions until reclamation, so the live-row envelope can be lower. Exhaustion is explicit rather than unbounded growth. |
| Table shape | At most 8 columns and a 4,096-byte encoded row. |
| Join shape | 2–8 left-associative roles; `RIGHT`, `FULL`, `CROSS`, `NATURAL`, `USING`, right-deep trees, multiple joined blocks, and nondeepest joined blocks are unsupported. |
| Durable view lineage | At most 32 ordered physical role IDs. Durable subquery graphs are unsupported. |
| Hash joins | Stable in-memory buckets through the 1,024-row store threshold; larger admitted inputs use the existing bounded stable fallback, not partitioned spill hashing. |
| P3/spill stores | At most 65,536 rows and 256 MiB per bounded store. |
| Join planning | Statistics cost strategies in SQL role order. Physical inner-island reordering is deferred; DML does not refresh statistics until `ANALYZE` is rerun. |
| Backup/operations | Backup is quiescent/offline. No online migration, repair automation, production packaging, replication, or failover. |
| Network | Authenticated TLS loopback only; non-loopback deployment is unsupported. |

The 65,536-slot table ceiling alone prevents a standard one-warehouse TPC-C
load: its initial `ORDER_LINE` relation is roughly 300,000 rows before workload
growth.

## Alpha 2 — robust P4C subqueries

Alpha 2 replaces the singleton/raw nested-query bridge with one canonical,
bounded query graph and the ordinary scalar/three-valued Boolean engine. The
complete architectural and semantic contract is
[the P4C delivery plan](../plans/m5-p4c-subqueries.md).

Delivery progress: P4C-0 through P4C-6 are accepted on
`feature/p4c-subqueries` at `f85c499`. Joined root and child graph blocks run
through the common 2–8-role join engine, and scalar/`EXISTS`/membership value,
LIMIT, cache, resource-bound, temporal, Unicode, and allocation semantics are
complete. Safe child access and exact per-edge `EXPLAIN [ANALYZE]` plan/counter
truth are also complete. P4C-7 consumer integration is the next production
task.

### Included target

- Multiple sibling and recursive `EXISTS`, scalar, and `IN`/`NOT IN` edges
  under parenthesized `NOT`/`AND`/`OR`, with left-to-right lazy execution.
- Computed local and correlated operands over any visible lexical ancestor,
  including n-table joined parent and child blocks.
- Scalar zero/one/multiple-row semantics, complete membership NULL/empty-set
  3VL, typed NULL inference, Unicode/temporal ownership, and global lexical
  typed-marker binding.
- Nested-filtered sources feeding direct projection, outer aggregate/group/
  `HAVING`, `DISTINCT`, ordering/spill, and P3 consumers exactly once.
- Bounded lazy uncorrelated caching, correlated streaming, safe child index
  access, retryable cleanup, no per-row allocation, and per-edge
  `EXPLAIN [ANALYZE]` truth.

### Major Alpha 2 restrictions

- The Alpha 1 physical-table, row-width, column, spill, network, and operations
  limits remain unchanged. Alpha 2 is therefore not TPC-C-capable.
- A child subquery may use one physical table or one admitted n-table join,
  canonical `WHERE`, one computed scalar projection, and `LIMIT`; child
  aggregate/`GROUP BY`/`HAVING`/`DISTINCT`/`ORDER BY`/P3 stages are deferred.
- At most 32 query blocks, 31 subquery edges, 8 Boolean leaves per block,
  32 scalar and 32 Boolean nodes per block, and 1,024 reached membership rows.
- SELECT-list subqueries, row-valued subqueries, `ANY`/`ALL`, CTEs, recursive
  SQL, and lateral `FROM` items remain unsupported.
- Durable views containing subqueries remain fail-closed. They are a separate
  core-SQL delivery after the higher-priority schema/TPC-C work.

### Promotion boundary

Alpha 2 requires the full C1–C4 P4C slice, full SQL/engine regression suites,
allocation and retained-memory evidence, design-debt policy, exact lifecycle
failure/reuse evidence, and independent relational-semantics plus allocation
review. A compile-green graph checkpoint is not an Alpha 2 release.

## Alpha 3 — functional TPC-C readiness

Alpha 3 shall run the standard five-transaction TPC-C workload through the
independently developed driver. The goal is a reproducible functional and
performance preview, not an official TPC result or certification claim.

### Required database additions

1. **Composite relational identity:** primary, unique, secondary, and foreign
   keys with at least four typed components, including text components needed
   by customer-name lookup. Comparators, WAL, recovery, catalog validation,
   dependency checks, and metadata must share one canonical tuple encoding.
2. **TPC-C-scale bounded storage:** replace the 65,536-slot table ceiling with
   a measured bounded capacity of at least 1,048,576 physical row/version slots
   per relevant table, plus reclamation sufficient for the declared run. The
   implementation must retain explicit `RESOURCE_EXHAUSTED` behavior rather
   than becoming unbounded.
3. **Composite access and DML:** point/range lookup, ordered scans, insert,
   update, and delete over composite predicates without table-scan-only
   execution. Transactional uniqueness and foreign-key checks must remain
   atomic under concurrency.
4. **Remaining transaction SQL:** descending latest/oldest-row selection with
   `LIMIT`, safe arithmetic updates, the required nonunique customer-name
   ordering, and `COUNT(DISTINCT ...)` or one equivalent admitted canonical
   plan used by Stock-Level.
5. **Concurrency and recovery evidence:** the five TPC-C transaction types,
   deadlock/retry handling, transaction invariants, checkpoint/reopen, and a
   bounded failure/recovery run with no leaked locks, cursors, versions, or
   partial transaction effects.

The external driver owns data generation, terminal scheduling, the transaction
mix, keying/think times, and result presentation. River owns the schema/load
admission, SQL semantics, transactional behavior, capacity, and engine metrics
needed by that driver; this milestone does not authorize unrelated JDBC work.

### Alpha 3 acceptance run

- Load the standard one-warehouse schema and initial data without rewriting it
  into denormalized single-column surrogate keys.
- Run all five standard transactions with ten concurrent terminals, including
  a warm-up and at least a 30-minute measured interval.
- Preserve the standard transaction mix and validate the database consistency
  conditions before and after the run.
- Complete without hitting row/version, lock, cursor, WAL, page, index, or
  retained-memory bounds; publish any configured bounds with the result.
- Checkpoint and reopen the loaded database, then rerun a functional transaction
  sample. Include one injected abort/retry and one deadlock/retry path.
- Report throughput and latency as River engineering measurements. Do not use
  the protected `tpmC` label or claim TPC-C compliance without the separate
  specification, pricing, full-disclosure, and audit process.

### Expected Alpha 3 restrictions

- One warehouse is the required promoted scale; larger scales are evidence-led
  follow-ons, not implied by the release name.
- River remains single-node, loopback-only, pre-V1, and bounded. Online backup,
  failover, replication, and production operations are not Alpha 3 gates.
- The driver is an immediate consumer, not a reason to broaden JDBC beyond the
  exact typed statements and lifecycle behavior the workload requires.

## Work after Alpha 3

The next core priorities remain full
[online schema evolution](../plans/m5-online-schema-evolution.md)—including
`ALTER TABLE`, online index creation/removal, foreign-key/view changes,
constraints, defaults, and generated values—and
[durable subquery views](../plans/m5-durable-subquery-views.md). Broader JDBC,
observability, replication, and operational systems remain lower priority
unless they directly block one of these product slices.
