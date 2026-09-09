# Benchmark log

This log records measured configurations and results. These are TPC-C-derived
engineering workloads, not audited TPC-C results; throughput is committed
transactions per second, not tpmC.

## 2026-09-09 — River and MariaDB, sample five-transaction mix

### Platform and versions

Host: Apple M1, 8 CPU cores, 16 GiB RAM, arm64; macOS 26.5.2 (25F84).
Runs were sequential, with no concurrent agent builds or database workloads.
Normal background services were left running.

| DBMS | Version/build | Connection | Commit durability |
| --- | --- | --- | --- |
| River | Native `0.1.0-alpha.2`; branch `ticket/tic-7ed6-single-runtime`, implementation commit `70c12782`, checkout `da7a8794`; GraalVM 25.0.4, O3 + PGO | Loopback TCP with TLS 1.3, River protocol v4 | Local durable WAL acknowledgement |
| MariaDB | `12.3.3-MariaDB`, Homebrew arm64 | Local Unix socket | `innodb_flush_log_at_trx_commit=1` |

Runner: external `river-harness`, source commit `3c5643b`, Go 1.27.1.
River used the installed `bin/river` server with its default 16-connection limit.
MariaDB used the harness-managed Homebrew server. Each run created and cleaned
up its workload database. Both targets acknowledged durable commits.

### Workload

- `tpcc sample all`: New Order / Payment / Order Status / Delivery / Stock Level,
  with weights 45 / 43 / 4 / 4 / 4.
- One warehouse, 10 districts, 30 customers per district, 100 items and 30
  initial orders per district; 4,218 initial rows in total.
- Four workers; seed 42; five-second warmup and 30-second measured window.
- Maximum retries: 20 for both targets. Earlier three-retry runs exhausted the
  budget on this contended profile for both databases.
- Transactions use READ COMMITTED with explicit `FOR UPDATE` locks. MariaDB's
  environment record also reports its REPEATABLE-READ connection default;
  the workload explicitly selects READ COMMITTED for each transaction and
  the admission check verifies the effective level.
- Order: River, MariaDB, MariaDB, River; a fresh workload database for each run.

Commands, executed in that order with each target command repeated twice:

```sh
~/src/ingres/river-harness/benchmark run river tpcc sample all \
  --river-executable=/Users/blater/src/river/bin/river \
  --river-version=ticket/tic-7ed6-single-runtime \
  --warmup=5s --duration=30s --workers=4 --warehouses=1 \
  --seed=42 --max-retries=20

~/src/ingres/river-harness/benchmark run mariadb tpcc sample all \
  --warmup=5s --duration=30s --workers=4 --warehouses=1 \
  --seed=42 --max-retries=20
```

### Results

| Order | DBMS | Commits | TPS | p50 (ms) | p99 (ms) | Retries |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| 1 | River | 5,194 | 173.42 | 16.69 | 84.61 | 624 |
| 2 | MariaDB | 35,756 | 1191.87 | 1.57 | 16.32 | 5,370 |
| 3 | MariaDB | 35,522 | 1184.07 | 1.57 | 16.15 | 5,287 |
| 4 | River | 4,827 | 161.00 | 18.46 | 88.15 | 572 |

Mean of the two run TPS values: **River 167.21 TPS**;
**MariaDB 1187.97 TPS**. MariaDB delivered **7.10×** River's
mean throughput; River delivered **14.1%** of MariaDB's.

All four runs passed the workload invariants, with zero failed transactions and
zero unknown commits in both warmup and measurement. All four reported graceful
shutdown and an inactive final service state. The artifacts declare the same
comparison key and are all eligible for comparison.

This measures the current complete local client/server configurations, including
their different transports and DBMS defaults. It does not isolate engine cost
or the cost of TLS. Two samples show a substantial throughput gap, but are not
a confidence interval or a general scaling result. River varied more between
samples; retain both values when using this entry as a reference. These figures
must not be compared directly with `tools/tps-test.sh`, which runs a different
workload implementation.

