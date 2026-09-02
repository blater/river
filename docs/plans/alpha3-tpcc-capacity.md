# Alpha 3 TPC-C schema and capacity plan

Status: implementation contract in progress on
`feature/alpha3-tpcc-capacity`.

Owner: storage/relational lead. Durable-format, recovery, relational-semantics,
and allocation review are required before promotion.

## Immediate consumer

The first correctness consumer is the standard one-warehouse TPC-C schema and
load. River must admit that schema without surrogate-key or denormalization
rewrites and must load it without exhausting table, row-version, page-cache,
checkpoint, or WAL bounds. The performance consumer is the same five-family
workload scaled by warehouses and terminals, with standard remote-payment and
remote-stock behavior. TPC-C is an acceptance workload, not an architectural
ceiling: row, version, and index ownership must remain bounded in heap at
billions of persisted entries.

The initial competitive promotion objective is 1,000 committed transactions
per second under the non-paced 45/43/4/4/4 engineering schedule. This is not
`tpmC` and must not be presented as an official TPC-C result. The evidence must
also report New-Order commits per minute, every transaction-family rate,
expected rollbacks, retries, p50/p95/p99 latency, River-owned allocation, and
the smallest warehouse/terminal scale that sustains the target. A one-warehouse
measurement is the contention and correctness baseline, not a license to remove
standard contention or weaken durability to manufacture the target.

## Fixed contracts

- Table, result, join-role, grouping, ordering, constraint, and key arity have
  no independent hard-coded logical count ceiling. Column and role ordinals
  remain positive `int` values; segmented durable/protocol formats carry
  validated counts and ordinals, and admission is governed by exact encoded
  bytes plus the owning schema/query resource lease. Deployment policy may set
  workload-isolation ceilings broadly comparable with PostgreSQL/MySQL defaults,
  but an enterprise setting can raise them without changing an API or durable
  format. Integer addressability, the physical row/key encoding, and the shared
  byte envelope are the architectural bounds.
- The maximum encoded physical row is 8,192 bytes. Column count does not
  weaken row-size, text, or page-bound validation.
- Nullability, defaults, checks, references, projections, and mutation masks
  use lazily reserved, caller-owned primitive word sets bounded by the owning
  shape rather than a single word or a maximum-sized allocation per session.
- Primary, unique, secondary, foreign, and exclusion-ready tuple descriptors
  use the same chunked ordered-part representation and exact encoded-key byte
  admission. Join, grouping, ordering, and predicate structures use the same
  chunked ordinal storage and query lease. None retains the historical
  eight-column/eight-join assumptions or introduces a replacement fixed arity.
- Catalog schemas use versioned header and continuation records. A table
  definition is not required to fit in one heap row, and catalog publication
  remains atomic.
- Catalog statistics use the same segmented publication model. Continuations
  occupy a validated reserved catalog key namespace disjoint from object,
  sequence, identity-allocation, and table-statistics keys; readers publish a
  generation only after every required segment is present and valid.
- Continuation allocation has one checksummed 64-bit watermark in the reserved
  catalog sequence namespace. A catalog mutation atomically advances that
  watermark with its continuation segments and published header; an aborted
  mutation may leak ordinals but cannot publish without advancing authority.
- Public and protocol row carriers pack text into one row-sized owned buffer
  with per-column offsets and lengths. They do not preallocate a maximum text
  buffer for every possible column.
- Logical row and row-version identifiers are positive `long` values. B-tree
  leaf values, version links, WAL records, recovery validation, vacuum, and
  checkpoint state use the same canonical 64-bit representation.
- Stable logical row identity and changing MVCC version identity are distinct
  positive-`long` domains. A logical-row directory maps table ownership and
  the current head version; a version directory maps physical page/slot,
  previous version, commit sequence, and deleted state. Page IDs and local heap
  slot IDs remain separate physical domains.
- Storage capacity is a validated database/store-wide configuration and
  disk/page-quota decision, not a dense compile-time per-table array bound.
  The configured quota covers aggregate rows plus version churn and exhaustion
  remains explicit through `RESOURCE_EXHAUSTED`.
- Transaction workspace capacity comes from one validated database-local
  resource plan, not fixed Java-array constants or independently tuned arrays.
  Admission jointly accounts for logical row mutations, active net tuple-index
  deltas, lock receipts, staged pages, WAL continuations, and encoded WAL bytes
  before taking the first lock or changing a staged page. A statement rejected
  by local policy returns `RESOURCE_EXHAUSTED`; transient shared pressure
  returns `RETRY`. Neither leaves partial locks, intents, pages, WAL
  reservations, or publication state.
- Page frames, dirty state, row-directory pages, and version-directory pages
  are cached through bounded reusable frame banks. Retained heap must depend
  on configured cache size, not total rows or pages in the table.
- Every borrowed page/row view has an explicit pin lifetime and release owner.
  A frame cannot be evicted or reused while a cursor, graph child, mutation,
  recovery operation, or checkpoint owns a view into it. Pin exhaustion is an
  explicit bounded status.
- Checkpoints publish roots, watermarks, and bounded dirty extents. They do not
  rewrite or retain one in-memory metadata entry per historical row version.
- Vacuum is bounded and incremental. It builds a replacement generation in
  chunks and atomically switches roots; it never stages every table/index page
  or requires one WAL transaction proportional to total cardinality.
- SQL execution continues to allocate no object per row. Wide-row scratch is
  lazily retained per reached statement/session owner and is erased on reset.
- Physical heap rows store every user column. Primary/composite index keys and
  hidden keyless identity remain separate from the row payload; column zero is
  no longer synthesized from a storage key. Key kind and arity are cataloged.
- Protocol v4 separates bounded SQL request, query metadata, and packed row
  payload envelopes. A maximum-shape statement/metadata frame is admitted without
  retaining a maximum text buffer per column; old protocol versions fail
  closed at this unreleased format boundary.

## Required transaction workspace and retention addendum

This section is part of the TPC-C delivery contract, not a post-TPC-C
optimization. TPC-C Delivery must remain one atomic transaction across all ten
districts. It must not be split into per-district commits to fit an internal
array, and index maintenance must not be skipped for unchanged indexed values
unless the common mutation planner proves that no physical key delta is
required.

### Configuration model: physical envelopes, not tuning profiles

There is no generally optimal `transactional`, `mixed`, or `analytic` preset.
The same customer can run all three shapes in one hour, and static percentage
partitions merely move the failure boundary between cache, transaction, and
operator memory. River therefore does not expose workload-labelled profiles or
make an operator tune mutation, lock, staged-page, hash-bucket, sort-run, WAL
record, retained-chunk, and trim-delay settings independently.

The normal production configuration contains only independently real resource
and admission facts:

| Input | Default authority | Why it remains independent |
| --- | --- | --- |
| Maximum River-owned accounted memory | One runtime-root envelope explicitly supplied by the embedding process | Accounted heap/direct capacity cannot be made available by free disk or WAL space. |
| Maximum active transactions | The existing database-open admission value | This is a workload/concurrency decision and determines mandatory per-owner progress state. |
| WAL retention capacity | Database creation/open storage configuration | A long transaction can prevent WAL reclamation even when resident memory is available. |
| Database storage capacity | Database creation/open storage configuration | Persisted pages and version churn consume durable space, not workspace memory. |
| Temporary spill capacity and location | `auto` within a configured filesystem/quota plus `river.sql.materialized.spill-directory` | Sort/hash/build work may spill; transaction locks and uncommitted correctness state may not pretend that disk is resident memory. |

`auto` is one deterministic policy, not a synonym for a hidden profile. It may
not give every database in one JVM the whole process-memory maximum. One
`RuntimeResourceRoot` owns the embedding process's River envelope and admits
each database as a child; an embedding that does not install a shared root must
supply an explicit per-database envelope. A database-local
`DatabaseResourcePlan` is compiled atomically from these inputs before files
open. It first charges conservative fixed runtime state and
non-borrowable recovery, rollback, checkpoint, WAL-force, and cleanup progress
reserves. It then creates one lendable accounted-memory pool from the remainder; it
does not divide that remainder into fixed cache/query/transaction percentages.
Open fails with the exact unsatisfied term when:

```text
fixed runtime
+ recovery and rollback progress reserve
+ maximum-active-transactions * minimum owner progress state
+ one required maximum-shape TPC-C Delivery reservation
> River-owned accounted-memory envelope
```

Accounted memory is the concrete River-owned array, arena, page-frame,
`ByteBuffer`, and reusable-carrier capacity charged by the allocator that
creates it, including conservatively measured Java array/object headers and
alignment. It excludes JVM/JIT code, thread stacks, collector metadata,
temporary GC duplication, and unrelated embedding allocations. River reports
process RSS, committed heap/direct memory, and their difference from accounted
capacity as observed telemetry; it does not promise to make JVM RSS equal the
resource envelope.

The same checked-long admission is performed independently for WAL retention,
storage, temporary bytes/files, addressable page frames, and operating-system
handles. One generous dimension cannot conceal a deficit in another.

Operators may optionally impose the following service-protection ceilings. All
three default to `auto` and are normally omitted:

| Optional policy | Meaning |
| --- | --- |
| `river.transaction.max-write-entries=auto|COUNT` | Distinct live base-row mutations plus active net old/new physical index deltas and lifecycle entries; raw journal history and cancelled deltas do not count. |
| `river.transaction.max-locks=auto|COUNT` | Distinct held point/range/covering lock identities; an upgrade remains one receipt. |
| `river.transaction.max-wal-bytes=auto|SIZE` | Exact encoded bytes including continuation framing and the final transaction decision/digest. |

They are necessarily distinct:
zero-payload deletes consume entries, read/range protection can consume locks
without writes, and wide rows consume WAL bytes without a proportional lock
count. These are one-sided workload-isolation policies, not memory-tuning
knobs. Staged pages, undo records, WAL continuation records, scan ordinals,
hash buckets, sort runs, retained chunks, and lock-provider storage are always
derived and cannot be overridden in `river.properties`. In particular, the
lock provider receives one checked database-wide byte sub-envelope; there is no
locks-per-write multiplier, global lock-count capacity, or `int` narrowing.
Tests may inject a small explicit plan through a package-private construction
boundary.

With `auto`, River admits the maximum standard TPC-C Delivery vector and then
allows any transaction to borrow further capacity while the root envelope and
other owners' progress reserves remain satisfied. The automatic policy has no
small logical count ceiling: format/addressability validation and exact byte
charges bound enterprise growth. Exceeding an optional local policy is
`RESOURCE_EXHAUSTED`; a request that fits policy but is temporarily blocked by
another live owner is `RETRY`. Neither outcome is reported as an allocation or
I/O failure.

Before issuing a retry ticket, the governor computes the maximum vector that
could be granted if all reclaimable chunks were shed and all live borrowable
leases completed, while preserving non-borrowable and other-owner minimum
progress reserves. A demand exceeding that ever-fit vector in any dimension is
permanently impossible under the current envelope and returns
`RESOURCE_EXHAUSTED` without entering the queue. Only an ever-fit request that
is blocked by current live ownership receives `RETRY` and a ticket.

The effective plan is inspectable before workload begins. Startup diagnostics
and `river config explain` report every physical input, fixed reserve, derived
guarantee, currently borrowable capacity, and the first limiting term for a
supplied transaction/query shape. They never print a recommendation such as
"increase hash buckets"; recommendations name the actual constrained resource
or concurrency guarantee.

This directly replaces the current independent materialized-cache,
schema-cache, session-shape-cache, sort-run, hash-row, and hash-bucket runtime
properties. River is pre-V1, so they are removed rather than retained as
compatibility aliases. In particular, `river.sql.materialized.page` is a 64 KB
SQL operator/spill block setting and is removed or derived; it is not River's
durable page format. The durable `PageCodec` page remains the separately frozen
16,384-byte database format unless an explicit format checkpoint changes it.
Spill location remains physical configuration. Cache frames, operator blocks,
retained schema/session shapes, hash tables, and sort runs are derived
consumers of the one resource plan.

### Shared bounded-resource pattern

`DatabaseResourceGovernor` is a child of `RuntimeResourceRoot` and owns a fixed
primitive resource vector rather than
separate unrelated counters. The vector accounts for River-owned bytes, typed
chunk capacity, distinct lock slots, ordinary and staged page frames/pins,
encoded and retained WAL bytes, spill bytes/files, and active owners. One
caller-owned `ResourceDemand` is measured with checked arithmetic and one
caller-owned `ResourceLease` records the atomic grant. These are reusable
primitive-field carriers, not maps, boxed collections, varargs, or per-request
objects.

```text
RuntimeResourceRoot
  database resource governor
    non-borrowable recovery/rollback/checkpoint/WAL progress reserve
    lendable accounted-memory and I/O capacity
      session lease
        transaction workspace and lock lease
        statement/operator lease
      maintenance lease
```

Every domain uses the same `measure -> reserve -> own -> release` protocol:

1. Measure exact logical/key/entry demand and a proven
   concurrency-independent upper bound for physical split pages and their WAL
   images without changing externally visible state. UPDATE compares canonical
   before/after keys and counts only active net index deltas; cancelled deltas
   decrement both count and byte demand. Foreign-key, uniqueness, and
   reverse-reference work is planned only for changed keys.
2. Deduplicate the statement lock plan and put lock targets in canonical
   acquisition order. The physical bound calls the storage algorithm's one
   authoritative `maximumNewPages(operation, formatMaximumHeight)` function for
   every active physical tree mutation and checked-sums those results. An
   insertion therefore reserves a full cascading split path through the frozen
   maximum height, including root publication, rather than assuming the tree
   can grow only once during a large batch. An operation may use a smaller
   bound only where the storage format proves it cannot allocate or split.
   Preflight staged frames, undo, worst-case encoded WAL continuation bytes,
   and commit/rollback requirements from that concurrency-independent bound.
3. Atomically grant or reject the complete resource vector. Partial charging
   is impossible. A granted lease includes the resources required to roll back
   or commit, so another owner cannot make an admitted transaction unfinishable.
4. Claim each current row through the shared lock-current contract below,
   recompute predicates, expressions, and physical key deltas from its
   lock-protected before-image, and refine the physical plan against the
   now-serialized keys. The actual plan must fit the reserved upper bound;
   surplus credits may be returned. Stage private pages, stream WAL records,
   force the commit marker, and publish. Release validates owner identity,
   generation, pins, and hand-offs before returning every charge.

INSERT may measure directly. UPDATE/DELETE of an unknown candidate set uses a
bounded spillable candidate-collection pass before write admission; it does
not discover the hard transaction limit after acquiring an unbounded series of
locks. Read-only sort, group, distinct, join, and candidate collection may
spill. Transaction metadata, held locks, rollback state, and staged ownership
do not spill in this checkpoint.

