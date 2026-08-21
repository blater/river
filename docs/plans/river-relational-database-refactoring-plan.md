# River Relational-Database Ownership Refactoring Plan

<!-- markdownlint-disable MD013 -->

Status: Accepted 2026-08-12 through project-owner approval of the River
Large-Class Architecture and Refactoring Plan

Deliverable: Q09

Audience: River relational execution, transactions/concurrency, runtime/performance, and architecture reviewers

Related plans:

- [River Project Implementation and Dependency Plan](river-project-implementation-plan.md)
- [River Engineering Personas and Performance Charter](river-engineering-personas-and-performance-charter.md)
- [River Indexed-Table Store Ownership Refactoring Plan](river-indexed-table-store-refactoring-plan.md)

## 1. Purpose

`RelationalDatabase` is the public database facade, but its 2,049 lines also
own embedded-database opening, catalog bootstrap and validation, session
creation, transaction admission, schema-change exclusion and versioning,
sequence reservation and caching, table and index DDL, resumable index build,
resumable table/index removal, dependency scans, foreign-key delete checks,
and the reusable buffers and cursors for all of those operations.

Q09 gives each stateful responsibility one concrete owner before the type,
temporal, maintenance, hash-index, and BRIN-index lanes add more catalog and
index lifecycle behavior. This is an ordered structural refactor. It preserves
the public `RelationalDatabase`, `RelationalSession`, `TableDefinition`, and
result APIs, current catalog and row bytes, schema-change behavior, transaction
semantics, and all existing `StatusCode` outcomes.

The goal is not a small facade by itself. The goal is an implementation graph
in which transaction admission, schema publication, sequence leases, catalog
DDL, index lifecycle, physical cleanup, and dependency checking each have one
authoritative owner and do not share ambient mutable scratch.

## 2. Entry findings

The current class contains four different lifetimes and several overlapping
state machines:

- database-lifetime state: the `EmbeddedDatabase`, schema version, active
  transaction count, current schema-change owner, and sequence cache;
- one-command catalog state: encoded keys and rows, decoded definitions,
  catalog scan cursors, and rename/create/drop mutation scratch;
- persistent schema-operation state: building/dropping publication, batch
  frontier, cleanup completion, resumable catalog markers, and bounded cleanup
  keys; and
- referential-check state: catalog scans, decoded child/view definitions,
  value-index lookup cursors, and result carriers.

The most important coupling is ownership, not line count:

- `RelationalSession` calls back into `RelationalDatabase` for transaction
  admission, schema-change acquisition/completion, DDL mutation, index build,
  drop completion, and delete-reference validation. The database is therefore
  simultaneously its owner, coordinator, service locator, and command
  implementation.
- one `catalogScratch`, `catalogOutput`, `catalogRow`, and `catalogKey` are
  reused by sequences, DDL, dependency scans, index builds, cleanup, bootstrap,
  and validation. `synchronized` makes this work, but the buffers have no
  responsibility-specific lifetime.
- index creation exists in two workflows: transactional in-statement build and
  bounded persistent build/resume. They duplicate row validation, typed value
  extraction, insertion, conflict mapping, and publication rules.
- index and table removal duplicate bounded physical-row deletion and terminal
  transaction cleanup.
- schema versioning and sequence-cache invalidation are coupled through
  `completeSchemaChange`, while persistent build/drop publication mutates the
  same version through separate methods.
- public convenience DDL methods repeat session creation and
  begin/commit/abort ladders. That framing obscures the actual catalog owner and
  makes status precedence difficult to compare.
- `RelationalSession` is itself large. Q09 must not make it the destination for
  extracted database responsibilities or replace one god class with two
  mutually recursive god classes.

The current useful properties remain requirements: bounded reusable storage,
no per-row allocation in index build or reference checks, status-code control
flow, one schema writer, resumable bounded build/drop work, stale-definition
fencing, and atomic catalog/base/index visibility.

## 3. Architectural decisions

