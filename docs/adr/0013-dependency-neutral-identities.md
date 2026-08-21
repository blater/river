# ADR 0013: Dependency-neutral identity catalog

Status: Accepted

## Context

River modules need distinct typed identities before physical page, row, WAL,
backup, and protocol encodings are selected. Making the base identity catalog
depend on those later layouts creates a P07/P09/format decision cycle.

## Decision

Freeze the semantic value types already required by Phase 0 contracts:
`DatabaseId`, `DatabaseIncarnation`, `NodeIncarnation`, `TablespaceId`,
`PageId`, `RowId`, `RelationId`, `IndexId`, `ColumnId`, `TransactionId`,
`CheckpointId`, `CheckpointManifestId`, `JournalPosition`, `Lsn`,
`WalGeneration`, `CommitSequence`, `RequestId`, and `IdempotencyKey`. The
journal contract introduced `WalGeneration` when its local-WAL mapping became
a public boundary. No type is substituted for another because a current Java
or prototype representation happens to fit the same primitive width.

Do not pre-create unused `FileId`, `BackupManifestId`, or other speculative
wrappers. The first contract that needs one introduces and reviews a distinct
semantic type before accepting primitive values at that boundary.

This ADR fixes names, ownership, equality domains, and lineage rules only. It
does not freeze packed widths, on-disk offsets, byte order, page size, row
headers, WAL record layout, or wire encoding. Those physical decisions remain
with ADR 0004 and the owning format/protocol ADR after P05/P09 evidence.

## Invariants

- `DatabaseIncarnation` fences copied or stale database files.
- `NodeIncarnation` fences one process/storage-node lifetime and is not part of
  logical row identity.
- Local WAL offsets are meaningful only with database incarnation and WAL
  generation.
- `JournalPosition`, local `Lsn`, and `CommitSequence` remain different units.
- `CheckpointId` and `CheckpointManifestId` identify different artifacts.
- A dependency-neutral value contract does not expose a durable packed encoding.

## Consequences

P07 can promote its base types without guessing a future physical layout. P09
may compare layouts using these semantic types, and K02 freezes durable
encodings only after the physical format decision is accepted.

## Required later validation

- ADR 0004/P09 selects and reviews physical page and row representations.
- K02 fixtures prove accepted format versions and reject incompatible versions.
- K11 recovery proves persisted incarnation and WAL-lineage fencing.
- Protocol and backup gates prove their own encodings without changing these
  semantic equality domains.
