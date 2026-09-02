# SQL shape and composite-key capacity plan

Status: implementation active; adversarial senior review incorporated. The
shared shape limits, wide parser/result carriers, catalog-v2 descriptors,
composite tuple keys/FKs/indexes, keyless rows, multi-expression grouping, and
64-role execution are implemented; final cross-module verification and numeric
range completion remain open. Each durable/public slice still requires the
independent acceptance lenses listed below.

Owner: relational execution lead, with storage/recovery and protocol builders
for the durable and public-boundary slices.

## Outcome

Remove River's alpha-era limits of eight columns, eight relation roles, one
grouping expression, one index column, and one `BIGINT` primary-key column.
Wide schemas and queries must work through the real path: SQL parsing,
binding, catalog persistence, row encoding, indexes and constraints,
transactions and recovery, execution, materialization, protocol, client, and
JDBC.

This work raises count limits to the same practical class as PostgreSQL and
MySQL. It does not claim that memory, row bytes, key bytes, statement text,
pages, or disk are unlimited. Count admission and byte admission are separate:
a statement within the column-count limit can still return
`RESOURCE_EXHAUSTED` when its encoded row or key exceeds the documented byte
format. That is normal database behavior, not a hidden column-count failure.

The target values are based on the current published limits: PostgreSQL allows
1,600 table columns, 1,664 result columns, and 32 columns per index, while
MySQL InnoDB allows 1,017 table columns, 64 secondary indexes, 16 columns per
multicolumn index, and 3,072 index-key bytes. MySQL also permits 61 tables in
one join. River chooses round implementation bounds at or between those
values. See the official [PostgreSQL limits][postgres-limits], [MySQL InnoDB
limits][mysql-innodb-limits], and [MySQL join syntax and limits][mysql-joins].

[postgres-limits]: https://www.postgresql.org/docs/current/limits.html
[mysql-innodb-limits]: https://dev.mysql.com/doc/refman/8.4/en/innodb-limits.html
[mysql-joins]: https://dev.mysql.com/doc/refman/8.4/en/join.html

## Product limits

These are explicit safety and representation limits. They are named by
semantic purpose even where two values are equal; one feature must not become
smaller merely because an unrelated limit changes.

| Contract | Target | Admission rule |
| --- | ---: | --- |
| Declared columns per physical table | 1,024 | Reject 1,025 before catalog mutation. The encoded maximum row must also fit `TableSchema.MAXIMUM_ROW_BYTES`. |
| Columns in a query, derived-relation, or view result | 1,664 | Includes `SELECT`, `RETURNING`, and expanded `*` output. A view may expose 1,664 columns even though a physical table may expose only 1,024. |
| Expressions in `SELECT`, `RETURNING`, `GROUP BY`, `ORDER BY`, `DISTINCT`, or a row constructor | 1,664 | Each list is counted independently. Combined operator output is still at most 1,664 lanes. |
| Insert target/value columns and update assignments | 1,024 | Must also match the target table and statement/value byte budgets. |
| Relation roles in one query block | 64 | Includes physical tables, self-join aliases, expanded views, and merged derived relations. A block may therefore contain at most 63 join stages. |
| Physical plan steps in one query block | 256 | Actual-count storage; sufficient for 64 scan/join roles plus filters, materialization, grouping, distinct, sort, and limit operators. |
| Parts in a primary, unique, foreign, or secondary-index key | 32 | Ordered parts; duplicate columns in one key are invalid. |
| Secondary indexes per table | 64 | Includes indexes that implement `UNIQUE`; the primary-key index is additional. |
| Foreign-key definitions per table | 64 | Each has at most 32 child and 32 referenced parts. This replaces the current four-constraint/index-era ceiling without making one DDL/DML exceed bounded transaction state. |
| Check-constraint definitions per table | 1,024 | Column-level checks desugar to table-level checks. Expression-program limits apply independently. |
| Aggregate invocations per query block | 1,664 | Includes aggregates used only by `HAVING` or ordering as well as projected aggregates. |
| Predicate leaves per Boolean program | 4,096 | Applies independently to each `ON`, `WHERE`, `HAVING`, and `CHECK` program. This is expression complexity, not table width. |
| Distinct column references in one `ON`, `WHERE`, `HAVING`, or `CHECK` program | No separate column cap | The input table/query width and expression-node/leaf budgets are the only bounds. An indexable equality prefix is still at most 32 key parts; further comparisons remain residual predicates. |
| Boolean/scalar nodes per program | 16,384 | A node has an `int` index. Nesting depth remains 64 to protect parser and evaluator stacks. |
| Encoded schema/catalog child bytes | 1 MiB in at most 160 chunks | Includes columns, names, keys, constraints, defaults, and checks but not asynchronously refreshed statistics. Each continuation provides capacity for at least 6,554 payload bytes; the final one may use less. |
| Index user-key bytes | 3,072 | Includes tuple header, part markers, and user values. The descriptor's maximum is checked at DDL; actual bytes are rechecked at mutation/decode. |
| Physical index-key bytes | 3,080 | Exactly the 3,072-byte user-key maximum plus the 8-byte logical-row-ID suffix used by physical entries and fence keys. |
| Stored base-row bytes | 8,192 | Retained initially. The row-format header and variable null bitmap count against it. |
| Encoded result/materialized row bytes | 4 MiB | Covers 1,664 maximum-width current `VARCHAR` lanes plus metadata. Records span materialized pages and protocol frames. |
| SQL statement text bytes | 1 MiB | Universal parser/protocol limit; stored view SQL has the narrower documented 256 KiB catalog limit. |
| SQL parameters | 65,535 | Uses unsigned 16-bit wire representation and `int` internal indexing. |
| Encoded parameter bytes per execution | 16 MiB | Includes descriptors, null bitmap, offsets/lengths, and values. Request records span protocol frames. |

The values are compiled format/product limits in this slice, not runtime
configuration. A configuration knob would create multiple compatibility
profiles without an immediate consumer. JDBC metadata reports the applicable
values rather than returning the old literal eight.

A 64-role query can expose far more source columns than a result can publish.
As in PostgreSQL, `SELECT *` is rejected when expansion exceeds the independent
result-column limit; explicitly projecting at most 1,664 lanes is admitted.

`TableSchema.MAXIMUM_ROW_BYTES` means that 1,024 `BOOLEAN` or narrow nullable
columns can be admitted while a schema with 1,024 inline `BIGINT` columns is
rejected with an exact size diagnostic. PostgreSQL and MySQL likewise have an
independent row-size restriction below their nominal column maximum. River
does not add out-of-line base-table values in this milestone.

## Scope and non-goals

### In scope

- table and view column count, insert/update/returning shape, projection, and
  public result metadata;
- up to 64 relation roles in a query block, including view lineage and the
  join planner/executor workspaces;
- multiple `GROUP BY` expressions and the corresponding `HAVING`, aggregate,
  `DISTINCT`, and ordering shapes;
- table-level and named `PRIMARY KEY`, `UNIQUE`, `FOREIGN KEY`, and `CHECK`
  grammar, with column-level forms desugared into the same representation;
- composite primary keys, unique constraints, foreign keys, and ordinary
  secondary indexes, including all supported River scalar types;
- a variable-width table row format, chunked catalog metadata/statistics, and
  a variable-key B-tree path;
- engine API, materialized query store, protocol, client, CLI, JDBC metadata,
  backup/reopen/recovery, and `EXPLAIN` propagation; and
- removal of fixed-width masks, byte ordinals, fixed-four tuple descriptors,
  and eager maximum-sized two-dimensional arrays from these paths.

### Not in scope

- higher SQL feature breadth such as `RIGHT`, `FULL`, `NATURAL`, or lateral
  joins merely because role capacity is widened;
- included/non-key index columns, partial indexes, expression indexes,
  descending key parts, alternate collations, or `NULLS NOT DISTINCT`;
- off-page base-table values or a larger heap page;
- an exhaustive 64-relation join-order search;
- online conversion of an unreleased River durable format; or
- compatibility adapters for existing alpha database or protocol versions.

River is pre-V1. This plan increments the affected durable and protocol
versions directly and requires users to recreate alpha databases and update
clients.

## Current blockers that must be removed together

Changing constants alone would either remain incorrect past 64 columns or
allocate maximum-sized buffers for every narrow statement. The current limit
is encoded in all of these layers:

- `SqlCommand`, `SqlJoinChain`, `SqlAggregateSet`, predicate programs, parser
  scratch, and command reset/copy code use fixed arrays, `byte` ordinals, and
  `long` masks;
- `TableSchema`, `TableDefinition`, `TableStatistics`, view lineage, and the
  catalog codecs store fixed arrays and four 64-bit column-property masks;
- `CatalogRecord` stores one monolithic table record, which cannot fit 1,024
  names, descriptors, constraints, and statistics in one 8 KiB record;
- the table row format uses one 64-bit null mask and eight-byte slots, and
  assumes a single first-column `BIGINT` primary key that is not part of the
  encoded row;
- `RelationalKey`, `IndexedTableStore`, WAL/index operations, locks, unique
  checking, and foreign-key lookup are keyed by one `long`;
- public and internal `CommandResult.key()`, `RowResult.key()`, protocol
  response keys, generated-key handling, and SQL row providers conflate a
  first-column user key with physical row identity;
- secondary-index catalog and mutation state stores one column and one `long`
  value per index, with fingerprint/collision handling for text;
- grouped aggregation has exactly one group lane, and `HAVING` maps one group
  value plus at most eight aggregate invocations;
