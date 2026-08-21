# River Performance Review and Benchmark Plan

<!-- markdownlint-disable MD013 -->

Status: Proposed performance-review protocol

Audience: River performance reviewers, subsystem engineers, CI maintainers, and release owners

Related plans:

- [River Engineering Personas and Performance Charter](river-engineering-personas-and-performance-charter.md)
- [River Standalone Workload Harness Plan](river-standalone-workload-harness-plan.md)
- [River High-Level Architecture and Delivery Plan](river-high-level-plan.md)
- [River Project Implementation and Dependency Plan](river-project-implementation-plan.md)
- [River Replicated Journal, Durability, and Storage Evolution Plan](river-replicated-journal-durability-plan.md)

## 1. Purpose

This plan turns River's low-GC, zero-copy, high-throughput objectives into a
repeatable review process. It defines what is measured, the tools and data used,
which gates run at each cadence, and the evidence required to approve a change.

Performance review is not a single TPS number. A change can increase throughput
while making p99.9 latency, recovery, allocation, copied bytes, write
amplification, or overload behavior unacceptable. The review packet reports the
whole relevant cost vector.

## 2. Decisions

1. **Seeded River-owned generators are the canonical regression data.** They are
   reproducible, scalable, redistributable, and can create deliberate skew and
   contention.
2. **External datasets are realism suites, not the only merge gate.** Pin their
   version, checksums, license, schema adapter, and source; fetch on demand
   instead of committing the data.
3. **JMH measures mechanisms; end-to-end drivers decide database performance.**
4. **Allocation, copying, CPU, I/O, network, boundedness, and latency accompany
   TPS.**
5. **Numbers come from a declared physical reference host.** Initial M0 budgets
   use the current physical development machine with repeated controlled runs.
   Dedicated Linux reruns are required before portable or Linux-specific
   performance claims, but are not a G0 prerequisite. Shared CI proves behavior
   and runs smoke tests but does not gate on noisy timings.
6. **Baseline and candidate run on the same machine and immutable setup.**
7. **Durability contracts are never mixed.** `LOCAL_DURABLE`,
   `QUORUM_DURABLE`, and any `QUORUM_ACCEPTED` results remain separate.
8. **Capacity is measured inside latency/error/lag bounds.** An unconstrained
   peak rate that produces unbounded queues is not a pass.
9. **Standalone end-to-end workloads use `river-harness`.** The Java
   `river-bench` module owns JMH, mechanism prototypes, and internal allocation
   evidence. The separate Go harness repository owns full-database workloads,
   DBMS adapters, cross-DBMS comparison, and external reporting projections.

## 3. Metric contract

### 3.1 User-visible metrics

| Metric | Definition |
| --- | --- |
| Committed TPS | Transactions reaching the requested acknowledgement contract per measured second; attempts, retries, and aborts are separate |
| Statement QPS | Completed statements per second, separated by statement/query class |
| Scheduled latency | Intended request issue time through complete response; required for open-loop tests |
| Service latency | Actual client send time through response; reported beside scheduled latency |
| Commit latency | Commit request through acknowledgement satisfying the named durability tier |
| Query latency | Execute request through complete result consumption; first-row latency may be a second metric |
| Error/abort rate | Exact status code and family by operation |
| Recovery | Restart/failover time, replay rate, permitted lost range, and time until full service |

Report count, minimum, p50, p95, p99, p99.9, maximum, mean, and the raw HDR
histogram. Maximum is retained because rare stalls remain important.

The driver supports:

- **Closed loop** for maximum sustainable throughput and concurrency scaling.
- **Open loop** for latency at declared offered rates. Requests are timed from
  their intended schedule, preventing coordinated omission from hiding stalls.

### 3.2 Engine-cost metrics