### Lock-current row execution contract

This contract replaces the current read-then-lock-then-reject sequence. It is
one relational execution rule shared by `UPDATE`, `DELETE`, and `SELECT ...
FOR UPDATE`; it is not a retry path or a second mutation implementation.

The current defect is:

```text
snapshot candidate -> evaluate predicate/SET -> wait for row lock
                   -> reject because the committed head advanced
```

The required sequence is:

```text
stable candidate identity -> acquire exact base-row lock through its FIFO
                          -> obtain the current protected before-image
                          -> recheck the same compiled predicate
                          -> evaluate SET once from the current before-image
                          -> measure, validate, and stage through one mutation path
```

Candidate discovery and mutation authorization are deliberately separate.
Discovery may read a statement/transaction snapshot without locking every
non-matching row. It publishes a primitive target containing table identity,
stable logical row identity, candidate physical-version identity, and, when an
exact primary/unique index found the row, the source predicate needed for the
post-lock recheck.
The discovered row image is never authoritative input to an update expression,
constraint, index delete, or foreign-key decision after a lock wait.

For an exact legacy physical primary key, the row key is already the stable
logical identity. For descriptor tables, every scalar, composite, typed, and
keyless row uses the descriptor base `(baseRows(tableId), logicalRowId)` as its
canonical row-lock identity. Key width and SQL type do not alter the row-lock
contract.

An exact descriptor primary/unique lookup under READ COMMITTED or REPEATABLE
READ follows the same row contract:

1. Discover `(logicalRowId, candidateVersionRowId)` at the statement's
   candidate snapshot.
2. Acquire the logical base-row lock through its resource-local FIFO and read
   the current protected before-image.
3. Re-evaluate the complete compiled predicate, including the primary or
   unique tuple which selected the candidate. A removed row, a reassigned
   mapping, or a row whose current values no longer match is skipped; the
   executor does not adopt a replacement logical row absent from discovery.
4. Retain only the canonical base-row lock for an accepted `SELECT FOR UPDATE`.
   UPDATE and DELETE then acquire their complete old/new tuple-delta plan in
   global key-ID/user-tuple order before validation and staging.

Exact index keys are not a second RC/RR row-lock identity. Retaining one before
the base row creates source-to-base inversions against scan candidates and
turns a FIFO row handoff into an artificial upgrade deadlock. Point, partial
composite, range, full, and ordered/materialized candidates therefore share
one base-row acquisition and post-lock predicate-recheck implementation.
Keyless rows use precisely the same operation without any index special case.

All descriptor mutations use one canonical tuple-protection service before
constraint validation or staging. It enumerates every physical primary,
unique, and non-unique index in key-ID/tuple order: INSERT protects every new
point, DELETE every old point, and UPDATE the union of old and new points. An
unchanged UPDATE point is protected once even when physical index maintenance
is elided, because a SERIALIZABLE reader of that index still conflicts with
the base-row change. Unique and composite foreign-key validation reuse these
same full tuple identities; hashes are directory accelerators only. Direct
tuple append is subject to the same invariant. The sole exception is private
backfill into a lifecycle-confirmed BUILDING index whose exclusive root is
already owned; callers cannot request or spoof that exception.

SERIALIZABLE discovery deliberately locks the predicate before it observes
candidates. An ordinary exact primary/unique read takes `SHARED` predicate
protection, including an absent exact key; a locking exact read or exact DML
takes `EXCLUSIVE` from the outset and never upgrades from `SHARED`. This is
predicate protection, not an alternative row identity. It then probes the current tuple root
plus its own intents. A range scan first acquires the ordered tuple interval and
only then captures and opens its current-visibility cursor. Base-table
exact/range access follows the same key/range-before-current-read rule. An
absent candidate therefore remains protected against a phantom until
transaction completion.

This makes point and scan plans semantically equivalent. An optimizer choice
cannot make a statement update a replacement row that another access path
would have skipped. Delete followed by insert under the same key is
also a replacement, not an update successor: current-version resolution must
walk the version chain back to the candidate and reject a chain containing an
intervening tombstone.

The table layer owns one allocation-free, execution-lane-owned
`IndexedCurrentRowGuard`. It contains primitive identity/state and its own
reusable `LockToken`; it does not expose lock tokens above the indexed-session
boundary. RC/RR candidate discovery retains no source tuple lock, so the guard
is the only borrowed row-lock carrier. SERIALIZABLE predicate protection is
transaction-retained before the guard is borrowed and is not a second row
identity. The base-row guard's
states are `IDLE`, `BORROWED`, and `ALREADY_RETAINED`:

- `BORROWED` means the exact lock has been granted but is not yet part of the
  transaction-lifetime retained set. Failure, a deleted/replaced candidate, or
  a false current predicate releases it immediately.
- `ALREADY_RETAINED` means this transaction already owned the canonical lock.
  Rejecting a repeated candidate must not weaken that earlier ownership.
- Successful locking reads and mutations adopt the guard into transaction
  lifetime exactly once. Commit/abort remains the terminal release owner.

The current synchronous SQL session has one execution lane, so it owns one hot
guard. This is not a configured or architectural lane limit: a future parallel
executor gives each admitted lane the same guard carrier from lazy governed
chunks without changing the lock manager or row contract.

`IndexedLockWait` therefore gains borrowed acquisition into a caller-owned
token plus explicit retain/release operations. Its existing enqueue-once,
park, targeted wakeup, deadline, cancellation, and deadlock behavior remains
unchanged. A borrowed row guard cannot reuse `IndexedLockWait`'s ordinary token
because index and foreign-key acquisition may use that wait lane while the base
guard remains live.

`IndexedTransactionSession` exposes one concrete internal operation family,
not separate scalar/composite/update/delete variants:

```text
lockCurrentKey(space, key, candidateVersionRowId, rowResult)
retainCurrentKey()
releaseCurrentKey()
stageCurrentUpdate(rowBytes)
stageCurrentDelete()
```

Names may change during implementation, but the ownership is fixed. Current
fetch is one synchronized table/kernel operation that captures the current
commit sequence internally, overlays this transaction's latest pending row,
and returns the authorized current row plus predecessor version identity. It
must not read `currentCommitSequence()` and fetch in two separately
synchronized calls. Locked staging consumes the captured predecessor directly;
it does not call snapshot-based `prepareMutation` again.

The existing optimistic byte-level `update`/`delete` API may retain its
snapshot conflict contract for non-SQL callers. SQL must use the locked-current
family exclusively. `validateKeyCurrent` is removed from SQL and relational
paths; weakening `IndexedKernelVisibility.prepareMutation` globally is
forbidden because that would permit stale encoded rows to overwrite current
values.

All buffers that can grow are reserved before borrowing the row lock:

- descriptor fetched/mutation `SqlValueBuffer` storage;
- encoded-row storage and maximum-width tuple-key scratch;
- predicate, expression, projection, and text scratch;
- a conservative next-row write/tuple/WAL reservation or the statement's
  already granted complete resource vector.

The protected section performs no object allocation, boxing, iterator/lambda
creation, `ByteBuffer` duplication, formatted diagnostics, or per-row governor
call. Exact measurement after reevaluation may return surplus reservation but
cannot demand an unreserved rollback/commit resource. Projection is completed
into caller-owned output before a locking read adopts the guard; a failed
projection releases the borrowed claim and publishes no row. Mutation staging
and guard adoption form one statement-owned success boundary; failure rolls
back staged deltas and releases a borrowed claim without releasing locks that
were already transaction-retained.

#### Isolation and candidate semantics

River freezes the following behavior for this checkpoint:

| Isolation | Ordinary reads | DML and `SELECT ... FOR UPDATE` |
| --- | --- | --- |
| `READ COMMITTED` | One statement snapshot | Snapshot candidates; after each FIFO grant use the current committed successor and recheck the predicate. |
| `REPEATABLE READ` | One transaction snapshot | Transaction-snapshot candidates; locking operations use the current committed successor instead of aborting merely because it advanced. Rows inserted after that candidate snapshot are not newly selected. |
| `SERIALIZABLE` | Shared exact/range locks followed by current reads under those locks | Predicate/range protection plus exclusive selected-row locks followed by the same current-row rule. Serializable scans capture current visibility only after their predicate/range lock is granted. |

Thus ordinary repeatable reads remain repeatable while repeatable-read DML and
locking reads are current. A genuine deadlock, deadline, cancellation, I/O
failure, corruption, or governed resource pressure remains visible. Ordinary
version advancement after a successful FIFO handoff does not return `RETRY` or
`DEADLOCK`.

This RR rule deliberately combines stable transaction-snapshot candidate
membership with current update-successor handling. It avoids River's obsolete
post-grant retry and serves the MariaDB comparison workload, but it is not
claimed to be identical to InnoDB candidate discovery, which may include rows
found by a current locking traversal after the ordinary transaction snapshot.

#### Immutable page generations for fixed candidate snapshots

A published page image is immutable for every snapshot that can still reach
it. A page pin identifies one exact `(logical page ID, valid-from commit
sequence)` generation; it does not merely pin whichever bytes are currently
mapped under that logical page ID. Publication must never copy staged bytes
over a pinned or snapshot-reachable frame. This invariant belongs to the shared
page provider, not to SQL: scalar scans, tuple probes, index scans, validation,
and future execution paths all receive it automatically.

The page cache keeps stable logical page IDs and bounded in-memory MVCC images.
Each committed frame has a half-open visibility interval
`[validFromCsn, validUntilCsn)`. Publication admits a replacement frame before
WAL force, copies the staged image into that new frame, closes the predecessor's
interval at the commit sequence, and atomically makes the replacement current.
`pinAt(pageId, snapshotCsn)` walks the page's short generation chain and returns
an authenticated borrow for the matching immutable frame. Release uses that
exact frame/generation borrow, never a page-ID lookup which could release a
newer image. A tuple cursor remains safely ordinal within its frozen graph;
root splits, sibling-link rewrites, delete compaction, and key movement in a
later generation cannot perturb it.

The transaction manager maintains the oldest visible commit sequence of active
transactions. Retired page images become reusable only when their visibility
interval ends at or before that floor and all exact pins have drained. This
includes transactions that began before a commit but open their first cursor
after it. Transactions do not survive process restart, so recovery needs only
the latest decided image; historical in-memory images are neither a second
durable format nor reconstructed after restart. WAL recovery still installs
all images before exposing their root/version rows.

Current, staging, and historical images consume one page-cache budget with
dynamic roles, not independent customer dials or a fixed number of generations
per page. The existing staged-operation ceiling remains an atomic-publication
bound. Under sustained old-snapshot pressure, eligible cold history may spill
to the engine-owned append-only transient tier described by the shared bounded
tier policy; if both resident and spill budgets are exhausted, admission fails
with governed pressure before WAL force and leaves the old graph/root untouched.
No overwrite, unbounded heap retention, hidden retry loop, or table/index lock
is an allowed fallback. Reclamation is incremental from the oldest-snapshot
floor, removes its transient files on every terminal path, and reports retained
bytes/generations, spill bytes, oldest age, and pressure rejections.

Required storage gates cover forward and reverse same-leaf delete/move,
insertion before/after the cursor, full physical duplicate-key ordering, leaf
split and root replacement, composite inclusive/exclusive bounds, a transaction
that opens after a newer commit while retaining an older snapshot, exact-pin
ABA prevention, pre-force pressure failure, before/after-decision crash
recovery, and zero warmed cursor-step allocation. SQL gates then repeat those
cases through ordinary RC/RR reads, SERIALIZABLE predicate-lock-then-current
capture, `SELECT FOR UPDATE`, UPDATE, and DELETE.

A current committed update successor is accepted. A deleted candidate, a
delete/reinsert replacement, or a source index mapping reassigned to another
logical row is skipped and does not increase affected-row count. Under RC/RR,
an access equality fully consumed by exact primary/unique binding needs no
redundant snapshot evaluator call. Every residual predicate must match during
candidate discovery and is rechecked against the protected current successor;
a residual false-to-true change cannot add a row, while true-to-false skips it.
SERIALIZABLE establishes candidate membership from the current protected row.
All evaluations call the same compiled predicate implementation; there is no
copied "post-lock predicate" path. `SET`, generated-value, CHECK, unique,
foreign-key, and physical index-delta logic runs once from the current
before/after images.

For scan DML, the candidate set is fixed by the statement snapshot. Rows that
become matching later are not added; candidates that are deleted, replaced, or
stop matching are skipped; candidates that still match are changed from their
current values. Scan DML uses a byte-governed primitive
`long[SqlCommand.MAXIMUM_INSERT_ROWS]` collector with a byte-governed primitive
target stream backed by the shared materialized-page/spill substrate. It has no
row-count limit and always removes its temporary files on success, rollback,
cancel, close, and recovery cleanup. Descriptor scans may stream only after
tests prove their fixed committed root and captured pending-intent horizon give
the identical stable-target/Halloween contract; otherwise they use the same
target stream.

For ordered locking reads, predicate and projection are rechecked against the
current row and `LIMIT` counts only accepted rows. To avoid locking every row
merely to sort, an external/materialized sort retains statement-snapshot
candidate order; a concurrent change to a mutable sort expression may therefore
make returned current values appear out of order, matching PostgreSQL's
documented practical behavior. Deleted or no-longer-matching candidates are
skipped and the executor refills the limit. Plans whose order key is protected
or immutable, including TPC-C Delivery's primary order key, preserve exact
order. This behavior is tested and cannot vary by in-memory versus spilled
sort.

Joined, grouped, aggregate, set, nested, and view `FOR UPDATE`, plus
`UPDATE ... FROM`, `DELETE ... USING`, and mutation `ORDER BY`/`LIMIT`, remain
explicit parser/binder `FEATURE_NOT_SUPPORTED` boundaries in this slice.
Enabling them later requires base-row provenance, `FOR UPDATE OF` ownership,
and target-identity deduplication; this change must not accidentally accept
them.

#### Relational mutation and lock ordering

After a current row is claimed, both legacy and descriptor mutation use this
single order:

1. Decode the current before-image and recheck the full predicate.
2. Evaluate row-dependent assignments once from that image.
3. Derive one reusable before/after key-delta plan.
4. Validate row shape, nullability, CHECK, and exact capacity.
5. Acquire canonical ordered tuple resources in `(scope, keyId, tuple bytes)`
   order, deduplicated with any protected source key. INSERT protects every
   destination index point, DELETE every source point, and UPDATE every old
   and new point. An unchanged index entry takes one point lock but produces
   no physical tuple delta; this lets a serializable predicate reader conflict
   with a non-key column update to a row inside its range.
