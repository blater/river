---
id: tic-erebor
status: open
type: performance
priority: 1
assignee: blater
parent: tic-isildur
delivery: code
tags:
    - performance
    - storage
    - recovery
    - sql
created: 2026-09-29T03:23:25Z
---
# Reach primary-key rows without a separate logical-head lookup

[Tic-thranduil](tic-thranduil.md) measured about 225 `order_line` and 225
`stock` candidates per full Stock Level transaction, matching MariaDB's actual
plan. River performs about 451 separate logical-head lookups and 472 heap
fetches per transaction. After warmup, the profiled run recorded no metadata
directory or indexed-page file reads, so increasing metadata frames is not
the remedy for this Stock Level path. The former scalar base-row B-tree was
already removed; the current logical-head radix traversal and subsequent
version, row-location and heap access remain.

## Design and delivery

- Make a primary-key index probe reach the current visible row without the
  separate logical-head and heap-location traversal. Compare a clustered
  primary-index row representation with a direct version/row reference in the
  existing primary tuple entry, including update, split, page fanout, WAL and
  recovery costs. Choose one canonical physical layout; do not retain two
  permanent row authorities or add a fallback to the old path.
- Preserve stable logical identity semantics for secondary indexes and foreign
  keys, changing their physical references if the selected layout requires it.
  They must reach the same canonical row authority, without a retained legacy
  base-row access path. Resolve older snapshots and pending writes correctly.
  Define row-byte ownership across page pins and nested JOINs; avoid copying
  a wide payload merely to answer a narrow projection when a safe bounded
  borrow is possible.
- Use unchanged SQL and the harness-declared schema and index inventory across
  River and MariaDB. No Stock Level-specific index, query rewrite or
  benchmark-family code in storage.
- Measure exact primary-probe candidate, head, version, location, page-pin,
  retained-byte and CPU work before and after. Check full Stock Level, sample
  New Order, Order Status and the mixed workload for general benefit or
  regression. Keep TPS samples interleaved and repeat anomalous low results.

## Acceptance

Primary-key reads no longer perform the separate logical-head lookup or
row-location-directory access on the current visible-row path. Focused tests
cover matching/missing keys, non-key and key updates, delete/reinsert,
secondary-index/FK references, current and older snapshots, READ COMMITTED,
SERIALIZABLE, pending writes, cancellation, resource pressure, page splits,
vacuum, checkpoint, WAL replay and crash reopen. The superseded layout and
callers are deleted in the same delivery. Record the durable layout and
recreation contract in an ADR. A clean full build, independent
durable-format/concurrency review and the repository performance checkpoint
gate pass before merge. A cross-database performance claim additionally needs
matching actual index inventories and longer interleaved eligible samples.
