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
created: 2026-09-04T15:10:07.080647Z
---
# Design the durable visibility frontier and dependency fencing

Specify how dependent reads and writes remain ordered while commit durability is overlapped across prepared transactions.

## Design

Define sequence assignment, WAL dependency, visibility, acknowledgement, force failure, cancellation, fencing, recovery, and exactly-once publication using the existing transaction and WAL owners.

## Acceptance Criteria

Independent concurrency and recovery review accepts a concrete state machine and failure matrix; no lock is released before an equivalent durable dependency contract protects observed data.
