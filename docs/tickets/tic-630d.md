---
id: tic-630d
status: open
type: investigation
assignee: blater
parent: tic-c7bb
delivery: evidence
tags:
    - performance
    - tpcc
    - 500tps
    - promotion
deps:
    - tic-bd79
created: 2026-09-04T15:10:08.245373Z
---
# Run the interleaved 500 TPS promotion campaign

Execute the accepted 500 TPS gate against a clean merged candidate and interleaved stable control.

## Design

Serialize host workloads, preserve all artifacts, investigate repeatable regressions at the nearest feature checkpoint, and report observed confidence rather than extrapolation.

## Acceptance Criteria

The predeclared lower-bound target passes with zero failed or unexplained outcomes and passing invariants; source, evidence, merge, checkpoint tag, and rollback point are pushed.
