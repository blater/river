# River High-Level Architecture and Delivery Plan

<!-- markdownlint-disable MD013 -->

Status: Reviewed draft; architecture review Cycle 1 and companion-plan integration reviews complete

Audience: River contributors, reviewers, and maintainers
Scope: Single-node River engine through operational beta, with staged full-replica replication

## 1. Purpose

River is a new relational database implemented in Java. It is influenced by the efficient storage, indexing, logging, recovery, and concurrency ideas found in reference projects & research literature without copying or attempting a line-by-line translation or preserving the legacy physical file format.

The initial product target is:

> A high-performance, single-node Java OLTP database with efficient analytical scans, SQL and JDBC access, strong crash recovery, modern MVCC, and minimal operating-system dependencies.

The first implementation remains a concrete local WAL. Its durable lineage,
append, force, recovery, and commit behavior is proved directly. Consensus,
state sync, and failover derive the smallest shared journal boundary from that
evidence when the first replicated provider is implemented.

## 2. Architectural principles

1. **Deliver useful function first.** Working storage, recovery, transactions, and queries outrank infrastructure, observability, documentation, and review activity. Supporting work requires an immediate kernel consumer and stops when that consumer is unblocked.
2. **Correctness precedes breadth.** Crash recovery, transaction isolation, and page invariants are developed before broad SQL compatibility.
3. **Performance is continuously measured.** Allocation rate, tail latency, I/O amplification, WAL cost, cache efficiency, and recovery time are release criteria.
4. **Portable correctness, optional native acceleration.** The reference implementation uses standard Java APIs. OS-specific I/O is provided only through optional adapters.
5. **Version every durable format.** Data pages, catalog records, WAL records, wire messages, and backups carry explicit format versions.
6. **Separate locks from latches.** Transaction locks protect logical objects; short-lived latches protect in-memory structures; MVCC controls visibility.
7. **Bound asynchronous work.** Queues and ring buffers are bounded and expose backpressure. Unbounded queues are not permitted in kernel paths.
8. **Use ownership instead of copying.** Buffers have explicit lifetimes and owners. Zero-copy is applied only when ownership and lifetime remain understandable.
9. **Keep dependencies replaceable.** Agrona, Aeron, Chronicle, consensus libraries, native I/O, parser generators, and telemetry libraries sit behind River-owned interfaces.
10. **No compatibility by accident.** Supported legacy db behavior is selected and documented. Unsupported legacy features fail clearly.
11. **Observe implemented behavior.** Page, WAL, lock, transaction, checkpoint, and recovery diagnostics are added only with the production path that consumes or emits them; observability is not a parallel product track.
12. **Require logical equivalence, scope physical identity.** Replicas must agree on SQL-visible rows, indexes, catalog, constraints, and MVCC outcomes. Byte-identical layout is required only within an explicitly identified immutable checkpoint or format that promises it.
13. **Protect correctness-critical progress.** Journal, recovery, checkpoint publication, and later consensus retain reserved CPU, memory, queue, and I/O capacity under SQL or maintenance overload.

The
[River Engineering Personas and Performance Charter](river-engineering-personas-and-performance-charter.md)
defines the contributor/reviewer mix, two-space style, trust-boundary policy,
status and diagnostic model, and measurable low-GC/zero-copy objectives used to
apply these principles.

### 2.1 TigerBeetle adoption boundary

The detailed
[River and TigerBeetle comparison](river-tigerbeetle-comparison-and-recommendations.md)
is incorporated as design rationale, not as a requirement to reproduce
TigerBeetle. River adopts the mechanisms that generalize to relational data:

- Bounded ordered journals, batching, pipelined replication/persistence/apply,
  checksums, state synchronization, incremental maintenance, backpressure, and
  deterministic simulation.
- Durable consensus as the first and default distributed commit contract.
- A staged path from physiological WAL to an optional canonical resolved-effect
  envelope, without asking followers to re-run SQL planning, nondeterministic
  expressions, or constraint decisions.
- Copy-on-write checkpoint roots and checkpoint-scoped block repair as later
  storage options after WAL replication and failover are correct.

River explicitly does not inherit a fixed operation vocabulary, one global
sequential executor, fixed-size relational records, mandatory byte-identical
replicas, or an LSM-only storage model. Concurrent MVCC, arbitrary synchronous
indexes, interactive transactions, SQL, and logical replica equivalence remain
product requirements.

## 3. Scope

### 3.1 River v1 engine scope

- Single-node databases and transactions.
- Heap tables and B+tree primary/secondary indexes.
- Versioned pages with checksums and lineage-qualified page WAL tokens.
- Write-ahead logging, group commit, checkpoints, redo, undo, and CLRs.
- MVCC for read committed and repeatable-read snapshots.
- Row/key locks, intention locks, schema locks, deadlock detection, and bounded escalation.
- Serializable transactions using key-range locking initially.
- SQL DDL, queries, joins, aggregation, DML, constraints, views, transactions, savepoints, and `EXPLAIN`.
- JDBC 4.3 driver, SQL terminal, administration CLI, backup/restore, verification, and migration tooling.
- Authentication, roles, grants, TLS, metrics, structured logs, and JFR events.

### 3.2 Deferred engine scope

- Hash indexes for equality-heavy workloads and BRIN indexes for large,
  physically correlated tables, each subject to its own workload benchmark and
  recovery/MVCC gate.
- Parallel query and intra-query exchange operators.
- Stored procedures, triggers, `MERGE`, advanced window processing, and specialized types.
- Change-data-capture connectors, R2DBC, ODBC, or PostgreSQL wire compatibility.
- Consensus, automatic failover, follower reads, sharding, and distributed transactions. Their shared journal contracts are derived with the first production replication consumer.
- Online index construction, online table rewrite, and index-ordered `CLUSTER`.

### 3.4 Legacy evidence informing River

see ['legacy-references.md'](legacy-references.md)

## 4. System context

```text
JDBC / SQL CLI / Admin CLI / Migration Tool
                    |
             River wire protocol
                    |
          server, authentication, sessions
                    |
       parser -> binder -> planner -> execution
                                      |
                        transactions and access methods
                                      |
                          buffer cache and page layer
                                      |
                           portable platform I/O
                                      |
                         data files, WAL, and backups

Transaction threads -> concrete LocalWal -> WAL writer -> local durability
Dirty page producers -> bounded flush queue -> page writers/checkpoints
Journal entries      -> future consensus/full-replica provider in a later phase
```

Ring buffers are used only at asynchronous ownership boundaries. Operators inside a query communicate through direct batch-oriented calls unless a measured parallel execution design requires an exchange queue.

## 5. Repository and build structure

The proposed Java namespace is `io.riverdb`. It is provisional until a project domain is selected. Published package names must not change casually after the first public release.

River should use a Gradle multi-module build. Modules are architectural boundaries; packages organize responsibilities inside a boundary.

```text
river/
  river-base/
  river-observability-api/
  river-platform/
  river-format/
  river-tx-api/
  river-wal/
  river-buffer/
  river-storage/
  river-tx/
  river-recovery/
  river-backup/
  river-catalog/
  river-sql/
  river-planner/
  river-exec/
  river-engine-api/
  river-engine/
  river-protocol/
  river-client/
  river-server/
  river-jdbc/
  river-cli/
  river-admin/
  river-inspect/
  river-migration/
  river-observability/
```

### 5.1 Dependency direction

The following table is the authoritative dependency direction. "Depends on" lists
the principal River modules and omits `river-base` for brevity.

| Module | May depend on |
| --- | --- |
| `river-observability-api` | None |
| `river-platform` | `river-observability-api` |
| `river-format` | None |
| `river-tx-api` | None |
| `river-wal` | `river-platform`, `river-format`, `river-observability-api` |
| `river-buffer` | `river-platform`, `river-format`, `river-observability-api` |
| `river-storage` | `river-format`, `river-buffer`, `river-tx-api`, `river-observability-api` |
| `river-tx` | `river-tx-api`, `river-observability-api` |
| `river-recovery` | `river-wal`, `river-buffer`, `river-storage`, `river-tx`, `river-tx-api` |
| `river-backup` | `river-platform`, `river-format`, `river-wal`, `river-buffer`, `river-storage`, `river-recovery` |
| `river-catalog` | `river-storage`, `river-tx-api`, `river-observability-api` |
| `river-sql` | `river-catalog` |
| `river-planner` | `river-sql`, `river-catalog`, exported storage capabilities |
| `river-exec` | `river-planner`, `river-storage`, `river-tx-api`, `river-catalog`, `river-observability-api` |
| `river-engine-api` | Stable value contracts from `river-base` only |
| `river-engine` | Kernel, catalog, SQL, planner, execution, recovery, backup, and `river-engine-api` modules |
| `river-protocol` | `river-engine-api` value contracts only |
| `river-client` | `river-protocol`, `river-engine-api` |
| `river-server` | `river-protocol`, `river-engine-api`, `river-engine` |
| `river-jdbc` | `river-client` |
| `river-cli` | `river-client` |
| `river-admin` | `river-client`, `river-engine-api`, `river-backup`, and admin protocol contracts |
| `river-inspect` | `river-platform`, `river-format` and read-only WAL decoders |
| `river-migration` | `river-client` plus source-driver APIs |
| `river-observability` | `river-observability-api` and selected exporters/JFR |

`river-tx-api` is a real Gradle module rather than a package inside
`river-tx`. It contains immutable transaction/snapshot contracts and the
storage port implemented by `river-storage`. `river-tx` calls that injected
port and does not depend on the storage implementation. This breaks the
storage/transaction cycle.

`river-wal` owns append, force, scan, segment, and retention mechanics only.
`river-recovery` coordinates WAL, transaction, buffer, and storage recovery.
Likewise, `river-backup` owns backup correctness; command-line modules merely
call its services.

Concrete `river-wal` owns current ordered publication, force, scanning, and
retention behavior. The first replicated provider must compose with this owner
without making transaction, storage, or buffer code reinterpret `force()`.
Its minimal shared seam and future module dependencies are derived and frozen
at R24, when both real implementations and their fault evidence exist.

### 5.2 Dependency rules

- The architecture map records intended future boundaries; it does not require
  empty modules to participate in the active Gradle graph.
- A module is included and dependency-wired only in the delivery slice that
  adds its first production code. Its dependencies must be justified by that
  code's current compile-time or runtime needs.
- Infrastructure and observability modules require an immediate production
  kernel consumer. A hypothetical later consumer is insufficient.
- `river-base` has no River dependencies.
- `river-tx-api`, `river-engine-api`, and
  `river-observability-api` are small,
  deliberately stable boundaries rather than convenience modules.
- Durable format codecs do not depend on the buffer cache, transaction manager, SQL, or server.
- `river-wal` does not coordinate buffers, transactions, storage, catalog, or
  SQL. Recovery handlers are registered and invoked by `river-recovery`.
- Transaction commit code uses the concrete LocalWal commit/force path; any
  later provider-neutral durability port is introduced with R24.
- SQL and planner code cannot access page buffers or file I/O directly.
- Execution operators use storage and transaction interfaces, not implementation classes.
- Client modules cannot depend on kernel implementation packages.
- No module exports an `internal` package.
- Cyclic Gradle module dependencies are prohibited.

### 5.3 API and compatibility policy

| Surface | Policy before 1.0 | Policy after 1.0 |
| --- | --- | --- |
| `river-engine-api` | Source compatibility is best effort | Semantic and source compatibility; binary compatibility within a major release |
| Native wire protocol | Explicit negotiation; incompatible changes allowed | Versioned compatibility window documented per release |
| JDBC API | Supported-method matrix evolves | JDBC behavior and supported methods follow compatibility policy |
| `*.spi` packages | Experimental and provider-version checked | Compatibility only where explicitly marked public SPI |
| Durable formats | Upgrade tools required from first persisted prototype | Published read/write compatibility and upgrade window |
| All other packages | Internal | No compatibility promise |

Only packages explicitly documented as API or SPI are exported from their
modules. Package names containing `.internal` are never exported or referenced
by other modules.

## 6. Module, package, and class responsibilities

The following classes are architectural roles, not a frozen class inventory. Names can change through review, but each responsibility must have a clear owner.

### `river-base`

Purpose: dependency-free value types, identifiers, errors, cancellation, and small utilities shared across River.

```text
io.riverdb.base
io.riverdb.base.id
io.riverdb.base.error
io.riverdb.base.concurrent
io.riverdb.base.collection
```

| Class or interface | Responsibility |
| --- | --- |
| `DatabaseId`, `TablespaceId` | Stable identifiers for durable database containers. |
| `RelationId`, `IndexId`, `ColumnId` | Compact typed catalog identifiers; prevent accidental ID interchange. |
| `PageId` | Identifies file/tablespace, page number, and allocation generation without exposing byte offsets. |
| `RowId` | Stable page/slot plus generation or row-directory identity used by heaps and indexes. |
| `TransactionId` | Monotonic transaction identity; not reused within a database lifetime. |
| `CommitSequence` | Orders committed visibility independently of physical WAL position. |
| `Lsn` | Typed offset inside one WAL generation; it is never compared or persisted without explicit lineage. |
| `WalGeneration`, `WalRecordPointer`, `WalRecordRange` | Qualify record start/exclusive end with database incarnation and WAL generation so rollover/restart cannot alias offsets. |
| `DurableWalEnd`, `PageWalToken` | Same-lineage forced exclusive end and durable page comparison token; bare offsets cannot prove write-ahead ordering. |
| `DatabaseIncarnation` | Durable identity changed on restore/reseed so positions from different histories cannot be confused. |
| `NodeIncarnation` | Journal-owned process/storage-node lifetime identity changed on restart and used later to fence replica acknowledgements. |
| `JournalPosition` | Logical `(databaseIncarnation, generation, sequence)` identity independent of a file byte offset or replica layout. |
| `CheckpointId` | Identity of one completed recovery-checkpoint boundary; it does not imply that mutable in-place data pages form a transferable immutable block set. |
| `BackupManifestId` | Identity of one complete manifest-last backup artifact, distinct from a recovery checkpoint. |
| `CheckpointManifestId` | Future immutable snapshot identity derived from database incarnation, covered journal position, format generation, and complete manifest/root digest. |
| `RequestId`, `IdempotencyKey` | Stable client operation identities used to resolve retries and unknown commit outcomes. |
| `StatusCode` | Stable non-allocating engine outcome used for expected failure, retry, cancellation, validation, I/O, corruption, and resource conditions. |
| `StatusDetail` | Optional bounded detail written into caller-owned/reused storage; not allocated for every hot-path failure. |
| `RiverException` | Cold/public-boundary adapter used only where a Java API requires an exception; carries a stable status and SQLSTATE where applicable. |
| `CancellationToken` | Cooperative cancellation checked at bounded execution and wait points. |
| `CloseGuard` | Detects invalid use after close for owned resources in debug/test configurations. |

