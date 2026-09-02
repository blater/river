# Materialized query store capacity plan

Status: implementation in progress

## Objective

Remove the artificial materialized-query-store ceilings and make the store
usable for result sets at the scale of the indexed table path. A materialized
store must not reject a query because it crossed 65,536 rows or 256 MB. Its
payload, row metadata, sort order, and encoded tuple keys are disk-backed. One bounded,
configurable page pool per opened database holds page-cache frames, active
sort-run memory, and the optional resident hash index.

The physical filesystem, the JVM address space, and `long` arithmetic remain
real resource boundaries. There is no product-defined materialized row-count
or materialized-byte quota in this slice. A representation overflow returns
`RESOURCE_EXHAUSTED`; a filesystem failure returns `IO_FAILURE`; malformed or
corrupt scratch bytes return `CORRUPTION`. Every terminal query outcome makes a
best-effort attempt to close all scratch handles and delete all query-local
files.

## Scope

The implementation owns these SQL components and their direct count/position
callers:

- `SqlBlockRowStore`, `SqlBlockRowIndex`, and `SqlBlockRowIndexStorage`;
- `SqlSortWorkspace` and `SqlSortSpill` for ordinary ordered scans;
- `SqlActiveScanState`, `SqlSortExecution`, `SqlScanPreparation`, and the block
  scan path in `SqlQueryExecution` where they carry materialized counts or
  positions;
- the two alternating stores in `SqlBlockPipelineExecution`;
- `SqlJoinHashWorkspace` and `SqlJoinMergeRightRows` when they materialize
  candidates;
- startup configuration, database/session ownership, and open diagnostics.

The implementation may add concrete package-private materialized-file,
page-pool, and external-order helpers used by these consumers. It must not add
an interface without a second implementation or an ownership boundary that
requires one.

### Compatibility with the next width milestones

This slice must not encode the historical eight-column or eight-join limits into a
new shared API or scratch format. In particular:

- materialized file, index, page, and run headers contain no column-count or
  join-role-count maximum;
- a materialized row codec records column count as a checked `int` and uses a
  variable-width null bitmap, not one `long` null mask;
- materialized index and run entries reference arbitrary encoded tuple keys
  with checked `int` arity, length, per-part direction, and null-order policy;
  they do not contain one numeric/text key field or cap tuple arity at index
  key arity;
- codec scratch arrays are sized once at operator `begin` from the validated
  schema and are reused, rather than being sized from
  `TableSchema.MAXIMUM_COLUMNS`, `CommandResult.MAXIMUM_COLUMNS`, or a literal
  eight;
- page-pool owner identities and external-sort ordinals do not encode a join
  stage or role in a narrow field; and
- stable ties use the original materialized row ordinal (or an explicit
  internal logical row ID where a reload adapter requires one), never an
  assumed scalar user primary key; and
- join workspaces may still be instantiated according to the current planner,
  but the materialization changes add no arrays or bit masks sized by the
  current maximum join count.

Public result carriers and planner arrays own independently validated semantic
bounds (currently 1,664 result expressions and 64 relation roles). The
materialized core does not import those maximum constants, so future widening
changes adapters and planner state rather than page, index, or external-order
formats.

The following remain separate contracts:

- table schema shape and the durable indexed-table row format;
- SQL column and text-width semantics;
- WAL, transaction, protocol, and public single-result-carrier bounds;
- the `long` representation limit and the operating system's file-size limit.

Changing one of those contracts is a separate change. It must not reintroduce
a hidden materialized-store row or byte ceiling.

## Startup configuration

### File and grammar

The database-local file is:

```text
<database-directory>/river.properties
```

Only the primary database directory supplies configuration for local and
quorum opens. On create, a database directory that does not yet exist has no
configuration file and therefore uses defaults. An operator that wants custom
create-time settings creates the directory and file before calling create.

