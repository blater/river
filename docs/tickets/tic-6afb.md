---
id: tic-6afb
status: open
type: story
assignee: blater
parent: tic-5db4
delivery: code
tags:
    - p0
    - concurrency
    - architecture
    - locks
deps:
    - tic-8ef7
created: 2026-09-04T19:40:44.256831Z
---
# Centralize exact-lock grant predicates and prove fairness liveness

Remove duplicate exact-lock grant/fairness policy so scheduling, blocker discovery, and cycle validation cannot drift.

## Design

Introduce one transaction-layer owner for the exact scheduler grant preconditions and make scheduler admission, blocker-edge construction, and cycle self-validation consume it. Preserve active-owner, conversion-priority, and FIFO distinctions. Add deterministic exact-resource FIFO and conversion liveness tests. Do not alter fairness policy unless a focused test first proves the current policy defective.

## Acceptance Criteria

No exact grant predicate is reimplemented in the scheduler, blocker cursor, or validator; every diagnostic edge names the canonical evaluated precondition; active-owner, compatible-reader FIFO, conversion priority, wakeup, starvation/liveness, and invalid-edge tests pass; river-tx remains benchmark-agnostic and allocation-stable; affected module tests pass.
