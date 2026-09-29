# Full-cardinality indexed-probe diagnosis

Ticket: [tic-thranduil](../../tickets/tic-thranduil.md). Source: `master`
`a8ceade9` (production engine JAR SHA-256
`9952756dec648338e009fc3ef47dc9ec133020fff30143e6e6c624146ade2b12`).
Harness: `5082670`, one warehouse and worker, seed 42, retry limit 3,
`full stock-level`, READ COMMITTED, durable WAL, GraalVM 25.0.4, `-Xmx1g`.
Measurements ran on 2026-09-29 UTC; host activity was not controlled.

The target commands were:

```sh
~/src/ingres/river-harness/benchmark run mariadb tpcc full stock-level \
  --warmup=40s --duration=10s --workers=1 --warehouses=1 \
  --seed=42 --max-retries=3 --no-report

~/src/ingres/river-harness/benchmark run river tpcc full stock-level \
  --river-executable=/private/tmp/river-ent-candidate/river \
  --river-version=thranduil-master-a8ceade9-a1 \
  --warmup=5s --duration=20s --workers=1 --warehouses=1 \
  --seed=42 --max-retries=3
```

The second ordinary River run changed only the version suffix to `a2`.
The profiled run used the same River workload settings and the JFR wrapper at
`/private/tmp/river-thranduil-river/dist/river` with
`--river-version=thranduil-master-a8ceade9-jfr --no-report`. The reported
counter passes used `/private/tmp/river-thranduil-diagnostic/run.sh` and
`--no-report`; their exact flags, source edits and logs remain in that
isolated directory. MariaDB's loaded plan SQL and its guarded lifecycle
command are retained in `/private/tmp/river-thranduil-mariadb-plan.sh`.

## Row work and actual MariaDB plan

The loaded MariaDB 13.0.2 schema had 100,000 `stock` and 299,836
`order_line` rows. Its actual `ANALYZE FORMAT=JSON` for the unchanged Stock
Level count SQL, district 1 and order window `[2981,3001)`, reported:

| Table | Access | Actual loops | Actual rows per loop | Indexed pages accessed |
| --- | --- | ---: | ---: | ---: |
| `order_line` | `PRIMARY` range on warehouse, district, order | 1 | 225 | 20 |
| `stock` | `PRIMARY` `eq_ref` on warehouse, item | 225 | 1 | 704 |

