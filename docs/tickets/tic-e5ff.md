---
id: tic-e5ff
status: open
type: epic
assignee: blater
parent: tic-30c3
delivery: none
tags:
    - performance
    - tpcc
    - p1
    - transactions
    - wal
deps:
    - tic-5db4
created: 2026-09-04T14:59:38.401234Z
---
# P1: break the serialized commit and lock ceiling

Reduce the independently measured singleton-force and broad lock-ownership ceilings without weakening serializable isolation or acknowledged durability.

## Design

Implement the smallest scalable P1 route in docs/perf_review.md: logical sealing, resource admission, cumulative cohort reservation, canonical physical staging, chunked WAL, durable publication, and exact cleanup.

## Acceptance Criteria

P1 funnel and stage metrics reconcile; eligible writes form effective cohorts or the remaining force path is explicitly explained; lock footprint is semantically justified; correctness and recovery gates pass.