Rules:

- IDs are immutable value types, preferably records where representation allows.
- `river-base` must not become a miscellaneous dumping ground.
- No database behavior, I/O, logging, or SQL types belong here.

### `river-observability-api`

Purpose: a dependency-light, no-op-capable instrumentation boundary that hot
kernel modules may call without depending on a telemetry backend.

```text
io.riverdb.observability.api
io.riverdb.observability.api.metric
io.riverdb.observability.api.event
```

| Class or interface | Responsibility |
| --- | --- |
| `MetricsSink` | Creates bounded-cardinality counters, gauges, and histograms. |
| `KernelEventSink` | Receives versioned structured diagnostic events without blocking producers. |
| `DiagnosticSink` | Emits fixed-field `DEBUG`, `INFO`, `WARN`, `ERROR`, and `FATAL` events without using exceptions or formatted strings as engine control flow. |
| `NoOpObservability` | Allocation-free reference used when instrumentation is disabled. |
| `MetricName`, `EventTypeId` | Stable, versioned identities independent of a chosen exporter. |
| `DiagnosticContext` | Bounded correlation fields with central sensitive-value policy. |

Metric labels must never contain SQL text, literals, usernames, table names,
transaction IDs, or other unbounded values. Audit events do not use this
best-effort interface; they have a separate security and durability contract.

### `river-platform`

Purpose: isolate River from operating-system, filesystem, clock, memory-allocation, and process-control details.

```text
io.riverdb.platform
io.riverdb.platform.file
io.riverdb.platform.memory
io.riverdb.platform.process
io.riverdb.platform.nio
io.riverdb.platform.nativeio        // optional, later
```

| Class or interface | Responsibility |
| --- | --- |
| `PlatformServices` | Immutable bundle of selected platform providers and detected capabilities. |
| `DurableFile` | Positional read/write, size, truncate, allocate, and `force` operations with documented durability semantics. |
| `DurableDirectory` | Directory creation, listing, rename, replacement, removal, and parent-directory synchronization; durable-record owners compose their local installation protocol from these operations. |
| `FileLockProvider` | Installation/database process-exclusion locks without leaking OS-specific handles. |
| `MappedRegionProvider` | Optional bounded mappings with explicit close and force behavior. |
| `MemoryAllocator` | Allocates aligned heap or native regions with explicit ownership. |
| `WallClock` | Audit timestamps and human-facing time. |
| `ProcessControl` | Process identity, shutdown hooks, and optional signal integration. |
| `PlatformCapabilities` | Reports supported atomic writes, page size, mapping, direct I/O, and force behavior. |
| `NioDurableDirectory` | Portable reference implementation using Java NIO/FileChannel. |
| Native durable-directory adapter | Optional OS-specific accelerator, introduced only with a production consumer. |

Rules:

- Standard Java is the correctness reference.
- Native providers must pass the same crash and durability contract tests.
- Content and metadata force semantics are distinct and explicit. Creation,
  replacement, rename, and deletion protocols include directory durability.
- Database logic never branches directly on `os.name`.
- The support matrix names tested JVM/filesystem combinations. River does not
  claim identical power-loss semantics on every filesystem merely because an
  API call succeeds.

### `river-format`

Purpose: define and encode every durable binary format without owning runtime policy.

```text
io.riverdb.format
io.riverdb.format.page
io.riverdb.format.row
io.riverdb.format.wal
io.riverdb.format.catalog
io.riverdb.format.backup
io.riverdb.format.checksum
```

| Class or interface | Responsibility |
| --- | --- |
| `FormatVersion` | Comparable major/minor format identity and compatibility checks. |
| `FormatConstants` | Durable magic values, byte order, alignment, and bounded sizes. |
| `PageHeader` | Logical view of page type, page ID/generation, lineage-qualified `PageWalToken`, checksum, flags, and free-space metadata. |
| `PageHeaderCodec` | Reads/writes page headers at fixed offsets with validation. |
| `PageType` | Enumerates durable page roles such as heap, B+tree, free-space, catalog, and overflow. |
| `SlotDirectoryCodec` | Encodes row slots and validates bounds/overlap invariants. |
| `TupleHeaderCodec` | Encodes MVCC metadata, null map, variable offsets, and optional undo pointer. |
| `JournalPositionCodec` | Encodes database incarnation, journal generation, and logical sequence independently of local byte layout. |
| `WalRecordHeader` | Common record length, type, version, logical journal position, transaction ID, lineage-qualified predecessor pointer, and checksum. |
| `WalRecordCodec` | Version-aware encode/decode contract for WAL record payloads. |
| `FullPageImageRecordCodec` | Provisional first-dirty-after-checkpoint image used when selected torn-page policy requires it. |
| `CatalogRecordCodec` | Durable catalog bootstrap record encoding independent of SQL objects. |
| `BackupManifestCodec` | Backup and immutable checkpoint identity, covered journal position/LSN, included files, checksums, and compatibility data. |
| `DatabaseControlRecordCodec` | Redundant database incarnation, file inventory, checkpoint pointer, and format state. |
| `DataFileHeaderCodec` | File ID, database incarnation, page size, generation, and creation metadata. |
| `WalSegmentHeaderCodec` | Segment sequence/incarnation, start LSN, block framing, and sealed state. |
| `WalBlockCodec` | Checksummed blocks, record fragmentation, padding, and valid-tail discovery. |
| `ResolvedEffectEnvelopeCodec` | Provisional future logical envelope for resolved row/index/catalog effects and nondeterministic results; not the Phase 1 WAL or initial R2 payload. |
| `ChecksumAlgorithm` | Pluggable checksum selected by durable format version. |
| `FormatVerifier` | Offline structural validation without starting the database engine. |

Rules:

- Codecs operate on bounded byte regions and never perform I/O.
- Every decode validates lengths before reading fields.
- Durable structures use a fixed canonical byte order.
- Runtime objects are not serialized using Java serialization.
- Changing offsets or meanings requires a format-version decision and upgrade plan.
- `ResolvedEffectEnvelopeCodec` is implemented only after its replication/CDC
  ADR and cross-version equivalence tests. Listing the role now reserves a clean
  format owner; it does not freeze an envelope in the first durable format.

### `river-tx-api`

Purpose: immutable transaction, snapshot, visibility, and storage-port contracts
shared by storage, transaction implementation, execution, and recovery without
introducing module cycles.

```text
io.riverdb.tx.api
io.riverdb.tx.api.lock
io.riverdb.tx.api.version
io.riverdb.tx.spi
```

| Class or interface | Responsibility |
| --- | --- |
| `TransactionContext` | Immutable operation-facing transaction ID, isolation, snapshot, and cancellation view. |
| `IsolationLevel` | River-supported isolation contract independent of JDBC constants. |
| `Snapshot` | Visibility boundary containing commit high-water and required active outcomes. |
| `TransactionOutcome` | In-progress, committed-at-CSN, aborted, or internally indeterminate result. |
| `Visibility` | Storage-facing tuple/index visibility contract. |
| `LockService` | Logical row/key/range/schema lock contract used by access methods. |
| `LockToken` | Scoped proof that the requested logical protection is held. |
| `VersionPointer` | Durable address of a prior row or index version. |
| `VersionRecord` | Durable prior-image/version-chain entry used for snapshot reconstruction. |
| `TransactionStorage` | Port for version append/read, rollback application, vacuum, and status persistence. |
| `RecoveryTransactionView` | Minimal transaction state exported to checkpoint and recovery orchestration. |

Contracts in this module do not expose heap pages, B+tree pages, buffer frames,
or transaction-manager implementation classes.

### `river-wal`

Purpose: append, force, scan, segment, and retain the concrete local write-ahead
log. Checkpoint and restart orchestration remain in
`river-recovery`.

```text
io.riverdb.wal
io.riverdb.wal.append
io.riverdb.wal.record
io.riverdb.wal.segment
io.riverdb.wal.checkpoint
io.riverdb.wal.spi
```

| Class or interface | Responsibility |
| --- | --- |
| `WalManager` | Concrete lifecycle owner for append, force, scan, segment, and retention mechanics. |
| `WalAppender` | Implements ordered journal reservation/publication without waiting for I/O under page latches. |
| `WalReservation` | Ordered bounded log-buffer range whose start assigns a lineage-qualified `WalRecordPointer`. |
| `WalBuffer` | Bounded concurrent ordered-byte reservation/publication buffer with explicit WAL generation. |
| `WalAppendRequest` | Owned record bytes plus transaction-chain metadata and force requirement. |
| `WalWriter` | Sole owner of physical file writes, segment rollover, draining published ranges, and exclusive `DurableWalEnd` publication. |
| `WalForceTicket` | Waitable force target used only after page latches have been released. |
| `WalFatalCauseReporter` | Proposes append/force integrity failure to the one database/engine `FatalStateFence`; it owns no second failure state. |
| `DurableWalEndTracker` | Monotonic publication and waiting for a forced exclusive end inside one database/WAL lineage. |
| `GroupCommitCoordinator` | Forms commit batches under latency and throughput bounds. |
| `WalSegmentManager` | Creates, names, validates, seals, and removes WAL segments. |
| `WalReader` | Forward/backward validated traversal across WAL segments. |
| `WalRetentionPolicy` | Computes the minimum retained lineage-qualified range required by current concrete recovery and backup consumers. |
| `LocalWalLeaseHandle` | Concrete LocalWal retention handle; any later shared lease contract is derived with its first replicated consumer. |
| `WalJournalPositionMapping` | Maps logical positions to local LSN ranges and validates incarnation/generation on recovery. |
| `CheckpointRecordWriter` | Encodes/appends begin and chunked end-checkpoint records supplied by recovery orchestration. |
| `CompensationLogRecord` | Records completed undo so recovery can safely resume after another crash. |

Critical invariants:

- A successful `LOCAL_DURABLE` commit is not returned before a same-lineage
  `DurableWalEnd` covers the commit record's exclusive end; the transaction
  layer observes that fact through the journal durability contract.
- A page image is not written before a `DurableWalEnd` with identical database
  incarnation/WAL generation covers its `PageWalToken.recordEndLsn`.
- A mutator reserves WAL capacity before taking a page latch. Reservation,
  publication, and LSN assignment under the latch cannot wait for the writer,
  force, disk I/O, or transaction locks.
- Abandoned or stalled reservations have a specified fail-stop/recovery policy;
  they cannot leave an undetectable hole in the log stream.
- WAL record publication is ordered and each submitted buffer has one owner.
- Segment deletion never crosses the minimum lineage-qualified retention range.
- A torn final active-segment record is truncated to the last validated block;
  corruption inside a sealed segment is fatal and requires restore/repair.

### `river-buffer`

Purpose: cache fixed-size database pages, coordinate page loading and flushing, and provide safe latch-scoped access.

```text
io.riverdb.buffer
io.riverdb.buffer.frame
io.riverdb.buffer.latch
io.riverdb.buffer.replace
io.riverdb.buffer.flush
io.riverdb.buffer.prefetch
```

| Class or interface | Responsibility |
| --- | --- |
| `BufferPool` | Resolves `PageId` to cached frames and enforces capacity and pinning rules. |
| `BufferFrame` | Internal page bytes, identity, state, pin count, dirty state, lineage-qualified `PageWalToken`, and latch. |
| `FrameState` | Loading, valid, flushing, evicting, failed, and reusable state machine. |
| `WritebackEpoch` | Detects a page dirtied again while an older flush image is in flight. |
| `PageHandle` | Scoped read/write access; releasing it unpins and releases the appropriate latch. |
| `PageLoader` | Coordinates single-flight reads when multiple callers miss the same page. |
| `PageLatch` | Short-lived shared/exclusive protection for an in-memory page. |
| `ReplacementPolicy` | Selects unpinned victims without containing I/O logic. |
| `ClockSweepPolicy` | Initial low-overhead replacement policy and reference implementation. |
| `DirtyPageTracker` | Tracks earliest uncovered recovery token, FPI/checkpoint pins, forced stable-page token, redirty/writeback epochs, and candidates for checkpoint/recovery. |
| `PageFlushScheduler` | Selects dirty pages without violating WAL-before-data. |
| `PageWriter` | Distinguishes full write completion from data-file force and reports only exact forced page tokens as stable. |
| `PageFlushImage` | Immutable checksummed capture plus identity, generation, complete `PageWalToken`, and writeback epoch; capture stability is not media stability. |
| `PagePrefetcher` | Issues bounded sequential/range prefetch requests. |
| `BufferPoolMetrics` | Hit, miss, wait, eviction, dirty age, and flush latency observations. |

Rules:

