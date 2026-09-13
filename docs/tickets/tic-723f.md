---
id: tic-723f
status: closed
type: epic
priority: 1
assignee: blater
parent: tic-30c3
delivery: none
tags:
    - performance
    - tpcc
    - p2
    - p3
    - protocol
    - execution
deps: []
created: 2026-09-04T14:59:38.49607Z
resolution: superseded
superseded-by:
    - tic-carcharoth
    - tic-primula
superseded-deps:
  - tic-e5ff
---
# P2-P3: remove protocol and demonstrated execution cost

Collapse the proven chatty transaction-family path, then optimize only inclusive CPU, allocation, copy, and row-publication costs admitted by measured-phase profiles.

### Design

Start with the full Payment semantics pilot from docs/perf_review.md. Do not fan out transaction programs or create a P3 mechanism story until evidence identifies its owner and denominator.

### Acceptance Criteria

Payment receives a paired semantic A/B; measured-phase profiles rank remaining inclusive costs; each accepted optimization has mechanism proof, matched performance evidence, and unchanged ownership and validation boundaries.

### 2026-09-13 performance realignment

Superseded by `tic-carcharoth`, `tic-primula`. Original scope above is retained as historical context, not the active execution contract. No code or performance result is certified by this closure. The delivered artifact is the reviewed backlog disposition in [the handover](../plans/performance-three-epics-handover.md). Pending consumers/dependencies are explicitly mapped there.
