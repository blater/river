# River/MariaDB transaction-cost comparison — current build

## Scope and executable

This is the final continuation of tic-gothmog on one macOS arm64 host, using
TPC-C-derived **sample New-Order**, not audited TPC-C and not all five families.
It combines the [historical baseline](2026-09-15-tic-gothmog-transaction-costs.md)
and [mechanism investigation](2026-09-15-tic-gothmog-mechanisms.md) with fresh
runs of the changed River code.

The River executable is `/private/tmp/river-gothmog-current/river`, packaged from
HEAD `eede02521803a148abf09a8df8155c33e8d9affe` plus the local descriptor admission,
page/root CRC and registry cleanup ordering changes. Its version label is
`master-eede0252-local-crc-v4-descriptors-<run-label>`. It runs GraalVM JDK 25
with `-Xmx1g`, protocol v5, public version 0.1.0-alpha.2. The copied runtime jars
are under the same artifact directory's `jvm/lib`; subsequent builds do not
change them. The harness uses the public `river server` lifecycle.

Page/root formats are v4. Whole-page CRC has been replaced with a 120-byte header
CRC; tuple-root record CRC has been removed; remaining accessible heap CRC
calculations use bulk updates. Structural and identity checks remain. The local
build also contains earlier descriptor changes and code newer than historical
ef935596. Comparing those historical and current runs therefore cannot isolate
the CRC change's effect. Reduced page payload corruption detection is an explicit
integrity tradeoff, not evidence of equivalent corruption protection to MariaDB.

## Matched workload contract

- Profile: sample; New-Order only; one warehouse; seed 42; 1% deliberate rollback.
- Warmup 20 seconds, measurement 30 seconds; retries 3 (up to four attempts).
- Effective transaction isolation: READ COMMITTED with explicit FOR UPDATE.
  MariaDB's reported default REPEATABLE-READ is overridden per transaction;
  the harness admission verifies the effective level.
- Flushed commit durability: River local durable WAL, MariaDB flush-at-commit=1.
  These runs do not certify physical power-loss behavior.
- Transport: River authenticated loopback TCP/TLS 1.3; MariaDB private Unix socket.
  Process CPU differences include this transport difference.
- One-worker order: River/MariaDB/MariaDB/River. Four-worker follow-up:
  River/MariaDB/River. One additional River one-worker JFR run is separate.
- Fresh harness-owned target instances; no simultaneous builds or workloads.

Latency covers the whole logical execution, including retries/backoff and all
terminal outcomes; input generation and preparation are excluded. CPU figures
are process CPU deltas divided by commits from phase brackets, not engine-only
CPU or exact synchronized accounting. Individual samples and boundary offsets
are retained. A 20-second warmup does not itself prove steady state.

## One-worker results

All four runs passed, comparison-eligible with the same key
`2eb18a26f6e6a3e13195557f9c4cc79a81c7b97b0c1b1c4f125adf32d50641b2`.
Invariants, outcome accounting and graceful cleanup passed; warmup and measured
windows both had zero retries, terminal failures and unknown commits.

| Run order | Target | TPS | p50 ms | p95 ms | p99 ms | Server CPU ms/commit | Client CPU ms/commit |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | River | 383.52 | 2.570 | 3.690 | 4.028 | 2.261 | 1.174 |
| 2 | MariaDB | 919.65 | 1.069 | 1.513 | 1.651 | 0.691 | 1.004 |
| 3 | MariaDB | 937.79 | 1.053 | 1.485 | 1.564 | 0.687 | 0.996 |
| 4 | River | 375.36 | 2.601 | 3.807 | 4.448 | 2.554 | 1.188 |

The arithmetic means of the two run TPS values are River 379.44 and MariaDB
928.72: a descriptive 2.45× MariaDB/River ratio, not a confidence interval or
formal performance claim. River server CPU/commit spans 2.26–2.55 ms versus
MariaDB 0.687–0.691 ms; client CPU spans 1.17–1.19 versus 0.996–1.004 ms. The
current controls establish persistent extra server-process work under these
transports, without assigning it all to engine code.

The earlier 20s/30s River control was 378.45 TPS and 2.38 ms server CPU/commit.
Those values lie within the current samples' range. These observations do not
establish a throughput improvement from the changes. They also cannot isolate
a CRC-specific effect because the historical executable predates other changes.

### Native reports and accounting

Report IDs below are under `/Users/blater/src/ingres/river-harness/runs/`.
Attempts equal commits + expected rollbacks + failures + unknown commits +
cancellations + retries in both phases. CPU bracket timestamps and individual
read intervals are retained in each capture JSON.

| Label | Report ID | Attempts | Commits | Expected rollbacks | Cancelled | CPU start/end offset ms |
| --- | --- | ---: | ---: | ---: | ---: | --- |
| `current-r1a` | `river_harness_20260915_175332_178c85de` | 11610 | 11506 | 103 | 1 | 0.05 / 14.56 |
| `current-m1a` | `river_harness_20260915_175459_6e6e2859` | 27838 | 27590 | 247 | 1 | -0.10 / 11.81 |
| `current-m1b` | `river_harness_20260915_175622_ea36865d` | 28389 | 28134 | 255 | 0 | -0.09 / 12.11 |
| `current-r1b` | `river_harness_20260915_175740_baa7f096` | 11360 | 11258 | 101 | 1 | -8.16 / 13.28 |