- physical-plan, scan, hash, merge, outer-row, and role bindings are sized for
  eight roles or steps;
- `CommandResult`, `RowResult`, scan/projected/block rows, sort spill, protocol
  responses, the client, and JDBC use fixed eight-lane arrays, `char[][]`, and
  one null mask;
- protocol v3 response payloads assume one frame and eight null bits, while
  the v4 envelope has an unrelated 255-column ceiling; and
- the existing `TupleKeyCodec` and `TupleBTreePageCodec` are useful groundwork
  but hard-code arity four and four descriptors in an 80-byte page header.

The implementation inventory must remain a checked deliverable. Before each
slice is accepted, `rg` searches for `MAXIMUM_COLUMNS`, `MAXIMUM_JOIN_ROLES`,
`MAXIMUM_ARITY`, `nullMask`, column-index shifts such as `1L << column`, and
fixed result/index/JDBC maxima in production source. Each remaining match must
be either removed or documented as a non-column scalar flag.

W0 installs `verifySqlShapeSourcePolicy` as the ratchet for the named legacy
patterns. Its per-module ceilings capture the accepted starting inventory;
each delivery slice lowers the affected ceiling after removing matches, and
any net addition fails `check`. The broader fixed result/index/JDBC inventory
is reviewed and added to the ratchet when its owning W1/W2 boundary is changed,
so W0 does not freeze unrelated scalar capacities by substring accident.

## Shared architecture

### 1. Semantic limits, not a universal maximum

Add dependency-free `SqlShapeLimits` to `river-base`. `river-sql`,
`river-format`, `river-engine-api`, protocol, and engine can all depend on it
without a new project edge. It exposes separately named constants such as
`MAX_TABLE_COLUMNS`, `MAX_RESULT_COLUMNS`, `MAX_JOIN_ROLES`,
`MAX_KEY_PARTS`, and `MAX_SECONDARY_INDEXES`.

Do not reintroduce `MAXIMUM_COLUMNS` as a universal array dimension. A table
shape, result shape, key shape, expression shape, and wire shape have different
contracts. Count validation uses checked `int` arithmetic at the first trust
boundary and every durable decode.

### 2. Reusable primitive column state

Add concrete, final primitive helpers in `river-base`; do not add an interface
hierarchy:

- `ColumnBitSet`: a reusable `long[]`, logical bit count, used-word count,
  `get/set/clear/reset/copy`, canonical trailing-bit validation, and direct
  bitmap encode/decode;
- `Utf8TextArena`: one reusable UTF-8 `byte[]` plus per-lane
  `int` offsets and lengths; it grows geometrically at statement/open time and
  never allocates per row;
- `SqlValueBuffer`: actual-count/growing `long[]` values, `int[]` descriptors,
  `ColumnBitSet` nulls, and a `Utf8TextArena`; and
- `IntRangeList`: one flat `int[]` with `first[]` and `count[]` metadata for
  ordered key/constraint parts.

These helpers have a small initial capacity of eight, grow by powers of two up
to their caller's semantic limit, clear only used prefixes/words, and retain
their high-water storage for owner reuse. Growth occurs only during parse,
bind, query `begin`, metadata load, or result-shape admission. A narrowly
scoped allocation failure leaves prior logical contents published and returns
`RESOURCE_EXHAUSTED`; capacity obtained by a successful sub-growth remains
retained as reusable high-water storage.

The helpers do not own SQL semantics. They replace repeated masks, offset
arrays, and text matrices while keeping callers concrete and local.

### 3. Immutable schema and key descriptors

Replace copied, maximum-sized `TableDefinition`/`TableSchema` state with an
immutable database-owned `ColumnDescriptorSet` containing exact-size type,
name, nullability, and name-index arrays. A physical `TableDescriptor` composes
that column set with storage layout, keys, and constraints; a persisted view
or query output composes it with lineage/expression metadata instead. This
keeps the 1,024 physical-table limit out of the 1,664-lane result/view path.

`TableDescriptor` contains:

- column descriptors, fixed offsets/widths, nullable/default flags, and
  expression-program ranges;
- the shared column set, whose packed UTF-8 name arena, offset/length pairs,
  and primitive open-addressed name-to-ordinal table are built once;
- zero or one primary `KeyDescriptor`, zero to 64 secondary
  `KeyDescriptor`s, foreign key descriptors, and check-program ranges; and
- stable table/object identity, positive `rowLayoutId`, encoded maximum row
  bytes, null-bitmap bytes, and catalog generation. Catalog generation changes
  on every descriptor publication. `rowLayoutId` changes only when the
  canonical physical column layout changes; metadata-only table/column/index
  rename or index-state publication reuses the immutable layout descriptor.
  A physical layout ID is never reused for a different layout.

`TupleShape` is the common exact-count description of ordered typed lanes and
supports up to 1,664 parts. `KeyDescriptor` composes a `TupleShape` but admits
only 1..32 parts and adds storage/constraint semantics. It contains
kind, uniqueness, part count, column ordinals, type descriptors, comparison
metadata, referenced key identity when applicable, and the admitted maximum
encoded bytes. Inline column constraints and table constraints both compile
to it. Index lookup, uniqueness, foreign keys, join access, catalog, and
`EXPLAIN` must not keep parallel lists of key columns.

Sessions and join roles retain a lightweight descriptor reference through a
concrete `SchemaPin` handle. Cache lookup and pin acquisition are one atomic
operation. Ownership transfers explicitly from bind to the opened cursor or
query; every failed `begin`, cursor/query close, session close, and
cancellation path releases exactly once. A public reusable result carrier
never owns a pin. DDL keeps its provisional descriptor session-owned and
outside the shared cache until catalog commit; abort discards it. Publication
keeps the previous descriptor alive until its last pin is released. Borrowed
name/type/key arrays remain database-owned, immutable, and valid only for the
pin lifetime. Tests cover source-schema reset after build, eviction racing an
active cursor, all failed-open releases, and provisional commit/abort
behavior. This states the zero-copy lifetime rather than copying a
1,024-column schema into each role.

`TableDefinition` becomes an explicitly closeable, reusable pin-owning handle;
`reset` and `close` release at most once and clear all metadata visibility. A
successful resolve moves one acquired pin into it. An explicit-session CREATE
may expose a provisional handle only while that creating session owns the
schema change; the session tracks that one handle, transfers its descriptor to
the cache and converts it to a normal pin on commit, and invalidates/releases
it on abort or commit failure. The implicit database CREATE returns only after
that conversion. No raw descriptor reference escapes without either the DDL
session ownership or a live pin.

Keep the descriptor cache bounded by a database-wide byte budget. Add
`river.sql.schema-cache=auto` to `river.properties`, using the same strict size
grammar and diagnostics as the materialized-store configuration. `auto` is
one sixteenth of `Runtime.maxMemory()`, clamped to `8MB..32MB`. An explicit
value is `8MB` through the smaller of `1GB` and one half of max heap.
`auto` budgets are evaluated jointly, and max heap below `32MB` is unsupported;
for every supported heap the two auto minima fit their combined half-heap cap.
Descriptors are charged deterministically by packed
payload plus conservative, eight-byte-aligned array/object headers, not by a
JVM-specific heap sampler. Decoding any legal 1 MiB catalog definition must
have a preflight charge no greater than 8 MB. Pinned entries cannot be evicted,
and a load with no eligible capacity returns `RESOURCE_EXHAUSTED`. The default
is enough for dozens of typical wide descriptors while a normal narrow
descriptor remains a few hundred bytes.

DDL reserves the descriptor charge, cache slot, and exact-name entry capacity
before durable definition building. The provisional descriptor owns that
reservation while session-local. After the head commit, publication is a
failure-free ownership transfer into the cache and caller pin; it performs no
new allocation or capacity admission. Thus the API cannot report cache
pressure for a table whose head was already durably committed. Fault tests
cover commit outcome uncertainty and every publication-transfer boundary.

### 4. One canonical tuple representation

Extend `TupleKeyCodec` into the common, allocation-free representation for
ordered scalar tuples. A caller-owned `TupleKeyBuilder` encodes directly into
reserved storage and a `TupleComparator` compares encoded inputs without
creating objects. One small `TupleInput` ownership interface is justified here
because there are two real providers: a contiguous heap/page buffer for index
keys and a page-spanning materialized stream for group/sort tuples. Both use
caller-owned reusable cursors; the comparator never gathers a multi-page tuple
into a temporary array. The representation defines, per part:

- type/comparison family and canonical normalized value;
- explicit NULL marker and SQL null-ordering mode supplied by the consumer;
- order-preserving encoding for exact numeric, Boolean, temporal, and Unicode
  scalar text values; and
- no row-ID in a generic query tuple, and a mandatory logical-row-ID suffix in
  every physical primary, unique, or nonunique index entry.

Text uses River's binary Unicode-scalar collation: compare decoded scalar
values, with no NFC/NFD normalization, case folding, locale, or padding.
Persisted index/FK key parts use their declared descriptor exactly; an FK child
and referenced part must have equal type, length, precision, and scale
descriptors (nullability is separate). Decimal and temporal values are encoded
at that descriptor's declared scale/precision, and time-zone-aware values use
River's existing canonical instant representation. Join equality first binds
both expressions to the existing common comparison descriptor, then encodes
that normalized value. Grouping uses each bound expression descriptor.

An immutable query tuple-order shape extends the type descriptors with
per-part ascending/descending direction and null placement. Direction/order
are comparator policy and are not duplicated in every encoded row.