- A `PageHandle` must not escape its lexical ownership scope.
- Buffer bytes are inaccessible after a handle is closed.
- Blocking file I/O is never performed while holding unrelated frame-table locks.
- Transaction waits are never performed while holding page latches.
- Page flush captures an immutable image while latched, waits for a
  same-lineage WAL durable end, writes without the latch, then forces the data
  file. It clears dirty state only after force and only if frame identity,
  generation, epoch, and `PageWalToken` still match; an older in-flight write
  cannot clear a redirty or release its DPT/FPI/redo pins.
- Short I/O, checksum failure, WAL-force failure, and page-write failure cause a
  defined database failed/read-only transition; background retries are bounded.
- Replacement policy is measurable and replaceable.

### `river-tx`

Purpose: transaction lifecycle implementation, commit publication, snapshots,
MVCC visibility, rollback, logical locks, deadlock detection, transaction status,
and vacuum coordination.

```text
io.riverdb.tx.core
io.riverdb.tx.mvcc
io.riverdb.tx.undo
io.riverdb.tx.lock
io.riverdb.tx.deadlock
io.riverdb.tx.vacuum
```

| Class or interface | Responsibility |
| --- | --- |
| `TransactionManager` | Begins, commits, aborts, and tracks active transactions. |
| `Transaction` | Scoped transaction context, isolation level, snapshot, lineage-qualified last-record pointer, state, and resources. |
| `TransactionState` | Enforces active, committing, committed, aborting, aborted, and internal failed/indeterminate transitions. |
| `CommitCoordinator` | Orders CSN allocation, journal publication, durability wait, local visibility publication, acknowledgement, and lock release. |
| `CommitSequenceAllocator` | Allocates CSNs in the same total order encoded by journaled commit records. |
| `CommitPublicationBarrier` | Publishes committed outcomes in order and synchronizes snapshot acquisition. |
| `SnapshotManager` | Creates statement/transaction snapshots and tracks the oldest required snapshot. |
| `CommitTable` | Resolves transaction IDs to in-progress, committed sequence, or aborted status. |
| `TransactionStatusStore` | Persists/freeze-compacts old outcomes so the in-memory commit table is bounded. |
| `IdempotencyOutcomeStore` | Durably resolves an idempotency key to its transaction, decision, CSN, and durability receipt under a bounded retention policy. |
| `VisibilityService` | Decides whether a tuple version is visible to a snapshot. |
| `RollbackManager` | Follows per-transaction WAL chains, applies undo, and emits redo-only CLRs. |
| `Savepoint` | Name, transaction last/undo `WalRecordPointer`, and statement-atomicity marker. |
| `LockManager` | Acquires, converts, releases, and times out logical locks. |
| `LockKey` | Typed table, row, key, range, schema, or database resource identity. |
| `LockMode` | Compatibility and conversion rules for intention/shared/update/exclusive modes. |
| `LockTable` | Sharded resource-to-grants/waiters structure with bounded memory accounting. |
| `DeadlockDetector` | Finds wait-for cycles and selects a deterministic victim. |
| `WaitForGraph` | Snapshot/traversal view of blockers without exposing mutable lock internals. |
| `RangeLockPolicy` | Maps key/gap predicates into resources handled by the common lock table and wait graph. |
| `SchemaLockManager` | Coordinates DDL, prepared plans, catalog versions, and running statements. |
| `VacuumCoordinator` | Advances safe reclamation horizons and distinguishes dead, reclaimable, reusable, and filesystem-releasable space. |
| `VacuumWorker` | Performs bounded relation/index cleanup and page-local compaction without starving foreground work. |

Commit protocol:

1. Transition the transaction from active to committing; cancellation can no
   longer turn it into an abort.
2. Allocate a CSN in the single commit-journal ordering domain.
3. Append a commit decision containing `(transactionId, CSN, idempotencyKey)`
   and obtain its logical journal position and local `WalRecordRange` mapping.
4. Wait through `DurabilityCoordinator` for the requested supported contract.
   The Phase 1 local provider satisfies only `LOCAL_DURABLE` when a
   same-lineage `DurableWalEnd` covers the commit range's exclusive end.
5. Apply/publish the committed outcome through the ordered publication barrier
   so local read-your-writes and snapshot visibility cover the decision.
6. Release locks and acknowledge the satisfied durability contract, position,
   CSN, and outcome finality.

Snapshot acquisition synchronizes with step 5 so a commit cannot appear on the
wrong side of a snapshot boundary. A local force failure after commit append
produces an unknown client outcome and places a single-node database in a
fail-stop/quiescing state; River must not convert that transaction to aborted.
A later replicated provider may resolve the outcome after failover, but only by
consulting the idempotency/outcome record and the journal's committed prefix.

Isolation plan:

- Read committed receives a new snapshot for each statement.
- Repeatable read receives a transaction-lifetime snapshot.
- Serializable initially adds key-range locking to repeatable-read visibility.
- Serializable uses strict two-phase locking: predicate/key-range locks remain
  through transaction end. Equality misses lock the gap; range scans lock
  covered and terminal gaps; heap/full scans use a table/predicate fallback.
- Writers retain row/key locks through commit or abort.
- Lock escalation is a memory-safety mechanism, not the normal access path.
- Range locks share the lock manager's compatibility graph and wait queues.
- Repeatable-read writers use first-updater-wins; read-committed updates recheck
  their predicate/version after waiting.
- The locking ADR defines schema/table/key-row acquisition order, latch order,
  conversion fairness, cancellation/timeout races, and starvation policy.
  Access methods may locate optimistically, release latches, wait for the logical
  lock, then relatch and validate/restart. Latch deadlocks are prevented through
  ordering/restart and never delegated to transaction deadlock detection.
- Escalation requests the table conversion while fine-grained locks remain held;
  they release only after the table grant, so no protection gap is introduced.

Abort and savepoint rules:

- Every DML statement begins with an implicit savepoint so partial statement
  failure is atomic.
- Rollback follows lineage-qualified WAL predecessor/CLR undo-next pointers;
  durable version chains serve snapshot reconstruction but are not the rollback
  driver.
- A deadlock victim is removed from its wait, rolls back while retaining its
  granted locks, and releases them only after reaching aborted state.
- Crash during rollback resumes from CLRs. Explicit abort acknowledgement need
  not force an abort-end record, but the chosen durability contract is recorded
  in the transaction ADR.

Vacuum maintains a visibility horizon separate from WAL retention. Durable
version records mean ordinary snapshots do not pin WAL. Long snapshots are
governed by configured age/space limits with observable warn, reject, or cancel
policy; otherwise version growth cannot be bounded.

Reclamation has three explicit levels:

1. Routine vacuum removes dead heap versions and tombstoned index entries once
   no snapshot, rollback, recovery, backup, or replication consumer can need
   them. It compacts payload bytes within a slotted page without changing live
   slot identities, and returns completely empty pages to pending reuse.
2. The allocator promotes pending pages/extents to reusable only after pin,
   writeback, WAL, backup, and generation/ABA safety conditions pass. A free
   trailing extent may then be durably removed from allocation metadata and the
   data file truncated. Interior free extents remain available for later writes.
3. `VACUUM FULL` rewrites a fragmented heap and all of its indexes into a new
   relation generation; `REINDEX` rebuilds selected indexes. The first
   implementation is offline under a schema-exclusive lock. Publication is an
   atomic catalog/root switch, so crash recovery selects the complete old or
   complete new generation. Old files are reclaimed only after all leases and
   recovery horizons release them.

Routine vacuum guarantees bounded reuse under sustained insert/delete churn;
it does not promise dense physical ordering or B+tree page merges. Full rewrite
is the fragmentation and file-shrink path. It preflights temporary-space
headroom, is cancellable before publication, reports progress, and fails closed
on disk-full or corruption. Online rewrite is a separately gated post-v1
capability.

The maintenance SQL intentionally follows PostgreSQL's familiar command names:
`VACUUM [table_name]`, `VACUUM FULL table_name`, `REINDEX INDEX index_name`,
and `REINDEX TABLE table_name`. `VACUUM FULL` promises a dense rewrite and
filesystem-space reclamation, not index-key ordering. The name `CLUSTER
table_name USING index_name` is reserved for a future operation that physically
orders a heap by an index; it is not an alias for `VACUUM FULL`. Concurrent
variants are rejected until their separately gated online implementations
exist.

### `river-storage`

Purpose: tablespaces, page allocation, heap tables, tuple encoding, large values, and index access methods.

```text
io.riverdb.storage
io.riverdb.storage.space
io.riverdb.storage.heap
io.riverdb.storage.row
io.riverdb.storage.index
io.riverdb.storage.btree
io.riverdb.storage.hash
io.riverdb.storage.brin
io.riverdb.storage.bulk
io.riverdb.storage.largevalue
io.riverdb.storage.recovery
```

| Class or interface | Responsibility |
| --- | --- |
| `StorageEngine` | Opens relations/indexes and coordinates storage lifecycle without SQL concepts. |
| `TablespaceManager` | Maps durable tablespace IDs to data files and allocation metadata. |
| `DataFile` | File identity/incarnation, page addressing, generation, and validated header lifecycle. |
| `PageAddressResolver` | Resolves stable page references to current physical file offsets. |
| `PageAllocator` | Allocates/frees pages transactionally and updates allocation maps. |
| `PendingReuseManager` | Prevents page/slot ABA reuse until WAL, snapshots, cursors, and writeback permit it. |
| `FreeSpaceMap` | Finds heap pages with sufficient free space using approximate, repairable metadata. |
| `ExtentReclaimer` | Promotes horizon-safe empty pages to reusable extents and truncates only contiguous free file tails after durable metadata publication. |
| `RelationHandle` | Scoped access to a physical relation and its format/catalog metadata. |
| `HeapTable` | Insert, fetch, update, delete, and scan tuple versions. |
| `HeapPage` | Validated slotted-page operations over a latched page handle. |
| `HeapCursor` | Snapshot-aware sequential/range heap traversal with prefetch hints. |
| `TupleCodec` | Maps typed execution values to the durable row representation. |
| `TupleVersion` | Header and payload view including creator/deleter identity and undo pointer. |
| `PersistentVersionStore` | Append/read/prune durable version records independently of normal WAL retention. |
| `OverflowStore` | Stores values that cannot fit safely in a heap page. |
| `AccessMethod` | Lifecycle root composed from small capability interfaces rather than one lowest-common-denominator API. |
| `PointLookup`, `EqualityLookup`, `OrderedRangeScan`, `LossyRangePruning`, `MutableIndex`, `BulkBuild` | Independently advertised access-method capabilities. |
| `AccessMethodCapabilities` | Planner-visible ordering, uniqueness, covering, cost, mutation, and bulk-build metadata. |
| `IndexHandle` | Transaction-aware index operations independent of a specific structure. |
| `SearchKey`, `KeyRange` | Encoded key and inclusive/exclusive search boundaries. |
| `KeyCodec` | Sort-preserving encoding for nullable composite typed keys. |
| `BTreeIndex` | B+tree point/range operations and root management. |
| `BTreePage` | Leaf/internal page validation and ordered slot manipulation. |
| `BTreeNavigator` | Root-to-leaf traversal with latch/lock protocol. |
| `BTreeSplitOperation` | One split, parent propagation, root publication, and corresponding WAL records. |
| `StructuralModificationOperation` | Redo-only nested/system transaction for splits, root publication, and allocation changes. |
| `BTreeBulkLoader` | Sorted high-fill construction with bounded memory and spill. |
| `HashIndex` | Collision-safe equality lookup over bounded bucket and overflow storage; it advertises no ordering or range capability. |
| `BrinIndex` | Lossy block-range summaries that return candidate heap ranges and require executor predicate recheck. |
| `BrinSummaryCodec` | Typed min/max summaries initially for ordered scalar keys, with conservative NULL and all-values markers. |
| `RelationRewriter` | Builds a compact replacement heap and its indexes in a new generation for atomic offline publication. |
| `IndexRebuilder` | Reconstructs one index from visible base rows and atomically replaces its physical generation. |
| `UniqueKeyGuard` | Coordinates uniqueness checks with transaction key locks. |
| `StorageRecoveryHandlers` | Redo/undo implementations for heap, allocation, overflow, and the enabled index access methods. |

Initial access-method policy:

- Heap and B+tree are production requirements.
- Hash is a post-v1 plugin for `=`, `IN`, and equijoin probes only. It must beat
  B+tree exact-match performance on a named workload after collision checks,
  overflow behavior, WAL amplification, vacuum, and resize costs are included.
- BRIN is a post-v1 plugin for large append-heavy or physically correlated heap
  tables. Its first operator class records typed min/max summaries per fixed
  page range for `BIGINT`, `DECIMAL`, `DATE`, `TIME`, and timestamp keys.
  Comparisons and `BETWEEN` may use it only to reject impossible ranges; every
  candidate tuple is rechecked by the ordinary typed predicate.
- A BRIN summary may be stale only in the conservative direction: inserts and
  key updates synchronously widen or invalidate the affected summary before it
  can exclude rows, while vacuum or explicit summarization may tighten it.
  BRIN never enforces uniqueness and does not satisfy `ORDER BY`, `MIN`, or
  `MAX` without a separate sort/aggregate path.
- The catalog records an access-method ID and versioned options. SQL exposes
  `CREATE INDEX ... USING HASH ...` and `CREATE INDEX ... USING BRIN ...` only
  after the corresponding method passes its independent gate; omitted
  `USING` continues to select B+tree.
- The access-method interface must not expose B+tree assumptions to the planner.

Page and row identities include allocation generations. A page reference is
conceptually `(fileId, pageNumber, allocationGeneration)`. A row identity adds
slot and slot generation, or uses a stable row-directory indirection selected
by ADR. Updates, forwarding, vacuum, page reuse, and index entries may not
silently create ABA identity reuse.