6. Probe unique and foreign-key state through current protected tuple APIs.
7. Stage the base predecessor and old/new tuple deltas atomically, then retain
   the row claim.

RC/RR point lookup resolves a snapshot candidate without retaining a source
tuple lock; SERIALIZABLE point lookup retains predicate protection before its
base row. A scan already owns a stable candidate identity and learns destination
keys only after locking that row. There is therefore no false universal
tuple-before-base rule for mutation resources. The phase order above is
canonical within a target; cross-row primary
key swaps and application statement order can form real cycles and use FIFO
waiting plus deterministic deadlock detection. River does not introduce a
target-count cap, release/retry loop, table lock, or speculative prelocking
scheme to hide those cycles.

The before/after key-delta plan is shared by measurement, lock acquisition,
uniqueness/FK validation, and tuple staging. Unchanged primary, unique, and
secondary keys coalesce one canonical point holding and produce no tuple-page
work; unchanged referenced keys perform no reverse-reference scan. Parent
update/delete uses a cataloged reverse
referenced-key dependency index rather than scanning every table for each row;
the index is maintained by the same schema publication transaction and is
covered by reopen/corruption tests.

Current protected tuple probes are separate from general snapshot query probes.
After an exact unique or foreign-key lock is granted, integrity validation must
read the current tuple root plus this transaction's intents. It must not call
`resolveTupleUniquePrefix`/`resolveTupleAnyPrefix` against an old visible root.

#### Production cutover map

The implementation is delivered bottom-up in reviewable checkpoints:

1. Table/transaction: `IndexedLockWait`, `IndexedSessionState`,
   `IndexedTransactionReadAccess`, `IndexedTransactionWriteSet`,
   `IndexedSessionTupleAccess`, `IndexedTransactionSession`, and the
   table/store/kernel visibility boundary gain borrowed lock-current,
   current-successor, current protected tuple-probe, and locked staging.
2. Relational: `RelationalSession`, `RelationalRowMutation`,
   `RelationalDescriptorRowAccess`, `RelationalDescriptorPrimaryAccess`,
   `RelationalDescriptorScanAccess`, and
   `RelationalDescriptorTableAccess` expose one current claim/apply path and
   carry candidate version identity through caller-owned result/cursor state.
3. Constraints/indexes: `RelationalDescriptorTupleMutations`, measurement,
   unique/foreign validation, staging, and reverse-FK checks consume the same
   current before/after delta plan and protected current probes.
4. DML: `SqlDmlExecutor` and `SqlMutationKeyCollector`, plus
   `SqlDescriptorPointExecution`, `SqlDescriptorPointScanAccess`,
   `SqlDescriptorUpdateScan`, and `SqlDescriptorDeleteScan`, migrate together.
5. Locking reads: `SqlPointSelectExecution`, `SqlQueryExecution`, and
   `SqlSortExecution`, plus `SqlDescriptorPointExecution`,
   `SqlDescriptorSingletonScan`, `SqlDescriptorScanNext`, and
   `SqlDescriptorMaterializedPublisher`, publish only the authorized current
   row.
6. Isolation/lifecycle: serializable point/scan visibility, statement rollback,
   cursor close, cancellation, and session close prove every borrowed guard is
   idle and every retained lock has one terminal owner. Delete obsolete SQL
   calls to `validateKeyCurrent` only after all callers have migrated.

No protocol round trip is added. The existing execute or fetch request remains
parked at the lock boundary and completes after targeted grant. Prepared
execution does not resend SQL or restart the whole transaction.

#### Test impact frozen before implementation

Existing tests are changed deliberately rather than repaired after production
code moves:

Before the first production edit, an `rg`-generated retry/conflict ledger is
checked in the working evidence for `river-tx`, `river-engine`, and `river-jdbc`.
Every existing assertion is classified as ordinary lock contention, deadline,
deadlock, optimistic low-level conflict, schema/maintenance pressure,
resource pressure, end-of-scan absence, or the obsolete post-grant stale-row
behavior. Only the last category changes semantics. The affected classes are
run once as a baseline, then after each cutover checkpoint; failures are not
discovered first by the full suite.

- `SqlSessionTest.streamingForUpdateRetryClosesItsWaitAndLeavesExplicitTransactionUsable`
  becomes a deterministic concurrent grant test: the waiter parks, the owner
  updates/commits, and the waiter returns the current value with `OK`. Deadline
  cleanup remains covered by a short/injected lock-wait test instead of a
  multi-second SQL test.
- `SqlSessionTest.forUpdateRejectsAVisibleRowWhoseCommittedHeadAdvancedBeforeLock`
  becomes an isolation matrix: ordinary RR `SELECT` keeps its snapshot, while
  RC/RR `SELECT FOR UPDATE` returns the advanced current row and SERIALIZABLE
  reads follow the current-under-lock contract.
- `SqlSessionTest.selectForUpdateProtectsLegacyAndCompositeDescriptorRowsUntilTransactionEnd`
  replaces contention `RETRY` assertions with concurrent blocked-then-`OK`
  handoffs for legacy, composite primary-key, and ordered/limit paths. Disjoint
  rows remain immediately available.
- `RiverTpccJdbcAcceptanceTest.reportsLockRetryWithoutPoisoningTheExplicitJdbcTransaction`
  becomes a blocked district-row handoff test and verifies the current
  `d_next_o_id`; `opposingCompositeKeyLocksReportOneDeadlockAndGrantTheSurvivor`
  remains a real two-resource deadlock test and additionally checks the
  survivor's current row.
- `IndexedTransactionSessionTest.staleRepeatableReadCannotOverwriteNewerVersion`
  remains unchanged for the explicitly optimistic low-level byte API. New SQL
  tests prove that SQL does not use that API after cutover.
- `SqlCompositeForeignKeyTest` contention cases remain genuine parent/child
  lock tests, but become concurrent handoff tests where appropriate; foreign
  key violations after the owner commits remain violations, not stale-snapshot
  retries.
- Schema-gate, index-build, vacuum, resource-governor, synthetic lifecycle,
  end-of-scan `CONFLICT`, and nonblocking `tryAcquire` `RETRY` assertions are
  unrelated and remain unchanged.
- `SqlKeylessTableTest` keeps scalar non-unique `FOR UPDATE` rejection. It adds
  streaming keyless locking coverage rather than weakening the exactly-one-row
  scalar contract.

The focused class set is fixed as `LockExactTableTest`,
`LockExactDeadlockTest`, `IndexedTransactionSessionTest`,
`RelationalDatabaseTest`, `SqlSessionTest`, `SqlCompositePrimaryKeyTest`,
`SqlOrdinaryCompositeIndexTest`, `SqlDescriptorTupleIndexScanTest`,
`SqlCompositeForeignKeyTest`, `SqlKeylessTableTest`,
`SqlSessionAllocationTest`, and `RiverTpccJdbcAcceptanceTest`. Parser boundary
tests are included from `river-sql`. A test is added to this set only when a
new call graph proves that the class is affected; unrelated `RETRY` assertions
are not mass-edited.

New focused tests are written with the production change, in this order:

1. Table guard lifecycle: immediate and queued acquire, current fetch,
   current-successor chain, intervening tombstone/replacement, own pending
   update/delete, retain, release, repeated already-retained claim, cancellation,
   deadline, deadlock, and injected failure cleanup.
2. Lost-update proof: two contended `SET n=n+1` statements commit both
   increments with no whole-statement retry; the same test covers point and
   scan access.
3. Predicate proof: a waiting candidate that remains matching updates from its
   current value; one that stops matching or is deleted/reinserted is skipped;
   affected rows and statement atomicity are exact.
4. Access parity: legacy primary/secondary, descriptor scalar/composite
   primary, composite secondary, integer/text/decimal key parts, and keyless
   hidden identities produce the same result. Concurrent primary rename,
   source-key reuse, and unique-secondary reassignment cannot mutate the wrong
   logical row. Exact and forced-scan plans for primary/unique equality plus a
   mutable residual predicate agree for false-to-true and true-to-false races.
5. Index/constraint proof: old entries come from the current before-image and
   new entries from the recomputed after-image; unchanged keys do no physical
   work; nullable unique, CHECK, outgoing/incoming composite FK, self-FK, and
   parent delete/update races remain atomic across rollback, WAL replay, and
   checkpoint/reopen.
6. Scan/Halloween proof: changing the indexed predicate/order key does not
   duplicate or skip an original target; rows becoming matching are not added;
   more than 1,024 and 65,536 targets cross memory/spill boundaries without a
   row-count limit; success, rollback, cancellation, and close leave no
   temporary file.
7. Locking-read proof: point, streaming, unique-index, ordered ASC/DESC with
   multi-column ties, `LIMIT 0`, early close, deleted/refilled limit, and
   in-memory/spilled materialization return current rows and retain only the
   contractually selected locks.
8. Isolation proof: RC statement snapshot, RR ordinary snapshot plus current
   locking read, and SERIALIZABLE current shared/range reads have explicit
   point, missing-key, base-range, and descriptor tuple-index phantom tests.
   Serializable descriptor ranges require ordered tuple interval protection;
   an exact hashed lock is not accepted as range protection. Missing exact
   scalar/composite primary and unique-secondary keys are protected against a
   concurrent insert. RR separately proves that a post-snapshot insert is not
   selected while an already-discovered row's committed update successor is.
9. Boundary proof: joined/grouped/set/nested/view `FOR UPDATE` and mutation
   join/order/limit forms continue to fail with their documented status.
10. Allocation and performance proof: warmed point/scan `FOR UPDATE`, arithmetic
    update, false-candidate skip, and composite current probes allocate zero per
    row; all first-use buffer growth happens before the row claim. Slopmark is
    recorded before/after, no touched existing class increases its score, and
    every new class remains below 50.
11. TPC-C proof: a hot one-warehouse/district handoff run reports FIFO grants,
    actual deadlocks, deadlines, borrowed/retained hold time, retries by source,
    requests per commit, family latency, allocation, and GC. Ordinary
    post-grant version advancement contributes zero retries, protocol request
    count does not increase, Payment aggregate totals are exact, and the same
    workload is rerun against MariaDB before the broader 1,000 TPS gate.

### Lock queue and deadlock contract

Lock contention is an enqueue-once continuation, not a retry loop. Each exact
resource has one bounded primitive directory record, an intrusive granted-owner
chain, and an intrusive FIFO wait chain. There is exactly one canonical
`(resource, transaction generation)` holding in either `RESERVED` or `ACTIVE`
state. The first queued request creates and indexes a `RESERVED` holding during
admission; additional lanes for the same transaction/resource pin that holding
rather than creating invisible alternatives. Its first compatible grant links
it into the resource-owner chain and changes it to `ACTIVE`. A reserved holding
with no remaining request references is removed from the index and recycled.
Thus a waiter that has been admitted cannot wake and fail because another
acquisition consumed active-lock capacity meanwhile, same-owner requests cannot
self-block behind duplicate reservations, and pending cohorts participate in
hash load/split accounting before grant. Admission reserves the resource
record, request/holding entry, transaction lookup, and traversal-workspace
depth implied by the prospective live transaction count atomically before
linking the request. Wait-for edges are derived from these canonical records
rather than copied into a second graph store. A resource record is
recycled only after both its owner and waiter chains are empty.

The provider authenticates every transaction context, token, lane, and
wait-handle mutation with one opaque per-provider capability whose object
identity is never exposed through the public API; predictable numeric provider
and transaction identifiers are diagnostics and lookup keys, not authority.
Because a transaction owns and reuses its context carrier, every operation
captures the current transaction generation by value and supplies it to the
provider. Rebinding the carrier for a later generation therefore cannot revive
a stale asynchronous borrower.
Authoritative request-store state and slot generation are revalidated for every
consume, cancel, grant, and release; carrier state is publication, never proof.
The caller supplies a reusable execution-lane and wait handle. The caller-owned
wait handle stores the exact waiting thread; the primitive request record stores
only the authenticated handle reference needed for targeted signalling.
Admission runs under the short lock-manager monitor; waiting never does. A
blocked caller parks outside the monitor until a targeted unpark, interruption,
or its exact deadline. Spurious JVM wakeups may reread the handle state and
re-park for the remaining deadline, but may not poll periodically, rescan
blockers, rerun SQL, or restart the transaction. Release, compatible downgrade,
cancellation, timeout, or deadlock-victim selection performs a terminal state
transition with release ordering and directly unparks only the affected
handles. The grant routine grants either the exclusive/update queue head or the
maximal compatible shared prefix. New arrivals cannot bypass an incompatible
queued head.

The validated database default is now exposed as
`river.tx.lock-wait-timeout` in `river.properties` and is passed through the
embedded/open construction path to the transaction manager. It accepts a
positive integral duration with `ns`, `us`, `ms`, `s`, `m`, or `h` units; the
default remains five seconds. Targeted SQL lock tests use this boundary to
select millisecond deadlines without changing production defaults.

TODO: complete the remaining session/API configuration boundaries without
duplicating timeout or queue logic. Override priority is (1) a session setting,
(2) the embedded/open API, and (3) `river.properties`; the effective value
follows that same order, then falls back to River's validated database default.
Each boundary must use the common duration validation policy and pass the
resolved nanosecond value to the transaction manager. Remaining tests must
cover precedence, an already-enqueued wait retaining its original absolute
deadline, new waits observing a changed session value, and session changes
racing with grant, cancellation, and waiter-slot reuse.

`SHARED`, `UPDATE`, and `EXCLUSIVE` compatibility is maintained from primitive
owner, shared-owner, and update-owner counts, without scanning the owner chain.
Shared owners may coexist with one update owner; a second update or an
exclusive owner remains incompatible. A granted-but-unconsumed request leaves
the resource FIFO but remains on its transaction request chain until consume or
terminal revocation. Terminal detach keeps its lane identity authenticated until
the caller acknowledges the outcome; premature handle reset fails rather than
stranding a pending lane.

Exact resource lookup is expected O(1) through a collision-safe, incrementally
growing primitive hash directory; it does not allocate an envelope-sized bucket
array or stop the lock manager for a full-table rehash. Exact enqueue, unlink,
and cancellation are O(1) intrusive-link operations. Each FIFO head decision
and single grant is O(1); a release handoff is O(g), where `g` is the maximal
compatible FIFO prefix actually granted and signalled. Ordered range locks use an augmented interval index with
O(log R + K) overlap lookup, where `R` is the live range count and `K` is the
returned overlap set. Acquisition, release, and wakeup therefore visit only
the affected exact resource and overlapping ranges, never every active or
waiting lock. Exact keys and ranges share the same overlap and fairness rules;
a key blocked by a range is linked into the affected scheduling set and is
signalled when that interval's blocker changes. Deadlock invalidation is
proportional to canonical records changed on affected resources, and iterative
cycle detection visits only the reachable wait-for subgraph.

