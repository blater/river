# River delivery Kanban and priority queue

This document is River's human-readable delivery queue. Ticket front matter is
authoritative for status, ownership, dependencies, and acceptance criteria.
This view adds the sequencing decision needed when several tickets are ready at
the same time; it must not copy or weaken a ticket's contract.

The queue is current at the revision containing this document. Refresh it when
a listed ticket changes state, a dependency changes, or evidence changes which
mechanism should be pursued next.

User-directed focused work: [`tic-e544`](tickets/tic-e544.md) is closed at the
pushed `perf-checkpoint-20260906-read-durability-dependencies` checkpoint.
Observed read dependencies passed the clean gate and independent review; longer
interleaved local samples show about 9.5% higher TPS than the later controls and
a lower Order Status p99. The performance ledger retains the neutral short pair,
all controls and remaining per-index conservatism. This does not displace the
broader P0/P1 frontier below. [`tic-186e`](tickets/tic-186e.md) remains the
preceding catalog-resolution checkpoint.

## Board

| Lane | Now: ready work | Next: unlocked by Now | Later: promotion path |
| --- | --- | --- | --- |
| Standalone `riverd` | [`tic-72ea`](tickets/tic-72ea.md) and [`tic-615d`](tickets/tic-615d.md) in parallel; [`tic-11a5`](tickets/tic-11a5.md) ratified accepted ADR 0014 | [`tic-ec50`](tickets/tic-ec50.md): deliver the installable authenticated lifecycle | Safe operations, external consumer migration, then [`tic-45a7`](tickets/tic-45a7.md): certify the benchmark lifecycle prerequisite |
| Transaction performance | [`tic-2828`](tickets/tic-2828.md) is closed with warmed page-generation reuse and a passing joint engine/clean-test gate; finish delivery acceptance for the independent `tic-288d`, `tic-e2be`, `tic-50e8`, and `tic-5cc0` baseline tickets; then [`tic-af29`](tickets/tic-af29.md): classify successful lock blocking and [`tic-8e74`](tickets/tic-8e74.md): expose the independent snapshot-cleanup gauge; [`tic-0636`](tickets/tic-0636.md) is closed with accepted v2 provenance; [`tic-d7c2`](tickets/tic-d7c2.md) owns the remaining exclusive-host evidence change | Resume [`tic-1dda`](tickets/tic-1dda.md) only after all four scoped P0 evidence prerequisites close; rerun the serializable P0 matrix, including the mixed-isolation reproducer | After P0, lead with the [`tic-b368`](tickets/tic-b368.md) durability-overlap design and [`tic-4d14`](tickets/tic-4d14.md) lock-scope audit; re-baseline existing logical/WAL mechanisms before editing them; then admit cumulative cohorts, remove one proved redundant holding rule, implement only a real pre-force overlap mechanism, run P1 promotion, and proceed to the Payment A/B |
| Stress and comparison | No River promotion work until [`tic-45a7`](tickets/tic-45a7.md) closes; `tools/tps-test.sh` remains available for River diagnostics | Verify `river-harness` uses the published `riverd` process contract; establish the independent artifact-comparison sidecar | [`tic-c7bb`](tickets/tic-c7bb.md): 500 committed TPS; then [`tic-9c58`](tickets/tic-9c58.md): MariaDB/PostgreSQL comparison and Alpha3 parity |
| Workflow safety | [`tic-701f`](tickets/tic-701f.md) and [`tic-dd80`](tickets/tic-dd80.md) may proceed when they do not displace P0 product work | Atomic cross-worktree claims and promotion enforcement | Close [`tic-ef07`](tickets/tic-ef07.md) when both enforcement gaps are proved |

The two P0 product lanes may proceed concurrently in separate worktrees. Builds,
profilers, and benchmarks remain serialized on the shared host. Workflow work
is non-blocking support work and must not become a substitute for delivery.

## Ordered critical paths

### A. Make `riverd` the supported benchmark lifecycle

1. The [`tic-a221`](tickets/tic-a221.md) audit design and parallel
   [`tic-de1d`](tickets/tic-de1d.md) boundary inventory are closed.
2. [`tic-11a5`](tickets/tic-11a5.md) is closed after independent acceptance of
   the one ADR 0014 lifecycle/security contract.