Collector, commands, logs and captures are under
`/private/tmp/river-gothmog-current/`. `audit.py` checks native outcomes, effective
settings, comparison keys and CPU capture errors; `audited-metrics.json` retains
individual normalized results. It is a temporary analysis aid, not a comparator
product or new repository measurement gate.

## Four-worker contention findings

All three current four-worker runs failed through retry exhaustion. They passed
post-run invariants and graceful cleanup with zero unknown commits. Their TPS is
retained in native artifacts but excluded from successful-throughput ranking.
The measured failures remain primary evidence, alongside the earlier 135/126
River and 359 MariaDB failures at the original 5-second warmup.

| Target/run | Commits | Attempts | Retries | Failed | Failed/logical outcomes | Retries/commit | p99 ms (all outcomes) |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| current-r4a | 12727 | 18624 | 5651 | 123 | 0.948% | 0.444 | 24.478 |
| current-m4 | 38366 | 54330 | 15223 | 360 | 0.921% | 0.397 | 9.871 |
| current-r4b | 12305 | 18043 | 5501 | 115 | 0.917% | 0.447 | 25.297 |

The failure denominator is attempts minus retries, including deadline
cancellations. Both targets fail near 1% of terminal logical outcomes in this
configuration; this small block does not establish a target ordering in failure
probability. Retries/commit describe wasted attempts without inventing the cost
of each attempt. Latencies include failed, expected-rollback and cancelled
outcomes and remain descriptive.

| Label | Report ID | Expected rollbacks | Cancelled | Warmup failures/retries | Server/client CPU ms/commit | CPU start/end offset ms |
| --- | --- | ---: | ---: | --- | --- | --- |
| `current-r4a` | `river_harness_20260915_175859_41835cbf` | 119 | 4 | 60 / 3082 | 4.468 / 2.043 | -8.41 / 14.62 |
| `current-m4` | `river_harness_20260915_180045_e69bc4ea` | 377 | 4 | 255 / 10778 | 1.192 / 1.290 | -0.17 / 11.78 |
| `current-r4b` | `river_harness_20260915_180201_653438b6` | 118 | 4 | 58 / 2991 | 4.650 / 2.102 | -8.95 / 14.81 |

CPU/commit here includes failed/retried work and is not a fair per-success service
cost comparison. The collector's failed-run path did not recognize the harness's
`run failed; evidence:` line; its raw captures still contain CPU snapshots.
`audit.py` resolves that path from the retained log and joins the untouched
native result/environment/validation files. No failed run was rerun or discarded
because of this capture limitation.

The shared executor acquires stock rows in generated input order, holding write
locks until completion. Opposite row orders can create a cycle across districts;
READ COMMITTED does not prevent it. Both targets' retained deadlock exemplars
and River's controlled opposite-order versus same-order SQL test support this
mechanism. They do not reconstruct every benchmark victim or establish a
River-only detector defect. Retries reuse the same input with 200/400/800 µs
backoff; increasing the retry limit changes the workload without resolving its lock ordering.

## Current measured-window profile

The separate River profile passed at 367.58 TPS, p99 4.80 ms and approximately
2.72 ms server CPU/commit. The unprofiled River controls were 375.36–383.52 TPS
and 2.26–2.55 ms CPU/commit. The profile is kept out of throughput ranking;
observer overhead and run variation are unresolved. Its report is
`river_harness_20260915_180329_87698068`: 11,124 attempts, 11,025 commits,
98 expected rollbacks, one cancellation, zero retries/failures/unknowns.
CPU start/end offsets were -6.57/+14.57 ms; invariants and cleanup passed.

The measured interval starts at 2026-09-15T18:03:51.994870Z and spans
29.993682 seconds. Timestamp filtering retains **896 Java execution samples**
and excludes four outside the interval. These are sample counts, not invocation
counts, process CPU fractions or predicted TPS gains.

| Leaf method | Samples | Java sample share |
| --- | ---: | ---: |
| `BTreePage.childForKey` | 48 | 5.36% |
| `IndexedPageFrameMap.find` | 32 | 3.57% |
| `TupleKeyCodec.compare` | 24 | 2.68% |
| `LockIntervalOrder.compare` | 24 | 2.68% |
| `BTreePage.lookupLeaf` | 18 | 2.01% |
| `LockTupleBytes.compare` | 15 | 1.67% |
| `Utf8TextDecoding.decode` | 14 | 1.56% |

