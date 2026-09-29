---
id: tic-ent
status: closed
type: feature
priority: 1
assignee: blater
parent: tic-isildur
delivery: code
tags:
    - performance
    - sql
    - storage
    - ownership
    - utf8
created: 2026-09-28T23:14:16.440016Z
---
# Remove SqlValueBuffer and keep SQL execution text in UTF-8

Remove `SqlValueBuffer` and the mandatory conversion from stored rows into
successive owned value containers. Synchronous SQL operators must consume the
required values through reusable views with explicit lifetimes. Keep text as
UTF-8 throughout execution; copy or convert only for a named consumer need.

This is the owning implementation ticket for the value-representation and
borrowed-access work described in [tic-celeborn](tic-celeborn.md), under
[tic-isildur](tic-isildur.md). Implement the lifetime changes needed by this
replacement here, with one integrator and one contract. Do not run a competing
row-transfer implementation under tic-celeborn. That ticket retains the wider
invariant-admission and storage-trust audit. Reuse the column-demand contract
and workload evidence of [tic-healthy-bellodonna](tic-healthy-bellodonna.md).
Neither open ticket is a prerequisite requiring the other to close first.

## Current problem and reason for the copies

Source inspected: `a2cdddb8`, accepted checkpoint
`perf-checkpoint-20260928-headerless-rows`, on 2026-09-29 Europe/London.
Start production work from the latest pushed stable checkpoint and refresh this
trace if the source has advanced. The 32-byte row header and historical-layout
resolver are already removed; their savings do not belong to this ticket.

The descriptor JOIN path currently performs:

```text
visible stored row
  -> retained encoded row bytes
  -> SqlValueBuffer primitive arrays and owned UTF-8 arena
  -> SqlBlockRow primitive arrays and UTF-16 char[] storage
  -> predicate / JOIN / aggregate / projection
```

- [IndexedKernelRowAccess.fetch](../../river-engine/src/main/java/io/riverdb/engine/table/IndexedKernelRowAccess.java)
  retains row bytes before releasing the page pin. That copy currently creates
  a real lifetime boundary. Its removal requires changing the ownership
  contract, including pending rows and older visible versions.
- [StoredTableRowPublisher](../../river-engine/src/main/java/io/riverdb/engine/relational/StoredTableRowPublisher.java)
  publishes selected fields into `SqlValueBuffer`. Scalars become array writes;
  selected text is copied into its UTF-8 arena.
- [SqlValueLaneStorage](../../river-engine/src/main/java/io/riverdb/engine/relational/SqlValueLaneStorage.java)
  clears five arrays over the previous row's column count. A narrow projection
  still incurs bookkeeping proportional to the table's width.
- [SqlUniversalDescriptorJoinRow](../../river-engine/src/main/java/io/riverdb/engine/sql/SqlUniversalDescriptorJoinRow.java)
  owns both containers, resets the block row, loops over every table column,
  copies selected scalars and decodes selected UTF-8 text into character arrays.
  [SqlDescriptorBlockRowValues](../../river-engine/src/main/java/io/riverdb/engine/sql/SqlDescriptorBlockRowValues.java)
  implements another conversion with the same responsibility.
- [SqlBlockRowRecordCodec](../../river-engine/src/main/java/io/riverdb/engine/sql/SqlBlockRowRecordCodec.java)
  encodes character arrays back into UTF-8 at retained-record boundaries.
- [SqlDescriptorMutationValues](../../river-engine/src/main/java/io/riverdb/engine/sql/SqlDescriptorMutationValues.java)
  owns fetched and mutation buffers. Updates copy unchanged columns, including
  text, between them before the row is encoded again.

The reason is the current API split: relational access returns an owned decoded
row, while SQL consumers require a different owned row representation. Ownership
does matter when a page is unpinned or an inner JOIN fetch reuses a buffer.
SQL semantics do not require two successive owned representations of each input.
Buffer reuse avoids allocation but still performs the copies, clears and text
conversions above. No full-workload byte total or TPS attribution is established
by this source trace.

