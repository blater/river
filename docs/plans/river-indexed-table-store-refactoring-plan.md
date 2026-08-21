# River Indexed-Table Store Ownership Refactoring Plan

<!-- markdownlint-disable MD013 -->

Status: Completed 2026-08-12

Deliverable: K16

Audience: River storage/recovery, transaction, runtime/performance, and architecture reviewers

Related plans:

- [River Project Implementation and Dependency Plan](river-project-implementation-plan.md)
- [River Engineering Personas and Performance Charter](river-engineering-personas-and-performance-charter.md)
- [River Performance Review and Benchmark Plan](river-performance-review-and-benchmark-plan.md)

## 1. Purpose

`IndexedPageStore` is the current single owner of recoverable heap and B+tree
page state, but it also owns logical insert and mutation semantics, MVCC row
metadata, compact and page-image WAL formats, group commit, recovery, vacuum,
checkpoint file lifecycle, and mutable page buffers. `IndexedTable` separately
owns overlapping B+tree traversal, validation, split, and mutation behavior.

K16 restores one authoritative owner for each invariant before River adds more
indexed-storage, row-format, recovery, or MVCC behavior. This is an ordered
structural refactor. It preserves the working recoverable indexed-table path
and its bounded, allocation-conscious implementation while removing duplicate
sources of truth and making buffer and operation lifetimes explicit.

K16 is independent of U00. U00 owns SQL-session decomposition; K16 owns the
indexed-table storage, WAL, recovery, and page-lifetime boundary. Neither
deliverable is a sub-slice of the other.

### 1.1 Priority decision

K16 ran as River's immediate priority alongside the already-underway U00
decomposition. K16a-K16g and U00 used separate ownership lanes, and U00 was not
paused. K16 completed on 2026-08-12, satisfying its temporary precedence over
the other planned feature and refactoring lanes.

## 2. Entry finding

The refactor is required because responsibility and authoritative behavior are
split, not merely because `IndexedPageStore` is large:

- `IndexedPageStore` owns page frames, dirty tracking, file I/O, WAL encoding,
  WAL publication, logical replay, heap placement, B+tree lookup and mutation,
  MVCC predecessor/deletion state, group-commit preparation, vacuum, checkpoint
  creation, checkpoint repair, and recovery.
- `IndexedTable` independently owns B+tree traversal, splits, mutation
  validation, structural validation, and the page-image fallback path.
- mutation codes, fixed page identities, tree-height bounds, uniqueness rules,
  leaf-capacity rules, and MVCC predecessor checks are duplicated across those
  classes.
- page accessors expose mutable `ByteBuffer` instances across the
  `engine.page`/`engine.table` package boundary. The API does not itself prevent
  committed page mutation outside staging, WAL publication, and dirty tracking.
- operation, prepared-group, forced-group, vacuum-replay, failed, and closed
  phases are encoded by overlapping booleans whose valid combinations are
  distributed across the class.
- durable record layout, admission, encoding, validation, state application,
  and recovery orchestration change in the same source file.

The current single-owner durability property remains valuable: WAL force,
heap/index atomicity, page publication, and failure poisoning must not be split
between independently acting services.

## 3. Refactoring decisions

1. **Keep one durable aggregate owner.** The target owner coordinates WAL
   reservation/publication/force, page publication, checkpoint lineage, and
   terminal failure. The existing structure places that table-specific durable
   aggregate in `io.riverdb.engine.table` as public final
   `IndexedTableStore`, alongside `IndexedTable`, transaction sessions, group
   commit, and vacuum. `IndexedTableStoreOpenResult` moves with it. The page
   set, table kernel, WAL codec, and phase state are package-private concrete
   implementation classes. An intermediate rename is not a slice outcome.
2. **Use one table mutation kernel.** Foreground operations, compact logical
   WAL application, page-image fallback preparation, recovery replay, and
   vacuum use the same concrete heap/B+tree/MVCC transition code wherever they
   enforce the same invariant.
3. **Make page-buffer capabilities local and phase-bound.** Only the page set
   and table kernel may receive mutable page payloads. Transaction, relational,
   SQL, protocol, and client code never receive page buffers.
4. **Separate bytes from behavior.** A package-private concrete WAL codec owns
   operation tags, sizes, encode/decode, and structural persisted-input
   validation: magic, version, type, lengths, counts, reserved fields, field
   bounds, and record-local structure. It does not decide transaction
   admission, validate a record against current table state, or mutate table
   state. The store and kernel validate key, page, capacity, and MVCC semantics
   before application. Persisted input remains untrusted until both validation
   stages pass.
