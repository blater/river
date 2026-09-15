---
id: tic-gothmog
status: closed
type: investigation
priority: 2
assignee: blater
delivery: evidence
evidence:
    - docs/delivery/evidence/2026-09-15-tic-gothmog-transaction-costs.md
    - docs/delivery/evidence/2026-09-15-tic-gothmog-mechanisms.md
    - docs/delivery/evidence/2026-09-15-tic-gothmog-current-comparison.md
tags:
    - performance
    - tpcc
    - diagnostic
links:
    - tic-da4e
    - tic-45a7
    - tic-1dda
    - tic-fine-barad-dur
created: 2026-09-15T16:39:59.394367Z
---
# Locate River versus MariaDB TPC-C-derived transaction costs

Identify the largest supported explanation for a repeatable transaction-cost gap,
then recommend one implement/defer/reject decision with its owning subsystem and
next correctness probe. No repeatable gap, or an inconclusive result naming the
missing evidence, is a valid outcome. Conclusions apply only to sample new-order at one/four workers
and any explicitly measured control, not TPC-C overall. This ticket was opened
at the user's request after the backlog reset;
it does not reactivate parked tickets or establish a wider delivery priority.

Use the installed-server `~/src/ingres/river-harness/benchmark`, current available
telemetry, and one host. This is an evidence investigation: no production
optimization, generic instrumentation framework, comparator implementation,
platform matrix, warehouse sweep, or formal performance claim. The workload is
TPC-C-derived engineering evidence, never audited `tpmC`.

## Design

### Matched baseline

1. Record executable versions/revisions, JVM/native mode, JDK where applicable,
   resource budgets, effective isolation and durability, and transport/TLS for
   each target. Use an identifiable stable River build; do not silently include
   uncommitted descriptor changes. Reuse existing harness admission and artifacts.
   Match workload/schema semantics and identify adapter/statement differences.
   Both workloads must explicitly use READ COMMITTED with the declared FOR UPDATE
   locks. Check the per-transaction executor configuration and admission evidence;
   a server/session default is not the effective transaction isolation. An actual
   isolation mismatch makes a cross-target pair ineligible.
   If isolation/durability cannot be reconciled, do not infer an engine-efficiency
   ratio. Transport differences must be disclosed and attributed separately.
2. Run sample `new-order`, one warehouse, seed 42, retries 3, warmup 5s, measurement
   30s. Start with one worker, then four workers. For each worker count execute
   River/MariaDB/MariaDB/River sequentially: eight baseline runs in total.
   Use fresh harness-owned instances with the same data/cache preparation policy.
   Warmup must cover JIT/cache settling; if the window is still trending, extend
   warmup equally for both targets before using the pair.
3. Require passed status, zero failed/unknown outcomes, successful invariants and
   owned-resource cleanup, and eligible matching comparison keys where emitted
   before ranking successful throughput. Failed runs remain primary diagnostic
   evidence: retain terminal failures, retries, commits, cancellations, error
   exemplars and cleanup outcomes. Keep expected rollbacks distinct. If retries
   exhaust, investigate the contention finding; do not rank its TPS or silently
   increase retry limits. A matched other-target control and an exact same-target
   repeat may replace the remaining baseline to establish reproducibility.
4. Lengthen one ambiguous configuration to an interleaved 60s block if necessary.
   If attribution remains uncertain, record inconclusive and the smallest next
   experiment. Do not keep widening runs until a desired answer appears.

Example single-target commands (execute sequentially, once per required sample):

```sh
~/src/ingres/river-harness/benchmark run river tpcc sample new-order \
  --river-executable="$PWD/bin/river" --river-version=revision-label \
  --warmup=5s --duration=30s --workers=1 --warehouses=1 --seed=42 --max-retries=3

~/src/ingres/river-harness/benchmark run mariadb tpcc sample new-order \
  --warmup=5s --duration=30s --workers=1 --warehouses=1 --seed=42 --max-retries=3
```