Phase 1 K08 entries are deliberately single-version and prove structural
navigation/split/recovery only. Phase 2 T04 makes B+tree entries MVCC-aware:
updating an indexed key retains/tombstones the old key entry until the
visibility horizon, creates the new entry, and rechecks heap/version visibility
during lookup. Unique checks then inspect committed and conflicting in-progress
versions under a canonical unique-key/next-key lock. Vacuum cleans compatible
heap and index versions together.

B+tree splits and root propagation are redo-only structural system operations.
Aborting the user transaction removes its logical key change but does not undo
a completed split. The initial tree uses high keys/right links and defers merge
unless the B+tree ADR proves a safe concurrent merge protocol.

The durable-format ADR selects a provisional full-page image on the first page
modification in each checkpoint epoch. Fuzzy checkpoints carry DPT, FPI, redo,
stable-page, redirty, and in-flight flush state; they release no pin until an
exact data-file-forced page or validated base-plus-complete-forced-suffix proof
exists and every transaction/backup lease also releases. Full write completion
is not media stability. If P09 selects a double-write area instead, it replaces
the mechanism explicitly. A checksum failure cannot trust the damaged page's
token for ordinary redo comparison.

### `river-recovery`

Purpose: orchestrate fuzzy checkpoints and restart recovery across WAL,
transactions, buffer state, storage handlers, and the database control record.

```text
io.riverdb.recovery
io.riverdb.recovery.checkpoint
io.riverdb.recovery.restart
io.riverdb.recovery.handler
io.riverdb.recovery.control
```

| Class or interface | Responsibility |
| --- | --- |
| `CheckpointCoordinator` | Begins a fuzzy checkpoint and collects participant snapshots without stopping transactions. |
| `CheckpointSnapshot` | Checkpoint ID, transaction table, dirty-page table, commit high-water, journal coverage, and participant format versions. |
| `CheckpointParticipant` | Supplies bounded versioned state from transaction, buffer, storage, and future replication services. |
| `MasterRecordStore` | Redundant atomic pointer to the last forced complete checkpoint and database incarnation. |
| `CommittedJournalView` | Supplies the valid incarnation and highest journal-committed prefix; local mode derives it from validated WAL and a future replicated provider supplies consensus proof. |
| `RecoveryManager` | Runs analysis, redo, undo, publication, and recovery-complete state transitions. |
| `AnalysisPass` | Rebuilds transaction outcomes, dirty-page earliest recovery tokens, FPI/stable/in-flight pins, and recovery work from checkpoint plus WAL. |
| `RedoPass` | Repeats history idempotently from the minimum lineage-qualified dirty-page recovery token. |
| `UndoPass` | Follows loser transaction chains and emits CLRs/end records. |
| `RecoveryHandlerRegistry` | Maps versioned record types to storage/index redo and undo handlers. |
| `RecoveryFailurePolicy` | Classifies torn tail, sealed-WAL corruption, damaged page, missing file, and incompatible format. |

A complete fuzzy checkpoint contains transaction entries including status and
lineage-qualified last/undo pointers; dirty-page entries including earliest
recovery token, FPI/checkpoint pins, stable-page token, redirty epoch, and
in-flight flush; and commit/transaction-ID high-water plus logical journal
position. The end checkpoint may span records. It becomes current only after
all records are forced and the redundant master pointer is installed durably.
Dirty-page flushing is not required to complete a fuzzy checkpoint and
checkpoint publication alone releases no page/WAL recovery pin.

Recovery never treats every physically present frame as chosen history. Local
mode's `CommittedJournalView` accepts the validated durable WAL lineage; a
replicated implementation also fences obsolete epochs and excludes an
uncommitted/conflicting suffix before analysis. SQL visibility is rebuilt from
transaction decision records, not inferred from journal commitment alone.

WAL truncation uses the minimum of dirty-page redo, loser/active transaction
undo, backup leases, and replication/logical-decoding leases. Persistent MVCC
versions do not pin WAL for ordinary readers.

Startup accepts a torn final active-WAL record only by truncating to the last
validated boundary. Interior or sealed-segment corruption is fatal. A damaged
data page is restored through the selected torn-page mechanism or recovery
fails explicitly. Startup validates touched pages and control structures; a
full database/index validation belongs to the offline/online verify service.

### `river-backup`

Purpose: own online backup correctness, WAL-retention leases, manifest
finalization, restore planning, and backup/restore engine APIs.

```text
io.riverdb.backup
io.riverdb.backup.manifest
io.riverdb.backup.source
io.riverdb.backup.restore
io.riverdb.backup.spi
```

| Class or interface | Responsibility |
| --- | --- |
| `BackupCoordinator` | Starts, monitors, cancels, and finalizes a consistent backup. |
| `BackupSession` | Durable operation identity, boundaries, progress, and retention lease. |
| `BackupSource`, `BackupSink` | Page-safe/file-safe data transfer independent of destination technology. |
| `ManifestFinalizer` | Forces copied objects and atomically installs the complete backup manifest. |
| `RestorePlanner` | Validates manifest, target emptiness, compatibility, required WAL, and recovery steps. |
| `RestoreExecutor` | Restores offline into a new directory, verifies all bytes, then invokes recovery. |

An online backup computes the earliest redo/transaction predecessor token from
a validated checkpoint and persists one semantic `WalRetentionLease` before
copying. It captures mutable pages as immutable checked images carrying complete
lineage-qualified page tokens. After the final capture it appends and forces an
end marker, copies and forces the complete start-to-end WAL interval plus every
named predecessor/undo record, then writes/forces/atomically installs and
reopens the manifest last. Every copied page token is in the manifest lineage
and no newer than the fenced end. Only then may the lease release. The manifest
includes database incarnation, format versions, control/checkpoint/transaction
state, file identities/generations/lengths, exact WAL ranges, and checksums.
Point-in-time and incremental backup remain deferred until the base algorithm
is proven. CLI commands are clients of this service and contain no backup
correctness logic.

### `river-catalog`

Purpose: transactional metadata, bootstrap catalogs, descriptors, schema versions, statistics, and DDL coordination.

```text
io.riverdb.catalog
io.riverdb.catalog.bootstrap
io.riverdb.catalog.descriptor
io.riverdb.catalog.cache
io.riverdb.catalog.ddl
io.riverdb.catalog.stats
io.riverdb.catalog.security
io.riverdb.catalog.dependency
io.riverdb.catalog.sequence
```

| Class or interface | Responsibility |
| --- | --- |
| `CatalogService` | Transactional lookup and mutation of database metadata. |
| `CatalogTransaction` | Transaction-local metadata overlay and staged catalog change set. |
| `CatalogChangeSet` | Ordered create/alter/drop changes published atomically at commit. |
| `CatalogBootstrap` | Creates the minimum self-describing system catalogs. |
| `CatalogSnapshot` | Immutable catalog view tied to a schema/catalog version. |
| `CatalogCache` | Version-aware descriptor cache with explicit invalidation. |
| `DatabaseDescriptor` | Database identity, owner, defaults, format state, and security policy. |
| `SchemaDescriptor` | Schema name, owner, privileges, and contained objects. |
| `RelationDescriptor` | Table identity, columns, physical relation, constraints, and indexes. |
| `ColumnDescriptor` | Name, ordinal, type, nullability, default, and collation. |
| `IndexDescriptor` | Access method, keys, included columns, uniqueness, and physical identity. |
| `ConstraintDescriptor` | Primary, unique, foreign-key, check, and not-null metadata. |
| `ViewDescriptor` | Bound definition, security mode, dependencies, and invalidation version. |
| `SequenceDescriptor` | Durable sequence/identity configuration and allocation state. |
| `TypeDescriptor`, `CollationDescriptor` | Catalog-visible type/collation identity and compatibility metadata. |
| `StatisticsDescriptor` | Row/page counts, distinct/null estimates, histograms, and freshness. |
| `StatisticsCollector`, `AnalyzeService` | Sampling, collection, publication, versioning, and invalidation policy. |
| `DdlCoordinator` | Applies catalog and physical changes under schema locks. |
| `DdlStateMachine` | Physical prepare, catalog publish, rollback, crash recovery, and orphan cleanup. |
| `CatalogMigration` | Upgrades catalog records between supported format versions. |
| `PrivilegeDescriptor` | Grantor, grantee, object, privilege, and grant-option state. |
| `PrincipalDescriptor`, `RoleDescriptor` | Users/service principals, roles, ownership, and status. |
| `RoleMembership` | Role hierarchy and grant/admin-option relationships. |
| `ObjectDependencyGraph` | View, foreign-key, index, sequence, and DROP dependency/cascade behavior. |

Rules:

- Catalog changes are ordinary recoverable transactions.
- A transaction sees its own staged DDL; other transactions see only catalog
  versions committed before their applicable snapshot/schema epoch.
- Plans bind to a catalog version and are invalidated explicitly.
- Catalog cache overlays publish only at commit and invalidate by object version,
  privilege/role epoch, statistics version, and dependent object graph.
- System catalogs use the same storage primitives wherever bootstrapping permits.
- SQL names do not serve as durable physical identifiers.

V1 includes transactional DDL, sequences/identity, immediate constraints, and
temporary tables with explicit logging/isolation rules. Deferred constraints
and broad cascade actions remain deferred unless promoted by the SQL profile
ADR.

### `river-sql`

Purpose: lexical analysis, parsing, SQL AST, type system, binding, semantic validation, functions, and SQL diagnostics.

```text
io.riverdb.sql
io.riverdb.sql.parser
io.riverdb.sql.ast
io.riverdb.sql.bind
io.riverdb.sql.type
io.riverdb.sql.function
io.riverdb.sql.rewrite
io.riverdb.sql.diagnostic
io.riverdb.sql.profile
```

| Class or interface | Responsibility |
| --- | --- |
| `SqlLexer` | Tokenizes SQL while preserving source spans for diagnostics. |
| `SqlParser` | Produces immutable AST nodes and bounded syntax errors. |
| `SqlStatement`, `SqlExpression` | Sealed AST roots for statements and expressions. |
| `QueryBlock`, `CorrelationScope` | Represents a nested `SELECT` scope and its permitted references to enclosing query blocks. |
| `SourceSpan` | Original text location used by errors and tooling. |
| `Binder` | Resolves names, expands stars, validates scopes, and creates a bound tree. |
| `NameResolver` | Applies database/schema/search-path and correlation rules. |
| `TypeSystem` | SQL type identity, coercion, comparability, precision, and collation rules. |
| `TypeCoercion` | Chooses explicit/implicit conversions and reports lossy conversions. |
| `BoundStatement`, `BoundExpression` | Catalog-resolved, typed input to logical planning. |
| `FunctionSignatureRegistry` | SQL-visible overloads, resolution, determinism, null, and result-type rules. |
| `ConstantFolder` | Evaluates deterministic constant expressions safely. |
| `SqlDiagnostic` | SQLSTATE, source span, hints, and structured context. |
| `SqlSemanticProfile` | Versioned statement/type/identifier/null/numeric/text/time/constraint behavior. |

Rules:

- Parsing performs no catalog or storage access.
- Binding is deterministic for a catalog snapshot and session settings.
- AST and bound nodes are immutable.
- Bound query, DML, DDL, transaction-control, and session-command forms are
  distinct so planner/execution responsibilities stay explicit.
- SQL owns function signatures and overload resolution; execution owns scalar,
  aggregate, and window kernels. SQL never depends on execution.
- legacy db-specific syntax is isolated in an optional compatibility layer, not spread through the core grammar.

The [SQL conformance profile](../compatibility/sql-conformance-profile.md) is a
contract, not a marketing feature list. It
defines identifier folding and quoting; NULL/three-valued logic; numeric
overflow, rounding, division, and decimal precision; character encoding,
collation, and length; date/time/time-zone behavior; implicit casts and
parameter inference; constraint timing; statement atomicity; view security;
stable SQLSTATE behavior; and nested query-block, correlation, scalar-subquery,
and `EXISTS`/`IN` semantics and limits.

#### V1 text, numeric, and temporal profile

M5 replaces the initial `BIGINT`/packed-ASCII `VARCHAR(7)` slice with an
explicit typed-value contract shared by the catalog, row codecs, indexes, WAL,
planner/execution, public API, protocol, and JDBC. Because River is pre-V1,
type IDs and row/catalog formats change directly; the implementation does not
carry adapters for the current experimental format.

The required M5 scalar set is:

| SQL type | V1 bound and durable meaning |
| --- | --- |
| `BIGINT` | Signed 64-bit integer. |
| `BOOLEAN` | `TRUE`, `FALSE`, or `NULL`; no implicit numeric truth values. |
| `DECIMAL(p,s)` | `1 <= p <= 18`, `0 <= s <= p`; signed scaled 64-bit integer with checked exact arithmetic and JDBC `BigDecimal` boundary mapping. |
| `VARCHAR(n)` | `1 <= n <= 255`; strict UTF-8, at most `n` Unicode scalar values and 1,020 encoded bytes per value, subject to the 4 KiB row bound. |
| `DATE` | Proleptic-Gregorian calendar date in years 0001-9999, stored as a signed epoch day. |
| `TIME(p)` | Local wall-clock time with `0 <= p <= 6`, stored as microseconds since midnight. It has no zone. |
| `TIMESTAMP(p)` | Local date and time with `0 <= p <= 6`, stored on a zone-free local microsecond timeline. |
| `TIMESTAMP(p) WITH TIME ZONE` | An instant stored as UTC epoch microseconds. The input zone name is not retained per value. |

Text uses one v1 collation: deterministic, case-sensitive Unicode-code-point
order. River performs no implicit Unicode normalization, case folding, or
locale-sensitive comparison. Declared `VARCHAR` length counts Unicode scalar
values, not UTF-16 code units or encoded bytes. Invalid UTF-8 and unpaired
surrogates are rejected at the boundary. A table definition is rejected when
the worst-case encoded row, including offsets/null state, exceeds the bounded
row format.

