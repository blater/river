# River delivery Kanban and priority queue

This document is River's human-readable delivery queue. Ticket front matter is
authoritative for status, ownership, dependencies, and acceptance criteria.
This view adds the sequencing decision needed when several tickets are ready at
the same time; it must not copy or weaken a ticket's contract.

The queue is current at the revision containing this document. Refresh it when
a listed ticket changes state, a dependency changes, or evidence changes which
mechanism should be pursued next.

## Board

| Lane | Now: ready work | Next: unlocked by Now | Later: promotion path |
| --- | --- | --- | --- |
| Standalone `riverd` | [`tic-11a5`](tickets/tic-11a5.md): ratify the lifecycle/security ADR; the [`tic-de1d`](tickets/tic-de1d.md) and [`tic-a221`](tickets/tic-a221.md) investigations are closed | [`tic-72ea`](tickets/tic-72ea.md) and [`tic-615d`](tickets/tic-615d.md) in parallel | Installable lifecycle, safe operations, external consumer migration, then [`tic-45a7`](tickets/tic-45a7.md): certify the benchmark lifecycle prerequisite |
| Transaction performance | [`tic-50e8`](tickets/tic-50e8.md): restore UNION execution acceptance; [`tic-5cc0`](tickets/tic-5cc0.md): independently restore resource-accounted SQL savepoint admission; [`tic-af29`](tickets/tic-af29.md): classify successful lock blocking; [`tic-0636`](tickets/tic-0636.md): retain exact build/run provenance | Give the confirmed wide-decimal sort and group-commit fencing regressions separate P0 tickets, then integrate all independent clean-master fixes for one green module gate before resuming [`tic-1dda`](tickets/tic-1dda.md) | P1 logical-commit preparation, visibility design, and lock-scope audit; then P1 promotion, Payment protocol A/B, measured P3 work, and the 500 TPS gate |
| Stress and comparison | No River promotion work until [`tic-45a7`](tickets/tic-45a7.md) closes; `tools/tps-test.sh` remains available for River diagnostics | Verify `river-harness` uses the published `riverd` process contract; establish the independent artifact-comparison sidecar | [`tic-c7bb`](tickets/tic-c7bb.md): 500 committed TPS; then [`tic-9c58`](tickets/tic-9c58.md): MariaDB/PostgreSQL comparison and Alpha3 parity |
| Workflow safety | [`tic-701f`](tickets/tic-701f.md) and [`tic-dd80`](tickets/tic-dd80.md) may proceed when they do not displace P0 product work | Atomic cross-worktree claims and promotion enforcement | Close [`tic-ef07`](tickets/tic-ef07.md) when both enforcement gaps are proved |

The two P0 product lanes may proceed concurrently in separate worktrees. Builds,
profilers, and benchmarks remain serialized on the shared host. Workflow work
is non-blocking support work and must not become a substitute for delivery.

## Ordered critical paths

### A. Make `riverd` the supported benchmark lifecycle

1. The [`tic-a221`](tickets/tic-a221.md) audit design and parallel
   [`tic-de1d`](tickets/tic-de1d.md) boundary inventory are closed.
2. Ratify one contract in [`tic-11a5`](tickets/tic-11a5.md) (**Now**).
3. Implement resource-accounted audit durability in
   [`tic-72ea`](tickets/tic-72ea.md) and instance identity/credentials in
   [`tic-615d`](tickets/tic-615d.md). These may proceed in parallel after the ADR.
4. Deliver authenticated start/restart in [`tic-ec50`](tickets/tic-ec50.md), then prove
   the distribution lifecycle in [`tic-95e8`](tickets/tic-95e8.md).
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
   detected standard-mix 10:2 regression and missing promotion evidence. First
   close the independent clean-master UNION execution regression in
   [`tic-50e8`](tickets/tic-50e8.md) and savepoint resource-admission regression
   in [`tic-5cc0`](tickets/tic-5cc0.md). Neither fix depends on the other. The
   full no-fail-fast baseline also confirms separate wide-decimal sort and
   group-commit fencing regressions; create distinct P0 owners for them rather
   than expanding either current ticket. All independent fixes must meet one
   joint module gate before affected-module acceptance for
   [`tic-af29`](tickets/tic-af29.md). Close `tic-af29` and
   [`tic-0636`](tickets/tic-0636.md), then resume the full P0 matrix.
   Correctness requires matching serializable isolation, explained retries,
   blocks and cycles, complete cleanup, and no timeout or liveness failure;
   TPS remains a separate regression guard.
2. After P0, pursue three evidence-linked streams:

   - design durable visibility and fencing in [`tic-b368`](tickets/tic-b368.md);
   - seal and admit immutable logical commits in [`tic-ca05`](tickets/tic-ca05.md),
     reserve cumulative cohort demand in [`tic-5b3e`](tickets/tic-5b3e.md), and append
     budget-derived WAL chunks in [`tic-6f81`](tickets/tic-6f81.md);
   - audit lock scope in [`tic-4d14`](tickets/tic-4d14.md), then remove only holdings
     proved redundant in [`tic-845d`](tickets/tic-845d.md).
3. Implement safe durability overlap and exact publication in
   [`tic-f1bb`](tickets/tic-f1bb.md) after visibility design and WAL chunking are
   complete.
4. Run the P1 promotion matrix and publish a stable checkpoint in
   [`tic-7a5a`](tickets/tic-7a5a.md) after both commit and lock streams pass.
5. Prove complete Payment semantics in [`tic-00e1`](tickets/tic-00e1.md), run the
   one-program-request A/B in [`tic-af0a`](tickets/tic-af0a.md), then capture the
   inclusive measured-phase profile in [`tic-da4e`](tickets/tic-da4e.md).
6. Add further P3 implementation stories only for costs demonstrated by that
   profile. Do not invent an optimization, arbitrary cap, or expected speedup.

Each performance story gets a clean feature-point build, focused correctness
evidence, repeated matched before/after measurements, a pushed merge, and a
recoverable checkpoint before the next mechanism is changed.

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
