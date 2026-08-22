# Alpha 3 TPC-C schema and capacity plan

Status: implementation contract in progress on
`feature/alpha3-tpcc-capacity`.

Owner: storage/relational lead. Durable-format, recovery, relational-semantics,
and allocation review are required before promotion.

## Immediate consumer

The first consumer is the standard one-warehouse TPC-C schema and load. River
must admit that schema without surrogate-key or denormalization rewrites and
must load it without exhausting table, row-version, page-cache, checkpoint, or
WAL bounds.

## Fixed contracts

- A table and a query result may expose at most 255 columns. Column ordinals
  remain `int` in Java; durable encodings may use an unsigned byte only where
  255 is represented unambiguously and validated before use.
- The maximum encoded physical row remains 4,096 bytes. Column count does not
  weaken row-size, text, or page-bound validation.
- Nullability, defaults, checks, references, projections, and mutation masks
  use four-word bounded column sets rather than a single 64-bit mask.
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
  payload envelopes. A 255-column statement/metadata frame is admitted without
  retaining a maximum text buffer per column; old protocol versions fail
  closed at this unreleased format boundary.

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

- Column sets are exactly four 64-bit words. Ordinals 0 through 254 are valid;
  bit 255 must be zero. Wire and durable owners serialize all four words in
  ordinal order.
- Catalog table headers declare key kind (`KEYLESS`, `PRIMARY`, or
  `COMPOSITE`), arity zero through four as appropriate, column count, payload
  bytes, generation, and typed segment count. Schema and statistics payloads
  occupy nonempty typed continuation segments in catalog sequence space 1.
  The 64-bit continuation watermark, every segment, and the generation header
  publish in one transaction. Table generations require at least one schema
  segment and never assemble after a missing, duplicate, wrong-namespace,
  wrong-generation, checksum, or kind failure.
- Heap rows contain all user columns and four null words. Storage keys never
  synthesize SQL column zero. A logical-row directory maps positive-long
  logical identity and table ownership to a positive-long head version. The
  version directory maps positive-long version identity to its prior version,
  commit state, and the full physical locator: positive-long page number and
  generation plus bounded slot number and slot generation.
- Primitive directory B-trees use distinct v3 leaf and internal entry layouts.
  Relational indexes use compact slotted tuple pages with inline canonical
  typed keys, descriptor/schema binding, at most four user components, and a
  positive logical-row tie-break. Numeric and temporal components use
  order-preserving fixed encodings; Unicode components use scalar-order
  encoding with the existing 255-scalar VARCHAR maximum. Full-page validation
  owns ordering, uniqueness, fences, sibling state, typed values, compact
  packing, and zero slack before traversal.
- Indexed WAL v5 carries at most 63 complete page images and 63 root updates in
  one bounded record, long logical/version watermarks, an exclusive int
  `nextPageId`, and long page generations. Recovery validates the complete
  envelope, then every `PageCodec` checksum and database/WAL/record identity,
  rejects duplicate or out-of-range page IDs, and checks an included root
  image's generation before publishing any image, root, or watermark.
- Checkpoint manifest v3 is a fixed 1,192-byte authority containing database,
  WAL, commit and transaction watermarks; long logical/version watermarks;
  root page IDs and generations; exclusive `nextPageId`; storage generation;
  and at most 64 ordered dirty extents. It contains no row/version arrays and
  accepts no older manifest version.
- Protocol v4 has three separate canonical network envelopes. SQL requests are
  bounded at 256 KiB, enough for a 255-parameter statement plus a 4 KiB packed
  value body; query metadata is bounded at 32 KiB, enough for 255 descriptors
  and 64-byte names; packed row responses are bounded at 8 KiB and contain at
  most a 4,096-byte encoded row plus 255 descriptors and offset/length entries.
  Every envelope carries four mask words and rejects bit 255 or bits outside
  its declared shape. These are payload bounds, not retained per-connection
  buffer requirements.

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

- Vacuum is a resumable storage-generation build. It scans after a durable
  positive-long logical-row watermark, writes replacement heap/index/directory
  pages in WAL batches no larger than the 63-page page-image contract, and
  persists progress through the reserved system portion of the rooted catalog.
- A progress generation identifies the source storage generation, replacement
  storage generation, source high watermark, last completely copied logical
  row, replacement root-directory authority, exclusive replacement
  `nextPageId`, rows copied, versions reclaimed, the latest observed source
  commit sequence, and the last commit sequence applied to the replacement. A
  chunk publishes its page images and next progress record atomically; replay
  is idempotent by generation and watermark.
- Ordinary writers remain admitted while `BUILDING`. Their WAL records are the
  authoritative bounded delta stream; vacuum advances the replacement through
  that stream in commit order after copying the snapshot row range. Before
  `COMPLETE`, vacuum takes a short publication fence, records the current source
  commit sequence, applies every remaining delta through that sequence, and
  requires `appliedCommitSequence == sourceCommitSequence`. Delta pressure may
  return `RETRY` or apply normal WAL backpressure, but cannot discard a source
  mutation or force a whole-generation write outage.
- Readers remain on the source roots while a replacement is building. The
  final transaction atomically switches the checkpoint/root authority only
  after validating the replacement roots and directories, then makes the old
  generation reclaimable. Cancellation or failure leaves the source
  authoritative and returns an explicit status; unreachable replacement pages
  are reclaimed by generation rather than retained in heap metadata.

### A3C-1 — 255-column relational path

Replace single-word masks with bounded column sets; segment catalog schemas;
make parser, DDL/DML, row codecs, projections, results, protocol metadata, JDBC
metadata, statistics, grouping, sorting, and P3 carriers wide-row safe.

Gate: create, checkpoint/reopen, insert, update, index, select, sort, and JDBC
metadata over a 255-column schema; NULL/default/check/reference bits cross the
63/64, 127/128, and 254 boundaries; retained allocation stays bounded.

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
their point/range/DML access paths. Also admit a logically keyless heap table
whose hidden internal row identity is never exposed as a surrogate SQL key;
TPC-C `HISTORY` has no declared primary key. Composite keys use one canonical
typed tuple encoding across catalog validation, indexes, WAL, recovery, and
referential checks.

Gate: primary-key widths one through four, the required composite foreign
keys, the nonunique ordered customer-name key, and keyless duplicate HISTORY
rows survive insert/update/delete, conflict, rollback, checkpoint, and reopen.

### A3C-5 — one-warehouse load

Set a measured default/configured store-wide capacity sufficient for the full
one-warehouse population and at least 1,048,576 physical row/version entries,
add reclamation headroom, and load the unmodified one-warehouse TPC-C schema
and initial data.

Gate: the standard load has `STOCK` exactly 100,000 rows and `ORDER_LINE`
exactly equal to the sum of the 5–15 line counts declared by its 30,000 orders.
All declared keys and references validate; checkpoint/reopen and consistency
counts agree; no cache or retained-memory bound scales with table cardinality.
A separate deterministic fixture crosses 300,000 rows and the configured
store-wide capacity remains at least 1,048,576 physical row/version entries.

## Non-goals for this slice

- Claiming official TPC-C compliance or reporting `tpmC`.
- Removing explicit disk, WAL, cache, transaction, or statement bounds.
- Increasing the 4,096-byte physical row limit without an immediate TPC-C
  consumer.
- Preallocating memory for 255 columns or billions of rows in ordinary narrow
  sessions.
- Compatibility adapters for unreleased River catalog, WAL, page, or protocol
  formats.