Exact decimal execution includes unary sign, `+`, `-`, `*`, `/`, `%`, `ABS`,
`CEIL`, `FLOOR`, `ROUND`, and `TRUNCATE`, plus `SUM`, `AVG`, `MIN`, and `MAX`.
The binder derives one deterministic result precision and scale from the input
descriptors; division and explicit scale reduction use round-half-even.
Intermediate rescaling and multiplication use reusable wide arithmetic so a
representable result is not rejected merely because an intermediate exceeds a
signed `long`. Division by zero is `22012` and an unrepresentable result is
`22003`. JDBC constructs `BigDecimal` at its public boundary; River does not
allocate one per row internally. Comparisons rescale exactly without rounding,
so values such as `1.0` and `1.00` compare equal and produce one grouping,
distinct, uniqueness, and index-key outcome.

Typed expressions and comparisons are context-independent. The same coercion,
comparison, collation, overflow, and three-valued NULL rules apply in
projection, `WHERE`, `JOIN ... ON`, `HAVING`, `CHECK`, `BETWEEN`, `IN`,
correlated predicates, grouping, ordering, distinct elimination, uniqueness,
and index-bound construction. `HAVING` may consume typed aggregate results,
including decimal `SUM`/`AVG` and decimal or temporal `MIN`/`MAX`; it does not
fall back to a separate untyped comparison path.

`BOOLEAN` supports equality, inequality, `IN`, and `IS TRUE`/`IS FALSE`/
`IS UNKNOWN`, but is not ordered and cannot appear in `BETWEEN`, `MIN`, or
`MAX`. `BIGINT`, `DECIMAL`, `VARCHAR`, `DATE`, `TIME`, and both timestamp types
are ordered and support the six comparison operators and `BETWEEN`.

Temporal SQL text is locale-independent and strict:

- `DATE 'YYYY-MM-DD'`;
- `TIME 'HH:MM:SS[.ffffff]'`;
- `TIMESTAMP 'YYYY-MM-DD HH:MM:SS[.ffffff]'` for an unzoned local value;
- `TIMESTAMP WITH TIME ZONE 'YYYY-MM-DD HH:MM:SS[.ffffff]+HH:MM'` for an
  instant with an explicit numeric offset.

Fields use ASCII digits and fixed-width date/time components. Fractional
precision may be 0-6 digits and must fit the target declaration without silent
truncation. River rejects year zero, invalid calendar dates, `24:00:00`, leap
second `:60`, missing offsets for offset-required input, trailing text, and
numeric offsets outside `-14:00` through `+14:00`. Canonical text output uses
the same forms and six fractional digits only when the declared/result
precision requires them.

Each session has a time zone, defaulting to UTC. A session may select UTC, a
fixed numeric offset, or an IANA region ID supplied by the supported JDK tzdb.
Unzoned `DATE`, `TIME`, and `TIMESTAMP` values never inherit the session zone.
Converting a local timestamp to an instant applies the explicitly requested or
session zone: nonexistent DST-gap local times fail, and ambiguous overlap
times fail unless an explicit matching offset disambiguates them. Converting
an instant to local fields is unambiguous and uses the session or requested
zone. The runtime exposes its tzdb version; supported deployment and future
replication configurations must not mix tzdb baselines without an explicit
upgrade gate.

`CURRENT_DATE`, `CURRENT_TIMESTAMP`, `LOCALTIME`, and `LOCALTIMESTAMP` are
stable for one SQL statement. Defaults, generated values, WAL, backup/recovery, and future
replication carry the resolved typed value; they never re-read the clock or
reapply time-zone rules during replay. Comparisons order local temporal types
on their local timeline and zoned timestamps by instant. Implicit comparison
or assignment between zoned and unzoned timestamps is rejected.

The required temporal expression set also includes `LOCALTIME`,
`LOCALTIMESTAMP`, `EXTRACT(YEAR|MONTH|DAY|HOUR|MINUTE|SECOND|TIMEZONE_HOUR|TIMEZONE_MINUTE
FROM value)`, date plus/minus a whole `BIGINT` number of days, date subtraction
returning a `BIGINT` day count, strict text casts, and `AT TIME ZONE`. Equality,
inequality, ordering, `BETWEEN`, index ranges, joins, `MIN`/`MAX`, grouping, and
`DISTINCT` use the same typed comparison kernel. Cross-family temporal
comparison requires an explicit checked cast; in particular, River never
silently applies the session zone to compare a local timestamp with an instant.
SQL-standard `CURRENT_TIME` waits for the deferred `TIME WITH TIME ZONE` type;
M5 `LOCALTIME` supplies the session-local unzoned wall time.

Invalid text uses SQLSTATE `22007`, temporal range overflow uses `22008`, an
invalid time-zone displacement uses `22009`, string overflow uses `22001`, and
numeric overflow uses `22003`. Temporal arithmetic beyond comparisons,
checked casts, date plus/minus whole days, and statement-current values waits
for a separate `INTERVAL` profile.

### `river-planner`

Purpose: logical plans, relational rewrites, statistics, cardinality estimation, costing, physical-plan selection, and plan explanation.

```text
io.riverdb.planner
io.riverdb.planner.logical
io.riverdb.planner.rule
io.riverdb.planner.stats
io.riverdb.planner.cost
io.riverdb.planner.physical
io.riverdb.planner.explain
```

| Class or interface | Responsibility |
| --- | --- |
| `Planner` | Converts a bound statement into an executable physical plan. |
| `LogicalPlan` | Immutable relational algebra tree. |
| `LogicalScan`, `LogicalJoin`, `LogicalAggregate` | Major logical operations without execution choices. |
| `RewriteRule` | Semantics-preserving logical transformation with traceable application. |
| `RuleEngine` | Applies normalization, predicate pushdown, projection pruning, and semantics-preserving decorrelation rules. |
| `StatisticsProvider` | Supplies catalog and runtime statistics through a stable interface. |
| `CardinalityEstimator` | Produces row-count/selectivity estimates with confidence metadata. |
| `CostModel` | Compares CPU, random/sequential I/O, memory, spill, and parallel costs. |
| `PlanEnumerator` | Explores join orders, access paths, and physical algorithms within bounded effort. |
| `PhysicalPlan` | Immutable executable operator specification. |
| `PlanCacheKey` | SQL shape, parameter types, settings, and catalog version identity. |
| `PlanExplainer` | Stable text/JSON description of estimates, choices, and runtime measurements. |

Initial planner policy:

- Begin with explicit rules and a small cost-based join/access-path search.
- Never encode access methods as parser decisions.
- Surface estimate errors in `EXPLAIN ANALYZE` to guide statistics work.
- Bound planning time and retain a safe fallback plan.

### `river-exec`

Purpose: execute physical plans using reusable column/vector batches, manage memory/spill, apply DML, and report runtime statistics.

```text
io.riverdb.exec
io.riverdb.exec.batch
io.riverdb.exec.operator
io.riverdb.exec.join
io.riverdb.exec.aggregate
io.riverdb.exec.sort
io.riverdb.exec.scan
io.riverdb.exec.dml
io.riverdb.exec.memory
io.riverdb.exec.spill
io.riverdb.exec.scheduler
io.riverdb.exec.expression
```

| Class or interface | Responsibility |
| --- | --- |
| `ExecutionContext` | Transaction, parameters, memory budget, cancellation, session settings, and metrics. |
| `Operator` | Open/next-batch/close execution lifecycle. |
| `Batch` | Bounded set of rows with vectors, row count, selection, and ownership. |
| `ValueVector` | Typed column storage with null representation and reusable backing memory. |
| `SelectionVector` | Selected row indexes without copying payload vectors. |
| `TableScanOperator` | Snapshot-aware heap scan with projection and predicate hooks. |
| `IndexScanOperator` | Point/range index traversal followed by visible-row resolution. |
| `FilterOperator`, `ProjectOperator` | Vector expression evaluation and selection/projection. |
| `ExpressionKernel` | Typed vector/scalar semantics used by filters, projections, checks, joins, and aggregates. |
| `NestedLoopJoin`, `HashJoin`, `MergeJoin` | Join implementations selected by physical planning. |
| `HashAggregate`, `SortAggregate` | Grouping and aggregate state with spill support. |
| `SortOperator` | In-memory and external merge sort under a memory budget. |
| `DmlOperator` | Drives an insert/update/delete statement through the write coordinator. |
| `WriteCoordinator` | Enforces statement savepoint, lock ordering, heap/version changes, indexes, and rollback. |
| `ConstraintEnforcer` | Not-null/check/unique/foreign-key validation and immediate timing. |
| `IndexMaintainer` | Ordered index changes and recovery-consistent failure cleanup. |
| `ReturningProjector` | Generated keys and SQL `RETURNING` result construction. |
| `MemoryGovernor` | Per-query and global reservation/revocation accounting. |
| `SpillManager` | Temporary-file lifecycle and bounded spill I/O. |
| `PipelineScheduler` | Initially synchronous; later coordinates bounded parallel pipelines. |
| `RuntimeProfile` | Rows, batches, CPU, waits, I/O, memory, spills, and estimates per operator. |

Rules:

- Hot operators avoid per-row object allocation.
- Batches and vectors have explicit ownership and are reused.
- A batch crossing the engine/client boundary is either detached, copied, or
  reference-counted under explicit acknowledgement. The engine caps retained
  batches and bytes; a slow/abandoned consumer cannot pin unlimited snapshots,
  locks, query memory, spill files, or buffer frames.
- Every potentially long loop checks cancellation at a bounded interval.
- Spill files are checksummed where corruption could produce incorrect results.
- Execution does not contain SQL name-resolution logic.

### `river-engine-api`

Purpose: supported embedded API and stable result/value contracts shared by
embedded users, the server adapter, protocol, and client libraries.

```text
io.riverdb.api
io.riverdb.api.session
io.riverdb.api.statement
io.riverdb.api.result
io.riverdb.api.value
```

| Class or interface | Responsibility |
| --- | --- |
| `EmbeddedDatabase` | Supported open/session/lifecycle surface for embedded use. |
| `DatabaseFactory` | Supported create/open entry point resolved to an engine provider at runtime. |
| `DatabaseSession` | Public session, transaction, prepare, execute, and cancellation API. |
| `Statement`, `PreparedCommand` | SQL command and parameter contracts without implementation types. |
| `QueryHandle` | Cancellation, deadline, progress, and completion identity. |
| `ResultCursor` | Backpressured batches/rows with explicit close and resource-release semantics. |
| `ResultMetadata` | Stable column name/type/nullability/origin information. |
| `SqlValue`, `SqlType` | Public typed values independent of durable tuple and protocol encodings. |
| `CommitResult`, `DurabilityReceipt` | Public transaction/CSN/idempotency identity, satisfied durability tier, epoch/incarnation, and final/unknown outcome without exposing internal journal types. |

The API does not expose buffer handles, page IDs, WAL records, catalog
implementation descriptors, physical plans, or internal transaction objects.

### `river-engine`

Purpose: assemble the kernel, expose embedded APIs, manage database/session lifecycle, prepare and execute statements, and coordinate shutdown.

```text
io.riverdb.engine
io.riverdb.engine.config
io.riverdb.engine.lifecycle
io.riverdb.engine.session
io.riverdb.engine.statement
io.riverdb.engine.security
io.riverdb.engine.admission
io.riverdb.engine.admin
io.riverdb.engine.spi
```

| Class or interface | Responsibility |
| --- | --- |
| `RiverEngine` | Top-level lifecycle and database-open facade. |
| `EngineBuilder` | Validated construction and provider injection for embedded/tests/server use. |
| `RiverConfig` | Immutable typed configuration with source and validation metadata. |
| `ConfigManager` | Precedence, static/reloadable classification, validation, and atomic reload. |
| `SecretProvider` | External secret resolution/rotation without placing secrets in ordinary configuration or logs. |
| `DatabaseInstance` | Owns WAL, buffers, transactions, storage, catalog, and background services for one database. |
| `DatabaseLifecycle` | Create, open, recover, online, quiesce, checkpoint, and close transitions. |
| `SessionManager` | Creates, tracks, cancels, and closes sessions. |
| `Session` | Identity, transaction state, settings, prepared statements, and current request. |
| `SessionState` | Open, transaction-active, failed-transaction, closing, and closed transitions. |
| `SessionSettings` | Search path, role, locale, time zone, collation, optimizer, and timeout settings. |
| `TransactionController` | Autocommit, implicit/explicit transactions, savepoints, commit, abort, and failed state. |
| `StatementExecution` | One statement's lifecycle, deadline, cancellation, savepoint, resources, and result. |
| `CursorManager` | Cursor identity, fetch/close, holdability policy, and disconnect cleanup. |
| `StatementService` | Parse, bind, plan, execute, and return a result stream. |
| `PreparedStatement` | Bound parameter contract plus cacheable plan metadata. |
| `PlanCache` | Bounded version-aware cache with explicit invalidation and metrics. |
| `ResultStream` | Backpressured result-batch and update-count interface. |
| `ResourceAdmissionController` | Connection/query/transaction/CPU/memory/temp/WAL quotas, workload-class admission, and overload rejection. |
| `CriticalProgressReserve` | Protects configured CPU, memory, I/O requests, and queue slots for journal, recovery, checkpoint publication, and later consensus. |
| `TransactionBudget` | Bounds age, WAL/journal bytes, locks, mutations, version space, and temporary/provisional storage for one transaction. |
| `AuthenticationService` | Validates identities through configured credential providers. |
| `CredentialProvider` | Password/token/certificate identity verification, hashing policy, rotation, and secret erasure. |
| `AuthorizationService` | Evaluates catalog privileges for bound operations. |
| `AuditService` | Security-relevant durable/bounded audit events separate from best-effort telemetry. |
| `AdminService` | Privileged checkpoint, backup, verify, vacuum/full-vacuum, reindex, cancel, and configuration operations with operation IDs. |
| `BackgroundServiceManager` | Starts/stops checkpoint, vacuum, flush, statistics, and maintenance workers. |
| `ShutdownCoordinator` | Rejects new work, drains/cancels sessions, checkpoints as configured, and closes safely. |

