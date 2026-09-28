# ADR 0004: Durable identities, pages, and rows

Status: Proposed — P05/P09 evidence required

## Context

Dependency-neutral identity semantics are accepted separately by
[ADR 0013](0013-dependency-neutral-identities.md). This ADR owns their physical
encoding together with page and row formats. Page size and row/version layout
materially affect OLTP, scans, WAL volume, amplification, and cache behavior.
P05 currently has no accepted numeric baseline, so those performance-sensitive
values cannot be presented as measured facts.

## Decision

Use the distinct typed identities accepted by ADR 0013 for every physical
format.
`DatabaseIncarnation` is owned by database control/recovery and fences copied
or stale files. `NodeIncarnation` lifecycle semantics are owned by
`river-journal-api` and, later, the replication API even if its
dependency-neutral value type lives in `river-base`. It fences one
process/storage-node lifetime and is not encoded into logical row identity.
Phase 0 freezes both incarnation semantics; Phase 1 first persists them.

Use one 16 KiB canonical v4 page size with no mixed page sizes inside a
database. Durable file and page formats carry their owning format version and
database/file identity where applicable; table-row payloads carry neither.
Pages use a canonical byte order, page type,
`PageId`, generation, free-space/slot bounds, header checksum, and a
`PageWalToken(DatabaseIncarnation, WalGeneration, recordStartLsn,
recordEndLsn)`. The end is exclusive. A bare offset is not a durable page
comparison value.

Heap pages are slotted; rows have bounded null/variable-offset metadata,
creator and deleter transaction fields, and an optional durable
`VersionPointer`. Oversized values use an explicit overflow representation.

Stored descriptor-table rows start with the null bitmap, followed by fixed
column slots and variable bytes. There is no row magic, format version, reserved
flags, layout ID or duplicate logical row ID. The admitted table descriptor owns
physical layout; current metadata successors preserve that layout. Mixed physical
layouts within a table are unsupported, and the historical-layout resolver and
its unused admission APIs are removed. The index/logical-head/scan owner supplies
logical identity independently of the row payload. MVCC identity, visibility and
version chains remain in their existing storage metadata.

Database control format v2 admits this headerless layout and rejects v1 at open.
This replaces the former 32-byte row header without an old-format reader,
migration or replacement per-row marker. Explicit future layout-changing DDL
must establish its table storage contract when that feature is implemented.

V1 durable fixtures cover same-version read/write and explicit rejection of
unknown/incompatible versions. Cross-version reading or upgrade is not implied
until an upgrade ADR and fixtures select it.

## Invariants

- Durable decoding validates identity, version, length, bounds, and the checksum
  defined by that format before creating a trusted bounded view. Page v4 CRC32C
  covers bytes 0–119 of the header only; its value/complement occupy bytes
  120–127. No page payload checksum is computed on reads or writes. Payload
  offsets and lengths are checked by their owning codec before access; ordinary
  row reads do not revalidate River-written UTF-8, type domains, null-slot
  padding, or other field content. Tuple-index root v4 records are
  208 bytes with structural/identity validation and no record CRC.
- A `PageId`, `RowId`, `Lsn`, or `JournalPosition` is never substituted for
  another unit because their integer representations happen to fit.
- Page reuse is delayed until WAL, snapshots, cursors, and writeback cannot
  observe an ABA alias.
- A page's `PageWalToken` identifies its newest applied local WAL record and
  the exact WAL lineage that can recover it. Tokens are ordered only when
  database incarnation and WAL generation match.
- WAL offsets may reset at a new `WalGeneration`. River retains the mapping and
  recovery base for every page token still present; it cannot reuse, compare,
  or discard a generation until every such page is durably rewritten under a
  retained successor recovery base or the database is fenced for migration.
- Row/index changes and version pointers are journaled before dirty-page write.

## Consequences

The 2026-09-15 user-directed performance change replaces whole-page CRC with
header-only bulk CRC32C and removes tuple-root record CRC. Page and root v3
formats are rejected; no compatibility path or migration is supplied. The
remaining heap-backed checksum helper uses bulk CRC32C instead of byte-at-a-time
updates; CRC32C itself is unchanged. WAL checksums continue to cover logged
payloads. A persisted payload change that remains structurally valid is no
longer detected by page admission or offline page inspection. This is an
explicit reduction of detection coverage, not a new guarantee of atomic writes.
Repeated tuple-root decode performs no checksum calculation or checksum caching.
Stored-row updates preserve unchanged typed bytes without a second content
admission. Post-admission content damage that leaves structural metadata valid
may remain undetected. Recovery retains its WAL framing and ordering checks;
it does not perform a substitute full row-content scan.


A single page size simplifies buffer, WAL, recovery, and access-method code.
The 16 KB value and tuple header are provisional: K02 may implement codecs and
fixtures for prototypes, but compatibility is not frozen before P09 review.

## Alternatives

- Mixed 2–64 KB pages were rejected for v1 complexity without evidence.
- An 8 KB or 32 KB canonical page remains a P09 comparison candidate.
- Java object serialization was rejected as unstable and allocation-heavy.

## Required evidence

- P05 runner and numeric workload baseline.
- P09 page-size, page-I/O, row/version-store, scan, and WAL-amplification
  prototypes using `river-bench`; WAL/vector tests alone are insufficient.
- Phase 0 prototype fixture review proving same-version acceptance and
  unsupported version rejection for the proposed encoding contract.
- Production format fixtures and corruption fuzzing before K02/G1 promotion;
  those tests validate the implementation and do not block the G0 decision.

## Authoritative context

- [High-level format and storage plan](../plans/river-high-level-plan.md)
- [Implementation plan P05, P09, and K02](../plans/river-project-implementation-plan.md)
- [Benchmark plan](../plans/river-performance-review-and-benchmark-plan.md)
