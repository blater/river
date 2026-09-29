---
id: tic-thranduil
status: closed
type: performance
priority: 1
assignee: blater
parent: tic-isildur
delivery: evidence
tags:
    - performance
    - storage
    - sql
created: 2026-09-29T02:56:29Z
---
# Diagnose complete indexed-probe cost and select the next storage change

Full-cardinality Stock Level performs repeated indexed `stock` probes. Measure
the whole path before choosing between metadata-directory repair and a durable
storage-layout change. The benchmark's SQL, schema and indexes must remain
unchanged and comparable across database targets.

## Current source facts

- The tuple index returns a logical row ID. Relational base-row lookup now uses
  `IndexedLogicalHeadDirectory`; the former second scalar B-tree traversal was
  removed by [tic-base-row-head-directory](tic-base-row-head-directory.md).
- `IndexedKernelRowAccess.fetch` locates the heap row, pins its page and calls
  `HeapRowResult.retainBytes`. The tuple-scan path can retain a projection, but
  a narrow value still requires the fixed prefix and an owned copy after the
  page pin is released.
- `StoredTableRowView.bindFetched` borrows an already retained heap result. It
  does not make a second full-row copy on that path. Check pending, scan and
  mutation variants separately before attributing copied bytes.
- `IndexedVersionDirectory` and `IndexedRowDirectory` each have 64 fixed 64 KiB
  frames. Misses may perform file reads and dirty-victim writes. Capacity does
  not establish the working-set miss rate.

Earlier `full all` diagnostic counters on `e3225ffd` reported 80,386 version
frame misses and 9,227 row-location frame misses over 29 measured seconds, but
that mix does not establish the Stock Level miss rate. The paused
`feature/configurable-directory-frames` branch defaults both caches to 256 and
passed its checks; its first short pair did not establish a throughput gain.
See its checkpoint in that branch before reusing or merging it.

## Diagnosis

1. On the unchanged full-cardinality Stock Level query, count tuple-index
   candidates, logical-head lookups, version-record accesses, row-directory
   accesses, page pins, rows fetched and bytes retained. This read-only
   workload has no pending row mutations; record its committed path.
2. For both metadata directories, capture frame hits/misses, file-read bytes,
   dirty evictions, and time spent on misses. Capture page-cache/file reads and
   CPU samples for index traversal, visibility, metadata, heap fetch, retention,
   row decoding and SQL evaluation. Report totals and per successful probe.
   Use bounded diagnostic instrumentation or a profiler; remove temporary
   probes unless a named runtime consumer justifies keeping them.
3. Run the equivalent full-profile MariaDB Stock Level SQL under the harness's
   declared DDL. Record `EXPLAIN`/`ANALYZE` actual access paths, estimated and
   actual rows, and handler/rows-examined counters where available. Compare
   actual row work and index inventory, not only transaction throughput.
4. Use at least two identical short River samples and repeat any anomalous low
   result once. Keep host activity, workload, runtime, seed, isolation,
   durability and cardinality visible. Interleave longer controls before a
   performance claim. Preserve artifacts and commands in
   `docs/performance-checkpoints.md`.

## Result and decision

The [complete diagnosis](../delivery/evidence/2026-09-29-tic-thranduil-indexed-probes.md)
records actual River candidate/head/heap counts, retained bytes, JFR file
activity and CPU samples, and MariaDB's loaded `ANALYZE` plan. Both targets
examined 225 `order_line` and 225 `stock` candidates per transaction. River
performed about 451 logical-head lookups and copied 35.5 kB of selected row
bytes per transaction. The final counter pass found 52 version and 15
row-location frames in the working set, with zero measured frame misses after
warmup. The 20-second profiled measured window also recorded zero
version-directory, row-directory and indexed-page file reads.

The remaining Stock Level gap is therefore not assigned to metadata cache
misses. The extra general storage-layout work is owned by
[tic-erebor](tic-erebor.md), which must compare and deliver a primary-key path
that reaches the row without the logical-head indirection. Mixed `full all`
metadata-cache misses remain a separate issue; the paused 256-frame branch
has not established a repeatable gain. No production code or TPS baseline was
changed by this diagnostic ticket.
