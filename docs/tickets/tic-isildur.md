---
id: tic-isildur
status: open
type: epic
priority: 1
assignee: blater
parent: tic-30c3
delivery: none
tags:
    - performance
    - storage
    - sql
created: 2026-09-28T15:54:39.393162Z
---
# Remove indexed-read work amplification

River's tuple-index scans return logical row IDs. The former scalar base-row
B-tree has been replaced by a logical-head directory, and projection now
retains only required row bytes. Current reads still traverse that separate
head directory, resolve MVCC visibility and heap location, then retain the
selected fields. This epic removes the remaining costs through one general
read contract and one canonical storage layout. It is the next architecture
delivery under [tic-30c3](tic-30c3.md) and expands the Stock Level work in
[tic-72e5](tic-72e5.md).

## Design

1. Deliver [projected descriptor reads](tic-healthy-bellodonna.md). Derive
   required columns once from output projections, predicates, joins and index
   rechecks. Share the existing liveness owner where it applies, then carry the
   result into storage-owned decoding and copying. Preserve full-row mutation
   reads and every SQL semantic dependency.
2. Measure and choose one replacement for the extra scalar base-row search
   ([tic-base-row-head-directory](tic-base-row-head-directory.md)):
   clustered primary-row storage or a paged directly addressed logical-row
   head directory. Record read, update, page fanout, MVCC and recovery evidence
   before selecting. Implement the chosen format and remove the superseded
   path, including its writes and recovery. No permanent fallback or second
   implementation path remains.
3. [Admit row access once, shrink headers and remove redundant copies](tic-celeborn.md).
   Simplify row transfer under explicit buffer ownership. Remove intermediate
   full-row materialization where a pin or bounded borrowed view permits it;
   otherwise copy only the required values before releasing the pin.
   [Tic-ent](tic-ent.md) completed the value-representation and borrowed-access
   replacement at `b6bc7e63`. Intermediate containers and conversion adapters
   are removed across read and write consumers; copies remain at explicit
   ownership boundaries. The wider tic-celeborn trust-boundary audit remains.
4. [Tic-thranduil](tic-thranduil.md) measured the current indexed-probe path
   against MariaDB's actual plan. [Tic-boromir](tic-boromir.md) tested bounded
   metadata frame-hit lookup with unchanged cache capacity and closed without
   code after repeated quiet-host TPS and p99 regressions.
   [Tic-erebor](tic-erebor.md) is now next: prefer canonical row payload in
   primary-index leaves, beginning with its explicit identity, MVCC/history,
   overflow and publication design checkpoint. Deliver and measure one
   complete row-store replacement. [Tic-uruk-hai](tic-uruk-hai.md) then
   consolidates surviving snapshot-resolution and bound-check responsibilities;
   do not optimize a row access path that the selected layout removes. Its
   direct comparison analysis applies to residual changed-row checks where
   still needed. The mixed-workload metadata-cache capacity question remains
   separate.

## Acceptance Criteria

- The unchanged full Stock Level workload shows fewer separate head-directory
  traversals, fewer bytes copied and fewer columns decoded; measure each
  mechanism separately. Preserve New Order and the adjacent sample workload
  without a repeated unexplained regression.
- Tests cover current and older snapshots, pending insert/update/delete,
  key changes, READ COMMITTED, SERIALIZABLE, cancellation, buffer lifetime,
  rollback and cleanup. The storage replacement additionally covers splits,
  vacuum, checkpoint, WAL replay, crash recovery and resource pressure.
- Keep workload SQL, schema, physical index inventory, seed, isolation,
  durability and runtime fixed across variants. Record individual interleaved
  samples and process or mechanism counters. Other host activity makes TPS
  variable; do not attribute a short-run difference without adjacent controls
  and a matching change in work performed.
- A clean full test build, source policy checks, independent durable-format and
  concurrency review, and the repository performance checkpoint rules pass
  before promotion. Cross-database claims additionally require eligible
  manifests, matching actual indexes and longer interleaved samples.
