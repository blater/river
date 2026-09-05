---
id: tic-b368
status: open
type: investigation
assignee: blater
parent: tic-e5ff
delivery: evidence
tags:
    - performance
    - tpcc
    - p1
    - wal
    - correctness
deps:
    - tic-1dda
links:
    - tic-32b3
created: 2026-09-04T15:10:07.080647Z
---
# Design the durable visibility frontier and dependency fencing

Specify how dependent reads and writes remain ordered while commit durability is overlapped across prepared transactions.

## Outcome

One accepted durability and visibility state machine names the pre-force
transition that permits dependent progress, the dependency inherited by that
progress, and every acknowledgement, failure, restart, cancellation, fencing,
and publication outcome. This ticket produces design evidence only and either
authorizes `tic-f1bb` or records why safe overlap is not available.

## In Scope / Owning Mechanism

The existing transaction commit coordinator owns transaction state and
acknowledgement; the existing WAL owner owns append, force, and durable
frontiers; the existing publication owner controls visibility. Specify their
single shared dependency contract, transition table, invariants, and failure
matrix without introducing another owner.

## Non-goals

- Production-code or test implementation.
- Queue, batching, lock-policy, retry, or benchmark tuning.
- A new WAL format, client/protocol contract, or alternative commit path.
- General transaction-state cleanup unrelated to durability overlap.

## Stop Conditions

Stop without authorizing `tic-f1bb` if the design cannot allow a dependent
transaction to make progress before its predecessor's force returns, or cannot
bind every dependent acknowledgement to the required durable frontier. Record
missing prerequisite evidence or separately owned contracts as blockers; do
not absorb their implementation here.

## Maximum Change Shape

One evidence-only design comprising one canonical state machine, one invariant
set, and one failure/recovery matrix. No production source, build logic,
benchmark tooling, or runtime configuration may change under this ticket.

## Design

Define sequence assignment, WAL dependency, visibility, acknowledgement, force failure, cancellation, fencing, recovery, and exactly-once publication using the existing transaction and WAL owners.

## Acceptance Criteria

Independent concurrency and recovery review accepts a concrete state machine and failure matrix; no lock is released before an equivalent durable dependency contract protects observed data.

## Notes

### 2026-09-04T19:20:01Z

Carry-over review docs/plans/billion-row-capacity-carryover-review.md identifies decision-appended cancellation versus fencing and post-fence transaction admission as explicit state-machine cases. Derive behavior from the accepted durability/visibility contract; do not import the dirty conditionals.

### 2026-09-04 ten-terminal architecture priority review

This is the first P1 design decision after P0. The accepted state machine must
name the pre-force transition at which a lock-blocked successor may execute or
enqueue while inheriting a durability dependency from its predecessor. It must
cover dependent read-only and write acknowledgements, the irreversible point
after decision append, cancellation, force failure, restart, and fencing. If
all transaction locks remain held until force completes, the design has not
created durability overlap and must not authorize `tic-f1bb`.