Do not overlap workloads with builds, profiling runs, or other database workloads.
Use harness lifecycle management; do not stop a user's MariaDB service. Retain
normal checkpoint/drain/cleanup behavior. A checkpoint or shutdown failure is a
finding for the existing incident owner, not permission to disable that behavior
or resume the parked crash campaign. Diagnostic work does not depend on formal
lifecycle certification in tic-45a7 and cannot certify it or tic-1dda.

### Measurements and interpretation

Required baseline: per-run committed TPS, p50/p95/p99 end-to-end transaction
latency, attempts/retries/expected rollbacks/failures/unknowns, and measured-window
server and client process CPU deltas where obtainable with existing tools.
Verify what each latency histogram measures: attempt or logical transaction,
whether retry/backoff time is included, and whether committed, expected-rollback
or failed outcomes are represented. Compare equivalent populations; do not
assume the report field names establish equivalent latency semantics. Record
missing CPU collection explicitly; lack of one counter does not trigger a new
telemetry project. Retain individual samples rather than only averaged ratios.

Normalize CPU milliseconds, allocations, bytes and operation counts by commits
from the SAME interval; include retry work in cost per successful commit and
report attempts alongside it. Sampled allocations are estimates. Exclude startup,
load and post-measurement cleanup from workload numerators and denominators;
report cleanup separately. Process CPU includes background threads. CPU percent
and profiler sample share are not CPU milliseconds per transaction.

| Question | Targeted evidence, only when available or needed |
| --- | --- |
| Extra execution work? | CPU/commit and CPU stacks; statement count/time; rows examined, index/page accesses; repeated parsing/binding or validation. |
| Client or protocol bottleneck? | Client CPU, requests/bytes per transaction, server socket waits, transport and prepared-statement differences. |
| Contention or serialization? | One-to-four-worker change, lock/queue waits, retries/deadlocks, and time holding locks across commit. |
| Durability or write amplification? | Commit latency, WAL/redo bytes and force calls per commit, commits per force, force duration and checkpoint activity. |
| Cache/storage or memory cost? | Physical I/O and latency, available cache-hit/miss evidence, memory budget/RSS, River allocation/commit and GC pauses. |

The same counter name need not mean the same thing across databases. Treat logical
row counts and physical page accesses separately; label counters by their actual
scope. Concurrent wait totals overlap and cannot be summed into elapsed transaction
latency. CPU profiles omit waiting; virtual-thread waits may be absent from JFR.
No visible sample is not proof of no cost. Short sample data cannot establish
full-cardinality access-path or sustained checkpoint efficiency.

### One targeted follow-up

If neither worker count shows a repeatable gap, close with that bounded result
and skip profiling. Otherwise select one follow-up from the baseline, rather
than requiring every metric above:

- A single-worker gap points first to execution, protocol, or commit service cost.
- A gap growing with four workers points first to contention, serialization or
  batching, while checking whether the client itself is saturated.
- If writes/commit appear dominant, run one matched `order-status` control at the
  implicated worker count. Its different SQL makes this supporting evidence,
  not a subtraction that measures WAL cost.
- Otherwise profile the implicated `new-order` configuration. Use existing River
  JFR and mechanism telemetry, and MariaDB Performance Schema statement/wait/I/O
  summaries. Map statements to the same logical transaction step where possible.
  Take counter deltas; keep profiling separate from baseline rankings and compare
  against an adjacent unprofiled control to expose observer overhead.

Stop after that bounded follow-up and rank at most three explanations by measured
absolute cost where available, evidence confidence, and plausible addressable
contribution. Qualitative rankings are sufficient when costs cannot be estimated;
do not invent additive contributions from overlapping waits or sampled profiles.
Name one next mechanism or one missing observation. Any new instrumentation or full-profile,
all-family, or sustained-checkpoint campaign becomes a separately scoped follow-up.
The descriptor optimization specifically requires matched River before/after
CPU-cost evidence; this cross-database ticket does not certify that change.

