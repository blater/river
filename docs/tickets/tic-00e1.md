---
id: tic-00e1
status: open
type: investigation
priority: 1
assignee: blater
parent: tic-723f
delivery: evidence
tags:
    - performance
    - tpcc
    - p2
    - payment
    - protocol
deps:
    - tic-7a5a
created: 2026-09-04T15:10:07.819726Z
---
# Prove complete Payment transaction-program semantics

Determine how every Payment branch, including non-unique last-name lower-median selection, is represented without changing transaction semantics.

## Design

Review ROW_SET/value dependency and ordered-selection needs against the shared transaction-program protocol. A branch-only diagnostic may be proposed but cannot stand in for full Payment.

## Acceptance Criteria

A reviewed mapping covers all branches, isolation, business rollback, result shapes, and failure outcomes; any protocol gap is named with one owner and focused tests.