`RelationalDatabaseFactory` reads and validates the file once before it opens
or creates database files. The normalized immutable `RiverRuntimeConfig` is
owned by the opened `RelationalDatabase` and passed to every SQL session. SQL
execution never reads `System.getProperty`, rereads the file, or mutates the
configuration after open. The default temporary directory is captured from
`java.io.tmpdir` only during this startup read.

The file is UTF-8 and deliberately uses a small properties grammar:

- a blank line or a line whose first non-space character is `#` is ignored;
- every other line is split at its first `=` into one `key=value` pair, so a
  later `=` is part of the value;
- ASCII space and tab around the key and value are ignored;
- keys and symbolic values are case-sensitive;
- continuation lines, escape substitution, environment expansion, and command
  substitution are not supported;
- duplicate and unknown keys are rejected.

The file is limited to 16,384 encoded bytes and each physical line to 4,096
encoded bytes, excluding the line terminator. The loader reads through a fixed
16,385-byte buffer, rejects excess before decoding, and uses a strict UTF-8
decoder. This bounds allocation before configuration has been admitted.

Missing `river.properties` means defaults. A read failure returns `IO_FAILURE`.
An unknown or duplicate key, malformed line or value, arithmetic overflow,
empty path, or incompatible setting returns `INVALID_EXTERNAL_INPUT`.

All user-facing sizes use decimal `KB`, `MB`, and `GB`. `1KB` is 1,000 bytes,
`1MB` is 1,000,000 bytes, and `1GB` is 1,000,000,000 bytes. A size is either an
unsigned decimal byte count or an unsigned decimal integer followed
immediately by one of those uppercase suffixes. Fractions, signs, embedded
spaces, lowercase suffixes, and binary-unit spellings are rejected. This
follows [ADR 0013](../adr/0013-configuration-size-units.md).

### Properties and validation

The initial property set is:

| Property | Default | Meaning |
| --- | --- | --- |
| `river.sql.materialized.cache` | `auto` | Database-wide budget for materialized page buffers. `auto` is one eighth of `Runtime.maxMemory()`, capped at `256MB`, then rounded down to whole pages. |
| `river.sql.materialized.page` | `64KB` | Physical size of each materialized scratch page, including its page header. |
| `river.sql.materialized.sort-run` | `auto` | Page-pool reservation holding in-memory entries for one sort run. `auto` is one quarter of the effective cache, rounded down to whole pages, with a minimum of two pages. Two additional pages are reserved for output and metadata/key scratch. |
| `river.sql.join.hash-build-rows` | `1024` | Maximum rows admitted to the optional resident hash index. A larger build uses the disk-backed stable fallback. |
| `river.sql.join.hash-buckets` | `2048` | Bucket count for the optional resident hash index. |
| `river.sql.materialized.spill-directory` | JVM temporary directory | Root for database-open and query-local scratch directories. A relative path resolves against the normalized absolute database directory. |

Validation produces byte and page counts with checked `long` arithmetic:

- `page` is from `4KB` through `16MB`, inclusive, and is a multiple of eight
  bytes;
- effective `cache` contains at least four pages;
- the frame count and frame-metadata byte calculation fit Java array and `int`
  index representations;
- effective `sort-run` contains at least two pages and leaves at least two cache
  pages unreserved, so `sortRunPages + 2 <= cachePages`;
- `hash-build-rows` is from 1 through 1,048,576, inclusive;
- `hash-buckets` is a power of two from 2 through 1,048,576, inclusive; and
- `12 * hashBuildRows + 8 * hashBuckets`, rounded up to pages, leaves at least
  two cache pages unreserved. This is an admission bound for the optional fast
  path, not a join-input limit.

Explicit cache and run budgets are rounded down to whole pages exactly once
during validation and are never otherwise increased or decreased. A zero or
incompatible effective value is rejected. No property controls materialized
row count, materialized payload bytes, run count, or scratch-file bytes.

The normalized spill root is created if absent and resolved to a real absolute
directory. Startup verifies it by creating and deleting a runtime-generated
probe file. Invalid path syntax or a non-directory is
`INVALID_EXTERNAL_INPUT`; creation, permission, or probe I/O failure is
`IO_FAILURE`. River accepts symlinks in this operator-controlled path after
real-path resolution and never appends a SQL-derived name.

