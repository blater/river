---
id: tic-voronwe
status: open
type: investigation
priority: 2
assignee: blater
delivery: evidence
tags:
    - performance
    - diagnostic
created: 2026-09-16T13:25:17.49765Z
---
# Explain CPU variation across complexity checkpoints

Retain and investigate the cumulative throughput/p99 signal and isolated daemon CPU-per-commit signal observed during tic-thuringwethil. The complexity refactor is accepted as maintainability work, with no performance-neutrality or optimization claim.

## Acceptance Criteria

A bounded same-workload investigation identifies a supported mechanism or explicitly records what remains inconclusive; retains all adverse samples, eligibility/accounting/cleanup evidence, and gives one concrete next decision. No cross-database matrix or benchmark infrastructure expansion.

## Evidence and boundaries

- Completion source runtime: `deb8da4c`; full complexity checkpoint evidence is
  in [performance-checkpoints.md](../performance-checkpoints.md), section
  "Complexity completion and periodic performance checkpoint 3".
- Earlier cumulative signal: `ff6ed6f9` versus `fa6f069a`, retained at
  `/private/tmp/river-complexity-perf-2` and `...-2-long`; SQL/engine/prequeue
  probes did not establish a cause. Do not conflate it with daemon-only results.
- Daemon-only evidence: `/private/tmp/river-complexity-perf-3`,
  `/private/tmp/river-complexity-final-profile`, and
  `/private/tmp/river-complexity-daemon-long`. Only the app jar differs; all
  transaction-path jars are identical. Short candidate CPU averaged +5.8%;
  longer unprofiled observations were also adverse and remain retained.
- Identical candidate binary: `/private/tmp/river-complexity-identical-control`.
  CPU varied +6.05% without source/jar changes. This demonstrates comparable
  variability, not performance neutrality or a root cause.
- All runs passed invariants, eligibility, accounting, and cleanup. Keep raw
  artifacts and distinguish 30s/60s, profiled/unprofiled, and actual run order.

## Next bounded investigation

Start with one hypothesis about runtime/host variation; establish an identical-
binary control before changing code. Retain process CPU and both client/server
costs; use the existing JFR configuration and aggregate or streaming analysis.
The prior whole-event JSON export was too large and may have perturbed later host
state; avoid materializing full stack-rich recordings. JFR thread-CPU and
allocation samples are incomplete estimates, not exact accounting. Do not
attribute gaps to the daemon merely from timing labels: changed methods are
startup/control/shutdown code, absent from the candidate transaction samples.

Stay on the same sample new-order, one-worker READ COMMITTED workload with
identical durability, heap, JDK, seed, and harness mechanics. No cross-database,
warehouse/worker sweep, new observability framework, or unrelated optimization
is required. A supported cause or an explicit inconclusive boundary with one
next decision is a valid investigation outcome.
