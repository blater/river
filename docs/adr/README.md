# River architecture decision records

<!-- markdownlint-disable MD013 -->

This directory is the Phase 0 P06 decision bundle. An `Accepted` ADR fixes a
contract for implementation; it does not claim that the deliverable or gate
named in the implementation plan has passed. `Proposed` decisions cannot be
used to freeze a durable format or promote G0 until their Phase 0 decision
evidence and reviews exist. Required production implementation evidence remains
at the first later gate containing that implementation and is not a circular
G0 prerequisite.

The governing scope is the
[product charter](../governance/product-charter.md). Delivery ordering and
promotion evidence remain controlled by the
[implementation plan](../plans/river-project-implementation-plan.md) and
[implementation status](../delivery/implementation-status.md).

## Index

| ADR | Decision | Status | Gate |
| --- | --- | --- | --- |
| [0001](0001-product-and-sql-profile.md) | Product and SQL profile | Accepted | Profile fixtures evolve through Q01/U06 |
| [0002](0002-provenance-boundary.md) | Provenance boundary | Accepted | Project-owner provenance decision recorded |
| [0003](0003-runtime-and-durable-io.md) | Runtime and durable I/O | Accepted | Filesystem power-loss claims remain evidence-gated |
| [0004](0004-durable-identities-pages-and-rows.md) | Durable identities, pages, and rows | Proposed | P05/P09 measurements and format review |
| [0005](0005-torn-page-protection.md) | Full-page-image torn-page protection | Proposed | P09 amplification and prototype protocol review; production crash matrix remains K10/G1 evidence |
| [0006](0006-journal-wal-and-checkpoint-units.md) | Journal, WAL, and checkpoint units | Accepted | Physical sizing remains P05/P09-gated |
| [0007](0007-frontiers-and-durability-contracts.md) | Frontier ownership and durability contracts | Accepted | P10 fake-provider suite; replication decisions deferred |
| [0008](0008-minimal-transactions-mvcc-and-rollback.md) | Minimal transactions, MVCC, and rollback | Proposed | P09 version-store and transaction-contract review; Phase 2 isolation remains G2 evidence |
| [0009](0009-b-link-tree-splits-and-recovery.md) | B-link/B+tree splits and recovery | Accepted | K08 model/crash evidence before G1 |
| [0010](0010-status-diagnostics-ownership-and-fatal.md) | Status, diagnostics, ownership, and fatal state | Accepted | Allocation and fault-path contract tests |
| [0011](0011-backup-boundary-and-retention.md) | Backup boundary and retention | Proposed | K14/O01 crash and restore evidence |
| [0012](0012-embedded-api-and-protocol-boundaries.md) | Embedded API and protocol boundaries | Accepted | Q01/N01-N06 compatibility and security evidence |
| [0013](0013-dependency-neutral-identities.md) | Dependency-neutral identity catalog | Accepted | Physical encodings remain ADR 0004/K02 decisions |
| [0014](0014-riverd-instance-security.md) | `riverd` instance security and client discovery | Accepted | Contract ratified by `tic-11a5`; implementation remains with core `tic-615d`/`tic-72ea`/`tic-ec50`, operations `tic-0803`/`tic-d2e9`/`tic-b901`, and evidence `tic-95e8`/`tic-9640` |

## Coupled vocabulary

These names are deliberately non-interchangeable:

- `JournalPosition` is logical order; `Lsn` is a replica-local WAL byte
  boundary; `CommitSequence` is SQL visibility order.
- `recordStartLsn` identifies a WAL record start; `durableEndLsn` is an
  exclusive forced byte boundary.
- journal frontiers, transaction `visibleCsn`, and the aggregate recovery and
  retention boundaries have different owners.
- `CheckpointId`, `BackupManifestId`, and the future
  `CheckpointManifestId` identify different artifacts.
- `CHECKPOINTED` is an administrative coverage/reclamation fact, never a
  client commit tier.

## Audit resolution record

- [ADR 0003](0003-runtime-and-durable-io.md) breaks the P08/K01 loop: Phase 0
  defines the platform/fault SPIs and K01 implements and qualifies NIO.
- [ADRs 0003](0003-runtime-and-durable-io.md),
  [0004](0004-durable-identities-pages-and-rows.md),
  [0006](0006-journal-wal-and-checkpoint-units.md), and
  [0008](0008-minimal-transactions-mvcc-and-rollback.md) require `river-bench`
  P09 evidence for I/O, page, WAL, and version-store prototypes. WAL/vector
  measurements alone cannot satisfy P09, and no P05 result is claimed.
- [ADR 0006](0006-journal-wal-and-checkpoint-units.md) separates local
  reservation, publication, write completion, exclusive forced end, and the
  stable tail validated after restart.
- [ADR 0007](0007-frontiers-and-durability-contracts.md) gives each standard
  frontier an owner and keeps journal frontiers, transaction `visibleCsn`, and
  derived recovery/retention decisions separate.
- [ADR 0008](0008-minimal-transactions-mvcc-and-rollback.md) assigns durable
  transaction-ID high-water and outcome semantics to K09/K11, bounds K12 to
  bootstrap records, and leaves storage recovery handlers with K07/K08.
- [ADR 0009](0009-b-link-tree-splits-and-recovery.md) makes K08 single-version
  in Phase 1 and defers merge rather than implying Phase 2 indexed MVCC.
- [ADR 0010](0010-status-diagnostics-ownership-and-fatal.md) replaces
  per-component fatal flags with one database/engine fatal fence.
- [ADR 0011](0011-backup-boundary-and-retention.md) replaces the overlapping
  `JournalRetentionLease`/`WalRetentionLease` plan names with one semantic
  `WalRetentionLease` plus a provider implementation handle, separates all
  manifest identities, and adds backup artifacts to K13 inspection.
- [ADR 0012](0012-embedded-api-and-protocol-boundaries.md) makes same-version
  support and incompatible-version rejection explicit instead of promising an
  unproved compatibility window.

Consensus selection, volatile acknowledgement (R4), copy-on-write checkpoints
(R5), and follower reads (R6) are not selected here. R4, R5, and R6 are
independent optional programs after R3. The bundle also makes no claim that
P05 measurements or P06 independent reviews have passed.

## Common authoritative plans

- [High-level architecture](../plans/river-high-level-plan.md)
- [Implementation and dependency plan](../plans/river-project-implementation-plan.md)
- [Replicated journal and durability plan](../plans/river-replicated-journal-durability-plan.md)
- [TigerBeetle comparison](../plans/river-tigerbeetle-comparison-and-recommendations.md)
- [Engineering and performance charter](../plans/river-engineering-personas-and-performance-charter.md)
- [Performance review and benchmark plan](../plans/river-performance-review-and-benchmark-plan.md)