### Open diagnostics

`DatabaseOpenResult` and `RelationalDatabaseOpenResult` each gain one reusable
`StatusDetail` with a 512-character capacity. The result object, and therefore
the detail, remains caller-owned and reusable. `reset` clears both the handle
and detail on every attempt. `RelationalDatabaseFactory` populates the
relational detail and `EmbeddedRiver` copies it to the public result when an
open fails. A failed open never publishes a database handle.

Configuration diagnostics name the property and the supplied value, truncated
only according to `StatusDetail` capacity. Examples are:

```text
invalid river.sql.materialized.page: 64001 (expected multiple of 8, 4KB..16MB)
duplicate property: river.sql.materialized.cache
cannot access spill directory: /var/tmp/river
```

Scratch-format diagnostics occur while executing or closing the affected SQL
query, not while opening the database. Internal materialized-store operations
accept the session's reusable `StatusDetail`; a version mismatch returns
`CORRUPTION` and identifies the file kind and versions. This slice does not add
scratch-format text to a public result carrier.

## Runtime ownership and page-pool policy

`RelationalDatabase` owns one `SqlMaterializedPagePool`, created after the
underlying database has opened but before catalog initialization. All SQL
sessions and materialized workspaces use that pool. If later database
initialization fails, the factory closes the pool and underlying database and
returns the original failure unless it was `OK`.

The pool contains `floor(cacheBytes / pageBytes)` frame descriptors. Each frame
has one direct `ByteBuffer` of exactly `pageBytes`, allocated on first use, and
tracks file identity, page number, state, dirty state, pin count, reservation
owner, and a clock-reference bit. Descriptor memory is a small bounded overhead
outside the configured page-buffer budget; all bulk materialized state is
inside page buffers or scratch files.

Every new materialized allocation is constructed into a local before it is
published. The page-pool factory catches `OutOfMemoryError` only around its
descriptor-array construction, and frame acquisition catches it only around
the individual `ByteBuffer.allocateDirect` call. A failed construction closes
any already-created local state and returns `RESOURCE_EXHAUSTED`; a failed frame
allocation restores the free descriptor. River does not catch
`OutOfMemoryError` broadly or continue after an allocation that may have
partially mutated published state.

Frame lookup, reservation, pin/unpin, dirty transitions, victim selection, and
file-owner invalidation are synchronized by the pool. The first implementation
may perform scratch `FileChannel` I/O while holding that pool lock; this is a
known serialization point but gives a simple correct cross-session lifecycle.
No caller may retain a buffer reference after unpinning it.

Victim selection is a bounded clock scan:

- pinned, reserved-for-another-owner, loading, writing, and invalid frames are
  skipped;
- the first pass clears reference bits;
- the second pass chooses the first unreferenced eligible frame;
- a dirty victim is written and checksummed before reassignment; and
- if the bounded scan finds no eligible frame, acquisition returns
  `RESOURCE_EXHAUSTED` without changing any owner data.

There is no timing, GC, monotonically increasing stamp, or fixed global run
count in eviction decisions. A normal unpinned, unreserved cold frame is always
evictable.

Each sort atomically reserves `sortRunPages + 2` frames while generating or
merging runs. Run generation uses `sortRunPages` for entries, one for output,
and one for metadata/key scratch. Merge uses `sortRunPages` inputs, one output,
and one metadata/key scratch, so merge fan-in is exactly `sortRunPages` and is
at least two. The reservation is released between materialization and result
consumption except for frames pinned by the operation in progress. If
concurrent owners leave too few frames, admission returns
`RESOURCE_EXHAUSTED` rather than silently shrinking the operator budget.

The resident hash path encodes its `int` bucket links and `long` hashes into an
atomic frame reservation calculated during configuration validation. After the
complete build input is materialized, a build within `hash-build-rows` attempts
that reservation and constructs the index. If the reservation is unavailable,
the workspace uses the disk-backed fallback. Cache contention therefore
changes join strategy, not query correctness.