Live execution-lane identity uses a second byte-accounted incremental primitive
directory keyed by `(transaction id, transaction generation, lane id, lane
generation)`. Enqueue rejects a duplicate live identity with `CONFLICT` before
admission; terminal consume/cancel removes the directory entry before the
request slot can be reused. Lane-generation reuse therefore requires no scan,
retained per-lane array, or configured lanes-per-transaction ceiling.

The lock manager is the sole owner of canonical `(transaction generation,
resource)` holdings. Active records also participate in an intrusive
per-transaction chain, so terminal transaction cleanup is O(number held) and
does not scan the global directory. Repeated acquisition, upgrade, ownership
queries, and coalescing use the global exact-resource and interval indexes; the
engine does not maintain a duplicate held-lock receipt journal or token array.
Savepoint and statement rollback retain locks, as required by River's current
two-phase-locking contract. Every terminal commit, abort, conflict, and
indeterminate path first cancels queued or granted-but-unconsumed lane requests,
then releases the transaction's active chain after its durable outcome has been
selected. Engine-internal acquisition is transaction-scoped and uses terminal
`releaseAll`; any retained public token API authenticates a distinct usage
claim on the canonical holding, so releasing one claim cannot release another
lane's protection.

Context activation and retirement linearize under the same lock-manager
monitor as admission. Begin prepares transaction fields, cancellation, and the
snapshot before activating the context as its final publication. Commit and
abort atomically retire the context and mark the canonical lock transaction
`FROZEN`; no public release, upgrade, consume, cancel, or timeout may create or
weaken protection or select a competing request outcome after that point.
Terminal cleanup bypasses the public fence, and terminal wait/token
acknowledgement may only retire already-invalid caller carriers. After the
durable decision is selected, cleanup precedes active-set removal and terminal
transaction/outcome publication.

Deadlock detection exposes a resource-governed wait-for graph derived directly
from canonical request, resource, and holding chains. It does not maintain a
duplicate edge table whose contents can become stale or whose growth could fail
during release. Each execution
lane has at most one current pending lock request because a parked lane cannot
execute a second acquisition concurrently. A transaction may own zero or more
lanes admitted from lazy chunks under the database-wide resource budget; there
is no per-transaction lane or waiter-count ceiling in APIs, formats, graph
layouts, or tests. The graph node for that transaction owns the union of all
current lane blocker edges. The initial synchronous SQL runtime uses one lane,
but resource identities, request entries, cancellation, and graph bookkeeping
must accept additional lanes without replacing the lock manager. This permits
future parallel query/DML, pipelined operations, and batched lock-plan
acquisition. Self-edges between lanes of one transaction are ignored. For a
non-head queued request, its blocker is its FIFO predecessor transaction
regardless of lock-mode compatibility; for a head request, blockers are its
incompatible foreign active owners. A transaction's outgoing relations are the
union derived from all its queued lane requests. Mutations mark only affected
resource and transaction records dirty.

An allocation-free iterative DFS walks only reachable relations with retained
primitive frame stores and visit/finish epochs. A gray ancestor identifies the
exact cycle segment; the youngest transaction start order, followed by
transaction identity as a deterministic tie-break, selects the victim.
Admission pre-reserves traversal workspace for the prospective live
transaction count, so detection, grant, cancellation, and release cannot
allocate or fail under resource pressure.

Victim selection marks a retained transaction tombstone, rejects further work
for that identity with `CONFLICT`, publishes a terminal result to every queued
or granted-unconsumed handle, unlinks all of its requests, and releases its
exact holdings so the cycle is actually broken. Survivors are granted and
directly signalled in the same reactive drain. The tombstone remains until the
transaction manager acknowledges terminal completion.

Resource/request/traversal/interval storage grows in reusable primitive chunks
within the database-wide byte envelope, using 64-bit external identities and
checked internal ordinals. Physical envelope exhaustion rejects before
graph-visible enqueue with `RESOURCE_EXHAUSTED`, restores the exact reservation
delta, and leaves no partial queue or graph state; it does not masquerade as a
small SQL or per-transaction capability limit.

Exact blocker enumeration is O(1) for a non-head request and O(number of active
owners) for a head request. Cycle detection is O(Vreachable + Ereachable),
victim cleanup is proportional to that victim's requests and holdings plus
affected grants, and FIFO scheduling is amortized proportional to requests
actually granted or unlinked. Exact and range providers must expose this same
canonical blocker-source contract before mixed exact/range waits are enabled;
an exact-only detector is not permitted to claim safety for a cycle crossing
provider domains.

Tests cover exclusive handoff, maximal shared-prefix grant, no barging,
key/range overlap, collision safety, timeout/grant and cancel/grant races,
deadlock victim/survivor progress, every exact capacity boundary, interruption,
close cleanup, and zero warmed allocation. They also assert that a blocked
thread is not periodically scheduled and that release wakes only eligible
resource waiters. Lock-wait, grant, timeout, cancellation, deadlock, queue
depth, graph-edge, and targeted-wakeup counters are fixed primitive metrics.

### No-arbitrary-limit rule

A numeric boundary is admissible only when it is a mathematical or durable
format addressability boundary, a customer-selected global byte/I/O resource
envelope, or a value derived mechanically from those inputs and selected
concurrency. Internal array length, an easy test fixture, TPC-C's current
shape, or a convenient power of two is not a valid capability limit. River
does not introduce per-table, per-index, per-constraint, per-key, per-query,
per-transaction, per-session, per-lock, per-waiter, per-lane, or wait-for-edge
count ceilings merely to simplify an implementation.

Variable-cardinality state uses reusable lazy primitive chunks and checked
64-bit external identities. A fixed processing quantum may bound one pass or
one I/O record only when work continues transparently across as many quanta as
required; it may not reject valid SQL or select a hidden reduced-capability
path at that count. Memory pressure is controlled by the shared accounted-byte
envelope and preset-derived physical budgets, not by adding one customer dial
per collection. Exhaustion diagnostics identify the real global resource
dimension and ever-fit capacity. Tests cross former small boundaries and
powers of two and prove identical behavior instead of canonizing those values
as maxima.

Conservation is checked in tests and debug diagnostics, but overlapping
dimensions are not added together. For fungible root byte credits:

```text
accounted-byte envelope
  = unallocated byte credits
  + non-borrowable allocated bytes
  + ownerless-reusable allocated bytes
  + live-leased allocated bytes
```

For each typed bank, `allocated units = ownerless units + exclusively leased
units`. Frames separately partition into free, clean, dirty, and staged states.
Pins are checked reference counts over frames, not additional frames, and may
have several read owners. Lock retained bytes, WAL bytes, spill bytes/files,
and owner tickets each have their own conservation equality. A chunk or frame charges
both its typed dimension and its accounted bytes deliberately; those values
overlap and are never summed as if fungible.

The DRY boundary stops at accounting and ownership. Mutation journals, lock
tables, page banks, WAL buffers, and sort/hash pages remain concrete typed
primitive structures; River does not introduce a generic object pool or make
domain code interpret an untyped byte quota. A domain owns measurement of its
real cost, while the one governor owns atomic cross-domain admission and
pressure order.

Lock storage participates through a `river-tx`-owned `LockMemoryBudget` SPI
with exact-delta `reserve(bytes)`, `release(bytes)`, and retained-byte
inspection. The engine implementation owns one authenticated retained-byte
lease in the database governor; standalone transaction tests use a fixed-byte
implementation. There is no lock-count capacity, locks-per-write estimate,
lock percentage, maximum-held-lock journal, or independently tuned lock-memory
dial. `LockSegmentArena` reserves the exact aggregate chunk growth before Java
allocation and releases that reservation on allocation failure. A target that
could never fit returns `RESOURCE_EXHAUSTED`; transient competition for the
shared byte pool returns `RETRY` with no partial arena or directory mutation.
The governor never calls the lock manager while holding its monitor.

Terminal lock release makes whole suffix chunks reusable but does not trim the
warm high water on every commit. Accounted-byte pressure asks the lock manager
to trim ownerless suffix chunks outside the governor monitor and then retries
admission once; database idle/close trims to the bootstrap state and releases
the retained-byte lease. Live holdings, queued or granted requests, graph
edges, and interval nodes never move and are never reclaimed. This removes the
current `lockSlotCapacity`, `LOCKS_PER_WRITE`, `MAXIMUM_LOCK_SLOTS`, and
`IndexedHeldLockJournal` paths instead of restoring compatibility overloads.

Pressure is deterministic and progress-safe. River first reuses ownerless
typed chunks, returns surplus session chunks to database-global banks, spills
spillable operators, evicts unpinned clean frames, and sheds ownerless bank
suffixes. It never reclaims a pin, active lease, lock, dirty frame, or the
non-borrowable progress reserve. If capacity still cannot be granted, local
policy exhaustion returns `RESOURCE_EXHAUSTED`; transient global contention
returns fair FIFO `RETRY`. A session/transaction may have exactly one pending
demand for its authenticated owner generation. The primitive ticket ring is
bounded by maximum session/transaction owners plus explicit checkpoint,
recovery, vacuum, index-build, and other maintenance lanes, not merely maximum
active transactions. Retry, cancellation, and deadline handling cannot skip or
strand the head ticket. WAL pressure preserves
checkpoint/recovery headroom and applies bounded commit admission backpressure;
it cannot deadlock log reclamation. River's commit-time redo protocol streams
bounded continuation records and publishes only a validated final
decision/digest, so an incomplete
transaction remains invisible. It does not copy Ingres's oldest-transaction
victim policy unless a future design begins retaining long-lived uncommitted
WAL and demonstrates that victim selection is necessary.

The durable WAL is a monotonically identified logical stream over bounded
recyclable physical segments: the modern equivalent of Ingres's circular log,
not necessarily one preallocated ring file. Every physical segment use carries
a generation/lap identity. A safe-reuse watermark is the minimum still needed
by crash recovery, checkpoint, live snapshots, backup, vacuum catch-up, and
replica retention. Reuse may advance only after every owner releases that
generation; wrap or generation mismatch fails closed. The in-memory WAL buffer
bank is a separately accounted typed pool and is not confused with durable
retention capacity. Soft pressure requests progress from the retaining owner;
hard pressure stops new commit admission before overwrite.

Logical lock escalation is a future borrower of the same protocol, not an
excuse for undersizing the TPC-C path. It may be planned before fine-grained
acquisition only after hierarchical intent and covering range/table lock
semantics exist. It must preserve predicate, foreign-key, uniqueness, and
row-current protection, acquire coarse locks in canonical order, and expose
its concurrency cost. River does not copy the historical Ingres escalation
threshold of 50 or any other historical numeric value.

### How the automatic policy is validated

"Optimal" is meaningful only for a stated workload, memory/concurrency
envelope, and latency/throughput/spill objective. River will not claim a
universal optimum. Instead, the performance harness sweeps internal resource
splits that are deliberately not user-facing and compares `auto` with the
measured Pareto frontier under the same physical envelope. The calibration
matrix includes:

- TPC-C at one, eight, 32, and the admitted maximum terminal count, including
  maximum-shape Delivery and bursty checkpoints;
- point/range OLTP with many small transactions and a rare large transaction;
- wide-row and composite-key write workloads with unchanged and changed index
  keys;
- in-memory and spilling sort, group, distinct, hash join, nested-loop join,
  and DML candidate collection;
- online index build, bulk load, recovery, vacuum, and WAL-retention pressure;
- uniform, skewed, phase-changing, and mixed foreground workloads at several
  accounted-memory and storage envelopes.

For each family, `auto` must remain within 10% of the best measured throughput
that meets the same correctness, memory, spill-capacity, and p99-latency
constraints, and must not regress p99 latency by more than 20% at its achieved
throughput. A miss changes the shared policy or algorithm; it does not add a
new customer profile without evidence that one observable service objective
cannot be inferred or expressed by the existing physical inputs. These are
nightly/release calibration gates, not timing-sensitive unit tests.

Calibration uses the versioned River reference-host protocol and records the
commit, JDK/JVM flags, host fingerprint, fixed workload seed, physical
envelope, and the finite candidate-policy sweep in its evidence artifact. Each
cell has five warmups followed by at least ten measured runs and 100,000
completed operations; p99 is reported only from a cell with at least 100,000
latency samples. "Best measured" is the highest median throughput among the
recorded candidates satisfying the same hard envelope and latency/spill
constraints, with bootstrap 95% confidence intervals. Overlapping intervals or
run coefficient of variation above 5% make the result inconclusive and require
rerun rather than pass or fail. The 10%/20% limits remain investigation
thresholds until two consecutive reference-host release runs establish stable
variance; only then do they become promotion gates.

Runtime evidence uses allocation-free fixed-bucket counters for requested,
granted, live, ownerless-reusable, peak, reclaimed, spilled, evicted, retried,
and rejected capacity by dimension, plus grant latency, WAL-retention age,
cache hit/miss, and River-owned copies/allocations. Session high-water ownership
shrinks at the first idle boundary by returning surplus whole chunks to the
global bank. The bank retains reusable chunks while inside the root envelope,
but a competing grant or database-quiescent trim sheds unused suffix chunks;
there is no customer-configured retained tier or 64-transaction timer. JVM
committed heap/RSS reduction remains best-effort, while River-owned live and
reusable capacity obeys the envelope exactly.

Every capacity outcome populates stable primitive fields in reusable
`StatusDetail`: resource dimension, local effective ceiling, local live use,
requested delta, global capacity, global available capacity, and retry ticket
when applicable. An ever-fit rejection also reports the maximum grantable
value after hypothetical release of live borrowable leases. Prose is
supplemental. Operations and tests must be able to
distinguish policy exhaustion, transient pool pressure, lock conflict, spill
quota exhaustion, and underlying I/O failure without parsing a message.

The governor is a control-plane boundary, not a row-loop service. A statement
or maintenance step obtains capacity in bulk; warmed row, key, lock, page, and
WAL loops debit caller-owned primitive lease cursors and contact the governor
only on bounded chunk growth or control-plane release. Identity lookup is
O(1)-average in primitive hash indexes, savepoint/net-state rebuild is O(n),
canonical lock ordering is bounded O(n log n), and WAL encode/recovery is
O(encoded bytes). No implementation may hide the present O(n-squared) journal
or lock scans behind the shared API.

### Resource-governance implementation order

