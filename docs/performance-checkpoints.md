# River performance checkpoints

This page tracks new baselines and the findings that matter for current performance
work. Full historical commands, individual samples, decisions and artifact IDs
through 2026-09-16 are in the [checkpoint archive](performance-checkpoints-archive-2026-09-16.md).
Diagnostic samples are not audited TPC-C results or general performance claims.

## Baseline stats

Append a row when a measured run is designated as a new performance baseline.
Keep earlier rows and compare results only within a compatible workload, platform
and runtime configuration.

| Measured start (UTC) | Branch at run | Ticket | Source | Workload and runtime | Committed TPS | p99 (ms) | Evidence |
| --- | --- | --- | --- | --- | ---: | ---: | --- |
| 2026-09-16 13:31:02 | Unrecorded | `tic-thuringwethil` | `deb8da4c` | `sample new-order`; 1 worker, 1 warehouse; GraalVM 25.0.4 JVM, macOS/arm64; 20s warmup, 30s measured | 374.025 | 4.391 | `river_harness_20260916_133040_34258f1d`; [checkpoint](performance-checkpoints-archive-2026-09-16.md#complexity-completion-and-periodic-performance-checkpoint-3--2026-09-16) |
| 2026-09-27 07:09:58 | `master` source snapshot | None | `c9c216d3` | `sample new-order`; 1 worker, 1 warehouse; GraalVM 25.0.4 JVM, macOS/arm64; 20s warmup, 30s measured | 346.265 | 4.944 | `river_harness_20260927_070936_84c40fe8`; [investigation](plans/river-current-hotpath-probes.md) |
| 2026-09-27 16:47:52 | `master` source snapshot | None | `c9c216d3` | `sample order-status`; 1 worker, 1 warehouse; GraalVM 25.0.4 JVM, macOS/arm64; 20s warmup, 30s measured | 1,920.906 | 0.839 | `river_harness_20260927_164729_f61ceff9`; [read-only comparison](#2026-09-27--one-worker-read-only-comparison) |
| 2026-09-27 16:52:11 | `master` source snapshot | None | `c9c216d3` | `sample stock-level`; 1 worker, 1 warehouse; GraalVM 25.0.4 JVM, macOS/arm64; 20s warmup, 30s measured | 1,064.832 | 1.082 | `river_harness_20260927_165149_3ea012e9`; [read-only comparison](#2026-09-27--one-worker-read-only-comparison) |
| 2026-09-27 23:57:22 | `ticket/tic-a29fc0d0ece668f5aa0a5fd8ba576f15-order-status-batches` | `tic-a29fc0d0ece668f5aa0a5fd8ba576f15` | River `e128e066`; harness `4a2c185`, version `e128e066-jvm-batched-b` | `sample order-status`; 1 worker, 1 warehouse, seed 42, retry limit 3; GraalVM 25.0.4 JVM `-Xmx1g`, macOS/arm64; 5s warmup, 20s measured | 3,763.695 | 0.428 | `river_harness_20260927_235714_cce57930`; [checkpoint](#2026-09-27--order-status-row-batching) |
| 2026-09-28 00:57:54 | `ticket/tic-celegorm-order-status-program` | `tic-celegorm` | River `8a37147f`; harness `f8e615a`, version `tic-celegorm-clean-jvm` | `sample order-status`; 1 worker, 1 warehouse, seed 42, retry limit 3; GraalVM 25.0.4 JVM `-Xmx1g`, macOS/arm64; 5s warmup, 20s measured | 12,913.782 | 0.122 | `river_harness_20260928_005746_33a8ee5a`; [checkpoint](#2026-09-28--one-request-order-status-program) |
| 2026-09-28 02:05:19 | `feature/stock-level-root-filter` | `tic-72e5` | River `40470245`; harness `2ab18c9`, version `40470245-stock-first-candidate` | `sample stock-level`, stock-first SQL; 1 worker, 1 warehouse, seed 42, retry limit 3; GraalVM 25.0.4 JVM `-Xmx1g`, macOS/arm64; 10s warmup, 30s measured | 1,957.795 | 0.618 | `river_harness_20260928_020507_3d0d57eb`; [checkpoint](#2026-09-28--stock-level-root-filter-checkpoint) |
| 2026-09-28 02:20:12 | `feature/stock-join-text-pruning` | `tic-72e5` | River `e56da68c`; harness `df66a3a`, version `e56da68c-text-prune-candidate` | `sample stock-level`, stock-first SQL; 1 worker, 1 warehouse, seed 42, retry limit 3; GraalVM 25.0.4 JVM `-Xmx1g`, macOS/arm64; 10s warmup, 30s measured | 2,062.893 | 0.598 | `river_harness_20260928_022000_32b6ff1c`; [checkpoint](#2026-09-28--numeric-join-text-materialization-checkpoint) |
| 2026-09-28 02:59:16 | `feature/stock-distinct-inline` | `tic-72e5` | River `209f8b37`; harness `df66a3a`, version `distinct-inline-209f8b37` | `sample stock-level`, stock-first SQL; 1 worker, 1 warehouse, seed 42, retry limit 3; GraalVM 25.0.4 JVM `-Xmx1g`, macOS/arm64; 5s warmup, 30s measured | 3,817.486 | 0.354 | `river_harness_20260928_025909_cd482460`; [checkpoint](#2026-09-28--inline-distinct-checkpoint) |
| 2026-09-28 03:19:17 | `feature/stock-singleton-row-store` | `tic-72e5` | River `1bf08325`; harness `df66a3a`, version `singleton-1bf08325` | `sample stock-level`, stock-first SQL; 1 worker, 1 warehouse, seed 42, retry limit 3; GraalVM 25.0.4 JVM `-Xmx1g`, macOS/arm64; 5s warmup, 30s measured | 4,424.739 | 0.273 | `river_harness_20260928_031909_32bac37c`; [checkpoint](#2026-09-28--single-row-store-checkpoint) |
| 2026-09-28 03:59:08 | `feature/stock-validated-root-filter` | `tic-72e5` | River `e029efdc`; harness `df66a3a`, version `validated-filter-e029efdc` | `sample stock-level`, stock-first SQL; 1 worker, 1 warehouse, seed 42, retry limit 3; GraalVM 25.0.4 JVM `-Xmx1g`, macOS/arm64; 5s warmup, 30s measured | 5,018.188 | 0.267 | `river_harness_20260928_035900_d016abce`; [checkpoint](#2026-09-28--validated-root-row-filter-checkpoint) |
| 2026-09-28 04:25:36 | River `feature/stock-validated-root-filter`; harness `feature/stock-level-program` | `tic-72e5` | River `e029efdc` (merged `2d21b5e5`); harness `4c16840` (merged `dae4786`), version `validated-filter-e029efdc` | `sample stock-level`, one-request program; 1 worker, 1 warehouse, seed 42, retry limit 3; GraalVM 25.0.4 JVM `-Xmx1g`, macOS/arm64; 5s warmup, 30s measured | 10,328.801 | 0.130 | `/private/tmp/river-harness-stock-program/runs/river_harness_20260928_042529_a09ebfaa`; [checkpoint](#2026-09-28--stock-level-read-program-checkpoint) |
| 2026-09-28 07:02:16 UTC | `feature/inner-join-order-cost` | `tic-72e5` | River `34ee0800`, version `join-cost-34ee0800`; harness `7c4b90d` | `full stock-level`, one-request program; 1 worker, 1 warehouse, seed 42, retry limit 3; GraalVM 25.0.4 JVM `-Xmx1g`, macOS/arm64; 5s warmup, 30s measured | 1,131.222 | 1.109 | `/private/tmp/river-harness-stock-analyze/runs/river_harness_20260928_070044_7e89c8f0`; [checkpoint](#2026-09-28--full-stock-level-costed-inner-join-order) |
| 2026-09-28 08:41:56 UTC | `feature/index-root-snapshot-cache` | `tic-72e5` | River `b17e0450`, version `b17e0450-jvm-clean`; harness `eba8ab0` | `full stock-level`, one-request program; 1 worker, 1 warehouse, seed 42, retry limit 3; GraalVM 25.0.4 JVM `-Xmx1g`, macOS/arm64; 5s warmup, 30s measured | 1,334.778 | 0.914 | `/private/tmp/river-harness-stock-analyze/runs/river_harness_20260928_084035_50a125d8`; [checkpoint](#2026-09-28--cache-versioned-index-roots-across-join-probes) |

The initial row was the latest recorded run as of this table's creation. Its source
commit is on `master`, but the branch checked out during the run was not recorded.
The run used READ COMMITTED, durable local WAL, loopback TCP/TLS, seed 42,
retry limit 3 and `-Xmx1g`; it passed validation with zero retries, failed
outcomes or unknown commits. The archived checkpoint retains the run-to-run
variation and open cumulative regression investigation. This focused New Order
sample is not directly comparable with full-mix results.

## What the older measurements established

| Observed repeated work | Recorded change and result | Scope |
| --- | --- | --- |
| Table descriptor resolution occupied 16.1% of sampled request/commit CPU. | Transaction-scoped bindings reduced the sampled share to 1.8%; short full-mix JVM candidates were 338.8/332.5 TPS against initial 307.2/303.3 controls and an interleaved 287.0 control. | [Transaction bindings](performance-checkpoints-archive-2026-09-16.md#2026-09-10--transaction-scoped-descriptor-bindings-tic-5c21) |
| Repeated server PREPARE occupied 16.9% of sampled request/commit CPU. | Session-local sharing reduced that share to 3.2%; the longer matched JVM pair was 371.7 to 389.8 TPS. | [Shared preparation](performance-checkpoints-archive-2026-09-16.md#2026-09-10--share-live-prepared-statements-tic-7a32) |
| River awaited 140 exchanges for a successful ten-line New Order. | Removing prepared-close replies reduced this to 94; a longer JVM pair moved from 392.58 to 429.27 TPS. MariaDB's corresponding count was 95. | [Prepared release](performance-checkpoints-archive-2026-09-16.md#2026-09-10--one-way-prepared-release-tic-6f28) |
| The external Go worker prepared SQL again through `Tx.StmtContext`. | Retaining its prepared catalogue cut socket writes from 65.35 to 33.95 per full-mix commit; 120-second River controls were 535.60/459.98 TPS and candidates were 691.81/685.50 TPS with the same River binary. | [Consumer checkpoint](performance-checkpoints-archive-2026-09-16.md#2026-09-14--local-prepared-catalogue-checkpoint-tic-gwindortic-rian) |

These gains are from different source and harness comparisons; they must not be
added together or applied as a correction to the older MariaDB ratio. The 2026-09-11
M5 full-mix diagnostic recorded River median 447.48 TPS and MariaDB median
1,126.48 TPS, with differing TCP/TLS and Unix-socket transports. The later
one-worker New Order comparison recorded River 375.36–383.52 TPS and MariaDB
919.65–937.79 TPS; its River server CPU was 2.26–2.55 ms/commit versus
0.687–0.691 ms/commit. Its external harness used the earlier binding, so it
does not measure the current prepared-catalogue consumer. These comparisons
establish a configuration-level gap, not its complete cause.

The latest New Order profile sampled B-tree internal-node routing, page-map
lookup, key comparison and lock comparison. Removing whole-page read CRC and
repeated descriptor checks produced no established TPS gain. A later binary
routing recheck produced no established throughput gain either. The remaining
cross-database CPU, wait, WAL-force, transport and allocation costs have not
been decomposed on one matched current configuration; the cumulative complexity
regression investigation also remains open. See the [current-build comparison](delivery/evidence/2026-09-15-tic-gothmog-current-comparison.md),
[routing recheck](performance-checkpoints-archive-2026-09-16.md#2026-09-16--scalar-routing-recheck-with-retained-warmup-workers-tic-waymeet),
and [latest complexity checkpoint](performance-checkpoints-archive-2026-09-16.md#complexity-completion-and-periodic-performance-checkpoint-3--2026-09-16).

## Historical checkpoint archive

The [archive](performance-checkpoints-archive-2026-09-16.md) retains every prior
checkpoint entry, including failed and inconclusive samples, the original
configuration and version labels, clean-build outcomes, independent reviews,
rollback decisions and immutable artifact identifiers. Append new accepted
checkpoint detail below this section and add a baseline-table row only when a
run is designated as a new baseline.

## 2026-09-27 — current hot-path investigation

Source was a frozen committed `master` snapshot at `c9c216d3`, external harness
`7d91f4f`, GraalVM 25.0.4, `-Xmx1g`, durable local WAL, TCP/TLS for River and
Unix socket for MariaDB. All reported runs used sample data, one worker and
warehouse, seed 42, READ COMMITTED, retry limit 3 and 20 seconds of warmup.
The initial 30-second New Order pair passed at 346.265 River and 904.973
MariaDB commits/s, p99 4.944 and 1.697 ms respectively. The Payment pair passed
at 2,035.090 and 4,481.791 commits/s, p99 0.785 and 0.371 ms. These pairs had
matching eligible comparison keys within each workload, successful invariants,
and zero retries, failures or unknown commits. They compare whole targets with
different transport, not isolated database kernels. Artifact IDs and exact
scope are in the [two-probe plan](plans/river-current-hotpath-probes.md).

A separate profiled River New Order run passed at 345.87 commits/s. Its measured
window contained 669 Java execution samples: 34 ended in
`BTreePage.childForKey`, 28 in `TupleKeyCodec.compare`, 25 in
`IndexedPageFrameMap.find`, and 16 in `LockIntervalOrder.compare`. These are
sample counts, not process CPU shares or predicted throughput gains. A temporary
scalar-routing counter observed 9,121,843 routing calls and 465,992,172
separator comparisons across setup, warmup and measurement, averaging 51.1
comparisons/call; 4,794,370 calls visited nodes with at least 64 separators.
This confirms repeated linear work but does not isolate measured-window CPU.
The unmodified tuple comparator's temporary prefix counter found zero first
differences before byte 8, 2,324,287 at bytes 8–15, 37,367,780 at 16–31,
30,505,649 at 32–63, 557 at byte 64 or later, and 9,614,004 comparisons
equal through the shared length. It also covers setup and warmup.

The independent reviewer approved two bounded probes with buffer-state and
current-build measurement requirements. Each candidate differed from the
control by exactly one jar. Focused routing storage/engine tests and revised
tuple format/storage/engine tests passed. All reported New Order workload
samples below passed eligibility, invariants, accounting and owned cleanup,
with zero retries, failures or unknown commits.

| Probe and run order | Committed TPS | p99 ms | Artifact |
| --- | ---: | ---: | --- |
| Routing 30s control A | 378.10 | 4.633 | `river_harness_20260927_072344_c5a90aaf` |
| Routing 30s candidate A | 382.27 | 4.272 | `river_harness_20260927_072446_b944c30f` |
| Routing 30s candidate B | 384.83 | 4.112 | `river_harness_20260927_072545_d3ea9f24` |
| Routing 30s control B | 379.13 | 4.248 | `river_harness_20260927_072645_8a5d74ea` |
| Routing 60s candidate A | 388.13 | 4.090 | `river_harness_20260927_073447_14be89ec` |
| Routing 60s control A | 366.48 | 4.448 | `river_harness_20260927_073617_83f9a251` |
| Routing 60s control B | 369.72 | 4.424 | `river_harness_20260927_073759_13c14128` |
| Routing 60s candidate B | 349.77 | 4.600 | `river_harness_20260927_073931_e0bd9dd1` |
| `getLong` tuple 30s control A (routing control B) | 379.13 | 4.248 | `river_harness_20260927_072645_8a5d74ea` |
| `getLong` tuple 30s candidate A | 370.36 | 4.415 | `river_harness_20260927_072756_0f29c381` |
| `getLong` tuple 30s candidate B | 348.53 | 4.559 | `river_harness_20260927_072856_4c7ad8a6` |
| Tuple 30s control B | 372.87 | 4.284 | `river_harness_20260927_072958_8d797d90` |
| VarHandle tuple 30s control A | 384.13 | 4.131 | `river_harness_20260927_074152_cad64e7f` |
| VarHandle tuple 30s candidate A | 359.95 | 4.563 | `river_harness_20260927_074251_c391450a` |
| VarHandle tuple 30s candidate B | 326.86 | 5.411 | `river_harness_20260927_074352_0f157ed5` |
| VarHandle tuple 30s control B | 332.00 | 4.907 | `river_harness_20260927_074451_513ce0d4` |

The routing 30-second candidates were above both adjacent controls. In the
60-second sequence, the early candidate beat both controls while the late
candidate lost to both. Neither direction explains the run-to-run host
variation. The tuple-word variants had adverse samples; the
unchanged control also fell sharply during the VarHandle sequence. A standalone
word-read probe favored wide comparisons, but it is not transaction evidence.
No current paired process CPU/commit was captured. **Decision:** retain both
changes as unaccepted probes; do not merge, tag or claim a throughput gain. The
next bounded step is the existing [CPU-variation investigation](tickets/tic-voronwe.md),
with identical-binary and process-CPU controls before revisiting either probe.
Temporary source, JFR, counters, commands and variant jars are under
`/private/tmp/river-perf-investigation/`; `commands.json` reconstructs the
exact target command and version label from each immutable report, and
`runtime-hashes.json` records all 19 jar hashes per variant.

## 2026-09-27 — one-worker read-only comparison

The external harness ran its `sample order-status` and `sample stock-level`
categories with one worker and warehouse, seed 42, READ COMMITTED, retry limit
3, 20 seconds of warmup and 30 seconds measured. Both categories execute only
`SELECT` statements and contain no `FOR UPDATE`; the harness still begins and
commits each transaction. River used the frozen committed `master` source
`c9c216d3` on GraalVM 25.0.4 with `-Xmx1g` and loopback TCP/TLS. MariaDB used
the harness-owned Unix socket. Each category's four runs shared an eligible
comparison key, passed invariants and cleanup, and reported zero retries,
failures and unknown commits. One attempt cancelled at the measurement boundary
in most runs; it was not counted as a failed or committed transaction. These
are diagnostic target comparisons with different transports.

| Category | River committed TPS, two runs | MariaDB committed TPS, two runs | MariaDB/River mean ratio |
| --- | ---: | ---: | ---: |
| Order Status | 2,030.07; 1,920.91 | 6,817.80; 6,889.29 | 3.47 |
| Stock Level | 1,098.97; 1,064.83 | 5,995.79; 5,981.25 | 5.54 |

Order Status artifacts, in River/MariaDB/MariaDB/River order:
`river_harness_20260927_164436_5c972d51`,
`river_harness_20260927_164534_cc8c41f7`,
`river_harness_20260927_164632_770ea826`,
`river_harness_20260927_164729_f61ceff9`. Stock Level artifacts in the same
order: `river_harness_20260927_164856_614b5f56`,
`river_harness_20260927_164953_9755225f`,
`river_harness_20260927_165050_04a583e7`,
`river_harness_20260927_165149_3ea012e9`. Bundles are under
`/Users/blater/src/ingres/river-harness/runs/`. The latest River run for each
category is also in the baseline table above.

A temporary copy of harness `7d91f4f` added monotonic timers around the
transaction and individual read phases. Its 20-second warmup plus 30-second
measurement totals include both phases, so these means are diagnostic phase
attribution, not separately measured-window latency. The same timing code ran
on both targets, passed the workload and validation, and left the original
harness executable restored. The temporary source and binaries are under
`/private/tmp/river-perf-investigation/harness-timing/`.

| Timed phase, mean microseconds | River | MariaDB |
| --- | ---: | ---: |
| Order Status whole transaction | 528.405 | 144.110 |
| Order Status order-line query stage | 315.484 | 30.342 |
| Order-line `QueryContext` | 45.331 | 26.860 |
| Order-line `Rows.Next()`, per call | 24.118 | 0.124 |
| Stock Level whole transaction | 953.932 | 147.420 |
| Stock Level `COUNT(DISTINCT ...)` join query | 741.257 | 90.209 |

Each Order Status transaction read about 10.08 order lines and called
`Rows.Next()` about 11.08 times, including the end-of-stream check. River's
first row is prefetched; each subsequent row calls
`protocol.exchange(messageFetch, ...)` in the harness River driver. The final
row response carries end-of-stream, so the following check completes locally.
MariaDB's
driver reads the result stream without issuing a new command for each row.
The timed `Rows.Next()` difference is about 266 microseconds per transaction,
or 69% of the 384-microsecond whole-transaction difference in that timed pair.
This establishes per-row request/reply as the dominant Order Status cost in
this configuration. It does not separate TLS from the protocol exchange and
server work performed for each `FETCH`.

Stock Level returns one aggregate row, so repeated result-row fetches do not
explain its gap. Its join query accounts for 651 microseconds, or 81%, of the
807-microsecond whole-transaction difference in the timed pair. On the same
sample data and representative warehouse/district/order range, River's
`EXPLAIN ANALYZE` reported 207 primary rows and 207 lookup/join steps before
89 rows passed the stock-quantity filter. MariaDB's `ANALYZE FORMAT=JSON`
reported 100 stock rows, 45 passing its quantity filter, then 45 indexed
order-line lookups averaging 3.11 rows each. Thus the two engines use different
join orders and River performs more inner lookups. The plans and timing do not
quantify how much of the remaining per-lookup cost comes from River's B-tree,
row decoding, SQL execution or transport. The River Stock Level JFR at
`/private/tmp/river-perf-investigation/stock-level.jfr` had 356 measured-window
Java execution samples spread across those paths; no single method dominated.

The read-only gaps exclude retries and contended write locks as their cause.
They do not measure the separate write, WAL, and commit costs in New Order or
Payment. The Order Status batching result is recorded below. For Stock Level,
compare the current join plan with a stock-filter-first plan
and measure its indexed lookup count and transaction time before accepting an
optimizer change.

## 2026-09-27 — Order Status row batching

Ticket [`tic-a29fc0d0ece668f5aa0a5fd8ba576f15`](tickets/tic-a29fc0d0ece668f5aa0a5fd8ba576f15.md)
starts from `c9c216d3` and adds byte-bounded v6 query response batches in
`e128e066`. The external Go adapter is `4a2c185`. Query open and `FETCH` can
return complete row frames in one server write; clients consume marked queued
frames before another request. Wide rows retain continuation framing. The
protocol, Java client/JDBC, server, and Go adapter use one row representation.

All comparison samples used `sample order-status`, one worker and warehouse,
seed 42, READ COMMITTED, retry limit 3, five seconds warmup and 20 seconds
measured. River used loopback TCP/TLS and MariaDB used its harness-owned Unix
socket. Every listed run passed validation and shutdown with zero retries,
failed outcomes and unknown commits. The comparison metadata was eligible and
had the same key,
`7ac8391ed14cc60edb4fb4f8c6636eb9416bf5d1af3a11ada7cd40664bceecb8`.
The short local samples support a directional result for this mechanism, not
an audited TPC-C or general platform claim.

| JVM run order | Base/candidate | Committed TPS | Mean latency (µs) | p99 (µs) | Immutable artifact |
| --- | --- | ---: | ---: | ---: | --- |
| 1 | base `c9c216d3` | 1,939.323 | 515.286 | 840.191 | `/Users/blater/src/ingres/river-harness/runs/river_harness_20260927_235557_6c7dc3b8` |
| 2 | candidate `e128e066` | 3,747.390 | 266.536 | 446.463 | `/private/tmp/river-harness-order-status/runs/river_harness_20260927_235635_adb52cc2` |
| 3 | candidate `e128e066` | 3,763.695 | 265.378 | 428.031 | `/private/tmp/river-harness-order-status/runs/river_harness_20260927_235714_cce57930` |
| 4 | base `c9c216d3` | 1,915.505 | 521.568 | 843.775 | `/Users/blater/src/ingres/river-harness/runs/river_harness_20260927_235752_964a2955` |

The mean candidate/control throughput ratio was 1.95. The JVM control was the
recorded GraalVM 25.0.4 `-Xmx1g` distribution; its protocol jar hash matches
the `c9c216d3` checkout. The candidate distribution is retained at
`/private/tmp/river-order-status-evidence/jvm-candidate/`.

A separate ABBA native check used the same GraalVM 25.0.4 and `-O2` for both
builds: controls 1,436.747/1,451.529 TPS and candidates
2,541.691/2,558.694 TPS, a 1.77 mean ratio. Artifacts in run order are
`river_harness_20260927_234049_5e5eba49`,
`river_harness_20260927_234126_91870b9f`,
`river_harness_20260927_234203_0795277f`, and
`river_harness_20260927_234235_723187a1` under the two harness checkouts
above. Native binaries and SHA-256 hashes are retained in
`/private/tmp/river-order-status-evidence/`. Standard `-O3` native compilation
failed in GraalVM while compiling unchanged
`IndexedKernelVisibility.nextEntry`; the O2 pair is labelled separately and
does not establish O3 performance.

The matching MariaDB diagnostic was 6,752.629 TPS, 147.807 µs mean and
222.847 µs p99, artifact
`/Users/blater/src/ingres/river-harness/runs/river_harness_20260927_234318_82fe58e2`.
Temporary phase timing over a 2-second warmup plus 10-second measurement
showed River's order-line `Rows.Next()` at 0.343 µs per call versus MariaDB's
0.131 µs, while the whole transaction was 395.992 versus 148.016 µs. River
versus MariaDB phase means were begin 38.742/23.922, commit 40.163/13.501,
customer identity 81.362/31.318, customer read 65.830/22.149, latest order
59.466/25.284 and line query 110.059/31.550 µs. The line query's opening
and batch delivery cost 103.975/27.896 µs. These timed totals include warmup
and are diagnostic phase attribution, not the measured-window latency.
The remaining gap is spread across request and query stages; this read-only
one-worker workload had no retry or lock-contention signal.

Clean `./gradlew --no-daemon --project-cache-dir
/private/tmp/river-order-status-project-cache clean test` passed in 3m20s
across 116 tasks. Focused protocol/client/server tests passed after the final
batch-policy refactor. The Go harness passed `go test ./...`,
`go test -race ./internal/dbms/river` and `go vet ./...`.
Slopmark review moved `ProtocolFrameCodec` from 35.3925 to 35.3925 and
`ProtocolResponseAdmission` from 27.2088 to 27.5988 after keeping batch policy
in `ProtocolRowBatch`; `SessionEndpoint` moved 144.519 to 147.877 and
`ServerResponseBuffer` 26.2288 to 43.3863 within its response storage and
publication responsibility.

Decision: accept the bounded row-batch feature. It removes the repeated
Order Status fetch request/reply cost and passes the correctness and cleanup
gates. The remaining roughly 1.8-fold JVM gap to MariaDB is a separate
request/statement execution investigation; this checkpoint does not claim
overall parity.

## 2026-09-28 — residual Order Status request timing

Temporary counters on the accepted JVM server and Go adapter split each
protocol request into client send, client receive, server processing, and
server response write. The final `sample order-status` run used one worker and
warehouse, seed 42, READ COMMITTED, a 2-second warmup and 10-second measured
window. It passed validation and cleanup with 3,492.84 committed TPS, zero
retries, failed outcomes and unknown commits. Its immutable artifact is
`/Users/blater/src/ingres/river-harness/runs/river_harness_20260928_001505_e13ba4fd`.
The temporary source patches and counter logs are under
`/private/tmp/river-order-status-evidence/`; neither instrumentation patch is
in production source.

| Request type | Calls | Client send mean | Client receive mean | Header read mean | Body read mean | Decode mean | Server process mean | Server write mean |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| EXECUTE (including transaction control and setup) | 81,093 | 2.991 µs | 41.359 µs | 40.754 µs | 0.175 µs | 0.059 µs | 11.205 µs | 3.574 µs |
| BEGIN_PREPARED_QUERY | 164,689 | 3.200 µs | 46.590 µs | 45.614 µs | 0.201 µs | 0.415 µs | 16.318 µs | 3.705 µs |

These counters span setup, warmup and measurement, so they are mechanism
timings, not measured-window latency decomposition. Client receive time is
dominated by waiting for the response header; body reading and decoding are
small. About 26 µs per request lies between the start of server processing
and completion of the client's header read after subtracting measured server
processing and response writing. That interval includes socket/TLS handling,
thread scheduling and any unmeasured dispatch work. The current counters do
not separate those components or establish how much each contributes to the
MariaDB comparison, which also uses a different transport. The next candidate
must reduce dependent request/reply exchanges, using River's existing
transaction-program protocol if it can preserve the full Order Status result
and failure semantics; a same-transport control is needed before assigning the
remaining cross-database difference to the engine.

## 2026-09-28 — one-request Order Status program

Ticket [`tic-celegorm`](tickets/tic-celegorm.md) extends the generic transaction
program with ordered row selection and a pre-commit row-set count requirement.
The Go binding executes either customer-ID or median-by-last-name Order Status
as one prepared program request. The selected customer and latest order ID feed
later steps in the same READ COMMITTED transaction. The 5–15 order-line count
is checked before commit. River engine and protocol contain no TPC-C-specific
logic. River source was `8a37147f` and the harness candidate was `f8e615a`.

The A–B–B–A diagnostic used the same River JVM build for both paths: the
accepted batched SQL binding at harness `f018ab1` versus the program binding
at `f8e615a`. It used `sample order-status`, one worker and warehouse, seed 42,
retry limit 3, 2-second warmup and 10-second measurement. All listed runs
passed invariants and cleanup with zero retries, failed outcomes and unknown
commits. Their comparison key was
`2862b245c7074f4c41c53dc0bd0508f154b239fab64bb8bf626ee9872a42232d`.

| Order | Binding | Committed TPS | Mean (µs) | p99 (µs) | Immutable artifact |
| --- | --- | ---: | ---: | ---: | --- |
| A1 | batched SQL | 3,604.042 | 277.146 | 492.543 | `/Users/blater/src/ingres/river-harness/runs/river_harness_20260928_004819_8bfce9b5` |
| B1 | one program request | 12,826.193 | 77.702 | 140.799 | `/private/tmp/river-harness-order-status/runs/river_harness_20260928_004835_00a2d8bb` |
| B2 | one program request | 12,798.516 | 77.859 | 143.231 | `/private/tmp/river-harness-order-status/runs/river_harness_20260928_004850_95a5f9f8` |
| A2 | batched SQL | 3,485.051 | 286.608 | 551.423 | `/Users/blater/src/ingres/river-harness/runs/river_harness_20260928_004952_4044f7c5` |

The mean program/batched throughput ratio was 3.61 for this same-server
diagnostic. An earlier A2 attempt completed its workload but failed server
shutdown (`river_harness_20260928_004905_dbe994b4`); it is excluded. No River
server remained, the harness-owned temporary instance was removed, and the
replacement A2 passed.

The matched MariaDB–River–River–MariaDB pair used the program harness,
5-second warmup and 20-second measurement with the same profile, seed,
warehouse, worker and retry limit. River used loopback TCP/TLS; MariaDB used
its harness-owned Unix socket. All four runs were eligible with comparison key
`7ac8391ed14cc60edb4fb4f8c6636eb9416bf5d1af3a11ada7cd40664bceecb8`,
passed validation and cleanup, and had zero retries, failed outcomes or
unknown commits.

| Order | Target | Committed TPS | Mean (µs) | p99 (µs) | Immutable artifact |
| --- | --- | ---: | ---: | ---: | --- |
| M1 | MariaDB | 6,777.341 | 147.263 | 213.887 | `/private/tmp/river-harness-order-status/runs/river_harness_20260928_005649_6362fe61` |
| R1 | River | 12,881.292 | 77.373 | 117.631 | `/private/tmp/river-harness-order-status/runs/river_harness_20260928_005718_8a235427` |
| R2 | River | 12,913.782 | 77.183 | 122.303 | `/private/tmp/river-harness-order-status/runs/river_harness_20260928_005746_33a8ee5a` |
| M2 | MariaDB | 6,701.492 | 148.946 | 226.431 | `/private/tmp/river-harness-order-status/runs/river_harness_20260928_005814_dec351d3` |

The mean River/MariaDB ratio was 1.91 for this one-worker Order Status
diagnostic. The result resolves the measured single-worker Order Status gap;
it does not establish full-mix parity or transport-normalized engine speed.

Focused engine API, engine and protocol tests passed. A clean
`./gradlew --no-daemon clean test` passed in 3m17s (116 tasks). The Go harness
passed `go test ./...`, affected-package `go test -race`, and affected-package
`go vet`. The JVM candidate distribution is retained at
`/private/tmp/river-order-status-evidence/jvm-program/`. Slopmark did not
surface a newly broadened high-scoring production owner in this slice.

Decision: accept the generic ordered-row program capability and its Order
Status consumer. The remaining performance investigation concerns other
transaction families and concurrency profiles.

## 2026-09-28 — Stock Level root-filter checkpoint

Ticket [`tic-72e5`](tickets/tic-72e5.md) remains open. The accepted River
`master` at `db8b3146` evaluated root-only stock quantity conditions after
joining. A representative `EXPLAIN ANALYZE` with stock first showed 100 stock
rows and 207 indexed join rows before the filter. On feature branch
`feature/stock-level-root-filter`, commit `40470245`, a generic filter tests
mandatory, root-local, total `WHERE` comparisons before probing either JOIN
executor. The full `WHERE` still runs on joined rows. The standalone harness
branch `diagnostic/stock-root`, commit `2ab18c9`, orders the equivalent Stock
Level SQL from `stock` and labels its transaction catalogue v2.

The same representative input on the candidate had 100 root stock rows and
8 indexed lookup rows. SQL results and the harness invariants passed. The
number of lookup rows varies with the input threshold; 8 is one plan sample,
not an average across the measured workload. Focused JOIN tests covered
`AND`/`OR`, scalar aggregation and an `ON` expression-error boundary. The
affected engine suite passed, followed by a clean
`./gradlew --no-daemon clean test` in 3m12s (116 tasks). Harness `go test ./...`
passed. The only binary difference in the matched River runs was the engine
JAR. The candidate distribution is retained at
`/private/tmp/river-stock-evidence/root-filter-program/`.

Interleaved old–new–new–old samples used the committed stock-first harness
catalogue, sample data, one worker and warehouse, seed 42, READ COMMITTED,
retry limit 3, durable local WAL, loopback TCP/TLS, GraalVM 25.0.4 JVM with
`-Xmx1g`, 10-second warmup and 30-second measurement. All four artifacts were
eligible with comparison key
`51420951377fa60dc58b255012cbb5b7d9c9a51a83f377137da80a61803f7bf5`,
passed validation and owned cleanup, with zero retries, failed outcomes and
unknown commits. Some runs cancelled one in-flight attempt at the measurement
cutoff; those attempts were excluded from committed TPS.

| Order | River engine | Committed TPS | p99 (ms) | Immutable artifact |
| --- | --- | ---: | ---: | --- |
| A1 | `db8b3146` control | 1,288.116 | 0.884 | `/private/tmp/river-harness-stock-root/runs/river_harness_20260928_020328_d0795856` |
| B1 | `40470245` root filter | 1,984.908 | 0.616 | `/private/tmp/river-harness-stock-root/runs/river_harness_20260928_020419_8fe96df6` |
| B2 | `40470245` root filter | 1,957.795 | 0.618 | `/private/tmp/river-harness-stock-root/runs/river_harness_20260928_020507_3d0d57eb` |
| A2 | `db8b3146` control | 1,263.782 | 0.937 | `/private/tmp/river-harness-stock-root/runs/river_harness_20260928_020556_db284cf2` |

The mean candidate/control throughput ratio was 1.545. Slopmark's scores for
`SqlBooleanPredicateEvaluator`, `SqlJoinChainSource` and
`SqlUniversalJoinSource` moved from 90.29/59.87/53.43 to 94.62/65.10/57.72;
the added filter owner scored 14.75. No benchmark semantics entered the
engine. The root-filter mechanism is accepted, but the Stock Level gap to
MariaDB remains open. A separate 10-second MariaDB diagnostic using the prior
catalogue reached 9,311.55 TPS; it is not a paired comparison with this
checkpoint.

## 2026-09-28 — Numeric JOIN text-materialization checkpoint

The descriptor JOIN row used to copy and decode every `VARCHAR` column even
when a query referenced only numeric columns. On feature branch
`feature/stock-join-text-pruning`, commit `e56da68c`, the bound JOIN block's
projection, `WHERE` and `ON` programs prove whether any text column is read.
Blocks with nested subqueries, ordering, grouping or an incomplete proof retain
full materialization. Only blocks with no text references omit the text copies.
The full row remains in the storage result buffer, and joins that project or
filter text continue to decode it. Focused tests covered text in projections,
`WHERE` and `ON`, plus mixed descriptor and nested JOIN paths. A clean
`./gradlew --no-daemon clean test` passed in 3m10s (116 tasks).

Interleaved root-filter control–text-prune candidate–candidate–control runs
used harness `df66a3a`, sample Stock Level, one worker and warehouse, seed 42,
READ COMMITTED, retry limit 3, durable local WAL, loopback TCP/TLS, GraalVM
25.0.4 JVM `-Xmx1g`, 10-second warmup and 30-second measurement. All four
artifacts were eligible under comparison key
`51420951377fa60dc58b255012cbb5b7d9c9a51a83f377137da80a61803f7bf5`
and passed invariants and owned cleanup, with zero retries, failed outcomes
and unknown commits. The only binary difference was the engine JAR.

| Order | River engine | Committed TPS | p99 (ms) | Immutable artifact |
| --- | --- | ---: | ---: | --- |
| A1 | `5a92fceb` root-filter control | 1,970.161 | 0.614 | `/Users/blater/src/ingres/river-harness/runs/river_harness_20260928_021824_c75589b9` |
| B1 | `e56da68c` text prune | 2,144.462 | 0.590 | `/Users/blater/src/ingres/river-harness/runs/river_harness_20260928_021911_97e50daa` |
| B2 | `e56da68c` text prune | 2,062.893 | 0.598 | `/Users/blater/src/ingres/river-harness/runs/river_harness_20260928_022000_32b6ff1c` |
| A2 | `5a92fceb` root-filter control | 1,919.327 | 0.628 | `/Users/blater/src/ingres/river-harness/runs/river_harness_20260928_022047_b2f66a83` |

The mean candidate/control throughput ratio was 1.082. Slopmark scores for
the touched existing production classes increased by at most 1.87 points;
the new text-usage proof owner scored 29.29. Decision: accept this generic
text-copy reduction as an incremental JOIN improvement. Ticket `tic-72e5`
remains open because Stock Level remains slower than MariaDB.

The subsequent MariaDB–River–River–MariaDB comparison used the same harness
catalogue and workload settings, with the integrated River source at
`16736705`. All four artifacts were eligible under the same comparison key
above, passed invariants and owned cleanup, and recorded zero retries, failed
outcomes or unknown commits. River used TCP/TLS and MariaDB used its harness
Unix socket, so the ratio describes whole targets with different transports.

| Order | Target | Committed TPS | p99 (ms) | Immutable artifact |
| --- | --- | ---: | ---: | --- |
| M1 | MariaDB | 5,855.391 | 0.198 | `/Users/blater/src/ingres/river-harness/runs/river_harness_20260928_022305_92bdfe71` |
| R1 | River | 2,065.196 | 0.599 | `/Users/blater/src/ingres/river-harness/runs/river_harness_20260928_022354_1174e772` |
| R2 | River | 2,186.190 | 0.594 | `/Users/blater/src/ingres/river-harness/runs/river_harness_20260928_022443_f3f6d6bd` |
| M2 | MariaDB | 6,079.131 | 0.190 | `/Users/blater/src/ingres/river-harness/runs/river_harness_20260928_022530_2e3d9e04` |

The mean MariaDB/River throughput ratio was 2.807. A temporary diagnostic
build timed 4,001 steady-state Stock Level calls per target. River averaged
65.5 µs for the district read and 275.4 µs for the count query; MariaDB
averaged 19.3 and 42.2 µs respectively. The MariaDB timing window fell near
the warmup/measurement boundary, so these per-statement figures identify the
large remaining count-query cost but are not a precise paired latency claim.

A subsequent local probe `851090a8` replaced `HeapRowResult.copyTo`'s byte loop
with an allocation-free bulk `ByteBuffer` copy. Focused heap tests passed, but
the 2-second warmup/10-second measured control–probe–probe–control sequence
did not establish a Stock Level gain: 2,094.97, 2,007.46, 2,068.78 and
1,939.98 TPS. All four runs passed; artifacts are respectively
`river_harness_20260928_023053_fe96bbb7`,
`river_harness_20260928_023112_c0d09af6`,
`river_harness_20260928_023132_906692c2` and
`river_harness_20260928_023153_f693ba26` under the harness `runs` directory.
The probe remains unmerged and is not a new baseline.

## 2026-09-28 — inline DISTINCT checkpoint

Ticket [`tic-72e5`](tickets/tic-72e5.md) remains open. Target-local query
timing after text pruning put River's Stock Level count query near 275 µs,
versus about 65 µs for its district read. With an invalid zero-threshold
diagnostic that still scanned 100 stock rows but made no JOIN probes, the
count query took about 211 µs. These timings were diagnostic, not workload
baselines. Temporary engine timing on the normal workload then found about
30 µs finalizing the small `COUNT(DISTINCT)` set and 25 µs opening a paged
row store for its single output. Instrumentation changed absolute timings;
the stage estimates must not be added to the uninstrumented query time.

On branch `feature/stock-distinct-inline`, commit `209f8b37`, the generic
`COUNT(DISTINCT)` value store keeps up to 16 exact typed values in retained
rows, using its existing comparison semantics. A seventeenth distinct value
spills all values into the existing paged external-order store. There is no
cardinality limit. Focused tests cover decimal and floating-point equality,
text, the spill boundary, large spills and copying a finalized set. The
candidate distribution is at
`/private/tmp/river-stock-evidence/distinct-inline-program/`. Slopmark for
the DISTINCT owner rose from 56.60 to 74.84; review found one owner for the
inline and spilled representations, with no workload-specific SQL policy.

The 5-second warmup/30-second measured River control–candidate–candidate–
control sequence used harness `df66a3a`, sample Stock Level, one worker and
warehouse, seed 42, READ COMMITTED, retry limit 3, durable local WAL,
loopback TCP/TLS, and GraalVM 25.0.4 JVM `-Xmx1g`. The only binary
difference was the engine JAR. All four runs were eligible under comparison
key `92304f6559add6ca75ccead01a5dbc118982216b805a8f00f784346d5af797a2`,
passed invariants and owned cleanup, and recorded zero retries, failed
outcomes or unknown commits.

| Order | River engine | Committed TPS | p99 (ms) | Immutable artifact |
| --- | --- | ---: | ---: | --- |
| A1 | `f44e6948` control | 2,102.037 | 0.606 | `/Users/blater/src/ingres/river-harness/runs/river_harness_20260928_025743_8a19ca06` |
| B1 | `209f8b37` inline DISTINCT | 3,744.786 | 0.380 | `/Users/blater/src/ingres/river-harness/runs/river_harness_20260928_025825_b5406ae2` |
| B2 | `209f8b37` inline DISTINCT | 3,817.486 | 0.354 | `/Users/blater/src/ingres/river-harness/runs/river_harness_20260928_025909_cd482460` |
| A2 | `f44e6948` control | 2,126.206 | 0.591 | `/Users/blater/src/ingres/river-harness/runs/river_harness_20260928_025951_c020e6c9` |

The mean candidate/control TPS ratio was 1.789. An earlier 2-second
warmup/10-second measured A–B–B–A sequence was also directional:
1,996.361/3,253.549/3,264.736/1,924.953 TPS. The short candidate JAR
preceded the final retained-value clearing change; the 30-second artifacts
above use the committed source.

A fresh MariaDB–River–River–MariaDB sequence used the same 5-second warmup,
30-second measured workload and comparison key. Every run passed invariants
and cleanup with zero retries, failed outcomes or unknown commits. River
used TCP/TLS; MariaDB used the harness-owned Unix socket.

| Order | Target | Committed TPS | p99 (ms) | Immutable artifact |
| --- | --- | ---: | ---: | --- |
| M1 | MariaDB | 6,612.378 | 0.205 | `/Users/blater/src/ingres/river-harness/runs/river_harness_20260928_030047_442234e6` |
| R1 | River `209f8b37` | 3,705.049 | 0.402 | `/Users/blater/src/ingres/river-harness/runs/river_harness_20260928_030134_5552506d` |
| R2 | River `209f8b37` | 3,622.254 | 0.371 | `/Users/blater/src/ingres/river-harness/runs/river_harness_20260928_030219_c91e101d` |
| M2 | MariaDB | 6,352.537 | 0.221 | `/Users/blater/src/ingres/river-harness/runs/river_harness_20260928_030301_1e876091` |

The mean MariaDB/River TPS ratio was 1.769 for these whole targets. The
remaining measured server costs include opening the one-row scalar result
store and starting the JOIN scan. They are the next generic mechanisms to
investigate. The final clean `./gradlew --no-daemon clean test` passed in
3m15s (116 tasks). Decision: accept the exact inline DISTINCT mechanism as
an incremental improvement; the ticket stays open.

## 2026-09-28 — single-row store checkpoint

The scalar COUNT result used to open paged row and index streams before
writing its only output row. On branch `feature/stock-singleton-row-store`,
commit `1bf08325`, the generic block row store retains its first row in a
budgeted reusable row. A second row moves both rows to the existing paged
store. The first row still passes the normal row and sort-key encoders for
validation. Empty and single sorted outputs, text and public keys, stable
ordering after migration, large paged results, and the DISTINCT spill path
passed focused tests. A clean `./gradlew --no-daemon clean test` passed in
3m9s (116 tasks). The candidate distribution is retained at
`/private/tmp/river-stock-evidence/singleton-program/`.

Interleaved control–candidate–candidate–control River runs used harness
`df66a3a`, sample Stock Level, one worker and warehouse, seed 42, READ
COMMITTED, retry limit 3, durable local WAL, loopback TCP/TLS, GraalVM
25.0.4 JVM `-Xmx1g`, 5-second warmup and 30-second measurement. The only
binary difference was the engine JAR. All four runs were eligible under
comparison key `92304f6559add6ca75ccead01a5dbc118982216b805a8f00f784346d5af797a2`,
passed invariants and owned cleanup, and had zero retries, failed outcomes
or unknown commits.

| Order | River engine | Committed TPS | p99 (ms) | Immutable artifact |
| --- | --- | ---: | ---: | --- |
| A1 | `7c5ecf33` control | 3,918.213 | 0.303 | `/Users/blater/src/ingres/river-harness/runs/river_harness_20260928_031743_7f4149bc` |
| B1 | `1bf08325` single row | 4,376.012 | 0.303 | `/Users/blater/src/ingres/river-harness/runs/river_harness_20260928_031825_e45e57c8` |
| B2 | `1bf08325` single row | 4,424.739 | 0.273 | `/Users/blater/src/ingres/river-harness/runs/river_harness_20260928_031909_32bac37c` |
| A2 | `7c5ecf33` control | 3,743.280 | 0.340 | `/Users/blater/src/ingres/river-harness/runs/river_harness_20260928_031951_f26c1ed9` |

The mean candidate/control TPS ratio was 1.149. Earlier 2-second
warmup/10-second measured samples were also directional:
3,619.55/3,991.94/4,018.74/3,454.76 TPS. Slopmark for the row-store
owner rose from 65.73 to 83.18. Review found the bounded inline and paged
representations within the same storage owner, with no second result
encoder or benchmark-specific branch.

A fresh MariaDB–River–River–MariaDB sequence used the same 5-second warmup,
30-second measured workload and comparison key. Every run passed invariants
and cleanup with zero retries, failed outcomes or unknown commits. River
used TCP/TLS; MariaDB used the harness-owned Unix socket.

| Order | Target | Committed TPS | p99 (ms) | Immutable artifact |
| --- | --- | ---: | ---: | --- |
| M1 | MariaDB | 6,821.345 | 0.185 | `/Users/blater/src/ingres/river-harness/runs/river_harness_20260928_032052_a0cdc7e4` |
| R1 | River `1bf08325` | 4,429.242 | 0.302 | `/Users/blater/src/ingres/river-harness/runs/river_harness_20260928_032135_1b9f5d72` |
| R2 | River `1bf08325` | 4,228.680 | 0.294 | `/Users/blater/src/ingres/river-harness/runs/river_harness_20260928_032218_9c04e259` |
| M2 | MariaDB | 6,504.864 | 0.196 | `/Users/blater/src/ingres/river-harness/runs/river_harness_20260928_032300_0280f7c0` |

The mean MariaDB/River TPS ratio was 1.539 for these whole targets.
Decision: accept the generic singleton row-store path; the Stock Level
ticket remains open for JOIN startup, stock scan and prepared-query costs.

A later isolated probe removed repeated domain and UTF-8 checks while
publishing stored rows after full body validation. It passed focused codec
tests, but a 2-second warmup/10-second measured A–B–B–A sequence was
inconclusive: 4,045.94, 4,190.84, 4,163.04 and 4,538.04 TPS. The four
passing artifacts are `river_harness_20260928_033515_ea8d3e34`,
`river_harness_20260928_033534_4489ce84`,
`river_harness_20260928_033556_9e1ab7f7` and
`river_harness_20260928_033620_76986eb1` under the harness `runs`
directory. The probe remains unmerged; it is not a new baseline.

## 2026-09-28 — Validated root row filter checkpoint

Branch `feature/stock-validated-root-filter`, source commit `e029efdc`,
ticket `tic-72e5`. A mandatory root-local integer comparison is now tested
after full stored-row validation and before publication into SQL value lanes.
The filter is compiled from the existing root `WHERE` leaves and applies only
to direct signed integer column/literal comparisons. Surviving rows still
pass through the canonical SQL three-valued predicate evaluator. The scan
skips rejected rows internally; it does not report them as end of scan.
Persisted-row corruption is reported even when the row fails the filter.

Focused row-codec, JOIN and plan-counter tests passed, including reversed
comparisons, `AND`, `OR`, and validation before rejection. The expected
`EXPLAIN ANALYZE` root count changed from two published candidates to one.
A clean full `./gradlew --no-daemon clean test` passed in 2m53s (116 tasks).
The candidate distribution is retained at
`/private/tmp/river-stock-evidence/validated-filter-program/`.

The 2-second warmup/10-second measured A–B–B–A diagnostic returned
4,054.030 / 4,526.338 / 4,542.740 / 4,049.040 TPS, all passed, with
artifacts `river_harness_20260928_034912_0aa43a25`,
`river_harness_20260928_034933_136cdb9e`,
`river_harness_20260928_034952_c6b49c4e`, and
`river_harness_20260928_035012_da41a791`.

The longer interleaved River runs used harness `df66a3a`, sample Stock
Level, one worker and warehouse, seed 42, READ COMMITTED, retry limit 3,
durable local WAL, loopback TCP/TLS, GraalVM 25.0.4 JVM `-Xmx1g`,
5-second warmup and 30-second measurement. The only binary difference was
the engine JAR. All runs passed invariants and cleanup, with zero retries,
failed outcomes or unknown commits. The four artifacts were eligible with
identical comparison key
`92304f6559add6ca75ccead01a5dbc118982216b805a8f00f784346d5af797a2`.

| Order | River engine | Committed TPS | p99 (ms) | Immutable artifact |
| --- | --- | ---: | ---: | --- |
| A1 | `4b5004fc` control | 4,576.140 | 0.258 | `/Users/blater/src/ingres/river-harness/runs/river_harness_20260928_035733_fd2fd966` |
| B1 | `e029efdc` validated filter | 5,029.350 | 0.265 | `/Users/blater/src/ingres/river-harness/runs/river_harness_20260928_035817_ca6ea11f` |
| B2 | `e029efdc` validated filter | 5,018.188 | 0.267 | `/Users/blater/src/ingres/river-harness/runs/river_harness_20260928_035900_d016abce` |
| A2 | `4b5004fc` control | 4,449.000 | 0.303 | `/Users/blater/src/ingres/river-harness/runs/river_harness_20260928_035944_cd31b7fb` |

Mean candidate/control committed TPS was 1.113. The subsequent
MariaDB–River–River–MariaDB pair used the same manifest and comparison key.
MariaDB used its harness-owned Unix socket; River used TCP/TLS. All four
passed invariants and cleanup with zero retries, failures or unknown commits.

| Order | Target | Committed TPS | p99 (ms) | Immutable artifact |
| --- | --- | ---: | ---: | --- |
| M1 | MariaDB | 6,816.334 | 0.182 | `/Users/blater/src/ingres/river-harness/runs/river_harness_20260928_040030_908a2ed2` |
| R1 | River `e029efdc` | 4,888.125 | 0.302 | `/Users/blater/src/ingres/river-harness/runs/river_harness_20260928_040114_1d52b835` |
| R2 | River `e029efdc` | 4,752.530 | 0.313 | `/Users/blater/src/ingres/river-harness/runs/river_harness_20260928_040158_a3b40139` |
| M2 | MariaDB | 6,583.543 | 0.186 | `/Users/blater/src/ingres/river-harness/runs/river_harness_20260928_040240_3d869c6d` |

Mean MariaDB/River committed TPS was 1.390. The generic filter is accepted;
the Stock Level ticket remains open. Next work should measure the remaining
count-query, district-query and protocol time on this accepted build, then
target the largest verified component.

## 2026-09-28 — Stock Level read program checkpoint

River `e029efdc` (integrated at `2d21b5e5`) and harness
`4c16840` (integrated at `dae4786`). The harness binding now uses the
existing generic River transaction program for Stock Level. It reads
`district.d_next_o_id`, computes `max(1, nextOrder - 20)` from that result,
executes the unchanged `COUNT(DISTINCT)` JOIN SQL and commits in one request.
The engine has no Stock Level-specific behavior. The binding checks both
scalar result shapes, the positive next order ID, nonnegative count,
commit status and uncertain outcomes. The SQL catalogue remains the single
source of both query texts.

A temporary, uncommitted phase-timing build of the standalone harness ran
the accepted SQL binding for 5 seconds of warmup and 10 seconds of
measurement, one worker and warehouse. It timed about 68,503 River and
132,249 MariaDB Stock Level calls across both windows. Mean phase times
in microseconds were:

| Phase | River | MariaDB |
| --- | ---: | ---: |
| Whole call | 218.342 | 112.682 |
| Begin | 34.102 | 22.990 |
| District query | 45.766 | 19.405 |
| Count query | 101.428 | 56.438 |
| Finish | 36.759 | 13.599 |

These are diagnostic, not a matched performance claim: the runs were
sequential, modified and without report artifacts. The count query was the
largest single phase, while begin and finish together cost 70.861 µs in
River. A separate diagnostic build compared the program's returned next
order ID and count with the original two SQL reads on every call during a
short static Stock Level run; no mismatch occurred. That diagnostic was
restored before measured program runs.

The same River JVM binary and workload were used for the SQL-path and
program-path A–B–B–A comparison. The 2-second warmup/10-second measured
samples were 4,684.397 / 10,064.755 / 10,053.068 / 4,552.887 committed
TPS. All were eligible under comparison key
`ae608dcd41da1a792877777c58542658174cbd0a3fbd0376f2a65c87568a5ed9`,
passed validation and cleanup, and had zero retries, failures or unknown
commits. The corresponding artifacts are
`/Users/blater/src/ingres/river-harness/runs/river_harness_20260928_041228_e0e8e854`,
`/private/tmp/river-harness-stock-program/runs/river_harness_20260928_041247_2d1d40ef`,
`/private/tmp/river-harness-stock-program/runs/river_harness_20260928_041308_cd33296b`,
and `/Users/blater/src/ingres/river-harness/runs/river_harness_20260928_041328_12f6f31a`.

The 5-second warmup/30-second measured A–B–B–A sequence used sample
Stock Level, one worker and warehouse, seed 42, READ COMMITTED, retry
limit 3, durable local WAL, loopback TCP/TLS and GraalVM 25.0.4 JVM
`-Xmx1g`. All four runs passed invariants and cleanup with zero retries,
failed outcomes or unknown commits and identical eligible comparison key
`92304f6559add6ca75ccead01a5dbc118982216b805a8f00f784346d5af797a2`.

| Order | Binding | Committed TPS | p99 (ms) | Immutable artifact |
| --- | --- | ---: | ---: | --- |
| A1 | two SQL reads | 5,094.360 | 0.246 | `/Users/blater/src/ingres/river-harness/runs/river_harness_20260928_041457_cd8bd7f8` |
| B1 | one program request | 11,051.450 | 0.122 | `/private/tmp/river-harness-stock-program/runs/river_harness_20260928_041540_0aa5153a` |
| B2 | one program request | 10,338.810 | 0.129 | `/private/tmp/river-harness-stock-program/runs/river_harness_20260928_041624_6bdbb5df` |
| A2 | two SQL reads | 5,119.290 | 0.240 | `/Users/blater/src/ingres/river-harness/runs/river_harness_20260928_041706_2cdb16ab` |

Mean program/SQL throughput was 2.094. The final harness source then
extracted result validation into a tested helper without changing the
program graph or SQL. `go test ./...`, `go test -race ./...`, `go vet ./...`
and `make build` passed. The final local harness binary SHA-256 is
`63162606ae3c903be09643c1ac6054e82bb078ab4204548afce79585d295669d`.

A final MariaDB–River–River–MariaDB sequence used that exact harness binary
and the same 5-second warmup/30-second workload. MariaDB used its
harness-owned Unix socket and River used TCP/TLS. All four runs were
eligible under comparison key
`92304f6559add6ca75ccead01a5dbc118982216b805a8f00f784346d5af797a2`,
passed invariants and cleanup with zero retries, failures or unknown
commits. All four manifest checksums reconciled; MariaDB reported a
graceful `mariadb-admin` shutdown and inactive service state afterward.

| Order | Target | Committed TPS | p99 (ms) | Immutable artifact |
| --- | --- | ---: | ---: | --- |
| M1 | MariaDB | 6,765.672 | 0.184 | `/private/tmp/river-harness-stock-program/runs/river_harness_20260928_042401_f1372029` |
| R1 | River | 10,440.427 | 0.126 | `/private/tmp/river-harness-stock-program/runs/river_harness_20260928_042446_d625f81b` |
| R2 | River | 10,328.801 | 0.130 | `/private/tmp/river-harness-stock-program/runs/river_harness_20260928_042529_a09ebfaa` |
| M2 | MariaDB | 6,818.415 | 0.182 | `/private/tmp/river-harness-stock-program/runs/river_harness_20260928_042612_5c530cf8` |

Mean River/MariaDB committed TPS was 1.529 for this one-worker workload.
The measured whole-transaction Stock Level gap is closed. The separate
count-query diagnostic still shows a 101.428 versus 56.438 µs difference
on the SQL path, so JOIN execution remains a candidate for further generic
optimization; the program result is not evidence of JOIN parity.

An adjacent, temporary SQL-only diagnostic then isolated the count-query
components on the accepted River binary. The unmodified 5-second
warmup/10-second control averaged 101.898 µs per count query. Adding an
impossible `s_quantity < 0` conjunct kept the stock scan but removed JOIN
probes and averaged 84.827 µs. An impossible `s_w_id = 0` conjunct removed
the stock rows and averaged 62.902 µs. The restored unmodified control
averaged 102.374 µs. The altered queries returned a different count, were
run with `--no-report`, and are not workload baselines. The adjacent
differences suggest about 17 µs for JOIN probes and 22 µs for stock-row
scanning in this setup; the roughly 63 µs empty-root query path is the
larger next target. Earlier temporary engine timing attributed about
16 µs to repeated block binding within that fixed cost. These components
are diagnostic estimates from sequential runs, not additive server
accounting or a MariaDB comparison. The diagnostic SQL was restored.

## 2026-09-28 — Stock Level full-cardinality JOIN-order diagnostic

The accepted one-request Stock Level program was tested with the `full`
profile, one warehouse, one worker, seed 42, retry limit 3, one-second
warmup and three-second measured window. Its load contains 598,847 rows,
including 100,000 stock rows. These short `--no-report` runs passed workload
validation but are diagnostics, not designated baselines or comparable
immutable artifacts.

| Target and query order | Committed TPS | p99 (ms) |
| --- | ---: | ---: |
| River, stock first, one-request program | 25.31 | 54.034 |
| MariaDB, stock first | 4,863.22 | 0.231 |
| River, order-line first, temporary two-read SQL path | 903.23 | 1.427 |

The temporary SQL reversal changed only the two inner JOIN inputs and kept
the predicates, but used two SQL reads because the River one-request program
encodes the original SQL. A sample-profile smoke of the reversed SQL reached
1,141.58 TPS, substantially below the accepted sample one-request baseline.
Thus source order must be selected by access cost, not reversed universally.
The full-profile result isolates a severe plan-order cost; the precise
remaining River/MariaDB difference needs a matched request path and longer
interleaved evidence.

Temporary server timers on the accepted sample path placed approximately
24.5 microseconds in root cursor advancement and 12.7 microseconds in inner
cursor opening per count query. The measurements are diagnostic and were
collected sequentially. A prepared JOIN table-resolution cache reduced its
isolated binding step from about 6.25 to 0.63 microseconds, but matched
30-second A–B–B–A whole-workload runs averaged 10,358.327 control and
10,406.454 candidate TPS, a 0.46% difference within adjacent variation.
Both variants passed validation and cleanup with zero retries, failures or
unknown outcomes and identical eligible comparison key
`92304f6559add6ca75ccead01a5dbc118982216b805a8f00f784346d5af797a2`.
The artifacts, in A–B–B–A order, are
`/Users/blater/src/ingres/river-harness/runs/river_harness_20260928_045045_374e951b`,
`/Users/blater/src/ingres/river-harness/runs/river_harness_20260928_045128_bb6988d0`,
`/Users/blater/src/ingres/river-harness/runs/river_harness_20260928_045210_a10b16ca`,
and `/Users/blater/src/ingres/river-harness/runs/river_harness_20260928_045254_083e0885`.
An ASCII UTF-8 validation fast path likewise did not show a stable
whole-workload improvement in short adjacent samples. Neither candidate was
promoted. The next engine change is cardinality-aware ordering for eligible
inner JOIN roles, followed by measurement of indexed inner probes and row
scanning on the full profile.

## 2026-09-28 — Full Stock Level costed inner JOIN order

River `34ee0800` chooses the lower estimated access cost for eligible
two-relation inner JOINs with analyzed tables. It accounts for the constrained
prefix of a compound primary key, root rows visited, and estimated JOIN
probes. The harness `7c4b90d` runs `ANALYZE TABLE stock` and `ANALYZE TABLE
order_line` after validating the River full-profile load. The Stock Level SQL
and one-request program are unchanged. A clean `./gradlew --no-daemon clean
test`, focused planner tests, `go test ./...`, and `go vet ./...` passed.

The two River builds came from `master` `971305e1` and feature `34ee0800`.
Both used the same harness commit, GraalVM 25.0.4 JVM with `-Xmx1g`,
macOS/arm64, READ COMMITTED, durable local WAL, one worker and warehouse,
seed 42, retry limit 3, and the `full stock-level` workload. The commands
changed only `--river-executable` and `--river-version` between builds:

```sh
./benchmark run river tpcc full stock-level \
  --river-executable=BUILD/river --river-version=BUILD-LABEL \
  --warmup=1s --duration=3s --workers=1 --warehouses=1 \
  --seed=42 --max-retries=3
```

| Short order | Build | Committed TPS | p99 (ms) | Immutable artifact |
| --- | --- | ---: | ---: | --- |
| A1 | master | 19.318 | 89.194 | `river_harness_20260928_063308_897c0511` |
| B1 | feature | 1,010.026 | 1.621 | `river_harness_20260928_065139_0cc5fb26` |
| B2 | feature | 1,018.242 | 1.309 | `river_harness_20260928_065313_b4933876` |
| A2 | master | 19.653 | 76.087 | `river_harness_20260928_065444_84ad8432` |

The longer sequence used `--warmup=5s --duration=30s` with the other
options unchanged:

| Long order | Build | Committed TPS | p99 (ms) | Immutable artifact |
| --- | --- | ---: | ---: | --- |
| A1 | master | 23.866 | 61.800 | `river_harness_20260928_065623_12323ec2` |
| B1 | feature | 1,140.290 | 1.056 | `river_harness_20260928_065833_38d8ff71` |
| B2 | feature | 1,131.222 | 1.109 | `river_harness_20260928_070044_7e89c8f0` |
| A2 | master | 23.598 | 62.521 | `river_harness_20260928_070257_047f0667` |

These artifacts are under `/private/tmp/river-harness-stock-analyze/runs/`.
Each sequence had an identical eligible comparison key within its four
runs: `3d606a7f865c6ab38afd83034a3cc80f9477a9c89a07d1b5041319e82d45e585`
for short and `1233ecf3b5d1481602a7daaef90e8d09db95fa8853cd6657579ce827583e835f`
for long. Every run passed full-load and post-run invariants with zero
retries, failed or unknown outcomes. Mean long-run throughput rose from
23.732 to 1,135.756 TPS, a 47.86-fold change for this workload.

A provisional estimator at River `29a9df94` chose stock first because it
costed only the first column of `order_line`'s compound primary key. Its
reported run `river_harness_20260928_063444_69363461` reached 24.65 TPS.
The compound-key correction and focused regression test are in `34ee0800`;
the provisional run is excluded from the accepted feature comparison.
`slopmark` flagged the provisional root-cost file at 104.105. Splitting
filter selectivity, compound-prefix costing, and root costing brought the
three touched files to 13.489, 19.854, and 12.144 respectively; the tool
reported shallow boundary coverage.

One matched MariaDB full-profile control with 5 seconds warmup and 30
seconds measured reached 4,848.308 TPS, p99 0.231 ms, artifact
`river_harness_20260928_070520_953426cb`. It has the same eligible long-run
comparison key, passed invariants, and had zero retries, failures or unknown
outcomes. River's two-run mean remains 4.27 times lower; this one MariaDB
control is diagnostic, not an audited cross-database performance claim.

Decision: accept generic costed inner JOIN ordering as a recoverable feature
checkpoint. Keep `tic-72e5` open. The next mechanism to test is an exact
primary-key inner probe using River's existing point fetch path while
preserving source locks and visibility. The current JOIN path opens and
closes an indexed scan for each matching outer row; the remaining cost must
be measured against the accepted feature before attribution.

## 2026-09-28 — Exact primary-key JOIN point-fetch diagnostic

Feature branch `feature/exact-primary-join-probe`, River `b21f8461`, used
River's existing exact primary-key fetch for eligible JOIN probes outside
SERIALIZABLE transactions. SERIALIZABLE retained the scan path. A focused
test covered missing keys, pending insert/update/delete, rollback, a
SERIALIZABLE query and early cursor close; `./gradlew --no-daemon clean test`
passed. A JFR diagnostic at `/private/tmp/river-stock-point-profile.jfr`
sampled the new `fetchPoint` path, but B-tree child lookup, tuple-key
comparison and base-row access remained the leading connection CPU samples.

The standalone harness ran `full stock-level`, one worker and warehouse,
seed 42, READ COMMITTED, retry limit 3, durable local WAL, GraalVM 25.0.4
JVM `-Xmx1g`, 5 seconds warmup and 30 seconds measured. Only the River
executable/version changed. The two builds were accepted `34ee0800` and
point candidate `b21f8461`; harness source was `7c4b90d`. All four runs
were eligible under comparison key
`1233ecf3b5d1481602a7daaef90e8d09db95fa8853cd6657579ce827583e835f`,
passed full-load and post-run invariants, and had zero retries, failed or
unknown outcomes. Artifacts are under
`/private/tmp/river-harness-stock-analyze/runs/`.

| Order | Build | Committed TPS | p99 (ms) | Immutable artifact |
| --- | --- | ---: | ---: | --- |
| A1 | accepted JOIN order | 1,218.767 | 0.972 | `river_harness_20260928_073255_0912150a` |
| B1 | point fetch | 1,213.001 | 1.064 | `river_harness_20260928_073500_ca1e1c68` |
| B2 | point fetch | 1,215.667 | 1.024 | `river_harness_20260928_073712_b09ab778` |
| A2 | accepted JOIN order | 1,132.165 | 1.128 | `river_harness_20260928_073929_3ee8399a` |

Candidate mean was 1,214.334 TPS and control mean 1,175.466 TPS, but the
two controls differed by 86.602 TPS. The short adjacent sequence also moved
within that range of host variation. Decision: do not merge the point-fetch
feature on this evidence. Repeated cursor setup alone has not established a
material gain; isolate tuple-key traversal, base-row lookup and wide-row
decode costs before the next production optimization.

## 2026-09-28 — Full Stock Level JOIN phase timing

Temporary timing counters on a detached worktree at accepted integration
`9221d802` measured the unchanged full-profile Stock Level JOIN. The
diagnostic code is under `/private/tmp/river-join-timing-diagnostic`, and its
shutdown counters are at `/private/tmp/river-join-timing-index.txt` (written
2026-09-28 09:04:56 BST). The harness was `7c4b90d`, version label
`diagnostic-join-timing-index`. Command:

```sh
./benchmark run river tpcc full stock-level \
  --river-executable=/private/tmp/river-join-timing-candidate/river \
  --river-version=diagnostic-join-timing-index \
  --warmup=2s --duration=10s --workers=1 --warehouses=1 \
  --seed=42 --max-retries=3 --no-report
```

The GraalVM 25.0.4 JVM used `-Xmx1g`, READ COMMITTED, and durable local WAL.
The run passed with 9,117 commits, 911.67 TPS, p99 1.653 ms, successful
post-run validation, and owned-server cleanup. Counters include warmup and
measured workload, so their totals must not be divided by measured commits.
They added per-call timing overhead and are diagnostic rather than a baseline.

| Timed operation | Calls | Total time (s) | Mean (µs/call) |
| --- | ---: | ---: | ---: |
| `order_line` root next | 2,355,372 | 1.947 | 0.826 |
| `stock` inner open | 2,344,950 | 3.692 | 1.574 |
| `stock` inner admission within open | 2,344,950 | 0.324 | 0.138 |
| `stock` inner index begin within open | 2,344,950 | 3.287 | 1.401 |
| Versioned index-root reload within begin | 2,344,950 | 0.938 | 0.400 |
| Tuple-index cursor open within begin | 2,344,950 | 1.475 | 0.629 |
| `stock` base-row lookup | 2,344,950 | 0.942 | 0.402 |
| `stock` base-row decode | 2,344,950 | 0.912 | 0.389 |
| `stock` inner close | 2,376,216 | 0.173 | 0.072 |

The inner index begin and base-row read dominate the timed JOIN path. The
accepted code reloads a versioned index-root record and opens an index cursor
for each inner key, then fetches and decodes the base row. The point-fetch
candidate still traversed the index to find the logical row ID before fetching
the base row, explaining why changing the cursor API alone did not remove the
dominant work. The following checkpoint tests caching the validated root record
across probes with the same visible snapshot and key, excluding private index
builds. After that, the remaining index traversal and base-row lookup motivate
a projected unique-key lookup.
This diagnostic does not establish that one feature alone will close the full
MariaDB gap.

## 2026-09-28 — Cache versioned index roots across JOIN probes

River `b17e0450` reuses a validated tuple-index root record when a reopened
scan requests the same key at the same visible commit sequence. It reloads on
snapshot or key change and never caches private index builds or direct prefix
probes. The focused JOIN test checks an indexed inner lookup, READ COMMITTED
visibility across statements, and REPEATABLE READ visibility. The final clean
`./gradlew --no-daemon clean test` passed. Independent review found no
production visibility defect; it requested a mechanism check, which a separate
temporary counter build supplied: 3,261,119 cache hits, 69 cacheable misses,
and 1,497,531 intentionally uncached calls in one passed full-profile run.
Those counters are at `/private/tmp/river-index-root-cache-hits.txt` and are
absent from `b17e0450`. `slopmark` scored the touched root-snapshot file
31.160 before and 33.785 after; the other two touched files remained at
28.774 and 17.498. It reported shallow boundary coverage.

The River builds were accepted `34ee0800` and candidate `b17e0450`, from
branch `feature/index-root-snapshot-cache`. The standalone harness source was
`eba8ab0` (workload behavior introduced at `7c4b90d`). All runs used the
unchanged `full stock-level` SQL, GraalVM 25.0.4 JVM `-Xmx1g`, macOS/arm64,
READ COMMITTED, durable local WAL, one worker and warehouse, seed 42, and
retry limit 3. Only executable/version changed between variants. Short
commands used 1 second warmup and 3 seconds measured; long commands used
5 seconds warmup and 30 seconds measured:

```sh
./benchmark run river tpcc full stock-level \
  --river-executable=BUILD/river --river-version=BUILD-LABEL \
  --warmup=5s --duration=30s --workers=1 --warehouses=1 \
  --seed=42 --max-retries=3
```

| Order | Window | Build | Committed TPS | p99 (ms) | Immutable artifact |
| --- | --- | --- | ---: | ---: | --- |
| A1 | short | control | 1,015.232 | 1.361 | `river_harness_20260928_081032_218194f9` |
| B1 | short | cache | 948.551 | 3.201 | `river_harness_20260928_081205_bfd4fd9b` |
| B2 | short | cache | 816.179 | 2.308 | `river_harness_20260928_081341_29bd9b92` |
| A2 | short | control | 736.246 | 2.404 | `river_harness_20260928_081522_36840536` |
| B1 | long | cache | 1,203.377 | 1.570 | `river_harness_20260928_081738_da30e565` |
| A1 | long | control | 933.213 | 2.517 | `river_harness_20260928_081943_c560676a` |
| A2 | long | control | 945.819 | 3.813 | `river_harness_20260928_082205_cafb7b33` |
| B2 | long | cache | 1,205.319 | 1.077 | `river_harness_20260928_082446_1cd85efd` |

Artifacts are under `/private/tmp/river-harness-stock-analyze/runs/`.
Every run was eligible, passed full-load and post-run invariants, and had
zero retries, failures, or unknown commits. The short runs shared comparison
key `3d606a7f865c6ab38afd83034a3cc80f9477a9c89a07d1b5041319e82d45e585`
but the two controls differed by 27.5%, so that sequence was inconclusive.
The long runs shared key
`1233ecf3b5d1481602a7daaef90e8d09db95fa8853cd6657579ce827583e835f`;
their controls averaged 939.516 TPS and cache builds averaged 1,204.348 TPS,
a 28.2% increase in this diagnostic window.

One adjacent `sample stock-level` pair used the same 5/30 second window.
Control reached 10,177.150 TPS and candidate 11,129.246 TPS, with eligible
comparison key
`92304f6559add6ca75ccead01a5dbc118982216b805a8f00f784346d5af797a2`;
artifacts are `river_harness_20260928_083309_109a8811` and
`river_harness_20260928_083357_5fdcd476`. Both passed invariants with zero
retries, failures, or unknown commits. This pair checks for an obvious sample
regression; it is not a separate accepted improvement estimate.

An additional clean-build candidate run labelled `b17e0450-jvm-clean`
started at 2026-09-28 08:41:56 UTC and reached 1,334.778 TPS, p99 0.914 ms,
artifact `river_harness_20260928_084035_50a125d8`. Its engine JAR checksum
matched the earlier candidate JAR, and the run had the eligible long-run key,
passed validation, and had zero retries, failures, or unknown commits. It is
the new baseline row above; the long interleaved sequence is the evidence for
the feature decision. Accept this generic cache as a recoverable feature
checkpoint. Keep `tic-72e5` open: repeated tuple cursor descent and base-row
fetch still dominate the inner probe, and the prior MariaDB control remains
substantially faster on full cardinality.

## 2026-09-28 — Foreign-key support index reuse and schema parity

Branch `feature/fk-exact-index-reuse`, commit `fb9c0a0b`, reuses an existing
primary or secondary index when its leading ordered columns support a foreign
key. A later named index can replace a redundant automatic support index in
the same catalog successor. Reference checks use the full physical index key
with the foreign-key prefix, including pending inserts and deletes. This is a
comparison-correctness feature for [`tic-72e5`](tickets/tic-72e5.md), not an
accepted Stock Level speedup. The standalone harness schema and workload were
unchanged.

The clean `./gradlew --no-daemon clean test` build passed in 3m 10s. Focused
tests cover exact primary and secondary reuse, prefix checks with multiple
children and pending deletes, restart, concurrent parent deletion, later named
index replacement, failed unique backfill, and preservation of a user-named
unique constraint. An independent correctness review found two issues in the
first candidate; both were fixed before the clean build. `slopmark` raised
`RelationalDescriptorIndexChange` from 36.992 to 107.91. Its index-add/remove
responsibility remains local, and automatic support replacement policy was
extracted to `RelationalForeignKeyIndexReplacement`; the score was treated as
a review trigger rather than a performance result.

A temporary read-only harness callback inspected River `SHOW INDEXES` and
MariaDB `information_schema.STATISTICS` after loading `sample stock-level`.
The diagnostic logs are `/private/tmp/river-fk-index-inventory.log` and
`/private/tmp/mariadb-index-inventory.log`. Excluding MariaDB's harness
ownership table, both targets had 15 physical indexes with identical table,
uniqueness and ordered-column shapes. Automatically assigned names differ.
The callback was removed and the clean harness `eba8ab0` rebuilt before the
performance runs. This manual inventory check is not yet an automatic
comparison eligibility rule.

The interleaved `full stock-level` run used one worker and warehouse, seed 42,
retry limit 3, READ COMMITTED, durable local WAL, GraalVM 25.0.4 JVM `-Xmx1g`,
5s warmup and 30s measured. The candidate engine JAR checksum
`3881882459d673565f208ce6dd98d2c18c5d4baf07d289b68c6f1271a72a923e`
matched the JAR rebuilt after the clean test. The command was:

```sh
./benchmark run river tpcc full stock-level \
  --river-executable=BUILD/river --river-version=BUILD-LABEL \
  --warmup=5s --duration=30s --workers=1 --warehouses=1 \
  --seed=42 --max-retries=3
```

| Order | Build/version | TPS | p99 (ms) | Artifact under `/private/tmp/river-harness-stock-analyze/runs/` |
| --- | --- | ---: | ---: | --- |
| B1 | `fk-exact-reuse-long-b1` | 1,317.219 | 0.959 | `river_harness_20260928_101648_1d4fc4f9` |
| A1 | `root-cache-long-a1` | 1,186.621 | 1.134 | `river_harness_20260928_101844_58af9c59` |
| A2 | `root-cache-long-a2` | 1,205.187 | 1.084 | `river_harness_20260928_102100_08639445` |
| B2 | `fk-exact-reuse-long-b2` | 1,146.651 | 1.537 | `river_harness_20260928_102315_a022c613` |

All four artifacts passed invariants and cleanup, had zero failures, unknown
commits and retries, and shared eligible comparison key
`1233ecf3b5d1481602a7daaef90e8d09db95fa8853cd6657579ce827583e835f`.
The candidate did not show a repeated directional improvement; no new
performance baseline is designated. Two earlier 1s/3s samples per build were
also inconclusive because one control fell to 441.614 TPS while the other
reached 1,186.223 TPS.

A 2s/10s, four-worker `sample all` candidate run failed with two New Order
deadlock retry exhaustions (`river_harness_20260928_102632_fbad33a5`). The
adjacent control failed with the same count and cause
(`river_harness_20260928_102715_87924b24`), so this is not evidence of a new
feature regression. A one-worker candidate `sample all` run passed every
invariant with zero failures, unknown commits and retries at 553.865 TPS
(`river_harness_20260928_102754_ad56a5ad`). Keep the feature for schema
comparison correctness; keep the Stock Level performance ticket open.

## 2026-09-28 — Borrowed descriptor-row decode diagnostic

An isolated `feature/descriptor-row-borrowed-decode` candidate let the
descriptor row codec read the result-owned heap bytes directly, removing its
second whole-row copy. The kernel still copied committed row bytes before
releasing the page pin, and the codec still validated the complete stored row.
Focused `RelationalDescriptorRowPathTest`, `SqlDescriptorTupleIndexScanTest`
and `HeapPageTest` passed. Candidate engine and storage JAR checksums were
`4daf8f451cdcfe20fcb8411f708f3f2b0371036b173c6b466fd4672b1c26a4bf`
and `959df079b7c92518bfa44127c5f88f088eb3e23614ca1a01da5181792a0a7ab3`.
Only those two JARs differed from the accepted index-parity control.

The unchanged harness `eba8ab0` ran `full stock-level` with one worker and
warehouse, seed 42, retry limit 3, GraalVM 25.0.4 JVM `-Xmx1g`, READ
COMMITTED and durable WAL. Two short 1s/3s samples per build were unstable:
control 1,107.61/691.27 TPS, candidate 1,004.92/811.25 TPS. The longer
interleaved 5s/30s sequence was:

| Order | Build | TPS | p99 (ms) | Artifact under `/private/tmp/river-harness-stock-analyze/runs/` |
| --- | --- | ---: | ---: | --- |
| B1 | borrowed row | 1,185.254 | 1.125 | `river_harness_20260928_104127_411e3b26` |
| A1 | index-parity control | 1,196.252 | 1.097 | `river_harness_20260928_104343_13f3876e` |
| A2 | index-parity control | 1,175.564 | 1.142 | `river_harness_20260928_104554_846a6161` |
| B2 | borrowed row | 1,186.262 | 1.130 | `river_harness_20260928_104810_885c1e26` |

All four were eligible with comparison key
`1233ecf3b5d1481602a7daaef90e8d09db95fa8853cd6657579ce827583e835f`,
passed invariants and cleanup, and recorded zero failures, unknown commits and
retries. Candidate and control means were both about 1,186 TPS. The change
did not establish a Stock Level gain, so its source was reverted and no new
baseline or performance checkpoint tag was created. The next investigation
remains a projected lookup or removal of wide-row work that is actually
measurable on the full-profile join.
