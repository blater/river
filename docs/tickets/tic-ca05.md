---
id: tic-ca05
status: open
type: story
assignee: blater
parent: tic-e5ff
delivery: code
tags:
    - performance
    - tpcc
    - p1
    - transactions
    - admission
deps:
    - tic-1dda
created: 2026-09-04T15:10:07.168483Z
---
# Seal and admit immutable logical commits before enqueue

Move only pure logical sealing, result admission, long-valued sizing, and resource-budget admission ahead of the commit queue.

## Design

Use session-owned chunked storage and authenticated resource receipts. Do not assign commit sequences, stage physical pages, append WAL, or publish data in this phase; direct and group policy share the same owner.

## Acceptance Criteria

Focused success, cancellation, impossible-request, transient-pressure, retry, and cleanup tests pass; admitted demand is stable through publication or abort; matched TPS and stage evidence show no unexplained regression.