`RelationalDatabase` owns a SQL-session lease count independently of the public
`EmbeddedRiver.EngineDatabase` wrapper. `SqlSession.create` acquires one lease
atomically with session publication and `SqlSession.close` releases it exactly
once after all query scratch is closed. Catalog bootstrap and direct
`RelationalSession` use do not acquire SQL leases. Relational database close
returns `CONFLICT` while a lease remains, so direct `RelationalDatabase` users
cannot close the pool beneath a live SQL session.

After the last lease closes, database close invalidates every remaining file
owner, attempts all runtime-owned scratch cleanup, releases all buffers, and
then calls the underlying database close. It returns the first non-`OK`
cleanup status after attempting the remaining runtime steps. This slice does
not claim the underlying database close is internally best-effort beyond its
existing contract.

For every operation with both a primary execution status and cleanup statuses,
the primary non-`OK` status wins. If execution was `OK`, the first non-`OK`
cleanup status is returned. Later cleanup failures are appended to the bounded
detail when space remains. No cleanup failure hides whether a statement had
already published a transactional outcome.

## Scratch ownership, files, and cleanup

After the underlying database has opened, the runtime creates an open-instance
directory beneath a deterministic database namespace under the normalized
spill root. The namespace contains the database incarnation and the first 16
lower-case hexadecimal characters of SHA-256 over the UTF-8 authoritative real
primary-database path returned by the opened directory adapter. This prevents
symlink aliases or an accidentally reused incarnation from sharing identity.
The open-instance directory is made with `Files.createTempDirectory` inside
that namespace; SQL text is never used. Query/store directories are created
beneath that instance directory. All files use `CREATE_NEW` and are opened only
through retained runtime `Path` objects.

An ordinary close does not flush dirty scratch pages merely to delete them. It:

1. marks the owner closing so no new pin can be acquired;
2. records `INVARIANT_BROKEN` if an internal pin remains and forcibly releases
   it only after the SQL lifecycle has excluded concurrent use; this condition
   is asserted in tests;
3. invalidates the owner's cache frames without write-back;
4. closes every channel, retaining the first failure status;
5. attempts every file and directory deletion even after a prior failure; and
6. clears reusable in-memory state only after handles and pins are released.

Append, merge, cancellation, early scan close, and query failure all enter this
same best-effort cleanup path. A deletion failure returns `IO_FAILURE` and
leaves the exact path in the session diagnostic so the operator can remove it.

Scratch data is non-recoverable. Every deterministic database namespace owns a
persistent `.owner.lock` file. Runtime open acquires its operating-system file
lock before inspecting any `open-*` entry; a concurrent opener returns
`CONFLICT` and neither creates nor reclaims an instance. After exclusive
ownership is established, open removes stale `open-*` trees without following
symbolic links and then creates the current instance. Close retains the lock
until owner cleanup and current-instance deletion succeed, so a cleanup retry
cannot race a new opener. The lock file itself survives normal close and is not
treated as an orphan.

Each store creates files lazily:

| File | Contents |
| --- | --- |
| `.rows` | Complete encoded materialized records at checked `long` logical offsets. |
| `.index` | Fixed-width row metadata and logical output-order entries. |
| `.keys` | Canonical typed tuple bytes for ordering, grouping, or distinct identity; offset and length live in `.index`/run entries. |
| `.runs0`, `.runs1` | Alternating external-sort run generations for bounded multi-pass merge. |

## Scratch format

All multibyte values use big-endian byte order. The scratch format is private
to one running River version, is never migrated, and starts at version `1`.
Versioning exists to detect internal misuse and corruption, not to make query
scratch compatible across database versions.

Every file starts with this 64-byte header:

| Offset | Width | Value |
| ---: | ---: | --- |
| 0 | 8 | File-kind magic. |
| 8 | 4 | Format version, initially `1`. |
| 12 | 4 | Header bytes, `64`. |
| 16 | 4 | File-kind code. |
| 20 | 4 | Configured physical page bytes. |
| 24 | 4 | Fixed record bytes, or zero for a variable stream. |
| 28 | 4 | Validated flags; unknown bits are corruption. |
| 32 | 8 | Positive file identity from a checked pool-local counter. |
| 40 | 8 | Published logical record count. |
| 48 | 8 | Published logical stream bytes. |
| 56 | 4 | CRC32C of bytes 0 through 55. |
| 60 | 4 | Reserved zero bytes. |

The header is validated once when a file is first read. Header identity,
version, kind, lengths, flags, reserved bytes, and checksum are checked before
allocation or offset arithmetic based on them.

After the file header, every physical page is exactly the configured page size
and begins with:

| Offset | Width | Value |
| ---: | ---: | --- |
| 0 | 4 | Page magic. |
| 4 | 4 | Page-header version, initially `1`. |
| 8 | 8 | File identity. |
| 16 | 8 | Zero-based logical page number. |
| 24 | 4 | Used payload bytes. |
| 28 | 4 | CRC32C of the used payload bytes. |

The remaining `pageBytes - 32` bytes are logical stream payload. The page pool
caches the 64-byte unpaged file header as a special header frame, so advancing
a published count dirties cache state instead of performing per-row I/O. Bulk
stream access maps checked `long` logical offsets across page payloads; fixed
and variable records may cross pages. A dirty page's checksum and used length
are finalized immediately before write-back. Unexpected EOF, invalid used
length, identity mismatch, page-number mismatch, and checksum mismatch return
`CORRUPTION`. A zero-progress read/write or an `IOException` returns
`IO_FAILURE`; positive partial I/O is retried until complete.

The `.index` logical stream contains one 48-byte record per row ordinal. The
metadata fields in record `n` describe row ordinal `n`; the order field in
record `p` independently gives the row ordinal at output position `p`:

| Offset | Width | Value |
| ---: | ---: | --- |
| 0 | 8 | Row payload logical offset in `.rows`. |
| 8 | 4 | Row payload length. |
| 12 | 4 | Flags: encoded tuple present; other bits are zero. |
| 16 | 8 | Output-order row ordinal; initialized to this record's ordinal. |
| 24 | 8 | Encoded tuple logical offset in `.keys`, or zero when absent. |
| 32 | 4 | Encoded tuple length, or zero when absent. |
| 36 | 4 | Reserved zero bytes. |
| 40 | 8 | Reserved zero bytes. |

`.rows` stores the consumer's complete output record, including projected
values, null state, physical text source, generated text, and an opaque logical
row ID only where an internal reload adapter requires it. It never assumes or
publishes one scalar user primary key. Every row begins with this 24-byte
materialized record envelope:

| Offset | Width | Value |
| ---: | ---: | --- |
| 0 | 4 | Codec kind. |
| 4 | 4 | Codec version. |
| 8 | 4 | Column count. |
| 12 | 4 | Null-bitmap bytes, exactly `(columnCount + 7) / 8`. |
| 16 | 4 | Codec-payload bytes. |
| 20 | 4 | Reserved zero bytes. |

The variable-width null bitmap and codec payload follow the envelope. Their
checked sizes plus 24 must equal the enclosing metadata length before any
schema-sized scratch is accessed. `SqlBlockRowStore` and `SqlSortWorkspace`
use one concrete paged-record implementation with their codecs as adapters;
they do not implement separate external-sort engines.

Each run in `.runs0` or `.runs1` starts with this 64-byte run header:

| Offset | Width | Value |
| ---: | ---: | --- |
| 0 | 8 | Run magic. |
| 8 | 4 | Run format version, initially `1`. |
| 12 | 4 | Run-header bytes, `64`. |
| 16 | 8 | Zero-based merge pass. |
| 24 | 8 | Run ordinal within the pass. |
| 32 | 8 | Entry count. |
| 40 | 4 | Entry bytes, `24`. |
| 44 | 4 | Comparator-policy flags. |
| 48 | 8 | Query-owned tuple-order descriptor hash. |
| 56 | 4 | CRC32C of bytes 0 through 55. |
| 60 | 4 | Reserved zero bytes. |