Initial session semantics:

- One active statement per session; protocol multiplexing is deferred.
- Autocommit owns one implicit transaction per statement. A statement error
  rolls back to its implicit statement savepoint before returning.
- Explicit transaction errors that cannot be contained place the session in a
  failed-transaction state until rollback.
- Prepared commands retain parsed/typed contracts but may rebind/replan when
  catalog, privilege, role, statistics, parameter type, or relevant settings
  versions change.
- Unread results, disconnect, timeout, close, and cancellation have explicit
  cursor cleanup and transaction policy. Commit with open cursors follows the
  declared holdability policy rather than silently leaking a snapshot.

Admission and scheduling rules:

- Foreground SQL, analytical scans, maintenance, backup/state sync, and
  correctness-critical kernel work do not share one unconstrained worker or I/O
  queue.
- SQL admission may consume spare critical capacity opportunistically, but it
  is revocable and cannot prevent journal drain, recovery, or checkpoint-root
  publication from making bounded progress.
- Maintenance is incremental and preemptible at explicit units. Overload tests
  must show progress while new SQL is rejected or cancelled.
- A transaction that exceeds its online mutation budget uses an explicitly
  designed bulk/provisional protocol or fails before it can exhaust journal
  retention; ordinary spill is not silently substituted for durable atomicity.

Measured fast-path candidate, deferred from v1:

| Class or interface | Responsibility |
| --- | --- |
| `TransactionTemplateDefinition` | Versioned parameter/result contract for a bounded sequence of already authorized SQL statements under one server transaction. |
| `TransactionTemplateCompiler` | Converts bound statements into a guarded allocation-bounded execution path without inventing non-SQL semantics. |
| `CompiledTransactionTemplate` | Executes the validated parameter batch, constraints, writes, and commit with fewer dispatch and network boundaries. |
| `TemplateValidityGuard` | Invalidates on schema, privilege/role, collation, function, settings, statistics, or format generations that affect semantics or plans. |
| `ContendedExecutionLane` | Optional bounded keyed FIFO lane for a measured hot transaction template; it reduces lock thrash without serializing unrelated SQL. |

Prepared statements, cached plans, JDBC batching, and group commit are v1
features. Compiled transaction templates enter Phase 5 only when profiling
shows that generic dispatch remains material. Templates use ordinary MVCC,
locks, authorization, constraints, WAL, recovery, audit, and observability;
they are not a second hard-coded command database.
An execution lane is enabled only for an explicit template/contention key with
admission and fairness rules; River never routes all transactions through one
global state-machine thread.

### `river-protocol`

Purpose: versioned native client/server messages, framing, value encoding, flow control, and protocol compatibility.

```text
io.riverdb.protocol
io.riverdb.protocol.frame
io.riverdb.protocol.message
io.riverdb.protocol.value
io.riverdb.protocol.auth
io.riverdb.protocol.flow
```

| Class or interface | Responsibility |
| --- | --- |
| `ProtocolVersion` | Negotiated client/server compatibility level and capabilities. |
| `ProtocolStateMachine` | Legal handshake, authentication, session, statement, cursor, cancel, and close transitions. |
| `FrameCodec` | Length-bounded message framing with request/session identity. |
| `ClientMessage`, `ServerMessage` | Sealed protocol message roots. |
| `HandshakeRequest`, `HandshakeResponse` | Version, capabilities, database, and authentication negotiation. |
| `PrepareRequest`, `ExecuteRequest`, `FetchRequest` | Statement lifecycle messages. |
| `ResultMetadata`, `ResultBatchMessage` | Column metadata and bounded result data. |
| `ProtocolValueCodec` | Stable encoding of SQL values independent of Java serialization. |
| `FlowController` | Credits/windows for result backpressure and bounded server memory. |
| `HandleId` | Versioned session/prepared/cursor handle with stale-handle detection. |
| `LobStreamMessage` | Bounded chunking, cancellation, and lifetime for large values. |
| `ProtocolError` | Stable SQLSTATE/error code, safe message, and optional diagnostics. |

Rules:

- Frames have strict maximum sizes before allocation.
- Authentication secrets are not retained in reusable buffers or diagnostics.
- Remote version/capability negotiation occurs inside a server-authenticated TLS
  channel, is bound into client authentication/session state, and is protected
  from downgrade; insecure remote authentication is not supported.
- Protocol capability negotiation permits additive evolution.
- Compression is optional and negotiated after authentication/security policy.
- V1 requests are ordered with bounded in-flight work and no multiplexed active
  statements per session. Deadlines propagate to the engine, slow clients are
  subject to credit/idle limits, and disconnect closes all server handles.

### `river-client`

Purpose: reusable network client runtime shared by JDBC, SQL CLI, online admin,
migration target, and future language drivers.

```text
io.riverdb.client
io.riverdb.client.transport
io.riverdb.client.auth
io.riverdb.client.session
io.riverdb.client.statement
io.riverdb.client.result
```

| Class or interface | Responsibility |
| --- | --- |
| `RiverClient` | Connection factory, TLS policy, capability negotiation, and shared resources. |
| `ClientConnection` | Transport lifecycle, authenticated session creation, deadlines, and failure state. |
| `ClientSession` | Transaction, prepare/execute/fetch/cancel, and server-handle ownership. |
| `ClientPreparedHandle` | Server prepared identity plus parameter/result contracts and stale handling. |
| `ClientResultCursor` | Credit-based fetch, value decoding, close, and disconnect cleanup. |
| `ClientAuthProvider` | Pluggable credential/token exchange with explicit secret lifetime. |
| `ClientTlsPolicy` | Hostname verification, trust material, minimum protocol, and certificate policy. |
| `ClientRetryPolicy` | Retries only operations proven safe; never guesses an unknown commit outcome. |

The client runtime owns protocol orchestration. JDBC and command-line tools map
their APIs onto it rather than implementing framing, TLS, or session state
independently.

### `river-server`

Purpose: network listeners, TLS, connections, authentication, protocol dispatch, session binding, cancellation, and service lifecycle.

```text
io.riverdb.server
io.riverdb.server.net
io.riverdb.server.connection
io.riverdb.server.auth
io.riverdb.server.dispatch
io.riverdb.server.lifecycle
```

| Class or interface | Responsibility |
| --- | --- |
| `RiverServer` | Starts listeners, owns engine instances, and exposes readiness/liveness state. |
| `ServerConfig` | Listener, TLS, authentication, limits, and engine configuration. |
| `NetworkListener` | Accepts connections through a replaceable transport implementation. |
| `ConnectionHandler` | Handshake, frame decoding, request dispatch, flow control, and close. |
| `SessionEndpoint` | Binds one authenticated protocol connection to an engine session. |
| `RequestDispatcher` | Maps protocol messages to bounded engine operations. |
| `TlsContextProvider` | Loads and rotates certificates without placing TLS concerns in the engine. |
| `RemoteSecurityPolicy` | Requires TLS/auth mechanisms, request limits, safe errors, and downgrade protection. |
| `ConnectionLimiter` | Enforces global/per-principal connection and outstanding-request limits. |
| `QueryCancellationRegistry` | Resolves external cancel requests safely to active statements. |
| `ServerShutdownCoordinator` | Stops accepts, drains work, and coordinates engine shutdown. |

Initial concurrency model:

- A virtual thread may own each mostly-blocked connection/session.
- CPU-heavy query work executes under engine memory and CPU admission control.
- WAL, page flush, checkpoint, and vacuum workers use dedicated platform threads.
- Network result production obeys flow-control credits rather than buffering without limit.
- The first non-loopback server includes TLS, authentication, authorization,
  safe error mapping, audit hooks, and resource limits. These are not postponed
  to a later production-hardening phase.

### `river-jdbc`

Purpose: JDBC 4.3 client API over the River protocol.

```text
io.riverdb.jdbc
io.riverdb.jdbc.connection
io.riverdb.jdbc.statement
io.riverdb.jdbc.result
io.riverdb.jdbc.metadata
io.riverdb.jdbc.datasource
io.riverdb.jdbc.convert
```

| Class or interface | Responsibility |
| --- | --- |
| `RiverDriver` | JDBC URL recognition, property parsing, and connection creation. |
| `RiverConnection` | Transactions, session settings, statements, warnings, and lifecycle. |
| `RiverStatement` | Direct SQL execution, timeouts, cancellation, result sequencing, and batching. |
| `RiverPreparedStatement` | Parameter typing/binding and prepared execution. |
| `RiverResultSet` | Forward result access with bounded fetch and conversion rules. |
| `RiverResultSetMetaData` | Stable column/type/nullability metadata. |
| `RiverDatabaseMetaData` | Catalog capability and metadata queries. |
| `RiverDataSource` | Configurable connection creation for pools and application servers. |
| `JdbcTypeConverter` | SQL-to-Java conversion and precise overflow/truncation behavior. |
| `JdbcExceptionMapper` | Converts protocol errors and SQLSTATEs to appropriate JDBC exceptions. |

The JDBC support matrix covers every interface method and explicitly records
support or the required `SQLFeatureNotSupportedException`. V1 semantics include
savepoints, generated keys, batch partial failures, warnings, network timeout,
LOB streaming limits, result-set holdability, metadata compatibility, and
cancellation races.

Later subpackages may add pooled and XA data sources. XA is not part of the initial transaction kernel.

### `river-cli`

Purpose: interactive SQL terminal and non-interactive script runner.

```text
io.riverdb.cli
io.riverdb.cli.shell
io.riverdb.cli.command
io.riverdb.cli.render
io.riverdb.cli.script
```

| Class or interface | Responsibility |
| --- | --- |
| `RiverSqlMain` | CLI entry point and exit-code contract. |
| `SqlShell` | Interactive read/evaluate/render loop and connection state. |
| `StatementAccumulator` | Correct multiline SQL accumulation without becoming another SQL parser. |
| `MetaCommandRegistry` | `connect`, `describe`, `timing`, `format`, `source`, and transaction commands. |
| `ScriptRunner` | Deterministic script execution, variables, stop/continue policy, and exit status. |
| `ResultRenderer` | Table, vertical, CSV, JSON, and unaligned output. |
| `ShellHistory` | Secure history persistence with controls for sensitive commands. |

### `river-admin`

Purpose: authenticated online administration and thin CLI access to engine
backup, checkpoint, verification, cancellation, and configuration services.

```text
io.riverdb.admin
io.riverdb.admin.command
io.riverdb.admin.backup
io.riverdb.admin.verify
```

| Class or interface | Responsibility |
| --- | --- |
| `RiverAdminMain` | Administrative command dispatch and machine-readable exit codes. |
| `InitDatabaseCommand` | Invokes `DatabaseFactory` creation and reports validated progress/errors. |
| `CheckpointCommand` | Requests and monitors an online checkpoint. |
| `BackupCommand` | Invokes and monitors the engine backup service. |
| `RestoreCommand` | Invokes offline restore into a new target after exclusion/target validation. |
| `VerifyCommand` | Requests bounded online checks with severity and repairability classification. |
| `AdminClient` | Authenticated admin protocol, privilege checks, progress, cancellation, and idempotent operation IDs. |

Online commands contain no backup/checkpoint correctness algorithms. Restore is
offline and delegates to `river-backup` after acquiring target exclusion.

### `river-inspect`

Purpose: read-only offline control/page/WAL/manifest inspection and explicit
repair planning over a closed database or verified cold copy.

```text
io.riverdb.inspect
io.riverdb.inspect.page
io.riverdb.inspect.wal
io.riverdb.inspect.manifest
io.riverdb.inspect.repair
```

| Class or interface | Responsibility |
| --- | --- |
| `OfflineDatabaseReader` | Acquires database exclusion and exposes validated read-only files. |
| `PageInspector` | Decodes page headers, slots, tuples, indexes, and checksums without mutation. |
| `WalInspector` | Dumps and filters validated WAL blocks, records, and transaction chains. |
| `ManifestInspector` | Verifies control/backup manifests, generations, lengths, and checksums. |
| `OfflineVerifier` | Full structural verification with bounded reporting and no implicit repair. |
| `RepairPlanner` | Produces an explicit repair plan; mutation requires a separate confirmed workflow. |

Repair is separated from verification. River never makes an implicit destructive
repair while opening a database, and offline inspection never reads a live
database without an exclusion lock or approved snapshot.

### `river-migration`

Purpose: a low-priority, target-neutral logical migration utility. It assesses,
translates, extracts, loads, validates, and reports a source migration without
embedding any source product's semantics in the River core. It does not block
the M1→M5 kernel, transaction, relational, or boundary delivery path.

Phase one supports exactly one target: an offline logical migration from a
consistent sqlite snapshot through JDBC. That target proves the generic
boundary; it is not the definition of the component and does not promise
direct sqlite file parsing, format compatibility, or online catch-up.

```text
io.riverdb.migration
io.riverdb.migration.source
io.riverdb.migration.schema
io.riverdb.migration.data
io.riverdb.migration.validate
io.riverdb.migration.report
io.riverdb.migration.spi
io.riverdb.migration.target.sqlite
```