| Area | Measurements |
| --- | --- |
| Allocation/GC | Heap bytes and objects per transaction/statement/batch/row, TLAB refills, GC count/time/pause, safepoints |
| Native/off-heap | River arena reserved/used/high-water bytes, direct buffers, mapped bytes, JVM native categories |
| Copies | Count and bytes by boundary: protocol, WAL, I/O, page flush, operator hand-off, result detach |
| CPU | Process/thread CPU, CPU per committed transaction/row/journal byte, hot stacks, context switches, optional hardware counters |
| Contention | Lock/latch/monitor/queue wait, parks, CAS retries where meaningful |
| Storage | Read/write/force bytes, operations, latency, queue depth, WAL/data/checkpoint traffic, database/index/WAL size |
| Network | Bytes/messages per transaction, batch size, RTT, retransmits, consensus round latency, follower lag |
| Amplification | WAL, network, page/checkpoint, and index bytes/writes per logical mutation |
| Boundedness | Queue/ring/history high-water marks, backpressure time, rejections, spill, retained batches/pins |

River counters are authoritative for logical copies, reservations, frontiers,
and amplification. OS/JVM tools corroborate them and reveal external costs.

## 4. Tools and checks

All versions are pinned in the benchmark image/manifest. A profiler, JVM,
kernel, firmware, or driver upgrade starts a new baseline.

### 4.1 Source and bytecode gates

| Check | Purpose | Cadence |
| --- | --- | --- |
| `.editorconfig` plus deterministic source-policy checks | Two spaces, no tabs, deterministic text layout | Every PR |
| Gradle dependency rules and preferably ArchUnit | Enforce the module DAG | Every PR |
| `javac -Xlint:all -Werror` plus River source/bytecode rules | Detect ignored diagnostics, boxing, accidental varargs, exception construction, and unsafe hot-path calls | Every PR |
| Hot-path bytecode audit | Reject forbidden allocation/calls in designated kernel methods unless allowlisted | Every PR |
| Format compatibility and size fixtures | Detect durable/wire/page representation changes | Every PR |

The bytecode audit checks designated hot paths for exception construction,
streams/collectors, string formatting, captured lambdas, object arrays used for
varargs, boxing, and other forbidden operations. It does not impose kernel rules
on parsing, planning, DDL, or administration. Runtime measurement is still the
source of truth because JIT escape analysis can eliminate allocations and
libraries can hide them.

### 4.2 JVM and concurrency tools

