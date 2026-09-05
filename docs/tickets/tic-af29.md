---
id: tic-af29
status: open
type: story
assignee: blater
parent: tic-5db4
delivery: code
tags:
    - performance
    - tpcc
    - p0
    - locks
    - observability
deps:
    - tic-5cc0
created: 2026-09-04T20:32:35.538355Z
---
# Classify successful lock blocking for P0

Expose the causal lock-block evidence required to explain the detected standard-mix 10:2 scaling regression before changing lock policy.

## Outcome

A bounded generic aggregate identifies which exact scheduler grant
preconditions caused successful lock blocks, and every aggregate block and
disposition reconciles without changing scheduler behavior. `tic-1dda`, not
this ticket, owns collection of the matched P0 workload evidence.

## In Scope / Owning Mechanism

The transaction scheduler's canonical grant predicate owns classification at
the point a request is actually blocked. One phase-scoped aggregate snapshot
exports the result through the existing diagnostics boundary.

## Non-goals

- Measure retained transaction snapshots; `tic-8e74` owns that cleanup gauge.
- Execute or interpret the two- and ten-terminal matrix, change lock policy, or
  claim a throughput improvement.
- Add TPC-C concepts to `river-tx`, a second grant predicate, an unbounded event
  stream, or a general metrics framework.

## Stop Conditions

Stop if classification would duplicate or approximate the scheduler predicate,
if disabled capture reads a clock or allocates, or if the aggregate cannot
reconcile exactly with actual blocks and terminal dispositions. If the scoped
dimensions cannot distinguish the dominant block class, retain that result and
open a separately reviewed diagnostic ticket; do not add dimensions in flight.

## Maximum Change Shape

One canonical predicate owner and one explicitly bounded aggregate structure
may change, plus the existing cold diagnostics adapter and focused tests.
Scheduler admission, fairness, lock lifetime, and transaction control flow must
remain equivalent in outcome; no parallel scheduler, classifier, or diagnostics
store is permitted.

## Design

Add bounded, allocation-stable, generic, phase-scoped aggregate classification
for every actual block by resource scope, requested mode, held or blocker mode,
ordinary or conversion or FIFO queue relationship, and the exact enforced
scheduler grant predicate. Scheduler admission and diagnostics share the
canonical predicate owner. Reconcile actual blocks, grants, timeouts,
cancellations, victims, and every bucket without TPC-C types in `river-tx`.
Detailed events remain explicitly bounded; disabled capture reads no clocks and
allocates nothing.

## Acceptance Criteria

Focused active-owner, FIFO-fairness, and conversion-priority tests prove exact
bucket selection, grant-predicate identity, overflow rejection, phase
separation, successful handoff, cancellation/victim separation, and zero
terminal transactions, locks, and waiters. Aggregate buckets sum exactly to
actual blocks and dispositions. A capture-disabled control proves zero
steady-state allocation, no clock reads, and unchanged grant outcomes. The
cold output contract supplies every declared dimension and reconciliation
total to `tic-1dda`; aggregate TPS alone admits no lock optimization.

## Notes

### 2026-09-04 ten-terminal architecture priority review

This remains an immediate P0 evidence prerequisite, not a throughput fix. It
does not remove a lock block. Because the implementation touches the canonical
scheduler predicate, acceptance also needs a matched capture-disabled
control/candidate check proving zero steady-state allocation and no repeated
throughput or latency regression. Enabled-capture results must be treated as
diagnostic evidence whose observer cost is reported, not as a capacity sample.