The query used `s_quantity < 15`, the harness catalogue's count SQL, and the
harness-created InnoDB schema. `SHOW INDEX` retained the expected primary,
item-FK and order-line secondary indexes; the prior matched inventory of all
15 workload indexes is recorded in the
[schema-parity checkpoint](../../performance-checkpoints.md#2026-09-28--foreign-key-support-index-reuse-and-schema-parity).
The `ANALYZE` query reported 0.162 ms, but it ran during the MariaDB harness
warmup and is not a paired latency comparison. The guarded 40-second-warmup,
10-second-measurement harness run passed at 4,779.50 TPS. Its plan and output
are `/private/tmp/river-thranduil-mariadb/plan-loaded.txt` and `harness.log`.
The earlier `plan.txt` was captured before data loading and is invalid for
this analysis.

A temporary counter build from `a8ceade9` ran the same River full profile for
5-second warmup and 30-second measurement. Within 27.038 complete measured
seconds, it counted 38,609 district rows, 8,687,239 `stock` candidates and
8,687,237 `order_line` candidates. Both candidate counts are **225.0 per
transaction**, matching MariaDB's actual row counts. It also counted
17,413,095 logical-head lookups (**451.0 per transaction**) and 18,223,883
heap fetches (**472.0 per transaction**, including noncandidate metadata and
other rows). The path makes one logical-head lookup for each base-row
candidate; it does not search the former scalar base-row B-tree.

The fetched rows' physical lengths sum to 4,001,304,397 bytes in that window
(103.6 kB per transaction). This is a sum of row lengths, **not** a claim that
all those bytes were copied or read from a file. `HeapRowResult.retainBytes`
copied 1,372,313,322 bytes (35.5 kB per transaction). The retained sizes
were 111 bytes per `stock` candidate and 44 bytes per `order_line` candidate;
the count SQL needs three `stock` columns and four `order_line` columns, all
numeric, and the narrow projection avoids copying their text payloads. The descriptor
reader borrows this already retained result and makes no second full-row copy
on the measured path. The counter build passed at 1,420.73 TPS with zero
retries, but its instrumentation changes CPU work, so its TPS is not a
candidate performance result. Counter source and complete one-second samples
are retained at `/private/tmp/river-thranduil-diagnostic` (`probe-counts.tsv`,
`phases-counts.tsv`, `harness-counts.log`). The isolated build passed
`:river-bench:installTps`; no diagnostic counters entered production source.

A final isolated 5/20-second pass counted the remaining metadata and page
steps. Across 17.023 complete measured seconds it saw 25,009 district rows,
5,626,850 candidates in each of `stock` and `order_line`, 11,278,711 head
lookups, 11,803,899 heap fetches, 46,240,245 current-page pins and
23,357,702 versioned-page pins. These are about 451 head lookups, 472 heap
fetches and 2,783 page-pin calls per transaction. Pin calls include other
transaction work; MariaDB's `pages_accessed` counter is not an equivalent
pin-call metric.

The same interval contained 23,497,982 version-record reads: 11,748,991
one-record cache hits and 11,748,991 frame hits, with **zero frame misses,
file reads or dirty evictions**. All 11,748,991 row-location directory reads
hit frames, again with zero misses, file reads or dirty evictions. The actual
working set touched 52 version frames and 15 row-location frames, below each
cache's 64-frame capacity. This confirms the JFR file observation directly.
The final diagnostic run passed invariants and cleanup at 1,459.39 TPS with
zero retries; its counter overhead precludes a throughput claim. The detailed
samples are `probe.tsv`, `directory-probe.tsv`, `phases.tsv` and `harness.log`
in the same temporary diagnostic directory. The production source and
benchmark SQL/schema/indexes were unchanged.

## File activity and CPU

An unchanged production JAR ran the same full profile with 5-second warmup,
20-second measurement and JFR with zero-threshold file events. Its run passed
at 1,480.66 TPS and zero retries. In the inferred measured window,
`04:04:52–04:05:12` Europe/London, JFR recorded **zero reads or writes** to
`river.indexed.versions`, `river.indexed.rows` or `river.indexed.pages`.
The five preceding warmup seconds recorded 27 version-directory reads, seven
row-directory reads and 810 indexed-page reads, so the file-event settings
were active. The measured window had 5,632 small SQL spill writes totaling
360,448 bytes and 20.3 ms of summed file-write duration. Since both metadata
frame miss paths call `file.read`, their file-reading miss rate was zero after
warmup in this run. JFR is at `/private/tmp/river-thranduil-river/probe.jfr`;
run output is `harness-jfr-escalated.log` there. The window boundaries are
inferred from the 5/20-second harness phases and JFR timestamps to within
about one second.

In that window, JFR recorded 909 Java execution samples on the virtual
`river-connection-0` worker. Top-frame counts included 117 in
`TupleKeyCodec.compare` (89 called by `comparePhysical`, 28 by
`comparePrefix`), 34 in `IndexedPageFrameMap.find`, 29 in
`StoredTableRowView.valueAt`, 12 in `IndexedVersionDirectory.frame`, nine in
`IndexedRowDirectory.frame`, and five in `IndexedLogicalHeadDirectory.lookup`.
Inclusive stacks contained 57 tuple-tree descents, 54 kernel row fetches, 29
head-directory lookups and 22 row-retention calls. These are samples, not
additive exact stage times.

JFR `jdk.CPULoad` averaged 0.12323 JVM CPU share across ten reported hardware
threads for the 20-second window, or about 24.65 core-seconds. Dividing by the
observed 450 base-row candidates per transaction and 29,614 commits gives
**1.85 microseconds of whole-server CPU per candidate**. This is a budget,
not isolated fetch time: it includes both tables, SQL execution, JIT and JFR
overhead. A temporary attempt at sampled `ThreadMXBean` stage timing produced
no samples because the connection worker is a virtual thread; no stage CPU
times are claimed from it.

Two ordinary, unprofiled 5/20-second River repeats passed with zero retries:

| Version label | TPS | p99 | Immutable harness report |
| --- | ---: | ---: | --- |
| `thranduil-master-a8ceade9-a1` | 1,438.82 | 0.855 ms | `river_harness_20260929_030737_6dcb5d0f` |
| `thranduil-master-a8ceade9-a2` | 1,463.54 | 0.845 ms | `river_harness_20260929_030928_bc253240` |

The reports are under `/Users/blater/src/ingres/river-harness/runs/` and
record complete invariants and owned-server cleanup. The MariaDB run used a
different warmup and measurement window, so its TPS is not paired with these
River results. No new baseline or cross-database speedup claim is designated.

## Decision

The Stock Level gap is **not caused by post-warmup metadata-directory file
misses** in this run. The 64-frame metadata caches still caused many misses in
the earlier mixed `full all` workload; that is a separate workload and does
not justify assigning its miss cost to Stock Level. The paused 256-frame
configuration branch remains unmerged pending its own repeated evidence.

The two systems examine the same 225 order-line and 225 stock candidates.
River adds a logical-head traversal, MVCC and row-location lookup, heap-page
access and a projected retention copy for each candidate. The former second
scalar B-tree is gone, but the head directory still imposes a separate page
path. These are general storage-layout costs. The next implementation ticket,
[tic-erebor](../../tickets/tic-erebor.md), tests primary-index-to-row access
that removes this indirection while preserving snapshots, updates and recovery.
Tuple-key comparison also deserves a separate general CPU investigation; it
should not be conflated with the metadata cache or claimed as a clustered-row
gain.
