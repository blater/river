---
id: tic-e5ff
status: open
type: epic
assignee: blater
parent: tic-30c3
delivery: none
tags:
    - performance
    - tpcc
    - p1
    - transactions
    - wal
deps:
    - tic-5db4
created: 2026-09-04T14:59:38.401234Z
---
# P1: break the serialized commit and lock ceiling

Reduce the independently measured singleton-force and broad lock-ownership ceilings without weakening serializable isolation or acknowledged durability.

## Design

Implement the smallest scalable P1 route in docs/perf_review.md: logical sealing, resource admission, cumulative cohort reservation, canonical physical staging, chunked WAL, durable publication, and exact cleanup.

## Outcome

P1 closes only when its child tickets preserve one end-to-end commit mechanism,
pass correctness and recovery gates, and either move a declared ten-terminal
denominator or explicitly expose the next measured ceiling. This epic records
that integrated decision; it does not implement it.

## In Scope / Owning Mechanism

The epic owns child ordering, dependency boundaries, the shared P1 invariants,
the declared performance denominators, and the final promotion decision. Each
child ticket owns exactly one design, audit, enabler, implementation, or
evidence responsibility; production ownership remains with those tickets and
the canonical commit-path components they name.

## Non-goals

- Production implementation, opportunistic fixes, or use as a catch-all for
  discoveries made while executing a child ticket.
- P2 protocol collapse, P3 profiling-driven optimization, benchmark-family
  semantics in production, or a broad transaction/WAL redesign.
- Duplicating child acceptance criteria or allowing one child to absorb another
  child's mechanism for delivery convenience.

## Stop Conditions

- Do not begin a production P1 child until its prerequisites and scope contract
  are satisfied. A new prerequisite blocks that child; independent work becomes
  a separately ordered follow-up rather than expanding it.
- Stop promotion on an unexplained regression, failed invariant, retry or
  cleanup-accounting gap, or a direct optimization that moves none of its
  declared denominators.
- Close an audit-first enabler without implementation when current-source
  verification proves its contract; do not invent replacement work to keep the
  epic active.

## Maximum Change Shape

This epic may change only planning, dependencies, aggregate evidence, and the
promotion decision. It may not carry production code. No child may introduce a
second executor, writer, queue, logical representation, WAL path, durability
state machine, transaction outcome, or compatibility route; a required new
owner is a separate architectural decision.

## Acceptance Criteria

P1 funnel and stage metrics reconcile; eligible writes form effective cohorts or the remaining force path is explicitly explained; lock footprint is semantically justified; correctness and recovery gates pass.

## Notes

### 2026-09-04 ten-terminal architecture priority review

Prioritize `tic-b368` and `tic-4d14` as the first post-P0 decisions. Treat
`tic-ca05`, `tic-5b3e`, and `tic-6f81` as preparation, scale, and failure-safety
enablers rather than independent TPS fixes. The direct P1 performance
hypotheses are the lock work proved removable by `tic-845d` and the real
pre-force durability overlap required from `tic-f1bb`. Exposing WAL force,
physical preflight, or relational execution as the next ceiling is acceptable;
retaining a mechanism that moves no declared denominator is not.