1. Add the immutable `DatabaseResourcePlan`, its checked compiler, fixed-field
   `ResourceDemand`/`ResourceLease`, and a concrete database governor. Model
   tests randomize reserve/release/reclaim sequences and prove conservation,
   owner generation, FIFO pressure, and non-borrowable progress invariants.
2. Add the common mutation planner. It collapses repeated base writes, tracks
   first-touch undo per savepoint depth, restores active net tuple heads on
   rollback, suppresses unchanged index/unique/foreign-key work, and measures
   exact entry/key/undo/WAL demand from the same chunk layouts used to allocate.
3. Replace fixed session mutation and lock arrays with lazy primitive chunks,
   primitive O(1) identity indexes, a deduplicated canonical statement lock
   plan, and atomic local/global reservation before the first acquisition.
4. Put transaction-private frames, B-tree split planning, publication
   ordinals, and page pins under the same lease. Reserve the proven
   concurrency-independent split upper bound before locks; refine within that
   bound after revalidation. Rollback and commit cannot require an unreserved
   frame.
5. Replace the 16-record WAL group with streamed bounded continuations and the
   final decision/digest record. Separate runtime policy from durable recovery
   validation and reserve global checkpoint/recovery progress capacity.
6. Move page cache, query materialization, sort/hash/group/distinct, DML
   candidate collection, online index build, checkpoint, and vacuum to the
   governor. Spillable owners share the materialized-page substrate; semantic
   transaction state never silently spills.
7. Delete the replaced low-level properties and constants, add normalized plan
   inspection and metrics, then run functional, pressure, allocation, recovery,
   and automatic-policy calibration gates before A3C-5 promotion.

### Tiered reusable storage

- Mutation metadata, tuple-intent metadata, lock receipts, compilation state,
  page-retention ordinals, and their hash/index side tables grow as bounded
  power-of-two chunks. They do not eagerly allocate their derived ceiling
  and do not copy a monolithic maximum-sized array when crossing a tier.
- Variable row and tuple-key bytes use bounded fixed-size arena chunks. Reset
  rewinds live cursors; it neither allocates nor clears unused payload byte by
  byte. Sensitive bytes are erased when a chunk is released or the owning
  session closes.
- At transaction reset a session keeps its first hot chunk per active typed
  path and returns every surplus whole chunk to the database-global typed bank.
  Repeated TPC-C work re-borrows already allocated chunks and reaches an
  allocation-free steady state without pinning a rare high-water tier to one
  idle session.
- A returned chunk is River-owned reusable capacity, not live capacity. It is
  synchronously reclaimable by a competing grant once it has no active
  transaction, statement, scan, savepoint, lock, borrowed row, staged page,
  WAL reservation, or commit/recovery hand-off. Database quiescence also sheds
  unused bank suffixes. Trimming only handles whole ownerless chunks at that
  control-plane boundary; it never runs in a row, index, lock, WAL encoding, or
  commit publication loop.
- A transaction at its derived or optional policy ceiling receives exact
  preflight admission or an explicit bounded status. Growth allocation failure
  returns `RESOURCE_EXHAUSTED` before external state changes. A failed statement,
  rollback, retry, close, and recovery hand-off all return ownership to the
  same reusable workspace and cannot strand a high tier as live state.
- Database/session metrics expose effective limits, current reusable bytes,
  live entries/bytes, peak entries/bytes, allocated chunk counts, trim-eligible
  chunks, completed trims, capacity rejections by dimension, and River-owned
  copies. Metrics use primitive counters and do not allocate on mutation or
  lock paths.

### Transaction workspace gates

- A deterministic maximum-shape Delivery fixture stages 150 `ORDER_LINE`
  updates plus all ten `NEW_ORDER`, `ORDERS`, and `CUSTOMER` changes with the
  declared primary and secondary indexes. It commits atomically, survives WAL
  replay and checkpoint/reopen, and preserves all TPC-C invariants.
- Local-policy and ever-fit tests run one unit below each
  entry/page/lock/WAL boundary, at the exact boundary, and one above it. An
  impossible request returns `RESOURCE_EXHAUSTED` during preflight, receives no
  ticket, leaves no residue, and the same session can immediately commit a
  small transaction. Page assertions distinguish the concurrency-safe reserved
  upper bound from actual staged-page high water.
- Global-pressure tests first prove that the same demand fits an otherwise
  empty governor, then occupy each resource with another owner. The request
  returns `RETRY` with FIFO owner/generation, succeeds after release, and
  cancellation/deadline removal permits the next ticket to advance.
- Configuration tests cover every physical `auto` input, optional transaction
  ceilings, duplicate/unknown properties, checked-arithmetic boundaries,
  independently deficient resource dimensions, concurrency/progress-reserve
  incompatibility, aggregate admission of several database children under one
  runtime root, and normalized effective-plan reporting. Reopen may use a lower
  runtime policy but recovery validates durable format ceilings and does not
  reinterpret or reject an already durable larger transaction.
- WAL format tests cover one and many continuations, the maximum ordinal/count
  through synthetic headers without allocating that many records, total byte
  and item overflow, missing/reordered/interleaved continuations, digest/final
  count mismatch, duplicate page/root identity through external recovery runs,
  torn final decisions, segment-generation wrap, and reopen below the persisted
  recovery-scratch minimum.
- Allocation tests warm the shared typed banks and then execute at least 10,000
  representative New-Order, Payment, Order-Status, Delivery, and Stock-Level
  statements with no River-owned per-row allocation. A rare large transaction
  returns surplus chunks at its first safe idle boundary; the global bank
  reuses them without allocation and a competing grant/quiescent trim can
  reclaim them without touching live ownership.
- Stress tests use a large physical envelope and small payload per mutation to
  cross the old 384, 1,024, and 65,536 entry boundaries and multiple WAL record
  boundaries. They prove count arithmetic, chunk indexing, lock release,
  rollback, streamed-WAL decision/digest replay, and trim behavior without
  combining a large entry count with maximum-width rows in routine CI.

## Serialized implementation checkpoints

### A3C-0 — format and ownership freeze

Inventory every column mask, fixed-width catalog/result/statistics carrier,
dense row array, 32-bit row reference, checkpoint entry, B-tree value, WAL
field, vacuum loop, and page-cache owner. Freeze versioned encodings,
pin/release lifetimes, reserved catalog namespaces, and corruption bounds
before production edits. Freeze distinct logical-row, row-version, page, and
page-local heap-slot domains; key kind/arity; the uniform all-user-column row
layout; and bounded SQL/metadata/row protocol envelopes.

Gate: byte-exact fixtures cover maximum ordinals; distinct logical/version
sentinels through `Long.MAX_VALUE`; maximum catalog segments; missing,
duplicate, wrong-generation, truncated, and checksum-corrupt records; separate
B-tree leaf/internal entries; and old-format fail-closed behavior.

#### Frozen A3C-0 formats

- Column sets encode exactly the word/byte count implied by their declared
  shape. Encoders reject nonzero trailing bits and readers reserve only the
  bounded caller-owned storage required for that shape.
- Catalog table headers declare key kind (`KEYLESS`, `PRIMARY`, or
  `COMPOSITE`), non-negative `int` arity, column count, payload bytes,
  generation, and typed segment count. Schema and statistics payloads
  occupy nonempty typed continuation segments in catalog sequence space 1.
  The 64-bit continuation watermark, every segment, and the generation header
  publish in one transaction. Table generations require at least one schema
  segment and never assemble after a missing, duplicate, wrong-namespace,
  wrong-generation, checksum, or kind failure.
- Heap rows contain all user columns and the shape-sized null bitmap. Storage keys never
  synthesize SQL column zero. A logical-row directory maps positive-long
  logical identity and table ownership to a positive-long head version. The
  version directory maps positive-long version identity to its prior version,
  commit state, and the full physical locator: positive-long page number and
  generation plus bounded slot number and slot generation.
- Primitive directory B-trees use distinct v3 leaf and internal entry layouts.
  Relational indexes use compact slotted tuple pages with inline canonical
  typed keys, descriptor/schema binding, an encoded-key-bounded component
  vector, and a positive logical-row tie-break. Numeric and temporal components use
  order-preserving fixed encodings; Unicode components use scalar-order
  encoding with the existing 255-scalar VARCHAR maximum. Full-page validation
  owns ordering, uniqueness, fences, sibling state, typed values, compact
  packing, and zero slack before traversal.
- One indexed WAL v5 continuation carries at most 63 complete page images and
  63 root updates, long logical/version watermarks, an exclusive int
  `nextPageId`, and long page generations. Continuation ordinal and final
  continuation count are positive 32-bit fields with a durable maximum of
  `Integer.MAX_VALUE`; total encoded bytes and total item count are positive
  64-bit fields checked against the record-count and record-size product. Thus
  the format ceiling is roughly two pebibytes at the 1 MiB record limit, not a
  small operational transaction limit. Runtime WAL retention normally admits
  far less.
- A transaction reserves one contiguous, non-interleaved logical WAL extent
  and may stream bounded continuations through that extent; there is no
  16-record transaction ceiling. Continuations carry transaction identity,
  ordinal, previous digest, and kind. The final decision declares total
  continuations, items, encoded bytes, minimum recovery scratch, and the whole
  digest chain, and is the only authority to publish roots and watermarks.
- Recovery first validates the committed chain sequentially, using a bounded
  heap window and fixed-width bounded-pass external radix partitions to order
  page/root identities and reject duplicates in O(item count), with a constant
  number of I/O passes independent of transaction cardinality. It then makes a second sequential pass to
  validate every `PageCodec` checksum and database/WAL/record identity before
  publishing any root or watermark. The checkpoint/control authority retains
  the maximum recovery-scratch requirement of untruncated WAL. Lower runtime
  transaction policy does not reject older WAL; if the environment cannot
  provide the persisted non-borrowable recovery minimum, the database-open
  operation fails closed with `RESOURCE_EXHAUSTED` and leaves durable state
  untouched rather than treating valid WAL as corrupt. A torn
  or incomplete chain may leave unreachable private page images but cannot
  publish them.
- Checkpoint manifest v3 is a fixed 1,192-byte authority containing database,
  WAL, commit and transaction watermarks; long logical/version watermarks;
  root page IDs and generations; exclusive `nextPageId`; storage generation;
  and at most 64 ordered dirty extents. It contains no row/version arrays and
  accepts no older manifest version.
- Protocol v4 has three separate canonical network envelopes. SQL text is
  bounded at 1 MiB with up to 65,535 parameters and a 16 MiB packed parameter
  body; query metadata is bounded at 1 MiB; packed result rows are bounded at
  4 MiB. Every envelope carries the canonical bitmap bytes implied by its
  declared shape and rejects nonzero trailing bits. These are admission bounds,
  not retained per-connection buffer requirements.

#### Frozen A3C-0 page ownership

- The A3C-2 cache is a configured bounded primitive `pageId -> frame` map, not
  an array indexed by every possible persisted page. A frame identifies page
  number and generation and is in exactly one of `FREE`, `CLEAN`,
  `DIRTY_CURRENT`, or `STAGED` state.
- A borrowed payload is valid only under a lease containing frame, page, and
  generation. Read leases may coexist; a staged mutation owns the exclusive
  writable frame. Publication never mutates a committed borrowed frame in
  place. Release validates the lease generation, clears the caller's result,
  and only then makes the frame evictable.
- Cursor current rows, committed lookahead, every live subquery or joined
  parent frame, mutation staging, recovery, checkpoint flush, and vacuum copy
  each declare their simultaneous pin demand. Admission reserves that computed
  demand before opening resources; insufficient configured frames returns
  `RESOURCE_EXHAUSTED` without partial cursor, mutation, or publication. There
  is no fixed row-count-derived cache minimum and no eviction of a pinned or
  dirty frame.
- A cursor owns its current and lookahead leases until advance, reset, or
  close. A graph evaluator releases child leases before its parent lease; a
  mutation/recovery operation releases staged leases only after WAL outcome
  and publication or cancellation. Checkpoint and vacuum use bounded batches
  and never retain a pin for every page in the store.

#### Frozen A3C-0 vacuum ownership

- Vacuum runs on a database-owned logical maintenance lane scheduled by the
  runtime root's bounded maintenance executor, never on a transaction,
  protocol, or WAL-writer thread. The executor's threads, OS handles, CPU share,
  and fixed worker state are admitted by the common runtime governor; River
  does not create an ungoverned physical thread per database. Foreground commit
  performs only bounded debt accounting and an idempotent wake-up when the
  controller crosses a trigger.
  Transaction begin never scans, validates, sizes, copies, or publishes vacuum
  work. There is one authoritative maintenance state machine per database:
  `IDLE -> REQUESTED -> BUILDING -> CATCHING_UP -> CUTOVER -> RETIRING -> IDLE`.
  Repeated signals coalesce by source storage generation and debt epoch only
  while work is live; they cannot create parallel vacuums or repeated preflight
  scans, while a later debt epoch on the same generation may request new work
  after cancellation or a low-yield run.
- `IndexedTableStore` owns a generation catalog rather than one mutable set of
  files. Each storage generation owns its page cache/file, row and version
  directories, scalar and tuple roots, logical/version watermarks, and
  exclusive `nextPageId`. A transaction snapshot is the exact tuple `(storage
  generation, immutable root authority, visible commit sequence)` and retains
  that generation/root lease. Read-committed execution may change snapshots
  only at a statement boundary; older snapshots continue on their captured
  roots after cutover. The source generation container remains append-only/COW
  for admitted writers while each captured root snapshot is immutable. A page
  pin alone is not a generation lease, and publication never overwrites a
  committed frame visible through an old lease.
- Triggering is predictive rather than a fixed row-count timer. The controller
  maintains allocation-free moving measurements of version/page debt, free-page
  headroom, update and WAL-delta rate, observed copy/apply rate, reclaim yield,
  oldest pinned-snapshot age, page-cache eviction pressure, commit-queue depth,
  and recent maintenance pause cost. It requests work before the predicted
  completion time reaches the predicted capacity-exhaustion time. Low-yield
  runs raise the next soft trigger; fast debt growth or shrinking headroom lowers
  it. A governed hard reserve changes foreground admission to explicit
  backpressure, but foreground threads still do not execute vacuum work.
- The controller consumes the shared database CPU, page-cache, materialization,
  WAL, and I/O budgets. It does not add independent row-count, sleep, batch, and
  thread-count knobs or workload-labelled profiles. The calibrated deterministic
  `auto` policy derives chunk bytes, duty cycle, and concurrency from the common
  resource envelope, observed foreground queue/latency pressure, and measured
  maintenance rates. Enterprise configuration may raise the common envelope
  without changing the algorithm or exposing a combinatorial set of vacuum
  dials.