The codec accepts a descriptor array plus offset/count, not four descriptor
arguments. Its arity is an unsigned varint/checked `int`, not a byte, and its
generic format maximum is 1,664 parts. The generic builder can encode a tuple
into a caller-supplied multi-page/materialized stream; a B-tree consumer
separately enforces 32 parts and the 3,072-byte user-key bound. `GROUP BY`,
`DISTINCT`, and sort keys therefore are not accidentally restricted to index
arity or key bytes.

For an index, the 3,072 user bytes include the tuple header, all part
markers/type/value encodings, and exclude only the logical-row-ID suffix. The
tuple-page, fence-key, WAL, and validation paths use the separately named
3,080-byte physical bound and account for slot/page headers before admitting an
entry. No codec constant ambiguously includes the suffix for one caller and
excludes it for another.

Replace the current ambiguous `MAXIMUM_ARITY`/`MAXIMUM_KEY_BYTES` with
separately named generic-tuple, index-user-key, and physical-index-key
constants. Generic materialized tuples use the 1,664-part and 4 MiB query-row
contracts; index pages never admit those larger bounds.

Change `TupleBTreePageCodec` so the page header stores `keySchemaId`, arity,
and a descriptor hash, not four inline descriptors. Full descriptors live in
the immutable catalog `KeyDescriptor`; page validation receives that
descriptor and verifies the hash, arity, type tags, key ordering, and bounds.
This removes the fixed-four blocker without copying 32 descriptors into every
B-tree page.

This tuple codec and `TupleShape` are the DRY commonality for:

- composite primary, unique, foreign, and secondary-index keys;
- equality/hash material for multi-column joins;
- full `GROUP BY` and `DISTINCT` identity; and
- `ORDER BY` and merge comparison prefixes.

The consumers retain their different SQL null semantics and byte budgets; a
single generic "key service" must not obscure those rules.

## Durable storage and catalog

### Variable-width base rows

Introduce the next table-row format with:

1. magic, version, zero flags/reserved state, unique `rowLayoutId`, and stable
   logical row ID in a fixed 32-byte header;
2. `ceil(columnCount / 8)` canonical null bytes;
3. compact fixed-width slots using widths appropriate to the type
   (`BOOLEAN` one byte, `DATE` four bytes, 64-bit exact/temporal types eight
   bytes, and offset/length pairs for text); and
4. packed variable text bytes.

Every declared column, including every primary-key part, is present in logical
ordinal order. The schema descriptor precomputes fixed offsets, null bytes,
and worst-case row bytes once. Column count is descriptor-derived and is not
duplicated in each row. Row decode resolves the exact immutable descriptor by
`rowLayoutId`, then validates version, layout identity, lengths, offsets, null
trailing bits, type canonicality, and zeroed unused bytes before publishing a
row. A missing or mismatched layout is `CORRUPTION`; a stable table ID alone
must never select a replacement layout for an older row.

The format is canonical little-endian and has a 32-byte header: 64-bit magic
at offset 0, 32-bit version at 8, 32-bit zero flags/reserved at 12, positive
64-bit `rowLayoutId` at 16, positive 64-bit `logicalRowId` at 24, followed by
the canonical null bitmap. The row length is supplied by the containing heap
slot/WAL record and covers the complete header, fixed region, and text region;
it is not duplicated in the row. Fixed slots are packed without alignment in
ordinal order immediately after the bitmap. A text slot is one little-endian
32-bit row-relative offset plus 32-bit byte length. Non-NULL text payloads are
contiguous in ordinal order after the fixed region with no gaps or overlaps.
NULL fixed/text slots, reserved fields, and any format-defined padding are
zero. Bitmap trailing bits are zero; Boolean and every fixed scalar use their
canonical encoding. Persisted offsets are never trusted: catalog decode
recomputes them from type descriptors. Decode compares the embedded logical ID
with the lookup key before it publishes any value.

CREATE/ALTER admission computes worst-case row bytes with checked arithmetic.
An oversized definition returns `RESOURCE_EXHAUSTED` with actual and maximum
bytes. DML also checks actual encoded bytes and never partially mutates a row
or its indexes.

### Internal row identity and tuple indexes

Base rows are addressed by a stable internal `long logicalRowId`, not by a
user primary-key scalar. When a primary key exists, its tuple index and every
unique/nonunique secondary index store exactly
`userTuple || logicalRowId`; there is no separate leaf value payload or
duplicate row-ID slot. Lookup returns the suffix. Primary and unique
enforcement probes the complete user-tuple prefix before publication; a
`NULLS DISTINCT` unique key with any NULL part skips conflict enforcement but
still stores its physical suffixed entry. Nonunique scans use the suffix as the
deterministic final ordering/tie-break. The same 3,080-byte physical bound and
layout apply to leaf and fence keys for every index kind.

Each table persists a monotonic next-logical-row-ID allocator in its own
object namespace. Range reservation is serialized and WAL-logged before use;
allocator advancement is not rolled back, so aborted IDs are skipped and
never reused. Row publication remains transactional. Exhaustion returns
`RESOURCE_EXHAUSTED`, and changing a primary key preserves the logical row ID.

`logicalRowId` is distinct from the append-only heap/MVCC
`heapVersionRowId`. An update retains the logical ID but writes a new heap
version ID. The shared `IndexedTableStore` append/version directory is the
sole allocator of physical heap-version IDs across catalog, allocator,
primary-map, and base-row mutations. A per-table physical cursor cannot agree
with that global stream and is forbidden. The per-table 64-byte watermark
therefore stores only `objectId` and `nextLogicalRowId`; its former physical
cursor slot is canonical zero. Storage, WAL, checkpoint, and result APIs use
those exact names; an unqualified `rowId` is not permitted where both domains
are in scope. The
W1a direct API supplies every logical column, including the temporary
single-part `BIGINT` primary key. Mutation allocates/injects the logical ID
into the physical row envelope, stores the base row under that ID, and
maintains a temporary scalar primary-key-to-logical-ID map used for
fetch/update/delete. That map is removed by K1 when the canonical tuple index
becomes the primary lookup path.

Logical-row IDs use the same nonrollbackable range-reservation discipline as
catalog IDs, with a durable allocator per table/object. An insert reserves its
exact admitted row cardinality before the user transaction publishes any row
or key; updates allocate none. Statement shape and ordinary transaction
capacity are preflighted before reservation to avoid unnecessary gaps. Abort,
conflict, and crash after successful reservation may leave gaps. Row validation
requires the base B-tree key, the physical-row `logicalRowId`, and any temporary
primary-map value to agree. The allocator is keyed by stable object ID and is
independent of catalog generation and row layout.

All direct exact/range/from scans expressed in user primary-key order traverse
the temporary scalar primary map and then fetch base rows by logical ID. A full
base scan may traverse logical IDs internally, but publishes the primary key
decoded from column zero and obeys the API's documented ordering. Direct and
SQL results and generated-key APIs never expose `logicalRowId`. Existing scalar
secondary indexes also store stable logical ID as their row identity/tie
payload in W1a; secondary lookup fetches the base by logical ID and returns the
current user primary key from the row. A primary-key update therefore does not
rewrite unrelated secondary entries.

Build the variable-key tree in `river-storage` using the existing tuple-page
format groundwork. It must provide allocation-free exact seek, leading-prefix
range seek, insert, delete, split, cursor, validation, checkpoint, and recovery
through caller-owned key and result buffers.

Change the tuple leaf slot so it does not repeat logical row ID beside the
suffixed key; the currently separate leaf `logicalRowId` field becomes reserved
zero or is removed in the direct format bump. The builder has distinct
`finishTuple()` and `finishPhysical(logicalRowId)` operations so query tuples
cannot accidentally receive a storage suffix.

W1a already changes the existing fixed-key base store to use logical row ID as
its `long` key. Until K1 lands, a temporary scalar primary map uses the current
fixed `long` index to map one non-null `BIGINT` user key to row ID. K1 replaces
that map with the tuple tree; it does not redefine or migrate base-row
identity. Retain the scalar map afterward as an internal fast provider only if
measurements justify it. It must implement the same `KeyDescriptor` contract
and produce the same locking, error, WAL, and `EXPLAIN` behavior; it is not a
separate SQL/catalog representation.

Replace text fingerprint collision chains and per-index previous `long`
arrays in `RelationalSecondaryIndexStore`/`RelationalRowMutation` with encoded
tuple slices and reusable mutation scratch. One DML operation:

1. collects/materializes the exact affected logical row IDs without publishing
   changes (INSERT already knows its row count);
2. computes with checked arithmetic the base plus old/new primary/secondary
   index mutations and reserves that many current
   `PendingMutationBuffer` slots, lock receipts, WAL bytes, and page work;
3. encodes old and new affected keys into its transaction-owned arena;
4. validates all unique and foreign-key probes;
5. publishes base-row and all index changes atomically; and
6. reuses the arena after commit/rollback completes.

The current pending-mutation capacity remains 384. An UPDATE of one row across
the primary and all 64 secondary indexes fits; a multi-row statement whose
exact mutation count exceeds 384 returns `RESOURCE_EXHAUSTED` before its first
lock or row/index publication. Capacity is a transaction mutation budget, not
a hidden column/key-part limit.

The WAL describes logical row ID, index identity, and length-delimited tuple
bytes with checked lengths. Redo, undo, checkpoint, validation, backup, and
vacuum must cover split and partially completed multi-index mutations.