| Class or interface | Responsibility |
| --- | --- |
| `MigrationPlanner` | Target-neutral coordinator: inventories an admitted source, creates an ordered plan, and drives resumable load/validation. |
| `MigrationTarget` | Explicitly registered adapter bundle for one source product and version family; supplies its source, translation, and compatibility behavior. No classpath scanning or plugin framework is introduced before a second real target needs it. |
| `SourceDatabase` | Neutral snapshot catalog/data contract consumed by the core migrator. |
| `JdbcSource` | SPI for a source whose metadata and rows are read over JDBC. |
| `FileSource` | SPI for a source whose exported logical files are read from a declared snapshot. |
| `CdcSource` | Future SPI for a source that can provide ordered change events after a snapshot; it is out of phase one. |
| `SchemaTranslator` | Target-supplied translator from admitted source objects/types/constraints to River DDL and load rules. |
| `CompatibilityAnalyzer` | Target-supplied classifier for unsupported features and semantic differences. |
| `SqliteJdbcTarget` | The sole phase-one `MigrationTarget`; provides an `SqliteJdbcSource`, Sqlite schema translation, and Sqlite compatibility analysis. |
| `BulkDataPump` | Streams source rows into River bulk-load APIs with bounded memory. |
| `MigrationCheckpoint` | Persists resumable progress and source/target identities. |
| `DataValidator` | Row counts, key ranges, checksums, and selected query comparisons. |
| `RejectedRowSink` | Durable rejected/dead-letter rows with source position and conversion error. |
| `MigrationReport` | Human and JSON report of conversions, warnings, omissions, and validation. |

The core owns source-independent planning, dependency ordering, bounded load
orchestration, checkpoints, rejection handling, validation, reports, and the
River target connection. An adapter owns source connection/snapshot identity,
catalog and row extraction, source type and feature semantics, schema
translation, and compatibility findings. Target-specific names, SQL dialect,
drivers, exports, and conversion rules must stay in that adapter package.

Phase-one migration is offline logical migration from a consistent source
snapshot. It defines source-agnostic checkpoint/restart and validation behavior;
the Sqlite JDBC adapter additionally defines its supported character encoding,
collation, time-zone, decimal/type conversion, identity reseeding, and legacy
feature classifications. Online CDC catch-up/cutover, direct Sqlite/MySQL/etc file
parsing, and additional source targets are separate future projects.

### `river-observability`

Purpose: concrete exporters, tracing, diagnostic snapshots, system-view
providers, and JFR integration for `river-observability-api`.

```text
io.riverdb.observability
io.riverdb.observability.metric
io.riverdb.observability.event
io.riverdb.observability.jfr
io.riverdb.observability.snapshot
```

| Class or interface | Responsibility |
| --- | --- |
| `MetricRegistryAdapter` | Connects the stable metrics API to a selected exporter. |
| `StructuredEventLogger` | Versioned recovery, lock, checkpoint, and slow-operation events. |
| `RiverJfrEvent` subclasses | Low-overhead JVM-native events for WAL stalls, latch waits, scans, spills, and recovery. |
| `DiagnosticSnapshot` | Point-in-time sessions, transactions, locks, WAL, buffers, and background work. |
| `SystemViewProvider` | Authorization-aware virtual tables for sessions, locks, WAL, buffers, and plans. |
| `SensitiveValueRedactor` | Central rules for excluding SQL literals, credentials, and protected data. |

Telemetry failures must never fail a transaction or block a correctness-critical thread indefinitely.
Metric/event names and schemas are versioned. Diagnostic snapshots are bounded
and privilege checked. Security audit output remains separate from ordinary
structured diagnostics.

| `LegacyResultNormalizer` | Normalizes formatting while preserving values, ordering, row counts, and errors. |
| `BenchmarkDatabase` | Reproducible schemas/data and engine settings for performance suites. |

## 7. Cross-component execution flows

### 7.1 Insert and commit

1. The session obtains or begins a transaction and snapshot.
2. Binder/planner produce a DML physical plan.
3. The DML operator acquires required schema, key, and row locks.
4. Heap/index code prepares record sizes and reserves WAL/buffer/allocation capacity before taking page latches.
5. It latches and validates target pages, publishes the version/change record into the reserved local-WAL range without waiting, obtains the logical journal position and lineage-qualified `PageWalToken`, applies the change, and sets that token/dirty epoch.
6. Page latches are released before force, transaction-lock wait, or backpressure wait.
7. Commit enters committing, allocates a CSN, and appends `(transactionId, CSN, idempotencyKey)` in commit-journal order.
8. The commit coordinator waits for the requested supported durability contract; Phase 1 maps `LOCAL_DURABLE` to local WAL force. It then publishes local visibility in CSN order.
9. Locks are released and success returns the satisfied contract and position. An unresolved durability failure produces an unknown outcome; the provider proposes the cause to the one database/engine fatal fence.

No thread waits for log force while holding a page latch.

### 7.2 Snapshot read

1. The session obtains the statement or transaction snapshot.
2. Planner selects heap or index access based on catalog statistics.
3. The scan latches a page only long enough to obtain a safe tuple/version view.
4. Visibility checks creator/deleter state against the snapshot.
5. Older state is obtained from the version store when required.
6. Visible columns are decoded into reusable execution vectors.
7. Result batches flow to the client under protocol credits.

### 7.3 Checkpoint and page flush

1. The checkpoint coordinator records its begin boundary.
2. Active transaction status and lineage-qualified predecessor/undo pointers, dirty-page earliest recovery tokens, FPI/stable-page/in-flight/redirty state, and commit high-water are captured without stopping transactions.
3. Chunked end-checkpoint records are appended and forced.
4. The redundant master checkpoint pointer is atomically and durably advanced.
5. Page flushing proceeds independently: it captures immutable images, waits for a same-lineage `DurableWalEnd`, writes, forces the data file, and only then records the exact page image stable; redirty retains its newer recovery pins.
6. WAL retention recomputes the minimum of redo, loser undo, backup, replication, and logical-decoding horizons; normal MVCC snapshots use durable versions and do not pin WAL.

### 7.4 Restart recovery

1. Validate redundant control records, database incarnation, journal generation, file generations, and WAL framing; establish the journal-committed prefix and find the last complete forced checkpoint.
2. Analysis rebuilds active transaction and dirty-page state.
3. Redo repeats history only where a validated same-lineage `PageWalToken` shows the operation is absent; a corrupt or foreign token is never compared by offset.
4. Undo rolls back loser transactions, writing CLRs.
5. Recovery rebuilds the commit table/status high-water and sequence allocator before snapshots can open.
6. Touched allocation, heap, B+tree, catalog, and version-store structures are checked; full verification remains an explicit operation.
7. A recovery-complete/checkpoint boundary is made durable before normal service begins.

## 8. Userland deliverables

### 8.1 Initial required tools

- `river sql`: interactive TUI and script runner.
- `river admin init|start|stop|status|checkpoint|backup|restore|verify|vacuum|reindex`;
  `river admin vacuum --full table_name` requests the offline full rewrite.
- JDBC driver with prepared statements, metadata, batching, cancellation, and `DataSource`.
- Offline page/WAL/manifest inspector.
- System views for sessions, statements, transactions, locks, buffers, WAL,
  checkpoints, vacuum/reclamation bytes, maintenance progress, and plans.
- Health/readiness endpoints and machine-readable diagnostics.

### 8.2 Later tools

- Browser-based administration interface built on supported APIs.
- CDC connector management.
- Replica/cluster management.
- Fleet upgrade and backup catalog management.
- Additional client drivers and wire compatibility layers.

## 9. Test and verification strategy

### 9.1 Legacy test reuse

Inventory each existing test as:

- `required`: River promises the behavior.
- `adapt`: behavior is supported but harness/output differs.
- `later`: desirable but not part of the current milestone.
- `unsupported`: intentionally excluded with a documented reason.

Selected `.sep` cases become neutral tests with normalized results and SQLSTATE expectations. They are not copied blindly into the production grammar or error text.

### 9.2 Required new suites

- Codec round-trip and corrupt-input property tests.
- Page invariant and free-space property tests.
- B+tree randomized/model tests, including concurrent split/merge cases.
- WAL append/rollover/retention and group-commit tests.
- Logical journal-position/LSN/CSN mapping across rollover, restart, restore,
  and rejected stale incarnations.
- Concrete LocalWal durability/recovery tests; replicated frontier and
  capability suites are added with the first real replicated provider.
- Replay of the same decided physiological WAL under different follower task
  schedules, proving logical equivalence of tables, authoritative indexes,
  catalog state, constraints, and MVCC visibility rather than page identity.
- Generated identity/sequence, timestamp, volatile-function, collation, and
  returned-result replay tests proving followers consume leader-resolved values
  and never rerun nondeterministic SQL or constraint decisions.
- R5 checkpoint-manifest repair tests: a block is transferable only for the
  expected immutable `CheckpointManifestId`, block identity, format, and
  checksum; otherwise River installs a complete snapshot or rebuilds logically.
- Crash testing at every meaningful log, force, page-write, and checkpoint boundary.
- MVCC visibility and vacuum tests with long-running snapshots.
- Insert/delete churn tests proving dead space becomes reusable without file
  growth, plus page-tail truncation and old/new rewrite-generation crash tests.
- Lock compatibility, conversion, timeout, escalation, deadlock, and starvation tests.
- Isolation history checking.
- SQL parser/binder/planner golden tests.
- JDBC compatibility tests.
- Backup/restore and migration rehearsals.
- Long-duration workload, disk-full, low-memory, and slow-I/O tests.
- Commit-versus-snapshot publication races and unknown commit outcomes.
- Idempotent retry/outcome lookup before append, after append, after durability,
  and after response loss.
- Indexed-key MVCC across update/delete/reinsert, crash, rollback, and vacuum.
- Serializable missing-key, range, terminal-gap, and full-scan phantom tests.
- DDL/catalog cache/privilege invalidation and crash-state tests.
- Protocol malformed-frame fuzzing, downgrade/authentication, stalled-client,
  cursor cleanup, and cross-version fixtures.
- Critical-progress overload tests proving bounded journal/recovery/checkpoint
  progress during maximum scans, backup, maintenance, state sync, and rejected
  SQL admission.

### 9.3 Performance gates

Measure at minimum:

- Indexed lookup/update latency at p50, p95, p99, and p99.9.
- Insert throughput and commit latency by batch and durability mode.
- Sequential/filtered scan bandwidth.
- Join, sort, and aggregation throughput and spill behavior.
- Allocation bytes per statement and returned row.
- WAL bytes, force frequency, group size, and force latency.
- Buffer hit rate, dirty-page age, eviction wait, and write amplification.
- Lock waits, deadlocks, lock memory, and escalation frequency.
- Checkpoint interference and recovery time per GiB of WAL.
- Tail latency during vacuum, backup, and statistics collection.
- Dead/reclaimable/reusable/truncatable bytes, page occupancy, rewrite
  amplification, file bytes returned, and time to reclaim after the safe
  horizon advances.
- Generic prepared SQL versus batched execution and any compiled transaction
  template, with attributed CPU, allocation, journal bytes, index writes, and
  network round trips.

JMH covers codecs, page operations, key encoding, B+tree primitives, vectors, and queues. End-to-end JDBC workloads determine release suitability.

The
[River Performance Review and Benchmark Plan](river-performance-review-and-benchmark-plan.md)
is the authoritative measurement protocol for tools, dedicated runners,
allocation/copy checks, workload generation, external dataset manifests,
statistics, and regression decisions.

Before Phase 1 implementation is accepted, Phase 0 converts these measures into
numeric budgets for the selected hardware/workload: WAL-buffer capacity and
backpressure latency, maximum pins/latches per operation, copies per WAL/page
flush, foreground checkpoint bandwidth, recovery throughput, allocation per
row/batch, and allowed p99 regression. Comparisons use identical durability
settings. V1 exposes one safe durable-commit mode unless an ADR defines another
mode and its externally visible guarantees.

## 10. Delivery phases and exit criteria

Deliverable IDs, hard/contract/gate dependencies, safe parallel work, critical
paths, and integration slices are maintained in the
[River Project Implementation and Dependency Plan](river-project-implementation-plan.md).

### Phase 0: charter, archaeology, and prototypes

Deliver:

- Target workload and measurable performance envelope.
- Accepted engineering/persona charter, deterministic two-space style checks, dependency
  checks, trust-boundary rules, and PR review matrix.
- Feature/test support matrix.
- License and provenance policy.
- ADRs for page size, MVCC representation, WAL/recovery, journal position and
  frontier semantics, I/O, wire protocol, and SQL scope.
- baseline benchmarks on representative hardware.
- Focused prototypes comparing page sizes, I/O mechanisms, WAL queues, and version storage.
- Status/diagnostic, buffer-ownership, allocation-measurement, and copy-count
  primitives proved in one WAL and one vector-execution prototype.
- Owner-local file-fault tests at the `DurableDirectory` boundary; broader
  scheduling and network simulation wait for a production consumer.
- Vocabulary distinguishing logical journal order, SQL visibility, local
  durability, checkpoint coverage, and safe truncation. The provider contract,
  capability negotiation, and idempotent replicated outcomes wait for R24.

Exit when the kernel design has explicit invariants, selected formats, and benchmark-backed initial choices.

### Phase 1: crash-safe storage kernel

Deliver concrete LocalWal behavior, portable I/O, durable control/file/page/WAL
formats, buffer pool, heap pages, B+tree, checkpoints,
torn-page protection, restart recovery, and a
minimal transaction skeleton: transaction IDs, lineage-qualified
`WalRecordPointer` predecessor chains, commit/abort,
loser undo, CLRs, and structural system transactions. Include bootstrap catalog
records, offline inspectors, minimal metrics/events, backup primitives, and
cross-version format fixtures. Give every complete checkpoint an immutable
`CheckpointId`. Implement the R1 frontier model, database/node
incarnations, retention accounting, and durability-wait metrics without
changing the single-node `LOCAL_DURABLE` commit guarantee.