5. **Represent the existing operation phase explicitly.** One phase value plus
   bounded phase-specific counters mechanically replaces overlapping lifecycle
   booleans. K16 does not add phases, transitions, or statuses and does not
   redesign the lifecycle. Existing legal and rejected calls retain their
   current results; focused characterization tests guard the consolidation.
6. **Keep components concrete and local.** K16 adds no generic page-store,
   storage-engine, recovery-handler, or operator interface. A new interface
   requires a real second provider or an existing architecture boundary.
7. **Preserve the durable format byte for byte.** K16 does not change page,
   checkpoint, or WAL bytes, format IDs, versions, record ordering, force
   boundaries, or replay meaning. A format change is outside K16 and requires a
   separately approved pre-V1 deliverable with recovery fixtures and the
   architecture, compatibility, and correctness review required by the
   engineering charter.

## 4. Target ownership

```text
IndexedTable
  `- IndexedTableStore
       |- IndexedPageSet
       |- IndexedTableKernel
       |- IndexedWalCodec
       `- operation phase state
```

- `IndexedTable` remains the transaction-facing table facade. It owns no raw
  page buffers, WAL layout constants, replay rules, or duplicate tree walker.
- public final `io.riverdb.engine.table.IndexedTableStore` owns the durable
  aggregate and coordinates create/open, admission, WAL ordering and force,
  page publication, flush, checkpoint, recovery, group commit, vacuum, failure,
  and close. It is public only because the root embedded coordinator and
  checkpoint package are current direct consumers; it is not a public engine
  API or a provider abstraction.
- `IndexedPageSet` owns allocated current/staged buffers, presence, dirty state,
  page record bounds, changed-page tracking, publication swaps, file reads and
  writes, and the exact lifetime of every borrowed payload.
- `IndexedTableKernel` owns fixed heap/index page identities, tree traversal,
  lookup, split, row placement, row-location rebuilding, structural validation,
  mutation application, and MVCC row-version metadata. It operates on the
  owning store's bounded reusable state and does not allocate per row.
- package-private `IndexedWalCodec` owns persisted operation constants and
  structural byte validation. It encodes into provider-owned reservations and
  decodes into reusable carriers; it neither forces WAL, publishes pages, nor
  validates decoded operations against current table state.
- Recovery orchestration remains in `IndexedTableStore` initially. A separate
  recovery coordinator is added only if a concrete second consumer or a clear
  ownership reduction appears during K16; extraction is not an exit criterion.

### 4.1 Buffer lifetime contract

- A current payload is immutable to borrowers and valid until the store
  publishes a replacement page, rebases/checkpoints, or closes.
- A staged payload is mutable only during the active store operation that
  granted it. It becomes unreachable to its borrower on commit, cancellation,
  failure, or close.
- The page set alone swaps current and staging buffers and marks record bounds
  and dirty state.
- No caller retains a mutable payload across WAL publication, force, flush,
  checkpoint rebase, recovery apply, or operation cancellation.
- K16 must not solve read-only access by allocating a new `ByteBuffer` view per
  lookup or row. Capability narrowing must preserve warmed allocation budgets.

## 5. Ordered delivery slices

