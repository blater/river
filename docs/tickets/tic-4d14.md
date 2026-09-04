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

## Design

At the benchmark/session boundary map opaque diagnostic tags to logical steps, then justify KEY, RANGE, TUPLE_KEY, and TUPLE_RANGE acquisition and retention against one canonical serializable policy.

## Acceptance Criteria

Every material holding class and step is classified as required, duplicate, or unnecessarily retained with anomaly and phantom reasoning; aggregate counts reconcile and any proposed removal has a focused correctness test plan.