`CREATE INDEX` over an existing table builds into a private index space. It
streams base rows, writes tuple/run bytes through the materialized external
sort, detects unique conflicts after full tuple comparison, bulk-builds and
validates the tree, then publishes one catalog/root reference atomically.

Reuse the existing durable building-index lifecycle rather than treating an
unbounded build as one transaction:

1. under the persistent schema-change/table-write gate, publish an
   `INDEX_BUILD` intent containing index/key IDs, table schema generation,
   private space owner, state `BUILDING`, and cleanup cursor before allocating
   a private page;
2. keep writers excluded for this milestone while ordinary readers continue
   on the old immutable table descriptor;
3. scan/materialize the fixed table snapshot and commit sorted private-tree
   writes in batches below transaction/WAL/page limits, updating only
   restart/cleanup metadata in the intent;
4. validate the complete private tree and, in one bounded transaction, publish
   the new table manifest/root and transition the intent to `READY`; then
   release the gate; and
5. on ordinary failure, tombstone the intent and stream cleanup. On startup,
   recovery discovers every non-`READY` intent before admitting writers and
   reclaims its private pages from the recorded owner/cursor. This milestone
   restarts rather than resumes an interrupted build.

No non-`READY` index is visible to binding, DML, checkpoint roots, backup, or
query planning. Build batches do not enqueue one transaction mutation or
retain one Java object per table row. Fault tests cover every intent, batch,
validation, manifest, READY, and cleanup boundary; concurrency tests prove a
writer cannot commit between the build snapshot and publication.

### Catalog records are chunked, not maximum-sized

Replace the monolithic table record with a versioned catalog manifest and
bounded children:

- one table manifest with schema ID/version, counts, row-layout bytes, primary
  key identity, and checksums of the child sets;
- column chunks containing at most 32 consecutive column records and their
  packed names/default/check program references;
- one key/index/foreign-key record with continuation chunks when needed;
- check/default expression-program chunks; and
- statistics chunks containing at most 128 consecutive columns.

Catalog physical addressing no longer uses the object-name hash as a unique
`long` key. Two persisted monotonic sequences allocate positive `objectId` and
`catalogRecordId` values. Object heads live at
`(CATALOG_OBJECT_HEAD_SPACE, objectId)`; manifests and children live at
`(CATALOG_DEFINITION_SPACE, catalogRecordId)`. Every definition record header
contains object ID, schema ID, generation, kind, ordinal, logical count,
payload length, and checksum. A DDL transaction reserves one contiguous child
record-ID range so the manifest can describe it with first ID/count plus the
child-set checksum. Before definition building begins, a separate
serialized allocation transaction durably reserves the object/layout IDs and
the child range. Only after that reservation commits may DDL encode children
and its manifest. An abort or crash after reservation leaves a gap. A
rollbackable transaction must not update either sequence and then claim the
update survives its rollback. Reservations are WAL-logged, IDs are never
reused or inferred from counts, and reopen advances from the committed
allocation record even when no object head references the reserved range.

Operation ordering is fixed: first freeze/admit the mutable caller schema into
an immutable session-owned provisional descriptor and compute its exact row
bytes, catalog chunks, deterministic cache charge, mutation count, and WAL
bytes; next acquire the persistent schema-change gate, reserve the bounded
session overlay/cache admission, and preflight the READY head, exact UTF-8
name row; only then commit the independent ID/range reservation, persist a
`CATALOG_DEFINITION_BUILD` intent covering the reserved range, and create the
per-object logical-row allocator as a durable private build artifact. The
operation never rereads either allocator through an older snapshot. Sizing or
final-publication admission failure occurs before reservation; once sizing
succeeds, later abort/failure may leave the documented ID gap but no visible
head, name, or shared-cache entry. Cleanup deletes the private logical-row
allocator together with an unpublished build, so an abandoned object cannot
retain a usable row namespace.

Manifest and children are written in bounded committed build batches below the
existing WAL, changed-page, frame, and mutation limits. Each batch advances the
intent cursor; no batch makes an object visible. After all records are present,
streaming validation recomputes the complete child checksum and descriptor.
One final small rollbackable transaction validates the already-durable private
logical-row allocator and atomically inserts the previously absent READY
object head (the catalog visibility point) and durable exact-name row (the SQL
visibility point).
The private BUILDING intent is deliberately append-only: after commit it is a
residual cleanup record whose identity must exactly match the READY head;
startup validates that pair and deletes the intent. A BUILDING intent without
a READY head is unpublished and its recorded private range is reclaimed in
bounded batches. This avoids updating rows committed after an older outer
transaction snapshot while still giving commit/abort an atomic publication
boundary. It is the same private-build discipline used for index construction,
not a claim that the current engine can stage a 1 MiB definition atomically.

The schema gate issues one persistent change token independent of a particular
`RelationalSession` object. The outer caller retains its rollbackable DDL
transaction and provisional descriptor; bounded internal builder sessions may
enter only with that same token to write intent batches. They cannot publish a
head, admit an unrelated transaction, or transfer the descriptor. Outer abort,
session close, or commit failure invalidates the token and drives bounded
cleanup; the user transaction is never secretly committed to make room for a
batch.

Name lookup uses the durable exact UTF-8 name table published in the same
transaction as the READY head. Rows are keyed by object ID and scans compare
the complete decoded name, so no hash collision can alias objects. The
creating session also owns a bounded prepared-descriptor overlay: it alone may
borrow the admitted immutable descriptor. Same-transaction inserts reserve
IDs through the durable private allocator before staging row mutations, so a
statement/savepoint rollback leaves a gap and cannot reuse an issued ID.
Savepoint rollback removes an affected overlay immediately but defers
private-build cleanup until terminal lock release. A borrowed unpublished pin
is authenticated against the still-visible overlay on every operation and
cannot escape that rollback boundary. Other sessions remain excluded by the
persistent schema-change gate.

Every record remains below the existing 8 KiB catalog row bound. Thirty-two
column chunks represent a 1,024-column table. Build batches preflight exact
row, WAL, changed-page, frame, and mutation use and stay within the existing
kernel bounds; do not raise those hot-path bounds merely to serialize wide
catalog metadata.

All column, key, constraint, default, and check-program children together are
limited to 1 MiB and 160 chunks. Small descriptors/programs share a chunk;
large programs continue across chunks. Child and manifest writes belong to the
private build batches, not the final publication transaction. At the later K2
maximum, the final transaction may contain 64 old reverse-FK deletes, 64 new
reverse-FK inserts, 65 primary/secondary root publications, and one head
publication: fewer than 200 mutations. It preflights exact WAL
and page use against existing limits. Statistics are refreshed through their
own bounded private build and use at most eight 128-column chunks. A legal
count whose encoded descriptor exceeds the byte/chunk budget returns
`RESOURCE_EXHAUSTED` before ID reservation or any build intent.

Statistics have their own head keyed by object ID. It contains the applicable
row-layout/catalog generation, first child record ID, child count, logical
column count, and checksum of the complete child set. New chunks remain
unreachable until one atomic statistics-head update commits. Statistics for a
different live layout/generation are treated as absent and the optimizer falls
back to defaults; malformed or missing chunks referenced by a committed,
applicable statistics head are `CORRUPTION`. Table drop retires the statistics
head in the same schema lifecycle.

The object-head insert/update is the sole publication and visibility point.
Creation publishes the already validated private manifest by its head pointer;
replacement publishes a new generation by one head update; drop publishes a
tombstoned head. The READY intent transition is atomic with that head change.
Object scans enumerate only head space and never expose an intent, manifest,
or child as a database object. Decode rejects
missing/duplicate/overlapping chunks, gaps, out-of-range counts, schema/hash
mismatches, noncanonical padding, and orphan references as `CORRUPTION`.
Catalog bootstrap and statistics refresh use exact-size scratch or stream one
chunk at a time.

W1a does not delete old/tombstoned generations. It retains them under an
explicit bounded catalog-byte budget and returns `RESOURCE_EXHAUSTED` before a
new DDL reservation when safe retention cannot be guaranteed. Later garbage
collection may delete a generation only after proving durable reachability is
zero: no active pin; no live/tombstoned head, build, or cleanup reference; no
live row/version/index/checkpoint-root reference; and WAL truncation beyond the
last reference. Checkpoint completion alone is never sufficient.

The lower indexed-storage recovery layer remains descriptor-independent: it
replays opaque row bytes, canonical order-preserving tuple bytes, physical
page/root changes, and allocation records before the relational catalog is
opened. Relational startup then loads every retained manifest generation,
validates recovered rows/index roots against their exact layout/key IDs, and
only then admits sessions. No storage/WAL decoder calls upward into the schema
cache. A missing retained descriptor during relational validation is
`CORRUPTION`, never interpreted using the current schema.

K2 adds one durable reverse-foreign-key catalog tuple index. Every FK receives
a stable positive `foreignKeyId`; the reverse user tuple is
`(referencedKeyId, foreignKeyId)` and its physical suffix identifies the child
catalog object. Creating, replacing, or dropping a child definition mutates
its forward descriptor and reverse entries in the same bounded catalog
transaction. Parent UPDATE/DELETE, referenced-key drop, and table drop stream
the referenced-key prefix, pin one child descriptor at a time, and either use
a compatible child index or scan that child table to enforce immediate
`NO ACTION`. They never scan/cache every database descriptor per parent row.
Recovery validates reverse entries against retained forward generations;
missing, stale, or mismatched pairs are `CORRUPTION`.

