---
id: tic-f1bb
status: open
type: story
assignee: blater
parent: tic-e5ff
delivery: code
tags:
    - performance
    - tpcc
    - p1
    - wal
    - transactions
deps:
    - tic-b368
    - tic-6f81
links:
    - tic-32b3
created: 2026-09-04T15:10:07.446626Z
---
# Implement safe durability overlap and exact publication

Apply the accepted visibility-frontier design so eligible prepared writers can overlap durability instead of forcing almost every write independently.

## Design

Install forced generations invisibly, advance visibility according to durable dependencies, acknowledge only at the required durability point, and release every lock and lease exactly once on all outcomes.

## Acceptance Criteria

Recovery and concurrency fault matrices pass; no dependent transaction is acknowledged ahead of observed WAL; measured cohorts and forces demonstrate the intended mechanism or identify the next blocker precisely.

## Notes

### 2026-09-04T19:20:01Z

Carry-over review docs/plans/billion-row-capacity-carryover-review.md requires focused faults for uncertain group durability and post-fence admission before implementation acceptance. The dirty worktree supplies scenarios only, not code.