Exit when randomized storage models and crash-recovery matrices pass, initial
performance budgets are met, and no kernel component confuses logical journal
order, SQL-visible CSN, lineage-qualified WAL ranges/durable ends, checkpoint
coverage, forced-page stability, or reclaimability.

### Phase 2: transactions and concurrency

Deliver full transaction lifecycle, commit publication, durable MVCC versions,
row/key/schema/range locks, deadlock detection, serializable behavior, savepoints,
vacuum, status compaction, and transactional catalog behavior behind a minimal
internal command API.

Exit when isolation histories are correct, version/lock growth is bounded, and crash recovery handles active/aborted transactions.

### Phase 3A: embedded SQL vertical slice

Deliver one end-to-end embedded slice: create table, insert, indexed lookup,
scan, transaction control, minimal catalog/binder/planner/vector execution, and
`river-engine-api`.

Exit when the slice works through the supported embedded API and retains Phase
1/2 crash and isolation guarantees.

### Phase 3B: secure client/server and JDBC slice

Deliver native protocol, reusable client runtime, TLS, authentication,
authorization, audit hooks, resource admission/quotas, a constrained JDBC
subset, and protocol/security fault tests.

The first bounded service uses TCP/TLS on loopback, with one configured service
principal and immutable permission mask. SQL-managed roles/grants and
non-loopback policy are deferred until a multi-principal operational consumer
exists. Exit when this remote protocol boundary is secure by default, its
forced audit and resource-lifecycle gates pass, and the JDBC support matrix for
the slice passes.

### Phase 3C: useful SQL and tooling

Deliver broader DDL/DML, joins, aggregation, constraints, views, sequences,
`EXPLAIN`, metadata, SQL CLI, system views, and adapted backend tests.

Exit when the agreed v1 SQL semantic profile works end to end through JDBC and
the SQL CLI.

### Phase 4: operational beta

Deliver production backup/restore, supported offline migration, online and
offline administration, configuration/secrets, packaging, full observability,
verification, upgrade/rollback tooling, and operational hardening.

Exit after repeated migration/backup rehearsals, fault-injection soak tests, and documented format/protocol compatibility policies.

### Phase 5: measured single-node expansion

Consider hash/ISAM-like access methods, parallel query, CDC, additional
protocols, vector/semantic retrieval, and online maintenance only against
explicit workload needs and benchmarks. Vector search follows the
[River Vector and Semantic Search Technical Plan](river-vector-semantic-search-plan.md):
the primary product is exact semantic ranking over a population bounded by
complete relational predicates. `EXACT` is the default; callers may select
`FUZZY` to permit a derived, rebuildable approximate candidate index and choose
its bounded effort and fallback policy. `FUZZY` results carry no absence or
completeness claim and are separately gated. Compiled
transaction templates are also a measured candidate after prepared plans,
batching, and group commit have been profiled. These items are independent of
the replication program and must not be coupled to its storage evolution.

### Replication program: staged after the local operational beta

The companion
[River Replicated Journal, Durability, and Storage Evolution Plan](river-replicated-journal-durability-plan.md)
is incorporated into this delivery plan as follows:

| Companion stage | Overall-plan placement | Outcome |
| --- | --- | --- |
| R0 compatibility contracts | R24 production work | Derive positions, frontiers, acknowledgement semantics, incarnation/idempotency rules, and WAL/consensus mapping from concrete LocalWal and the first replicated provider. |
| R1 explicit single-node frontiers | Phase 1 concrete behavior | Retain and prove the existing LocalWal durability and recovery behavior without a speculative provider interface. |
| R2 durable full-replica journal | Post-Phase 4 replication stage A | Add one consensus group and non-serving full followers, deriving the shared journal boundary in the same slice. The provisional payload is bounded physiological-WAL batches plus transaction decisions; replay must prove logical relational equivalence. |
| R3 state sync and failover | Replication stage B | Add complete-snapshot bootstrap, membership, logical verification/rebuild fallback, rolling restart, and operational failover. Selective immutable-block repair is not assumed for the in-place engine. |
| R4 volatile acknowledgement | Optional replication stage C | Add only if durable-quorum measurements justify its extra failure contract; it remains non-default. |
| R5 copy-on-write checkpoints | Independent storage stage D | May begin after R3 without waiting for optional R4, but proceeds only after its own complete-manifest, format, recovery, and workload gate. Adds checkpoint-manifest-scoped block transfer/repair; replication does not require an LSM or copy-on-write rewrite. |
| R6 follower reads | Replication stage E | Add stale-snapshot reads first; linearizable reads require a separately proved protocol. |

This split is deliberate: build and prove the current local journal first, then
derive the shared contract while adding consensus and state synchronization.
River does not build two unrelated journals, and it
does not make cluster failure modes part of the Phase 1 storage-kernel proof.

The pre-R2 ADR evaluates a maintained Raft implementation as the reference
baseline because consensus is infrastructure rather than River's relational
differentiator. It is selected only if River can control durable
acknowledgements, snapshots, membership changes, batching, deterministic fault
tests, storage formats, and mixed-version behavior through River-owned ports.
A custom Raft/VSR implementation or flexible-quorum extension requires a
specific demonstrated limitation, a reviewed proof, and comparative tests.

Bounded physiological WAL batches remain the first replication payload. A
canonical resolved-effect envelope is a later compatibility/CDC/logical-repair
boundary and must capture resolved generated/nondeterministic values, base and
authoritative-index mutations, catalog effects, transaction decision, CSN,
idempotency, and result identity. Followers never rerun SQL planning or
constraint decisions from that envelope.

## 11. Architecture review cycle

Every material subsystem passes the following review cycle before its public API or durable format is frozen.

### Review A: proposal

- Responsibilities and non-responsibilities are explicit.
- API ownership and dependency direction are shown.
- State machines and durable representations are described.
- Alternatives and rejected options are recorded.

### Review B: correctness and failure

- Invariants are executable as assertions or tests where possible.
- Crash points, partial operations, retries, and idempotence are enumerated.
- Lock/latch ordering and wait behavior are documented.
- Corruption, disk-full, cancellation, and shutdown behavior are specified.

### Review C: performance and resources

- Expected hot paths and allocation behavior are identified.
- Memory, queue, lock, WAL, spill, and disk usage are bounded.
- A benchmark and profiling plan exists.
- Simpler reference and optimized implementations can be compared where valuable.

### Review D: operations and compatibility

- Metrics, events, diagnostics, inspection, backup, and upgrade implications are covered.
- Format/protocol compatibility and migration behavior are defined.
- Security and sensitive-data handling are reviewed.

### Review E: implementation readiness

- Interfaces are minimal and justified by production consumers.
- Material failures are tested at real production boundaries without product-side test hooks.
- ADRs are accepted and unresolved decisions have named owners.
- Exit criteria are automated in CI where practical.

Reviews should produce changes to this plan, focused ADRs, and checklists. A review is not complete merely because code compiles.

## 12. Initial ADR backlog

1. River license
2. Primary workload: OLTP-only versus OLTP with meaningful analytical scans.
3. JDK baseline, supported JVMs/filesystems, force/directory-sync semantics, and database control-file installation.
4. Canonical page size and whether mixed page sizes will ever be supported.
5. Page checksum and torn-page protection: full-page images versus double-write.
6. FileChannel versus mapped `MemorySegment` roles.
7. Journal API, logical position/LSN/CSN mapping, contiguous frontier semantics,
   local durability provider, WAL reservation/LSN assignment,
   record/block/segment format, group commit, force-failure state, and
   checkpoint protocol.
8. MVCC tuple/index headers, durable version store, rollback WAL chains, commit protocol/status compaction, and vacuum policy.
9. B+tree concurrency algorithm and split/merge policy.
10. Serializable implementation: key-range 2PL initially versus SSI.
11. SQL grammar/parser and the versioned SQL semantic/conformance profile.
12. Public embedded API, native River protocol versus an ecosystem protocol, and reusable client boundary.
13. Catalog bootstrap and catalog upgrade mechanism.
14. Backup consistency, incremental backup, and WAL retention contract.
15. Native acceleration/plugin loading and support boundaries.
16. Status and diagnostic event API, exception-boundary policy, fatal-state
    transitions, sensitive-value policy, and durable audit separation.
17. Session, autocommit, failed-transaction, prepared-plan, cursor, cancellation, timeout, and disconnect state machines.
18. Authentication/authorization threat model, credential storage, TLS/channel policy, quotas, and audit durability.
19. Result batch/vector ownership across engine, protocol, JDBC, and slow clients.
20. Online administration control plane, operation identity, privilege model, and offline exclusion policy.
21. Transactional DDL/catalog overlay, dependencies, cache invalidation, statistics, and orphan cleanup.
22. Multi-database deployment model, global resource-budget ownership,
   workload classes, critical progress reserves, and overload scheduling.
23. Replicated-journal operation granularity, consensus protocol, quorum and
   reconfiguration proof, durability tiers, incarnation fencing, idempotent
   outcome lookup, deterministic simulation, and state synchronization. Evaluate
   a maintained Raft core as the baseline; custom consensus requires a proved
   need. Only the compatibility contracts required by Phases 0/1 freeze before
   the post-beta implementation.
24. Canonical resolved transaction-effect envelope, nondeterministic value/result
   capture, cross-version semantics, and uses for later replication, CDC,
   logical repair, and storage migration. This does not block initial WAL
   replication.
25. Recovery-checkpoint identity versus immutable `CheckpointManifestId`,
   manifest/root hashing, R5 checkpoint-scoped block transfer, logical
   verification/index rebuild, and full-snapshot repair fallback.
   Byte-identical live replicas are not an initial requirement.

## 13. Immediate next work

1. Approve the single-node product target and explicit exclusions.
2. Decide license/provenance constraints before translating legacy algorithms or tests.
3. Create the feature/test support matrix from `tests/`.
4. Write the page-format, journal/WAL/recovery, and MVCC/locking ADRs, including
   the Phase 0 replication-compatibility subset.
5. Build a small benchmark harness and add owner-local fault tests at real production boundaries.
6. Establish the engineering charter tooling and prove the status, diagnostics,
   ownership, allocation, and copy-budget patterns in focused prototypes.
7. Prototype a recoverable heap plus B+tree through an internal transaction API.
8. Add SQL/JDBC only after the crash-safe kernel meets its first correctness and performance gates.

The first engineering milestone is not a parser. It is a recoverable concurrent table containing heap rows, a B+tree, transaction versions, and enough inspection tooling to explain every durable byte and recovery decision.

## 14. Review record

### Cycle 1: high-level architecture review, 2026-08-09

Three independent focused reviews examined the initial proposal:

| Review | Focus | Principal outcomes incorporated |
| --- | --- | --- |
| Storage/recovery | I/O, formats, WAL, buffers, heap/B+tree, checkpoint, recovery, backup | Moved recovery/checkpoint above WAL; added filesystem durability, WAL reservation, control records, torn-page strategy, frame state, page generations, structural system transactions, backup service, and corruption policy. |
| Transactions/concurrency | Commit ordering, MVCC, locks/latches, deadlocks, serializable, vacuum | Added real `river-tx-api`, durable CSN commit protocol, version-versus-WAL separation, indexed-key MVCC, strict range locking, rollback/savepoint rules, horizons, and nonblocking under-latch WAL publication. |
| SQL/product surface | Catalog, SQL semantics, planner/execution, engine, protocol, JDBC, tools, security | Added engine/client APIs, transactional catalog model, SQL semantic profile, session/cursor state machines, expression/write coordination, result ownership, secure first server, admin/inspect separation, and finer delivery slices. |

The initial draft did not pass review unchanged. The module DAG, commit protocol,
checkpoint ownership, network security milestone, and tooling ownership were
revised as a direct result.

### Replicated-journal integration review, 2026-08-09

The companion plan was checked against the module DAG, local commit protocol,
checkpoint/recovery ownership, and delivery order. The review preserves logical
positions independent of local LSNs and explicit journal-commit versus
SQL-visibility concepts, but subsequent implementation evidence showed that a
provider module without a production consumer was premature. R1 stays concrete
LocalWal work; the shared R0 seam is now derived with R24. R2/R3 consensus and
failover remain post-operational-beta stages. Copy-on-write/LSM evolution
remains an independent measured decision.

### TigerBeetle comparison integration review, 2026-08-09

The comparison was accepted as rationale with a deliberate adoption boundary.
The plan now adds logical rather than universal physical replica equivalence,
immutable checkpoint identities and scoped block repair, bounded WAL-batch
replication, a later resolved-effect envelope, critical kernel progress
reserves, deterministic cluster simulation, and compiled relational transaction
templates as a measured fast path. A maintained Raft core is the preferred
baseline to evaluate, not a protocol selected without the R0 ADR. TigerBeetle's
fixed operation vocabulary, global sequential executor, mandatory physical
determinism, and assumed LSM layout remain rejected for River.

### Required Cycle 2 reviews

After the first ADR drafts, repeat focused reviews on:

1. Durable page/control/WAL formats, logical journal-position mapping,
   contiguous frontier proof, and torn-page proof.
2. Commit/snapshot linearization, rollback/version chains, and status freezing.
3. B+tree concurrency, indexed MVCC, range-lock correctness, and vacuum.
4. SQL semantic profile, transactional DDL, constraints, and statement atomicity.
5. Session/protocol/JDBC state machines, TLS/auth threat model, and slow-client resource release.
6. Backup/restore, corruption handling, upgrades, and operating procedures.
7. Deterministic cluster simulation, critical-progress overload behavior,
   checkpoint repair identity, and WAL-replay logical equivalence.

Cycle 2 findings must update the ADRs, this high-level plan where boundaries
change, executable fault tests, and milestone exit criteria before implementation
interfaces or durable formats are frozen.
