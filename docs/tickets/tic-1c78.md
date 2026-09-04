---
id: tic-1c78
status: in_progress
type: story
priority: 1
assignee: blater
parent: tic-ef07
delivery: documentation
base-commit: a2301a376666638b3494e57279be9c1674ac248c
branch: ticket/tic-1c78-priority-kanban
tags:
    - workflow
    - backlog
    - planning
created: 2026-09-04T17:50:05.26655Z
---
# Publish visible backlog priority Kanban

Add a checked-in human-readable execution queue over the source-controlled ticket graph.

## Design

Keep ticket status and dependencies authoritative; summarize the immediate ready frontier, ordered delivery lanes, promotion gates, and update rules without duplicating ticket acceptance criteria.

## Acceptance Criteria

The document identifies the current P0 queue, riverd prerequisite chain, performance P0-P4 route, 500 TPS and parity gates, external-tool ownership boundaries, and a deterministic refresh procedure; README and manifesto link to it.