| Tool | Evidence |
| --- | --- |
| [OpenJDK JMH](https://github.com/openjdk/jmh) | Forked/warmed mechanism benchmarks, confidence intervals, throughput/latency, normalized allocation with its GC profiler |
| [OpenJDK JOL](https://github.com/openjdk/jol) | Object/array layout, alignment, and footprint assumptions |
| [OpenJDK jcstress](https://github.com/openjdk/jcstress) | Memory-ordering/concurrent primitive correctness; not a throughput benchmark |
| [JDK Flight Recorder](https://openjdk.org/jeps/328) | Allocation, GC, safepoints, locks, file/socket activity, threads, and River phase events |
| [async-profiler](https://github.com/async-profiler/async-profiler) | CPU, wall, allocation, native allocation, lock, and hardware-counter profiles |
| JVM GC logs | Collector/heap configuration, pauses, causes, and concurrent cycles |
| Native Memory Tracking and `jcmd` | JVM native categories and steady-state deltas |

JFR and async-profiler find allocation sites. A warmed normalized allocation
counter gates zero allocation; sampling alone cannot prove zero. HotSpot thread
allocation counters sit behind a River platform adapter and the result reports
when the capability is unavailable.

### 4.3 OS, storage, and network tools

| Tool | Evidence |
| --- | --- |
| Linux `perf stat/record` | Cycles, instructions, branches, cache misses, switches, migrations, native/kernel stacks |
| `pidstat`, `iostat`, `/proc`, cgroups | Scheduling, faults, memory, storage queueing, throttling |
| Optional eBPF/bpftrace | Block, syscall, scheduler, and network tail diagnosis; not enabled in canonical timing unless overhead is quantified |
| [fio](https://github.com/axboe/fio) | Filesystem/device calibration matching WAL-force and page/checkpoint patterns |
| [iperf3](https://github.com/esnet/iperf) | Pairwise cluster bandwidth, loss, and TCP calibration |
| River fault scheduler/proxy | Repeatable delay, loss, partition, disk stall, and force failure |

`fio` uses an explicitly dedicated benchmark file/filesystem, never an
unresolved raw device. Calibration establishes the ceiling but does not replace
a River durable-WAL benchmark.

### 4.4 Drivers and histograms

- Record latency in fixed-size
  [HdrHistogram](https://github.com/HdrHistogram/HdrHistogram) instances without
  allocation during measurement.
- Build the separate `river-harness` repository so suite semantics, DBMS
  bindings, and DBMS adapters remain distinct. Its first adapter targets the
  installed Homebrew MariaDB through an explicit start/stop lifecycle; a
  PostgreSQL adapter then proves that generators, scheduling, verification,
  metrics, artifacts, and comparison are not DBMS-specific. River enters later
  when its public SQL/query execution path supports the required vertical
  slice.
- Add a River dialect/driver to
  [BenchBase](https://github.com/cmu-db/benchbase) when JDBC ships. Start with
  SmallBank, then TPC-C-like and CH-benCHmark coverage. Call results BenchBase
  results, not audited official TPC results.
- Use [YCSB](https://github.com/brianfrankcooper/YCSB) only to diagnose point/
  range storage. It does not represent relational transactions or joins.
- Add [ClickBench](https://github.com/ClickHouse/ClickBench) after Phase 3C for
  scan/aggregation/operator coverage. Its nearly 100-million-row flat table and
  43 queries are analytical evidence, not an OLTP gate.

## 5. Benchmark layers

| Layer | Examples | Required for |
| --- | --- | --- |
| L0 policy/correctness | Bytecode audit, copy/ownership tests, jcstress | Every relevant PR |
| L1 JMH primitive | Codecs, checksums, WAL reservation, queue, latch, node search, vector kernel | Hot primitive change |
| L2 component | WAL/group force, buffer flush, B+tree split, lock table, vector pipeline, protocol codec | Subsystem change |
| L3 single-node engine | Embedded/native/JDBC SQL with durability, checkpoints, vacuum, complete results | Engine-visible change/nightly |
| L4 replicated engine | Durable quorum, failover, catch-up, degraded member, state sync | Replication change/weekly |
| L5 soak/interference | Mixed load with backup, checkpoint, vacuum, maintenance, faults, restart | Weekly/release |

Review uses the lowest layer that isolates the mechanism and at least one higher
layer validating the system effect.

## 6. Reference runner protocol

### 6.1 Environment manifest

Initial runs use the declared physical development host. Later portable or
Linux-specific claims use reserved bare metal or a demonstrably stable Linux
runner. Every result records the host class and may be compared only with a
compatible environment unless the report explicitly analyzes the difference.
Record:

- River/benchmark commits, generator/dataset version, schema, and indexes;
- JDK build, flags, collector, heap/direct limits, and JIT settings;
- CPU/microcode, core/SMT/NUMA placement, governor/turbo, memory, thermal state;
- kernel, filesystem/mounts, storage/firmware/write-cache/durability settings;
- network adapters/topology/MTU/RTT/bandwidth and client/replica placement;
- durability tier, isolation, clients, offered rate, scale, warm-up, duration,
  cache state, and background work.

Controlled repeated runs on the declared physical reference host may gate
initial M0/G0 budgets even when that host is a developer laptop. Uncontrolled
laptop runs and shared CI timings are advisory. Portable, Linux-specific, and
release performance claims require a suitable later reference host.

### 6.2 Run method

1. Calibrate CPU, filesystem/device, and network; quarantine a drifting runner.
2. Build baseline and candidate with the same toolchain.
3. Restore the same database image or regenerate from the same seed. Give every
   independent write sample a fresh restore/clone.
4. Warm JVM/database to the declared compilation/cache condition and record the
   warm-up.
5. Interleave baseline/candidate, for example `A B B A`, over at least five
   independent samples; reverse the starting order.
6. Keep clients off database CPUs and, for cluster capacity, off replica hosts.
7. Save raw histograms, counters, and time series.
8. Rerun with profiles for attribution. Do not substitute instrumented timings
   for canonical results unless overhead is quantified.

Cold, lukewarm, and warm cache results are separate. Restarting River without
clearing the OS page cache is labeled lukewarm.

### 6.3 Decision rules

- Compare on the same runner and report effect plus 95% confidence interval.
- Inspect time series for compilation, bimodality, thermal drift, checkpoint/GC
  cycles, and client saturation.
- Investigate outliers rather than deleting them automatically.
- Define capacity as the highest offered load inside latency, error, queue, and
  durability-lag budgets.

Initial alerts, recalibrated after Phase 0 runner variance is known:

- any allocation, copy, or boundedness budget violation: block;
- sustained throughput regression greater than 5% with confidence excluding
  zero: block or explicit waiver;
- p99/p99.9 regression greater than 10% and above a material absolute floor:
  block or explicit waiver;
- WAL/network/storage/index/copied bytes per operation increasing more than 5%:
  require explanation and workload evidence;
- new GC pause, saturation, retry storm, durability-lag, or recovery budget
  breach: block regardless of mean TPS.

Intentional trade-offs need architecture-owner and performance-reviewer
approval with benefit, cost, affected workload, and rationale recorded.

## 7. Canonical generated data

### 7.1 Why generators are primary

Keep schemas, deterministic generators, workload scripts, expected aggregates,
and small seed fixtures in the repository. Generation enables:

- stable licensing and CI availability;
- cache-resident through many-times-RAM scaling;
- uniform, Zipfian, temporal, and hot-key distributions;
- controlled null/width/cardinality/duplicate/correlation patterns;
- valid balances, constraints, foreign keys, and mutations;
- targeted page splits, overflow values, index fan-out, and conflicts.

Each generator has a versioned algorithm and seed. A generator change starts a
new baseline.

### 7.2 RiverBank

The canonical OLTP model contains branches, customers, accounts, cards, loans,
payments, account/card transactions, employees, and support tickets. It runs:

- balance/account/card point reads;
- atomic two-account transfers with funds check, ledger inserts, balance
  updates, indexes, and idempotency;
- deposit/withdrawal and card authorization/reversal;
- loan payment and late-state update;
- recent-statement range scan;
- customer/branch joins and indexed risk lookup;
- contended hot accounts and independent lanes;
- constraint rejection, deadlock, rollback, retry, unknown commit outcome;
- periodic branch/month aggregation concurrent with OLTP.

Report prepared SQL, batched SQL, and compiled transaction templates separately
without weakening constraints, indexes, or isolation.

Scales are `tiny` (PR smoke), `cache` (active set in buffer pool),
`memory-pressure` (active set exceeds pool), `storage` (database several times
RAM), and `history` (vacuum/checkpoint/recovery/retention stress).

### 7.3 RiverPapers

This variable-width model contains DOI, title, institution, date, version,
category, nullable publication DOI, abstract, and normalized author relations.
It measures:

- batch ingestion and constraint/index build;
- UTF-8 tuple encoding, overflow storage, and wide results;
- unique DOI and category/date indexes;
- supported point/prefix/range lookups;
- author joins, grouping, sorting, pagination, and scans;
- replacing abstracts/adding versions without retaining old large values;
- cold/warm scans, spill, streaming, and slow consumers.

It does not imply full-text search. Until River ships a full-text index, only
supported scans and B+tree-compatible predicates are tested.

### 7.4 Pathological generators

Generate page/overflow-boundary rows and keys, monotonic/random/common-prefix
keys, high duplicates, nullable composites, skewed/correlated foreign keys,
large transactions, compressible/incompressible payloads, and immediate through
maximally stalled result consumers.

## 8. External data

An external dataset manifest pins URL, owner/slug, version, retrieval date,
archive/file checksums, declared license, expected schema/rows, and adapter
version. CI never silently fetches a newer version.

### 8.1 Banking Transactions Dataset

The referenced
[Kaggle dataset](https://www.kaggle.com/datasets/vivekmali1436/banking-transactions-dataset)
is a strong Phase 3C realism suite:

- Kaggle metadata currently reports version 1, about 304 MB, CC BY-SA 4.0.
- Its [linked project](https://github.com/Vivek7ok/Banking_data_analysis)
  describes about 5.9 million rows in ten tables: 2 million account
  transactions, 3 million card transactions, 600,000 loan payments, 95,000
  accounts, 60,000 customers, and supporting entities.
- It supplies a normalized PostgreSQL schema, primary/foreign keys, targeted
  indexes, and over 40 analytical questions.

Use it for bulk load, constraints, index build, joins, grouping, window
functions when supported, storage size, plan selection, and cold/warm reports.
It is clean and static, so it is not the only OLTP test. Apply River-owned
mutations to a clone only after verifying/reconstructing balance/ledger
invariants needed by the driver.

Because the data is share-alike and externally hosted via Kaggle/Git LFS, store
attribution and fetch instructions but do not vendor the CSVs without a separate
provenance/license decision.

### 8.2 BioRxiv Preprints Corpus

The referenced
[Kaggle corpus](https://www.kaggle.com/datasets/uradkr/biorxiv-life-sciences-preprints-corpus)
is a useful Phase 3C variable-width realism suite:

- Kaggle metadata currently reports version 1, 17,997 rows, about 36.6 MB, and
  CC BY-NC-SA 4.0.
- Columns include DOI, title, comma-separated authors, institution, date,
  version, category, abstract, and mostly-null publication DOI.
- It is useful for realistic UTF-8 width/cardinality but too small to prove scan
  bandwidth or spill behavior alone.

Use the pinned corpus for import correctness and distribution realism. Scale
with RiverPapers generation; do not duplicate article abstracts to manufacture
volume.

Its non-commercial/share-alike declaration makes it unsuitable for bundling by
default without legal approval. BioRxiv's
[TDM guidance](https://www.biorxiv.org/tdm) also says its bulk repository is not
a grant to re-host content. Store only the manifest/adapter and treat download,
use, and redistribution as a separate license decision.

### 8.3 Standard suites

| Suite | Use | Limitation |
| --- | --- | --- |
| BenchBase SmallBank | Early JDBC transactions/contention | Narrow; does not replace RiverBank |
| BenchBase TPC-C | Recognizable multi-table OLTP | Do not call results official/audited TPC |
| BenchBase CH-benCHmark | OLTP plus analytical interference | Requires Phase 3C SQL |
| ClickBench | Wide analytical operator comparison | Flat-table analytical focus |
| YCSB | Storage/API diagnostic | Does not test relational semantics |

## 9. Change-to-evidence matrix

| Change | Minimum evidence |
| --- | --- |
| Codec/key/page layout | JMH allocation/cycles, JOL, format/copy delta, component round trip |
| WAL/journal | Reserve/encode JMH, durable append by batch/concurrency, force/WAL bytes, CPU/JFR, checkpoint interference |
| Buffer/flush | Hit/miss/pin/evict, stable-image copies, amplification, cache/storage RiverBank |
| B+tree/index | Search/insert/split JMH, size/fan-out, skew/concurrency/range/recovery |
| Hash index | Equality hit/miss and collision probes, bucket occupancy/overflow bounds, resize stalls, skew/concurrency, recovery, and latency/byte comparison with B+tree |
| BRIN index | Correlation and pages-per-range sweep, pages skipped, false-positive rate, predicate-recheck cost, summary/WAL bytes, update drift, resummarization, and recovery |
| Reclamation/full rewrite | Insert/delete churn steady state, dead-to-reusable latency, page occupancy, free-tail truncation, `VACUUM FULL` duration/headroom/amplification, bytes returned, concurrent interference, cancellation, and recovery |
| MVCC/locks | jcstress/model tests, conflict scaling, allocation, hot-key p99, history/vacuum soak |
| SQL/operator | Kernel JMH, no per-row allocation, RiverPapers/relevant external queries, spill/result cost |
| Protocol/JDBC | Codec allocation/copies, loopback/remote, wide/slow results, full-result timing |
| Replication | Same-tier A/B, CPU/network/WAL bytes, batches, cluster scaling, lag/failover/catch-up/state sync |
| Observability | On/off overhead, disabled allocation, saturation/drop/cardinality behavior |

## 10. Automation cadence

### Every PR

- Format/tab/dependency/static/bytecode/ownership/status/copy gates.
- Relevant JMH smoke run, advisory on shared CI.
- `tiny` generated workload plus affected correctness/fault tests.
- Declaration of required dedicated run for a material hot-path change.

### Nightly dedicated

- Full L1/L2 set.
- RiverBank `cache` and `memory-pressure` single-node tests.
- Allocation, GC, copy, WAL/storage amplification, JFR summary.
- Control charts and automatic issue with raw regression artifacts.

### Weekly

- RiverBank `storage`/`history`, scaled RiverPapers, available pinned realism
  data, and supported BenchBase suites.
- Durable replication, failover/catch-up, backup/checkpoint/vacuum interference,
  overload, and multi-hour soak.
- Profiles plus storage/network calibration.

### Release candidate

- Complete supported durability/JVM/filesystem/workload matrix.
- Database several times RAM and long-history recovery.
- Clean-machine external dataset preparation from manifests.
- Same-hardware comparison with previous release.
- Published methodology/raw River results; third-party results only when terms
  permit and configuration is fully disclosed.

## 11. Review packet

The reviewer links:

1. hypothesis and affected hot paths;
2. baseline/candidate commits and environment manifest;
3. workload, seed, scale, schema/indexes, durability/isolation, clients, rate,
   warm-up, and duration;
4. raw histograms and throughput/error time series;
5. allocation/GC/native-memory and arena results;
6. copy/WAL/network/storage/index/checkpoint amplification;
7. unprofiled result plus JFR/flame/profile attribution;
8. queue/frontier/lag behavior at steady state and saturation;
9. confidence intervals, runner control, and outlier explanation;
10. pass, blocker, or recorded trade-off against named budgets.

## 12. Adoption

### P0: harness and manifests

- Retain `river-bench` for Java microbenchmarks, mechanism prototypes, and
  internal allocation evidence; benchmark dependencies never enter the engine.
- Create the separate `river-harness` Git repository according to the
  [standalone workload harness plan](river-standalone-workload-harness-plan.md)
  for full-database suites, DBMS adapters, cross-DBMS comparison, and external
  report compatibility.
- Define machine/run/dataset/result JSON schemas.
- Add HDR histograms, open/closed-loop scheduling, fixed seeds, status/error
  accounting, and complete result consumption.
- Add allocation/copy/arena/queue counters and benchmark-only readers.
- Declare the initial physical reference host and quantify its control variance.

### P1: kernel gates

- Add JMH for codecs, checksums, journal reservations, page access, B+tree nodes,
  queues, and disabled diagnostics.
- Add scoped hot-path bytecode audit and owned allowlist.
- Implement RiverBank `tiny`/`cache` and WAL/buffer/index component loads.
- Freeze initial allocation/copy/WAL/force/recovery/p99 budgets before Phase 1.

### P2: relational and external suites

- Add RiverPapers and the banking manifest/adapter.
- Add bioRxiv only after provenance/license review.
- Adapt banking analytical SQL with expected-result checks.
- Add BenchBase River JDBC support and selected workloads.

### P3: replication and mixed load

- Provision reserved multi-node runners and record physical topology.
- Add durable quorum, degraded quorum, failover, catch-up, and state-sync runs.
- Measure accepted-to-durable lag only if the volatile tier ships.
- Add checkpoint/backup/vacuum/analysis/state-sync interference with critical-
  progress assertions.

## 13. References

- [OpenJDK JMH](https://github.com/openjdk/jmh)
- [JDK Flight Recorder](https://openjdk.org/jeps/328)
- [async-profiler](https://github.com/async-profiler/async-profiler)
- [OpenJDK JOL](https://github.com/openjdk/jol)
- [OpenJDK jcstress](https://github.com/openjdk/jcstress)
- [HdrHistogram](https://github.com/HdrHistogram/HdrHistogram)
- [BenchBase](https://github.com/cmu-db/benchbase)
- [YCSB](https://github.com/brianfrankcooper/YCSB)
- [ClickBench](https://github.com/ClickHouse/ClickBench)
- [fio](https://github.com/axboe/fio)
- [iperf3](https://github.com/esnet/iperf)
- [Banking Transactions Dataset](https://www.kaggle.com/datasets/vivekmali1436/banking-transactions-dataset)
- [Banking dataset schema and row counts](https://github.com/Vivek7ok/Banking_data_analysis)
- [BioRxiv Life Sciences Preprints Corpus](https://www.kaggle.com/datasets/uradkr/biorxiv-life-sciences-preprints-corpus)
- [bioRxiv text/data-mining guidance](https://www.biorxiv.org/tdm)
