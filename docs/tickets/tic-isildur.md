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

River's tuple-index scans return logical row IDs, then search the scalar base-row
tree, resolve MVCC visibility, locate a heap row, retain and copy the full row,
and decode columns the query may not use. This epic removes those costs through
one general read contract and one canonical storage layout. It is the next
architecture delivery under [tic-30c3](tic-30c3.md) and expands the Stock Level
work in [tic-72e5](tic-72e5.md).

## Design

1. Deliver [projected descriptor reads](tic-healthy-bellodonna.md). Derive
   required columns once from output projections, predicates, joins and index
   rechecks. Share the existing liveness owner where it applies, then carry the
   result into storage-owned decoding and copying. Preserve full-row mutation
   reads and every SQL semantic dependency.
2. Measure and choose one replacement for the extra scalar base-row search:
   clustered primary-row storage or a paged directly addressed logical-row
   head directory. Record read, update, page fanout, MVCC and recovery evidence
   before selecting. Implement the chosen format and remove the superseded
   path, including its writes and recovery. No permanent fallback or second
   implementation path remains.
3. Simplify row transfer under explicit buffer ownership. Remove intermediate
   full-row materialization where a pin or bounded borrowed view permits it;
   otherwise copy only the required values before releasing the pin.

## Acceptance Criteria

- The unchanged full Stock Level workload shows fewer base-tree searches,
  fewer bytes copied and fewer columns decoded; measure each mechanism
  separately. Preserve New Order and the adjacent sample workload without a
  repeated unexplained regression.
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
