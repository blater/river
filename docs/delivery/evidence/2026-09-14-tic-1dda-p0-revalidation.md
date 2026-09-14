# P0 revalidation on the accepted concurrency fixture

Status: predeclared campaign; no passing P0 claim.
Runtime source: `5dcee338fdd8af5abfb2cab84129443cff2b451c` (published master).
Existing claim resumed: `ticket/tic-1dda-p0-revalidation`; the September 4
claim and failed evidence remain historical. All three prerequisites are closed.
The treebeard policy withdrawal is integrated before freezing this runtime.

## Fixed campaign and decision

Run the accepted b1b7 SQL and Payment/New Order reproducer tests on this source.
Then execute exactly five drift-balanced blocks of the existing eight cells:
New Order/Payment 50/50 and standard, each at 2, 3, 4 and 10 terminals. Odd
blocks use ascending terminals with discriminator then standard; even blocks
use descending terminals with standard then discriminator. No extra cell or
profile is admitted. The first cell is also the standalone lifecycle check.

Every cell uses fresh tiny data, warehouse 1, seed 42, load batch 32, maximum
attempts 32, no-wait-stress scheduling, diagnostic evidence, and SERIALIZABLE
for both JDBC and programs. Keep existing resource budgets and retry defaults.
JFR is disabled. Run only one workload at a time, with no overlapping build or
other database workload. Record branch/version, exact command, source, all raw
results, invariants and cleanup. No descriptors, hashes, leases or receipt
framework is added. The accepted mixed-isolation test remains diagnostic only.

Warmup is 5 seconds and measurement is 30 seconds, declared before sampling to
reduce the variance of the historical 1s/10s windows. This changes no matrix
cell, workload semantics, retry policy or acceptance rule. Do not compare these
TPS figures directly with historical differently timed samples or the external
harness; this is a within-campaign concurrency-scaling decision.

Reuse the historical paired within-block natural-log TPS ratios for 3:2, 4:2,
10:2, 4:3 and 10:4, separately by mix. Report geometric means and individual
two-sided 95% Student-t intervals (five pairs, four degrees of freedom).
There is no introduced numerical noninferiority/noise margin: upper bound below
1 detects regression; lower bound at least 1 supports non-regression; an
interval crossing 1 is inconclusive and cannot pass. These are individual
intervals, not a simultaneous/global 95% guarantee. Retain all anomalies; do not
stop a block or discard a sample because TPS falls.

Stop immediately on a correctness/cleanup failure or invalid causal evidence,
retaining every begun sample. Before proceeding to each next cell verify run
completion, invariants, effective isolation, zero unknown/failed/exhausted
outcomes, zero timeout/liveness/diagnostic-overflow failures, scheduler-produced
block classification, exact victim/outcome/cancellation/retry reconciliation,
and zero final transactions, locks, waiters and retained snapshots. Every novel
cycle/outcome requires explanation. Separate intentional preflight victims from
warmup and measured-plus-drain epochs. Warehouse-read retries must be zero;
retries/commits must not exceed 5% per cell, with outcome counts also normalized
by attempts. No production/tool change may repair a failed cell in this ticket.

Only a complete passing campaign proceeds to the existing clean integration
checkpoint and P0 closure. Otherwise retain the first failure or inconclusive
result and keep dependent WAL work gated. No lock-removal or force-overlap work
starts by treating an open prerequisite as completed.

## Commands and evidence

Use JDK 25 at `/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home`.
Build/install and run the accepted reproducible fixtures with `--no-daemon`,
`GRADLE_USER_HOME=/private/tmp/river-b1b7-gradle` and
`--project-cache-dir /private/tmp/river-p0-cache` in the clean P0 checkout.

```sh
./gradlew --no-daemon :river-engine:test \
  --tests io.riverdb.engine.sql.SqlGeneralConcurrencyTest \
  --tests io.riverdb.engine.sql.SqlConcurrencyIsolationTest \
  :river-bench:test --tests io.riverdb.bench.tpcc.TpccConcurrencyReproducerTest \
  :river-bench:installTps

tools/tps-test.sh --version=p0-20260914-5dcee338 \
  --profile=tiny --mix=MIX --terminals=TERMINALS \
  --scheduling=no-wait-stress --evidence=diagnostic --fresh-load=true \
  --warehouses=1 --batch-rows=32 --maximum-attempts=32 \
  --warmup-seconds=5 --measured-seconds=30 --seed=42 --isolation=serializable \
  --deadlock-diagnostics-bytes=8388608 --deadlock-diagnostics-epochs=4 \
  --deadlock-diagnostics-signatures-per-epoch=64 \
  --deadlock-diagnostics-events-per-epoch=16384 \
  --deadlock-diagnostics-exemplars-per-signature=1 \
  --deadlock-diagnostics-maximum-cycle-edges=16 \
  --sample-id=BLOCK-CELL --output-dir=EVIDENCE/BLOCK-CELL
```

Evidence root: `/Users/blater/src/river/benchmark-results/p0-20260914/`.
Independent concurrency/performance review approved the finite campaign and
confirmed the former fixture/diagnostic readiness gaps are resolved. The
osgiliath checkpoint-cause investigation remains separate and unresolved;
normal campaign success cannot establish the historical kernel-stall cause.
