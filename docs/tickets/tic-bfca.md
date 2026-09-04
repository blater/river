---
id: tic-bfca
status: open
type: investigation
assignee: blater
parent: tic-761e
delivery: evidence
tags:
    - riverd
    - benchmark
    - harness
    - integration
deps:
    - tic-4cb6
created: 2026-09-04T15:23:11.996038Z
---
# Verify external river-harness migration to riverd

Verify that river-harness, in its own repository, uses only the installed riverd process and published consumer contract for River lifecycle.

## Design

The harness remains the engine-independent stress/workload runner. Its own ticket and commits delete River Gradle invocation, classpath parsing, Java main knowledge, process-class inspection, and private security coupling.

## Acceptance Criteria

A linked external commit runs focused and wider River workloads through riverd, records executable fingerprint and PID, authenticates from file paths, stops the exact child, and passes invariants without importing River internals.