1. **Keep `RelationalDatabase` as the public aggregate facade.** It owns the
   embedded database and a small fixed set of concrete collaborators. Static
   create/open methods, replication durability counters, session creation,
   checkpoint, vacuum, and close remain on the facade. Public convenience DDL
   and sequence methods may delegate through it so callers do not change.
2. **Give admission and schema publication one owner.** A package-private
   `RelationalSchemaGate` owns active transaction count, schema-change owner,
   schema version, acquisition/release, and committed publication. It is also
   the database-identity/version token retained by `TableDefinition`; catalog
   decode no longer needs the public database facade solely to establish table
   ownership. The gate does not execute DDL or access catalog bytes.
3. **Separate sequence allocation from catalog DDL.** A package-private
   `RelationalSequenceService` owns sequence lookup/reservation, overflow and
   exhaustion, the bounded cache arrays/replacement policy, and its reusable
   catalog carriers. It owns both user-sequence and identity allocation. The
   transactional creation/deletion of sequence catalog records remains a
   catalog mutation owned by `RelationalCatalogDdl`.
4. **Make catalog DDL a single concrete command owner.** Package-private
   `RelationalCatalogDdl` owns simple transactional catalog mutations: create
   table/view/sequence, drop view/sequence, and rename table/column/index. It
   owns catalog encode/decode scratch and table-definition mutation scratch.
   Multi-phase index/table build and drop markers belong to their lifecycle
   owners. Catalog DDL invokes dependency checking through narrow typed
   methods; it does not own its cursors or arrays.
5. **Give index lifecycle one owner.** Package-private
   `RelationalIndexLifecycle` owns transactional index build, bounded
   reserve/resume/build/publish/cleanup, index-dropping validation, typed index
   row extraction, and all index-build state and scratch. Both current build
   entry paths use one row-copy/validation/insertion kernel. This is a concrete
   B+tree/value-index lifecycle owner, not a speculative access-method
   interface. Hash/BRIN capability interfaces wait for their named E04
   consumers.
6. **Share physical row cleanup without sharing DDL policy.** Package-private
   `RelationalPhysicalCleanup` owns the bounded scan/copy-key/delete/commit
   mechanism for a physical table ID. Table and index workflows decide what
   must be deleted and when catalog publication is safe; the cleanup component
   owns only physical progress, reusable key storage, cursor closure, and
   retry-safe terminal framing.
7. **Separate schema dependencies from row constraints.** Package-private
   `RelationalCatalogDependencies` owns the read-only metadata checks used by
   drop/rename: dependent views, foreign-key definitions, and dependent index
   catalog records. Session-owned `RelationalReferentialIntegrity` owns both
   outbound insert/update parent checks and inbound delete child-row checks,
   including its catalog scan and value-index lookup cursors. This removes the
   global database monitor from the DML path and reunites referential semantics
   currently split between database and session. Neither component mutates
   catalog state.
8. **Conditionally use one concrete convenience-operation coordinator.** A
   package-private `RelationalDatabaseCommands` may own repeated facade-level
   create-session/begin/body/commit-or-abort framing when doing so deletes a
   complete status/cleanup ladder. It uses explicit command methods, not
   callbacks, lambdas, reflection, or an untyped command bag. Keep framing
   beside the facade operation when extraction would create forwarding methods
   or separate failure precedence from the command. Transaction-scoped methods
   on `RelationalSession` continue to own their existing public validation and
   pending-operation lifecycle.
9. **Do not add a Java subpackage during Q09.** The extracted classes should
   remain package-private in `io.riverdb.engine.relational`, beside
   `CatalogRecord`, `RelationalKey`, `TableDefinition`, and
   `RelationalSession`. Moving them to `.relational.catalog`, `.schema`, or
   `.index` would require widening package-private durable codecs and mutable
   descriptors, or adding bridge interfaces with no second consumer. Revisit a
   subpackage only when a real boundary can keep internals non-public. Source
   grouping can still use the `RelationalCatalog*`, `RelationalIndex*`, and
   `RelationalSequence*` prefixes.
