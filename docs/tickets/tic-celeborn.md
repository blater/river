---
id: tic-celeborn
status: in_progress
type: feature
priority: 1
assignee: blater
parent: tic-isildur
delivery: code
branch: ticket/tic-celeborn-compact-row-header
tags:
    - performance
    - storage
    - sql
    - ownership
created: 2026-09-28T21:55:36.301048Z
---
# Admit row access once, shrink row headers and remove redundant copies

Replace repeated internal row validation and representation copying with one storage-owned bounded row contract. Reduce the durable row header to fields with a demonstrated semantic consumer, and use reusable borrowed views for synchronous execution.

Storage and River-written content are trusted. Internal checks and copies must
have a named correctness or lifetime purpose. General claims of safety, public
Java visibility, convenience for a caller, or possible storage corruption do
not justify repeating them on every row.

This is the remaining row-transfer and trust-boundary slice of
[tic-isildur](tic-isildur.md), following [tic-elvenking](tic-elvenking.md) and
[its completion](tic-elvenking-completion.md). Coordinate with
[projected-read evidence](tic-healthy-bellodonna.md); reuse its column-demand
contract and copy/decode measurements. Do not create another executor, value
representation, projection analysis or parallel performance campaign.

The header-removal implementation and validation are recorded in
[the headerless-row review](../delivery/evidence/2026-09-28-tic-celeborn-headerless-rows.md).
[tic-ent](tic-ent.md) completed the `SqlValueBuffer`, UTF-8 representation and
borrowed-access replacement at `b6bc7e63`, tagged
`perf-checkpoint-20260929-tic-ent-values`. The broader invariant-admission and
storage-trust audit remains here; section 3 states the shared ownership
requirements.