- Before `BUILDING`, admission reserves or conservatively proves headroom for
  replacement-generation durable space, retained WAL through catch-up, fixed
  cancellation/recovery progress, chunk frames/buffers/force capacity, and
  already-retired generations. The worker never holds one borrowable resource
  dimension while FIFO-waiting for another required by foreground progress.
  Simultaneous active/building/retired generation bytes are governed together;
  excess old-snapshot retention applies bounded backpressure rather than
  accumulating unbounded complete copies.
- The `REQUESTED -> BUILDING` transition briefly enters the serialized
  publication gateway and atomically captures source root authority, commit
  sequence/high-water, and the first retained WAL position before releasing
  publishers. The build scans exactly that immutable captured root snapshot and
  catch-up replays only later decided commits, leaving no snapshot-to-delta gap.
- `BUILDING` and `CATCHING_UP` are concurrent phases against that captured root
  snapshot while the source generation remains append-only/COW. The worker
  yields at byte/time budgets and reduces its duty cycle when foreground commit
  latency, WAL force queueing, or cache eviction cost rises beyond calibrated
  fair-share bounds. Under capacity pressure it may consume the full admitted
  maintenance share. It never holds the transaction manager monitor, the
  commit-publication authority, or a page latch across a scan, copy, WAL force,
  or wait.
- Every committed user/catalog mutation has one canonical,
  generation-independent logical-redo representation. Live publication,
  recovery, and vacuum catch-up decode that same representation and invoke the
  same kernel mutation primitives; source-generation page images may accelerate
  recovery but are never the sole mutation authority. This includes base rows,
  every tuple/index delta, descriptor/index lifecycle, and schema/catalog
  changes. There is no second vacuum-specific mutation implementation.
- WAL append/publication remains serialized through one gateway. The worker
  reads through an independent reusable direct buffer and owns a retention
  lease plus a durable cursor `(wal generation, byte offset, journal sequence,
  applied commit sequence)`, so rotation cannot remove unapplied deltas.
  Vacuum chunks are independently decided maintenance transactions and may
  interleave with user commits; the existing decisionless vacuum stream and
  its exclusive recovery phase are deleted.
- Vacuum is a resumable storage-generation build. It scans after a durable
  canonical ordered `(space, key, logical row identity)` resume key, writes
  replacement heap/index/directory pages in bounded WAL continuations no larger
  than the 63-page page-image contract, and persists progress through the
  reserved system portion of the rooted catalog. A row ordinal or logical-row
  watermark alone is not a resume position because physical traversal is key
  ordered; restart seeks directly after the last complete key and never
  rescans all preceding chunks.
- A progress generation identifies the source storage generation, replacement
  storage generation, captured source commit sequence, ordered resume key, last
  completely copied logical row for validation, replacement root-directory
  authority, exclusive replacement `nextPageId`, rows copied, versions
  reclaimed, retained WAL cursor, latest observed source commit sequence, and
  the cursor/commit sequence applied to the replacement. A chunk publishes its
  page images and next progress record atomically; replay is idempotent by
  generation, ordered key, and WAL cursor.
- Ordinary writers remain admitted while `BUILDING`. Their WAL records are the
  authoritative bounded delta stream; vacuum advances the replacement through
  that stream in commit order after copying the snapshot row range. Before
  `CUTOVER`, vacuum takes a short publication fence, records the current source
  commit sequence, applies every remaining delta through that sequence, and
  requires `appliedCommitSequence == sourceCommitSequence`. Delta pressure may
  return `RETRY` or apply normal WAL backpressure, but cannot discard a source
  mutation or force a whole-generation write outage.
- A transaction may span cutover. Its staged changes contain only canonical
  logical identities and before/after mutation intent, never source page/root
  locators. At publication, under its retained row/tuple/schema locks, the
  common mutation path resolves and revalidates those changes against the then
  authoritative generation and applies them there. A semantic conflict returns
  the normal precise transaction status; generation change alone never loses a
  write or causes a blind page-image publish. Tests cover cross-cutover insert,
  update, delete, key change, index/FK maintenance, RC statement transition,
  and RR/Serializable transactions.
- Readers remain on the source roots while a replacement is building. The
  final transaction atomically switches the checkpoint/root authority only
  after validating the replacement roots and directories, then makes the old
  generation reclaimable. Cancellation or failure leaves the source
  authoritative and returns an explicit status; unreachable replacement pages
  are reclaimed by generation rather than retained in heap metadata.
- `CUTOVER` is the only admission-disruptive phase. The worker enters it only
  after all full page/tree validation and forceable target work have completed
  incrementally and the remaining delta is within the measured pause byte/time
  budget. It closes commit-publication admission, lets already admitted
  publishers reach a safe boundary, captures and applies the final ordered
  delta, checks only fixed-size root/digest/decision invariants, and switches
  root authority atomically. A pre-force estimate that no longer fits the
  budget reopens admission and returns to `CATCHING_UP`; it does not begin an
  unbounded stop-the-world pause. Once a filesystem force has been issued,
  however, River remains fenced until that non-preemptible operation returns:
  a successful slow force completes the authoritative cutover and records the
  overrun, while an indeterminate force keeps the database fenced for recovery.
  Old generations retire asynchronously after their final snapshot/page lease
  is released. A long reader can delay physical reuse but cannot make the
  maintenance worker repeatedly rebuild the same generation.
- Database construction recovers generation/progress/WAL authority before it
  starts the worker and publishes the database handle. Close rejects new work,
  cancels or completes the current atomic chunk, clears every admission fence
  and lease, joins the worker, and only then closes table, WAL, and directory
  resources. Crash recovery never persists a thread identity: it resumes
  committed progress, discards only unreachable uncommitted target suffixes,
  and clears ephemeral admission state. Explicit `VACUUM` requests this same
  controller and reports its status; it has no synchronous implementation.
- Metrics expose controller state and reason, debt/headroom, source and target
  generations, copied/applied/reclaimed bytes and rows, wake coalescing, yields,
  cutover attempts and pause histogram, foreground backpressure time, failure
  phase, and cleanup outcome. These are fixed evidence dimensions, not control
  dials.

The current synchronous `IndexedVacuum.runAutomatic` implementation violates
this ownership: transaction begin can perform multiple full B-tree validation
and sizing passes before `TransactionManager.commitMaintenance` discovers that
another transaction or lock is active, and the eventual rewrite runs while
holding transaction-manager authority. It is replaced directly by the state
machine above; no synchronous foreground fallback or compatibility path is
retained.

Until that vertical cut lands, the synchronous path is contained rather than
allowed to dominate transactions: soft obsolete-version thresholds do not run
synchronous vacuum. Only the existing hard version-capacity reserve may request
it; known-active transactions cause no preflight or tree/page scan, repeated
deferrals and pressure transitions coalesce, and pressure rejects new admission
until one quiescent begin performs reclamation. Explicit vacuum remains the
manual route. This containment has no threshold or new tuning input and is
deleted, not adapted, when the maintenance controller is wired.

The cutover deletes the old `IndexedVacuum` coordinator/writer/batch/scanner,
same-page shadow publication, vacuum WAL chunk/commit codecs, `VACUUM_APPLY`
recovery phase, transaction-begin maintenance, and checkpoint-triggered vacuum.
They are not retained as fallback or compatibility paths.

Vacuum delivery gates are:

1. Ten continuously active terminals crossing the soft trigger cause one
   coalesced request and one build, with zero page/tree preflight frames on
   foreground transaction stacks.
2. Concurrent writes remain visible through snapshot copy and ordered delta
   catch-up; recovery from every durable phase either resumes that generation
   or discards it while preserving the source.
3. Cutover pause stays within its recorded budget, including an injected slow
   pre-force estimate. Pre-force rejection reopens admission and resumes
   catch-up; a successful slow force completes and records an overrun; an
   indeterminate force fences and resolves through recovery. No path loses or
   duplicates a mutation.
4. A pinned old snapshot delays only old-generation reuse. Soft pressure adapts;
   hard reserve returns bounded backpressure and recovers automatically after
   the pin closes.
5. Repeated warmed runs reuse worker buffers, cursors, page leases, delta
   carriers, and controller state with no per-row or per-chunk steady-state
   allocation.
6. Cancellation, close, I/O error, corruption, resource exhaustion, and crash
   clear or durably fence maintenance ownership on every path; reopen cannot
   inherit a phantom worker, admission barrier, or leaked staged generation.
7. Snapshot copy plus catch-up covers base insert/update/delete, every index and
   constraint delta, descriptor/index lifecycle, and catalog/schema mutation
   through the common logical-redo decoder. WAL rotation retains the worker's
   cursor, and restart seeks directly after the persisted ordered source key.
8. The production tree contains no synchronous automatic/checkpoint vacuum,
   same-page shadow vacuum, exclusive vacuum recovery stream, or obsolete
   behavior-legitimizing tests.
9. Commits injected immediately before, during, and after BUILDING capture show
   no root-to-WAL registration gap, and transactions spanning cutover publish
   through current-generation logical revalidation for every mutation kind.
10. Admission proves replacement/WAL/recovery/chunk/retired-generation
    headroom atomically; long snapshots across successive cycles cannot retain
    unbounded generations, and cancellation releases every reserved dimension.

Vacuum is wired only as one complete vertical cut. Prerequisite types may land
unwired, but production does not temporarily run the old rewrite on a worker:

1. Generation-address page, row, version, and root storage; add immutable
   generation/root/frame leases to transaction snapshots.
2. Introduce canonical logical redo and one decoder/applier shared by normal
   recovery, live publication, and target catch-up.
3. Persist maintenance generation, ordered source resume key, target roots, and
   retained WAL cursor; replace the unreleased manifest directly.
4. Add a deterministic allocation-free controller `step()` state machine and
   the single database-owned thread that drives it under the common governor.
5. Build in ordered-key/byte-budget chunks, catch up every mutation kind,
   execute the bounded cutover decision, and retire by generation lease.
6. Move O(1) debt signaling to successful commit accounting; remove begin and
   checkpoint maintenance; make explicit vacuum request the controller.
7. Delete the complete synchronous implementation, old vacuum WAL/recovery
   format, constructors/counters, and tests in the same delivery.

The principal production ownership changes span `EmbeddedDatabase` construction
and close, `IndexedTableStore`/page-frame ownership, transaction snapshots,
commit/WAL codecs and publication, retained WAL reading/rotation, checkpoint
manifest/control authority, and recovery. The controller owns policy and state;
the generation catalog owns storage lifetime; the serialized WAL gateway owns
append/publication ordering. These responsibilities are not duplicated in
sessions, checkpoint, or SQL execution.

Known test migrations are made up front: old-snapshot vacuum tests expect build
and cutover to proceed while retirement waits; automatic-vacuum tests prove
transaction begin performs no maintenance; pressure tests exercise controller
backpressure; interrupted-vacuum recovery resumes committed generation progress;
checkpoint tests no longer trigger vacuum; old vacuum-codec fixtures become
progress/cursor/root-decision fixtures; and same-page shadow interruption tests
become generation-build/cutover fault tests. New fault coverage injects failure
after each committed chunk, before/after final delta and decision, during WAL
rotation, during retirement, and during every worker close state.

### A3C-1 — broad-shape relational path

Replace single-word masks with bounded column sets; segment catalog schemas;
make parser, DDL/DML, row codecs, projections, results, protocol metadata, JDBC
metadata, statistics, grouping, sorting, and P3 carriers wide-row safe.

Gate: create, checkpoint/reopen, insert, update, index, select, sort, and JDBC
metadata over representative PostgreSQL/MySQL-width shapes and a configured
enterprise shape beyond those defaults; NULL/default/check/reference bits cross
several word and chunk boundaries, including 63/64, 255/256, 1,023/1,024, and
the configured default boundary; retained allocation stays byte-bounded.

### A3C-2 — paged table ownership

Replace `MAX_ROWS`-sized row-location/version/checkpoint arrays and the
all-pages-resident `IndexedPageSet` with long-keyed disk-backed row/version
directories and a bounded, pinned page-frame cache. Directory records use the
final 64-bit logical identity from this checkpoint; they are not built around
temporary `int` keys.

Gate: retained heap is invariant between two tables with the same active
working set but materially different persisted row counts; eviction, dirty
publication, checkpoint, reopen, and injected I/O failure preserve ownership.

### A3C-3 — 64-bit row/version identity

Complete propagation of the long identities through logical row counts,
B-tree leaf values, WAL payloads, recovery, bounded generational vacuum,
scans, and metrics. Internal B-tree child page IDs remain page references;
leaf and internal layouts have distinct versioned codecs. Change unreleased
formats directly rather than adding adapters.

Gate: boundary fixtures cross `Integer.MAX_VALUE` without allocating that many
rows, while a scale run crosses the former 65,536 limit through real insert,
update, delete, checkpoint, recovery, and index access paths.

### A3C-4 — composite and keyless identity

Before the load, admit canonical composite primary/unique/foreign keys and
their exact, leading-prefix, bounded/unbounded range, reverse-order, join, and
DML access paths. Also admit a logically keyless heap table
whose hidden internal row identity is never exposed as a surrogate SQL key;
TPC-C `HISTORY` has no declared primary key. Composite keys use one canonical
typed tuple encoding across catalog validation, indexes, WAL, recovery, and
referential checks.

Gate: primary-key widths one through 32, the required composite foreign
keys, the nonunique ordered customer-name key, and keyless duplicate HISTORY
rows survive insert/update/delete, conflict, rollback, checkpoint, and reopen.

Index scans use one descriptor-native contract with caller-owned encoded bounds,
explicit inclusivity and direction, logical-row identity separate from projected
SQL values, MVCC base-row recheck, and a read-your-writes merge over bounded
session-owned intent ordinals. Leaf pages carry reciprocal sibling links and a
cursor copies admitted bounds once into owned scratch; no scan retains mutable
encoder buffers or singleton probe state.

CREATE INDEX is a durable online bulk build, not a long exclusive transaction
performing random inserts. It registers WAL delta capture before its snapshot,
writes checksummed external-sort runs under an explicit memory quota, bulk-packs
private leaf/internal pages, persists forced progress watermarks, catches up
deltas through a short final fence, and atomically publishes READY. Startup
resumes valid BUILDING work and cleans only cancelled, corrupt, or already
published residue. Progress, run buffers, merge fan-in, WAL retention, and
active builds are all bounded with explicit pressure status.

### A3C-5 — one-warehouse load

Set a measured default/configured store-wide capacity sufficient for the full
one-warehouse population and at least 1,048,576 physical row/version entries,
add reclamation headroom, and load the unmodified one-warehouse TPC-C schema
and initial data. Complete the required transaction workspace and retention
addendum in the same checkpoint; A3C-5 cannot promote on load-only evidence.

