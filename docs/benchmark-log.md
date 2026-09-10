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


## 2026-09-10 — completed INSERT efficiency versus MariaDB

River `master` at `56f73a81` (`perf-checkpoint-20260910-insert-efficiency-complete`),
native O3/PGO `bin/river`, versus Homebrew MariaDB 12.3.3. Same Apple M1,
macOS 26.5.2 arm64 host. No concurrent build, profile or second workload.
The harness artifact reports build `94fb63a24d28ca6f0e9a15b45322fb4d3240e963+dirty`;
all samples used that same runnable harness without edits.

Matched sample/all mix (45/43/4/4/4), four workers, one warehouse, seed42,
20 maximum retries, 15-second warmup and 60-second measurement. Both bindings
use READ COMMITTED with explicit FOR UPDATE locks. River uses loopback TCP/TLS;
MariaDB uses a Unix socket. MariaDB retains `innodb_flush_log_at_trx_commit=1`
and `innodb_snapshot_isolation=1`; River retains local durable WAL acknowledgement.
The OS synchronization primitives and transports remain different; this is a
whole-configuration diagnostic, not verified equivalent power-loss protection.

Executed sequentially MariaDB/River/River/MariaDB:

```sh
~/src/ingres/river-harness/benchmark run mariadb tpcc sample all \
  --warmup=15s --duration=60s --workers=4 --warehouses=1 --seed=42 --max-retries=20

~/src/ingres/river-harness/benchmark run river tpcc sample all \
  --river-executable=/Users/blater/src/river/bin/river \
  --river-version=master-56f73a81-native-comparison-N \
  --warmup=15s --duration=60s --workers=4 --warehouses=1 --seed=42 --max-retries=20
```

`N` is 1 or 2 for the River sample.

| Order | Target | Commits | TPS | p99 ms | Retries |
| --- | --- | ---: | ---: | ---: | ---: |
| 1 | MariaDB | 71,170 | 1,186.158 | 16.327 | 10,285 |
| 2 | River native | 13,371 | 223.108 | 78.971 | 1,785 |
| 3 | River native | 13,280 | 221.406 | 76.022 | 1,903 |
| 4 | MariaDB | 65,726 | 1,095.424 | 17.498 | 9,652 |

Arithmetic means: River 222.257 TPS; MariaDB 1,140.791 TPS, a 5.133× ratio.
River reaches 19.5% of MariaDB throughput in these samples. Both River samples
are close; MariaDB varies by about 8% between samples. Two observations per target
are diagnostic, not a confidence interval or general throughput claim. These
figures cannot be compared with `tools/tps-test.sh` scores.

All four artifacts are eligible with the same comparison key
`0f9fba963bebb12b563b1fd707b82bcc18b66b16cdaf23be538c4b28cef12011`.
Warmup and measurement have zero failed/unknown transactions; all invariants pass.
All servers stopped gracefully and returned to inactive state. Retries per commit
were River 0.134/0.143 and MariaDB 0.145/0.147; retry frequency alone does not
explain the throughput gap. In the first pair, New Order mean latency was
31.93 ms versus 6.17 ms, Payment 4.25 versus 0.92 ms, Delivery 23.00 versus
3.81 ms, and Stock Level 10.84 versus 0.48 ms. Low-frequency query costs deserve
attention too, but New Order has much greater workload weight.

Artifacts under `~/src/ingres/river-harness/runs/`, in run order:

- `river_harness_20260910_134541_595395ef`
- `river_harness_20260910_134718_68bddb1a`
- `river_harness_20260910_134841_b7b3392c`
- `river_harness_20260910_135003_86da4ed0`

Commands/logs and compact summary: `/private/tmp/river-maria-20260910-final/`.

### Next nominated change: reuse admitted table bindings

A separate current-code JVM run used the same workload and GraalVM 25.0.4 with
`-Xmx1g`. Async-profiler captured 20 seconds CPU at 10ms and then 20 seconds wall
at 1ms during measurement. It passed invariants, zero failed/unknown outcomes and
graceful cleanup. Its 276.92 TPS is instrumented JVM evidence and is excluded
from the native/MariaDB comparison. Artifact:
`river_harness_20260910_135240_35f993df`.

Of 2,559 CPU samples in request-dispatch and commit-worker stacks, descriptor
resolution (`RelationalDescriptorNames.open`) accounts for 16.06% inclusive:
name-map search 8.25%, catalog head/manifest loading 7.35%. Preparation occupies
18.60%; INSERT execution 12.78%; historical-frame reclamation only 0.35%.
The separately sampled wall proportions agree approximately (16.24%, 18.70%,
11.51%, 0.35%). Inclusive groups overlap and cannot be added. These percentages
exclude other process stacks and unmounted virtual-thread waits; they are neither
exact method elapsed times nor predicted TPS gains. Native attribution still
needs confirmation during any candidate validation.

Source confirms `RelationalDescriptorNames.find` scans the durable name-map rows,
and `CatalogTableOpener.load` reads the head and manifest before looking up an
already cached descriptor. The earlier `tic-186e` moved these reads into the owning
transaction to remove an unrelated durability wait; it did not eliminate repeated
resolution. The existing schema gate and pins provide a concrete lifetime boundary.

Nominate one bounded change: resolve a table once within the admitted relational
transaction, retain its validated object/schema binding under the existing memory
budget, and reuse it for subsequent preparation/execution/FK lookups. Keep private
DDL overlays authoritative; invalidate affected bindings on DDL and savepoint
rollback, release at transaction end, and preserve durability dependencies and
pin ownership. Begin with transaction lifetime rather than a global SQL cache or
cross-transaction invalidation framework. Replace repeated lookup for admitted
bindings without changing SQL, transaction structure, isolation or durability.

