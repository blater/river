---
id: tic-da4e
status: open
type: investigation
priority: 1
assignee: blater
parent: tic-723f
delivery: evidence
tags:
    - performance
    - tpcc
    - p3
    - profiling
deps:
    - tic-af0a
created: 2026-09-04T15:10:07.987949Z
---
# Capture a measured-phase inclusive server profile

Rank the next server CPU, allocation, copy, I/O-wait, lock, and row-publication costs after P1 and the Payment pilot.

## Design

Profile measurement only, quantify profiler overhead, use inclusive stacks and allocation/copy counters, and pair the same source/database image with a non-instrumented control.

## Acceptance Criteria

The top material mechanism has a stable denominator and owning boundary; startup/load/checkpoint work is excluded; a specific follow-up story is created only when mechanism evidence and a candidate test are defined.