3. Implement resource-accounted audit durability in
   [`tic-72ea`](tickets/tic-72ea.md) and instance identity/credentials in
   [`tic-615d`](tickets/tic-615d.md) (**Now**). These may proceed in parallel after the ADR;
   `tic-615d` creates the non-empty app boundary with its real security consumer.
4. Complete authenticated start/restart in [`tic-ec50`](tickets/tic-ec50.md),
   migrate every River caller including diagnostics, and delete all plain APIs;
   then prove the distribution and no-plain-path gate in
   [`tic-95e8`](tickets/tic-95e8.md).
5. Deliver exact stop and instance discovery in
   [`tic-0803`](tickets/tic-0803.md) then [`tic-d2e9`](tickets/tic-d2e9.md). Audit archive and
   credential renewal in [`tic-b901`](tickets/tic-b901.md) may proceed after the audit
   and executable lifecycle exist.
6. Run the operational/recovery gate in [`tic-9640`](tickets/tic-9640.md).
7. Publish the process/file consumer contract in [`tic-4cb6`](tickets/tic-4cb6.md),
   preserve authenticated River diagnostics in [`tic-3f57`](tickets/tic-3f57.md), and
   verify the external harness migration in [`tic-bfca`](tickets/tic-bfca.md).
8. Certify the complete prerequisite in [`tic-45a7`](tickets/tic-45a7.md).

No harness-based River promotion or cross-engine comparison starts before step
8. This does not block focused engine work or River-specific diagnostics.

### B. Remove the measured transaction ceilings

1. The first [`tic-1dda`](tickets/tic-1dda.md) revalidation found a statistically
   detected standard-mix 10:2 regression and missing promotion evidence.
   Warmed page-generation reuse in [`tic-2828`](tickets/tic-2828.md) is now
   closed at the pushed `perf-checkpoint-20260906-page-generation-reuse`
   checkpoint, with all 981 engine tests and the clean full test build passing.
   Existing source/bytecode policy failures remain recorded. Complete the
   independent baseline deliveries: group-commit fault fencing in
   [`tic-288d`](tickets/tic-288d.md), the stale spill-test boundary in
   [`tic-e2be`](tickets/tic-e2be.md), UNION execution in
   [`tic-50e8`](tickets/tic-50e8.md), and savepoint resource admission in
   [`tic-5cc0`](tickets/tic-5cc0.md). None depends on another. The independent
   fixes must meet one joint module gate before affected-module acceptance for
   the successful-block classifier in [`tic-af29`](tickets/tic-af29.md) and the
   separately owned terminal snapshot gauge in
   [`tic-8e74`](tickets/tic-8e74.md). Exact built-byte provenance and cooperative
   host observations in [`tic-0636`](tickets/tic-0636.md) are accepted and closed.
   Complete the remaining exclusive-host change in
   [`tic-d7c2`](tickets/tic-d7c2.md) against that existing mechanism, then resume
   the full P0 matrix. The promotion investigation consumes those four
   capabilities and does not implement or repair them. The engine-diagnostics
   and tooling tracks may proceed concurrently only with disjoint file
   ownership; deliveries that share a diagnostics surface or
   `tools/tps-test.sh` are serialized.
   Correctness requires matching serializable isolation, explained retries,
   blocks and cycles, complete cleanup, and no timeout or liveness failure;
   TPS remains a separate regression guard.
2. After P0, pursue three evidence-linked streams. The design and audit are
   the first P1 decisions; the preparation and WAL stories must begin by
   identifying the exact missing behavior in current source rather than
   recreating mechanisms that are already present:

   - design durable visibility and fencing in [`tic-b368`](tickets/tic-b368.md),
     including the pre-force transition that lets a blocked successor make
     progress under an explicit durability dependency;
   - re-baseline the existing logical sealing and chunked WAL paths against
     [`tic-ca05`](tickets/tic-ca05.md) and [`tic-6f81`](tickets/tic-6f81.md), then
     close either ticket without production changes when current source already
     satisfies it; make any proved missing contract a separately scoped code
     ticket before implementation; reserve cumulative cohort demand in
     [`tic-5b3e`](tickets/tic-5b3e.md) as a scalability and failure-safety
     prerequisite rather than an independent TPS claim;
   - audit lock scope in [`tic-4d14`](tickets/tic-4d14.md), then remove only holdings
     proved redundant in [`tic-845d`](tickets/tic-845d.md). Before implementation,
     narrow `tic-845d` to exactly one audit-selected rule; every additional
     candidate is a separate ticket. A predicted fall in lock work with the
     singleton-force ceiling becoming dominant is an acceptable bottleneck shift.