The header-removal slice passed independent durable-format review, the clean
integration build and matched Stock Level/New Order regression checks on
`f3b0573e`. The [performance checkpoint](../performance-checkpoints.md#2026-09-28--headerless-descriptor-rows)
records the accepted slice and tag `perf-checkpoint-20260928-headerless-rows`.
These checks do not close the wider ticket or claim a measured speedup.

## Baseline evidence and scope

Source inspected: integrated `e3225ffd` on 2026-09-28. The trace below records
that earlier source; tic-ent has removed some of these classes and paths.
Recheck current code before work on the remaining audit.

- `StoredTableRowDecoder` (removed by tic-ent)
  and `StoredTableRowHeaderCodec` (removed in the implementation slice)
  repeat argument, range, identity and format checks across the internal call.
- [RelationalDescriptorRowValidation](../../river-engine/src/main/java/io/riverdb/engine/relational/RelationalDescriptorRowValidation.java)
  copies row bytes and checks the header to resolve a historical layout, then
  invokes the decoder, which checks the same header again.
- [IndexedKernelRowAccess.fetch](../../river-engine/src/main/java/io/riverdb/engine/table/IndexedKernelRowAccess.java)
  retains row bytes before releasing its page pin. The retained copy currently
  creates the lifetime needed by callers; removing only the copy is incorrect.
- [RelationalDescriptorRowBuffer](../../river-engine/src/main/java/io/riverdb/engine/relational/RelationalDescriptorRowBuffer.java)
  already decodes retained read-only bytes directly. The common second full-row
  copy has been removed; the new ticket must not claim that old cost as a new win.
- [HeapRowResult](../../river-storage/src/main/java/io/riverdb/storage/heap/HeapRowResult.java)
  can retain selected fields, but builds another encoded row: zeroing a fixed
  region, copying metadata and fields, and rewriting text offsets. This is less
  work than retaining all text, but is not a zero-copy projected view.
- [SqlUniversalDescriptorJoinRow](../../river-engine/src/main/java/io/riverdb/engine/sql/SqlUniversalDescriptorJoinRow.java)
  still publishes into `SqlValueBuffer` and then copies selected values into
  `SqlBlockRow`. These are reusable containers, but reuse does not eliminate
  their data movement or repeated representation work.

The logical-head directory has already replaced relational base-row B-tree
searches under [tic-base-row-head-directory](tic-base-row-head-directory.md).
Do not reopen that replacement or count its accepted savings again.

No full-workload copied-byte total or isolated check cost is established by
this ticket. Measure them; do not assume that megabytes are copied per query
or that removing these checks alone explains the MariaDB performance gap.

## 1. Establish row access once; remove repeated internal checks

Trace the real point, range, JOIN, snapshot-version, mutation and result-transfer
paths. Identify the owner that first establishes each required invariant, then
delete downstream repetitions. Cover duplicate header decoding, positive-ID
checks, null checks on River-controlled arguments, descriptor/shape checks,
range checks already guaranteed by a bounded view, and repeated resource
preflight that can be established when preparing the read.

Use one internal contract carrying the admitted layout, visible row identity,
bounded bytes and lifetime. The contract must arise from the existing owning
access operation; do not add a validator wrapper, trust flag, validation cache,
capability-token hierarchy, or another pass over the row. Reuse state already
resolved by the fetch and table-descriptor owners.

For every retained hot-path check, name the invariant and explain why its owner
cannot establish it earlier. Record this compactly with the implementation
review, not as a permanent method/signature inventory or source-token gate.
Move invariant checks to preparation only when the invariant actually remains
valid across all rows; row-dependent offsets and lifetimes are not constants.

Preserve actual external admission, new-value constraints, query predicates,
index/snapshot consistency, page-generation protection, mutation permissions,
resource exhaustion behavior and required WAL/recovery framing. Reading bytes
within the enclosing Java buffer alone does not prove they are the intended
field or visible row. Establish those guarantees once at their real owner.
Do not replace status-returning boundary handling with uncontrolled exceptions.

## 2. Reduce the durable row header

The baseline header is 32 bytes, before the null bitmap and field slots:

| Field | Bytes | Required disposition |
| --- | ---: | --- |
| Magic | 8 | Delete the per-row signature. The owning storage format determines the row encoding; do not replace it with another per-row marker or checksum. |
| Version | 4 | Delete the per-row constant `1`. Own any required format identification at the existing durable container/open boundary. |
| Reserved flags, currently zero | 4 | Delete. There is no implemented consumer; do not replace them with padding or another future-use field. |
| Row layout ID | 8 | Delete. Use the admitted table descriptor; remove unsupported mixed-layout resolution and its unused APIs/tests. |
| Logical row ID | 8 | Delete the header copy. Keep authoritative logical identity in the index/head/scan machinery. |

The magic is a constant format-recognition signature, not a checksum, identity,
or integrity proof. The four-byte version is currently the constant `1`, and
the decoder only accepts equality with that value. It selects no alternate
decoder or migration. It is not the MVCC version or the schema layout ID.
Its current feature is incompatible-format rejection, which belongs at the
durable container/open boundary that determines row encoding. Delete this
constant from individual rows; neither a future encoding nor a hypothetical
mixed-format reader is a reason to retain it.

The user explicitly requires deletion of the per-row magic and format version:
the owning reader already knows it is accessing table rows in the selected
storage format. Detecting that
arbitrary bytes were mistakenly supplied to an internal decoder is not a
required row feature under the storage-trust policy. This intentionally removes
that diagnostic check. Keep format selection at the existing owning storage
boundary without adding another per-row validation mechanism.

Apply YAGNI: the reserved flags must also be removed, not retained pending a
possible future use. This delivery does not invent a new feature to justify
them. A future format change can change the format when an actual requirement
exists; there is no backward-compatibility obligation to reserve space now.

The selected row format has **no separate header**, removing all 32 bytes.
The user explicitly rejected mixed physical layouts and historical-layout
resolution as an unsupported feature. Catalog successors currently preserve the
physical layout and columns. Resolve layout from the admitted table descriptor.
Do not retain the old per-row layout field, resolver, compatibility adapter,
cache-admission path or tests for the removed feature.

The header's logical row ID was used only to check an ID already supplied by
the caller. Point access and scans obtain identity from the logical-head directory
and index/scan results; mutations and MVCC continue to use it there. A future SQL
`ROWID` expression can expose that existing identity without reintroducing a
row-header copy. This change does not itself implement that SQL feature.

Audit point/range scans, pending and historical versions, catalog rows, index
backfill, mutations, vacuum, checkpoint, WAL replay and reopen. Change row
offsets, maximum-row calculations, readers, writers and format fixtures together.
Make the durable decision in the appropriate ADR. There is no backward-
compatibility requirement: replace the format, reject incompatible databases
at the owning format boundary, and retain no legacy reader, migration adapter
or runtime compatibility flag.

## 3. Make borrowed access the normal synchronous read contract

Use reusable flyweight views over the selected visible row. A synchronous
filter, projection or JOIN probe should read the required primitives directly
while the owning row/page lifetime remains valid, without first constructing a
second encoded row and then two generic value containers. Fuse field access
with the existing evaluator or write once into the actual consumer-owned result.
Preserve one canonical execution path for ordinary and projected reads.

The implementation must define and enforce:

- Who owns the page/version pin and the borrowed view; exactly when advance,
  reset, close or reuse invalidates the view.
- How nested JOIN roles retain only the outer values or pins they actually
  need, within the configured resource budget, without holding arbitrary pages.
- How success, filtered rows, errors, cancellation and transaction end release
  each pin exactly once, with no steady-state per-row view allocation.
- Which consumers outlive the borrow, such as retained sort/hash/distinct state
  or asynchronous result output. Copy only their required values into their
  final owned storage, once per necessary lifetime boundary.
- How mutable/private pending rows and older visible versions obey the same
  contract without stale views or overwrite during nested access.

Keep pinned operations inside the owning implementation. An earlier public
borrowed callback exposed writable page bytes and allowed reentrant table
operations while pinned; that candidate was removed, as recorded in the
[projected-read investigation](../performance-checkpoints.md#2026-09-28--projected-descriptor-read-investigation).
Resolve those concrete ownership faults in the replacement. Do not use them
as a blanket reason to copy every row. A public read-only buffer alone does
not establish a lifetime or prevent reentry.

Any retained copy must name the source owner, destination owner, bytes copied
and the precise lifetime/consistency boundary it creates. Caller convenience
or conversion between internal containers is insufficient. Do not preserve
empty fixed slots, clear entire row-sized regions or rewrite text handles just
to make a narrow result resemble a full stored row.

## DRY and architecture requirements

- One owner establishes each trust, layout, visibility and lifetime guarantee.
  Passing through another internal service does not reset that guarantee.
- One row-layout owner interprets headers, null bitmaps, fixed fields and text
  offsets. Do not duplicate its policy in generic heap retention and SQL code.
- Reuse the existing bound column-demand/liveness contract. Do not infer needed
  columns independently in storage or add Stock Level-specific behavior.
- Replace superseded transfer APIs, scratch buffers, full-row adapters and
  tests in the same delivery. Do not leave a fallback path for caller migration.
  Small copy primitives for genuinely different owners need not be forced into
  a generic interface merely to make their syntax identical.
- Keep deep content validation and integrity checking outside critical paths.
  Standalone integrity tools remain deferred; do not implement or scaffold them.

## Acceptance and evidence

- The reviewed production paths admit each invariant once. Historical-layout
  selection uses the table descriptor without historical-layout dispatch, and trusted downstream
  access does not repeat null/range/domain checks with no independent purpose.
- The eight-byte per-row magic, four-byte constant format version and unused
  four-byte flags, eight-byte row-layout ID and eight-byte duplicate logical ID
  are deleted, with no substitute markers, checksums or reserved bytes. The row
  starts at its null bitmap. Their deletion is not conditional on a performance
  measurement. All format callers and required recovery paths use
  the same new contract; no legacy format remains.
- Synchronous projected reads avoid intermediate row materialization. Every
  remaining copy has a necessary named lifetime boundary and copies only needed
  data. Values, nulls and byte ownership remain correct after source reuse.
- Focused tests cover selected numeric/text fields, empty/null/multibyte text,
  actual external malformed input, boundary sizes, table-owned layouts,
  pending insert/update/delete, key changes, older snapshots, READ COMMITTED and
  SERIALIZABLE, nested access, cancellation and cleanup. Pin-release and reuse
  tests exercise the real public SQL path. Durable changes also require split,
  vacuum, checkpoint, WAL replay, crash-reopen and failure-boundary coverage.
- Capture temporary counters or existing profiling for checks executed, header
  reads, bytes copied at each ownership boundary, bytes cleared, values decoded,
  representation transfers, allocations and retained pins. Report counts per
  committed transaction and separately for warmup/load. Count actual memory
  work, not just buffer capacity or a declared retained-row length.
- Establish those counts on the unchanged `full stock-level` workload, and
  share the evidence with `tic-healthy-bellodonna`. Preserve the existing SQL,
  schema, physical indexes, seed, isolation and durability. Include New Order
  for write/header effects and adjacent sample Stock Level. Use fixed control
  and candidate configurations, at least two samples each, and longer interleaved
  runs to resolve a repeated shift or host variability. No speculative TPS claim.
- Run focused tests while editing, then affected-module checks and the clean
  feature checkpoint. Durable-format and lifetime/concurrency changes require
  independent review. Record source, commands, mechanism counts, individual
  samples, cleanup and acceptance in `docs/performance-checkpoints.md`; use
  the existing merge/tag/push process.

The user has authorized implementation of the header and unsupported-layout
removal. The active worktree is `/private/tmp/river-compact-row-header`, branch
`ticket/tic-celeborn-compact-row-header`, based on stable checkpoint `e3225ffd`
(`perf-checkpoint-20260928-indexed-read-head-directory`), owned by `codex/root`.
This is one mechanism within the wider ticket; borrowed row lifetimes and the
remaining representation copies are still separate work. Do not close the whole
ticket on header removal alone.
