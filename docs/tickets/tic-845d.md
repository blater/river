---
id: tic-845d
status: open
type: story
assignee: blater
parent: tic-e5ff
delivery: code
tags:
    - performance
    - tpcc
    - p1
    - locks
deps:
    - tic-4d14
created: 2026-09-04T15:10:07.636141Z
---
# Remove lock holdings proved redundant by the scope audit

Remove only lock acquisition or retention that the accepted audit proves redundant under the declared serializable contract.

## Outcome

Remove exactly one holding, acquisition, or retention rule selected by the
accepted `tic-4d14` audit, with the predicted lock denominator changing and all
serializable, failure, and cleanup behavior preserved. This ticket is not
implementation-ready until that single rule and its predicted effect are named
in the accepted audit.

## In Scope / Owning Mechanism

Only the canonical transaction-layer lock-policy rule selected by `tic-4d14`,
its River-owned callers, focused correctness tests, and mechanism evidence are
in scope. The ticket owns no other lock class or policy decision.

## Non-goals

- Removing a second holding, acquisition, or retention rule.
- Table escalation, weaker isolation, retry inflation, fairness changes, or a
  benchmark-specific kernel branch.
- Commit, WAL, durability-overlap, protocol, or workload redesign.
- General lock-manager cleanup not required to remove the selected rule.

## Stop Conditions

Close without implementation if `tic-4d14` selects no credible candidate. Stop
and split out newly discovered candidates rather than adding them here. Reject
the implementation if the declared holding, blocked-time, or service-cost
denominator does not move as predicted, or if correctness requires one of the
excluded policy changes.

## Maximum Change Shape

One canonical lock-policy rule removed or narrowed, with only the directly
owned caller and test changes needed to make that removal complete. No second
rule, policy owner, lock mode, compatibility path, or alternative lock manager
may be introduced.

## Design

Change the canonical lock-policy owner and all River callers together. Do not add table escalation, weaker isolation, retry inflation, or a benchmark-specific kernel branch.

## Acceptance Criteria

Serializable anomaly, fairness, cleanup, allocation, and workload tests pass; holding counts fall as predicted; force and failure-mode metrics remain separately reconciled; repeated matched samples show no unexplained regression.

## Notes

### 2026-09-04 ten-terminal architecture priority review

This is a direct but probably bounded performance mechanism. Before editing,
declare the expected change in holding count, blocked-time bucket, and
acquisition or release stage. Overall TPS may remain flat when the existing
roughly one-force-per-write ceiling becomes dominant; that is an acceptable
bottleneck shift when the predicted lock denominator moves and latency,
failures, and retries do not regress. A lower holding count without the
predicted service or blocking effect is ineffective and must not be promoted.