3. Implement safe durability overlap and exact publication in
   [`tic-f1bb`](tickets/tic-f1bb.md) after visibility design and WAL chunking are
   complete. Keep this one atomic vertical implementation rather than splitting
   unsafe module-local states. Do not implement another append-force-publish path: the accepted
   design must make dependent progress possible before the predecessor's force
   returns and must reduce the force-per-write or cohort-size denominator. If
   it cannot, reject the throughput mechanism rather than retain complexity.
4. Run the P1 promotion matrix and publish a stable checkpoint in
   [`tic-7a5a`](tickets/tic-7a5a.md) after both commit and lock streams pass.
5. Prove complete Payment semantics in [`tic-00e1`](tickets/tic-00e1.md), run the
   one-program-request A/B in [`tic-af0a`](tickets/tic-af0a.md), then capture the
   inclusive measured-phase profile in [`tic-da4e`](tickets/tic-da4e.md).
6. Add further P3 implementation stories only for costs demonstrated by that
   profile. Do not invent an optimization, arbitrary cap, or expected speedup.

The production-throughput hypotheses on this path are therefore deliberately
narrow: `tic-845d` may reduce measured lock work, `tic-f1bb` must amortise
durability by an explicitly proved mechanism, and `tic-af0a` may shorten the
Payment protocol and hot-lock residence. `tic-ca05`, `tic-5b3e`, and `tic-6f81`
are preparation, scale, and failure-safety enablers; neutral TPS is acceptable
only when their named mechanism and non-regression evidence pass. Diagnostic,
profiling, and promotion tickets produce evidence rather than database speed.

Each performance story gets a clean feature-point build, focused correctness
evidence, repeated matched before/after measurements, a pushed merge, and a
recoverable checkpoint before the next mechanism is changed.
Its outcome, owning mechanism, non-goals, stop conditions, and maximum change
shape are locked under the
[`docs/tickets` scope rule](tickets/README.md#performance-ticket-scope-lock)
before implementation begins; discoveries become dependencies or follow-ups,
not additions to an in-progress ticket.

### C. Prepare both gates, promote 500 TPS, then pursue parity

The 500 TPS milestone is not ready until both the P2/P3 performance epic and
the certified `riverd` lifecycle are complete.

1. Define the exact 500 TPS contract in [`tic-8561`](tickets/tic-8561.md), after
   both prerequisites close.
2. Verify that `river-harness` automates the contract in
   [`tic-bd79`](tickets/tic-bd79.md).
3. Run the interleaved campaign in [`tic-630d`](tickets/tic-630d.md), closing
   [`tic-c7bb`](tickets/tic-c7bb.md) only on honest lower-bound evidence.
4. In parallel after `riverd` certification, classify workload compatibility
   through
   [`tic-46ec`](tickets/tic-46ec.md) and [`tic-e6c5`](tickets/tic-e6c5.md).
5. Define and verify the independent comparison sidecar through
   [`tic-61c2`](tickets/tic-61c2.md) and [`tic-e305`](tickets/tic-e305.md).
6. Establish the matched family-level gap in [`tic-7ec5`](tickets/tic-7ec5.md).
7. Run the normative parity and Alpha3 campaign in
   [`tic-a133`](tickets/tic-a133.md) only after both the matched gap and the 500
   TPS campaign complete.

`river-harness` owns stress execution and versioned per-target artifacts. The
comparison sidecar owns eligibility, pairing, statistics, confidence, ratios,
and reports. Neither comparison implementation nor harness-specific mechanics
belong in River core.

## How to refresh this board

From the repository root, inspect the source of truth before editing this
ordering view:

```sh
tk validate
tk ready --sort priority
tk blocked --sort priority
tk dep tree tic-c7bb
tk dep tree tic-9c58
```

Apply these rules when refreshing:

- `P0` outranks `P1`, but an unresolved dependency always wins over a priority
  label.
- Among simultaneously ready P0 work, advance both product critical paths when
  isolated owners are available: riverd lifecycle and transaction performance.
- Keep only genuinely actionable work in **Now**. Move newly unblocked work
  from **Next**; do not pull a later optimization around a failed gate.
- If evidence invalidates the planned mechanism, update the owning ticket and
  this queue in the same documentation delivery.
- Ticket status changes happen in ticket files through `tk`; this document
  records priority and sequencing, not a second independent status.
