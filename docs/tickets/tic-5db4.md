---
id: tic-5db4
status: open
type: epic
assignee: blater
parent: tic-30c3
delivery: none
tags:
    - performance
    - tpcc
    - p0
    - concurrency
created: 2026-09-04T14:59:38.314611Z
---
# P0: close concurrency correctness and scaling guard

Close the remaining P0 promotion evidence for isolation, retry/deadlock causality, cleanup, liveness, and concurrency scaling.

## Design

Use docs/perf_review.md P0 acceptance gate. Throughput cannot waive a false cycle, unexplained retry, cleanup residue, timeout, or failure-mode displacement.

## Acceptance Criteria

The stable-source discriminator and standard-mix matrix pass every absolute correctness criterion and the predeclared performance regression guard.
