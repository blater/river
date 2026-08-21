# ADR 0011: Backup boundary and retention

Status: Proposed — K14/O01 crash and restore evidence required

## Context

Replication is not backup, an in-place fuzzy checkpoint is not an immutable
copy source, and retention cannot be determined by checkpoint coverage alone.
Plans also used overlapping lease and manifest names.

## Decision

`WalRetentionLease` is the single semantic retention contract owned by the
journal API. It is a named, database-incarnation-qualified, durably recoverable
pin with owner kind, minimum required `JournalPosition`/mapped LSN, expiry or
renewal policy, and explicit release. The local WAL implementation may return a
`LocalWalLeaseHandle`, but that handle introduces no second retention meaning.
Recovery, backup, CDC, upgrade, and later follower catch-up all use the same
contract. `safeTruncate` is the greatest prefix below every active requirement;
space pressure backpressures or fails explicitly and never overwrites a lease.

Identity names are disjoint:

- `CheckpointId` names a complete fuzzy recovery checkpoint and boundary. It
  does not identify an immutable set of in-place data files.
- `BackupManifestId` names one finalized backup artifact and its complete file,
  WAL, format, lineage, length, and checksum inventory.
- `CheckpointManifestId` is reserved for a future R5 immutable copy-on-write
  complete-database root. Phase 1 does not create or promise one.

K14 performs an online in-place backup in this order:

1. Select the newest validated K11 checkpoint and compute
   `backupStartRedoToken` as the earliest same-lineage WAL record required by
   its DPT, active/loser transaction predecessor/undo chains, outcome/high-water
   state, and any structural operation in progress. Record the database, file,
   page, WAL, and checkpoint generations.
2. Acquire and durably record a `WalRetentionLease` at or before that exact
   start before copying any mutable state. Failure to persist the lease aborts
   the backup.
3. Capture each mutable page under its latch into independently owned immutable
   bytes with checksum, file/page identity and generation, and complete
   `PageWalToken`. Copying can proceed without the latch; redirty creates later
   WAL and does not mutate the captured bytes. A raw source-file copy is allowed
   only for an exact checked page already proved data-file-forced.
4. After the last page/file capture, append a versioned backup-end marker,
   publish and force it, and fence the backup end as its
   `backupEndJournalPosition` and `backupEndWalToken`. Later mutations are
   outside the artifact. Every copied page token must have the same lineage and
   `recordEndLsn <= backupEndWalToken.recordEndLsn`.
5. Copy the complete validated WAL interval from `backupStartRedoToken` through
   the exclusive end, plus every earlier transaction predecessor/undo record
   named by captured checkpoint/transaction state. Validate there is no gap,
   preserve segment/block/record framing, and keep the lease until the
   destination WAL and all data/checkpoint/transaction objects are fully
   copied, checksummed, and forced.
6. Write the complete `BackupManifestId` manifest last. It contains the start
   redo token, fenced end, checkpoint/transaction state, file/page inventory,
   every WAL range, formats, lineages, lengths, and checksums. Force the
   manifest, atomically install it, force its directory, verify it can be
   reopened, and only then release the lease.

A partial object set or manifest is never restorable. The end marker orders the
artifact; it does not pause foreground work or make copied in-place pages an
immutable checkpoint.

K13 inspects control, page, WAL, checkpoint, backup data, and backup manifests
read-only before K14/G1 promotion. Restore targets a new excluded directory,
validates every artifact, recovers to the manifest boundary, and never mutates
the source backup.

## Invariants

- Backup and restore remain independently testable when no replication exists.
- A backup never mixes database/file generations or releases WAL before its
  complete manifest is durable.
- Every copied mutable page is a capture-stable immutable image. "Captured" or
  "written" never means source- or destination-stable; destination force plus
  final manifest installation supplies the artifact's durability proof.
- Restore begins at `backupStartRedoToken`, validates the checkpoint and
  transaction predecessor closure, and replays only through the fenced end.
- No copied page can be newer than the end marker or carry a bare/cross-lineage
  LSN. Missing predecessor, WAL interval, page, or outcome data invalidates the
  whole manifest.
- Normal MVCC snapshots do not pin WAL; version retention has its own horizon.
- An in-place page from one physical layout is not selectively installed into
  another live replica merely because its `PageId` matches.
- R3 state synchronization uses a complete validated snapshot plus suffix, or
  logical rebuild, for the in-place engine.
- R5 block transfer requires the same `CheckpointManifestId`, block identity,
  format, and checksum. R5 is independent of optional R4 and R6 after R3.

## Consequences

Initial backups may copy more data than a later immutable checkpoint design,
but their consistency and restore proof do not depend on consensus or physical
replica identity. One lease model prevents backup and WAL code disagreeing
about reclamation.

## Alternatives

- Replication alone was rejected because it reproduces operator error and
  correlated logical corruption.
- Treating a fuzzy checkpoint as an immutable backup/snapshot was rejected.
- Selective in-place page repair for R3 was rejected without a common immutable
  manifest identity.

## Required evidence

- K14 crashes before/after start selection, lease persistence, each page
  capture, concurrent redirty, end-marker append/force, WAL/predecessor copy,
  every object force, manifest write/force/install/reopen, and lease release.
- K13 rejects truncation, checksum, identity, generation, and unsupported
  version damage across backup artifacts.
- O01 repeated restore-to-new-directory, recovery, relational verification,
  retention-pressure, cancellation, and disk-full tests.

## Authoritative context

- [High-level backup plan](../plans/river-high-level-plan.md)
- [Implementation plan K13, K14, and O01](../plans/river-project-implementation-plan.md)
- [Replicated journal state-sync plan](../plans/river-replicated-journal-durability-plan.md)
