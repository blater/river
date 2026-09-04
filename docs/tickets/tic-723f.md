---
id: tic-723f
status: open
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
deps:
    - tic-e5ff
created: 2026-09-04T14:59:38.49607Z
---
# P2-P3: remove protocol and demonstrated execution cost

Collapse the proven chatty transaction-family path, then optimize only inclusive CPU, allocation, copy, and row-publication costs admitted by measured-phase profiles.

## Design

Start with the full Payment semantics pilot from docs/perf_review.md. Do not fan out transaction programs or create a P3 mechanism story until evidence identifies its owner and denominator.

## Acceptance Criteria

Payment receives a paired semantic A/B; measured-phase profiles rank remaining inclusive costs; each accepted optimization has mechanism proof, matched performance evidence, and unchanged ownership and validation boundaries.