Gate: the standard load has `STOCK` exactly 100,000 rows and `ORDER_LINE`
exactly equal to the sum of the 5–15 line counts declared by its 30,000 orders.
All declared keys and references validate; checkpoint/reopen and consistency
counts agree; no cache or retained-memory bound scales with table cardinality.
A separate deterministic fixture crosses 300,000 rows and the configured
store-wide capacity remains at least 1,048,576 physical row/version entries.
The maximum-shape atomic Delivery, typed-bank growth/idle reclaim, enterprise
boundary, allocation, streamed-WAL replay, and clean exhaustion gates in the addendum all
pass before this checkpoint is complete.

### A3C-6 — competitive throughput and scale-out

Remove the one-warehouse assumption from the generator, loader, transaction
inputs, invariants, recovery identity, and evidence. Terminals have stable home
warehouse/district ownership. Multi-warehouse runs use the standard 1% remote
New-Order supply selection and 15% remote Payment customer selection; their
cross-warehouse writes remain one atomic transaction. ITEM is loaded once and
all other warehouse-owned data is generated from the shared deterministic seed.

The load path and steady-state path are measured separately. Loading may use
bounded multi-row requests and parallel warehouse partitions, but it may not
change the resulting logical rows or omit normal constraint/index maintenance.
The measured transaction path reuses prepared plans and parameter/result
carriers, does not parse or allocate per statement/row in steady state, and
does not require one network request/response turn for each dependent SQL
statement. Protocol pipelining or an engine-side transaction program must keep
transaction ownership, cancellation, retry classification, result ordering,
and bounded admission explicit; a benchmark-only bypass is not acceptable.

#### Highest-impact performance delivery order

The first measured-only two-second diagnostic window on 2026-09-01 completed
26 transactions (13 engineering TPS) in the tiny nonstandard 100-item,
10-terminal stress shape. It is a discovery profile, not competitive evidence
or a steady-state projection. It nevertheless establishes four categorical
defects:

- JFR sampled 40.4% of Java CPU in automatic-vacuum B-tree validation and row
  scanning. Foreground transaction begin repeatedly performs that work although
  concurrent activity normally prevents publication.
- The same window threw 82 `NOT_OWNER` and 37 `DEADLOCK` JDBC failures. Sixty-eight
  `NOT_OWNER` outcomes were `new-order.advance-district` after a successful
  `SELECT ... FOR UPDATE`, despite one terminal owning each distinct district.
  Thirty-two `DEADLOCK` outcomes were `payment.update-warehouse`, although every
  Payment takes the sole warehouse in the same order and should form a FIFO
  convoy. These are lock-lifecycle/deadlock correctness defects, not acceptable
  contention retries.
- A subsequent exact measured window after automatic soft-trigger vacuum was
  removed completed 31 transactions (15.5 TPS), with 23 retries and 1,899
  protocol requests. JFR recorded 276 file forces: 275 against `river.wal`
  totalling 1,098.875 ms (3.996 ms mean) and one indexed-page force. Stack
  attribution showed that 274 forces passed through
  `RelationalDescriptorRowStore.reserve`: each INSERT statement opened and
  committed a second SERIALIZABLE transaction merely to reserve logical row
  IDs. New-Order therefore performs roughly 7--17 allocator commits and forces
  in addition to its real transaction, including forces for attempts that later
  abort. This allocator transaction and its database-wide monitor are the
  largest measured throughput defect and must be deleted.
- Completed attempts used 26.05 protocol exchanges on average. This is lower
  than the previously reported retry-amplified 96 exchanges per commit but
  remains structurally incompatible with the target. The benchmark had also
  retried non-retryable `NOT_OWNER` because JDBC grouped it under SQLSTATE
  `40001`; retry classification must use River's stable status code.

The benchmark now opens connections and prepares statements before its JFR
window, starts all terminals behind one measured barrier, stops profiling at
the common cutoff, and excludes transactions that complete after that cutoff.
JFR allocation samples remain discovery weights rather than object-size
measurements; exact warmed allocation gates use thread byte counters and exact
allocation events separately.

Every performance improvement is an evidence-bearing delivery. Before starting
the next improvement, publish in the working thread and append here:

- the revision/worktree state and exact reproducible command, workload manifest,
  host/JVM/storage configuration, warmup, measurement interval, and whether the
  point is diagnostic or qualifying;
- primary before/after TPS runs without a profiler, plus separate paired profile
  runs with identical JFR settings; profiler-on TPS is reported only as profile
  context because event-volume changes can change profiler overhead and JFR I/O
  can contend with WAL I/O;
- a predeclared paired method using a fresh copy of the same loaded-image digest
  for each sample, alternating AB/BA order, a fixed sample count and stopping
  rule, all runs and declared outliers, and the median delta and confidence
  interval;
- the unchanged correctness, isolation, durability, invariant, close/reopen,
  and recovery gates that passed, plus any broader suite not completed or not
  green;
- comparable before/after committed TPS and per-family throughput/latency,
  commits, expected rollbacks, whole-transaction retries by stable River status
  and statement, protocol requests per commit and attempt, and confidence data
  when the run is long enough to support it;
- retry counts by stable status, family, statement label, attempt number, proven
  deadlock versus ordinary wait/conflict, exhaustion, and backoff time; the gate
  fails if a non-retryable status is retried, and SQLSTATE alone is not a stable
  classification;
- one common measurement/cutoff domain for commits, attempts, requests,
  latency, allocation, and profile events, including started, completed, and
  in-flight-at-cutoff counts and separately reported bounded drain work;
- CPU/on-CPU and off-CPU profile attribution, logical-lock wait/grant/deadlock
  counts and time, WAL records/bytes/forces/cohort size and force time, page and
  socket I/O, exact warmed whole-process allocation/operation split by terminal,
  server, WAL, vacuum, and other thread role, sampled allocation sites,
  direct/native memory, governed retained high-water, and GC count/pause/
  allocation rate; moving work or allocation off a terminal thread is not an
  improvement;
- cutoff maintenance debt and bounded drain cost: WAL bytes retained, dirty and
  obsolete page/version counts, vacuum/floor/maintenance backlog, database and
  temporary-file bytes, drain duration, checkpoint time/bytes/forces,
  reopen/recovery time and bytes replayed, and post-recovery resource
  conservation; asynchronous work moved past the measured cutoff is not a win;
- engine/provider counters, corroborated rather than replaced by JFR, for force
  reason, cohort commits, WAL records/bytes appended and forced, queue/append/
  force/publication latency, direct fallback reason, and in-flight force at
  cutoff and after drain;
- Slopmark before/after scores for changed production classes, with every new
  class below 50, the tool version, touched/new classes and package total, and
  an explicit statement of whether any score increased; splitting a class does
  not manufacture a claimed improvement;
- complete provenance: Git revision and dirty-diff digest, benchmark/artifact
  schema versions, Gradle/JDK/JVM/heap/GC/JFR settings, River's effective
  resource plan and cache/page/WAL/group-delay/vacuum/lock-timeout values,
  provider/force/replication settings, host CPU/memory/filesystem/device/power/
  free-space state, competing load, command/environment, and hashes of captured
  stdout, stderr, profiles, and result artifacts;
- the acceptance decision and the next largest measured bottleneck, without
  claiming causality from correlated counters alone; and
- confirmation that profiles, databases, Gradle caches created specifically
  for the run, and other temporary artifacts were removed after the evidence
  was extracted; close statuses, no owned live threads/open files, resource-
  governor conservation, and no undeclared files below owned temporary roots
  are part of the proof. Declared evidence artifacts are hashed and retained in
  the evidence destination rather than mistaken for leaks.

Diagnostic windows may deliberately be short when a gap is categorical, but
their TPS and latency are labelled directional and never substituted for the
qualifying sample gate below. A delivery is not credited merely because a local
counter improved: it must preserve correctness and show the end-to-end effect,
including a neutral or negative result. Before/after comparisons use the same
manifest and environment; otherwise both results are published but no speedup
ratio is claimed.

Each improvement predeclares its causal mechanism and permitted side effects.
Its decision is `ACCEPTED` only when correctness and recovery are green, that
mechanism is achieved, and paired evidence excludes a material regression;
`REJECTED` for a correctness failure, missed mechanism, resource leak, or
material regression; and `INCONCLUSIVE` when sample size or confidence cannot
separate improvement from regression. Byte, allocation, force, and request
totals are normalized per attempt, commit, and family. Latency reports both
logical end-to-end time including retries/backoff and individual-attempt service
time, with censored/in-flight transactions visible.

Durability-path deliveries additionally run abrupt-crash/fault fixtures at the
changed pre-force, force, post-force/pre-publication, decisionless suffix,
checkpoint-manifest installation, WAL-removal, and second-reopen boundaries as
applicable. A graceful close/reopen and the current partial TPC-C logical digest
are necessary but insufficient; physical page/index/catalog validation and
changed-path recovery assertions are mandatory. If measurement instrumentation
changes, rerun the baseline with that instrumentation or corroborate it from an
independent lower-level source.

Work is ordered by measured impact and correctness dependency, not
implementation convenience:

1. **Delete allocator-only transactions and make logical row allocation part of
   the original durability decision.** The database owns a per-table primitive
   exact-range issuer; warmed allocation is an O(1) CAS with no global monitor,
   catalog access, lock, heap version, or force. Each transaction retains a
   primitive, resource-accounted max-next floor per table. A nonempty floor
   journal makes scalar/keyless DML relational WAL work as well as indexed DML,
   so every committed row and its floor share one decision. The relational WAL
   format carries one `(table object ID, next logical row ID)` floor per touched
   table. Live publication and recovery call one shared max-publication path
   only after the transaction is durably decided and its page/version state is
   publishable; abort and decisionless/torn WAL never publish. Reversed commits
   are safe because publication is `max`, while process-local aborted ranges
   remain gaps and may be reused only after a crash in which they were never
   made durable.

   Checkpoints stream the committed floors, sorted by table ID, into a bounded
   immutable two-slot generation. The checkpoint manifest version atomically
   binds the page generation, floor slot/count/bytes/digest, database
   incarnation, WAL generation, checkpoint ID, and covered commit sequence.
   Page/version and floor generations are written and forced before the
   manifest is atomically installed; obsolete WAL is removable only afterward.
   Missing, corrupt, or mismatched named floor state fails closed, and an
   indeterminate post-force publication fences for recovery. Open loads
   checkpoint floors into both issued and published state before replaying the
   WAL suffix. Durable hi/lo leases, a transactional watermark row, and a block
   size tuning dial are explicitly rejected: they retain force/serialization or
   merely amortize it.

   Delete `RelationalDescriptorRowStore`, `LogicalRowIdAllocator`, the allocation
   watermark format/catalog row and their lifecycle wiring in the same format
   cut; do not retain a compatibility reader. Gates cover concurrent disjoint
   ranges, keyless/indexed/batch inserts, savepoint/abort gaps, reversed commit
   order, exhaustion, torn/decisionless WAL, checkpoint rotation with old-WAL
   removal, corruption, and a force-count assertion proving there is no
   allocator-only force.

   Delivery evidence, 2026-09-01: the allocator transaction, catalog watermark,
   compatibility path, and allocator-only force were deleted. The focused
   format/engine suites passed 114 tests covering relational row storage,
   transaction/WAL integration, checkpoint control, and logical-row-ID
   generations. The real tiny TPC-C lifecycle path passed schema/load, all
   invariants, checkpoint, clean close/reopen, and recovery verification. Its
   profiled two-second diagnostic window moved from 31 commits/15.5 TPS to 48
   commits/24.0 TPS (an observed 1.548x), but produced 45 whole-transaction retries and
   3,586 protocol requests (74.708/commit, 38.559/attempt), so it remains an
   unaccepted performance point. This is directional profile evidence, not a
   credited A/B speedup: removing 233 JFR force events also changes profiler
   overhead, and the then-current worker-allocation counter did not cover
   server/WAL threads or align its drain cutoff with JFR. JFR recorded 43 ordinary transaction/group
   forces totalling 164.170 ms versus 276 forces totalling 1,098.875 ms before;
   allocator-attributed forces fell from 274 to zero. It also recorded 159
   thrown `SQLException` objects, all caused by 81 underlying `DEADLOCK`
   outcomes: 70 at `new-order.insert-line`, seven at
   `delivery.update-customer`, one at `payment.update-warehouse`, and the second
   exception at each failing call site was benchmark context wrapping rather
   than a second engine outcome. There were 23,194 parks totalling 15.9 thread
   seconds, 614 socket reads totalling 860 ms, four GCs, and 55,569,576 bytes
   allocated by worker threads. Allocation samples attributed 79.23% to
   `LockServiceWaits.await`; CPU samples were led by tuple-key comparison and
   decoding, pending-mutation lookup, and repeated value/tuple validation. New
   and materially changed logical-ID/checkpoint classes scored 0--27.5248 in
   Slopmark, with all new classes below 50. The affected focused suites were
   green; a broad `river-engine` suite run was interrupted after more than two
   minutes in `SqlDescriptorIndexBackfillTest` with no failure emitted, so it is
   not represented as complete evidence. The profile file was removed after
   extracting these results. The causal allocator-force invariant and focused
   correctness gates passed, but the comparative performance decision is
   `INCONCLUSIVE` because this was one profiled discovery sample with incomplete
   allocation and cutoff domains. Its end-to-end result is plainly insufficient;
   the next delivery must first explain and remove the false deadlock/retry path
   before protocol amplification is credited.
