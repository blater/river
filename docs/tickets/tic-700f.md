---
id: tic-700f
status: in_progress
type: story
assignee: blater
parent: tic-e1c9
delivery: documentation
base-commit: 11aa75c6c6adf885cdad6d130bcbb4f59b92eb1c
branch: ticket/tic-700f-riverd-backlog
tags:
    - backlog
    - riverd
    - benchmark
    - architecture
created: 2026-09-04T15:21:00.761395Z
---
# Add riverd backlog and decouple comparison workflow

Structure the standalone riverd and authentication work, make riverd a prerequisite for benchmark promotion, and keep cross-database comparison tooling outside River and decoupled from river-harness internals.

## Design

Use docs/plans/riverd-standalone-server-plan.md as the launcher authority. River-harness owns stress execution; an external sidecar comparator consumes stable artifacts; River owns only River behavior and exported contracts.

## Acceptance Criteria

riverd/auth epics and stories cover the accepted plan; 500 TPS and parity tickets depend on riverd; comparison tickets contain no River-core or harness-internal implementation ownership; tk validate and dependency checks pass.