Directly increment catalog, row, tuple-page, checkpoint, and WAL format
versions. Do not add old-format readers or upgrade shims.

## SQL grammar, binding, and semantics

### Table and constraint grammar

Accept both inline and table forms:

```sql
CREATE TABLE order_line (
  warehouse_id BIGINT NOT NULL,
  order_id BIGINT NOT NULL,
  line_no BIGINT NOT NULL,
  item_id BIGINT NOT NULL,
  quantity BIGINT,
  CONSTRAINT order_line_pk PRIMARY KEY (warehouse_id, order_id, line_no),
  CONSTRAINT order_line_item_fk
    FOREIGN KEY (warehouse_id, item_id)
    REFERENCES item (warehouse_id, item_id),
  CONSTRAINT order_line_qty CHECK (quantity IS NULL OR quantity > 0),
  UNIQUE (warehouse_id, order_id, item_id)
);

CREATE INDEX order_line_item
  ON order_line (warehouse_id, item_id, order_id);
```

Remove the rules that the first column must be the primary key, that it must be
`BIGINT`, and that references target only one `BIGINT` primary key. Permit zero
or one primary key per table at any ordinals; a table without one remains
addressable internally by logical row ID. Every primary-key part is implicitly
`NOT NULL`. Identity remains legal on at most one `BIGINT` column; if it is
part of a composite key, generation supplies that part and the caller supplies
the others.

Key binding validates 1..32 parts, unique column names within the key, exact
child/parent descriptor equality, and a referenced primary or unique key with
identical arity and part order. River does not silently
create a child-side foreign-key index; users may add one for performance.

SQL null semantics are fixed as follows:

- primary-key parts cannot be NULL;
- unique keys use `NULLS DISTINCT`: if any part is NULL, multiple rows do not
  conflict;
- foreign keys use `MATCH SIMPLE`: if any child part is NULL, the row satisfies
  the constraint without a parent probe; otherwise all parts must match; and
- group/distinct tuple equality treats corresponding NULLs as equal, while
  join `=` never matches NULL.

Foreign keys are immediate `MATCH SIMPLE` with `NO ACTION` for parent update
and delete. The parser accepts no action clause in this slice; recognized
explicit `MATCH FULL`, `ON UPDATE`, `ON DELETE`, deferred, cascade, set-null,
or set-default forms return `FEATURE_NOT_SUPPORTED`, never partial
single-column behavior.

### Wide parser and binder state

Convert `SqlCommand` and subordinate objects from maximum-sized fields to
reusable arenas with counts/ranges. Identifiers are packed into one command
name arena. Values, descriptors, column ordinals, constraint parts, aggregate
mappings, role mappings, and expression nodes are primitive arrays sized to
the admitted statement.

Every entry point applies the same 1 MiB UTF-8 statement limit. The protocol
validates bytes before decode; the embedded Java API counts strict UTF-8 bytes
while scanning its `CharSequence` and rejects malformed surrogate input. The
parser neither creates a second encoded statement nor has a different hidden
character-count ceiling. Stored view SQL has the separately documented
256 KiB durable byte limit.

Bound parameters borrow the admitted reusable `ParameterSet` for the whole
execution. Parameter ordinals are `int`; null state is word-backed; scalar and
text access reads primitive values/slices without creating wrapper objects.

Use `int` for column, expression, aggregate, plan-step, and role ordinals.
`byte` is permitted only for a closed enum kind. No set of columns or roles is
stored in one primitive integer mask.

Parsing is append-only into unpublished scratch. At statement completion,
validate all counts and byte estimates, then publish the command shape.
`reset` clears counts and used bitmap words but retains arrays. Command copy
copies only used ranges. INSERT row values use row-major offsets calculated
with checked multiplication and must not eagerly reserve
`MAX_INSERT_ROWS * MAX_TABLE_COLUMNS`.

Binding builds one exact immutable plan shape or reuses session high-water
arrays. It resolves a column name through the immutable table name index and
stores `(role, ordinal)` once on each bound scalar node. Execution does not
perform name lookup or allocate descriptors.

### Grouping, aggregates, and HAVING

Generalize the grammar and bound model to a comma-separated list of zero to
1,664 `GROUP BY` expressions. `SqlAggregateSet` holds actual-count aggregate
program ranges and `int` mappings. Output consists of any admitted projection
over group expressions and aggregate results; there is no privileged group
lane zero.

At operator `begin`, evaluate each input row's group expressions into one
reusable `SqlValueBuffer`, encode the entire tuple through the canonical tuple
codec, and use the materialized query store for ordering/group identity. Group
comparison checks every tuple part with group NULL semantics. Accumulators are
flat primitive arrays indexed by aggregate invocation and are reset in place
between groups. Text aggregate state borrows query-owned materialized storage
or copies into one reusable arena at the lifetime boundary.

`HAVING` binds any group expression, aggregate invocation, or alias allowed by
River's SQL conformance profile. Its dynamic Boolean program is prepared once
and evaluated against one role-aware group/aggregate provider. A reference to
group or aggregate ordinal 8, 63, 255, or 1,663 must behave exactly like
ordinal zero.

`DISTINCT` and `ORDER BY` use the same encoded tuple comparator and
materialized external-order machinery. They do not add another row store,
sort format, collation, or null-mask representation. This slice depends on and
must preserve the unbounded disk-backed design in
[`materialized-query-store-capacity.md`](materialized-query-store-capacity.md).
Run/group entries cache a fixed hash and short encoded prefix only as candidate
accelerators; equality and order always recheck the complete materialized
tuple, so long common prefixes do not create a second semantic comparator.

### Joins and planner scaling

Raise one query block to 64 relation roles and convert all role/stage state to
actual-count primitive arrays. A role owns a cursor and lazily created owned
row image only when the source lifetime requires copying; a 64-way plan must
not construct 64 maximum-width row matrices before execution.

Keep the logical left-to-right join chain and outer-join barriers. Access
selection may bind a composite equality vector when all parts compare an
earlier-role tuple with one right-role primary/unique/index prefix. It encodes
one probe tuple and rechecks the complete `ON` predicate. A partial or unsafe
vector remains a candidate generator or falls back to scan without changing
semantics.

Do not extend the accepted eight-role subset enumeration to `2^64`. Use:

- exhaustive inner-island enumeration only through eight roles;
- deterministic greedy extension for larger inner islands using row estimates,
  available tuple-index prefixes, and materialization cost; and
- written order across outer-join and semantic barriers.

Planner work is therefore bounded polynomially after role eight. `EXPLAIN`
reports the strategy, chosen key parts/prefix length, estimated rows, barriers,
and when greedy planning was used. Plan/result ordinals are `int`; source
bitsets use `ColumnBitSet`/a dedicated word array rather than `long`.

Nested-loop remains the universal correctness fallback. Hash and merge joins
encode composite equality tuples through the same codec, retain their existing
total-expression/error-order eligibility checks, and spill through the shared
materialized store. Their bucket/run state is sized from admitted input and
page-pool reservations, not role or column maxima.

## Public results, protocol, client, and JDBC

### Caller-owned result buffers

Replace `CommandResult`, `RowResult`, `ProtocolResponse`, `SqlScanRowResult`,
`SqlProjectedRow`, and block-row `char[][]`/fixed arrays with the reusable
`SqlValueBuffer` layout. Public result objects remain caller-owned. Query open
admits immutable result metadata but cannot assume which `RowResult` a caller
will pass to `RiverQuery.next(RowResult)`. Add
`RowResult.reserve(QueryMetadata, StatusDetail)`. The caller, Java client, and
JDBC adapter must invoke it once for each result owner before the first fetch.
`next` only validates that the supplied row carries the query's reserved shape;
it never grows storage. An insufficient row returns `RESOURCE_EXHAUSTED` with
the row and query position unchanged; the explicit `reserve` call supplies the
detailed requested/allowed diagnostic. Once reserved, each fetch resets and
fills the same storage without allocation.

Remove the public `long nullMask()` contract. Preserve `isNull(int)` and, where
bulk access is required, expose a bounded copy into caller-supplied `long[]`
words. Do not return an internal mutable bitmap or text array. Column names use
one metadata arena with offset/length accessors.

Also remove the assumption that every result has one scalar `key()`.
Relational scan carriers expose an opaque `logicalRowId()` only to internal
mutation/reload code; ordinary SQL `CommandResult`, query `RowResult`, and wire
rows expose projected columns and row ordinal, not physical identity. A
generated identity is an ordinary typed returned value with an explicit
`generatedValueAvailable` flag. It is never populated from logical row ID or a
composite primary key.

The narrow common case still starts at eight lanes. Creating a result/session
must not allocate capacity for 1,664 values or 1,664 maximum `VARCHAR`s.

### Protocol

Replace frame protocol v3 with `ProtocolFrameV4Codec` version 4 and the current
v4 data envelope with `ProtocolV5EnvelopeCodec` version 5. Encode counts as
checked unsigned varints/16-bit fields supporting 65,535, followed by
length-delimited null bitmaps and actual-count descriptor/name vectors. Keep
ordinary transport frames bounded at 16 KiB.

Before authentication or data frames, a fixed-size connection preface
exchanges the exact `(frame=4, envelope=5)` pair. The server returns the
accepted pair or a stable `FEATURE_NOT_SUPPORTED` code in the preface and
closes; malformed magic/length closes without allocating or reading a payload.
Only the exact pair is supported in this pre-V1 slice. No v3/v4 data codec is
invoked after negotiation, and the old codec classes/tests are removed rather
than mutated under misleading names.