## Replace materializing reads with direct value access

Replace the existing value-source boundary with a small read-only contract used
by actual consumers. A reusable stored-row view carries the admitted layout,
visible identity, bounded bytes and owning lifetime. Nulls and primitives are
read at their prepared offsets; text is exposed as a UTF-8 byte slice. Reuse
descriptor/type metadata resolved at preparation rather than publishing it into
per-row arrays. Do not allocate a view or slice object per row or field.

Change predicates, JOIN bindings and row providers, projections, aggregates,
subqueries, index bounds/rechecks and result transfer to consume this contract.
Use the same interpretation for ordinary and projected reads. Respect existing
column liveness, including filters, index rechecks and outer JOIN dependencies;
do not independently infer column demand in each layer. Work for a narrow read
must not clear or populate a table-width value container or construct an encoded
row with empty slots just to satisfy an evaluator API.

`SqlDescriptorValueSource` currently switches between already-materialized
`SqlValueBuffer` and `SqlBlockRow` instances. Replace that boundary and remove
obsolete conversion helpers. Remove the requirement for `SqlBlockRow` as a
materialized input to synchronous evaluation. Any remaining owned SQL state must
belong to a real retained result or computed-value consumer, with UTF-8 text.
Delete the class if the replacement leaves it without such an owner.

## Keep text in UTF-8 throughout execution

The stored representation is already UTF-8. Conversion occurs because
`SqlBlockRow` and its consumers use Java `char[]`, which holds UTF-16 code units.
Remove this mandatory representation and the associated UTF-8 -> UTF-16 ->
UTF-8 round trips. A text view identifies backing storage, byte offset, byte
length and lifetime. UTF-8 storage alone is insufficient if every consumer still
copies the slice into another arena.

- Carry UTF-8 slices through projection, applicable comparison/JOIN operations,
  index-key encoding, retained/spilled records and protocol result encoding.
  Copy needed text directly into the final destination when that destination
  must own it. Reuse the existing text/key/protocol owners.
- Character operations may walk UTF-8 code points without materializing an
  entire UTF-16 string. Character counts and positions must preserve the
  operation's defined semantics; byte offsets are not character positions.
- Preserve existing collation, comparison, ordering, LIKE and case semantics
  where supported. Raw byte comparison is permitted only when equivalent to
  the admitted comparison contract. Do not introduce new collation features.
- Newly computed text needs owned output storage. Encode its result directly
  as UTF-8 where practical. Keep UTF-16 or Java `String` conversion local to an
  actual character-processing or public API requirement, such as JDBC string
  access, and document that consumer. Do not convert merely to cross an internal
  layer. Preserve required public result behavior.
- Validate genuinely external text at admission. Trust already admitted values
  and River-written storage. Do not reintroduce repeated UTF-8 validation while
  walking trusted text. Explicit integrity/content checking remains outside
  critical paths, with standalone utilities deferred.

## Make ownership explicit at each consumer

