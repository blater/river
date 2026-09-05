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
links:
    - tic-32b3
created: 2026-09-04T15:10:07.987949Z
---
# Capture a measured-phase inclusive server profile

Rank the next server CPU, allocation, copy, I/O-wait, lock, and row-publication costs after P1 and the Payment pilot.

## Design

Profile measurement only, quantify profiler overhead, use inclusive stacks and allocation/copy counters, and pair the same source/database image with a non-instrumented control.

## Acceptance Criteria

The top material mechanism has a stable denominator and owning boundary; startup/load/checkpoint work is excluded; a specific follow-up story is created only when mechanism evidence and a candidate test are defined.

## Notes

### 2026-09-04T19:20:01Z

Carry-over review docs/plans/billion-row-capacity-carryover-review.md records an unintegrated constructor-owned page-frame payload-view candidate. Reproduce and attribute the allocation on stable source before creating any P3 implementation story; the dirty code is not accepted evidence.

### 2026-09-04 ten-terminal architecture priority review

This is the mandatory admission gate for every later CPU, allocation, buffer,
page, index, or publication optimization. The parked `tic-2828` remote-branch
frame-reuse candidate is the negative example: it changed cache policy to
remove a bounded warm-up allocation without proving measured-phase materiality,
and both candidate TPS samples were below their immediate-parent controls. Do
not import or redesign that mechanism unless this profile first attributes a
material stable-source cost to the same owner and denominator.
