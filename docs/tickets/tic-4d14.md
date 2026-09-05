---
id: tic-4d14
status: open
type: investigation
assignee: blater
parent: tic-e5ff
delivery: evidence
tags:
    - performance
    - tpcc
    - p1
    - locks
deps:
    - tic-1dda
created: 2026-09-04T15:10:07.543963Z
---
# Audit serializable lock scope and retention by workload step

Explain the roughly 79 holdings per standard-mix write and the larger New Order footprint without embedding TPC-C types in the transaction layer.

## Outcome

A reconciled, ranked audit classifies every material lock holding by necessity,
blocked time, and acquisition/release cost, then selects at most one first
removal candidate for `tic-845d`. It is valid for the audit to conclude that no
removal has a credible performance benefit.

## In Scope / Owning Mechanism

The canonical transaction-layer serializable lock policy remains the only
owner of acquisition and retention semantics. Benchmark/session diagnostics
may map opaque tags to workload steps solely to explain that policy and
reconcile aggregate evidence.

## Non-goals

- Removing, weakening, escalating, or otherwise changing any lock rule.
- Changing isolation, retry behavior, fairness, deadlock policy, or workload
  semantics.
- Moving benchmark-family knowledge into transaction or storage internals.
- Selecting multiple implementation candidates for one follow-on ticket.

## Stop Conditions

If evidence cannot reconcile material holding classes or distinguish blocked
time and service cost, stop and request the missing diagnostic evidence rather
than infer a removal. If no redundant rule has a credible blocking or CPU
effect, close the audit with no candidate and do not start `tic-845d`.

## Maximum Change Shape

One evidence package and ranked decision covering the existing canonical lock
policy, with zero production behavior change and at most one named first
removal candidate. Every additional candidate becomes a separate ticket after
this audit is accepted.

## Design

At the benchmark/session boundary map opaque diagnostic tags to logical steps, then justify KEY, RANGE, TUPLE_KEY, and TUPLE_RANGE acquisition and retention against one canonical serializable policy.

## Acceptance Criteria

Every material holding class and step is classified as required, duplicate, or unnecessarily retained with anomaly and phantom reasoning; aggregate counts reconcile and any proposed removal has a focused correctness test plan.

## Notes

### 2026-09-04 ten-terminal architecture priority review

Run this as the first P1 lock decision after P0 and before any lock-removal
code. Rank candidates by successful blocked time and acquisition/release CPU in
addition to raw holding count. A numerous holding class that neither blocks nor
consumes material service time is not sufficient evidence for `tic-845d`.