The storage owner must establish which visible bytes are stable and for how
long. Synchronous evaluation borrows within that lifetime. Advance, reset,
close, mutation and buffer reuse must have defined invalidation behavior.
Reuse existing pin/version ownership; do not expose writable page buffers or
allow reentrant operations to invalidate an active borrow. Resolve the concrete
faults in the earlier borrowed-callback attempt recorded in
[the projected-read investigation](../performance-checkpoints.md#2026-09-28--projected-descriptor-read-investigation).

For nested JOINs, keep only the outer values or pins needed while inner access
runs. A view over one shared reusable fetch buffer cannot serve multiple live
roles. Retained sort/hash/distinct state and asynchronous output must receive
their required values directly into their owned storage. Account for pins and
retained bytes under configured budgets, release exactly once on success,
filter rejection, failure, cancellation and transaction end, and preserve
explicit resource-exhaustion behavior. Do not invent arbitrary row/pin caps.

For every remaining copy, identify source owner, destination owner, actual
bytes moved and the lifetime or consistency boundary it creates. Direct field
loads and genuinely computed results remain necessary work. Copying values to
satisfy a second internal container API is the work being removed.

## Remove write-side and remaining consumers

Delete `SqlValueBuffer` itself, its unused backing storage and all River-owned
callers, overloads, conversion adapters and tests for the superseded contract.
The migration includes inserts, updates, primary/index keys, index backfill,
constraints, foreign-key checks, catalog access, ordered rows and test/benchmark
callers. Java visibility alone creates no compatibility obligation.

Use an admitted view of original values plus owned changed/computed values for
updates, so unchanged fields need not be copied into a second complete row.
Row and key encoders and constraint checks consume that source directly.
Preserve before/after semantics: evaluate assignments against the required
original row, retain values while index/FK planning needs them, and prevent
partial mutation on a failed check. Encode into the final transaction/provider
destination where its reservation and lifetime contract permits it.

Use one semantic value-access boundary for stored and actually owned results.
Introduce an interface only where these real implementations require it. Do not
rename the old full-row buffer, add a second executor/value hierarchy, retain a
compatibility wrapper, or leave a legacy path for incremental caller migration.
Keep small computed-value storage local to its immediate consumer. Preserve
status-returning errors, transaction visibility and durability contracts.

## Acceptance and evidence

- `SqlValueBuffer` and obsolete conversion/backing code are gone. All production
  consumers use the replacement contracts; no parallel legacy implementation
  remains. Synchronous reads avoid intermediate owned value rows.
- Text stays in UTF-8 through storage, execution and owned output boundaries
  that support UTF-8. Tests identify each necessary character conversion and
  prove there is no mandatory per-row `char[]`/`String` materialization.
- Focused real-path SQL tests cover selected numeric/text columns, null/empty
  text, multibyte and supplementary characters, comparisons, supported character
  functions, JOINs/subqueries, aggregation, sort/distinct/spill and result output.
  Cover actual external malformed text at admission without reinstating stored
  content validation. A numeric Stock Level run alone cannot prove the UTF-8
  improvement; include a focused text projection/JOIN/retention workload.
- Exercise source reuse, outer-row survival across nested fetches, pending
  insert/update/delete, older snapshots, key changes, index backfill, constraints,
  rollback, cancellation, resource pressure and pin cleanup. Cover the affected
  READ COMMITTED and SERIALIZABLE paths and reopen/recovery behavior.
- Measure actual bytes copied and cleared, fields decoded, UTF-8/UTF-16
  conversions, representation transfers, allocations and live pins at each
  changed boundary. Use focused runtime tests or temporary instrumentation,
  with load/warmup separated from measured transactions. No permanent source
  signature inventory, token-count gate or new profiling framework.
- Use unchanged full Stock Level and adjacent New Order, fixed workload/data,
  seed, isolation, durability, runtime and existing harness binding. Capture at
  least two control/candidate samples and investigate repeated shifts with
  longer interleaving. Record mechanism counts and individual artifacts in
  `docs/performance-checkpoints.md`; do not promise an unmeasured MariaDB ratio
  or reuse header/head-directory savings as gains from this ticket.
- Apply focused tests, affected-module checks, slopmark review and the clean
  integration checkpoint proportionately. Ownership/concurrency changes require
  independent review. Complete replacement, review and evidence precede the
  existing merge/tag/push process. No implementation is started by this ticket's
  creation.

## Delivery

The value-buffer and conversion paths named above are removed. Stored rows and
ordinary SQL scans expose borrowed typed values; retained sort, grouping and
result owners copy admitted UTF-8 only when their lifetimes require it. Updates
borrow unchanged original columns and own only changed values. External write
values and tuple-key text are validated at admission.

Focused ownership, UTF-8, JOIN, spill, mutation and malformed-input tests pass.
The clean repository `check` and installed TPS distribution pass. An independent
ownership review found and verified fixes for public admission, mutable view
exposure, failed-borrow invalidation and text index-bound lifetimes. Run-level
results and the variability assessment are recorded in
[performance checkpoints](../performance-checkpoints.md).