This targets a substantial measured cost across common operations. It does not
promise to remove the entire 16% or close the 5× gap. Compare matched controls and
candidate profiles/TPS before accepting it. Frame-history reclamation remains a
smaller follow-up: its full-cache scan is visible in single-INSERT work, but is
only 0.35% in this full-mix profile. Preparation beyond catalog binding and socket
write overhead are separate later candidates, not additions to the first change.

Profiles, command configuration and source-level diagnostic scripts:
`/private/tmp/river-maria-20260910-final/`. View `cpu-requests.svg` and
`wall-requests.svg`; raw collapsed stacks and `profile-summary.json` are alongside.
No production or harness code changed in this comparison.

## 2026-09-10 — current native River and next repeated-work target

Current `master` `a4567c01`, native O3/PGO `bin/river`. Reused the recorded
MariaDB 12.3.3 baseline above rather than rerunning it. Same host, sample/all,
four workers, one warehouse, seed42, max retries20, warm15/load60 and unchanged
isolation/durability settings and transports.

```sh
~/src/ingres/river-harness/benchmark run river tpcc sample all \
  --river-executable=/Users/blater/src/river/bin/river \
  --river-version=master-a4567c01-native-followup-N \
  --warmup=15s --duration=60s --workers=4 --warehouses=1 --seed=42 --max-retries=20
```

| Sample | TPS | p99 ms | Retries | Commits |
| --- | ---: | ---: | ---: | ---: |
| River N=1 | 280.862 | 60.260 | 2,328 | 16,839 |
| River N=2 | 289.743 | 56.001 | 2,308 | 17,374 |
| Recorded MariaDB 1 | 1,186.158 | 16.327 | 10,285 | 71,170 |
| Recorded MariaDB 2 | 1,095.424 | 17.498 | 9,652 | 65,726 |

Mean River 285.303 TPS versus recorded MariaDB 1,140.791: approximately 4.00×
remaining gap. The MariaDB controls are older, so this is a diagnostic comparison,
not an adjacent pairing or a claim that a particular change caused the ratio.
Both new runs passed warmup/measurement outcomes, all invariants and graceful
cleanup with zero failed/unknown transactions. Comparison eligibility/key match
the stored baseline. Final `river ps` reported no running servers.

Reports below `~/src/ingres/river-harness/runs/`:
`river_harness_20260910_165243_cca42897` and
`river_harness_20260910_165407_e95a3858`. Commands and summary:
`/private/tmp/river-next-repeat-20260910/`.

### Nomination: share already-prepared immutable SQL templates within a session

The latest same-code JVM CPU/wall profile remains
`/private/tmp/river-tic-5c21/`. Preparation occupies 16.895% of sampled request/
commit CPU (16.583% wall). Within preparation, parsing takes 6.63% of that same
CPU denominator, template capture 2.78%, and bound-state reset 2.52%. These are
statistical, overlapping call-tree shares, not a predicted native TPS gain.
Other candidates are smaller: FK discovery scan 2.57%, binder-view construction
0.56%, historical-frame reclamation 0.43% inclusive CPU.

Source tracing identifies a concrete repetition trigger. The common sqlfull
worker prepares its catalogue through `connection.PrepareContext`, then calls
`tx.StmtContext` for operations. Installed Go1.27.1 `database/sql/sql.go` passes
the connection as the statement's non-null `cg`; `Tx.StmtContext` consequently
calls `ctxDriverPrepare` again. Both MariaDB and River use this common worker.
River's `EngineSession.prepare` unconditionally calls `validatePrepared`, which
reparses SQL, resets binding state, estimates retained storage and captures another
immutable template before `RetainedPreparedStatements.open` allocates a handle.

The proposed River change gives duplicate PREPARE requests independent handles
sharing one retained template for exact SQL and its valid semantic context.
Keep lookup/accounting under the existing session prepared-statement owner;
validate authorization and schema freshness through their existing owners.
Parameters, execution buffers and result state remain per execution. Close releases
one handle/reference; final release frees the template reservation. Revalidate on
schema changes; do not retain a process-wide or unbounded SQL cache. No harness,
transaction boundary, durability or wire-contract change is required.

This is the strongest currently evidenced repetition-removal candidate. It removes
parsing/template construction already completed for another live handle, while
leaving actual SQL execution and all benchmark work intact. It does not promise
to remove all preparation time or close the full 4× gap. No implementation or new
ticket is included in this investigation.


## 2026-09-10 — repeated PREPARE implementation

[tic-7a32](tickets/tic-7a32.md), implementation `f90eabf9`, shares session-local
prepared plans between independent live handles. Matched JVM full-mix 30s
controls/candidates were 313.0/338.4 versus 377.2/377.6 TPS. The subsequent 60s
pair was 371.7 versus 389.8 TPS (+4.9%), with lower p99 and retry rate per commit.
All passed correctness and cleanup. PREPARE's sampled request/commit CPU share
fell 16.9% → 3.2%; template capture no longer appeared. The larger short-run gain
is not a general speedup claim. The preceding native/MariaDB comparison remains
a separate diagnostic; MariaDB was not rerun for this implementation.

Commands, exact versions, report IDs and profile details are in
[the performance ledger](performance-checkpoints.md) and
`/private/tmp/river-tic-7a32/`. Workload semantics, transaction boundaries,
isolation and durability are unchanged.