The header is followed by 24-byte entries:

| Offset | Width | Value |
| ---: | ---: | --- |
| 0 | 8 | Materialized row ordinal. |
| 8 | 8 | Encoded tuple offset in `.keys`. |
| 16 | 4 | Encoded tuple length. |
| 20 | 4 | Reserved zero bytes. |

Page checksums protect run entries. A run is published only by advancing the
file header's published run count after its header and entries are present in
the cache. Readers ignore bytes beyond published stream length.

The query owns one immutable tuple-order descriptor with checked `int` arity,
type descriptors, per-part ascending/descending direction, and null placement.
It supports the query-result/grouping limit rather than the smaller B-tree
index-key arity. Its hash in each run header prevents a run from being decoded
with the wrong query descriptor; the complete encoded tuple remains
self-validating and is always rechecked, so the hash is not an identity.

All ordinals, counts, output positions, payload offsets, tuple offsets, run
counts, and run record counts use `long` through the complete production call
chain. Record and tuple lengths remain `int` because one encoded row and one
encoded tuple are independently bounded. Every addition,
multiplication, round-up, page mapping, file-position conversion, and count
increment that can overflow is checked before state mutation.

## Append, read, and failure state

`SqlBlockRowStore.append` performs:

1. validate schema and source row;
2. encode one complete row into the reusable codec scratch buffer;
3. reserve checked payload, optional tuple, and metadata logical ranges without
   publishing them;
4. copy payload and optional encoded tuple through pinned pages;
5. write the metadata record with its order field initialized to the row
   ordinal; and
6. advance the in-memory and file-header published counts last.

Publication means visible to this running query after successful cache
mutation; scratch pages are not forced durably on append. If a later eviction
cannot write a published dirty page, the store enters a terminal failed state,
returns `IO_FAILURE`, and permits only close.

Before publication, failure restores logical lengths, clears any touched
unpublished metadata and key bytes in resident frames, and attempts to truncate
already-written file tails. Bytes that cannot be truncated remain beyond the
published lengths and are never visible. Failure to perform rollback upgrades
the store to terminal `IO_FAILURE`, but close still attempts all cleanup.

`rowCount`, `next`, `readAt`, `stored`, ordinary-sort progress, join run bounds,
and every output-order position use `long`. Sequential reads consult the order
field when sorting is active and use the row ordinal directly otherwise. Row
decoding pins only pages needed for the current bounded copy and releases every
pin before returning the caller-owned row. A returned row or text view never
aliases a page frame.

`RESOURCE_EXHAUSTED` from temporary frame contention does not corrupt or alter
the owner. SQL execution may close the query or retry the exact operation after
other owners release frames; append is never implicitly retried after its
publication point.

## External ordering

One concrete external-order engine serves block ordering, ordinary ordered
scans, grouping, and `DISTINCT`. Run generation fills the configured reserved
frames with fixed 24-byte entries, sorts those entries in place without
per-row allocation, writes one run, and releases the reservation. It never
retains all row ordinals or tuple keys in Java arrays.

When run count exceeds merge fan-in, merge is multi-pass:

1. group at most `fanIn` adjacent published runs;
2. merge each group into the alternate run file;
3. publish each completed output run independently;
4. after every input run in the pass is consumed, publish the completed pass
   number, invalidate and truncate the previous-generation file; and
5. alternate files until one run remains.

A failure leaves the current output run unpublished, marks the sort terminal,
and enters query cleanup. Because query scratch is not recovered, no restart or
resume protocol is required. Run descriptors are fixed arrays sized only to
the admitted fan-in; total run count and pass count are `long` values stored in
file headers.