### Execution steering — 2026-09-15

The user explicitly requires comparable effective isolation across targets and
emphasized that retry exhaustion is a significant finding, not discarded evidence.
After the first River/four-worker run recorded 135 terminal failures and 5,742
retries, the lead selected one unchanged MariaDB/four-worker control followed by
one exact River/four-worker repeat. Preserve the failure evidence and distinguish
within-run repeated events from cross-run reproducibility. No isolation, retry,
cardinality or executable change is authorized by this diagnostic adaptation.

### Adversarial strategy review — 2026-09-15

Independent reviewer: `strategy_adversary`. Verdict: scope and major cost coverage
are reasonable; approve after three small revisions. Applied: explicit latency
population/retry semantics; no-repeatable-gap closeout without mandatory profiling
and conclusions limited to the tested workloads; shell-safe version placeholder.
Also accepted the optional improvement allowing qualitative attribution where
absolute costs cannot be established. The review added no runs or infrastructure.
The reviewer rechecked the revised strategy and approved it with no remaining
blocking issues. This reviews the strategy, not execution evidence; the ticket
stays open.

## Acceptance Criteria

- Record baseline manifests, individual outcomes, artifact paths, counter windows,
  effective settings, and any semantic/transport/instrumentation limitations.
- Explain the one-versus-four-worker result and the selected follow-up (or why
  no follow-up was warranted), with
  evidence distinguishing CPU work, waiting, and client overhead as far as observed.
- Produce one short evidence note with a ranked explanation table and one reviewed
  implement/defer/reject, no-repeatable-gap or inconclusive decision. Reuse native
  artifacts; bulky profiles stay outside Git. No dashboard, synthetic score, source fingerprints,
  host leases, terminal receipts, or new measurement-validity framework.
- A runtime/performance lead integrates the evidence; an independent adversarial
  reviewer checks attribution and scope. Evidence closeout follows the repository's
  normal evidence-commit rules. Do not implement fixes or close other campaigns.

### References

- [Existing attribution and observability limits](tic-da4e.md).
- [Repository measurement and harness contract](../../AGENTS.md).
- [MariaDB statement digest metrics](https://mariadb.com/docs/server/reference/system-tables/performance-schema/performance-schema-tables/performance-schema-events_statements_summary_by_digest-table).

## Notes

### 2026-09-15T18:10:10Z

Accepted diagnostic closeout, 2026-09-15. Final evidence: [current-build comparison](../delivery/evidence/2026-09-15-tic-gothmog-current-comparison.md), with historical runs and implementation evidence linked above.

The user directed page/root CRC changes after the initial investigation, then requested current reruns. Current one-worker ABBA passed: River 383.52/375.36 TPS; MariaDB 919.65/937.79 TPS. Effective READ COMMITTED, workload, retries and timing match; transport and resource limitations remain explicit. Zero one-worker retries/failures/unknowns. Current four-worker River retry exhaustion reproduced with 123/115 measured failures; MariaDB had 360. All eight current runs passed invariants and owned cleanup; failed TPS remains excluded from ranking. The original River 135/126 and MariaDB 359 failures remain primary historical evidence.

Current profile: linear internal-node routing led Java leaf samples (48/896). Recommend binary routing next in river-storage; consistent stock acquisition order in a versioned harness binding, preserving duplicate/original-line semantics; defer further broad integrity reductions and speculative rewrites. No full-gap or formal performance claim is made. Current local code passed 1,300 affected-module tests and final focused recovery checks; it remains uncommitted.

Independent reviewer strategy_adversary accepted after checking all eight native artifacts, current profile and source. Adopted refinements: version the sorted-stock workload change and retain possible client-capacity limits without asserting saturation. Evidence-path delivery closes this investigation; no parked campaign or proposed optimization is marked delivered.