2. **Repair false lock outcomes before performance tuning.** Add deterministic
   TPC-C-shaped tests for distinct-district `FOR UPDATE` followed by UPDATE and
   the same-order warehouse FIFO convoy. A successful wait retains canonical
   source/base ownership; only a proven wait-for cycle may produce `DEADLOCK`,
   and cleanup may not replace that outcome with `NOT_OWNER`. Delete SQLSTATE-only
   retry classification and report stable status by family and statement.

   Lock-current delivery evidence, 2026-09-01: READ COMMITTED and REPEATABLE
   READ point, partial-composite, range, full, and ordered/materialized
   candidates now acquire the descriptor logical base row as their sole row
   identity and re-evaluate the compiled predicate against the protected
   current image. Exact index-source locking was removed from this path rather
   than retained as a compatibility route; serializable index protection
   remains a separate predicate-lock concern. The ordered path now skips a
   materialized candidate which ceased to qualify and continues toward the
   SQL LIMIT. UPDATE, DELETE, and `SELECT FOR UPDATE` call the same scanned
   candidate operation. Obsolete duplicate point/scanned method aliases and
   the unused exact-source candidate validator were deleted. TPC-C Payment now
   acquires and updates warehouse, district, then customer in the standard
   order, verified by a JDBC operation-order test.

   The initial focused gate passed 15 engine tests across composite FIFO handoff,
   point/range/ordered access, current predicate changes, source reassignment,
   delete/reinsert, and keyless rows, plus the Payment-order test. The identical
   unprofiled tiny lifecycle command passed load invariants, post-run
   invariants, checkpoint, close/reopen, and recovery verification at 64
   commits/32.0 TPS, nine whole-transaction retries, 4,120 protocol requests
   (64.375/commit and 55.676/attempt), and 38,716,328 worker-allocated bytes.
   The immediately preceding exact-source sample was 59 commits/29.5 TPS, 61
   retries, 5,356 requests, and 53,750,240 worker-allocated bytes. This is
   strong directional evidence that the artificial lock cycle was removed,
   but the comparative throughput decision remains `INCONCLUSIVE`: both are
   single two-second discovery samples rather than the paired qualifying gate.

   A separate two-second JFR diagnostic passed the same lifecycle and recorded
   62 commits/31.0 TPS, one Delivery `deliverLines` deadlock retry, 3,908
   requests (63.032/commit), 35,862,952 worker-allocated bytes, 56 file forces,
   14,585 parks totalling 11.9 thread-seconds, and three GCs. CPU samples remain
   led by tuple comparison/validation and pending-mutation lookup; whole-process
   allocation is dominated by server-side connection work and therefore is
   not represented by the worker-only counter. All temporary JFR files were
   removed. Slopmark's maximum touched production score is 43.6493, the known
   `RelationalDescriptorTableAccess` score decreased from 26.0558 to 25.036,
   and no new production class was introduced at that measurement point.
   Subsequent adversarial stale-candidate tests exposed that page publication
   overwrites a pinned tuple leaf: delete/key movement can compact the cursor's
   ordinal and lose the following candidate. The apparent lock-path improvement
   is therefore not yet a deliverable. Correctness decision: `REJECTED` pending
   the immutable page-generation gate above. Qualifying performance decision:
   `INCONCLUSIVE`; the 32.0 TPS sample remains diagnostic evidence only and must
   be rerun after correctness is green.
3. **Replace foreground synchronous vacuum with the dedicated concurrent
   maintenance state machine above.** The gate is zero vacuum scan/preflight
   samples on transaction stacks, one coalesced build per source generation,
   bounded cutover pause, and preserved recovery/corruption validation.
4. **Make WAL force grouping real and observable.** A database commit writer
   owns admitted byte/count/deadline cohorts, forces the minimum durable record
   sequence once per cohort, and publishes decisions in order. Report force
   reason, cohort size, records/bytes, queue delay, force duration, and direct
   fallback. Vacuum uses the same writer and cannot create a per-chunk force
   storm outside its admitted maintenance share.
5. **Make prepared statements compiled immutable plans.** A retained handle
   currently resolves to SQL text and execution reparses, reauthorizes, rebinds,
   and replans every invocation. Replace it with immutable parsed/bound physical
   IR containing catalog identities, typed parameter slots, authorization
   result, access/join choices, and catalog generation. Session-owned reusable
   execution state supplies values and cursors. A generation mismatch performs
   one explicit recompile; text execution is not the steady-state fallback.
6. **Remove synchronous protocol amplification.** Protocol v4 uses byte-bounded
   packed row batches whose open response carries the first batch and EOS closes
   exhausted cursors, then a generic compiled transaction-program envelope with
   typed slots, ordered results, bounded admission, and stop-on-failure semantics.
   It contains no TPC-C-specific operations. The target steady path is one
   request per committed transaction; exceptional streaming or payload bounds
   may require a small recorded number of continuations.

   The program envelope is a three-operation session-owned lifecycle, not a
   graph retransmitted with every invocation. `PREPARE_PROGRAM` carries one
   frozen graph, rebuilds all derived topology through the public builder,
   validates every referenced statement/action/parameter shape before any
   transaction begins, retains authoritative plan references, and returns an
   opaque stale-safe program handle. `EXECUTE_PROGRAM` carries only that handle
   plus dense typed arguments; `CLOSE_PROGRAM` releases the graph and its plan
   references. Prepared statements referenced by a program cannot close first.
   All session resources use one O(1), process-unique opaque-handle directory,
   so foreign, stale, and wrong-kind handles cannot alias and there is no
   configured statement/program count ceiling. Growth is byte-budgeted and
   returns resource pressure rather than imposing a small cardinality limit.

   Graph copying, protocol decoding, and embedded registration rebuild through
   one canonical program builder and freeze validator. Statement safety is one
   plan-owned whitelist of transaction-contained query and DML kinds; protocol,
   client, server, and executor do not reproduce command-kind policy. Typed
   arguments, internal captured values, command projections, scan rows, and
   wire result cells share one primitive value representation and copying
   policy. Singleton actions use a reusable point-result carrier rather than
   materializing a general row-set pipeline; true row sets retain the block
   execution path and encode from its canonical value layout. Neither route
   introduces a second SQL executor or a per-row allocation path.

   Result bytes and response-buffer ownership are admitted before commit, so a
   successfully forced transaction cannot subsequently be reported as failed
   because encoding needs memory. A transport loss after durable commit is an
   indeterminate response, never permission for an automatic whole-program
   retry. Program preparation, execution, and close are independently bounded
   by the existing logical connection byte envelope; there are no TPC-C-shaped
   step, argument, capture, or result-count constants.

   Program-registry implementation evidence, 2026-09-02: embedded registration
   now deep-copies and canonicalizes the graph once, retains each prepared plan
   once, validates plan/action and parameter shape once, and executes directly
   from retained plans. Resetting the caller graph cannot mutate the registered
   program, and referenced prepared handles remain protected until program
   close. A shared process-unique primitive handle directory provides O(1)
   foreign/stale/wrong-kind rejection. Command projections and scan rows use one
   reusable typed-value copy path. Focused registry, rollback, numeric, and
   handle tests pass. Current Slopmark scores are 20.0575 for the executor,
   0 for the shared value path, 48.2613 for step execution, 46.755 for scalar
   evaluation, 13.3678 for retained program ownership, 5 for the registry, and
   0 for the handle directory.

   Remote-program evidence, 2026-09-02: protocol v4, the ordered client, and the
   server endpoint now implement the same prepare/execute/close lifecycle. A
   real loopback program containing an `UPDATE` followed by a typed `SELECT`
   completes through exactly one execute request and one serializable engine
   commit; no SQL text, parse, bind, or plan lookup crosses that invocation
   boundary. Program close releases its prepared-plan pins, and foreign-kind
   handles remain rejected. The execute encoder derives its dense argument
   count from the argument arena rather than accepting a second conflicting
   count. Exact result wire capacity is allocated through a generic result
   publication-admission callback before commit; admission failure aborts the
   transaction and leaves zero effects. The client, protocol, engine, and
   server focused suites pass. Continued results larger than one physical
   frame retain the one-request invariant, are admitted against the current
   grown response target before commit, and reassemble without per-frame
   dispatch. Caller-side result-memory exhaustion is reported as resource
   pressure rather than corrupt wire data after the complete response has been
   drained; request ordering remains synchronized and an exact-once follow-up
   observes the committed effect. Command steps now carry generic inclusive
   affected-row contracts; a violation aborts the entire program before any
   later step or commit. `TransactionProgramResult` was split from a
   69.6172 Slopmark score into result, metadata, and lease-coordination classes
   scoring 5.35195, 0, and 7.68026 respectively; every new remote/admission
   class is below 11. Program request and result workspaces now share a
   physical-frame-derived two-tier retention policy: live values are always
   scrubbed after publication, ordinary warm capacity is retained, and larger
   bursts are shed. Server sizing is phase-based and includes every
   connection's two program warm ceilings plus the authoritative rounded graph,
   validation, argument, result, request-assembly, and response-buffer peak for
   one bursting connection. Reconnect tests return decoder charges exactly to
   baseline. All-five-family one-request evidence remains a gate, and no TPS
   improvement is claimed yet.

   The five-family mapping must not hide missing relational/program features.
   Customer-by-last-name selection requires a parameterized ordered offset
   (`LIMIT 1 OFFSET ?`, with the offset computed from the count result) so
   Payment and Order-Status choose the standard lower median inside the same
   transaction. Bad-credit Payment requires canonical numeric-to-text casts,
   concatenation, and bounded substring/left semantics in the shared scalar
   rules; Java-side reconstruction after commit is not acceptable. Command
   steps carry explicit affected-row predicates rather than relying on harness
   assertions after commit, including exact-one mutations and Delivery's
   expected order-line range. New-Order uses one reusable program shape per
   admitted line count, while Delivery uses forward empty branches for its
   district blocks; neither introduces a TPC-C opcode or an engine loop bound.
   These generic capabilities, their SQL/prepared-plan forms, and full
   numeric/text/null tests are prerequisites for claiming all five families.

   First-row/template delivery evidence, 2026-09-02: protocol v4 now returns the
   first row with query-open and closes an exhausted cursor reactively; immutable
   prepared syntax retains actual-count root and physical query-block shapes,
   including the Stock-Level aggregate join, and invocation values materialize
   through one primitive parameter frame. A logarithmic pending-mutation index
   replaced repeated reverse lookup and quadratic ordered enumeration. Focused
   gates passed the exact prepared-template suite, 17 pending-mutation/index
   tests, and all four JDBC TPC-C acceptance cases covering the five transaction
   families, warehouse batching, composite deadlock handoff, and a short lock
   timeout which leaves the explicit transaction usable.

   The identical unprofiled two-second lifecycle completed 61 transactions at
   30.5 engineering TPS, five whole-transaction retries, 2,164 protocol requests
   (35.475/commit and 31.824/attempt), and 2,833,800 worker-allocated bytes. The
   clean pre-slice point was 57 commits at 28.5 TPS, nine retries, 4,199 requests
   (73.667/commit), and 37,305,048 worker-allocated bytes. This is a 1.070x
   throughput observation, not a qualifying speedup; request amplification fell
   51.8% and the bounded worker counter fell 92.4%, but wall throughput barely
   moved.

   A separate two-second JFR diagnostic completed 67 transactions at 33.5 TPS,
   three retries, and 2,176 requests (32.478/commit). It recorded 62 WAL forces
   totalling 234.915 ms (3.789 ms average), so near-one-force-per-commit is a
   measured hard ceiling below the 500 TPS goal even before other work. CPU
   samples were led by raw tuple comparison (15.28%) and repeated tuple shape,
   structure, and value-domain validation; 9,768 parks totalled 12.0 thread
   seconds and 610 recorded socket reads totalled 2.06 seconds. The next ranked
   deliveries are therefore real observable commit grouping, the generic
   one-request transaction program, and a generation-bound validated tuple view
   that removes repeated trusted-key validation without weakening persisted-byte
   admission. The JFR and all River/JUnit scratch artifacts were deleted after
   extraction.

   Reactive group-writer evidence, 2026-09-02: every mutating session commit now
   enters one database-owned intrusive FIFO, arrivals may join the next cohort
   while the current cohort is forcing or publishing, and waiters are unparked
   on completion rather than polling. Six focused insert/update/delete,
   resurrection, fault, and recovery cases pass; four simultaneous independent
   transactions recover after one force. All four new group-writer classes score
   0 in Slopmark. The identical unprofiled two-second lifecycle then completed
   69 transactions at 34.5 engineering TPS, with two retries, 2,378 protocol
   requests (34.500/commit and 33.028/attempt), and 3,877,472 worker-allocated
   bytes. Against the immediately preceding exact 30.5 TPS point this is only a
   1.131x directional gain. It confirms that grouping alone cannot overcome the
   current request-per-statement path: the one-request transaction program is
   the next throughput-critical vertical slice. The result is diagnostic, not a
   qualifying performance claim.

Independent synchronization work remains evidence-driven. `IndexedTable`
still serializes broad operations, and transaction completion can retain
transaction-manager authority across participant work and WAL force. Replace
those global regions with pinned page frames, page-local latches,
latch-coupled traversal, an atomic transaction publication epoch, and the
bounded commit-writer queue. Delete the global synchronized publication path;
it is not retained as a fallback. Before promotion, evidence must distinguish
CPU, Java-monitor wait and synchronized-body CPU, logical lock wait/grant,
vacuum phase, WAL force/cohort, page I/O, socket off-CPU time, parse/bind/plan,
and exact warmed allocation at 1/2/4/10 terminals.

The current MariaDB evidence (1,169.75 and 1,336.63 TPS) used a smaller
1,000-item/8-worker harness profile and is not an apples-to-apples River result;
only the identical manifest below is competitive evidence.

Gate: publish no-wait 45/43/4/4/4 scaling points at 1, 2, 4, 8, and then
successively doubled warehouses until either 1,000 committed transactions per
second is sustained or the admitted reference-host maximum is reached. Each
point uses ten terminals per warehouse unless a separately recorded terminal
sweep establishes a better standards-valid mapping. A qualifying point has at
least five warmups and ten measured samples, at least 100,000 completed
transactions per sample, zero unexpected failures, all invariant/recovery
checks passing, and a 95% confidence interval whose lower bound is at least
1,000 TPS. Report whole-transaction retries, expected rollbacks, allocation,
WAL bytes/forces, protocol exchanges, plan-cache hits, lock wait/conflict
counts, CPU, storage I/O, and latency per family.

Run the identical semantic manifest against River, PostgreSQL, and MariaDB.
Comparison claims require the same scale, seed, mix, terminal scheduling,
requested isolation, acknowledged durability, warmup/measurement windows, and
host. Differences such as group-commit policy or database-specific schema/index
DDL remain visible evidence dimensions. If River misses the target, profiling
must attribute the gap among admission/locking, execution/planning, protocol,
WAL force/group commit, page/index work, and allocation before changing policy.
MariaDB is the primary competitive reference: River's median committed TPS
must be at least 80% of MariaDB's at the same qualifying point, while River's
p99 per-family latency is no more than 20% worse for any family with sufficient
samples. This relative gate is additional to the absolute 1,000 TPS gate.
Expected rollbacks and serialization retries are not commits, and neither
system may disable synchronous durability, declared constraints/indexes, or
standard remote transaction behavior for the comparison.

## Non-goals for this slice

- Claiming official TPC-C compliance or reporting `tpmC`.
- Removing explicit disk, WAL, cache, transaction, or statement bounds.
- Increasing the 8,192-byte physical row limit without an immediate workload
  consumer.
- Preallocating memory for maximum-width shapes or billions of rows in ordinary narrow
  sessions.
- Compatibility adapters for unreleased River catalog, WAL, page, or protocol
  formats.
