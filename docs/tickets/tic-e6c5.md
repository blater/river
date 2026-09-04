---
id: tic-e6c5
status: open
type: story
priority: 1
assignee: blater
parent: tic-9c58
delivery: code
tags:
    - performance
    - tpcc
    - mariadb
    - parity
    - harness
deps:
    - tic-46ec
created: 2026-09-04T15:10:08.429494Z
---
# Make the River and MariaDB TPC-C pair comparison-eligible

Close the evidenced compatibility gap so the shared harness can execute the same logical workload and comparison key on River and MariaDB.

## Design

Implement the missing behavior in its semantic owner, or correct the harness contract in its repository, without target-specific workload semantics or weakened River validation.

## Acceptance Criteria

Matched smoke and targeted family runs pass setup, workload, invariants, and outcome reconciliation on both targets; result.json reports eligibility=eligible and identical comparison keys.