| Slice | Demonstrable outcome | Required constraints and evidence |
| --- | --- | --- |
| K16a: characterization and ownership map | Existing compact logical operations, page-image fallback, group commit, vacuum, flush, checkpoint, reopen, repair, and recovery behavior have a named authoritative owner and a focused baseline. | Add only missing characterization tests. Record buffer validity, operation phases, WAL force/publication ordering, failure poisoning, row-version ownership, and current copy/allocation counters. No production abstraction is introduced in this slice. |
| K16b: equivalence and phase consolidation | A differential test proves that equivalent logical-WAL and page-image operations reopen to identical key/row/MVCC state. One explicit phase value replaces the existing overlapping booleans for idle, staged, prepared, forced, vacuum-apply, failed, and closed state without changing lifecycle behavior. | Cover the existing success, retry/resource-exhaustion, cancellation, append/force failure, durable-append failure, publish failure, incomplete-vacuum, close-conflict, and recovery cases. Preserve every current `StatusCode` and state outcome. A discovered defect is recorded and handled outside K16; a correctness or data-safety blocker may interrupt K16 but is not folded into the refactor. No formal state-machine framework or new transition is introduced. |
| K16c: WAL codec ownership | `IndexedWalCodec` is the only owner of indexed-operation magic, version, operation tags, header/entry sizes, field offsets, and structural persisted-byte validation. | Encode directly into `LocalWal` reserved storage and decode through reusable carriers. Preserve format ID/version and record bytes exactly. The codec rejects corrupt magic/version/type/count/length/reserved fields, invalid field bounds, truncated payloads, and record-local duplicate pages before returning a decoded carrier. The store/kernel then rejects duplicate or conflicting keys, invalid page/state references, illegal row chains, capacity violations, and other current-state semantic failures before mutation. No Java serialization, streams, boxing, or per-record object creation enters replay. |
| K16d: page-set ownership | `IndexedPageSet` exclusively owns current/staged payload arrays, presence/staged/dirty flags, record bounds, changed-page IDs, page publication, and checked file I/O. Raw mutable payload methods disappear from the `IndexedPageStore` public surface. | State current and staged lifetimes in code comments at the ownership boundary. Prove commit/cancel publication, WAL-before-page ordering, dirty flush, short I/O, force failure, checkpoint zero suffixes, torn checkpoint repair, and close behavior. Preserve current page-copy accounting and introduce no additional full-page copy. |
| K16e: one indexed-table kernel | `IndexedTableKernel` is the sole owner of tree traversal, split, heap placement, row location, structure validation, mutation codes, fixed page IDs, and MVCC predecessor/deletion transitions. `IndexedTable` and recovery no longer contain independent versions of those rules. | Migrate one operation family at a time: lookup/validation, insert, mixed mutations, split fallback, replay, then vacuum. Compact logical operations may retain a no-split fast path, but it must call the same validation/application primitives as fallback and replay. Model and differential tests cover duplicate keys, resurrection, update/delete conflicts, multi-level splits, capacity exhaustion, and corruption. |
| K16f: prepared commit and vacuum ownership | Prepared group commit and multi-record vacuum use the explicit store phase and shared kernel without operation-specific ambient booleans or duplicate mutation rules. | Prove cumulative preflight bounds, cancel-before-append, append order, one force for the batch, publish order, release behavior, partial/incomplete vacuum recovery, obsolete-version accounting, retained tombstones, snapshot pressure, and bounded arrays. Expected outcomes remain status-code control flow. |
| K16g: aggregate rename and exit gate | The durable owner and open-result carrier reside in `io.riverdb.engine.table` as `IndexedTableStore` and `IndexedTableStoreOpenResult`; package-private implementation components remain local; build-policy descriptors and callers are updated; and `IndexedPageStore` no longer exists as a misleading public implementation boundary. | Rename only after callers depend on the target ownership graph. Do not add a compatibility adapter for the unreleased internal name. Run structural checks proving `IndexedTable` does not reference `IndexedPageSet`, raw page payload accessors, or WAL layout fields, then run recovery, allocation, affected-module, and policy checks plus independent correctness, architecture, and performance/allocation reviews. |

The slices are sequential in one checkout because they move overlapping state
and methods from `IndexedPageStore` and `IndexedTable`. One integrator owns the
contract. Reviewers do not independently redefine the WAL or page-lifetime
boundary.

## 6. Required behavioral and failure evidence

The exit suite must cover at least:

- create, insert, multi-row insert, update, delete, deleted-key resurrection,
  lookup, scan, and multi-level B+tree split;
- compact logical WAL versus page-image fallback equivalence after reopen;
- group preflight conflict/capacity rejection, cancellation, append, force,
  ordered publication, and release;
- vacuum with one and multiple chunks, tombstone retention, interrupted batch,
  recovery cancellation, and commit publication;
- checkpoint creation, generation rebase, reopen from checkpoint plus suffix,
  truncated/torn checkpoint repair, invalid page identity, and corrupt WAL;
- short read/write, file force failure, directory force failure, WAL reserve,
  append, force, and read failures at the existing injectable boundaries;
- close while staged, prepared, dirty, failed, and already closed; and
- unchanged transaction visibility, obsolete-version counts, commit sequences,
  transaction IDs, row IDs, and persisted state across each migrated slice.

Tests should prefer existing `IndexedTableTest`,
`IndexedTransactionSessionTest`, `EmbeddedDatabaseTest`, and
`IndexedTableAllocationTest` fixtures. Add a new test class only when the
differential or ownership contract does not fit an existing behavioral owner.

## 7. Performance and allocation contract

K16 is not complete if cleaner source ownership makes the data plane more
expensive. After warmup:

- point lookup, insert, update, delete, replay application, and scan introduce
  no per-row allocation;
- WAL encode/decode uses provider- or store-owned reusable storage;
- no new boxing, streams, iterators, captured lambdas, formatted strings, or
  varargs enter the page, WAL, heap, tree, row-version, or replay inner paths;
- staged full-page copies remain bounded by changed pages and do not increase
  relative to the equivalent pre-K16 path;
- `stagedCopyBytes` and `walCopyBytes`, or their renamed equivalents, retain one
  authoritative owner and comparable semantics; and
- every array, batch, page set, replay carrier, changed-page list, prepared
  group, and vacuum history remains explicitly bounded with its existing
  backpressure or recovery status.