Metadata and row payloads may span continuation frames. Each segment carries
query/row identity, total checked length, segment offset, and final marker.
The receiver rejects gaps, overlaps, duplicates, excess total length, invalid
UTF-8, noncanonical null bits, and a final segment before publishing the row.
It assembles into the query's reusable 4 MiB result arena; there is no
per-segment object list or per-row allocation.

Requests use the same bounded continuation mechanism. Statement text is
admitted against 1 MiB before UTF-8 decode; parameter metadata/null words and
values are admitted against 65,535 parameters and 16 MiB total encoded bytes.
Segments carry request identity, kind, total length, and checked offset; the
server does not parse or execute until the complete request has passed gap,
overlap, length, canonical bitmap, descriptor, and UTF-8 validation.

Add a reusable client/server `ParameterSet` with primitive descriptors,
offsets/lengths, null words, fixed values, and one UTF-8 arena. JDBC prepared
statement setters and the Java client fill it without per-parameter objects;
the server borrows the assembled request arena through execution and releases
it on completion/cancel/connection close. A 65,535-`BIGINT` parameter request
must work even though neither it nor its descriptor vector fits one 16 KiB
frame or the former 256 KiB v4 request envelope.

The new v5 envelope parameter/column checks, v4 frame response math,
encoder/decoder null bitmaps, scalar response-key removal, and maximum
request/response constants change together. Old and new peers fail the fixed
version negotiation explicitly; no eight-column fallback is permitted.

### Client, CLI, and JDBC

The Java client and JDBC driver size metadata/value arrays to actual admitted
shape and reuse them. `DatabaseMetaData.getMaxColumnsInTable()` returns 1,024,
`getMaxColumnsInSelect()` returns 1,664, and `getMaxColumnsInIndex()` returns
32. Index metadata emits one row per key part with correct `ORDINAL_POSITION`
and can stream 65 index definitions (64 secondary plus the primary) rather
than retaining a five-row matrix.

`DatabaseMetaData.getPrimaryKeys()` emits one row per primary-key part and an
empty result for a table without a primary key, with `KEY_SEQ` 1..32 in
declared order. Replace `RiverPrimaryKeyResultSet`'s one-row/single-column
state. `getImportedKeys()`, `getExportedKeys()`, and `getCrossReference()`
stream one metadata row per foreign-key part with correct `KEY_SEQ`, names,
referenced key, and immediate `NO ACTION` rules; they do not materialize a
64-by-32 object matrix. JDBC `getGeneratedKeys()` returns only an
identity/default value actually generated by DML, with its SQL type; it does
not return the opaque logical row ID or invent one value for a composite key.

CLI rendering walks actual metadata and may stream/truncate display width; it
must not reject a valid result because a terminal cannot sensibly display
1,664 columns. Backup, restore, views, and stored SQL use chunked/streamed
metadata and raise stored query text capacity to 256 KiB so generated wide
definitions are not rejected by the former 768-byte view text record. This is
an independent byte limit and is persisted in continuation chunks, not one
catalog row.

Creating a `String` because a JDBC/client caller explicitly requests one is a
public-boundary allocation and is not counted as an engine/fetch-row
allocation. Fetch, decode, null/type access, and slice access themselves remain
allocation-free after query open.

## Memory, allocation, and copy contract

The wide limits are not licenses for eager maximum allocation.

### Ownership and lifetimes

| Storage | Owner | Lifetime | Reuse/immutability |
| --- | --- | --- | --- |
| Table/key descriptor arrays and packed names | Database schema cache | Schema pin/generation | Immutable after publication; evicted only when unpinned. |
| Parser/binder arrays and name/value arenas | SQL session | Session | Reused by statements; only used prefixes reset. |
| Row values/null words/text arena | Cursor/result/operator | Query or cursor | Refilled per row; result-owned values remain valid until that owner is reset/refilled, while a borrowed cursor/page slice ends at its documented advance/unpin. |
| Encoded index/probe tuple | Transaction or operator scratch | Mutation/probe | Directly encoded into reserved buffer and reused after operation. |
| Materialized/group/sort bytes | Query scratch files/page pool | Query | Disk-backed; page borrow ends at unpin. |
| Protocol assembly/metadata arenas | Connection/query | Query | Reused across frames and rows; bounded by admitted totals. |

### Hot-path rules

- No object, array, string, boxed value, iterator, stream, captured lambda, or
  varargs allocation per scanned, joined, grouped, sorted, indexed, encoded,
  decoded, or returned row after warm-up.
- No schema or key-part copy per row. Operators retain immutable descriptors
  and primitive ordinals established at bind/begin.
- Null checks are word/bit operations on reusable arrays. Loops use actual
  counts and hoist used-word/count values.
- Text crosses a lifetime boundary once: borrowed page/protocol text is copied
  into a reusable owner arena only when the source may advance. Comparison and
  tuple encoding consume slices directly.
- Tuple indexes encode into provider-owned reserved page/WAL storage when the
  final length is known. If sizing needs a first pass, it performs no copy;
  there is at most one River-owned payload copy into each necessary durability
  or lifetime boundary.
- Narrow single-key/single-row paths retain specialized loops where measured,
  but share descriptors, semantics, statuses, and formats.

### Bounded growth and failure

All arrays and arenas have a semantic count cap plus a byte cap. Growth uses
checked arithmetic and publishes only after successful allocation. Query
operators reserve page-pool frames before mutating output. Descriptor-cache,
session-arena, materialized-page, protocol-row, index-key, and row-format
pressure return `RESOURCE_EXHAUSTED` with the named resource and
requested/allowed count or bytes.

Do not catch `OutOfMemoryError` across statement execution. Catch it only
around a single unpublished array/direct-buffer construction, restore the
previous owner state, and translate it to `RESOURCE_EXHAUSTED`. GC, timing, or
soft/weak references must not determine correctness or eviction.

The transaction lock-receipt array starts at the current narrow capacity and
may grow during mutation preflight to at most 4,096 receipts. Preflight uses
the bound table's foreign keys and affected indexes to reserve receipts before
the first lock or page mutation. This admits a single-row operation at the
maximum constraint/index counts without per-lock allocation; a larger
multi-row statement that cannot fit returns `RESOURCE_EXHAUSTED` before
publication. The global lock table remains independently bounded.

The session's retained SQL-shape scratch is capped at 8 MiB. A statement whose
parser/binder arrays would exceed that uses the materialized page pool for
large literal/value payloads where supported or returns `RESOURCE_EXHAUSTED`.
The cap excludes database-owned immutable descriptors and disk-backed query
payload. It is large enough for the maximum counts above but prevents many
idle sessions from retaining arbitrary statement text or literal storage.

Add a database-wide `river.sql.session-shape-cache=auto` retained-heap budget.
`auto` is one eighth of max heap clamped to `8MB..64MB`; explicit values use
`8MB..1GB`. Schema-cache plus session-shape-cache may not exceed one half of
max heap. A session reserves deterministic charged bytes before growing and
releases them on close; failed reservation leaves its old arrays intact and
returns `RESOURCE_EXHAUSTED`. Thus an unbounded number of open sessions cannot
each retain the 8 MiB high-water maximum outside a database-wide bound.

### Status classification

- An admitted grammar form whose count, encoded row/key/schema bytes, scratch,
  or pool reservation exceeds its documented bound returns
  `RESOURCE_EXHAUSTED` and names requested versus allowed units.
- Duplicate names/parts, type or arity mismatch, invalid references, malformed
  SQL, and invalid external configuration return `INVALID_EXTERNAL_INPUT`.
- Recognized SQL forms deliberately outside scope return
  `FEATURE_NOT_SUPPORTED`.
- Invalid durable or wire counts, chunks, ordering, padding, hashes, or lengths
  return `CORRUPTION`; Java I/O failures remain `IO_FAILURE`.

Validation completes before catalog publication, cursor open, lock
acquisition, or result publication whenever the required size is knowable.

## Concurrency, locking, and recovery

Canonical user-tuple bytes are the authoritative identity for equality,
uniqueness, foreign-key, and predicate-lock decisions. The owning key ID is
the namespace; the tuple header and physical logical-row suffix are not part
of the protected user point. Hashes may accelerate directory lookup, but a
grant, collision decision, and final equality check compare the complete
canonical bytes. Child foreign-key probes use the referenced parent key ID and
the same tuple identity, so they conflict directly with parent mutation.

Single-key equality and multi-index mutation lock ordering is deterministic:
table ID, key/index ID, canonical tuple order, then logical row ID. One shared
mutation-protection path covers every physical index: INSERT protects each new
user point, DELETE protects each old user point, and UPDATE protects both old
and new points in tuple order. An unchanged UPDATE point is protected once
even though physical index maintenance remains suppressed, because a
SERIALIZABLE secondary-index reader must conflict with the base-row change.
Unique and parent-key locks are acquired before validation and before the base
mutation is staged. Foreign-key child insert versus parent delete/update tests
must prove no orphan can commit.

Serializable tuple scans use variable-length ordered tuple endpoints in the
same canonical lock provider, FIFO queues, ownership records, and deadlock
graph as exact tuple points. A scan acquires its root and tuple interval before
opening or refreshing the cursor; mutation points overlap that interval by
canonical tuple order. Prefix bounds use explicit before/after cuts so
inclusive and exclusive composite-prefix semantics include exactly the
intended descendants. Whole-index/table locks and hashed range endpoints are
not acceptable substitutes and no compatibility path retains them.

