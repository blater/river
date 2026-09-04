---
id: tic-af29
status: in_progress
type: story
assignee: blater
parent: tic-5db4
delivery: code
base-commit: 9f756561f79d1ad0952c0ff4d38c07f670badd31
branch: ticket/tic-af29-lock-block-causality
evidence:
    - docs/delivery/evidence/2026-09-04-tic-af29-lock-block-causality.md
tags:
    - performance
    - tpcc
    - p0
    - locks
    - observability
created: 2026-09-04T20:32:35.538355Z
---
# Classify successful lock blocking for P0

Expose the causal lock-block evidence required to explain the detected standard-mix 10:2 scaling regression before changing lock policy.

## Design

Add bounded, allocation-stable, generic, phase-scoped aggregate classification for every actual block by resource scope, requested mode, held or blocker mode, ordinary or conversion or FIFO queue relationship, and the exact enforced scheduler grant predicate. Scheduler admission and diagnostics share the canonical predicate owner. Separately expose terminal retained-snapshot count and reconcile actual blocks, grants, timeouts, cancellations, victims, and every bucket without TPC-C types in river-tx. Detailed events remain explicitly bounded; disabled capture reads no clocks and allocates nothing. Use focused two- and ten-terminal standard diagnostics to attribute the detected loss.

## Acceptance Criteria

Focused active-owner, FIFO-fairness, and conversion-priority tests prove exact bucket selection, grant-predicate identity, overflow rejection, phase separation, successful handoff, cancellation/victim separation, and zero terminal snapshots, transactions, locks, and waiters. Aggregate buckets sum exactly to actual blocks and dispositions. Retained matched two- and ten-terminal standard diagnostics reconstruct the dominant successful-block cause; aggregate TPS alone admits no lock optimization.

## Notes

### 2026-09-04 implementation candidate

The scheduler-owned causal aggregate, terminal retained-snapshot gauge, exact
reconciliation, focused tests, and disabled-capture allocation guard are
implemented. Focused transaction and propagation tests pass. The affected
transaction and bench module suites pass; an engine savepoint-capacity test
fails identically at the exact pre-ticket source and is retained as an
unrelated pre-existing failure.

The ticket remains `in_progress`. Its required matched two- and ten-terminal
standard diagnostics must wait for the `tic-0636` provenance runner to merge,
then run from one exact clean candidate with the build/workload lane exclusively
granted. No lock-policy optimization is authorized by this implementation.
