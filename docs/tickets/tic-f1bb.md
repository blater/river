---
id: tic-f1bb
status: open
type: story
assignee: blater
parent: tic-e5ff
delivery: code
tags:
    - performance
    - tpcc
    - p1
    - wal
    - transactions
deps:
    - tic-b368
    - tic-6f81
links:
    - tic-32b3
created: 2026-09-04T15:10:07.446626Z
---
# Implement safe durability overlap and exact publication

Apply the accepted visibility-frontier design so eligible prepared writers can overlap durability instead of forcing almost every write independently.

## Outcome

One atomic end-to-end implementation of the accepted `tic-b368` state machine
allows eligible dependent work to progress before a predecessor's force
returns while preserving durable acknowledgement, exact publication, recovery,
and exactly-once cleanup. The mechanism must move force-per-write, useful
cohorting, or the specifically predicted lock-residence denominator.

## In Scope / Owning Mechanism

Extend the existing commit execution path, WAL durability dependency, and
publication frontier as one vertical mechanism. Existing transaction, WAL, and
publication owners retain their current responsibilities; all River-owned
callers and focused concurrency/recovery tests change together.

## Non-goals

- A second executor, commit queue, durability state machine, or publication
  path.
- WAL-format changes, lock-policy tuning, client/protocol work, retry tuning,
  or benchmark-specific behavior.
- Mere rearrangement of work after force has already returned.
- Unrelated transaction, WAL, or lifecycle cleanup.

## Stop Conditions

Do not begin if `tic-b368` does not identify safe pre-force dependent progress
and its acknowledgement dependency. Stop and reject the implementation if it
requires a prohibited parallel mechanism or cross-contract expansion. Reject
it after measurement if the declared force, cohort, or lock-residence
denominator does not move; do not retain complexity on an end-to-end TPS claim
alone.

## Maximum Change Shape

One indivisible vertical extension of the existing commit state machine and
path, even when it touches several owning modules. It must not be split into
module-local partial deliveries, leave an alternate path, add compatibility
behavior, or introduce more than one execution, queueing, durability, or
publication mechanism.

## Design

Install forced generations invisibly, advance visibility according to durable dependencies, acknowledge only at the required durability point, and release every lock and lease exactly once on all outcomes.

## Acceptance Criteria

Recovery and concurrency fault matrices pass; no dependent transaction is acknowledged ahead of observed WAL; measured cohorts and forces demonstrate the intended mechanism or identify the next blocker precisely.

## Notes

### 2026-09-04T19:20:01Z

Carry-over review docs/plans/billion-row-capacity-carryover-review.md requires focused faults for uncertain group durability and post-fence admission before implementation acceptance. The dirty worktree supplies scenarios only, not code.

### 2026-09-04 ten-terminal architecture priority review

This is the highest-leverage P1 throughput hypothesis and the highest-risk
correctness change. Current source already appends, forces, installs forced
generations invisibly, advances one frontier, and then releases transaction
state. Repeating that post-force sequence is not an optimization. The accepted
`tic-b368` design must let a blocked successor make progress before its
predecessor's force returns while binding every dependent acknowledgement to
the required durable frontier. Candidate evidence must show the intended
direction in forces per write and cohort distribution, together with the
predicted lock-residence change. If those denominators do not move, reject the
implementation rather than retain a second state machine or publication path.
