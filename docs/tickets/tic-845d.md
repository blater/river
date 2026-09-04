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

## Design

Change the canonical lock-policy owner and all River callers together. Do not add table escalation, weaker isolation, retry inflation, or a benchmark-specific kernel branch.

## Acceptance Criteria

Serializable anomaly, fairness, cleanup, allocation, and workload tests pass; holding counts fall as predicted; force and failure-mode metrics remain separately reconciled; repeated matched samples show no unexplained regression.