10. **Add no compatibility layer.** River is pre-V1 and these are internal
    implementation boundaries. Move callers directly to the target graph; do
    not retain forwarding implementations under old internal names.

## 4. Target ownership graph

```text
RelationalDatabase                         public aggregate facade
  |- EmbeddedDatabase                     durable kernel aggregate
  |- RelationalSessionFactory             conditional construction/bootstrap owner
  |- RelationalSchemaGate                 admission, schema owner/version
  |- RelationalDatabaseCommands           conditional facade transaction framing
  |- RelationalSequenceService            leases and bounded cache
  |- RelationalCatalogDdl                 catalog mutations
  |    `- RelationalCatalogDependencies   read-only schema dependencies
  |- RelationalIndexLifecycle             build/drop/rebuild lifecycle
  |    `- RelationalPhysicalCleanup       bounded physical deletion
  `- RelationalTableLifecycle             table drop/resume coordination
       `- RelationalPhysicalCleanup

RelationalSession
  |- IndexedTransactionSession
  |- RelationalReferentialIntegrity       row-level constraint checks
  |- session-local catalog resolver/scan scratch
  `- transaction and pending-schema-operation state
```

`RelationalSessionFactory` is conditional. Create it only if construction,
catalog bootstrap, and open validation form a cohesive lifetime that deletes
their state and operation family from the facade. Otherwise keep those
operations beside the facade while moving their mutable catalog state to its
authoritative owner. It is never a provider abstraction.

`RelationalTableLifecycle` owns the persistent table-dropping workflow:
marking, schema publication request, per-index and base-table cleanup ordering,
and final removal of table/index catalog records. It does not own generic table
create/rename mutations.

### 4.1 Allowed dependencies

- The facade may call every direct collaborator.
- `RelationalSession` may call `RelationalSchemaGate`,
  `RelationalCatalogDdl`, `RelationalIndexLifecycle`,
  `RelationalTableLifecycle`, `RelationalReferentialIntegrity`, and
  `RelationalSequenceService` through concrete package-private references
  supplied at construction. It must not call back through the facade for those
  behaviors.
- DDL/lifecycle components may use the active `RelationalSession`, durable
  catalog codecs/keys, and their owned scratch. They must not receive the
  public facade or another component's mutable workspace.
- `RelationalSchemaGate` depends on no DDL, sequence, index, table, catalog
  codec, or embedded-storage component.
- `RelationalSequenceService` may read the gate's volatile schema version. A
  cache hit first compares its observed version and clears stale leases before
  returning a value. This closes the publication/invalidation race without a
  callback or a gate-to-sequence dependency.
- `RelationalPhysicalCleanup` does not publish catalog state or schema version.
- No component stores a caller's `CharSequence`, row view, catalog row, or
  `TableDefinition` beyond the documented synchronous call unless it copies it
  into bounded owned storage.

### 4.2 State destinations

| Current state | Target owner | Lifetime |
| --- | --- | --- |
| `embedded` | `RelationalDatabase` | Database |
| `schemaVersion`, `schemaChangeOwner`, `activeTransactions`, table-definition ownership token | `RelationalSchemaGate` | Database / active transaction |
| sequence cache arrays and replacement cursor | `RelationalSequenceService` | Database, invalidated on committed schema change |
| sequence catalog row/key/buffers/results | `RelationalSequenceService` | One reservation/refill |
| general DDL catalog row/key/buffers and rename definitions | `RelationalCatalogDdl` | One catalog command |
| schema dependency catalog cursor/row and scanned table/view | `RelationalCatalogDependencies` | One schema command |
| DML reference definitions, catalog cursor, row and value-index lookup | session-owned `RelationalReferentialIntegrity` | One row constraint check |
| index definition/storage definitions, build cursor/row/key buffer, build frontier | `RelationalIndexLifecycle` | One transactional or persistent index operation |
| bounded deletion keys, cleanup cursor/row/completion | `RelationalPhysicalCleanup` | One cleanup batch |
| dropping-table index catalog keys and table-drop definitions | `RelationalTableLifecycle` | One persistent table drop |
| catalog bootstrap/validation carriers | `RelationalSessionFactory` | Database create/open |

No target component receives a broad `RelationalDatabaseContext`. Constructor
parameters are concrete owners or immediate providers, and mutable scratch is
never shared merely to reduce allocation count.

### 4.3 Concurrency model

- Gate methods synchronize only their own bounded state transitions. A
  successful schema acquisition grants an owner token; it does not retain a
  Java monitor across catalog or storage work.
- Schema-mutating services rely on that exclusive owner token and validate it
  at their entry boundary. They do not add independent monitors or call back
  into the gate while holding another monitor.
- `RelationalSequenceService` remains locally synchronized because sequence
  calls can run without exclusive schema ownership. Referential-integrity
  workspaces are session-local and add no database-global monitor.
- Sequence cache admission reads the gate version while holding only the
  sequence-service monitor. The gate never calls the sequence service, so the
  lock graph is acyclic.
- Session-local catalog resolution and row/index cursors remain session-owned;
  they need no database-global monitor.

## 5. Ordered delivery slices

| Slice | Demonstrable outcome | Constraints and evidence |
| --- | --- | --- |
| Q09a: characterization and graph gate | Every field and method has one target owner; current DDL, dependency, build/resume/drop, schema-fencing, sequence-cache, bootstrap, and failure precedence are characterized. | Add tests only for missing boundaries: build/scan close precedence and reuse, abort failure precedence in convenience commands, schema-change conflict and stale-definition fencing, sequence cache invalidation, and resumed cleanup after reopen. Record the exact current public and package-private call graph. No production abstraction in this slice. |
| Q09b: schema gate | `RelationalSchemaGate` exclusively owns database identity, transaction admission, schema-change ownership, version publication, and release. `RelationalSession`, `TableDefinition`, and catalog decode use the gate rather than the public facade for ownership/version checks. | Preserve foreign-database definition rejection, one-writer admission, owner checks, active-transaction counting, commit-only version increments, stale definition rejection, and cache invalidation ordering. Do not move DDL or catalog buffers yet. Focused concurrent session and failed/aborted schema-change tests pass. |
| Q09c: sequence service and construction/bootstrap ownership | `RelationalSequenceService` owns all reservation/cache state. Construction and catalog bootstrap/open validation move to `RelationalSessionFactory` only if the depth test proves a cohesive owner; either way, the facade owns no catalog buffer and no sequence array. | Preserve reservation size, overflow/exhaustion, identity bounds, returned commit sequence, schema-change retry, cache replacement, cache invalidation, quorum create/open, corrupt catalog rejection, and no per-value allocation after a cache refill. User/identity sequence DDL remains where it is until Q09d. |
| Q09d: dependencies, referential integrity, and catalog DDL | `RelationalCatalogDependencies` owns read-only schema-dependency scans; session-owned `RelationalReferentialIntegrity` owns parent/child row checks; `RelationalCatalogDdl` owns simple catalog mutations. | Preserve view and foreign-key restrictions, insert/update/delete constraint semantics, constraint-index rules, catalog-name collisions, close-status precedence, corrupt catalog handling, and transactional atomicity. Move scratch with behavior; do not pass buffers from the facade. Consolidate convenience framing in `RelationalDatabaseCommands` only after body ownership is stable. |
| Q09e: index lifecycle | `RelationalIndexLifecycle` owns transactional build and persistent bounded build/resume/publish/drop cleanup. One typed row kernel serves both build paths. | Preserve numeric/VARCHAR, NULL, unique/non-unique, constraint violation mapping, duplicate-chain behavior, build frontier, batch limit, reservation/publish order, retry/resume after reopen, failure cleanup, and cursor reuse. Keep storage/access-method behavior concrete; no hash/BRIN abstraction. Run allocation checks over warmed populated builds. |
| Q09f: physical cleanup and table lifecycle | `RelationalPhysicalCleanup` is the only owner of bounded physical row deletion; `RelationalTableLifecycle` owns table-drop ordering and final catalog removal. Index cleanup delegates physical deletion to the same component. | Preserve per-command batch budget across indexes and base table, persistent schema lease ownership, close/abort failure precedence, retry after incomplete work or reopen, deletion of identity sequences, dependent-object rejection, and bounded catalog-key collection. Cleanup must be retry-safe and must not publish catalog removal before physical completion. |
| Q09g: facade/coordinator closure | `RelationalDatabase` is a small public aggregate and lifecycle facade; `RelationalSession` holds concrete collaborators rather than calling the facade for implementation. `RelationalDatabaseCommands` exists only if it owns and deletes a complete convenience-transaction framing family. | Remove obsolete mutable state and method forwarding from the facade. Reject cycles, duplicated status ladders, shared scratch, and context bags through structural tests. Run all relational, SQL DDL/index/sequence/constraint, JDBC boundary, allocation, recovery/reopen, and affected `river-engine` tests, followed by independent relational-correctness, architecture, and performance/allocation reviews. |

The slices are sequential in one integration lane because most begin by moving
overlapping fields from `RelationalDatabase`. Review lenses may run
independently, but two implementers must not edit the same owner or redefine
the schema/catalog contract concurrently.

These slice boundaries are an accepted coordination baseline, not immutable
implementation contracts. The relational integrator may combine, split, or
reorder them when characterization produces a smaller vertical change and the
authority map, one-way dependencies, behavior, evidence, and WIP limits remain
intact. Do not retain a forwarding layer or duplicate owner merely to preserve
the table above.

## 6. Behavioral and failure evidence

Q09 is not complete without focused evidence for:

- database create/open with local and durable-quorum WAL, catalog bootstrap,
  invalid/corrupt catalog sequence, close, checkpoint, and vacuum delegation;
- transaction admission during ordinary work and schema change, exact owner
  release on commit/abort/failure, and stale `TableDefinition` rejection after
  every committed catalog change;
- public convenience DDL and transaction-scoped DDL producing identical
  catalog visibility and status precedence;
- create/rename/drop table, column, index, view, user sequence, identity
  sequence, constraints, and name collision behavior;
- view dependencies, foreign-key metadata dependencies, indexed child-row
  existence checks, NULL foreign keys, missing/corrupt reference indexes, and
  cursor cleanup on success and failure;
- transactional index build on empty and populated tables for numeric and
  VARCHAR values, NULLs, duplicate secondary entries, unique conflicts,
  constraint violations, close failure, rollback, and subsequent cursor reuse;
- bounded index build retry/resume, reopen mid-build, duplicate-safe replay of a
  batch, publish failure, cleanup after failed build, and maximum table/index
  IDs;
- bounded index/table drop, reopen mid-cleanup, multiple indexes, identity
  sequence removal, catalog scan exhaustion/corruption, and final schema
  publication only after physical deletion;
- positive/negative sequence increments, overflow, exhaustion, reservation
  refill, cache replacement, schema invalidation, identity range, and returned
  durable commit sequence; and
- injected begin/scan/next/close/commit/abort/catalog-fetch/catalog-update
  failures wherever an existing test provider supports them, with the original
  failure winning unless cleanup failure is the current documented terminal
  result.

Tests should extend `RelationalDatabaseTest` for database/catalog/lifecycle
behavior and the existing SQL/JDBC suites for supported boundary behavior.
Add component-level tests for a new owner only when they prove a lifecycle or
ownership invariant that cannot be observed reliably through the real path.

## 7. Performance and allocation contract

- Construct each collaborator once per database. Do not create command objects,
  transaction outcomes, catalog carriers, buffers, cursors, or definitions per
  row or per sequence value.
- Index build and physical cleanup retain bounded reusable rows, cursors,
  key arrays, and direct buffers. Extraction must not add a row copy or an
  encoded catalog copy.
- Sequence cache hits remain synchronized, bounded array operations with no
  transaction/session creation and no allocation.
- Dependency scans and reference probes allocate nothing per catalog or child
  row after warmup.
- Do not use streams, iterators, boxing, varargs, captured lambdas, formatted
  strings, or callback-based transaction framing in these paths.
- Shared scratch must not be retained to save memory if it obscures ownership.
  A small bounded buffer per long-lived collaborator is preferable and should
  be counted explicitly; Q09 should record the database-lifetime direct-memory
  delta.
- Synchronization should move with authoritative state. Do not leave every
  facade method `synchronized` after state moves, and do not add nested locks.
  Establish one lock order: schema gate before a service operation only during
  admission/publication; services never call back while holding an unrelated
  monitor. Characterization tests must prove behavior before reducing lock
  scope.

## 8. Structural exit criteria

Q09 is complete only when:

- `RelationalDatabase` owns only the embedded aggregate and the justified
  concrete collaborators; it has no catalog row/buffer/cursor, table-definition scratch,
  index-build state, cleanup arrays, sequence cache arrays, schema owner, or
  active-transaction counter;
- `RelationalSession` does not invoke catalog, sequence, index, table-lifecycle,
  dependency, transaction-admission, or schema-publication behavior through
  `RelationalDatabase`;
- `RelationalSchemaGate` is the sole owner of schema version and admission
  state and the internal table-definition ownership token, and has no
  dependency on catalog codecs or DDL services;
- `RelationalSequenceService` is the sole owner of sequence reservation cache
  and arithmetic;
- `RelationalIndexLifecycle` is the sole owner of index-build/drop state and
  both build entry paths share row validation and typed insertion semantics;
- `RelationalPhysicalCleanup` is the sole implementation of bounded physical
  table deletion;
- `RelationalCatalogDependencies` owns schema-dependency cursors,
  session-owned `RelationalReferentialIntegrity` owns row-constraint cursors,
  and neither performs catalog mutation;
- no mutable buffer, cursor, result carrier, array, or table-definition scratch
  is aliased between components, and no generic shared context bag replaces the
  original class;
- no new public/internal interface, subpackage visibility widening,
  compatibility adapter, catalog/row/WAL format change, SQL feature, or access
  method is introduced by the refactor;
- current public APIs, catalog bytes, transaction/isolation behavior,
  schema-version effects, retry/resume behavior, and `StatusCode` precedence
  remain unchanged; and
- focused tests, `RelationalDatabaseTest`, affected relational/SQL/JDBC tests,
  allocation tests, the `river-engine` suite, and independent correctness,
  architecture, and performance/allocation reviews pass.

Line counts are diagnostic only. Promotion depends on this ownership graph and
the absence of cycles and ambient mutable state, not on an arbitrary maximum
class size.

## 9. Sequencing with current work

- Q09 establishes each relevant relational authority before the first U02,
  O08, or E04 slice that would otherwise deepen its current shared catalog,
  type, maintenance, or index-lifecycle ownership. Independent semantic
  fixtures and type-contract work need not wait.
- Q09 does not reopen U00. SQL session execution continues to consume the
  existing public `RelationalDatabase`/`RelationalSession` behavior while the
  internal relational graph changes.
- Q09 does not break up `RelationalSession` generally. It removes its callbacks
  into the database facade and prevents database responsibilities from moving
  into it. A later session decomposition should be justified from its own
  transaction, catalog-resolution, row/index, and cursor ownership map.
- K16's `IndexedTableStore` remains the durable aggregate below this work. Q09
  must not reach into page, B+tree, MVCC, WAL, recovery, or checkpoint internals.

## 10. Focused build loop

Use one Gradle build at a time, starting with the narrowest current owner:

```sh
./gradlew :river-engine:compileJava
./gradlew :river-engine:test \
  --tests io.riverdb.engine.relational.RelationalDatabaseTest
./gradlew :river-engine:test \
  --tests io.riverdb.engine.sql.SqlSessionTest
./gradlew :river-engine:test \
  --tests io.riverdb.engine.sql.SqlSessionAllocationTest
```

Run targeted methods during each slice, then the affected `river-engine` suite
at slice gates. Reserve `./verify` and clean-checkout verification for Q09g.