Comparison walks every encoded tuple part with the query-owned direction and
null-placement policy and the canonical typed comparator. Grouping and
`DISTINCT` use their SQL equality policy; ordering uses its per-part policy.
Equal complete tuples use original row ordinal ascending as the stable physical
tie. No comparator reads a user primary key, so composite-key and no-primary-key
tables use the same path. Existing alpha ordering among SQL-equal rows was not
a public SQL guarantee and is replaced directly. UTF-8 text comparison follows
the common Unicode-scalar comparator.

The number of generated runs and merge passes is limited only by checked
`long` format arithmetic and temporary-file capacity. There is no fixed 64-run
or Java-array-sized run ceiling.

After run generation or the final merge leaves one run, a bounded installation
scan writes each winning row ordinal into the `.index` order field at its
`long` output position. The store publishes `sorted = true` only after every
published row has one installed order entry. A failed installation leaves the
store terminal and exposes no sorted result. Empty and one-row stores install
their identity order without creating run files.

## Join behavior

`SqlJoinHashWorkspace` always materializes the complete build input in the
paged store. It attempts the resident hash path only after atomically reserving
the configured hash pages. Bucket addressing uses the validated power-of-two
mask. Hash slots use `int` links because the resident path is capped at
1,048,576 rows; materialized row ordinals remain `long`.

If the completed build exceeds `hash-build-rows`, or the reservation is
unavailable, the workspace selects the stable disk-backed scan fallback without
constructing a partial hash index. It does not discard or rebuild the
materialized row store. The fallback scans candidates in original
materialization order and has no 65,536-row gate. This slice claims correctness
and bounded memory, not partitioned hash-join throughput.

All position and run fields in `SqlJoinMergeRightRows` become `long`. Repeated
probe runs continue to reuse the same materialized records and order mapping.

## Test plan and acceptance criteria

### Configuration and open lifecycle

`RiverRuntimeConfigTest` proves:

- missing-file defaults and the exact `auto` calculations;
- accepted decimal units and exact bytes;
- rejection of binary/lowercase suffixes, signs, fractions, malformed lines,
  continuation syntax, duplicates, unknown keys, invalid UTF-8, overflow,
  zero, empty paths, files above 16,384 bytes, lines above 4,096 bytes, and
  incompatible page/cache/run/hash settings;
- relative spill resolution, real-path normalization, create/delete probing,
  and source-file immutability after open;
- primary-directory behavior for quorum opens; and
- invalid configuration and inaccessible spill roots fail before database
  files open and never publish a handle.

Open-result tests prove detail reset/copy behavior and best-effort close of the
page pool and underlying database after catalog initialization failure.

### Page-backed storage and format

`SqlBlockRowIndexStorageTest` uses four pages and proves:

- dirty-page checksum/write-back and cold-page reload;
- records crossing physical pages;
- single- and 1,664-part nullable typed tuple metadata;
- payload reads after metadata eviction;
- pinned and reserved frames are not evicted;
- all-pinned acquisition preserves owner data and returns
  `RESOURCE_EXHAUSTED`;
- sparse ordinals and logical offsets above `Integer.MAX_VALUE` work; and
- checked arithmetic rejects values near `Long.MAX_VALUE` before mutation.

Corruption cases cover every file and page header field, unknown flags,
reserved bytes, used length, identity, page number, checksum, truncated page,
short read, and zero-progress I/O. A run-version mismatch returns `CORRUPTION`
with expected version, actual version, and file kind in the internal detail.

### Append and cleanup failure matrix

Fault-injected tests fail each payload, tuple, metadata, publication, eviction
write-back, channel-close, truncate, and deletion step. They prove:

- an unpublished append is never visible;
- published rows remain readable until a later terminal write-back failure;
- terminal stores permit close but no further read or append;
- close attempts every channel and path after the first failure;
- cancellation and early scan close use the same cleanup path;
- deletion failure reports the retained path; and
- close never scans or removes an unretained sibling instance directory.

### Removed row and byte boundaries

`SqlBlockRowStoreTest` appends 65,537 rows, finishes, and reads the first,
middle, and final rows after forced eviction. It asserts `long` counts and
successful object reuse after close.

