---
id: tic-ef07
status: open
type: epic
priority: 1
assignee: blater
delivery: none
tags:
    - workflow
    - tickets
    - coordination
created: 2026-09-04T16:49:13.901413Z
---
# Complete multi-worktree ticket workflow enforcement

Close the remaining gap between River's ticket working agreement and enforcement across concurrent worktrees and promotion.

## Design

Ticket repository owns claim mechanics; River owns its promotion checks. Cross-repository delivery uses immutable external references rather than copied implementation.

## Acceptance Criteria

Worktree claims cannot race or be silently stolen; River promotion rejects invalid branch/commit/ticket linkage; recovery and bypass procedures are explicit and tested.
