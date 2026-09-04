---
id: tic-d2e9
status: open
type: story
priority: 1
assignee: blater
parent: tic-2109
delivery: code
tags:
    - riverd
    - operations
    - registry
deps:
    - tic-0803
created: 2026-09-04T15:23:11.538879Z
---
# Implement bounded registry, ps, and multiple instances

Publish and validate per-user ready-instance records and support independent instances without scanning the process table.

## Design

Use a digest of normalized data-directory path as the filename; publish atomically after readiness; validate bounded regular files; list only verified live records in deterministic order; warn but do not delete stale records.

## Acceptance Criteria

No-argument and ps behavior match; empty guidance, two-instance start/list/stop, port collision, same-directory lock contention, stale registry, and matching-record removal tests pass without signalling unrelated processes.
