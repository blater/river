---
id: tic-61c2
status: open
type: investigation
priority: 1
assignee: blater
parent: tic-9c58
delivery: evidence
tags:
    - performance
    - tpcc
    - comparison
    - architecture
    - sidecar
deps:
    - tic-45a7
created: 2026-09-04T15:23:20.082396Z
---
# Define the engine-neutral external comparison boundary

Define how a separate comparison utility consumes River-harness and other runner artifacts without living in River or importing river-harness implementation code.

## Design

Use a versioned engine-neutral result schema, process/file boundary, declared compatibility policy, and adapter tests. Database targets and stress execution remain outside the comparator core; river-harness remains a producer, not a linked library dependency.

## Acceptance Criteria

The sidecar repository and ticket are linked; the contract covers run identity, workload semantics, configuration equivalence, eligibility, outcomes, latency, throughput, provenance, and version mismatch; River and river-harness internals are absent from its dependency graph.