### Run artifacts

Artifacts are under `~/src/ingres/river-harness/runs/`. Each directory contains
configuration, environment, results, admission checks and validation outcomes.

1. River: `river_harness_20260909_194348_b8184666`
2. MariaDB: `river_harness_20260909_194448_4d1d0219`
3. MariaDB: `river_harness_20260909_194546_e2298844`
4. River: `river_harness_20260909_194638_9ada53cc`

Comparison key: `c43b7664cc83be6b10b08711e25bd592b252ad3fbf865ffc3014d97c0e1312e6`.

## Follow-up: macOS commit synchronization (2026-09-09)

A single-client JVM INSERT/COMMIT method trace at River `da7a8794` found one WAL force per matched commit. Across 2,550 complete traces, file force averaged 3.343 ms of a 3.532 ms server commit; enqueue-to-processing and notification-to-resume each averaged about 0.014 ms. The measured workload passed all update/row-count checks. These are instrumented diagnostic timings, not a TPC-C comparison.

Installed-binary inspection found that the JVM file-force path calls macOS `fcntl(F_FULLFSYNC)`, whereas MariaDB 12.3.3's InnoDB file-flush routine calls `fsync`. Matching high-level commit settings therefore did not establish equal OS synchronization semantics or cost. The earlier throughput ratio remains an observation of those configurations, not a comparison with verified equivalent power-loss durability. No durability setting was changed.

Trace, commands, timing breakdown and disassembly: `/private/tmp/river-commit-trace-20260909/README.md`.

## 2026-09-10 — mapped WAL and cursor compiler fix

Branch `ticket/tic-6a91-mapped-wal`, based on `da7a8794`; same Apple M1/macOS
host and GraalVM 25.0.4. Native build retains O3 and PGO and enables shared arenas
for mapped-file lifetimes. A local cursor-loop restructuring resolves the native
compiler failure without optimizer exclusions.

| Runtime / WAL | Warmup | Committed TPS samples | p99 samples (ms) |
| --- | ---: | --- | --- |
| JVM / channel baseline | 15s | 169.71, 168.47 | 85.07, 88.93 |
| JVM / mapped candidate | 15s | 238.29, 246.12 | 71.11, 68.16 |
| Native / mapped final | 5s | 168.04, 167.76 | 104.53, 108.99 |

All use sample all, 30s measured, four workers, one warehouse, seed 42 and
20 retries. The native pair matches the earlier native workload configuration
in this log: its mean of 167.90 TPS remains within the earlier 173.42/161.00
range. These samples show no clear native TPS gain. Native p99 increased from
84.61/88.15 ms to 104.53/108.99 ms, and retries from 624/572 to 795/778.
The controls are older rather than adjacent, so the cause remains unresolved;
a paired investigation remains a follow-up. The user approved promotion on
2026-09-10, noting that faster commits may have shifted the bottleneck to
contention; that explanation has not yet been established. Do not compare its 5s
warmup directly with the JVM's 15s warmup or attribute the runtime difference
without a paired investigation. The earlier JVM pair showed a 43% higher mean.

Both final native runs passed all invariants, with zero failed/unknown outcomes.
Retries were 795 and 778; deadline cancellations were four and three. Run IDs:
`river_harness_20260909_230444_0be985f4` and
`river_harness_20260909_230554_c1c5f1fa`, under `river-harness/runs`.
Versions: `tic-6a91-mapped-native-final-1` and `tic-6a91-mapped-native-final-2`.
Both harness-owned servers shut down; final `river ps` reported no servers.

Mapped synchronization changes the macOS JVM primitive from `F_FULLFSYNC` to
`msync(MS_SYNC)` for ordinary WAL commits. Process-crash recovery passed; this
is not evidence of equivalent hardware power-loss protection. The complete
configuration, single-INSERT timing, correctness results and source-level
compiler diagnosis are recorded in the
[performance checkpoint](performance-checkpoints.md#2026-09-09--mapped-wal-tic-6a91).