Run the allocation test after each slice that moves a hot path. Run broader
performance measurement only when a slice changes copies, WAL record handling,
page traversal, or synchronization; extraction alone does not justify a new
benchmark framework.

## 8. Exit criteria

K16 is complete only when:

- foreground, fallback, replay, and vacuum use one authoritative indexed-table
  kernel for shared heap/B+tree/MVCC invariants;
- raw mutable page payloads do not cross the table-store ownership boundary;
- one explicit operation phase replaces the overlapping booleans while focused
  characterization tests preserve the existing accepted and rejected lifecycle
  calls and statuses;
- one codec owns every indexed WAL field definition and structural
  persisted-byte check, while the store/kernel own current-state semantic
  validation;
- the durable aggregate remains the sole owner of WAL force, page publication,
  checkpoint lineage, failure poisoning, and close;
- page, checkpoint, and WAL bytes, format identities, force boundaries, replay
  meaning, public engine behavior, and existing `StatusCode` outcomes are
  unchanged;
- no generic interface, untyped context bag, compatibility adapter, per-row
  allocation, extra full-page copy, or unbounded retained state was introduced;
- all focused table, transaction, checkpoint/reopen, allocation, and affected
  `river-engine` tests pass, followed by the repository policy checks relevant
  to renamed classes and forbidden hot-path bytecode; and
- independent correctness-adversary, architecture, and
  performance/allocation reviews approve the final ownership graph.

## 9. Sequencing relative to U00 and U02

- K16 and U00 are separate deliverables with disjoint authoritative contracts.
  Neither waits for the other: the already-underway U00 decomposition continues
  while K16a-K16g run concurrently. This concurrency does not merge their
  ownership or exit gates.
- U00 and K16 use disjoint primary source ownership and separate Git worktrees
  with separate Gradle user homes and project caches when builds overlap. One
  lead integrator coordinates shared integration points; K16 owns
  `IndexedPageStore`, `IndexedTable`, indexed recovery, and their focused tests,
  while U00 retains SQL-session decomposition files. Changes to shared points
  such as `EmbeddedDatabase` and build-policy descriptors are serialized by the
  lead integrator.
- No third implementation lane is scheduled until K16g passes. Independent
  correctness, architecture, relational-semantics, and performance reviewers
  may inspect completed K16 and U00 slices without taking production ownership.
- U02a type descriptors and SQL-level semantics may proceed independently.
  K16 must complete before U02b merges durable variable-width row/index/WAL
  expansion into this indexed-table path. This avoids adding another durable
  state transition to the duplicated ownership model.
- K16 is not permission to broaden K05-K11, introduce speculative buffer-cache
  or recovery interfaces, or redesign the on-disk engine beyond the immediate
  indexed-table consumer.

## 10. Focused build loop

Run one Gradle build at a time. Use the narrowest proving task while editing:

```sh
./gradlew :river-engine:compileJava
./gradlew :river-engine:test \
  --tests io.riverdb.engine.table.IndexedTableTest
./gradlew :river-engine:test \
  --tests io.riverdb.engine.table.IndexedTransactionSessionTest
./gradlew :river-engine:test \
  --tests io.riverdb.engine.table.IndexedTableAllocationTest
```

Expand to checkpoint/reopen and affected `river-engine` tests at slice gates.
Reserve `./verify` and clean-checkout verification for the K16g integration
checkpoint rather than ordinary extraction feedback.

## 11. Completion evidence

K16 completed as a pure refactor on 2026-08-12. `IndexedPageStore` and its open
result were replaced directly by `IndexedTableStore` and
`IndexedTableStoreOpenResult`, with no compatibility adapter. The aggregate now
coordinates durability while package-private `IndexedPageSet`,
`IndexedTableKernel`, `IndexedWalCodec`, and `IndexedStorePhase` own their named
state and invariants. Mutation constants have one definition in the codec; the
facade and kernel do not re-export pass-through aliases.

The focused and affected-module gates passed in an isolated K16 worktree with
its own Gradle user home and project cache:

- the full `:river-engine:test` suite;
- exact compact-WAL/page-image differential recovery and lifecycle-status
  characterization;
- byte-exact WAL, page, and checkpoint compatibility fixtures;
- codec structural-corruption rejection and interrupted multi-chunk vacuum
  recovery;
- indexed-table allocation and copy-accounting tests; and
- `verifyHotPathBytecode`, retargeted to the actual Store, Kernel, PageSet, and
  Codec implementations.

Independent architecture, correctness, and performance/allocation reviews
identified and drove fixes for staged-view validation, vacuum changed-page
bounds, duplicate validation passes/tree traversals, page-state encapsulation,
phase transition handling, and hot-method policy coverage. No behavioral,
durable-format, allocation, or full-page-copy change remains in the verified
K16 lane. U00 continued separately throughout this work.
