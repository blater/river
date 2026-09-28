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
