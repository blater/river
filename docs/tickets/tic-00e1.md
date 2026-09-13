---
id: tic-00e1
status: closed
type: investigation
priority: 1
assignee: blater
parent: tic-723f
delivery: evidence
evidence:
    - docs/plans/performance-three-epics-handover.md
tags:
    - performance
    - tpcc
    - p2
    - payment
    - protocol
deps: []
created: 2026-09-04T15:10:07.819726Z
resolution: superseded
superseded-by:
    - tic-edoras
superseded-deps:
  - tic-7a5a
---
# Prove complete Payment transaction-program semantics

Determine how every Payment branch, including non-unique last-name lower-median selection, is represented without changing transaction semantics.

### Design

Review ROW_SET/value dependency and ordered-selection needs against the shared transaction-program protocol. A branch-only diagnostic may be proposed but cannot stand in for full Payment.

### Acceptance Criteria

A reviewed mapping covers all branches, isolation, business rollback, result shapes, and failure outcomes; any protocol gap is named with one owner and focused tests.

### Notes

### 2026-09-04 ten-terminal architecture priority review

This is a semantic design gate after P1, not a performance result. Prefer a
mapping through the existing program and relational owners. If correct
last-name lower-median selection requires a broad new protocol abstraction or
a second executor, stop and compare that complexity with the Payment-only
benefit before authorizing `tic-af0a`.

### 2026-09-13 performance realignment

Superseded by `tic-edoras`. Original scope above is retained as historical context, not the active execution contract. No code or performance result is certified by this closure. The delivered artifact is the reviewed backlog disposition in [the handover](../plans/performance-three-epics-handover.md). Pending consumers/dependencies are explicitly mapped there.