Recovery replays catalog publication, logical-row allocation, base-row
changes, every affected tuple-index change, and root/page splits idempotently.
A crash at any injected WAL boundary must expose either the old complete row
and index set or the new complete set, never a base row without its key entries
or a published manifest with missing chunks.

## Delivery slices and dependencies

Each slice is a smallest end-to-end capability and has one integrator. Storage
and protocol builders own disjoint files; no two agents redefine the descriptor
or tuple contract.

### W0: limits and reusable shape primitives

Deliver `SqlShapeLimits`, `ColumnBitSet`, packed UTF-8 text/value buffers, bounded
growth helpers, source-policy checks, and allocation/unit/property tests.
Convert one private result carrier to prove the API before broad migration.

Exit: no helper eagerly allocates a maximum-wide text matrix; 1,664 bits and
lanes grow/reset correctly; narrow construction allocation stays near the
eight-lane baseline.

W0 completed on 2026-08-25. Acceptance evidence:

- the reusable helpers start empty and use small bounded geometric growth
  (eight entries/bytes where applicable and one bitmap word), transactional
  logical publication on growth failure, and warmed allocation tests for
  lane, UTF-8, bitmap encode/decode, and carrier paths;
- randomized property coverage compares bitmap operations and canonical
  round trips at word/byte boundaries through 1,664 bits and checks range-list
  behavior against a flat reference model;
- `verifySqlShapeSourcePolicy` counts occurrences of compound null-mask names
  and column/index/lane/projection shifts against exact per-module baselines;
  its fixture task covers camel/snake/plural, qualified/parenthesized, two on
  one line, and unrelated scalar-bit negative cases;
- the private `SHOW` catalog carrier uses `SqlValueBuffer`, admits at most five
  lanes and 512 UTF-8 bytes, publishes through the existing caller-owned result
  boundary, preserves the 64-supplementary-scalar text boundary, and allocates
  no bytes per warmed row; and
- `:river-base:test`, the full `:river-engine:test`, and both SQL-shape policy
  tasks pass. Independent adversarial re-review found no remaining W0 blocker.
  Every new production class scores below 50 in Slopmark (maximum 17.9823),
  and the converted catalog code decreases from 5.68752 to a combined 5.

### W1a: variable descriptors, catalog chunks, and row format

Deliver immutable table/key descriptors, schema cache/admission, chunked
catalog/statistics, the variable null bitmap, compact row encoding, and direct
format version bumps. Convert the direct relational definition/mutation/read
path first and initially use a single-part `BIGINT` primary key through the
temporary scalar primary-key-to-row-ID map. W1a also persists/WAL-logs the
logical-row-ID allocator and keys every base row by row ID, freezing identity
semantics before K1. This is foundation with W1b as its named immediate
consumer; it does not claim embedded SQL completion.

Exit: a table containing one `BIGINT` primary key and 1,023 `BOOLEAN` columns
created through the direct relational test path survives checkpoint/reopen and
row/null values at ordinals 0, 7, 8, 63, 64, 255, 1,023 are correct. A schema
whose encoded row exceeds 8,192 bytes fails before catalog publication with an
exact diagnostic. Acceptance also proves:

- catalog chunk boundaries at 31/32/33 and 1,023/1,024 columns, and rejection
  of missing, duplicate, reordered, or corrupt records referenced by a head;
- fault outcomes before/after ID reservation, intent, each build batch,
  manifest validation, and atomic head/READY publication; abort or commit
  failure exposes no descriptor while committed allocation gaps remain;
- logical-row IDs skip aborted inserts, remain stable across primary-key
  update, checkpoint, and WAL-only reopen, and always match the base key,
  physical envelope, and temporary primary map;
- an active pinned cursor survives metadata publication and cache eviction;
  failed opens, cancellation, close, provisional commit, and provisional abort
  release or transfer the pin/charge exactly once;
- narrow construction creates no 1,024-sized array, while warmed narrow and
  1,024-column row encode/decode/scan paths allocate no bytes per row; and
- an exact 8,192-byte row succeeds while 8,193 bytes fails during freeze before
  ID reservation, catalog intent, locks, or row mutation.

### W1b: embedded SQL wide-table vertical slice

Convert SQL command/parser/binder, projection/insert/update state, scan/block
rows needed by the direct path, and engine API results. This slice is the first
user-visible embedded SQL capability; it may reject a wide query that requires
an operator not yet converted, but direct create/insert/update/filter/project
must work.

Exit: create, insert, project, filter, update, return, and reopen a
1,024-column table through embedded SQL; publish a 1,664-lane
embedded result assembled from existing joined/derived inputs; reject 1,665
atomically; and preserve narrow allocation gates.

### W2: protocol, client, JDBC, views, and materialized rows

Convert the materialized row codec, protocol continuation, client, CLI, JDBC,
request `ParameterSet`, view output/lineage, stored SQL chunks, backup, and
restore. Reuse the W1b engine/result shapes without a boundary-specific column
representation.

Exit: stream and consume the W1b maximum cases through protocol/client/JDBC;
send 65,535 fixed parameters and a multi-frame statement/request;
persist/reopen a 1,664-column view; expose correct JDBC metadata/index parts;
back up and restore a maximum-width table/view; reject corrupt or oversized
continuations before result publication.

### K1: variable tuple tree and composite primary/unique indexes

Generalize the tuple codec to 1,664 parts and tuple B-tree pages/keys to 32
parts, implement the storage tree and WAL, replace the temporary scalar primary
map with the tuple index, add table-level primary/unique/index grammar, and
replace single-column secondary-index mutation state. Base rows and row/result
ownership remain on the W1a logical row-ID contract.

Exit: 32-part mixed-type primary, unique, and nonunique keys support seek,
prefix range, update/delete, checkpoint/recovery, split/vacuum, NULL semantics,
and all 64 secondary index definitions. Part 33, index 65, and a 3,073-byte
user key fail before publication.

### K2: composite foreign keys and checks

Deliver common constraint representation, multi-column/table checks,
`MATCH SIMPLE` composite FKs, the durable reverse-FK catalog index,
deterministic locking, and recovery/concurrency tests.

Exit: arity/type mismatches fail at DDL; NULL and full-match semantics are
correct; parent/child races cannot orphan data; multiple child tables are
found without a global descriptor scan; referenced unique/table drop is
blocked; forward/reverse publication is crash atomic; and high-ordinal check
columns work through reopen and recovery.

### G1: multi-expression grouping, HAVING, DISTINCT, and ordering

Deliver list grammar/binding, tuple group identity, actual-count accumulators,
generalized `HAVING`, and materialized external order integration.

Exit: group and project ordinals above eight, group 1,664 narrow expressions
(including repeated/computed expressions over a 1,024-column table), produce a
1,664-lane aggregate result, spill/replay mixed NULL/text tuples, and match the
non-spill reference result.

### J1: 64-role joins and composite access

Convert role/plan/row workspaces, lineage, planner fallback, and hash/merge
tuple equality. Preserve all accepted n-table join semantics and the common
materialized store.

Exit: 64 one-row roles execute with correct aliases, self-joins, INNER/LEFT
semantics, high-role predicates/projection, views, grouping, and `EXPLAIN`; a
65th role fails during parse/bind without opening a cursor. Planner time and
memory remain bounded on a 64-role query.

### F1: integration, performance, docs, and limit removal

Run affected-module and full policy verification, allocation/benchmark gates,
fault/recovery suites, and update the conformance profile, limitations,
roadmap, protocol/JDBC docs, and old plans. Mark the eight-role clauses in
`m5-n-table-joins.md` superseded and remove the corresponding limitation from
alpha delivery docs.

Exit: every old fixed-width production match has an explicit resolution, all
acceptance evidence is recorded, and the independent reviews are closed.

Dependencies are `W0 -> W1a -> W1b`,
`W1b + materialized-query-store-capacity -> W2`,
`W1b + materialized-query-store-capacity -> K1 -> K2`,
`W1b + K1 + materialized-query-store-capacity -> G1`, and
`W2 + K1 + G1 -> J1 -> F1`. K1 may develop its storage tree beside W2 only
after W1a freezes the descriptor/row/catalog contracts and W1b freezes the
SQL key/result shape, with disjoint file ownership.

## Test strategy

### Boundary matrix

Use generated SQL/test builders so tests are reviewable without committing
megabytes of literal SQL. Exercise the discontinuities, not just maximums:

- columns/result/group lanes: 1, 8, 9, 63, 64, 65, 255, 256, 1,023, 1,024,
  1,025, 1,663, 1,664, and 1,665 where the relevant semantic limit permits;
- join roles: 1, 8, 9, 32, 61, 64, and 65;
- key parts: 1, 2, 4, 5, 16, 17, 31, 32, and 33;
- secondary indexes: 0, 4, 5, 63, 64, and 65;
- foreign-key definitions: 0, 4, 5, 63, 64, and 65;
- null bit ordinals: 0, 7, 8, 63, 64, 127, 255, 511, 1,023, and 1,663; and
- encoded row/key/result bytes: exactly limit minus one, limit, and limit plus
  one, including checked integer overflow inputs.

### SQL and relational semantics

- Wide CREATE/INSERT/default/NULL/UPDATE/DELETE/SELECT/RETURNING and `SELECT *`
  through direct, block, view, subquery, materialized, and protocol paths.
- 32-part keys with mixed numeric, Boolean, temporal, and Unicode text parts;
  leading-prefix seeks and residual recheck; duplicate suffixes; primary-key
  mutation; all-null/some-null unique and FK cases.
