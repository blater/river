---
id: tic-6f81
status: open
type: story
assignee: blater
parent: tic-e5ff
delivery: code
tags:
    - performance
    - tpcc
    - p1
    - wal
deps:
    - tic-5b3e
created: 2026-09-04T15:10:07.351684Z
---
# Append large cohort WAL through budget-derived chunks

Replace implementation-sized pending-record retention with resource-admitted chunked WAL reservations shared by direct and group commits.

## Design

Encode into provider-owned reservations and preserve one durable decision, force result, and publication outcome even when a logical cohort spans chunks.

## Acceptance Criteria

Boundary, force-failure, indeterminate outcome, recovery, cancellation, and allocation tests pass; WAL bytes and forces reconcile; no guessed transaction or record limit is introduced.