A separate deterministic test writes sparse/full pages through the production
page mapping until the logical payload exceeds the former exact
268,435,456-byte boundary. It reads records on both sides of that offset and
proves no materialized-byte quota is consulted. The test may use sparse files,
but it must exercise production checked offset and page-cache code.

### External ordering

`SqlExternalSortTest` configures the minimum four-page cache and two-page run,
creates more than 64 runs and more runs than one merge fan-in, and checks:

- at least two merge passes and alternating run files;
- ascending and descending null placement;
- numeric, duplicate, UTF-8, mixed-direction, and per-part null ordering;
- grouping/distinct NULL equality and original-ordinal stable ties;
- a record and 1,664-part encoded tuple crossing pages;
- partial output-run failure remains unpublished; and
- no descriptor array grows with total run count.

### Shared-pool and join behavior

`SqlMaterializedPagePoolTest` runs concurrent owners and proves atomic
reservations, pin isolation, dirty-owner failure isolation, deterministic
clock eviction, strategy fallback when hash reservation is unavailable, and
complete reservation release on every close path. Tests use latches and
counters, not timing thresholds.

`SqlLargeJoinMaterializationTest` builds more than 65,536 rows, crosses the
resident hash threshold, and asserts stable fallback results, correct repeated
probe runs, `long` positions, and complete cleanup.

### Real SQL path

`SqlSubqueryOrderedConsumerTest` uses the public embedded SQL path to
materialize and consume more than 65,536 rows. It asserts exact cardinality,
first/middle/final values, ordering, and successful cleanup. A second case uses
both alternating block stores under one configured cache budget.

An ordinary ordered-scan test crosses 65,536 rows through `SqlSortWorkspace`,
`SqlActiveScanState`, and `SqlQueryExecution`; it detects any narrowing cast by
checking rows around the old boundary and the final row.

Lower-level materialized-record tests use synthetic schemas with 9 and 65
columns. They prove column count is not narrowed to a byte, null state crosses
the first 64-bit bitmap word, codec scratch is schema-sized once, and the
index/run/page formats are unchanged. A join-owner test uses role identities
above eight and proves file and cache ownership do not alias. These tests do
not require the public parser or planner to admit those widths in this slice.

## Allocation and copy acceptance

Steady-state append, page lookup, eviction selection, run generation, merge,
hash probing, and sequential decode allocate no object per row or run record.
Tests or allocation instrumentation record:

- one bounded codec/output scratch area per active operator;
- schema-dependent scratch grows once at `begin` in proportion to validated
  column count and is independent of materialized row count;
- one direct allocation per page frame on first use;
- fixed descriptor arrays proportional to configured frames or merge fan-in;
- zero arrays proportional to materialized row count or total run count; and
- River-owned copies at page/row lifetime boundaries.

The necessary copy from a pinned page into the caller-owned row is accepted
because it ends page aliasing before unpin. No returned view extends a pin
lifetime.

## Implementation order

1. Add exact configuration parsing, immutable validation, spill-root probing,
   and open-result diagnostics.
2. Add the synchronized shared page pool, reservations, checked logical-page
   mapping, file/page headers, and fault-injected format tests.
3. Thread configuration, pool, diagnostics, and cleanup ownership from
   `RelationalDatabase` through every SQL session.
4. Replace resident row-index, payload, and encoded-tuple arrays with the concrete
   paged record store and convert the complete count/position call chain to
   `long`.
5. Implement the shared tuple run generator and bounded alternating multi-pass
   merge with query-owned per-part comparator policy.
6. Adapt ordinary ordered scans and block stores to the shared engine, then
   remove the 65,536-row and 256 MB checks.
7. Move hash-index storage into reservations, implement threshold/contention
   fallback, and convert merge-join run positions to `long`.
8. Complete cleanup/orphan handling, failure matrices, allocation checks, and
   the public SQL acceptance tests.

Run the narrowest targeted Gradle test while editing, only one Gradle build at
a time in this checkout. Expand to all affected `river-engine` tests and policy
checks after the vertical path passes.