- Table and inline constraint forms compile to identical descriptors and
  behavior. Duplicate part names, wrong arity/order/type, missing referenced
  uniqueness, and ambiguous high-ordinal names fail deterministically.
- Multiple grouping expressions with permutations that differ only in late
  parts, NULL positions, canonically equivalent but binary-distinct Unicode
  spellings (which must remain distinct), aggregate counts above eight,
  aliases/high ordinals in `HAVING`, and spill/non-spill equivalence.
- 9/32/64-role self/aliased INNER and LEFT joins, composite index lookup,
  residual false/unknown, duplicates, prior null extension, views/subqueries,
  and one-row inputs that avoid accidental Cartesian test explosions.
- JDBC column/index metadata has correct counts, names, types, nullability,
  index name, and part ordinal; protocol segmentation is transparent to the
  client.

### Tuple codec and storage properties

Property-test encoded ordering against a slow reference comparator for every
type and mixed tuples. Cover equality, antisymmetry, transitivity, prefix
ranges, NULL modes, 1,664-part non-index tuples, Unicode scalar boundaries and
absence of Unicode normalization, descriptor-bound decimal/temporal
canonicalization, and the row-ID suffix. Fuzz malformed arity, descriptors,
lengths, trailing bytes, slots, high keys, and ordering.

Run variable-key tree split/merge/cursor/model tests with minimum, maximum,
duplicate, and common-prefix keys. Validate every page after randomized
operations and after checkpoint/reopen.

### Catalog, transaction, and recovery

- Reopen maximum-width tables with zero/maximum keys, constraints, indexes,
  checks, long names, defaults, and statistics.
- Corrupt each catalog manifest/chunk field; remove, duplicate, reorder, or
  overlap chunks; use wrong counts/schema hashes/continuations and require
  `CORRUPTION` without partial publication.
- Fault-inject before and after child records, manifest publication, base-row
  WAL, each tuple-index mutation, page split/root update, checkpoint, and
  cleanup. Reopen must yield the complete old or new state.
- Rollback DDL and DML affecting 64 secondary indexes plus the primary and
  prove there are no orphan records/entries. Validate backup/restore and vacuum
  after wide/composite mutations.
- Exercise DML preflight at exactly 384 and 385 pending base/index mutations;
  the latter leaves every row, tree, lock, WAL reservation, and cursor position
  unchanged.
- Race writers with `CREATE INDEX`, fault every BUILDING batch/READY-head boundary,
  reopen, and prove recovery either exposes the complete READY index or
  discovers the intent and reclaims every private page before admitting a
  writer.
- Race duplicate composite inserts, key-changing updates, parent delete/update
  against child insert, descriptor eviction against active readers, and close
  against active wide results.
- Create inbound FKs from multiple child tables, enforce parent mutation with
  and without child-side indexes, block referenced-key/table drop, and inject a
  crash at every forward/reverse catalog publication boundary.
- Create, replace, and drop a table with 64 FKs at the worst legal 160-chunk
  catalog shape; assert the exact DDL mutation preflight remains below 384,
  then fault every forward delete/insert, manifest, head, and cleanup step.

Protocol property/fault tests segment statement text, 65,535 fixed parameters,
mixed/null parameter tuples, metadata, and maximum result rows at every byte
boundary. They reject missing/duplicate/overlapping/out-of-order segments,
wrong totals, cancellation, disconnect, and byte/count overflow without
executing or advancing a result. JDBC tests cover 0/1/32 primary-key metadata
rows, 65 index definitions, multi-part imported/exported/cross-reference rows,
and generated identity values distinct from logical row IDs.

### Allocation and bounded-memory gates

Use the existing `ThreadMXBean` allocation harness after warm-up:

- runtime-config tests prove joint schema/session `auto` calculations at
  32/64/128/256/512 MiB heaps, reject heaps below 32 MB, explicit range and
  combined-half-heap violations, and exact reserve/release accounting;
- narrow and 1,024-column scans/projections/results allocate zero bytes per
  row;
- composite exact seeks, index maintenance, FK/unique checks, grouped
  accumulation, hash/merge comparison, materialized encode/decode, and
  protocol fetch allocate zero bytes per row/probe after `begin`;
- parsing/binding a repeated same-shape statement allocates zero after session
  high-water warm-up, excluding the caller's SQL string;
- constructing a narrow session/result/schema does not allocate arrays sized
  to 1,024/1,664 lanes or 64 roles/indexes; and
- retained memory across many idle sessions is bounded by the documented 8 MiB
  per-session high-water cap, while schema/materialized pools honor their
  database budgets and return `RESOURCE_EXHAUSTED` under contention.

Add copy counters in benchmarks for base-row decode, tuple probe/mutation,
materialization, and protocol assembly. The accepted steady-state target is no
River-owned copy for fixed values, one copy only at an actual text
lifetime/durability boundary, and no additional copy caused by key arity.

### Performance gates

Add JMH/workload cases for 8 and 1,024 column rows, 1/16/32-part key seeks and
updates, 8/32/64-role one-row joins, and 1/64/1,024-part grouping with and
without spill. Record throughput, p50/p95 latency, allocation/op, copied
bytes/op, page reads/writes, and planner time.

For existing narrow workloads, the accepted change must add zero steady-state
allocation and no more than 5% median throughput regression on the reference
host. A larger regression requires a measured explanation and explicit
performance-review acceptance, not a silent limit trade. A 64-role planner
test must demonstrate bounded polynomial growth and complete within the
existing SQL test timeout without GC dependence.

### Verification sequence

During each slice, run one Gradle build at a time and use the narrowest target:

```sh
./gradlew :river-base:test
./gradlew :river-format:test --tests '*Tuple*'
./gradlew :river-storage:test --tests '*Tuple*'
./gradlew :river-engine:compileJava
./gradlew :river-engine:test --tests '*Wide*'
./gradlew :river-engine:test --tests '*Composite*'
./gradlew :river-engine:test --tests '*Grouped*'
./gradlew :river-engine:test --tests '*NTableJoin*'
./gradlew :river-protocol:test :river-client:test :river-jdbc:test
```

Expand to affected-module tests, repository policy checks, and finally
`./verify` only at the integration checkpoint.

## Required independent reviews

- Architecture: limits are semantic and the shared tuple/descriptor contracts
  remove duplication without creating a speculative service hierarchy.
- Durability/recovery: catalog chunks, row format, tuple pages, WAL atomicity,
  checkpoint, backup, and corruption handling.
- Relational semantics: composite NULL behavior, foreign-key matching,
  grouping/HAVING, outer joins, aliases, and index residual recheck.
- Performance/allocation: exact-size ownership, descriptor-cache accounting,
  no maximum-eager arrays, no per-row allocation, copy counters, planner
  scaling, and narrow-path regression evidence.
- Boundary/compatibility: protocol continuation and validation, public result
  ownership, JDBC metadata, direct version bumps, and diagnostic statuses.

No durable-format, recovery, concurrency, or public-boundary slice is accepted
solely by its author. Meaningful review findings are added to this plan or its
acceptance evidence with a named disposition before F1 closes.

### Aderserial K1 recovery review — 2026-08-25

The independent aderserial review found no P0 issue. It recorded these open
items; passing happy-path recovery tests does not close them:

- **P1, K1 release blocker:** checkpoint/reopen admission must enumerate the
  durable tuple-root registry, resolve the exact descriptor shape for every
  READY root, validate every tuple graph, reconcile reachable pages with page
  ownership, and reject or clean non-READY state. Envelope-only tuple-page
  validation is insufficient.
- **P1, K1 release blocker:** a committed grouped relational WAL record must
  have a positive commit sequence even when its sequence is covered by the
  selected checkpoint. Covered zero and negative sequences are corruption.
- **P2:** tuple-tree reconfiguration must transactionally reject a descriptor
  whose encoded user or physical key exceeds the index-key byte bound.
- **P2:** tuple-root registry lookup must preserve provider pressure and I/O
  statuses rather than collapsing them to corruption.
- **P2:** warmed grouped-WAL descriptor decode must reuse bounded descriptor
  shape state rather than allocate a new immutable shape per decoded group.
- **P2:** a failed relational tuple-key encode invalidates the previously
  borrowed result. This item is implemented and covered by the encoder's
  failure and warmed 32-part zero-allocation tests.

The recovery/checkpoint owner is responsible for closing the two P1 items and
the storage/replay P2 items with corruption, status-propagation, alternating-
descriptor, and warmed-allocation tests before K1 exit evidence is accepted.

## Completion checklist

- The documented count and byte limits are enforced once at the correct trust
  boundary and reported accurately by JDBC/docs.
- A 1,024-column table and 1,664-column result work end to end without eager
  maximum allocation.
- Primary, unique, foreign, secondary-index, grouping, distinct, ordering, and
  join equality use the canonical tuple representation with their explicit
  null semantics.
- A 32-part key and 64 secondary indexes survive mutation, rollback,
  checkpoint, recovery, backup/restore, and validation.
- Multi-expression `GROUP BY`/`HAVING` and 64-role joins work through the same
  materialized/result paths as narrow queries.
- No production column/role/property set uses a one-word mask, byte ordinal,
  fixed-four descriptor header, or literal eight-sized carrier.
- Allocation, copy, memory-pressure, concurrency, fault-injection, and narrow
  performance gates pass.
- Old alpha limits and protocol/durable versions are removed or explicitly
  superseded in roadmap, conformance, limitations, and component docs.