All 48 `BTreePage.childForKey` leaf samples come from `IndexedTreeLookup.find`.
The current implementation linearly scans sorted internal-node separator keys
in `river-storage/.../btree/BTreePage.java:124`. `lookupLeaf` in that same file
already uses binary search. This is a concrete algorithmic candidate, rather
than an inference that every index access is slow. `IndexedPageFrameMap.find`
and tuple-key/lock comparisons also consume samples, but their counts do not
prove excessive hash collisions or unnecessary locks.

`FormatBytes.checksum` has six current leaf samples (0.67%), versus 64/893 in
the historical profile. The root-record CRC path was deleted from current
code. These samples support that the formerly prominent checksum work has
receded; profiles of different builds do not measure an isolated optimization
speedup. The current single-worker TPS range still overlaps the historical
20-second-warmup control.

Artifacts in `/private/tmp/river-gothmog-current/`: `current-r1-profile.jfr`,
`cpu-samples.json`, `profile-summary.py`, `cpu-sample-summary.json`, custom
`profile.jfc`, JFR start/stop logs and native workload captures.

## Ranked recommendations and decisions

| Rank | Finding and confidence | Decision | Owner and smallest next correctness/performance probe |
| ---: | --- | --- | --- |
| 1 | River's single-worker process cost remains much higher; linear internal-node routing is the leading sampled Java leaf method and is confirmed by source. High confidence in the mechanism; its contribution to the full gap is unknown. | **Implement next:** replace scalar B-tree internal-node linear routing with binary search. | river-storage B-tree owner. Cover empty/first/last/equal separators, namespace ordering, splits and recovery; then the same one-worker New-Order controls. Preserve allocation-free traversal and equality-to-right-child semantics. |
| 2 | Stock deadlock retry exhaustion reproduces in current River (123/115 failures) and MariaDB (360); normalized failure rates are about 0.92–0.95%. High confidence in a shared contention boundary, not a River-only detector defect. | **Implement a focused harness fix next:** acquire overlapping stock rows in a consistent `(warehouse,item)` order while preserving logical input/order-line semantics. Preserve duplicate-item and original order-line semantics, version the changed harness binding, and keep original failures as the baseline. | river-harness transaction executor, independently reviewed with River transaction owner. Controlled opposite-order schedule plus same four-worker R/M/R block; unchanged retries and isolation. Do not raise retries to make the original failure disappear. |
| 3 | Broad checksum removal did not produce an established end-to-end gain in this evidence. Remaining sampled costs are distributed across lookup, lock bookkeeping, key comparison and text/copy work. | **Defer** further checksum/integrity reductions and speculative lock/cache/protocol rewrites. | Runtime/performance lead. After the routing change, choose the next mechanism from current profiles and transaction-step evidence; match transport before making engine-only attributions. No generic telemetry project is required. |

These recommendations complete this bounded investigation; they are not claims
that all of the 2.45× throughput difference has been explained. The accepted
checksum changes remain a user-directed integrity/performance tradeoff, with
correctness tests and independent review recorded in the mechanism note.

## Coverage, limits and completion audit

- **Implemented task:** page/root CRC changes and live/replay ordering fix; six
  affected suites passed (1,300 tests), followed by focused tests after final
  simplification. Current packaged JVM successfully ran the real authenticated
  server path. Implementation delivery is tracked in
  [tic-fine-barad-dur](../../tickets/tic-fine-barad-dur.md); no installed user
  executable or existing database was replaced.
- **One-worker comparison:** four fresh current samples, exact workload/isolation
  match, common eligible key, individual latencies/CPU/outcomes retained.
- **Other findings:** current matched contention control and exact River repeat,
  normalized failures, source lock-order trace, controlled SQL test and cleanup.
- **Attribution:** current process CPU, separate measured-window JFR, source-level
  verification of the leading algorithm, and bounded ranked decisions.
- **Unmeasured categories:** no matched MariaDB Performance Schema statement/wait
  deltas, complete WAL force/byte attribution, allocation/commit, physical-I/O or
  cache-hit accounting. MariaDB buffer-pool/log-buffer settings were not retained;
  resource budgets are therefore not equalized beyond recorded configuration.
  These limits preclude an exhaustive decomposition of the cross-system gap.
- **Excluded scope:** all-family/full-cardinality, sustained checkpoints, recovery
  certification, platform matrix and formal comparator/claim campaign. They were
  explicitly outside this ticket; this closeout does not certify them.

## Independent acceptance

`strategy_adversary` accepted this report for diagnostic closeout after checking
all eight native reports against the audited metrics and reviewing the current
profile/source. No blocking corrections remained. Two refinements were adopted:
version the sorted-stock binding and preserve duplicate/original-line semantics;
retain client capacity as a limit. MariaDB client CPU is about 0.92–0.93 CPU-seconds
per wall-second in the one-worker controls, which suggests a possible client
constraint but does not prove saturation or a server capacity ceiling.

A final process check found none of the retained benchmark-owned client/server
PIDs running. This closes the comparison investigation with evidence paths;
implementation delivery is tracked in
[tic-fine-barad-dur](../../tickets/tic-fine-barad-dur.md). The binary-routing and
stock-order recommendations remain future work.
